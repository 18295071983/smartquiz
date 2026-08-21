package com.oilquiz.app.ai.agent.online;

import android.app.Activity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.InferenceProgressListener;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
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
    /** 历史摘要消息最大长度（字符），超出只保留最新部分，防止摘要本身撑爆上下文 */
    private static final int MAX_SUMMARY_LENGTH = 8000;
    /** 模型生成摘要的输入对话文本上限（字符），防止单次摘要请求过大 */
    private static final int SUMMARY_MODEL_INPUT_MAX = 8000;
    /** 模型生成摘要的超时（毫秒），超时回退规则式摘录，避免阻塞主对话 */
    private static final long SUMMARY_MODEL_TIMEOUT_MS = 20000;
    /** 对话历史 token 预算：超过则自动把早期消息压缩为摘要。
     *  取 24000（低于常见 32K/64K 上下文窗口留出余量），触发即压缩，
     *  避免长对话缓存命中失败 + 大量消耗输入 token。 */
    private static final int HISTORY_TOKEN_BUDGET = 24000;

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

    /** 本轮是否深度思考：true 时向 API 请求传 thinking 参数（DeepSeek 等返回 reasoning_content） */
    private volatile boolean enableThinking = false;

    /** 当前会话 ID（null/空 = 默认单文件历史；非空 = 按会话隔离的历史文件） */
    private volatile String sessionId;

    private AgentCallback callback;
    private InferenceProgressListener progressListener;
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicInteger toolLoopCount = new AtomicInteger(0);

    // OpenAI 格式消息历史（直接使用 JsonObject，支持 tool 角色消息）
    private final List<JsonObject> messageHistory = new ArrayList<>();
    /** 恢复历史时暂存的【对话历史摘要】消息，execute 重建 system 后插回（防止压缩内容跨会话丢失） */
    private JsonObject pendingSummaryMessage;

    // 推理进度统计
    private long inferenceStartTime;
    private int totalTokenCount;
    /** 最近一次推理的缓存命中 token 数（DeepSeek prompt_cache_hit_tokens / OpenAI cached_tokens） */
    private volatile int lastCacheHitTokens = 0;
    /** 最近一次推理的输入/输出 token（API usage，用于统计展示） */
    private volatile int lastPromptTokens = 0;
    private volatile int lastCompletionTokens = 0;
    /** 本次执行（一次用户消息，含多轮工具调用）累计的输入/输出 token —— 真正的总消耗 */
    private volatile int execTotalPromptTokens = 0;
    private volatile int execTotalCompletionTokens = 0;

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
        // 恢复上次的对话历史（Activity 重建/应用重启后保持前缀稳定，利于服务商前缀缓存命中）
        restoreHistory();
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
     *
     * @param enableThinking 是否开启深度思考（true 时向 API 请求传 thinking 参数，
     *                       DeepSeek 等模型返回 reasoning_content 思考链）
     */
    public void execute(String userMessage, int maxTokens) {
        execute(userMessage, maxTokens, false);
    }

    public void execute(String userMessage, int maxTokens, boolean enableThinking) {
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
        // 记录本轮是否深度思考：贯穿到 API 请求（thinking 参数 → reasoning_content）
        this.enableThinking = enableThinking;
        // 不清除 messageHistory，保留对话上下文实现连续对话
        // 仅清除本轮推理的状态
        thinkingChain.clear();
        toolLoopCount.set(0);
        totalTokenCount = 0;
        // 重置本次执行的累计 token（一次用户消息 = 多轮工具调用 + 最终回答）
        execTotalPromptTokens = 0;
        execTotalCompletionTokens = 0;
        lastPromptTokens = 0;
        lastCompletionTokens = 0;
        lastCacheHitTokens = 0;
        inferenceStartTime = System.currentTimeMillis();

        int effectiveMaxTokens = maxTokens > 0 ? maxTokens : MAX_TOKENS;

        executor.submit(() -> {
            try {
                doExecute(userMessage, effectiveMaxTokens);
                // 本轮 Agent 执行完成（含工具多轮），持久化对话历史，供 Activity 重建/重启后恢复
                persistHistory();
            } catch (Throwable t) {
                AILogger.e(TAG, "Execute failed: " + t.getMessage(), t);
                finishGeneration();
                notifyError("执行中断: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            } finally {
                // 任务结束清理临时工作区（tmp/ 执行中间文件/缓存/进度）——
                // 工作区是临时执行空间，长期产物在 files/，临时缓存不跨任务保留
                try {
                    int removed = com.oilquiz.app.ai.agent.online.AgentWorkspace
                            .getInstance(activity).clearTmp();
                    if (removed > 0) {
                        AILogger.i(TAG, "任务结束清理临时工作区: 删除 " + removed + " 个临时文件");
                    }
                } catch (Throwable ignored) {
                }
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

        // 1. 无 system 消息时注入系统提示词和环境上下文（恢复历史时会丢弃旧 system，
        //    保证始终使用当前版本的提示词；连续对话时已有 system，跳过）
        if (!hasSystemMessage()) {
            String systemPrompt = agentMode == AgentMode.TAKEOVER
                ? promptBuilder.buildSystemPromptTakeover()
                : promptBuilder.buildSystemPrompt();
            // 深度思考：开启时按模型名注入对应思考指令（DeepSeek/Qwen3/o系列等指令不同，
            // API thinking 参数触发 reasoning_content，此指令强化思考质量）
            if (enableThinking) {
                systemPrompt += "\n【深度思考】"
                        + com.oilquiz.app.ai.model.OnlineModelManager.getThinkingInstruction(
                                cfg != null ? cfg.modelName : null)
                        + "\n";
            }
            // 注入工作区路径：Agent 生成的文件默认在工作区，明确告知路径与访问方式
            try {
                com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                        com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(activity);
                String wsPath = ws.getWorkspacePath();
                String wsLocation = ws.isPublicWorkspace()
                        ? "公共目录(Download/OilQuiz，用户可直接看到和管理)"
                        : "应用私有目录(用户需通过App管理页查看)";
                systemPrompt += "\n【文件与工作区】你有专属文件工作目录（工作区）: " + wsPath
                        + "（" + wsLocation + "）"
                        + "\n【何时生成文件】用户要求「写/生成/创建/导出」文档、报告、配置、代码、Markdown、表格等时，用 file_generator 工具生成；"
                        + "要求画图时用 image_gen。"
                        + "\n【长期 vs 临时】用户要保留的产物（报告/文档/图片/导出）用 file_generator/image_gen 生成，默认存入长期文件区 files/（跨任务保留）；"
                        + "执行过程的中间文件/缓存/进度是临时的，不要刻意保留。"
                        + "\n【如何管理文件】生成后用 workspace 工具管理："
                        + "workspace(action=list) 查看工作区文件（[长期]/[临时]标记）；workspace(action=read, fileName=文件名) 读取文本内容；"
                        + "workspace(action=delete, fileName=文件名) 删除；workspace(action=clear) 清空。"
                        + "也可用 file_reader 读取工具返回的绝对路径。"
                        + "\n【权限请求】如果工作区在私有目录，且用户要求文件可被直接看到/分享/备份，可主动用 "
                        + "permission_manager(action=request_and_wait, permission=存储) 请求「所有文件访问」权限"
                        + "（授权后工作区自动切换到公共目录 Download/OilQuiz，文件对所有 App 可见）。"
                        + "其他权限同理：需要相机/录音/定位/通知等时，先 permission_manager(action=request_and_wait, permission=对应权限名)。"
                        + "\n【重要】不要用 /storage/emulated/0/ 猜测工作区文件路径（绝对路径以工具返回为准）；"
                        + "引用工作区文件用相对路径（如 <a href=\"report.md\">），用户点击会在 App 内预览。\n"
                        + "【文件链接规则】生成 HTML 页面时自动适配本环境："
                        + "引用工作区里的文件用相对路径（如 <a href=\"report.md\">），"
                        + "用户点击会在 App 内自动预览（md/文本/表格/pdf 等按类型打开）；"
                        + "引用网页用 https:// 链接（App 内打开）。"
                        + "生成文件后如需在页面中引用，用与页面同目录的文件名即可。\n";
            } catch (Throwable t) {
                AILogger.w(TAG, "注入工作区信息失败: " + t.getMessage());
            }
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", systemPrompt);
            // 插到最前（恢复的历史可能是 用户/助手 消息，system 必须在前）
            messageHistory.add(0, systemMsg);

            // 注入长期记忆摘要（跨会话持久化的用户偏好/事实），帮助模型"记得你"
            String memorySummary = AgentMemoryStore.getInstance(activity).buildMemorySummary();
            if (memorySummary != null && !memorySummary.isEmpty()) {
                JsonObject memoryMsg = new JsonObject();
                memoryMsg.addProperty("role", "system");
                memoryMsg.addProperty("content", "【长期记忆】以下是你记住的关于用户的信息，回答时自然运用。仅当用户明确要求记住或主动告知新的个人信息/偏好时，才用 memory 工具 save 新增或更新（不要擅自把普通聊天内容存为记忆）：\n" + memorySummary);
                messageHistory.add(1, memoryMsg);
                AILogger.i(TAG, "Long-term memory injected: " + AgentMemoryStore.getInstance(activity).size() + " entries");
            }

            // 获取环境上下文（日期、位置、天气），注入为系统消息辅助Agent思考
            notifyStep("环境感知", "正在获取位置和天气信息...");
            String envContext = buildEnvironmentContext();
            if (envContext != null && !envContext.isEmpty()) {
                JsonObject envMsg = new JsonObject();
                envMsg.addProperty("role", "system");
                envMsg.addProperty("content", envContext);
                messageHistory.add(envMsg);
                AILogger.i(TAG, "Environment context injected: " + envContext.length() + " chars");
            }
            AILogger.i(TAG, "System prompt injected: history=" + messageHistory.size() + " messages");
        } else {
            // 已有 system：检查环境上下文日期是否过期（跨天继续对话时刷新"今天"）
            refreshEnvIfStale();
            AILogger.i(TAG, "Continuing conversation: messageHistory size=" + messageHistory.size());
        }

        // 1.5 恢复历史时暂存的【对话历史摘要】插回（在所有 system 消息之后，保持前缀稳定：
        // 提示词/长期记忆/环境上下文在摘要之前，跨会话不因摘要内容变化导致前缀 miss）
        if (pendingSummaryMessage != null) {
            messageHistory.add(pendingSummaryMessage);
            pendingSummaryMessage = null;
            AILogger.i(TAG, "Summary message re-inserted after system messages");
        }

        // 2. 添加用户消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messageHistory.add(userMsg);

        // 3. 通过 OnlineToolManager 获取工具定义。
        // 全量注入：在线 API 通常有 prompt caching（前缀缓存，system+工具定义不变即命中）且长上下文支持，
        // 全量工具定义成本可忽略，且让模型自行探索/组合任意工具（不做关键词裁剪限制能力）。
        String toolsJson = toolManager.getToolDefinitions();
        int toolCount = countToolsInJson(toolsJson);
        AILogger.i(TAG, "Tool definitions: count=" + toolCount + ", json_len=" + (toolsJson != null ? toolsJson.length() : 0));
        if (toolCount == 0) {
            AILogger.w(TAG, "No tools available! Agent will run without tool calling capability.");
        }

        // 4. Agent 主循环
        int iteration = 0;
        // 连续暗示"要调用工具"但未输出正确 tool_calls 格式的次数，用于防死循环
        int consecutiveHintForToolCount = 0;
        final int MAX_CONSECUTIVE_TOOL_HINT = 2; // 连续 2 次都暗示要调工具却格式不对，第 3 次强制终止

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
                // ========= 无 tool_calls =========
                AILogger.i(TAG, "No tool calls, content_len=" + result.content.length()
                    + " finish_reason=" + result.finishReason
                    + " iteration=" + iteration + "/" + maxIterations
                    + " consecutive_tool_hint=" + consecutiveHintForToolCount);

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

                // 【关键判断】思考链中是否暗示了要调用工具？
                boolean hintToCallTool = !finalAnswer.isEmpty() && hintForToolCall(finalAnswer);

                // 1. 模型暗示要调用工具，但格式输出错误没生成 tool_calls → 不终止，提醒它用正确格式
                if (hintToCallTool && consecutiveHintForToolCount < MAX_CONSECUTIVE_TOOL_HINT
                    && iteration < maxIterations) {
                    consecutiveHintForToolCount++;
                    AILogger.i(TAG, "Model hints for tool call in content (count="
                        + consecutiveHintForToolCount + "), prompting correct format");

                    // 把本轮回复加入历史
                    JsonObject assistantMsg = new JsonObject();
                    assistantMsg.addProperty("role", "assistant");
                    assistantMsg.addProperty("content", finalAnswer);
                    // thinking 模式：assistant 消息必须回传 reasoning_content（否则下轮 400）
                    if (result.reasoningContent != null && !result.reasoningContent.isEmpty()) {
                        assistantMsg.addProperty("reasoning_content", result.reasoningContent);
                    }
                    messageHistory.add(assistantMsg);

                    // 发一条系统提示：提醒它用标准的 tool_calls JSON 格式输出，而不是在内容里描述
                    JsonObject formatHint = new JsonObject();
                    formatHint.addProperty("role", "system");
                    formatHint.addProperty("content",
                        "【格式提示】\n"
                      + "你在上一轮回复中表达了要调用工具的意图，但没有使用标准的 tool_calls JSON 格式输出。\n"
                      + "正确做法：将工具调用放在 tool_calls 数组中（name=工具名, arguments=参数），而不是写在文本里。\n"
                      + "请立即使用正确的 JSON 格式输出工具调用，不要重复描述。\n"
                      + "（连续 2 次格式错误将终止推理）");
                    messageHistory.add(formatHint);

                    notifyStep("格式修正", "提示模型使用标准工具调用格式（重试 "
                        + consecutiveHintForToolCount + "/" + MAX_CONSECUTIVE_TOOL_HINT + "）");
                    continue; // 继续下一轮，让模型重新输出正确格式
                }

                // 2. 连续暗示超过上限 / 迭代到最大轮次 / 真的是最终回答 → 返回
                if (hintToCallTool && consecutiveHintForToolCount >= MAX_CONSECUTIVE_TOOL_HINT) {
                    AILogger.w(TAG, "Model hinted tool call for " + consecutiveHintForToolCount
                        + " consecutive times without valid format; terminating with content");
                    finalAnswer = finalAnswer + "\n\n（提示：模型已连续 " + consecutiveHintForToolCount
                        + " 次试图调用工具但未使用正确格式，请尝试用更简洁的方式提问）";
                }

                JsonObject assistantMsg = new JsonObject();
                assistantMsg.addProperty("role", "assistant");
                assistantMsg.addProperty("content", finalAnswer);
                // thinking 模式：assistant 消息必须回传 reasoning_content（否则下轮 400）
                if (result.reasoningContent != null && !result.reasoningContent.isEmpty()) {
                    assistantMsg.addProperty("reasoning_content", result.reasoningContent);
                }
                messageHistory.add(assistantMsg);

                AILogger.i(TAG, "Returning final answer (iteration " + iteration
                    + "/" + maxIterations + ", mode=" + agentMode
                    + ", hinted_tool=" + hintToCallTool + ")");
                notifyExecutionStep(OnlineExecutionStep.COMPLETED, "完成");
                notifyComplete(finalAnswer);
                return;
            }

            // ===== 有工具调用：重置"连续暗示次数"计数 =====
            consecutiveHintForToolCount = 0;

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
            // DeepSeek thinking 模式硬性要求：assistant 消息必须原样回传 reasoning_content，
            // 否则下一轮请求 HTTP 400（"The reasoning_content in the thinking mode must be passed back"）
            if (result.reasoningContent != null && !result.reasoningContent.isEmpty()) {
                assistantMsg.addProperty("reasoning_content", result.reasoningContent);
            }
            // 统一生成 tool_call id：assistant.tool_calls 与后续 tool 消息必须严格配对
            // （模型未提供 id 时直接写回 tc.id，保证两处引用同一 id）
            JsonArray toolCallsArray = new JsonArray();
            for (int ci = 0; ci < result.toolCalls.size(); ci++) {
                OnlineInferenceService.ToolCallInfo tc = result.toolCalls.get(ci);
                if (tc.id == null || tc.id.trim().isEmpty()) {
                    tc.id = "call_" + System.nanoTime() + "_" + ci;
                }
                JsonObject tcObj = new JsonObject();
                tcObj.addProperty("id", tc.id);
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
                // 通知 UI 工具调用开始（id 已统一）
                activity.runOnUiThread(() -> {
                    final String callId = tc.id;
                    if (callback != null) callback.onToolCallStart(callId, tc.name, tc.arguments);
                });

                // 并行执行（toolCallId 与 assistant.tool_calls 中的 id 一致）
                final String toolCallId = tc.id;
                CompletableFuture<OnlineToolResult> future = CompletableFuture.supplyAsync(
                    () -> executeToolCall(toolCallId, tc.name, tc.arguments), executor);
                toolFutures.add(future);
            }

            // 等待所有工具完成，并收集失败工具（可被 cancel() 打断）
            List<String> failedTools = new ArrayList<>();
            for (int i = 0; i < toolFutures.size(); i++) {
                if (isCancelled.get()) {
                    // 取消剩余未完成的工具执行
                    for (CompletableFuture<OnlineToolResult> f : toolFutures) {
                        f.cancel(true);
                    }
                    finishGeneration();
                    notifyError("工具执行已中断");
                    return;
                }
                final OnlineInferenceService.ToolCallInfo tc = result.toolCalls.get(i);
                try {
                    // 工具等待超时：默认 60 秒；ui_component 的 get_result（阻塞等待用户交互）用长超时
                    // （120 秒），避免用户点组件按钮/对话框期间被引擎超时中断，导致 Agent"越过交互"。
                    boolean isUserWait = "ui_component".equals(tc.name)
                            && tc.arguments != null && tc.arguments.contains("\"get_result\"");
                    long waitTimeoutSec = isUserWait ? 120 : 60;
                    OnlineToolResult toolResult = toolFutures.get(i).get(waitTimeoutSec, TimeUnit.SECONDS);
                    // 通知 UI 工具调用完成
                    final OnlineToolResult tr = toolResult;
                    activity.runOnUiThread(() -> {
                        if (callback != null) callback.onToolCallComplete(tr.toolCallId, tr.toolName, tr);
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
                    // 配对保障：工具执行异常/超时也必须补充对应的 tool 消息，
                    // 否则 assistant.tool_calls 无响应（孤儿 tool_call）导致 API 拒绝或上下文错乱
                    try {
                        JsonObject toolMsg = new JsonObject();
                        toolMsg.addProperty("role", "tool");
                        toolMsg.addProperty("tool_call_id", tc.id);
                        toolMsg.addProperty("name", tc.name);
                        toolMsg.addProperty("content", "工具执行异常: " + e.getMessage());
                        messageHistory.add(toolMsg);
                        AILogger.i(TAG, "补充失败 tool 响应(配对保障): " + tc.id);
                    } catch (Exception inner) {
                        AILogger.w(TAG, "补充 tool 响应失败: " + inner.getMessage());
                    }
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
     * 截断并压缩消息历史（发送前调用）。
     * 触发条件：消息条数超过 MAX_HISTORY_MESSAGES，或估算 token 超过 HISTORY_TOKEN_BUDGET。
     * 策略：保留首条 system 消息 + 摘要消息 + 最近 MAX_HISTORY_MESSAGES-1 条消息。
     * 安全保证：截断点不会落在 tool 消息上（否则其对应的 assistant.tool_calls 被移除，
     * 导致 API 报错）。若截断点处为 tool 消息，继续前移直至非 tool 消息。
     * 摘要：把移除的旧消息压缩为一条 system 摘要消息（长对话"不失忆"且省 token）。
     */
    private void trimMessageHistory(OnlineModelManager.OnlineModelConfig cfg) {
        // 计算消息数超限还是 token 超预算
        int tokenCount = 0;
        for (JsonObject msg : messageHistory) {
            String content = msg.has("content") && !msg.get("content").isJsonNull()
                    ? msg.get("content").getAsString() : "";
            tokenCount += estimateTokens(content);
        }
        boolean overBudget = tokenCount > HISTORY_TOKEN_BUDGET;
        boolean overCount = messageHistory.size() > MAX_HISTORY_MESSAGES;
        if (!overBudget && !overCount) return;

        // 计算需要移除的条数：优先满足 token 预算（移除到预算的一半，避免频繁触发），再满足条数上限
        int toRemove = 0;
        if (overBudget) {
            int running = 0;
            for (int i = 1; i < messageHistory.size(); i++) {
                JsonObject msg = messageHistory.get(i);
                String content = msg.has("content") && !msg.get("content").isJsonNull()
                        ? msg.get("content").getAsString() : "";
                running += estimateTokens(content);
                toRemove++;
                if (running >= tokenCount - HISTORY_TOKEN_BUDGET / 2) break;
            }
        }
        if (overCount) {
            toRemove = Math.max(toRemove, messageHistory.size() - MAX_HISTORY_MESSAGES);
        }
        toRemove = Math.min(toRemove, messageHistory.size() - 2); // 至少留 system + 1 条

        int cutoff = 1 + toRemove;
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
            // 找插入位置：摘要插到所有 system 消息之后（index 0=提示词，1=长期记忆，2=环境上下文），
            // 避免摘要挤掉/覆盖长期记忆与环境上下文
            int systemEnd = 0;
            while (systemEnd < messageHistory.size()) {
                JsonObject m = messageHistory.get(systemEnd);
                String r = m.has("role") ? m.get("role").getAsString() : "";
                if ("system".equals(r)) systemEnd++;
                else break;
            }
            // 已有历史摘要消息（system 且含【对话历史摘要】）→ 保留它（避免"摘要的摘要"），
            // 从其后移除旧消息并追加新内容
            int removeStart = systemEnd;
            boolean hasSummary = false;
            for (int i = 0; i < systemEnd && i < messageHistory.size(); i++) {
                JsonObject m = messageHistory.get(i);
                String content = m.has("content") && !m.get("content").isJsonNull()
                        ? m.get("content").getAsString() : "";
                if (content != null && content.contains("【对话历史摘要】")) {
                    hasSummary = true;
                    removeStart = i + 1;
                    break;
                }
            }
            // 短期记忆增强：将移除的旧消息压缩为摘要，保留早期上下文要点（而非直接丢弃，避免"失忆"）
            String summary = buildHistorySummary(removeStart, Math.max(removeStart, cutoff), cfg);
            messageHistory.subList(removeStart, Math.max(removeStart, cutoff)).clear();
            if (summary != null && !summary.isEmpty()) {
                if (hasSummary) {
                    // 已有摘要：追加新移除消息的摘要（避免新内容丢失）
                    for (int i = 0; i < systemEnd && i < messageHistory.size(); i++) {
                        JsonObject m = messageHistory.get(i);
                        String content = m.has("content") && !m.get("content").isJsonNull()
                                ? m.get("content").getAsString() : "";
                        if (content != null && content.contains("【对话历史摘要】")) {
                            String merged = content + "\n" + summary;
                            // 摘要过长时只保留最新部分，防止摘要本身撑爆上下文
                            if (merged.length() > MAX_SUMMARY_LENGTH) {
                                merged = merged.substring(merged.length() - MAX_SUMMARY_LENGTH);
                            }
                            m.addProperty("content", merged);
                            break;
                        }
                    }
                } else {
                    JsonObject summaryMsg = new JsonObject();
                    summaryMsg.addProperty("role", "system");
                    summaryMsg.addProperty("content", "【对话历史摘要】以下是本对话更早内容的压缩摘要（详细内容已省略以节省上下文，回答可参考）：\n" + summary);
                    messageHistory.add(systemEnd, summaryMsg);
                }
            }
            AILogger.i(TAG, "Trimmed message history: removed " + actualRemoved
                + " old messages (summarized), " + messageHistory.size() + " remaining"
                + ", tokens " + tokenCount + " -> budget " + HISTORY_TOKEN_BUDGET);
        }
    }

    /**
     * 生成历史摘要：优先用在线模型把被移除的对话语义压缩成真正摘要
     * （保留关键信息：用户需求、结论、重要事实、未完成事项），
     * 模型失败/超时/无配置时回退规则式摘录（首条用户消息 + 最近几条 用户/助手 消息）。
     * 工具消息不参与摘要（其信息已体现在后续助手回复中）。
     */
    private String buildHistorySummary(int fromIndex, int toIndex,
                                       OnlineModelManager.OnlineModelConfig cfg) {
        // 1. 收集被移除范围内的 用户/助手 消息正文，作为模型摘要的输入
        StringBuilder dialogue = new StringBuilder();
        for (int i = fromIndex; i < toIndex && i < messageHistory.size(); i++) {
            JsonObject msg = messageHistory.get(i);
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            String content = msg.has("content") ? msg.get("content").getAsString() : "";
            if (content == null || content.isEmpty()) continue;
            if ("user".equals(role)) {
                if (dialogue.length() > 0) dialogue.append("\n");
                dialogue.append("用户: ").append(truncateForSummary(content, 300));
            } else if ("assistant".equals(role)) {
                if (dialogue.length() > 0) dialogue.append("\n");
                dialogue.append("助手: ").append(truncateForSummary(content, 300));
            }
            if (dialogue.length() > SUMMARY_MODEL_INPUT_MAX) break; // 输入过长则截断
        }
        if (dialogue.length() == 0) return null;

        // 2. 优先模型语义压缩（非流式单次请求，显式关闭 thinking 快速返回）
        if (cfg != null) {
            try {
                String prompt = "请将以下AI对话压缩成一份简洁的中文摘要（保留关键信息：用户需求、结论、重要事实、未完成事项），"
                        + "不超过500字，直接输出摘要内容：\n\n" + dialogue;
                String summary = onlineInferenceService.generateOnceAsync(prompt, cfg, 1024)
                        .get(SUMMARY_MODEL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (summary != null && !summary.trim().isEmpty()) {
                    AILogger.i(TAG, "Model-generated history summary: " + summary.trim().length()
                        + " chars (from " + dialogue.length() + " chars dialogue)");
                    return summary.trim();
                }
            } catch (Exception e) {
                AILogger.w(TAG, "Model summary generation failed, falling back to rule-based: " + e.getMessage());
            }
        }

        // 3. 回退：规则式摘录（零成本、无失败风险）
        return buildRuleBasedSummary(fromIndex, toIndex);
    }

    /** 规则式摘要回退：首条用户消息（对话主题）+ 末尾最近的 用户/助手 消息，每条截取前 100 字符 */
    private String buildRuleBasedSummary(int fromIndex, int toIndex) {
        try {
            StringBuilder sb = new StringBuilder();
            int userCount = 0;
            for (int i = fromIndex; i < toIndex && i < messageHistory.size(); i++) {
                JsonObject msg = messageHistory.get(i);
                String role = msg.has("role") ? msg.get("role").getAsString() : "";
                String content = msg.has("content") ? msg.get("content").getAsString() : "";
                if (content == null || content.isEmpty()) continue;
                if ("user".equals(role)) {
                    userCount++;
                    if (userCount == 1 || i >= toIndex - 4) {
                        appendSummaryLine(sb, "用户", content);
                    }
                } else if ("assistant".equals(role)) {
                    if (i >= toIndex - 4) {
                        appendSummaryLine(sb, "助手", content);
                    }
                }
                if (sb.length() > 2000) break;
            }
            return sb.toString();
        } catch (Exception e) {
            AILogger.w(TAG, "buildRuleBasedSummary failed: " + e.getMessage());
            return null;
        }
    }

    private void appendSummaryLine(StringBuilder sb, String who, String content) {
        // 只取每段前 100 字符，避免工具输出/长文本撑爆摘要
        String text = content.replace("\n", " ").trim();
        if (text.length() > 100) text = text.substring(0, 100) + "...";
        if (sb.length() > 0) sb.append("\n");
        sb.append(who).append(": ").append(text);
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
        trimMessageHistory(cfg);

        JsonArray messagesArray = new JsonArray();
        for (JsonObject msg : messageHistory) {
            messagesArray.add(msg);
        }

        onlineInferenceService.generateStreamWithToolsV2(messagesArray, cfg, maxTokens, toolsJson,
            enableThinking,
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
                    // 清理模型输出中的乱码/非法字符
                    if (fullContent != null) {
                        String cleaned = ToolResultInterpreter.cleanModelOutput(fullContent);
                        if (cleaned != null) {
                            fullContent = cleaned;
                        } else {
                            fullContent = ToolResultInterpreter.sanitize(fullContent);
                            AILogger.w(TAG, "流式输出检测为乱码，已清理非法字符");
                        }
                    }
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

                @Override
                public void onUsageWithCache(int promptTokens, int completionTokens, int totalTokens, int cachedTokens) {
                    totalTokenCount = totalTokens;
                    lastCacheHitTokens = cachedTokens;
                    lastPromptTokens = promptTokens;
                    lastCompletionTokens = completionTokens;
                    // 累加本次执行的总消耗：API usage 每轮返回的是「本轮请求的完整输入」（含历史前缀），
                    // 多轮工具调用时每轮输入都在增长，必须逐轮累加才是真实总输入
                    execTotalPromptTokens += promptTokens;
                    execTotalCompletionTokens += completionTokens;
                    AILogger.i(TAG, "Token usage: prompt=" + promptTokens + " completion=" + completionTokens
                        + " total=" + totalTokens + " cache_hit=" + cachedTokens
                        + " | exec累计: in=" + execTotalPromptTokens + " out=" + execTotalCompletionTokens);
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

        // 1. 当前日期时间（始终可用）。
        // 注意：格式只到"日"级别，不含分钟——避免 Activity 重建后重新注入时
        // 前缀因分钟变化而不同，导致服务商前缀缓存 miss
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);
        String dateTime = sdf.format(new Date());
        sb.append("当前日期：").append(dateTime).append("\n");

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
        if (result == null || result.isEmpty()) return null;
        try {
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            StringBuilder w = new StringBuilder();
            if (json.has("temp")) w.append(json.get("temp").getAsString()).append("°C");
            else if (json.has("temperature")) w.append(json.get("temperature").getAsString()).append("°C");
            if (json.has("text")) w.append(" ").append(json.get("text").getAsString());
            else if (json.has("weather")) w.append(" ").append(json.get("weather").getAsString());
            if (json.has("humidity")) w.append(" 湿度").append(json.get("humidity").getAsString()).append("%");
            // 尝试从 formatted_result 嵌套字段提取
            if (w.length() == 0 && json.has("formatted_result")) {
                String fr = json.get("formatted_result").getAsString();
                if (fr != null && !fr.isEmpty()) return fr.length() > 100 ? fr.substring(0, 100) + "..." : fr;
            }
            return w.length() > 0 ? w.toString() : null;
        } catch (Exception e) {
            // JSON 解析失败，尝试从纯文本中提取温度等关键信息
            try {
                StringBuilder w = new StringBuilder();
                java.util.regex.Matcher tempMatcher = java.util.regex.Pattern.compile("(-?\\d+\\.?\\d*)\\s*°?C?").matcher(result);
                if (tempMatcher.find()) w.append(tempMatcher.group(1)).append("°C");
                if (w.length() > 0 && w.length() < 80) return w.toString();
            } catch (Exception ignored) {}
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

    /**
     * 检测文本中是否"暗示了要调用工具但未使用标准 tool_calls 格式"。
     *
     * 设计原则（避免误伤正常回答）：
     *   1. 不匹配单独的英文/中文工具名词（如 "weather"、"天气"）—— 这些在解释概念时也会出现
     *   2. 不匹配常见的过渡语（如 "首先查询"、"接下来获取"）—— 这些是模型正常推理过渡
     *   3. 只匹配"动作动词 + 紧邻的工具标识符"这种明确意图
     *   4. 匹配伪代码调用语法（如 "weather(...)"）和 JSON 片段中的工具名
     *
     * @return true 表示文本中强烈暗示要调用工具却没有正确输出 tool_calls
     */
    private boolean hintForToolCall(String content) {
        if (content == null) return false;
        String text = content.trim();
        if (text.isEmpty()) return false;
        // 只取前 800 字做判断，避免长文本拖慢性能
        if (text.length() > 800) text = text.substring(0, 800);
        String lower = text.toLowerCase(java.util.Locale.ROOT);

        // 工具标识符列表（仅作为"紧邻动词"或"伪代码调用"判断使用，不单独匹配）
        final String[] toolIds = {
            "env_loc", "env_time", "weather", "web_search", "search_tool",
            "wiki", "wikipedia", "calculator", "calc", "note_tool", "drawing_tool"
        };

        // 1. 伪代码调用语法：工具名 + 紧跟左括号（半角或全角）
        for (String tid : toolIds) {
            String tLower = tid.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains(tLower + "(") || lower.contains(tLower + " (")
                || lower.contains(tLower + "（") || lower.contains(tLower + " （")) {
                AILogger.i(TAG, "hintForToolCall matched: pseudo-call '" + tid + "(...)'");
                return true;
            }
        }

        // 2. JSON 片段中的工具名（如 {"name": "weather"} 但未被解析成 tool_calls）
        if (lower.contains("\"name\"")) {
            for (String tid : toolIds) {
                String tLower = tid.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("\"" + tLower + "\"")) {
                    AILogger.i(TAG, "hintForToolCall matched: JSON tool name '" + tid + "'");
                    return true;
                }
            }
        }

        // 3. 中文明确动作句式：动作动词 + 紧邻（≤6字）的工具标识符
        // 必须同时有动词和具体工具名，避免"调用工具"这种空泛表达误触发
        final String[] zhVerbs = {"调用", "使用", "执行", "运用", "启用", "需要调用", "我要调用", "我将调用", "准备调用"};
        for (String verb : zhVerbs) {
            int idx = text.indexOf(verb);
            while (idx >= 0) {
                int end = Math.min(text.length(), idx + verb.length() + 15);
                String window = text.substring(idx, end).toLowerCase(java.util.Locale.ROOT);
                for (String tid : toolIds) {
                    if (window.contains(tid.toLowerCase(java.util.Locale.ROOT))) {
                        AILogger.i(TAG, "hintForToolCall matched: zh verb '" + verb + "' + tool '" + tid + "'");
                        return true;
                    }
                }
                // 也支持"调用 weather 工具"这种"动词 + 工具名 + 工具"句式
                if (window.contains("工具") || window.contains("函数") || window.contains("接口")) {
                    AILogger.i(TAG, "hintForToolCall matched: zh verb '" + verb + "' + '工具/函数/接口'");
                    return true;
                }
                idx = text.indexOf(verb, idx + 1);
            }
        }

        // 4. 英文明确句式：必须同时含 (call/use/invoke) + (tool/function)
        // 不匹配 "let me check"、"get the current" 等过宽短语
        java.util.regex.Pattern pEnExplicit = java.util.regex.Pattern.compile(
            "(i will|i'll|i am going to|i need to|i want to|let me|please)\\s+"
          + "(call|use|invoke|execute)\\s+(the\\s+)?(\\w+\\s+)?(tool|function)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        if (pEnExplicit.matcher(lower).find()) {
            AILogger.i(TAG, "hintForToolCall matched: en explicit tool/function call intent");
            return true;
        }

        return false;
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
        // 优先使用配置字段（由 OnlineModelManager.refreshAllSupportsFunctionCalling 按模型名推断并持久化）
        if (cfg.supportsFunctionCalling) return true;
        // 兜底：字段缺失（旧版本未刷新）时按模型名关键词推断
        // 优先使用 selectedModel，其次 modelName
        String model = cfg.selectedModel != null && !cfg.selectedModel.isEmpty()
            ? cfg.selectedModel : cfg.modelName;
        return OnlineModelManager.isFunctionCallingModelName(model);
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

    public void shutdown() {
        cancel();
        messageHistory.clear();
        thinkingChain.clear();
    }

    public boolean isGenerating() {
        return isGenerating.get();
    }

    public int getToolLoopCount() {
        return toolLoopCount.get();
    }

    /** 最近一次推理的缓存命中 token 数（0 = 未命中或不支持） */
    public int getLastCacheHitTokens() {
        return lastCacheHitTokens;
    }

    /** 最近一次推理的输入 token（API usage） */
    public int getLastPromptTokens() {
        return lastPromptTokens;
    }

    /** 最近一次推理的输出 token（API usage） */
    public int getLastCompletionTokens() {
        return lastCompletionTokens;
    }

    /** 本次执行累计输入 token（一次用户消息含多轮工具调用的真实总输入） */
    public int getExecTotalPromptTokens() {
        return execTotalPromptTokens;
    }

    /** 本次执行累计输出 token（真实总输出） */
    public int getExecTotalCompletionTokens() {
        return execTotalCompletionTokens;
    }

    public OnlineThinkingChain getThinkingChain() {
        return thinkingChain;
    }

    public void clearHistory() {
        messageHistory.clear();
        thinkingChain.clear();
        deleteHistoryFile();
    }

    /**
     * 手动压缩对话：调用在线模型把早期对话生成摘要，替换为一条摘要消息 + 保留最近 N 条。
     * 用于长对话节省 tokens（早期细节压缩为要点，上下文不丢失）。
     *
     * @param keepRecent 保留最近的对话条数（用户/助手消息，不含 system/tool）
     * @param callback   完成回调（摘要文本或 null=失败）
     */
    public void compressHistory(final int keepRecent, final java.util.function.Consumer<String> callback) {
        if (isGenerating.get()) {
            if (callback != null) callback.accept(null);
            return;
        }
        final OnlineModelManager.OnlineModelConfig cfg = onlineInferenceService.getActiveConfig();
        if (cfg == null) {
            if (callback != null) callback.accept(null);
            return;
        }

        executor.submit(() -> {
            try {
                // 1. 收集早期对话（跳过 system/记忆/env，只取 用户/助手 正文）
                StringBuilder dialogue = new StringBuilder();
                int userMsgCount = 0;
                for (JsonObject msg : messageHistory) {
                    String role = msg.has("role") ? msg.get("role").getAsString() : "";
                    String content = msg.has("content") ? msg.get("content").getAsString() : "";
                    if (content == null || content.isEmpty()) continue;
                    if ("user".equals(role)) {
                        userMsgCount++;
                        if (dialogue.length() > 0) dialogue.append("\n");
                        dialogue.append("用户: ").append(truncateForSummary(content, 300));
                    } else if ("assistant".equals(role)) {
                        if (dialogue.length() > 0) dialogue.append("\n");
                        dialogue.append("助手: ").append(truncateForSummary(content, 300));
                    }
                }
                if (userMsgCount < 4) {
                    // 对话太短，压缩意义不大
                    if (callback != null) callback.accept(null);
                    return;
                }

                // 2. 调用模型生成摘要（非流式单次请求）
                String prompt = "请将以下AI对话压缩成一份简洁的中文摘要（保留关键信息：用户需求、结论、重要事实、未完成事项），" 
                        + "不超过 500 字，直接输出摘要内容：\n\n" + dialogue;
                String summary = onlineInferenceService.generateOnceAsync(prompt, cfg, 1024).get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (summary == null || summary.trim().isEmpty()) {
                    if (callback != null) callback.accept(null);
                    return;
                }
                summary = summary.trim();

                // 3. 保留 system 消息 + 摘要消息 + 最近 keepRecent 条 用户/助手 消息
                java.util.List<JsonObject> keepSystem = new java.util.ArrayList<>();
                java.util.List<JsonObject> recent = new java.util.ArrayList<>();
                int kept = 0;
                for (int i = messageHistory.size() - 1; i >= 0 && kept < keepRecent; i--) {
                    JsonObject msg = messageHistory.get(i);
                    String role = msg.has("role") ? msg.get("role").getAsString() : "";
                    if ("user".equals(role) || "assistant".equals(role)) {
                        recent.add(0, msg);
                        kept++;
                    }
                }
                java.util.List<JsonObject> newHistory = new java.util.ArrayList<>();
                for (JsonObject msg : messageHistory) {
                    String role = msg.has("role") ? msg.get("role").getAsString() : "";
                    if ("system".equals(role)) keepSystem.add(msg);
                }
                newHistory.addAll(keepSystem);
                JsonObject summaryMsg = new JsonObject();
                summaryMsg.addProperty("role", "system");
                summaryMsg.addProperty("content", "【历史对话摘要】以下是你与用户此前对话的摘要（早期详情已压缩，回答时可参考）：\n" + summary);
                newHistory.add(summaryMsg);
                newHistory.addAll(recent);

                messageHistory.clear();
                messageHistory.addAll(newHistory);
                persistHistory();
                AILogger.i(TAG, "History compressed: " + messageHistory.size() + " messages remain (summary=" + summary.length() + " chars)");
                if (callback != null) callback.accept(summary);
            } catch (Exception e) {
                AILogger.w(TAG, "compressHistory failed: " + e.getMessage());
                if (callback != null) callback.accept(null);
            }
        });
    }

    private String truncateForSummary(String text, int max) {
        if (text == null) return "";
        String t = text.replace("\n", " ").trim();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    /**
     * 清空所有会话的历史（含内存与全部历史文件）。
     * 用于"清空全部对话"操作（UI 层同时清空所有会话）。
     */
    public void clearAllHistory() {
        messageHistory.clear();
        thinkingChain.clear();
        try {
            java.io.File dir = activity.getFilesDir();
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String name = f.getName();
                    if (name.startsWith("online_agent_history") && name.endsWith(".json")) {
                        f.delete();
                    }
                }
            }
            AILogger.i(TAG, "Cleared all agent history files");
        } catch (Exception e) {
            AILogger.w(TAG, "clearAllHistory failed: " + e.getMessage());
        }
    }

    /** 当前历史是否已包含 system 消息（用于判断是否需要注入/重建系统提示词） */
    private boolean hasSystemMessage() {
        for (JsonObject msg : messageHistory) {
            String role = msg.has("role") ? msg.get("role").getAsString() : "";
            if ("system".equals(role)) return true;
        }
        return false;
    }

    /**
     * 环境上下文过期刷新：跨天继续对话时，模型拿到的"当前日期"是上次对话的。
     * 检测 env 消息（以【环境上下文】开头）中的日期，非今天则用最新环境上下文原位替换。
     */
    private void refreshEnvIfStale() {
        try {
            String today = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(new Date());
            for (int i = 0; i < messageHistory.size(); i++) {
                JsonObject msg = messageHistory.get(i);
                String role = msg.has("role") ? msg.get("role").getAsString() : "";
                if ("system".equals(role)) {
                    String content = msg.has("content") ? msg.get("content").getAsString() : "";
                    if (content != null && content.startsWith("【环境上下文】")) {
                        if (!content.contains(today)) {
                            String envContext = buildEnvironmentContext();
                            if (envContext != null && !envContext.isEmpty()) {
                                JsonObject envMsg = new JsonObject();
                                envMsg.addProperty("role", "system");
                                envMsg.addProperty("content", envContext);
                                messageHistory.set(i, envMsg);
                                AILogger.i(TAG, "Environment context refreshed (date changed to " + today + ")");
                            }
                        }
                        return; // 只处理第一个 env 消息
                    }
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "refreshEnvIfStale failed: " + e.getMessage());
        }
    }

    // ==================== 对话历史持久化（按会话隔离，跨 Activity 重建/重启保持前缀稳定，利于缓存命中） ====================

    /**
     * 历史文件路径：非空 sessionId 时按会话隔离（online_agent_history_{id}.json），
     * 否则使用默认文件（兼容旧版本/无会话场景）。
     */
    private java.io.File getHistoryFile() {
        if (sessionId != null && !sessionId.isEmpty()) {
            String safeId = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
            return new java.io.File(activity.getFilesDir(), "online_agent_history_" + safeId + ".json");
        }
        return new java.io.File(activity.getFilesDir(), "online_agent_history.json");
    }

    /**
     * 设置当前会话 ID（会话切换时调用）。
     * 保存当前会话历史 → 清空内存 → 切换目标文件 → 恢复目标会话历史。
     * 传 null/空 表示回到默认会话。
     */
    public void setSessionId(String newSessionId) {
        String old = this.sessionId;
        boolean changed = (old == null) ? (newSessionId != null && !newSessionId.isEmpty())
                : !old.equals(newSessionId);
        if (!changed) return;

        // 先持久化当前会话的历史，避免切换丢失
        persistHistory();
        this.sessionId = newSessionId;
        // 清空并恢复目标会话历史
        messageHistory.clear();
        thinkingChain.clear();
        restoreHistory();
        AILogger.i(TAG, "Session switched: " + old + " -> " + newSessionId
            + ", restored=" + messageHistory.size() + " messages");
    }

    /** 保存当前对话历史到私有文件（按会话隔离） */
    private void persistHistory() {
        try {
            if (messageHistory.isEmpty()) {
                deleteHistoryFile();
                return;
            }
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonArray arr = new JsonArray();
            for (JsonObject msg : messageHistory) {
                arr.add(msg);
            }
            java.io.FileWriter writer = new java.io.FileWriter(getHistoryFile());
            gson.toJson(arr, writer);
            writer.close();
            AILogger.i(TAG, "Persisted agent history: " + messageHistory.size()
                + " messages (session=" + sessionId + ")");
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to persist agent history: " + e.getMessage());
        }
    }

    /**
     * 从私有文件恢复对话历史（按会话隔离）。
     * 丢弃旧版本 system 消息（含过期提示词与环境上下文）：
     * 系统提示词会在下次 execute 时按当前版本重建，避免升级后旧提示词永久生效。
     * 但【对话历史摘要】必须保留：被压缩掉的旧消息只存在于摘要中，丢弃即永久失忆。
     */
    private void restoreHistory() {
        try {
            java.io.File file = getHistoryFile();
            if (!file.exists()) return;
            java.io.FileReader reader = new java.io.FileReader(file);
            JsonArray arr = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();
            messageHistory.clear();
            pendingSummaryMessage = null;
            int systemDiscarded = 0;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject msg = arr.get(i).getAsJsonObject();
                String role = msg.has("role") ? msg.get("role").getAsString() : "";
                if ("system".equals(role)) {
                    // 摘要消息暂存，execute 重建 system 后插回（保持前缀稳定）
                    String content = msg.has("content") ? msg.get("content").getAsString() : "";
                    if (content != null && content.contains("【对话历史摘要】")) {
                        pendingSummaryMessage = msg;
                        continue;
                    }
                    systemDiscarded++;
                    continue; // 丢弃旧 system（提示词/环境上下文），下次 execute 重建
                }
                messageHistory.add(msg);
            }
            if (!messageHistory.isEmpty()) {
                AILogger.i(TAG, "Restored agent history: " + messageHistory.size()
                    + " messages (session=" + sessionId + ", discarded_system=" + systemDiscarded
                    + ", summary=" + (pendingSummaryMessage != null) + ")");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to restore agent history: " + e.getMessage());
            deleteHistoryFile();
        }
    }

    private void deleteHistoryFile() {
        try {
            java.io.File file = getHistoryFile();
            if (file.exists()) file.delete();
        } catch (Exception ignored) {}
    }

    // ========== 内部通知方法 ==========

    private void finishGeneration() {
        isGenerating.set(false);
    }

    private void notifyComplete(String text) {
        // 清理模型输出中的乱码/非法字符
        String outputText = text;
        String cleaned = ToolResultInterpreter.cleanModelOutput(text);
        if (cleaned != null) {
            outputText = cleaned;
        } else if (text != null) {
            outputText = ToolResultInterpreter.sanitize(text);
            AILogger.w(TAG, "模型输出检测为乱码，已清理非法字符");
        }
        final String finalOutput = outputText;
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
            if (callback != null) callback.onComplete(finalOutput);
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
