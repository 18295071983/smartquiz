package com.oilquiz.app.ai.agent.online;

import android.app.Activity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.InferenceProgressListener;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 在线模型专用 Agent 引擎（完全独立于本地 Agent）。
 *
 * 不依赖 AgentService、AIService、UnifiedAgentEngine 或 InferenceRouter。
 * 使用独立的工具系统（{@link OnlineToolManager}）、提示词构建器（{@link OnlinePromptBuilder}）、
 * 思考链管理器（{@link OnlineThinkingChain}）和执行步骤（{@link OnlineExecutionStep}）。
 *
 * 核心能力：
 * 1. 原生 function calling（非文本 TOOLS_CALL 格式）
 * 2. 并行工具执行（API 返回多个 tool_calls 时并行执行）
 * 3. 流式 reasoning_content（思考链实时展示）
 * 4. 流式 tool_calls（工具调用增量实时展示）
 * 5. 流式 content（最终回答实时展示）
 * 6. 多轮 agent 循环（推理 → 工具 → 结果 → 再推理，最多 8 轮）
 */
public class OnlineAgentEngine {

    private static final String TAG = "OnlineAgentEngine";
    /** 辅助模式最大迭代轮数 */
    private static final int MAX_ITERATIONS = 20;
    /** 接管模式最大迭代轮数（模型自主控制，放宽上限） */
    private static final int MAX_ITERATIONS_TAKEOVER = 30;
    private static final int MAX_TOKENS = 4096;
    /** 消息历史最大保留条数（超出则从前面截断，保留 system + 最近消息） */
    private static final int MAX_HISTORY_MESSAGES = 30;

    /** Agent 执行模式 */
    public enum AgentMode {
        /** 模型接管：模型具备完整 agent 能力，本地退化为纯执行器，信任模型自主决策 */
        TAKEOVER,
        /** 本地辅助：模型 agent 能力不足或未知，本地提供回退注入、工具指南等辅助 */
        ASSISTED
    }

    private final Activity activity;
    private final OnlineToolManager toolManager;
    private final OnlineInferenceService onlineInferenceService;
    private final OnlinePromptBuilder promptBuilder;
    private final OnlineThinkingChain thinkingChain;
    private final ExecutorService executor;

    /** 当前执行模式（每次 doExecute 开始时根据模型能力设定） */
    private volatile AgentMode agentMode = AgentMode.ASSISTED;

    private AgentCallback callback;
    private InferenceProgressListener progressListener;
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicInteger toolLoopCount = new AtomicInteger(0);

    // OpenAI 格式消息历史（直接使用 JsonObject，支持 tool 角色消息）
    private final List<JsonObject> messageHistory = new ArrayList<>();

    // 推理进度统计
    private long inferenceStartTime;
    private int totalTokenCount;

    public OnlineAgentEngine(Activity activity, OnlineToolManager toolManager) {
        this.activity = activity;
        this.toolManager = toolManager;
        this.onlineInferenceService = OnlineInferenceService.getInstance(activity);
        this.promptBuilder = new OnlinePromptBuilder(toolManager.getToolGuideInstance());
        this.thinkingChain = new OnlineThinkingChain();
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "OnlineAgent-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
    }

    public void setCallback(AgentCallback callback) {
        this.callback = callback;
    }

    public void setInferenceProgressListener(InferenceProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * 执行 Agent 任务。
     * 使用原生 function calling 循环：推理 → 工具调用 → 结果注入 → 再推理。
     */
    public void execute(String userMessage, int maxTokens) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            notifyError("消息不能为空");
            return;
        }
        if (toolManager == null) {
            notifyError("工具管理器未初始化");
            return;
        }
        if (isGenerating.getAndSet(true)) {
            notifyError("正在生成中，请等待完成");
            return;
        }

        isCancelled.set(false);
        messageHistory.clear();
        thinkingChain.clear();
        toolLoopCount.set(0);
        totalTokenCount = 0;
        inferenceStartTime = System.currentTimeMillis();

