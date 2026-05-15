package com.oilquiz.app.ai.agent;

import android.app.Activity;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class UnifiedAgentEngine {

    private static final String TAG = "UnifiedAgentEngine";

    public enum ReasoningMode {
        AUTO("自动选择"),
        REACT("ReAct推理"),
        CHAIN_OF_THOUGHT("链式思维"),
        PLAN_EXECUTE("计划执行"),
        DIRECT("直接生成");

        public final String displayName;
        ReasoningMode(String displayName) { this.displayName = displayName; }
    }

    public interface AgentCallback {
        void onToken(String token);
        void onThinkingToken(String token);
        void onThinkingEnd();
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, AgentService.ToolResult result);
        void onStepUpdate(String step, String detail);
        void onComplete(String fullText);
        void onError(String error);
    }

    private final Activity activity;
    private final AIService aiService;
    private final AgentService agentService;
    private final SmartIntentRecognizer intentRecognizer;
    private final ExecutorService executor;
    private final Object lock = new Object();

    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicInteger toolLoopCount = new AtomicInteger(0);
    private final AtomicInteger iterationCount = new AtomicInteger(0);
    private final AtomicReference<StringBuilder> currentResponse = new AtomicReference<>(new StringBuilder());
    private final AtomicReference<StringBuilder> currentThinking = new AtomicReference<>(new StringBuilder());
    private final AtomicBoolean isInThinking = new AtomicBoolean(false);
    private final List<String> conversationContext = Collections.synchronizedList(new ArrayList<>());

    private ReasoningMode currentMode = ReasoningMode.AUTO;
    private int maxToolLoops = 5;
    private AgentCallback callback;

    private static final int MAX_CONTEXT_ENTRIES = 20;
    private static final int MAX_ENTRY_LENGTH = 1000;
    private static final int MAX_CONTEXT_CHARS = 12000;
    private static final int TOOL_RESULT_MAX_LENGTH = 1500;
    private static final int SUMMARY_TRIGGER_ENTRIES = 15;

    public UnifiedAgentEngine(Activity activity, AIService aiService, AgentService agentService) {
        this.activity = activity;
        this.aiService = aiService;
        this.agentService = agentService;
        this.intentRecognizer = SmartIntentRecognizer.getInstance(activity);
        this.intentRecognizer.setAgentService(agentService);
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "UnifiedAgent-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
    }

    public void setCallback(AgentCallback callback) {
        this.callback = callback;
    }

    public void setReasoningMode(ReasoningMode mode) {
        this.currentMode = mode;
    }

    public void setMaxToolLoops(int max) {
        this.maxToolLoops = Math.max(1, Math.min(10, max));
    }

    public SmartIntentRecognizer.IntentResult analyzeIntent(String message) {
        return intentRecognizer.recognize(message);
    }

    public SmartIntentRecognizer.IntentResult analyzeIntentWithContext(String message) {
        return intentRecognizer.recognizeWithContext(message, getContextSummary());
    }

    public ReasoningMode selectBestMode(SmartIntentRecognizer.IntentResult intent) {
        if (currentMode != ReasoningMode.AUTO) return currentMode;

        switch (intent.intent) {
            case WEATHER:
            case SEARCH:
            case QUIZ:
            case DATABASE:
            case OCR:
            case CALCULATOR:
                return ReasoningMode.REACT;
            case CREATIVE:
            case ANALYSIS:
                return ReasoningMode.CHAIN_OF_THOUGHT;
            case LEARNING:
                return ReasoningMode.CHAIN_OF_THOUGHT;
            case TRANSLATE:
            case CHAT:
            case FILE:
            case WEB:
                return ReasoningMode.DIRECT;
            default:
                return ReasoningMode.REACT;
        }
    }

    public void execute(String message, int maxTokens) {
        execute(message, maxTokens, true);
    }

    public void execute(String message, int maxTokens, boolean enableThinking) {
        if (isGenerating.getAndSet(true)) {
            notifyError("正在生成中，请等待完成");
            return;
        }

        isCancelled.set(false);
        toolLoopCount.set(0);
        iterationCount.set(0);
        currentResponse.set(new StringBuilder());
        currentThinking.set(new StringBuilder());
        isInThinking.set(enableThinking);

        conversationContext.add("用户: " + truncateForContext(message));

        SmartIntentRecognizer.IntentResult intent = analyzeIntentWithContext(message);
        ReasoningMode mode = selectBestMode(intent);

        AILogger.i(TAG, "Execute: intent=" + intent.intent.id + " conf=" + intent.confidence
            + " mode=" + mode.name() + " needsTool=" + intent.needsTool());

        String contextHint = buildContextEnhancedHint(intent);
        String fullMessage = contextHint + message;

        String intentHint = "";
        if (intent.needsTool() && intent.confidence >= 0.5) {
            String recommendedTool = intentRecognizer.getRecommendedTool(intent.intent);
            if (recommendedTool != null) {
                intentHint = "\n[提示] 用户可能需要使用工具: " + recommendedTool;
                if (intent.extractedEntity != null) {
                    intentHint += "，关键信息: " + intent.extractedEntity;
                }
                intentHint += "（你也可以选择不使用工具直接回答，或使用其他更合适的工具）\n";
            }
        }

        notifyStep("意图识别", intent.intent.displayName + " (置信度:" + String.format("%.0f%%", intent.confidence * 100) + ") → " + mode.displayName);

        switch (mode) {
            case REACT:
                executeReActLoop(fullMessage + intentHint, maxTokens, enableThinking);
                break;
            case CHAIN_OF_THOUGHT:
                executeCoTLoop(fullMessage + intentHint, maxTokens, enableThinking);
                break;
            case PLAN_EXECUTE:
                executePlanLoop(fullMessage + intentHint, maxTokens, enableThinking);
                break;
            case DIRECT:
            default:
                executeDirect(fullMessage + intentHint, maxTokens, enableThinking);
                break;
        }
    }

    private String buildContextEnhancedHint(SmartIntentRecognizer.IntentResult intent) {
        if (conversationContext.size() <= 2) return "";

        StringBuilder hint = new StringBuilder();
        hint.append("[上下文信息]\n");
        hint.append("当前是第").append(conversationContext.size() / 2 + 1).append("轮对话。\n");

        List<String> recentObservations = new ArrayList<>();
        List<String> recentReplies = new ArrayList<>();
        synchronized (conversationContext) {
            for (int i = conversationContext.size() - 1; i >= 0 && recentObservations.size() < 3; i--) {
                String entry = conversationContext.get(i);
                if (entry.startsWith("Observation:") || entry.startsWith("工具结果:")) {
                    recentObservations.add(0, entry);
                } else if (entry.startsWith("助手:")) {
                    recentReplies.add(0, entry);
                }
            }
        }

        if (!recentObservations.isEmpty()) {
            hint.append("最近的工具查询结果:\n");
            for (String obs : recentObservations) {
                hint.append("  ").append(truncateForContext(obs, 200)).append("\n");
            }
        }

        if (!recentReplies.isEmpty() && recentReplies.size() >= 2) {
            hint.append("之前的对话已在探讨相关问题，请保持回答的连贯性。\n");
        }

        hint.append("你可以参考以上上下文信息来理解用户的意图。\n\n");
        return hint.toString();
    }

    public String getContextSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("对话共").append(conversationContext.size()).append("条记录。");
        synchronized (conversationContext) {
            int userCount = 0;
            int toolCount = 0;
            for (String entry : conversationContext) {
                if (entry.startsWith("用户:")) userCount++;
                if (entry.startsWith("Observation:") || entry.startsWith("工具结果:")) toolCount++;
            }
            summary.append("用户提问").append(userCount).append("次，工具调用").append(toolCount).append("次。");
        }
        return summary.toString();
    }

    private void executeDirectToolCall(SmartIntentRecognizer.IntentResult intent, int maxTokens) {
        String toolName = intentRecognizer.getRecommendedTool(intent.intent);
        if (toolName == null) {
            AILogger.w(TAG, "No tool for intent: " + intent.intent.id);
            executeDirect(intent.intent.displayName, maxTokens, false);
            return;
        }

        String args = buildToolArgs(intent);
        notifyToolCallStart(toolName, args);
        notifyStep("调用工具", toolName);

        executor.execute(() -> {
            if (isCancelled.get()) { finishGeneration(); return; }

            AgentService.ToolCall call = new AgentService.ToolCall(toolName, args);
            AgentService.ToolResult result = agentService.executeTool(call);

            activity.runOnUiThread(() -> {
                if (callback != null) callback.onToolCallComplete(toolName, result);
            });

            if (isCancelled.get()) { finishGeneration(); return; }

            String toolResultStr = result.success ? result.result : "工具执行失败: " + result.result;
            conversationContext.add("工具结果: " + truncateForContext(toolResultStr, TOOL_RESULT_MAX_LENGTH));

            String synthesisPrompt = buildSynthesisPrompt(intent, toolResultStr);
            resetBuffers();
            executeReActLoop(synthesisPrompt, maxTokens, false);
        });
    }

    private String buildToolArgs(SmartIntentRecognizer.IntentResult intent) {
        if (intent.parameters != null && !intent.parameters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : intent.parameters.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("\"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\"");
            }
            return "{" + sb.toString() + "}";
        }
        if (intent.extractedEntity != null) {
            return "{\"query\": \"" + intent.extractedEntity + "\"}";
        }
        return "{}";
    }

    private String buildSynthesisPrompt(SmartIntentRecognizer.IntentResult intent, String toolResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("[工具调用结果整合]\n\n");
        sb.append("用户意图: ").append(intent.intent.displayName).append("\n");
        if (intent.extractedEntity != null) {
            sb.append("关键信息: ").append(intent.extractedEntity).append("\n");
        }
        sb.append("\n工具返回数据:\n").append(truncateForContext(toolResult, TOOL_RESULT_MAX_LENGTH)).append("\n\n");
        sb.append("请基于以上工具数据，用自然、友好的语言回答用户。如果数据不完整，请补充常识性信息。");
        return sb.toString();
    }

    private void executeReActLoop(String message, int maxTokens, boolean enableThinking) {
        String prompt = buildReActPrompt(message);
        sendAndProcess(prompt, maxTokens, enableThinking, new ResponseHandler() {
            @Override
            public void onThinkingToken(String token) {
                notifyThinkingToken(token);
            }

            @Override
            public void onToolCallDetected(String responseText) {
                handleReActToolCall(responseText, maxTokens);
            }

            @Override
            public void onFinalResponse(String responseText) {
                conversationContext.add("助手: " + truncateForContext(responseText));
                notifyComplete(responseText);
            }
        });
    }

    private String buildReActPrompt(String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ReAct推理模式]\n");
        sb.append("请按以下步骤处理：\n");
        sb.append("1. 思考(Thought)：分析问题，判断是否需要工具\n");
        sb.append("2. 行动(Action)：如需工具，按以下格式调用：\n");
        sb.append("   <|tool_call_begin|>function: \"工具名\"arguments: {\"参数名\": \"参数值\"}<|tool_call_end|>\n");
        sb.append("   或使用JSON格式：{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
        sb.append("3. 观察(Observation)：等待工具结果\n");
        sb.append("4. 如有足够信息，直接给出最终答案\n\n");
        sb.append(buildToolListPrompt());
        sb.append("\n用户问题: ").append(userMessage);
        return sb.toString();
    }

    private void handleReActToolCall(String responseText, int maxTokens) {
        List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
        if (toolCalls.isEmpty()) {
            conversationContext.add("助手: " + truncateForContext(responseText));
            notifyComplete(responseText);
            return;
        }

        AgentService.ToolCall call = toolCalls.get(0);
        toolLoopCount.incrementAndGet();
        iterationCount.incrementAndGet();

        AILogger.i(TAG, "ReAct tool call #" + toolLoopCount.get() + ": " + call.name);
        conversationContext.add("助手: [Action: 调用 " + call.name + "]");
        notifyToolCallStart(call.name, call.arguments);
        notifyStep("ReAct步骤" + iterationCount.get(), "调用工具: " + call.name);

        executor.execute(() -> {
            if (isCancelled.get()) { finishGeneration(); return; }

            AgentService.ToolResult result = agentService.executeTool(call);

            activity.runOnUiThread(() -> {
                if (callback != null) callback.onToolCallComplete(call.name, result);
            });

            if (isCancelled.get()) { finishGeneration(); return; }

            String obsEntry = "Observation: " + truncateForContext(result.result, TOOL_RESULT_MAX_LENGTH);
            if (!result.success) {
                obsEntry += " [失败]";
            }
            conversationContext.add(obsEntry);

            maybeSummarizeContext();

            if (toolLoopCount.get() >= maxToolLoops) {
                String summary = buildReActSummaryPrompt();
                resetBuffers();
                executeDirect(summary, maxTokens, false);
                return;
            }

            String nextPrompt = "[ReAct继续] 观察结果：\n" + truncateForContext(result.result, TOOL_RESULT_MAX_LENGTH)
                + "\n\n请继续思考，如需更多信息请调用工具，否则给出最终答案。";
            resetBuffers();
            executeReActLoop(nextPrompt, maxTokens, false);
        });
    }

    private String buildReActSummaryPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ReAct总结] 已收集信息：\n\n");
        synchronized (conversationContext) {
            for (String ctx : conversationContext) {
                if (ctx.startsWith("Observation:") || ctx.startsWith("工具结果:")) {
                    sb.append(ctx).append("\n");
                }
            }
        }
        sb.append("\n请基于以上信息给出完整答案。");
        return sb.toString();
    }

    private void executeCoTLoop(String message, int maxTokens, boolean enableThinking) {
        String prompt = buildCoTPrompt(message);
        sendAndProcess(prompt, maxTokens, enableThinking, new ResponseHandler() {
            @Override
            public void onThinkingToken(String token) {
                notifyThinkingToken(token);
            }

            @Override
            public void onToolCallDetected(String responseText) {
                handleCoTToolCall(responseText, maxTokens);
            }

            @Override
            public void onFinalResponse(String responseText) {
                conversationContext.add("助手: " + truncateForContext(responseText));
                notifyComplete(responseText);
            }
        });
    }

    private String buildCoTPrompt(String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("[链式思维模式]\n");
        sb.append("请逐步推理：\n");
        sb.append("1. 先分析问题，展示推理过程\n");
        sb.append("2. 如需查询信息，按以下格式调用工具：\n");
        sb.append("   <|tool_call_begin|>function: \"工具名\"arguments: {\"参数名\": \"参数值\"}<|tool_call_end|>\n");
        sb.append("   或JSON格式：{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
        sb.append("3. 基于推理和工具结果给出答案\n\n");
        sb.append(buildToolListPrompt());
        sb.append("\n用户问题: ").append(userMessage);
        return sb.toString();
    }

    private void handleCoTToolCall(String responseText, int maxTokens) {
        List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
        if (toolCalls.isEmpty()) {
            conversationContext.add("助手: " + truncateForContext(responseText));
            notifyComplete(responseText);
            return;
        }

        AgentService.ToolCall call = toolCalls.get(0);
        toolLoopCount.incrementAndGet();
        iterationCount.incrementAndGet();

        notifyToolCallStart(call.name, call.arguments);
        notifyStep("推理中调用工具", call.name);

        executor.execute(() -> {
            if (isCancelled.get()) { finishGeneration(); return; }

            AgentService.ToolResult result = agentService.executeTool(call);

            activity.runOnUiThread(() -> {
                if (callback != null) callback.onToolCallComplete(call.name, result);
            });

            conversationContext.add("工具结果: " + truncateForContext(result.result, TOOL_RESULT_MAX_LENGTH));

            maybeSummarizeContext();

            if (toolLoopCount.get() >= maxToolLoops) {
                String resp = currentResponse.get().toString();
                conversationContext.add("助手: " + truncateForContext(resp));
                notifyComplete(resp);
                return;
            }

            String nextPrompt = "[推理继续] 工具返回：\n" + truncateForContext(result.result, TOOL_RESULT_MAX_LENGTH) + "\n\n请继续推理并给出答案。";
            resetBuffers();
            executeCoTLoop(nextPrompt, maxTokens, false);
        });
    }

    private void executePlanLoop(String message, int maxTokens, boolean enableThinking) {
        String prompt = buildPlanPrompt(message);
        sendAndProcess(prompt, maxTokens, enableThinking, new ResponseHandler() {
            @Override public void onThinkingToken(String token) { notifyThinkingToken(token); }

            @Override
            public void onToolCallDetected(String responseText) {
                conversationContext.add("助手: " + truncateForContext(responseText));
                notifyComplete(responseText);
            }

            @Override
            public void onFinalResponse(String responseText) {
                conversationContext.add("计划: " + truncateForContext(responseText));
                executePlanSteps(responseText, maxTokens, message, new StringBuilder());
            }
        });
    }

    private String buildPlanPrompt(String userMessage) {
        return "[计划-执行模式] 请为以下任务制定执行计划，每步一行。\n如某步骤需工具，请标注工具名和参数。\n\n"
            + buildToolListPrompt() + "\n\n" + userMessage;
    }

    private void executePlanSteps(String plan, int maxTokens, String originalMessage, StringBuilder accumulated) {
        String[] steps = plan.split("\n");
        List<String> validSteps = new ArrayList<>();
        for (String step : steps) {
            String trimmed = step.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---")) {
                validSteps.add(trimmed);
            }
        }
        if (validSteps.isEmpty()) {
            notifyComplete(plan);
            return;
        }
        executePlanStep(validSteps, 0, maxTokens, originalMessage, accumulated);
    }

    private void executePlanStep(List<String> steps, int index, int maxTokens, String original, StringBuilder accumulated) {
        if (isCancelled.get() || index >= steps.size()) {
            String summary = "[计划总结] 原始任务: " + original + "\n\n执行结果:\n" + accumulated.toString() + "\n请整合结果给出最终答案。";
            resetBuffers();
            executeReActLoop(summary, maxTokens, false);
            return;
        }

        iterationCount.incrementAndGet();
        String step = steps.get(index);
        notifyStep("步骤 " + (index + 1) + "/" + steps.size(), step);

        String stepPrompt = "[执行步骤 " + (index + 1) + "/" + steps.size() + "]\n" + step
            + "\n\n原始任务: " + original + "\n请执行此步骤。";

        int nextIndex = index + 1;
        sendAndProcess(stepPrompt, maxTokens, false, new ResponseHandler() {
            @Override public void onThinkingToken(String token) {}

            @Override
            public void onToolCallDetected(String responseText) {
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
                if (toolCalls.isEmpty()) {
                    accumulated.append("步骤").append(index + 1).append(": ").append(responseText, 0, Math.min(300, responseText.length())).append("\n");
                    resetBuffers();
                    executePlanStep(steps, nextIndex, maxTokens, original, accumulated);
                    return;
                }
                AgentService.ToolCall call = toolCalls.get(0);
                toolLoopCount.incrementAndGet();
                notifyToolCallStart(call.name, call.arguments);

                executor.execute(() -> {
                    if (isCancelled.get()) { finishGeneration(); return; }
                    AgentService.ToolResult result = agentService.executeTool(call);
                    if (result == null) {
                        AILogger.e(TAG, "executeTool returned null for: " + call.name);
                        activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, new AgentService.ToolResult(call.name, "Tool execution failed", false)); });
                        accumulated.append("步骤").append(index + 1).append("工具").append(call.name).append(": 失败\n");
                        resetBuffers();
                        executePlanStep(steps, nextIndex, maxTokens, original, accumulated);
                        return;
                    }
                    activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, result); });
                    accumulated.append("步骤").append(index + 1).append("工具").append(call.name).append(": ")
                        .append(result.success ? result.result.substring(0, Math.min(300, result.result.length())) : "失败").append("\n");

                    if (toolLoopCount.get() >= maxToolLoops) {
                        String summary = "[计划总结] " + accumulated.toString() + "\n请给出最终答案。";
                        resetBuffers();
                        executeDirect(summary, maxTokens, false);
                        return;
                    }
                    resetBuffers();
                    executePlanStep(steps, nextIndex, maxTokens, original, accumulated);
                });
            }

            @Override
            public void onFinalResponse(String responseText) {
                accumulated.append("步骤").append(index + 1).append(": ").append(responseText, 0, Math.min(300, responseText.length())).append("\n");
                resetBuffers();
                executePlanStep(steps, nextIndex, maxTokens, original, accumulated);
            }
        });
    }

    private void executeDirect(String message, int maxTokens, boolean enableThinking) {
        sendAndProcess(message, maxTokens, enableThinking, new ResponseHandler() {
            @Override public void onThinkingToken(String token) { notifyThinkingToken(token); }
            @Override
            public void onToolCallDetected(String responseText) {
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
                if (toolCalls.isEmpty()) {
                    conversationContext.add("助手: " + truncateForContext(responseText));
                    notifyComplete(responseText);
                    return;
                }
                AgentService.ToolCall call = toolCalls.get(0);
                toolLoopCount.incrementAndGet();
                notifyToolCallStart(call.name, call.arguments);
                notifyStep("调用工具", call.name);

                executor.execute(() -> {
                    if (isCancelled.get()) { finishGeneration(); return; }
                    AgentService.ToolResult result = agentService.executeTool(call);
                    if (result == null) {
                        AILogger.e(TAG, "executeTool returned null for: " + call.name);
                        activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, new AgentService.ToolResult(call.name, "Tool execution failed", false)); });
                        if (isCancelled.get()) { finishGeneration(); return; }
                        conversationContext.add("工具结果: 执行失败");
                        maybeSummarizeContext();
                        String nextPrompt = "[继续] 工具调用失败，请尝试其他方式完成。";
                        resetBuffers();
                        executeDirect(nextPrompt, maxTokens, false);
                        return;
                    }
                    activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, result); });
                    if (isCancelled.get()) { finishGeneration(); return; }
                    conversationContext.add("工具结果: " + truncateForContext(result.result, TOOL_RESULT_MAX_LENGTH));
                    maybeSummarizeContext();
                    if (toolLoopCount.get() >= maxToolLoops) {
                        conversationContext.add("助手: " + truncateForContext(currentResponse.get().toString()));
                        notifyComplete(currentResponse.get().toString());
                        return;
                    }
                    String nextPrompt = "[继续] 工具返回：\n" + truncateForContext(result.result, TOOL_RESULT_MAX_LENGTH) + "\n\n请基于以上结果继续回答。";
                    resetBuffers();
                    executeDirect(nextPrompt, maxTokens, false);
                });
            }
            @Override
            public void onFinalResponse(String responseText) {
                conversationContext.add("助手: " + truncateForContext(responseText));
                notifyComplete(responseText);
            }
        });
    }

    private interface ResponseHandler {
        void onThinkingToken(String token);
        void onToolCallDetected(String responseText);
        void onFinalResponse(String responseText);
    }

    private void sendAndProcess(String message, int maxTokens, boolean enableThinking, ResponseHandler handler) {
        if (isCancelled.get()) {
            String resp = currentResponse.get().toString();
            handler.onFinalResponse(resp);
            return;
        }

        String contextMessage = buildContextMessage(message);

        aiService.chatSend(contextMessage, maxTokens, enableThinking, new LlamaHelper.TokenCallback() {
            @Override
            public void onToken(String token) {
                activity.runOnUiThread(() -> {
                    if (token.equals("[TOOL_CALL]")) return;
                    if (token.equals("[THINK_END]")) {
                        isInThinking.set(false);
                        if (callback != null) callback.onThinkingEnd();
                        return;
                    }
                    if (token.contains("<think") || token.contains("</think")) {
                        isInThinking.set(!token.contains("/"));
                        return;
                    }
                    if (isInThinking.get()) {
                        currentThinking.get().append(token);
                        if (callback != null) callback.onThinkingToken(token);
                        handler.onThinkingToken(token);
                        return;
                    }
                    currentResponse.get().append(token);
                    if (callback != null) callback.onToken(token);
                });
            }

            @Override
            public void onComplete(String fullText) {
                String responseText = currentResponse.get().toString();
                AILogger.i(TAG, "onComplete: len=" + responseText.length());
                if (responseText.contains("<|tool_call_begin|>") || responseText.contains("tool_call_begin")) {
                    handler.onToolCallDetected(responseText);
                } else {
                    handler.onFinalResponse(responseText);
                }
            }

            @Override
            public void onError(String error) {
                if ("[TOOL_CALL]".equals(error)) {
                    String responseText = currentResponse.get().toString();
                    handler.onToolCallDetected(responseText);
                    return;
                }
                AILogger.e(TAG, "chatSend error: " + error);
                finishGeneration();
                notifyError(error);
            }
        });
    }

    private String buildContextMessage(String currentMessage) {
        if (conversationContext.isEmpty()) return currentMessage;

        StringBuilder sb = new StringBuilder();
        sb.append("[对话历史 - 共").append(conversationContext.size()).append("条记录]\n");

        int start = Math.max(0, conversationContext.size() - MAX_CONTEXT_ENTRIES);

        int totalChars = 0;
        List<String> entriesToInclude = new ArrayList<>();
        for (int i = conversationContext.size() - 1; i >= start && totalChars < MAX_CONTEXT_CHARS; i--) {
            String entry = conversationContext.get(i);
            if (entry.length() > MAX_ENTRY_LENGTH) {
                entry = entry.substring(0, MAX_ENTRY_LENGTH) + "...";
            }
            entriesToInclude.add(0, entry);
            totalChars += entry.length() + 1;
        }

        for (String entry : entriesToInclude) {
            sb.append(entry).append("\n");
        }

        if (entriesToInclude.size() < Math.min(conversationContext.size(), MAX_CONTEXT_ENTRIES)) {
            sb.append("[...更早的对话已省略...]\n");
        }

        sb.append("\n").append(currentMessage);
        return sb.toString();
    }

    private void maybeSummarizeContext() {
        if (conversationContext.size() > SUMMARY_TRIGGER_ENTRIES) {
            compactContext();
        }
    }

    private void compactContext() {
        synchronized (conversationContext) {
            int removeCount = conversationContext.size() - SUMMARY_TRIGGER_ENTRIES + 5;
            if (removeCount <= 0) return;

            StringBuilder compactSummary = new StringBuilder();
            compactSummary.append("[上下文压缩: 省略了").append(removeCount).append("条早期对话记录]\n");

            List<String> keyObservations = new ArrayList<>();
            for (int i = 0; i < removeCount && i < conversationContext.size(); i++) {
                String entry = conversationContext.get(i);
                if ((entry.startsWith("Observation:") || entry.startsWith("工具结果:")) && keyObservations.size() < 3) {
                    keyObservations.add(entry);
                }
            }

            for (String obs : keyObservations) {
                compactSummary.append("  ").append(truncateForContext(obs, 150)).append("\n");
            }

            while (conversationContext.size() > SUMMARY_TRIGGER_ENTRIES - 5) {
                conversationContext.remove(0);
            }

            conversationContext.add(0, compactSummary.toString());

            AILogger.i(TAG, "Context compacted, now " + conversationContext.size() + " entries");
        }
    }

    private String truncateForContext(String text) {
        return truncateForContext(text, MAX_ENTRY_LENGTH);
    }

    private String truncateForContext(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private String buildToolListPrompt() {
        if (agentService == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【可用工具】\n");
        List<AgentService.ToolSchema> schemas = agentService.getToolSchemas();
        Map<String, AgentService.ToolSchema> uniqueTools = new LinkedHashMap<>();
        for (AgentService.ToolSchema schema : schemas) {
            SmartIntentRecognizer.Intent mapped = intentRecognizer.mapToolNameToIntentPublic(schema.name);
            String key = mapped != null ? mapped.id : schema.name;
            if (!uniqueTools.containsKey(key)) {
                uniqueTools.put(key, schema);
            }
        }
        for (AgentService.ToolSchema schema : uniqueTools.values()) {
            sb.append("- ").append(schema.name).append(": ").append(schema.description)
                .append("\n  ").append(schema.paramDesc).append("\n");
        }
        sb.append("\n你可以组合使用多个工具来完成任务。每次调用一个工具，等待结果后再决定下一步。\n");
        return sb.toString();
    }

    private void resetBuffers() {
        currentResponse.set(new StringBuilder());
        currentThinking.set(new StringBuilder());
        isInThinking.set(false);
    }

    private void finishGeneration() {
        isGenerating.set(false);
    }

    public void cancel() {
        isCancelled.set(true);
        aiService.chatStop();
        finishGeneration();
    }

    public void clearContext() {
        conversationContext.clear();
        AILogger.i(TAG, "Conversation context cleared");
    }

    public List<String> getConversationContext() {
        synchronized (conversationContext) {
            return new ArrayList<>(conversationContext);
        }
    }

    public boolean isGenerating() { return isGenerating.get(); }
    public int getToolLoopCount() { return toolLoopCount.get(); }
    public String getCurrentResponse() { return currentResponse.get().toString(); }
    public String getCurrentThinking() { return currentThinking.get().toString(); }

    private void notifyToken(String token) {
        activity.runOnUiThread(() -> { if (callback != null) callback.onToken(token); });
    }

    private void notifyThinkingToken(String token) {
        activity.runOnUiThread(() -> { if (callback != null) callback.onThinkingToken(token); });
    }

    private void notifyToolCallStart(String name, String args) {
        activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallStart(name, args); });
    }

    private void notifyStep(String step, String detail) {
        activity.runOnUiThread(() -> { if (callback != null) callback.onStepUpdate(step, detail); });
    }

    private void notifyComplete(String text) {
        finishGeneration();
        AILogger.i(TAG, "Complete: mode=" + currentMode + " len=" + text.length());
        activity.runOnUiThread(() -> { if (callback != null) callback.onComplete(text); });
    }

    private void notifyError(String error) {
        finishGeneration();
        activity.runOnUiThread(() -> { if (callback != null) callback.onError(error); });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}