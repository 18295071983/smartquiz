package com.oilquiz.app.ai.chat;

import android.app.Activity;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentRouter;
import com.oilquiz.app.ai.agent.SmartIntentRecognizer;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.agent.software.AgentSoftwareLayer;
import com.oilquiz.app.ai.agent.software.engine.AgentLoopEngine;
import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.AgentStats;
import com.oilquiz.app.util.AILogger;

import java.util.List;

public class AgentChatHandler {
    private static final String TAG = "AgentChatHandler";

    public enum InferenceMode {
        REACT,
        CHAIN_OF_THOUGHT,
        PLAN_EXECUTE
    }

    public interface AgentChatCallback {
        void onToolCallStart(String toolCallId, String toolName, String args);
        void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result);
        void onToken(String token);
        void onThinkingToken(String token);
        void onThinkingEnd();
        void onComplete(String fullText);
        void onError(String error);
        void onModeSwitched(String mode);
        void onAgentStep(ChatMessage.AgentStepInfo stepInfo);
        void onToolCallUI(String toolName, String args, int position);
        void onToolCallResultUI(int position, boolean success, String result);
        void onAgentStepUpdateUI(int position, String thought, String action, String observation, boolean isCompleted);
        void onInferenceProgress(int tokenCount, float tokensPerSecond);
    }

    private final Activity activity;
    private final AgentService agentService;
    private final AgentChatCallback callback;
    private final AgentRouter engine;
    private final SmartIntentRecognizer intentRecognizer;
    private final com.oilquiz.app.ai.refactor.AIConfig aiConfig;
    private AgentSoftwareLayer softwareLayer;
    private volatile boolean isShutdown = false;

    private InferenceMode currentInferenceMode = InferenceMode.REACT;

    /**
     * 本地 Agent 流式正文的思考拆分器。native chatJson 对本地模型从不分离思考
     * （reasoning 恒为 0，思考被当普通 token 流入正文），这里用模板标签在 Java 侧把
     * &lt;think&gt;…&lt;/think&gt; 从正文流中拆出：内容进 onToken、思考进 onThinkingToken，
     * 使深度思考的思考内容能实时显示在思考区而不是主消息气泡。
     */
    private final com.oilquiz.app.ai.chat.parser.OutputRouter thinkingRouter =
            new com.oilquiz.app.ai.chat.parser.OutputRouter(
                    new com.oilquiz.app.ai.chat.parser.OutputRouter.OutputHandler() {
                        @Override public void onTextOutput(String text, boolean isComplete) {
                            if (isValid() && text != null && !text.isEmpty()) {
                                AgentChatHandler.this.callback.onToken(text);
                            }
                        }
                        @Override public void onThinkingStart() { }
                        @Override public void onThinkingContent(String content) {
                            if (isValid() && content != null && !content.isEmpty()) {
                                AgentChatHandler.this.callback.onThinkingToken(content);
                            }
                        }
                        @Override public void onThinkingEnd() {
                            if (isValid()) AgentChatHandler.this.callback.onThinkingEnd();
                        }
                        @Override public void onToolCall(String toolName, org.json.JSONObject parameters) { }
                        @Override public void onStructuredData(String dataType, org.json.JSONObject data) { }
                        @Override public void onError(String error) { }
                        @Override public void onStreamComplete(String fullContent) { }
                    });

    public AgentChatHandler(Activity activity, AIService aiService, AgentService agentService, AgentChatCallback callback) {
        this(activity, aiService, null, agentService, callback, false);
    }

    public AgentChatHandler(Activity activity, AIService aiService, InferenceRouter inferenceRouter,
                            AgentService agentService, AgentChatCallback callback, boolean useOnlineModel) {
        this.activity = activity;
        this.agentService = agentService;
        this.callback = callback;
        // 使用 AgentRouter 自动路由到本地/在线引擎
        this.engine = new AgentRouter(activity, aiService, inferenceRouter, agentService);
        this.intentRecognizer = SmartIntentRecognizer.getInstance(activity);
        this.aiConfig = new com.oilquiz.app.ai.refactor.AIConfig(activity);
        
        // 初始化新的 AgentSoftwareLayer
        this.softwareLayer = new AgentSoftwareLayer(activity, aiService);
        this.softwareLayer.setCallback(new AgentSoftwareLayer.AgentCallback() {
            @Override
            public void onStepUpdate(String step, String detail) {
                AILogger.i(TAG, "Agent step: " + step + " - " + detail);
                if (isValid()) {
                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING, 0, 0);
                    stepInfo.thought = step + ": " + detail;
                    stepInfo.isCompleted = true;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onThinkingUpdate(String thought) {
                if (isValid()) {
                    callback.onThinkingToken(thought);
                }
            }

            @Override
            public void onToolCallStart(String toolName, String args) {
                if (isValid()) {
                    callback.onToolCallStart("software_" + System.nanoTime(), toolName, args);
                    callback.onToolCallUI(toolName, args, -1);
                }
            }

            @Override
            public void onToolCallComplete(String toolName, boolean success, String result) {
                if (isValid()) {
                    OnlineToolResult toolResult = success
                        ? OnlineToolResult.success(null, toolName, result, 0)
                        : OnlineToolResult.failure(null, toolName, result, 0);
                    callback.onToolCallComplete("software_" + System.nanoTime(), toolName, toolResult);
                    callback.onToolCallResultUI(-1, success, result);
                }
            }

            @Override
            public void onToken(String token) {
                if (isValid() && token != null) {
                    // 本地 Agent 流式中 native 不分离思考（reasoning 恒空，思考混在正文 token），
                    // 用模板标签在 Java 侧拆分：思考→onThinkingToken，正文→onToken。
                    thinkingRouter.setThinkingTags(com.oilquiz.app.ai.jni.LlamaHelper.getThinkingTags());
                    thinkingRouter.processToken(token);
                }
            }

            @Override
            public void onComplete(AgentResponse response) {
                if (isValid()) {
                    callback.onComplete(response.finalAnswer);
                }
            }

            @Override
            public void onError(String error) {
                if (isValid()) {
                    callback.onError(error);
                }
            }

            @Override
            public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
                if (isValid()) {
                    callback.onInferenceProgress(tokenCount, tokensPerSecond);
                }
            }
        });

        engine.setCallback(new AgentCallback() {
            @Override
            public void onToken(String token) {
                if (isValid()) callback.onToken(token);
            }

            @Override
            public void onThinkingToken(String token) {
                if (isValid()) callback.onThinkingToken(token);
            }

            @Override
            public void onThinkingEnd() {
                if (isValid()) callback.onThinkingEnd();
            }

            @Override
            public void onToolCallStart(String toolCallId, String toolName, String args) {
                if (isValid()) {
                    callback.onToolCallStart(toolCallId, toolName, args);
                    callback.onToolCallUI(toolName, args, -1);
                }
            }

            @Override
            public void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result) {
                if (isValid()) {
                    callback.onToolCallComplete(toolCallId, toolName, result);
                    // 保护：result可能为null
                    boolean success = result != null && result.success;
                    String resultStr = result != null ? result.result : "工具执行返回null";
                    callback.onToolCallResultUI(-1, success, resultStr);
                }
            }

            @Override
            public void onStepUpdate(String step, String detail) {
                if (isValid()) {
                    // 根据步骤内容判断步骤类型
                    ChatMessage.AgentStepInfo.AgentStepType stepType = ChatMessage.AgentStepInfo.AgentStepType.THINKING;

                    if (step.contains("意图") || step.contains("intent") || step.contains("分析")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.PLANNING;
                    } else if (step.contains("执行") || step.contains("action") || step.contains("工具")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.ACTING;
                    } else if (step.contains("结果") || step.contains("观察") || step.contains("observe")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.OBSERVING;
                    } else if (step.contains("反思") || step.contains("reflect")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.REFLECTING;
                    } else if (step.contains("推理") || step.contains("reason")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.REASONING;
                    } else if (step.contains("循环") || step.contains("loop")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.LOOPING;
                    }

                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        stepType,
                        engine.getToolLoopCount() + 1,
                        5
                    );
                    stepInfo.thought = step;
                    stepInfo.detail = detail;
                    stepInfo.reasoningMode = "在线";
                    stepInfo.isCompleted = true;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onComplete(String fullText) {
                if (isValid()) callback.onComplete(fullText);
            }

            @Override
            public void onError(String error) {
                if (isValid()) callback.onError(error);
            }

            // ========== 新增回调方法 ==========

            @Override
            public void onThinking(String thought) {
                AILogger.i(TAG, "onThinking: " + thought);
                if (isValid()) {
                    // 显示思考内容
                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING,
                        0,
                        0
                    );
                    stepInfo.thought = thought;
                    stepInfo.reasoningMode = "在线";
                    stepInfo.isCompleted = false;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onThinkingStage(String stage) {
                AILogger.i(TAG, "onThinkingStage: " + stage);
                if (isValid()) {
                    // 可以在UI上显示阶段变化
                    callback.onAgentStep(new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING,
                        0,
                        0
                    ));
                }
            }

            // ==========================================
        });

        // 设置推理进度监听器，转发到UI
        engine.setInferenceProgressListener((tokenCount, tokensPerSecond) -> {
            if (isValid()) {
                callback.onInferenceProgress(tokenCount, tokensPerSecond);
            }
        });
    }

    public void setInferenceMode(InferenceMode mode) {
        this.currentInferenceMode = mode;
        // 推理模式（REACT/CoT/Plan）为旧本地 Agent 概念，在线引擎忽略
    }

    public InferenceMode getInferenceMode() {
        return currentInferenceMode;
    }

    public boolean shouldUseAgent(String message) {
        return intentRecognizer.shouldUseAgent(message);
    }

    /**
     * 检查 AgentChatHandler 是否有效
     * 防止 callback=null 导致的崩溃
     */
    public boolean isValid() {
        return !isShutdown && callback != null && activity != null && !activity.isFinishing();
    }

    /**
     * 安全执行回调操作
     * 在执行前检查 callback 是否有效
     */
    private void safeCallback(java.util.function.Consumer<AgentChatCallback> action) {
        if (isValid()) {
            try {
                action.accept(callback);
            } catch (Exception e) {
                AILogger.e(TAG, "Callback error: " + e.getMessage());
            }
        } else {
            AILogger.w(TAG, "Callback invalid, skipping action");
        }
    }

    public String getIntentType(String message) {
        return intentRecognizer.getIntentType(message);
    }