        int effectiveMaxTokens = maxTokens > 0 ? maxTokens : MAX_TOKENS;

        executor.submit(() -> {
            try {
                doExecute(userMessage, effectiveMaxTokens);
            } catch (Throwable t) {
                AILogger.e(TAG, "Execute failed: " + t.getMessage(), t);
                finishGeneration();
                notifyError("执行中断: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            }
        });
    }

    /**
     * 核心 Agent 执行循环
     */
    private void doExecute(String userMessage, int maxTokens) {
        OnlineModelManager.OnlineModelConfig cfg = onlineInferenceService.getActiveConfig();
        if (cfg == null) {
            finishGeneration();
            notifyError("没有激活的在线模型");
            return;
        }

        // 0. 检测在线模型 agent 能力，选择执行模式
        agentMode = detectAgentCapability(cfg) ? AgentMode.TAKEOVER : AgentMode.ASSISTED;
        int maxIterations = agentMode == AgentMode.TAKEOVER ? MAX_ITERATIONS_TAKEOVER : MAX_ITERATIONS;
        AILogger.i(TAG, "Agent mode: " + agentMode + " (maxIterations=" + maxIterations + ")");
        notifyStep("执行模式", agentMode == AgentMode.TAKEOVER ? "模型接管模式" : "本地辅助模式");

        // 0.5 刷新工具注册系统（必须在构建系统提示词之前，确保工具列表和指南不为空）
        toolManager.refreshRegistry();

        // 1. 使用 OnlinePromptBuilder 构建系统提示词（按模式选择）
        String systemPrompt = agentMode == AgentMode.TAKEOVER
            ? promptBuilder.buildSystemPromptTakeover()
            : promptBuilder.buildSystemPrompt();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messageHistory.add(systemMsg);

        // 1.5 获取环境上下文（日期、位置、天气），注入为系统消息辅助Agent思考
        notifyStep("环境感知", "正在获取位置和天气信息...");
        String envContext = buildEnvironmentContext();
        if (envContext != null && !envContext.isEmpty()) {
            JsonObject envMsg = new JsonObject();
            envMsg.addProperty("role", "system");
            envMsg.addProperty("content", envContext);
            messageHistory.add(envMsg);
            AILogger.i(TAG, "Environment context injected: " + envContext.length() + " chars");
        }

        // 2. 添加用户消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messageHistory.add(userMsg);

        // 3. 通过 OnlineToolManager 获取工具定义
        String toolsJson = toolManager.getToolDefinitions();
        int toolCount = countToolsInJson(toolsJson);
        AILogger.i(TAG, "Tool definitions: count=" + toolCount + ", json_len=" + (toolsJson != null ? toolsJson.length() : 0));
        if (toolCount == 0) {
            AILogger.w(TAG, "No tools available! Agent will run without tool calling capability.");
        }

        // 4. Agent 主循环
        int iteration = 0;
        while (iteration < maxIterations && !isCancelled.get()) {
            iteration++;
            AILogger.i(TAG, "Agent iteration " + iteration + "/" + maxIterations + " [" + agentMode + "]");

            // 开始新一轮思考块
            thinkingChain.startNewBlock(iteration);
            notifyStep("推理轮次 " + iteration, "正在思考...");
            notifyExecutionStep(OnlineExecutionStep.THINKING, "推理轮次 " + iteration);

            // 流式生成一轮
            IterationResult result = streamOneIteration(cfg, maxTokens, toolsJson);
            if (result == null) {
                return; // 错误已处理
            }
            // 取消检查：流被中断后立即退出，不继续处理工具调用
            if (isCancelled.get()) {
                finishGeneration();
                if (!result.content.isEmpty()) {
                    notifyComplete(result.content);
                }
                return;
            }

            // 更新进度
            totalTokenCount += estimateTokens(result.content);
            notifyProgress();

            // 完成当前思考块
            thinkingChain.completeActiveBlock();

            // 检查是否有工具调用
            if (result.toolCalls == null || result.toolCalls.isEmpty()) {
                // 没有工具调用，输出最终回答
                AILogger.i(TAG, "No tool calls, final response. content_len=" + result.content.length()
                    + " finish_reason=" + result.finishReason);
                String finalAnswer = result.content;
                // 处理异常 finish_reason
                if ("length".equals(result.finishReason)) {
                    AILogger.w(TAG, "Response truncated due to max_tokens (finish_reason=length)");
                    finalAnswer = finalAnswer + "\n\n（注：回答因达到长度上限被截断）";
                } else if ("content_filter".equals(result.finishReason)) {
                    AILogger.w(TAG, "Response filtered (finish_reason=content_filter)");
                    if (finalAnswer.isEmpty()) {
                        finalAnswer = "（回答被内容过滤机制拦截，请尝试调整问题后重试）";
                    }
                }
                notifyExecutionStep(OnlineExecutionStep.COMPLETED, "完成");
                notifyComplete(finalAnswer);
                return;
            }

            // 有工具调用但 finish_reason=length：工具参数可能被截断，记录警告
            if ("length".equals(result.finishReason)) {
                AILogger.w(TAG, "Tool calls may be truncated due to max_tokens (finish_reason=length)");
            }

            // 有工具调用
            toolLoopCount.incrementAndGet();
            notifyExecutionStep(OnlineExecutionStep.TOOL_CALLING, "执行 " + result.toolCalls.size() + " 个工具");
            notifyStep("工具调用", "执行 " + result.toolCalls.size() + " 个工具");

            // 将 assistant 消息（含 tool_calls）加入历史
            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            if (result.content != null && !result.content.isEmpty()) {
                assistantMsg.addProperty("content", result.content);
            }
            JsonArray toolCallsArray = new JsonArray();
            for (OnlineInferenceService.ToolCallInfo tc : result.toolCalls) {
                JsonObject tcObj = new JsonObject();
                tcObj.addProperty("id", tc.id != null ? tc.id : "call_" + System.nanoTime());
                tcObj.addProperty("type", "function");
                JsonObject funcObj = new JsonObject();
                funcObj.addProperty("name", tc.name);
                funcObj.addProperty("arguments", tc.arguments != null ? tc.arguments : "{}");
                tcObj.add("function", funcObj);
                toolCallsArray.add(tcObj);
            }
            assistantMsg.add("tool_calls", toolCallsArray);
            messageHistory.add(assistantMsg);

            // 并行执行所有工具调用
            notifyExecutionStep(OnlineExecutionStep.TOOL_EXECUTING, "工具执行中");
            List<CompletableFuture<OnlineToolResult>> toolFutures = new ArrayList<>();
            for (OnlineInferenceService.ToolCallInfo tc : result.toolCalls) {
                if (isCancelled.get()) {
                    finishGeneration();
                    return;
                }
                // 通知 UI 工具调用开始
                activity.runOnUiThread(() -> {
                    if (callback != null) callback.onToolCallStart(tc.name, tc.arguments);
                });

                // 并行执行
                final String toolCallId = tc.id != null ? tc.id : "call_" + System.nanoTime();
                CompletableFuture<OnlineToolResult> future = CompletableFuture.supplyAsync(
                    () -> executeToolCall(toolCallId, tc.name, tc.arguments), executor);
                toolFutures.add(future);
            }

            // 等待所有工具完成，并收集失败工具
            List<String> failedTools = new ArrayList<>();
            for (int i = 0; i < toolFutures.size(); i++) {
                if (isCancelled.get()) {
                    finishGeneration();
                    return;
                }
                try {
                    OnlineToolResult toolResult = toolFutures.get(i).join();
                    // 通知 UI 工具调用完成
                    final OnlineToolResult tr = toolResult;
                    activity.runOnUiThread(() -> {
                        if (callback != null) callback.onToolCallComplete(tr.toolName, tr);
                    });

                    // 在思考链中记录工具调用
                    String resultSummary = tr.success ? (tr.result != null ? tr.result.substring(0, Math.min(200, tr.result.length())) : "") : tr.error;
                    thinkingChain.appendToolCall(tr.toolName, "", tr.success, resultSummary);

                    // 将工具结果加入消息历史（OpenAI tool 角色）
                    JsonObject toolMsg = new JsonObject();
                    toolMsg.addProperty("role", "tool");
                    toolMsg.addProperty("tool_call_id", tr.toolCallId);
                    toolMsg.addProperty("name", tr.toolName);
                    String toolContent = tr.success ? tr.result : ("工具执行失败: " + tr.error);
                    toolMsg.addProperty("content", toolContent != null ? toolContent : "");
                    messageHistory.add(toolMsg);

                    // 收集失败工具用于回退建议
                    if (!tr.success && tr.toolName != null) {
                        failedTools.add(tr.toolName);
                    }

                    AILogger.i(TAG, "Tool " + tr.toolName + " done, success=" + tr.success);
                } catch (Exception e) {
                    AILogger.e(TAG, "Tool future error: " + e.getMessage(), e);
                }
            }

            // 工具失败时注入回退建议（仅辅助模式；接管模式下信任模型自主决策，不主动干预）
            if (agentMode == AgentMode.ASSISTED && !failedTools.isEmpty() && toolManager.getToolChain() != null) {
                StringBuilder fallbackHint = new StringBuilder();
                java.util.Set<String> addedFallbacks = new java.util.LinkedHashSet<>();
                for (String failedTool : failedTools) {
                    List<String> fallbacks = toolManager.getToolChain().getFallbackTools(failedTool);
                    // 过滤已添加的回退工具
                    List<String> uniqueFallbacks = new ArrayList<>();
                    for (String fb : fallbacks) {
                        if (addedFallbacks.add(fb)) uniqueFallbacks.add(fb);
                    }
                    if (!uniqueFallbacks.isEmpty()) {
                        String hint = promptBuilder.buildFallbackHint(failedTool, uniqueFallbacks);
                        if (hint != null) fallbackHint.append(hint).append("\n");
                    }
                }
                if (fallbackHint.length() > 0) {
                    JsonObject hintMsg = new JsonObject();
                    hintMsg.addProperty("role", "system");
                    hintMsg.addProperty("content", fallbackHint.toString());
                    messageHistory.add(hintMsg);
                    AILogger.i(TAG, "Injected fallback hint for failed tools: " + failedTools);
                }
            }

            // 继续下一轮推理
        }

        // 达到最大迭代次数
        AILogger.w(TAG, "Reached max iterations (" + maxIterations + ", mode=" + agentMode + ")");
        notifyStep("总结", "已达到最大推理轮次，生成最终回答...");
        notifyExecutionStep(OnlineExecutionStep.RESPONDING, "生成最终回答");
        IterationResult finalResult = streamOneIteration(cfg, maxTokens, null);
        if (finalResult != null) {
            notifyExecutionStep(OnlineExecutionStep.COMPLETED, "完成");
            notifyComplete(finalResult.content);
        } else {
            notifyComplete("已达到最大推理轮次。");
        }
    }

    /**
     * 截断消息历史，避免长对话或多次工具调用后超出模型上下文窗口。
     *
     * 策略：保留首条 system 消息 + 最近 MAX_HISTORY_MESSAGES-1 条消息。
     * 安全保证：截断点不会落在 tool 消息上（否则其对应的 assistant.tool_calls 被移除，
     * 导致 API 报错）。若截断点处为 tool 消息，继续前移直至非 tool 消息。
     */
    private void trimMessageHistory() {
        if (messageHistory.size() <= MAX_HISTORY_MESSAGES) return;
        int removeCount = messageHistory.size() - MAX_HISTORY_MESSAGES;
        int cutoff = 1 + removeCount; // 保留 index 0 (system)，从 index 1 开始移除
        // 调整 cutoff：若 cutoff 处是 tool 消息，其配对的 assistant.tool_calls 已被移除，需一并移除
        while (cutoff < messageHistory.size()) {
            JsonObject msg = messageHistory.get(cutoff);
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            if ("tool".equals(role)) {
                cutoff++;
            } else {
                break;
            }
        }
        int actualRemoved = cutoff - 1;
        if (actualRemoved > 0) {
            messageHistory.subList(1, cutoff).clear();
            AILogger.i(TAG, "Trimmed message history: removed " + actualRemoved
                + " old messages, " + messageHistory.size() + " remaining");
        }
    }

    /**
     * 流式生成一轮推理。
     * 使用 V2 接口直接发送 JsonArray 消息，支持所有角色。
     */
    private IterationResult streamOneIteration(OnlineModelManager.OnlineModelConfig cfg,
                                                int maxTokens, String toolsJson) {
        final IterationResult result = new IterationResult();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] errorHolder = {null};

        // 截断消息历史，避免长对话或多次工具调用后超出模型上下文窗口
        trimMessageHistory();

        JsonArray messagesArray = new JsonArray();
        for (JsonObject msg : messageHistory) {
            messagesArray.add(msg);
        }

        onlineInferenceService.generateStreamWithToolsV2(messagesArray, cfg, maxTokens, toolsJson,
            new OnlineInferenceService.NativeToolStreamCallback() {
                @Override
                public void onStart() {
                    AILogger.i(TAG, "Stream started");
                }

                @Override
                public void onReasoningToken(String token) {
                    if (isCancelled.get()) return;
                    totalTokenCount++;
                    thinkingChain.appendReasoningToken(token);
                    activity.runOnUiThread(() -> {
                        if (callback != null) callback.onThinkingToken(token);
                    });
                }

                @Override
                public void onContentToken(String token) {
                    if (isCancelled.get()) return;
                    totalTokenCount++;
                    activity.runOnUiThread(() -> {
                        if (callback != null) callback.onToken(token);
                    });
                    if (totalTokenCount % 10 == 0) {
                        notifyProgress();
                    }
                }

                @Override
                public void onToolCallsReady(List<OnlineInferenceService.ToolCallInfo> toolCalls) {
                    AILogger.i(TAG, "Tool calls ready: " + (toolCalls != null ? toolCalls.size() : 0));
                    // 工具调用通知在 doExecute 中处理（需要 toolCallId）
                }

                @Override
                public void onComplete(String fullContent, String reasoningContent,
                                        List<OnlineInferenceService.ToolCallInfo> toolCalls,
                                        String finishReason) {
                    result.content = fullContent != null ? fullContent : "";
                    result.reasoningContent = reasoningContent != null ? reasoningContent : "";
                    result.toolCalls = toolCalls;
                    result.finishReason = finishReason;

                    notifyProgress();
                    activity.runOnUiThread(() -> {
                        if (callback != null) callback.onThinkingEnd();
                    });
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    errorHolder[0] = error;
                    latch.countDown();
                }

                @Override
                public boolean isCancelled() {
                    return isCancelled.get();
                }

                @Override
                public void onUsage(int promptTokens, int completionTokens, int totalTokens) {
                    // 用 API 返回的精确 token 数更新（prompt_tokens 含 system+工具定义+历史消息）
                    totalTokenCount = totalTokens;
                    AILogger.i(TAG, "Token usage: prompt=" + promptTokens
                        + " completion=" + completionTokens + " total=" + totalTokens);
                    notifyProgress();
                }
            });

        try {
            boolean completed = latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                // 超时：流式推理 120 秒未完成
                finishGeneration();
                AILogger.w(TAG, "Stream timed out after 120s, content_len=" + result.content.length());
                if (!result.content.isEmpty()) {
                    notifyComplete(result.content + "\n\n（注：推理超时，回答可能不完整）");
                } else {
                    notifyError("推理超时（120秒无响应），请检查网络或模型状态");
                }
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishGeneration();
            notifyError("推理被中断");
            return null;
        }

        if (errorHolder[0] != null) {
            finishGeneration();
            if (!result.content.isEmpty()) {
                AILogger.w(TAG, "Returning partial result due to error: " + errorHolder[0]);
                notifyComplete(result.content);
                return null;
            }
            notifyError("推理失败: " + errorHolder[0]);
            return null;
        }

        return result;
    }