public SmartIntentRecognizer.IntentResult analyzeIntent(String message) {
        return intentRecognizer.recognize(message);
    }

    public void startAgentLoop(String message, int maxTokens, boolean enableThinking) {
        startAgentLoop(message, maxTokens, enableThinking, null);
    }

    /**
     * 启动 Agent 循环。
     * @param history 多轮上下文（user/assistant，本地 Agent 用）；在线引擎自带会话历史，忽略该参数
     */
    public void startAgentLoop(String message, int maxTokens, boolean enableThinking,
                               java.util.List<AgentLoopEngine.HistoryEntry> history) {
        AILogger.i(TAG, "startAgentLoop: mode=" + currentInferenceMode + ", msg_len=" + message.length());
        // 新一轮开始前重置思考拆分器状态（防止上一轮未闭合的思考段串到本轮）
        thinkingRouter.reset();

        // 路由分支（R3-1/R8-1）：本地模型且 localAgentEnabled → 本地软件层；否则在线引擎
        boolean useLocalAgent = aiConfig != null && aiConfig.isLocalAgentEnabled()
                && !engine.isOnlineModelActive();
        if (useLocalAgent) {
            AILogger.i(TAG, "Local model + localAgentEnabled → AgentSoftwareLayer (JSON 协议)"
                    + ", history: " + (history != null ? history.size() : 0));
            softwareLayer.processMessage(message, enableThinking, history);
        } else {
            // 在线模型 → OnlineAgentEngine
            engine.execute(message, maxTokens, enableThinking);
        }
    }

    public void cancel() {
        // R3-2：本地软件层也需打断 native 生成（softwareLayer.cancel 内会调 stopGeneration）
        if (softwareLayer != null && softwareLayer.isProcessing()) {
            softwareLayer.cancel();
        }
        engine.cancel();
    }

    /** 清空对话历史（用于"新对话"/"清空对话"操作） */
    public void clearHistory() {
        engine.clearHistory();
    }

    /** 手动压缩对话历史：模型生成摘要，保留最近 keepRecent 条消息 */
    public void compressHistory(int keepRecent, java.util.function.Consumer<String> callback) {
        engine.compressHistory(keepRecent, callback);
    }

    /** 清空所有会话的历史（内存 + 全部历史文件） */
    public void clearAllHistory() {
        engine.clearAllHistory();
    }

    /** 切换会话 ID（引擎按会话隔离历史：保存当前 → 恢复目标） */
    public void setSessionId(String sessionId) {
        engine.setSessionId(sessionId);
    }

    /** 设置当前在线模型 ID（模型切换时调用，引擎按「会话 × 模型」隔离历史文件） */
    public void setModelId(String modelId) {
        engine.setModelId(modelId);
    }

    public boolean isGenerating() {
        return engine.isGenerating();
    }

    public int getToolLoopCount() {
        return engine.getToolLoopCount();
    }

    public int getLastCacheHitTokens() {
        return engine.getLastCacheHitTokens();
    }

    public int getLastPromptTokens() {
        return engine.getLastPromptTokens();
    }

    public int getLastCompletionTokens() {
        return engine.getLastCompletionTokens();
    }

    public int getExecTotalPromptTokens() {
        return engine.getExecTotalPromptTokens();
    }

    public int getExecTotalCompletionTokens() {
        return engine.getExecTotalCompletionTokens();
    }

    public int[] getContextWindowInfo() {
        return engine.getContextWindowInfo();
    }

    public void shutdown() {
        isShutdown = true;
        engine.shutdown();
        AILogger.i(TAG, "AgentChatHandler shut down");
    }
}