    /**
     * 执行单个工具调用（通过 OnlineToolManager）
     */
    private OnlineToolResult executeToolCall(String toolCallId, String toolName, String arguments) {
        AILogger.i(TAG, "Executing tool: " + toolName + " args: " + arguments);
        return toolManager.executeTool(toolCallId, toolName, arguments);
    }

    /**
     * 构建环境上下文：当前日期时间、位置、天气。
     * 在Agent主循环前调用，注入为system消息辅助模型思考。
     * 位置获取有5秒超时，失败则仅使用日期时间。
     */
    private String buildEnvironmentContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("【环境上下文】\n");

        // 1. 当前日期时间（始终可用）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA);
        String dateTime = sdf.format(new Date());
        sb.append("当前时间：").append(dateTime).append("\n");

        // 2. 获取位置（5秒超时）
        String locationInfo = null;
        try {
            CompletableFuture<OnlineToolResult> locFuture = CompletableFuture.supplyAsync(
                () -> toolManager.executeTool("env_loc", "location", "{\"action\":\"get_current\"}"),
                executor);

            OnlineToolResult locResult = locFuture.get(5, TimeUnit.SECONDS);
            if (locResult != null && locResult.success && locResult.result != null) {
                String city = extractCityFromLocationResult(locResult.result);
                String district = extractDistrictFromLocationResult(locResult.result);
                if (city != null) {
                    locationInfo = district != null ? district : city;
                    sb.append("当前位置：").append(locationInfo).append("\n");

                    // 3. 获取天气（基于城市，3秒超时）
                    String weatherSummary = null;
                    try {
                        String weatherArgs = "{\"action\":\"current\",\"city\":\"" + city + "\"}";
                        CompletableFuture<OnlineToolResult> weatherFuture = CompletableFuture.supplyAsync(
                            () -> toolManager.executeTool("env_weather", "ai_weather", weatherArgs),
                            executor);

                        OnlineToolResult weatherResult = weatherFuture.get(3, TimeUnit.SECONDS);
                        if (weatherResult != null && weatherResult.success && weatherResult.result != null) {
                            weatherSummary = extractWeatherSummary(weatherResult.result);
                            if (weatherSummary != null) {
                                sb.append("当前天气：").append(weatherSummary).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        AILogger.w(TAG, "Weather fetch for env context failed: " + e.getMessage());
                    }

                    // 反馈环境感知结果
                    String finalWeather = weatherSummary;
                    notifyStep("环境感知", "✅ 位置: " + locationInfo + (finalWeather != null ? " | 天气: " + finalWeather : " | 天气获取失败"));
                } else {
                    notifyStep("环境感知", "⚠️ 位置解析失败，仅使用日期时间");
                }
            } else {
                notifyStep("环境感知", "⚠️ 位置获取失败，仅使用日期时间");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Location fetch for env context failed: " + e.getMessage());
            notifyStep("环境感知", "⚠️ 位置获取超时，仅使用日期时间");
        }

        sb.append("（以上环境信息已自动获取，回答时可据此理解\"今天\"、\"附近\"等指代）");
        return sb.toString();
    }

    /** 从location工具返回的JSON中提取城市名 */
    private String extractCityFromLocationResult(String result) {
        try {
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            if (json.has("city")) return json.get("city").getAsString();
            if (json.has("cityName")) return json.get("cityName").getAsString();
            if (json.has("address")) {
                String addr = json.get("address").getAsString();
                // 尝试从地址中提取城市
                String[] parts = addr.split("[省市自治区]");
                if (parts.length > 0) return parts[0].replace("省", "").trim();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to extract city from location result: " + e.getMessage());
        }
        return null;
    }

    /** 从location工具返回的JSON中提取区/县名 */
    private String extractDistrictFromLocationResult(String result) {
        try {
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            if (json.has("district")) return json.get("district").getAsString();
            if (json.has("address")) {
                return json.get("address").getAsString();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /** 从weather工具返回的JSON中提取天气摘要 */
    private String extractWeatherSummary(String result) {
        try {
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            StringBuilder w = new StringBuilder();
            if (json.has("temp")) w.append(json.get("temp").getAsString()).append("°C");
            else if (json.has("temperature")) w.append(json.get("temperature").getAsString()).append("°C");
            if (json.has("text")) w.append(" ").append(json.get("text").getAsString());
            else if (json.has("weather")) w.append(" ").append(json.get("weather").getAsString());
            if (json.has("humidity")) w.append(" 湿度").append(json.get("humidity").getAsString()).append("%");
            return w.length() > 0 ? w.toString() : null;
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to extract weather summary: " + e.getMessage());
        }
        return null;
    }

    /**
     * 统计工具定义 JSON 中的工具数量。
     */
    private int countToolsInJson(String toolsJson) {
        if (toolsJson == null || toolsJson.isEmpty() || toolsJson.equals("[]")) return 0;
        try {
            JsonArray arr = JsonParser.parseString(toolsJson).getAsJsonArray();
            return arr.size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== Agent 能力检测 ==========

    /**
     * 检测在线模型是否具备完整 agent 能力（原生 function calling + 多轮自主推理）。
     *
     * 检测策略（按优先级）：
     * 1. 模型名启发式：匹配已知支持 function calling 的模型族
     * 2. 默认保守：未知模型降级为辅助模式
     *
     * 已知支持 function calling 的模型族（持续扩充）：
     * - OpenAI: gpt-4*, gpt-3.5-turbo-1106+, gpt-5*, o1*
     * - Anthropic: claude-3*, claude-sonnet, claude-opus, claude-haiku
     * - DeepSeek: deepseek-chat, deepseek-v2/v3, deepseek-reasoner
     * - Qwen: qwen-plus, qwen-max, qwen-turbo, qwen2.5*, qwen3*
     * - GLM: glm-4*, glm-5*
     * - Moonshot/Kimi: moonshot-v1*, kimi
     * - Yi: yi-large, yi-medium
     * - 豆包: doubao-pro*
     * - Gemini: gemini-1.5*, gemini-2*
     * - MiniMax: abab6.5*
     * - 其他主流：dbrx, command-r+, mistral-large, mixtral
     *
     * @param cfg 在线模型配置
     * @return true 表示模型具备 agent 能力，应启用接管模式
     */
    private boolean detectAgentCapability(OnlineModelManager.OnlineModelConfig cfg) {
        if (cfg == null) return false;
        // 优先使用 selectedModel，其次 modelName
        String model = cfg.selectedModel != null && !cfg.selectedModel.isEmpty()
            ? cfg.selectedModel : cfg.modelName;
        if (model == null || model.isEmpty()) return false;

        String m = model.toLowerCase();
        // 已知支持 function calling 的模型族匹配
        if (m.contains("gpt-4") || m.contains("gpt-5") || m.contains("gpt-4o")
            || m.contains("gpt-3.5-turbo-1106") || m.contains("gpt-3.5-turbo-0125")
            || (m.contains("gpt-3.5") && !m.contains("instruct"))
            || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")) return true;
        if (m.contains("claude-3") || m.contains("claude-sonnet") || m.contains("claude-opus")
            || m.contains("claude-haiku") || m.contains("claude-3.5")) return true;
        if (m.contains("deepseek-chat") || m.contains("deepseek-v2") || m.contains("deepseek-v3")
            || m.contains("deepseek-reasoner") || m.contains("deepseek-coder")) return true;
        if (m.contains("qwen-plus") || m.contains("qwen-max") || m.contains("qwen-turbo")
            || m.contains("qwen2.5") || m.contains("qwen3") || m.contains("qwen-")) return true;
        if (m.contains("glm-4") || m.contains("glm-5") || m.contains("glm4") || m.contains("glm5")) return true;
        if (m.contains("moonshot") || m.contains("kimi") || m.contains("yi-large") || m.contains("yi-medium")) return true;
        if (m.contains("doubao-pro") || m.contains("doubao-1")) return true;
        if (m.contains("gemini-1.5") || m.contains("gemini-2")) return true;
        if (m.contains("abab6") || m.contains("abab7")) return true;
        if (m.contains("dbrx") || m.contains("command-r") || m.contains("mistral-large")
            || m.contains("mixtral")) return true;
        // 兜底：未知模型保守降级为辅助模式
        return false;
    }

    /**
     * 获取当前执行模式。
     */
    public AgentMode getAgentMode() {
        return agentMode;
    }

    /**
     * 手动覆盖执行模式（用于配置或调试）。
     */
    public void setAgentMode(AgentMode mode) {
        if (mode != null) this.agentMode = mode;
    }

    // ========== 控制方法 ==========

    public void cancel() {
        isCancelled.set(true);
    }

    public boolean isGenerating() {
        return isGenerating.get();
    }

    public int getToolLoopCount() {
        return toolLoopCount.get();
    }

    public OnlineThinkingChain getThinkingChain() {
        return thinkingChain;
    }

    public void clearHistory() {
        messageHistory.clear();
        thinkingChain.clear();
    }

    // ========== 内部通知方法 ==========

    private void finishGeneration() {
        isGenerating.set(false);
    }

    private void notifyComplete(String text) {
        finishGeneration();
        notifyProgress();
        thinkingChain.completeAll();
        // 输出工具使用统计日志
        if (toolManager != null && toolManager.getUsageTracker() != null) {
            AILogger.i(TAG, "Usage stats: " + toolManager.getUsageStats());
            // 输出发现的工具组合模式
            List<OnlineToolUsageTracker.ToolPattern> patterns = toolManager.getUsageTracker().discoverPatterns();
            if (!patterns.isEmpty()) {
                StringBuilder pb = new StringBuilder("Discovered tool patterns: ");
                for (OnlineToolUsageTracker.ToolPattern p : patterns) {
                    pb.append(p.toString()).append("; ");
                }
                AILogger.i(TAG, pb.toString());
            }
        }
        activity.runOnUiThread(() -> {
            if (callback != null) callback.onComplete(text);
        });
    }

    private void notifyError(String error) {
        finishGeneration();
        activity.runOnUiThread(() -> {
            if (callback != null) callback.onError(error);
        });
    }

    private void notifyStep(String step, String detail) {
        activity.runOnUiThread(() -> {
            if (callback != null) callback.onStepUpdate(step, detail);
        });
    }

    private void notifyExecutionStep(OnlineExecutionStep step, String detail) {
        activity.runOnUiThread(() -> {
            if (callback != null) callback.onExecutionStep(step, detail);
        });
    }

    private void notifyProgress() {
        if (progressListener == null) return;
        long elapsed = System.currentTimeMillis() - inferenceStartTime;
        float tps = elapsed > 0 ? (totalTokenCount * 1000f / elapsed) : 0;
        final int tokens = totalTokenCount;
        final float tpsFinal = tps;
        activity.runOnUiThread(() -> {
            if (progressListener != null) {
                progressListener.onProgressUpdate(tokens, tpsFinal);
            }
        });
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseCount = 0;
        int otherCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chineseCount++;
            else otherCount++;
        }
        return (int) (chineseCount * 0.7 + otherCount * 0.25);
    }

    /**
     * 单轮迭代结果
     */
    private static class IterationResult {
        String content = "";
        String reasoningContent = "";
        List<OnlineInferenceService.ToolCallInfo> toolCalls;
        /** 完成原因：stop/tool_calls/length/content_filter/null */
        String finishReason;
    }
}
