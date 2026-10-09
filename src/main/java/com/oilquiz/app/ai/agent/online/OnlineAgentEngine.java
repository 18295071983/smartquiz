package com.oilquiz.app.ai.agent.online;

import android.content.Context;
import com.oilquiz.app.ai.tool.TaskStateTracker;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.InferenceProgressListener;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.agent.debug.DebugTracer;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    /** 辅助模式最大迭代轮数（安全兜底，正常任务远达不到；仅防死循环） */
    private static final int MAX_ITERATIONS = 50;
    /** 接管模式最大迭代轮数（信任模型自主控制，上限仅作安全兜底防死循环） */
    private static final int MAX_ITERATIONS_TAKEOVER = 100;
    private static final int MAX_TOKENS = 16384;
    /** 工具调用轮的输出上限：工具轮只需简短 tool_call（通常 <1K），
     *  设小上限避免大 max_tokens 被中转站预扣额度/防工具轮跑飞长文；
     *  最终答案轮仍用 MAX_TOKENS（16384）不受限。 */
    private static final int TOOL_ITERATION_MAX_TOKENS = 8192;
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

    /**
     * 系统提示词版本标记（缓存命中优化）。
     * persistHistory 时写入 system 消息的 "pv" 字段（仅落盘，不进入请求体）；
     * restoreHistory 时若历史里的 pv 与当前版本一致 → 原样保留 system/环境/记忆
     * （切换模型再切回后前缀与切换前逐字节一致 → 服务商前缀缓存命中最大化）；
     * 版本不一致（提示词升级）→ 丢弃重建（缓存 miss 一次属合理）。
     * 【注意】修改 OnlinePromptBuilder 的提示词模板时，必须同步递增此版本号。
     */
    private static final String PROMPT_VERSION = "20260923-v8";

    /** Agent 执行模式 */
    public enum AgentMode {
        /** 模型接管：模型具备完整 agent 能力，本地退化为纯执行器，信任模型自主决策 */
        TAKEOVER,
        /** 本地辅助：模型 agent 能力不足或未知，本地提供回退注入、工具指南等辅助 */
        ASSISTED
    }

    private final Context context;
    private final OnlineToolManager toolManager;
    private final OnlineInferenceService onlineInferenceService;
    private final OnlinePromptBuilder promptBuilder;
    private final OnlineThinkingChain thinkingChain;
    private final ExecutorService executor;
    /** 主线程回调通道（替代 Activity.runOnUiThread，引擎不持有 Activity，避免泄漏） */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 当前执行模式（每次 doExecute 开始时根据模型能力设定） */
    private volatile AgentMode agentMode = AgentMode.ASSISTED;

    /** 本轮是否深度思考：true 时向 API 请求传 thinking 参数（DeepSeek 等返回 reasoning_content） */
    private volatile boolean enableThinking = false;

    /** 当前会话 ID（null/空 = 默认单文件历史；非空 = 按会话隔离的历史文件） */
    private volatile String sessionId;

    /** 当前在线模型配置 ID（null = 未知/本地路径）。
     *  历史文件按「会话 × 模型」双维度隔离，切换模型不共享上下文，
     *  避免 A 模型的 system 提示词/工具链记录污染 B 模型。 */
    private volatile String activeModelId;

    private AgentCallback callback;
    private InferenceProgressListener progressListener;
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicInteger toolLoopCount = new AtomicInteger(0);

    /** 上次模型摘要时间（限频用：长任务中每轮 trim 都可能触发模型摘要，30s 内只做一次） */
    private volatile long lastModelSummaryTime = 0;
    private static final long MODEL_SUMMARY_MIN_INTERVAL_MS = 30_000;

    // OpenAI 格式消息历史（直接使用 JsonObject，支持 tool 角色消息）
    private final List<JsonObject> messageHistory = new ArrayList<>();
    /** 回合审计 meta（dsh turn/end 对齐）：结束原因/轮次/工具调用数/token；persistHistory 时按会话落盘 */
    private volatile java.util.LinkedHashMap<String, Object> lastTurnMeta;
    /**
     * 引擎引导消息队列（dsh 对齐，2026-09-23）：格式提示/截断修正/工具回退建议等"控制信号"
     * 只在本轮请求末尾生效，不入 messageHistory、不落盘、不参与 trim —— 防止任务多轮后
     * 上下文被引导消息污染（dsh 的 turn/step 边界与错误同样只作 trace 数据，不派生进模型历史）。
     */
    private final java.util.List<JsonObject> pendingControlMessages = new java.util.ArrayList<>();
    /**
     * Agent 事件总线（dsh 事件体系对齐，2026-09-23）：插件化扩展点——
     * 压缩/重试/权限/审计等策略通过注册监听器实现，不动引擎主循环。
     */
    public final AgentEventBus eventBus = new AgentEventBus();
    /** 目标自动续行（dsh goal-round-driver 对齐）：execute 完成后若仍有活跃任务且上轮推进过工具，
     *  自动注入「继续任务」提示词再跑一轮，直到任务完成/达上限/用户打断。 */
    private static final int MAX_AUTO_CONTINUE = 3;            // 单次用户触发最多自动续行轮数
    private static final long MIN_AUTO_CONTINUE_INTERVAL_MS = 30_000L; // 续行间隔，防瞬时重复
    private int autoContinueCount = 0;
    private long lastAutoContinueTs = 0;
    /** 恢复历史时暂存的【对话历史摘要】消息，execute 重建 system 后插回（防止压缩内容跨会话丢失） */
    private JsonObject pendingSummaryMessage;

    /** 当前长期记忆摘要（缓存命中优化）：移出前缀区，每轮请求末尾动态注入。
     *  记忆库变化（用户新增/更新记忆）时摘要立即刷新，但只影响请求尾部 D 块，
     *  system+历史前缀保持稳定 → 服务商前缀缓存不受记忆变化影响。 */
    private volatile String latestMemorySummary = null;
    /** 本轮知识库自动检索结果（2026-09-23）：请求末尾【相关资料】块动态注入，让模型直接用上知识库 */
    private volatile String latestKnowledgeContext = null;

    // ==== 缓存命中优化：会话内工具集「只增不改」====
    // 工具定义按用户消息意图动态筛选会导致 tools 部分每轮字节变化，
    // 服务商前缀缓存对 tools 段持续 miss（全价计费）。
    // 改为：同一引擎会话内工具名集合只增不改（新意图工具追加到末尾），
    // tools 前缀字节稳定 → 缓存命中最大化；能力只增不减。
    private final Set<String> cachedToolNames = new LinkedHashSet<>();
    private String cachedToolsJson = null;
    private boolean toolsCacheInitialized = false;
    /** 常驻核心工具集（2026-09-23 v6：ui/workspace/搜索/时间日期/发现/知识库）——LRU 永不淘汰。
     *  calculator 移出核心（模型内置数学能力足够，要精确计算可 tool_registry 发现拿回）；
     *  time_date 加入核心（今天几号/现在几点/日期推算，问答与提醒场景高实用）。 */
    private static final java.util.Set<String> CORE_TOOLS = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "ui_component", "workspace", "network_search", "time_date", "tool_registry", "knowledge_base",
            "douyin_downloader"
    ));
    /** 会话工具集上限（2026-09-23 v2）：核心 6 + 最多 3 个扩展工具——默认少带，模型不知道再发现 */
    private static final int MAX_SESSION_TOOLS = 9;
    /** 活工具集（2026-09-23 v5）：模型 tool_registry(get) 成功后立即更新，
     *  当前 execute 的下一迭代就用新工具（MCP 语义：get 后本轮可调，不等下条用户消息）。
     *  每 execute 开头重置为 null；registerToolIntoSession 时刷新为最新 cachedToolsJson。 */
    private volatile String liveToolsJson = null;
    /** 工具最后使用时间（LRU 淘汰依据，调用/注册时刷新） */
    private final java.util.Map<String, Long> toolLastUsed = new java.util.concurrent.ConcurrentHashMap<>();

    // 推理进度统计
    private long inferenceStartTime;
    private int totalTokenCount;
    /** 最近一次推理的缓存命中 token 数（DeepSeek prompt_cache_hit_tokens / OpenAI cached_tokens） */
    private volatile int lastCacheHitTokens = 0;
    private volatile int lastCacheMissTokens = 0;   // 2026-09-23：API 直读 prompt_cache_miss_tokens
    /** 最近一次推理的输入/输出 token（API usage，用于统计展示） */
    private volatile int lastPromptTokens = 0;
    private volatile int lastCompletionTokens = 0;
    /** 本次执行（一次用户消息，含多轮工具调用）累计的输入/输出 token —— 真正的总消耗 */
    private volatile int execTotalPromptTokens = 0;
    private volatile int execTotalCompletionTokens = 0;
    /** LLM 调用轮次计数（每次 execute 开始重置） */
    private volatile int llmCallCount = 0;
    /** 最近一次推理的思考 token 数 */
    private volatile int lastReasoningTokens = 0;
    /** 当前模型上下文窗口（tokens，按模型名推断） */
    private volatile int contextWindowTokens = 0;

    // ==== 调试追踪钩子（DebugTracer，可为空，空时零开销）====
    private volatile DebugTracer debugTracer;
    private volatile String debugRunId;
    /** 历史兜底恢复已尝试标记（2026-09-23 v7.1）：冷启动模型 ID 晚到时序下，
     *  execute 首次执行前按 会话×模型 补恢复一次；已尝试/无文件/已有历史则跳过。 */
    private boolean historyLoadAttempted = false;
    private volatile String debugLlmSpanId;
    private volatile long debugLlmStartTs;

    /**
     * 获取上下文用量信息（供 UI 展示）。
     * @return {window, used, remaining} —— 上下文窗口、已用（最近请求输入）、剩余
     */
    public int getLastReasoningTokens() { return lastReasoningTokens; }

    public int getLastCacheMissTokens() { return lastCacheMissTokens; }

    public int[] getContextWindowInfo() {
        int window = contextWindowTokens > 0 ? contextWindowTokens : 32768;
        int used = lastPromptTokens > 0 ? lastPromptTokens : execTotalPromptTokens;
        return new int[]{window, used, Math.max(0, window - used)};
    }

    public OnlineAgentEngine(Context context, OnlineToolManager toolManager) {
        this.context = context;
        this.toolManager = toolManager;
        this.onlineInferenceService = OnlineInferenceService.getInstance(context);
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

    /** 注册调试追踪监听（调试控制台用；可为空，空时零开销） */
    public void setDebugTracer(DebugTracer tracer) {
        this.debugTracer = tracer;
    }

    private void startDebugRun() {
        if (debugTracer == null) return;
        debugRunId = java.util.UUID.randomUUID().toString();
        // 历史兜底恢复标记（2026-09-23 v7.1）：App 冷启动若模型 ID 晚于会话设置导致
        // setSessionId 恢复 0 条，execute 首次执行前补恢复一次；文件不存在/已尝试过则跳过。
        this.historyLoadAttempted = false;
        debugLlmSpanId = null;
        debugLlmStartTs = 0;
        String model = "unknown";
        try {
            OnlineModelManager.OnlineModelConfig cfg = onlineInferenceService.getActiveConfig();
            if (cfg != null && cfg.modelName != null) model = cfg.modelName;
        } catch (Throwable ignored) { }
        final String m = model;
        debugTracer.onRunStart(debugRunId, m, System.currentTimeMillis());
    }

    private void debugLlmStart() {
        if (debugTracer == null || debugRunId == null) return;
        debugLlmSpanId = java.util.UUID.randomUUID().toString();
        debugLlmStartTs = System.currentTimeMillis();
        debugTracer.onLlmStart(debugRunId, debugLlmSpanId, debugLlmStartTs);
    }

    private void debugLlmUsage(int promptTokens, int completionTokens, int totalTokens, int cachedTokens) {
        if (debugTracer == null || debugRunId == null) return;
        String spanId = debugLlmSpanId != null ? debugLlmSpanId : java.util.UUID.randomUUID().toString();
        long startTs = debugLlmStartTs > 0 ? debugLlmStartTs : System.currentTimeMillis();
        long endTs = System.currentTimeMillis();
        debugTracer.onLlmUsage(debugRunId, spanId, promptTokens, completionTokens, totalTokens, cachedTokens, startTs, endTs);
        debugLlmSpanId = null;
        debugLlmStartTs = 0;
    }

    private void endDebugRun(String status, String error) {
        if (debugTracer == null || debugRunId == null) return;
        debugTracer.onRunEnd(debugRunId, status, error, System.currentTimeMillis());
        debugRunId = null;
        debugLlmSpanId = null;
        debugLlmStartTs = 0;
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
        execute(userMessage, maxTokens, enableThinking, false);
    }

    /**
     * 执行 Agent 任务。
     *
     * @param autoContinue 是否为引擎内部「目标自动续行」（true 时不重置续行计数/间隔，
     *                     且该轮消息为系统注入的【继续任务】提示词，非用户输入）
     */
    public void execute(String userMessage, int maxTokens, boolean enableThinking, boolean autoContinue) {
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
        // 历史兜底恢复（2026-09-23 v7.1）：覆盖"模型 ID 晚于会话设置"的冷启动时序，
        // 避免装机/重启后首条消息模型失忆（restored=0）。幂等：已尝试/无文件/已有历史则跳过。
        ensureHistoryLoadedOnce();
        if (!autoContinue) {
            // 用户消息触发：重置自动续行预算（dsh：人类消息让行自动工作）
            autoContinueCount = 0;
            lastAutoContinueTs = 0;
        }
        // 上轮取消/中断可能残留未发送的引擎引导消息，本轮重新开始前清空
        pendingControlMessages.clear();
        // 回合审计 meta（dsh turn/end 对齐，2026-09-23）：本轮结束原因/轮次/工具调用数，随 persistHistory 落盘
        lastTurnMeta = new java.util.LinkedHashMap<>();
        lastTurnMeta.put("reason", "running");
        lastTurnMeta.put("turns", 0);
        lastTurnMeta.put("toolCalls", 0);
        lastTurnMeta.put("promptTokens", 0);
        lastTurnMeta.put("completionTokens", 0);
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
        llmCallCount = 0;
        lastPromptTokens = 0;
        lastCompletionTokens = 0;
        lastCacheHitTokens = 0;
        inferenceStartTime = System.currentTimeMillis();
        startDebugRun();

        // 在线模型每轮输出上限：用宽松值（16384），不被外部保守配置（4096 是给本地模型的）截断。
        // 在线 API 通常支持大 max_tokens（模型自己决定实际输出），App 不设紧限制。
        // max_tokens 只是上限（按实际生成计费），最终答案轮用大值防截断；
        // 工具调用轮只需简短 tool_call，streamOneIteration 内用小上限（见 TOOL_ITERATION_MAX_TOKENS）。
        int effectiveMaxTokens = Math.max(maxTokens > 0 ? maxTokens : 0, MAX_TOKENS);

        executor.submit(() -> {
            try {
                doExecute(userMessage, effectiveMaxTokens);
                // 本轮 Agent 执行完成（含工具多轮），持久化对话历史，供 Activity 重建/重启后恢复
                persistHistory();
            } catch (Throwable t) {
                AILogger.e(TAG, "Execute failed: " + t.getMessage(), t);
                if (lastTurnMeta != null) lastTurnMeta.put("reason", "error");
                finishGeneration();
                notifyError("执行中断: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            } finally {
                // 任务结束清理临时工作区（tmp/ 执行中间文件/缓存/进度）——
                // 工作区是临时执行空间，长期产物在 files/，临时缓存不跨任务保留
                try {
                    int removed = com.oilquiz.app.ai.agent.online.AgentWorkspace
                            .getInstance(context).clearTmp();
                    if (removed > 0) {
                        AILogger.i(TAG, "任务结束清理临时工作区: 删除 " + removed + " 个临时文件");
                    }
                } catch (Throwable ignored) {
                }
                // 目标自动续行（dsh goal-round-driver 对齐）：仍有活跃任务且上轮推进过工具 →
                // 自动注入【继续任务】再跑一轮（受 MAX_AUTO_CONTINUE / 间隔 / 取消 三重约束）
                maybeAutoContinue();
            }
        });
    }

    /**
     * 核心 Agent 执行循环
     */
    private void doExecute(String userMessage, int maxTokens) {
        eventBus.post(AgentEventBus.EventType.TURN_START, userMessage.length() > 40
                ? userMessage.substring(0, 40) : userMessage);
        OnlineModelManager.OnlineModelConfig cfg = onlineInferenceService.getActiveConfig();
        
        // 记录模型上下文窗口（供历史压缩阈值 + UI 展示上下文用量）。
        // 优先实际查询 API(/models 接口,真实窗口),失败回退配置表推断。
        if (cfg != null) {
            Integer apiCtx = null;
            try {
                apiCtx = onlineInferenceService.queryContextWindowFromAPI(cfg);
            } catch (Throwable t) {
                AILogger.w(TAG, "API context window query failed: " + t.getMessage());
            }
            contextWindowTokens = apiCtx != null && apiCtx > 0 ? apiCtx
                    : (cfg.contextWindow > 0 ? cfg.contextWindow
                    : com.oilquiz.app.ai.model.OnlineModelManager.getContextWindowForModel(cfg.apiUrl, cfg.modelName));
        }
        
        if (cfg == null) {
            finishGeneration();
            notifyError("没有激活的在线模型");
            return;
        }

        // 0. 深度思考门控：仅当模型支持 thinking/reasoning 参数时才开启（避免对不支持的服务商
        //    发送 enable_thinking 导致 HTTP 400）。不支持时静默降级为普通模式：
        //    - 不注入思考指令（下方 system 提示词构建已检查 enableThinking 字段）
        //    - 不向 API 传 thinking 参数（streamOneIteration 使用同一字段）
        if (enableThinking) {
            String modelName = cfg.modelName;
            if (!com.oilquiz.app.ai.model.OnlineModelManager.isThinkingModelName(modelName)) {
                AILogger.w(TAG, "Deep thinking requested but model does not support it, "
                        + "degrading to normal mode: " + modelName);
                enableThinking = false;
                notifyStep("深度思考", "当前模型不支持深度思考，已自动切换为普通模式");
            } else {
                AILogger.i(TAG, "Deep thinking enabled for model: " + modelName);
            }
        }

        // 0. 检测在线模型 agent 能力，选择执行模式
        agentMode = detectAgentCapability(cfg) ? AgentMode.TAKEOVER : AgentMode.ASSISTED;
        int maxIterations = agentMode == AgentMode.TAKEOVER ? MAX_ITERATIONS_TAKEOVER : MAX_ITERATIONS;
        AILogger.i(TAG, "Agent mode: " + agentMode + " (maxIterations=" + maxIterations + ")");
        notifyStep("执行模式", agentMode == AgentMode.TAKEOVER ? "模型接管模式" : "本地辅助模式");

        // 0.5 刷新工具注册系统（必须在构建系统提示词之前，确保工具列表和指南不为空）
        toolManager.refreshRegistry();
        // 0.5.1 工具定义入库知识库（2026-09-23：工具即知识，幂等+版本控制，首次/升级时全量重建）
        toolManager.ensureToolDefsInKnowledgeBase();

        // 1. 无 system 消息时注入系统提示词和环境上下文（恢复历史时会丢弃旧 system，
        //    保证始终使用当前版本的提示词；连续对话时已有 system，跳过）
        if (!hasSystemMessage()) {
            boolean takeover = agentMode == AgentMode.TAKEOVER;
            // 深度思考指令：开启时按模型名注入对应思考指令（DeepSeek/Qwen3/o系列等指令不同，
            // API thinking 参数触发 reasoning_content，此指令强化思考质量）
            String thinkingInstruction = null;
            if (enableThinking) {
                // 带 apiUrl：同名模型可能命中别家服务商的关键词（如 deepseek-v4-flash
                // 同时出现在 dashscope/siliconflow/deepseek 的关键词里），必须按实际服务商取
                thinkingInstruction = com.oilquiz.app.ai.model.OnlineModelManager.getThinkingInstruction(
                        cfg != null ? cfg.apiUrl : null,
                        cfg != null ? cfg.modelName : null);
            }
            // 注入工作区路径：Agent 生成的文件默认在工作区，明确告知路径与访问方式
            String workspaceBlock = null;
            try {
                com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                        com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
                String wsPath = ws.getWorkspacePath();
                String wsLocation = ws.isPublicWorkspace()
                        ? "公共目录(Download/OilQuiz，用户可直接看到和管理)"
                        : "应用私有目录(用户需通过App管理页查看)";
                workspaceBlock = "\n【文件与工作区】你有专属文件工作目录（工作区）: " + wsPath
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
                        + "生成文件后如需在页面中引用，用与页面同目录的文件名即可。";
            } catch (Throwable t) {
                AILogger.w(TAG, "注入工作区信息失败: " + t.getMessage());
            }
            // 分段组装：基础段（assist/takeover）+【深度思考】段 +【文件与工作区】段，
            // 由 PromptAssembler（dsh 分段语义）按 order 排序拼接；段文本与原手动 += 语义一致
            String systemPrompt = promptBuilder.buildSystemPromptWithRuntime(
                    takeover, enableThinking, thinkingInstruction, workspaceBlock);
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", systemPrompt);
            // 插到最前（恢复的历史可能是 用户/助手 消息，system 必须在前）
            messageHistory.add(0, systemMsg);

            // 获取环境上下文（日期、位置——天气不注入，Agent 用 ai_weather 工具主动获取），
            // 注入为系统消息辅助Agent思考
            notifyStep("环境感知", "正在获取位置信息...");
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

        // 长期记忆（缓存命中优化）：移出前缀区，改为请求末尾 D 块动态注入。
        // 每轮以当前用户消息刷新摘要——记忆库变化（用户新增/更新记忆）立即生效，
        // 但只影响请求尾部，system+历史前缀保持稳定 → 服务商前缀缓存不受记忆变化影响。
        // 闲聊轮不注入（2026-09-23，极致精简）：闲聊就是随便聊，不携带任务相关记忆块；
        // 任务轮/知识轮才带记忆摘要（模型据此运用用户信息）。闲聊轮用户信息缺失时模型可如实说明。
        if (!isCasualChat(userMessage)) {
            latestMemorySummary = AgentMemoryStore.getInstance(context).buildMemorySummary(userMessage);
            if (latestMemorySummary != null && !latestMemorySummary.isEmpty()) {
                AILogger.i(TAG, "Long-term memory refreshed: "
                        + AgentMemoryStore.getInstance(context).size() + " entries");
            }
        } else {
            latestMemorySummary = null;
        }

        // 1.5.5 知识库自动检索（2026-09-23 好好利用）：非闲聊轮对用户消息全文检索知识库，
        // 命中 top-3 注入请求末尾【相关资料】块——用户问应用专属/个人资料问题时，
        // 模型直接看到相关材料作答，无需先想起"知识库"这个工具（knowledge_base 也在核心集可主动用）
        if (!isCasualChat(userMessage)) {
            latestKnowledgeContext = buildKnowledgeContext(userMessage);
        } else {
            latestKnowledgeContext = null;
        }

        // 1.5 恢复历史时暂存的【对话历史摘要】插回（在所有 system 消息之后，保持前缀稳定：
        // 提示词/环境上下文在摘要之前，跨会话不因摘要内容变化导致前缀 miss；
        // 长期记忆已移出历史，每轮在请求末尾 D 块动态注入）
        if (pendingSummaryMessage != null) {
            messageHistory.add(pendingSummaryMessage);
            pendingSummaryMessage = null;
            AILogger.i(TAG, "Summary message re-inserted after system messages");
        }

        // 1.7 「继续」类消息接续（2026-09-23）：用户发"继续/接着"时，若有活跃任务，
        // 转成【继续任务】引导（复用 goal 续行语义，带上工具、不空转、不靠 DSML 硬调）
        String effectiveUserMessage = maybeResolveContinueMessage(userMessage);

        // 2. 添加用户消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", effectiveUserMessage);
        messageHistory.add(userMsg);

        // 3. 通过 OnlineToolManager 获取工具定义。
        // 缓存安全的 MCP 式注入：
        // - 固定核心工具集（高频，始终注入，顺序固定）→ 前缀缓存稳定
        // - 按用户消息意图追加低频工具（同一次用户请求的所有 Agent 轮次 tools 相同，
        //   任务内缓存稳定；不同任务按意图变化属合理缓存 miss）
        // 核心集：ui_component(组件/交互) file_generator(文件) workspace(工作区)
        // memory(记忆) task(任务状态跟踪) tool_registry(工具发现) permission_manager(权限)
        // ai_weather(天气高频) network_search(搜索高频) calculator(计算)
        // control_lookup(低频控件参数查询，构建UI多用，token小)
        // knowledge_base(用户知识库：检索/导入，应用专属资料问答的必用工具，schema 小 → 常驻核心集)
        // 常驻核心工具集（硬编码高频，2026-09-23 精简 12→8）：
        // 低频工具（tool_registry/permission_manager/control_lookup/knowledge_base）移出常驻，
        // 由 getToolDefinitionsForMessageAndCore 的关键词意图按需临时加回（能力不降，每轮省 ~4 个 schema）
        // 常驻核心 5 个（2026-09-23 v3）：模型自行发现制——只保留入口/UI/文件/搜索/计算，
        // 其余（weather/file_generator/task/memory/knowledge_base/permission/control…）一律走
        // tool_registry 发现 + getToolDefinitionsForMessageAndCore 关键词意图按需注入（能力不降，每轮省 ~12K）
        java.util.Set<String> coreTools = new java.util.LinkedHashSet<>(CORE_TOOLS);
        // 模型驱动工具注入（2026-09-23 v4，dsh-agent-loop 对齐）：
        // 每轮 tools = 核心 6（稳定前缀，缓存命中）+ 高频预载（≤2）+ 本轮调用即注册。
        // 零预判——不按关键词/KB/意图注入工具；模型要新工具 → tool_registry(get) 发现，
        // 调用成功即注册进本轮缓存。工具结果进历史，模型基于结果作答。
        // 闲聊模式已废弃：每一轮恒带工具，短消息恒有工具能力，根除 DSML 硬调。
        String toolsJson;
        boolean casualChatTurn = isCasualChat(effectiveUserMessage);
        if (casualChatTurn) {
            toolsJson = null;
            AILogger.i(TAG, "Casual chat detected, tools=null (token saving)");
        } else {
            toolsJson = getStableToolsJson(effectiveUserMessage, coreTools, cfg);
        }
        liveToolsJson = null; // 每 execute 重置：模型 get 新工具前，工具集=核心+预载
        int toolCount = countToolsInJson(toolsJson);
        if (toolCount == 0 && !casualChatTurn) {
            AILogger.w(TAG, "No tools available! Agent will run without tool calling capability.");
        }

        // 4. Agent 主循环
        int iteration = 0;
        // 连续暗示"要调用工具"但未输出正确 tool_calls 格式的次数，用于防死循环
        int consecutiveHintForToolCount = 0;
        final int MAX_CONSECUTIVE_TOOL_HINT = 2; // 连续 2 次都暗示要调工具却格式不对，第 3 次强制终止

        while (iteration < maxIterations && !isCancelled.get()) {
            iteration++;
            if (lastTurnMeta != null) lastTurnMeta.put("turns", iteration);
            AILogger.i(TAG, "Agent iteration " + iteration + "/" + maxIterations + " [" + agentMode + "]");

            // 开始新一轮思考块
            thinkingChain.startNewBlock(iteration);
            notifyStep("推理轮次 " + iteration, "正在思考...");
            notifyExecutionStep(OnlineExecutionStep.THINKING, "推理轮次 " + iteration);

            // 流式生成一轮（模型 get 新工具后，liveToolsJson 已更新 → 本轮下一迭代即带新工具）
            if (liveToolsJson != null) {
                toolsJson = liveToolsJson;
            }
            debugLlmStart();
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
                    pendingControlMessages.add(formatHint);

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

                // 模型给出最终答案（无 tool_calls）→ 直接返回（信任模型自主判断完成时机，
                // 不强加目标评估等引导，避免限制模型能力或引发额外循环）
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
                if (lastTurnMeta != null) {
                    lastTurnMeta.put("reason", "final_complete");
                    lastTurnMeta.put("turns", iteration);
                }
                notifyComplete(finalAnswer);
                return;
            }

            // ===== 有工具调用：重置"连续暗示次数"计数 =====
            consecutiveHintForToolCount = 0;

            // 有工具调用但 finish_reason=length：工具参数可能被截断。
            // 截断处若恰是合法 JSON 前缀会执行非预期动作（删错文件/写入截断内容），
            // 因此丢弃本轮 tool_calls，要求模型重新完整输出。
            if ("length".equals(result.finishReason)) {
                AILogger.w(TAG, "Tool calls truncated due to max_tokens (finish_reason=length), discarding");
                JsonObject sysMsg = new JsonObject();
                sysMsg.addProperty("role", "system");
                sysMsg.addProperty("content", "注意：上一轮输出因长度限制被截断，工具调用参数不完整，已全部丢弃。"
                        + "请重新完整输出工具调用（arguments 必须是完整闭合的 JSON），或直接给出最终回答。");
                pendingControlMessages.add(sysMsg);
                notifyStep("截断修正", "工具参数可能被截断，已丢弃并要求模型重新完整输出");
                continue;
            }

            // 有工具调用
            toolLoopCount.incrementAndGet();
            notifyExecutionStep(OnlineExecutionStep.TOOL_CALLING, "执行 " + result.toolCalls.size() + " 个工具");
            notifyStep("工具调用", "执行 " + result.toolCalls.size() + " 个工具");

            // 将 assistant 消息（含 tool_calls）加入历史
            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            // content 恒有（空串兜底）：GLM-5 等严格校验的模型对缺失 content 字段的
            // assistant 消息会返回 400 (1214 messages 参数非法)；空字符串兼容所有厂商
            assistantMsg.addProperty("content", result.content != null ? result.content : "");
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
                mainHandler.post(() -> {
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
                    mainHandler.post(() -> {
                        if (callback != null) callback.onToolCallComplete(tr.toolCallId, tr.toolName, tr);
                    });

                    // 在思考链中记录工具调用（arguments 完整传入，展示层可看参数）
                    String resultSummary = tr.success ? (tr.result != null ? tr.result.substring(0, Math.min(200, tr.result.length())) : "") : tr.error;
                    thinkingChain.appendToolCall(tr.toolName,
                            tc.arguments != null ? tc.arguments : "", tr.success, resultSummary);

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
                    pendingControlMessages.add(hintMsg);
                    AILogger.i(TAG, "Injected fallback hint for failed tools: " + failedTools);
                }
            }

            // 继续下一轮推理
        }

        // 达到最大迭代次数
        if (lastTurnMeta != null) {
            lastTurnMeta.put("reason", "max_iterations");
            lastTurnMeta.put("turns", maxIterations);
        }
        AILogger.w(TAG, "Reached max iterations (" + maxIterations + ", mode=" + agentMode + ")");
        notifyStep("总结", "已达到最大推理轮次，生成最终回答...");
        notifyExecutionStep(OnlineExecutionStep.RESPONDING, "生成最终回答");
        debugLlmStart();
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
     * 触发条件：消息条数超过 MAX_HISTORY_MESSAGES，或估算 token 超过动态预算
     * （基于模型上下文窗口的 60%，为工具结果/回答预留空间；未知模型回退 24K）。
     * 策略：保留首条 system 消息 + 摘要消息 + 最近 MAX_HISTORY_MESSAGES-1 条消息。
     * 安全保证：截断点不会落在 tool 消息上（否则其对应的 assistant.tool_calls 被移除，
     * 导致 API 报错）。若截断点处为 tool 消息，继续前移直至非 tool 消息。
     * 摘要：把移除的旧消息压缩为一条 system 摘要消息（长对话"不失忆"且省 token）。
     */
    private void trimMessageHistory(OnlineModelManager.OnlineModelConfig cfg) {
        // 动态预算：基于模型上下文窗口（contextWindow × 60%），预留 40% 给工具结果/回答
        int budget = HISTORY_TOKEN_BUDGET;
        if (cfg != null) {
            int ctx = cfg.contextWindow > 0 ? cfg.contextWindow
                    : com.oilquiz.app.ai.model.OnlineModelManager.getContextWindowForModel(cfg.apiUrl, cfg.modelName);
            if (ctx > 0) {
                budget = (int) (ctx * 0.6);
                budget = Math.max(budget, 8192); // 下限 8K，避免极小上下文过度频繁压缩
            }
        }
        // 计算消息数超限还是 token 超预算
        int tokenCount = 0;
        for (JsonObject msg : messageHistory) {
            String content = msg.has("content") && !msg.get("content").isJsonNull()
                    ? msg.get("content").getAsString() : "";
            tokenCount += estimateTokens(content);
        }
        boolean overBudget = tokenCount > budget;
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
                if (running >= tokenCount - budget / 2) break;
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
        // 用户消息保护（1214 根因修复）：GLM/OpenAI 兼容校验要求 messages 数组至少 1 条 user。
        // 若裁剪后剩余段 user 数量为 0（历史全是 assistant/tool/system，user 被误删），
        // 把 cutoff 前移到最后一条 user 消息处，保留该 user 及其后完整消息链
        // （避免孤儿 tool_call 的另一种 1214）。
        if (cutoff < messageHistory.size()) {
            int userAfter = 0;
            for (int i = cutoff; i < messageHistory.size(); i++) {
                JsonObject m = messageHistory.get(i);
                String r = m.has("role") ? m.get("role").getAsString() : "";
                if ("user".equals(r)) userAfter++;
            }
            if (userAfter == 0) {
                for (int i = cutoff - 1; i >= 1; i--) {
                    JsonObject m = messageHistory.get(i);
                    String r = m.has("role") ? m.get("role").getAsString() : "";
                    if ("user".equals(r)) {
                        cutoff = i;
                        break;
                    }
                }
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
                + ", tokens " + tokenCount + " -> budget " + budget);
            eventBus.post(AgentEventBus.EventType.COMPACTION, String.valueOf(actualRemoved));
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
        //    限频：30s 内只调一次模型摘要（长任务每轮 trim 都可能触发，避免同步阻塞 + 消耗额度）
        if (cfg != null && System.currentTimeMillis() - lastModelSummaryTime >= MODEL_SUMMARY_MIN_INTERVAL_MS) {
            try {
                String prompt = "请将以下AI对话压缩成一份简洁的中文摘要（保留关键信息：用户需求、结论、重要事实、未完成事项），"
                        + "不超过500字，直接输出摘要内容：\n\n" + dialogue;
                String summary = onlineInferenceService.generateOnceAsync(prompt, cfg, 1024)
                        .get(SUMMARY_MODEL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (summary != null && !summary.trim().isEmpty()) {
                    lastModelSummaryTime = System.currentTimeMillis();
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
        eventBus.post(AgentEventBus.EventType.PRE_STEP, "iteration");
        final IterationResult result = new IterationResult();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] errorHolder = {null};

        // 截断消息历史，避免长对话或多次工具调用后超出模型上下文窗口
        trimMessageHistory(cfg);

        // 构建发送用消息数组（副本）：按本轮思考开关 + 服务商要求规范化 reasoning_content。
        // DeepSeek 思考模式硬性要求（社区多起 400 实证，如 opencode PR #24150 "inject reasoning_content
        // for ALL assistant msgs"）：请求中**所有** assistant 消息都必须带 reasoning_content 字段，
        // 否则 API 返回 HTTP 400 "The 'reasoning_content' in the thinking mode must be passed back to the API"。
        // 历史里旧轮次（用户此前普通模式 / 修复前持久化的历史）的 assistant 消息没有该字段，
        // 一旦本轮开启思考就会触发 400 —— 开启时对缺失字段补空串；关闭时移除残留字段
        // （思考轮字段发给非思考请求同样可能 400）。
        // 部分服务商配置 requiresReasoningInContext=true（如用户反馈"不回传 reasoning 会 400"的在线模型）：
        // 无论是否思考模式，所有 assistant 消息都必须带 reasoning_content。只规范化副本，不修改本体。
        JsonArray messagesArray = buildOutgoingMessagesArray(cfg);

        // P0 缓存诊断探针：打印本轮实际发送 messages 的 SHA256 指纹，
        // 连续两轮哈希一致 = 前缀稳定；不一致 = 前缀抖动（缓存 miss 根因）。
        // 配合工具名缓存日志（cachedToolNames 大小不变即 tools 前缀稳定）可直接定位。
        try {
            String probeJson = messagesArray.toString();
            AILogger.i(TAG, "CACHE-PROBE sha256=" + sha256Hex(probeJson)
                    + " msg_len=" + probeJson.length()
                    + " tools_len=" + (toolsJson != null ? toolsJson.length() : 0)
                    + " thinking=" + enableThinking);
        } catch (Exception ignored) {}

        // 工具轮用小上限（只需简短 tool_call）；最终答案轮（toolsJson=null）保持传入的大值防截断
        int iterMaxTokens = toolsJson != null
                ? Math.min(maxTokens, TOOL_ITERATION_MAX_TOKENS)
                : maxTokens;

        // idle 超时计时：任何 token（思考/正文）到达都刷新，防止慢模型生成长答案被误判超时
        final java.util.concurrent.atomic.AtomicLong streamLastActivity =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

        onlineInferenceService.generateStreamWithToolsV2(messagesArray, cfg, iterMaxTokens, toolsJson,
            enableThinking,
            new OnlineInferenceService.NativeToolStreamCallback() {
                @Override
                public void onStart() {
                    streamLastActivity.set(System.currentTimeMillis());
                    AILogger.i(TAG, "Stream started");
                }

                @Override
                public void onReasoningToken(String token) {
                    if (isCancelled.get()) return;
                    streamLastActivity.set(System.currentTimeMillis());
                    totalTokenCount++;
                    thinkingChain.appendReasoningToken(token);
                    mainHandler.post(() -> {
                        if (callback != null) callback.onThinkingToken(token);
                    });
                }

                @Override
                public void onContentToken(String token) {
                    if (isCancelled.get()) return;
                    streamLastActivity.set(System.currentTimeMillis());
                    totalTokenCount++;
                    mainHandler.post(() -> {
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
                    // 本轮回合结束：引擎引导消息已随本次请求发送，清空避免下轮重复
                    pendingControlMessages.clear();
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
                    // reasoning_content 兜底：优先用回调参数，为空或比thinkingChain短时用流式收集的内容
                    String chainReasoning = "";
                    try {
                        OnlineThinkingChain.ThinkingBlock activeBlock = thinkingChain.getActiveBlock();
                        if (activeBlock != null && activeBlock.reasoningContent != null) {
                            chainReasoning = activeBlock.reasoningContent;
                        }
                    } catch (Exception ignored) {}
                    if (reasoningContent != null && reasoningContent.length() >= chainReasoning.length()) {
                        result.reasoningContent = reasoningContent;
                    } else if (!chainReasoning.isEmpty()) {
                        result.reasoningContent = chainReasoning;
                        if (reasoningContent == null || reasoningContent.isEmpty()) {
                            AILogger.w(TAG, "onComplete reasoningContent为空，使用thinkingChain兜底: " + chainReasoning.length() + " chars");
                        } else {
                            AILogger.w(TAG, "onComplete reasoningContent不完整(" + reasoningContent.length() + " chars)，使用thinkingChain兜底(" + chainReasoning.length() + " chars)");
                        }
                    } else {
                        result.reasoningContent = "";
                    }
                    result.toolCalls = toolCalls;
                    result.finishReason = finishReason;

                    notifyProgress();
                    mainHandler.post(() -> {
                        if (callback != null) callback.onThinkingEnd();
                    });
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    pendingControlMessages.clear();
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
                    debugLlmUsage(promptTokens, completionTokens, totalTokens, 0);
                    AILogger.i(TAG, "Token usage: prompt=" + promptTokens
                        + " completion=" + completionTokens + " total=" + totalTokens);
                    notifyProgress();
                }

                @Override
                public void onUsageWithCacheFull(int promptTokens, int completionTokens, int totalTokens, int cachedTokens, int missTokens) {
                    // 2026-09-23：直读 API 缓存未命中量（prompt_cache_miss_tokens）
                    lastCacheMissTokens = missTokens;
                }

                @Override
                public void onUsageWithReasoning(int promptTokens, int completionTokens, int totalTokens, int cachedTokens, int reasoningTokens) {
                    lastReasoningTokens = reasoningTokens;
                    AILogger.i(TAG, "Reasoning tokens: " + reasoningTokens + " / " + completionTokens
                        + " = " + (completionTokens > 0 ? reasoningTokens * 100 / completionTokens : 0) + "%");
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
                    debugLlmUsage(promptTokens, completionTokens, totalTokens, cachedTokens);

                    // ===== 详细 token 日志 =====
                    int roundNum = ++llmCallCount;
                    int cachePct = promptTokens > 0 ? (cachedTokens * 100 / promptTokens) : 0;
                    AILogger.i(TAG, String.format(
                        "Token usage: prompt=%d completion=%d total=%d cache_hit=%d (%d%%)" +
                        " | 第%d轮 | exec累计: in=%d out=%d" +
                        " | 本轮增量: in+%d out+%d",
                        promptTokens, completionTokens, totalTokens, cachedTokens, cachePct,
                        roundNum,
                        execTotalPromptTokens, execTotalCompletionTokens,
                        promptTokens, completionTokens
                    ));

                    notifyProgress();
                }
            });

        try {
            // idle 超时（替代一次性 120s 硬超时）：流式下每 5s 检查一次，
            // 只要还有 token 到达就继续等——慢模型生成长答案不再被误判超时
            final long idleTimeoutMs = 120_000L;
            boolean completed = false;
            while (!completed) {
                boolean done = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                if (done) {
                    completed = true;
                    break;
                }
                if (System.currentTimeMillis() - streamLastActivity.get() > idleTimeoutMs) {
                    break; // 长时间无新 token：判定 idle 超时
                }
            }
            if (!completed) {
                // 超时：流式推理长时间无响应
                finishGeneration();
                AILogger.w(TAG, "Stream idle timeout (120s no tokens), content_len=" + result.content.length());
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
     * 构建发送用消息数组（副本）：按本轮思考开关 + 服务商要求规范化 assistant 消息的 reasoning_content 字段。
     * 不修改 messageHistory 本体（思考轮的真实 reasoning_content 保留在历史中，供持久化与回传）。
     *
     * DeepSeek 思考模式硬性要求（社区多起 400 实证，如 opencode PR #24150 "inject reasoning_content
     * for ALL assistant msgs"）：请求中**所有** assistant 消息都必须带 reasoning_content 字段，
     * 否则 API 返回 HTTP 400 "The 'reasoning_content' in the thinking mode must be passed back to the API"。
     *
     * 场景：深度思考开关是「本条」语义（发送后自动复位关闭），历史里会混有
     * - 思考轮（assistant 消息带 reasoning_content）与
     * - 普通轮（assistant 消息没有该字段，例如用户上一轮未开思考、或修复前持久化的历史）。
     * 一旦本轮开启思考，把缺少字段的旧 assistant 消息发给 DeepSeek 就会 400。
     *
     * 处理：
     * - 服务商 requiresReasoningInContext=true（不回传 reasoning 会 400）→ 所有 assistant 消息始终带字段；
     * - enableThinking=true  → 所有 assistant 消息补齐 reasoning_content（缺失补空字符串）；
     * - 否则 → 移除残留的 reasoning_content（思考轮字段发给非思考请求同样可能 400）。
     */
    private JsonArray buildOutgoingMessagesArray(OnlineModelManager.OnlineModelConfig cfg) {
        JsonArray out = new JsonArray();
        // 服务商要求恒回传（配置表 requiresReasoningInContext）→ 所有 assistant 消息始终带该字段
        boolean forceReasoning = cfg != null && cfg.apiUrl != null
                && com.oilquiz.app.ai.model.ProviderConfigManager.get()
                        .requiresReasoningInContext(cfg.apiUrl);
        // tool 消息配对保护：role=tool 必须紧跟带 tool_calls 的 assistant 消息之后，
        // 否则 GLM/OpenAI 兼容接口 400 "Messages with role 'tool' must be a response to a
        // preceding message with 'tool_calls'"。历史裁剪/持久化恢复可能留下悬空 tool 消息，
        // 发送前逐个跳过，保证发出的数组恒合法（对 start/sendMessage/恢复历史所有入口统一兜底）。
        boolean toolCallsOpen = false;
        int skippedOrphanTools = 0;
        int skippedEmptyAssistant = 0;
        try {
            for (JsonObject msg : messageHistory) {
                JsonObject copy = msg.deepCopy();
                String role = copy.has("role") && !copy.get("role").isJsonNull()
                        ? copy.get("role").getAsString() : "";
                if ("assistant".equals(role)) {
                    boolean hasToolCalls = copy.has("tool_calls") && !copy.get("tool_calls").isJsonNull()
                            && copy.get("tool_calls").isJsonArray()
                            && copy.getAsJsonArray("tool_calls").size() > 0;
                    // deriveEventMessage 对齐（dsh，2026-09-23）：content 为空且无 tool_calls 的
                    // assistant 消息不派生进模型上下文（纯思考/无输出轮次对后续推理无信息量，
                    // 发送只会白占 token 并可能让模型困惑）
                    String asContent = copy.has("content") && !copy.get("content").isJsonNull()
                            ? copy.get("content").getAsString() : "";
                    boolean emptyAssistant = (asContent == null || asContent.trim().isEmpty()) && !hasToolCalls;
                    if (emptyAssistant) {
                        skippedEmptyAssistant++;
                        continue; // 不影响 toolCallsOpen 配对状态（无 tool_calls）
                    }
                    // DSML 残留过滤（2026-09-23，模型自测发现）：历史里可能残留模型在
                    // tools=null 时输出的 DSML 伪调用文本（特征 `<｜｜`）——无信息量且会
                    // 误导后续推理（模型自己也称其 malformed）。发送时跳过，不再重演；
                    // 历史文件保留（审计），仅请求层过滤。
                    if (asContent != null && asContent.contains("<｜｜")) {
                        AILogger.i(TAG, "DSML residue assistant message filtered (len="
                                + asContent.length() + ")");
                        continue;
                    }
                    // 带 tool_calls 的 assistant 打开配对段落；普通 assistant 关闭
                    toolCallsOpen = hasToolCalls;
                    if (forceReasoning || enableThinking) {
                        if (!copy.has("reasoning_content") || copy.get("reasoning_content").isJsonNull()) {
                            copy.addProperty("reasoning_content", "");
                        }
                    } else {
                        copy.remove("reasoning_content");
                    }
                    out.add(copy);
                } else if ("tool".equals(role)) {
                    if (toolCallsOpen) {
                        out.add(copy);
                        // 多个 tool 消息可与同一 assistant.tool_calls 配对，保持打开
                    } else {
                        skippedOrphanTools++;
                        AILogger.w(TAG, "Skipped orphan tool message (no preceding tool_calls): "
                                + copy);
                    }
                } else {
                    // user/system 等消息关闭配对段落
                    toolCallsOpen = false;
                    out.add(copy);
                }
            }
            if (skippedOrphanTools > 0) {
                AILogger.i(TAG, "Orphan tool messages filtered: " + skippedOrphanTools);
            }
            if (skippedEmptyAssistant > 0) {
                AILogger.i(TAG, "Empty assistant messages skipped (deriveEventMessage align): " + skippedEmptyAssistant);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "buildOutgoingMessagesArray failed, sending raw history: " + e.getMessage());
            out = new JsonArray();
            for (JsonObject msg : messageHistory) {
                out.add(msg);
            }
        }
        // 长期记忆动态追加到请求末尾（D 块，缓存命中优化）：
        // 记忆内容随记忆库变化，放在尾部不影响 system+历史前缀缓存命中；
        // 用副本追加，不污染 messageHistory 本体
        if (latestMemorySummary != null && !latestMemorySummary.isEmpty()) {
            JsonObject memoryMsg = new JsonObject();
            memoryMsg.addProperty("role", "system");
            memoryMsg.addProperty("content", "【长期记忆】以下是你记住的关于用户的信息，回答时自然运用。仅当用户明确要求记住或主动告知新的个人信息/偏好时，才用 memory 工具 save 新增或更新（不要擅自把普通聊天内容存为记忆）：\n" + latestMemorySummary);
            out.add(memoryMsg);
        }
        // 知识库相关资料（D2 块，2026-09-23）：KB 自动检索命中时注入，优先级高于记忆、低于控制信号
        if (latestKnowledgeContext != null && !latestKnowledgeContext.isEmpty()) {
            JsonObject kbMsg = new JsonObject();
            kbMsg.addProperty("role", "system");
            kbMsg.addProperty("content", latestKnowledgeContext);
            out.add(kbMsg);
        }
        // 引擎引导消息（格式提示/截断修正/回退建议）→ 仅本轮请求末尾生效（dsh 对齐：
        // 控制信号与语义消息分离，不入历史不落盘）。发送后由 streamOneIteration 轮末清空。
        for (JsonObject ctl : pendingControlMessages) {
            out.add(ctl.deepCopy());
        }
        return out;
    }

    /**
     * 执行单个工具调用（通过 OnlineToolManager）
     */
    private OnlineToolResult executeToolCall(String toolCallId, String toolName, String arguments) {
        // 回合审计：本轮工具调用数（dsh turn/end toolCalls 对齐）
        if (lastTurnMeta != null) {
            int tc = 0;
            Object v = lastTurnMeta.get("toolCalls");
            if (v instanceof Number) tc = ((Number) v).intValue();
            lastTurnMeta.put("toolCalls", tc + 1);
        }
        // 工具执行前可 veto（dsh tools/pre-execute）：策略监听器可拒绝该工具调用
        if (!eventBus.postVetoable(AgentEventBus.EventType.TOOL_PRE_EXECUTE, toolName, arguments)) {
            AILogger.i(TAG, "Tool call vetoed by policy: " + toolName);
            return OnlineToolResult.failure(toolCallId, toolName,
                    "工具调用被策略拒绝（TOOL_PRE_EXECUTE vetoed）", 0);
        }
        int tokensBefore = execTotalPromptTokens + execTotalCompletionTokens;
        AILogger.i(TAG, "Executing tool: " + toolName + " args: " + arguments
            + " | 调用前 token 累计: " + tokensBefore);
        OnlineToolResult result = toolManager.executeTool(toolCallId, toolName, arguments);
        // ——发现制闭环（2026-09-23 v5）：模型实际调用（含 tool_registry get 的目标工具）成功后，
        // 自动并入会话工具缓存，并立即刷新 liveToolsJson——当前 execute 的下一迭代就能直接调用
        // 该工具（MCP 语义：get 后本轮可调，不等下条用户消息）——
        if (result != null && result.success) {
            if ("tool_registry".equals(toolName) && arguments != null) {
                String target = extractToolRegistryTarget(arguments);
                if (target != null && !target.isEmpty()) registerToolIntoSession(target);
            }
            registerToolIntoSession(toolName);
            if (cachedToolsJson != null) {
                liveToolsJson = cachedToolsJson;
                AILogger.i(TAG, "Live tools refreshed: next iteration carries updated toolset ("
                        + cachedToolNames.size() + " tools)");
            }
        }
        eventBus.post(AgentEventBus.EventType.TOOL_RESULT, toolName,
                result != null ? result.result : null);
        int tokensAfter = execTotalPromptTokens + execTotalCompletionTokens;
        int resultLen = (result != null && result.result != null) ? result.result.length() : 0;
        AILogger.i(TAG, "Tool done: " + toolName + " | 调用后 token 累计: " + tokensAfter
            + " | 工具结果长度: " + resultLen);
        return result;
    }

    /**
     * 发现制闭环：工具调用成功后并入会话级工具缓存（只增不改，下一轮请求直接带上 schema）。
     */
    private void registerToolIntoSession(String toolName) {
        try {
            if (toolName == null || toolName.isEmpty()) return;
            if (cachedToolNames != null) {
                toolLastUsed.put(toolName, System.currentTimeMillis());
                // 用完即走策略下，调用即注册仅作用于「当前 execute 内」多轮迭代；
                // 跨 execute 由 getStableToolsJson 重建（核心+本轮命中），不再累积
                boolean added = cachedToolNames.add(toolName);
                // 2026-09-23 v9：定义总是刷新——即使工具名已在缓存（只增不改的旧逻辑下，
                // 动态工具 update/重新注册后 schema 变化，缓存仍是旧快照 → 误导模型）。
                // 调用成功即重拉缓存集最新定义：新增=注册，已存在=刷新。
                cachedToolsJson = toolManager.getToolDefinitionsForNames(cachedToolNames);
                AILogger.i(TAG, (added ? "Tool auto-registered after call: " : "Tool definition refreshed after call: ")
                        + toolName + ", total=" + cachedToolNames.size());
            }
        } catch (Exception e) {
            AILogger.e(TAG, "registerToolIntoSession failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从 tool_registry(action=get) 的 arguments 中解析目标工具名。
     * 支持 {"action":"get","tool":"ai_weather"} 及带空格/单引号变体。
     */
    private String extractToolRegistryTarget(String arguments) {
        try {
            if (arguments == null) return null;
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(arguments).getAsJsonObject();
            String action = obj.has("action") ? obj.get("action").getAsString() : "";
            if ("get".equals(action) && obj.has("tool")) {
                return obj.get("tool").getAsString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * 构建环境上下文：当前日期时间 + 位置（可选）。
     * 天气不注入——Agent 有 ai_weather 工具，用户问天气时主动调用获取完整信息；
     * 注入天气既浪费 token 又拖慢启动（每次执行前等待位置+天气），且摘要易截断。
     * 位置获取有5秒超时，失败则仅使用日期时间。
     */
    /**
     * 知识库自动检索（2026-09-23）：对用户消息全文检索知识库，返回 top-3 命中片段的
     * 【相关资料】提示块；知识库为空/无命中/失败返回 null（不注入，模型正常作答）。
     * 检索是引擎级硬编码（不消耗模型工具调用轮次、不污染历史，仅本轮请求尾部生效）。
     */
    private String buildKnowledgeContext(String userMessage) {
        try {
            if (userMessage == null || userMessage.trim().isEmpty()) return null;
            com.oilquiz.app.ai.knowledge.KnowledgeBaseManager kb =
                    com.oilquiz.app.ai.knowledge.KnowledgeBaseManager.getInstance(context);
            org.json.JSONArray hits = kb.search(userMessage.trim(), null, 8, false);
            if (hits == null || hits.length() == 0) return null;
            StringBuilder sb = new StringBuilder();
            sb.append("【相关资料】以下内容来自你的知识库（自动检索，与当前问题相关），回答时优先参考，不要凭空作答：\n");
            int injected = 0;
            for (int i = 0; i < hits.length(); i++) {
                org.json.JSONObject item = hits.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title", "");
                String content = item.optString("content", "");
                String category = item.optString("category", "");
                // 过滤工具定义条目（2026-09-23）：tool_defs 是引擎工具库，工具 schema 已在 tools 数组注入，
                // 再以文本进 D2 块会与 tools 重复；source=system_tool_defs 同样排除
                if ("tool_defs".equals(category) || "system_tool_defs".equals(item.optString("source", ""))) {
                    continue;
                }
                if (injected >= 3) break;
                if (title.isEmpty() && content.isEmpty()) continue;
                sb.append("[").append(i + 1).append("] ");
                if (!title.isEmpty()) sb.append(title).append(" ");
                if (!category.isEmpty() && !"general".equals(category)) sb.append("(").append(category).append(")");
                sb.append("\n");
                if (!content.isEmpty()) {
                    String c = content.length() > 500 ? content.substring(0, 500) + "…" : content;
                    sb.append(c).append("\n");
                }
                injected++;
            }
            if (injected == 0) return null;
            String result = sb.toString();
            AILogger.i(TAG, "Knowledge auto-retrieval: " + injected + "/" + hits.length() + " hits injected for query='"
                    + (userMessage.length() > 40 ? userMessage.substring(0, 40) + "…" : userMessage) + "'");
            return result;
        } catch (Throwable t) {
            AILogger.w(TAG, "Knowledge auto-retrieval skipped: " + t.getMessage());
            return null;
        }
    }

    private String buildEnvironmentContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("【环境上下文】\n");

        // 1. 当前日期时间（始终可用）。
        // 注意：格式只到"日"级别，不含分钟——避免 Activity 重建后重新注入时
        // 前缀因分钟变化而不同，导致服务商前缀缓存 miss
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);
        String dateTime = sdf.format(new Date());
        sb.append("当前日期：").append(dateTime).append("\n");

        // 2. 获取位置（5秒超时，失败仅用日期）
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
                    notifyStep("环境感知", "✅ 位置: " + locationInfo);
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

        sb.append("（以上为系统实时获取的当前日期与位置，是当前权威事实；回答今天/现在/最新/几号等问题以此为准，");
        sb.append("不以训练数据中的旧时间推断。工具与搜索返回的实时数据（新闻、开奖、行情、政策、天气）直接采用其内容，");
        sb.append("不要用训练知识改写或否定。）");
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
     * 获取「模型驱动」的工具定义 JSON（2026-09-23 v4，dsh-agent-loop 对齐）。
     *
     * dsh 设计：工具集 per-agent 稳定呈现、模型自主选择，引擎不做关键词/意图预判。
     * 本方法让 tools 段 = 核心 6（前缀稳定，缓存命中）+ 高频预载（历史行为自适应，≤2）：
     * - 零预判：不关键词、不 KB 工具检索、不问模型意图——模型要什么工具，
     *   tool_registry(get) 发现（50 token 轻量），调用即注册进本轮缓存；
     * - 用完即走：非核心工具不跨 execute 累积，工具执行结果已进历史；
     * - 核心 6 顺序固定 → 服务商前缀缓存持续命中。
     */
    private String getStableToolsJson(String userMessage, Set<String> coreTools,
                                      OnlineModelManager.OnlineModelConfig cfg) {
        try {
            // 1. 模型驱动：核心 6 稳定呈现（dsh：per-agent 稳定工具集，模型自主选择）
            cachedToolNames.clear();
            cachedToolNames.addAll(coreTools);

            // 2. 高频工具预载（自进化，非预判）：历史共现统计里的常客工具首轮就在场，
            //    省发现轮；上限 2 个防膨胀。这是观察模型历史行为的自适应，dsh 无此机制但无害。
            java.util.Set<String> frequent = toolManager.getFrequentToolsFromPatterns(coreTools, 3, 2);
            for (String f : frequent) {
                if (!coreTools.contains(f)) cachedToolNames.add(f);
            }
            if (!frequent.isEmpty()) {
                AILogger.i(TAG, "Frequent tools preloaded: +" + frequent.size()
                        + " → " + cachedToolNames.size() + " tools total");
            }

            // 3. LRU 上限（兜底）：注入过多时淘汰最旧非核心工具
            trimSessionTools(coreTools);
            cachedToolsJson = toolManager.getToolDefinitionsForNames(cachedToolNames);
            AILogger.i(TAG, "Tool definitions (model-driven): count=" + countToolsInJson(cachedToolsJson)
                    + ", json_len=" + (cachedToolsJson != null ? cachedToolsJson.length() : 0)
                    + ", cache_size=" + cachedToolNames.size());
            return cachedToolsJson;
        } catch (Exception e) {
            AILogger.w(TAG, "getStableToolsJson failed, fallback to core tools: " + e.getMessage());
            return toolManager.getToolDefinitionsForNames(coreTools);
        }
    }

    /**
     * 会话工具集 LRU 上限（2026-09-23）：超过 MAX_SESSION_TOOLS 时，
     * 按最后使用时间淘汰最旧的非核心工具（核心工具永不淘汰）。
     * 兼顾前缀缓存稳定（核心段不变）与长会话体积控制（扩展工具最多 6 个）。
     */
    private void trimSessionTools(java.util.Set<String> coreTools) {
        try {
            if (cachedToolNames.size() <= MAX_SESSION_TOOLS) return;
            java.util.List<String> removable = new java.util.ArrayList<>();
            for (String n : cachedToolNames) {
                if (coreTools.contains(n)) continue;
                removable.add(n);
            }
            removable.sort((a, b) -> Long.compare(
                    toolLastUsed.getOrDefault(a, 0L), toolLastUsed.getOrDefault(b, 0L)));
            int evicted = 0;
            for (String n : removable) {
                if (cachedToolNames.size() <= MAX_SESSION_TOOLS) break;
                if (cachedToolNames.remove(n)) {
                    toolLastUsed.remove(n);
                    evicted++;
                }
            }
            if (evicted > 0) {
                AILogger.i(TAG, "Session tool LRU trim: evicted " + evicted
                        + ", total=" + cachedToolNames.size() + " (cap=" + MAX_SESSION_TOOLS + ")");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "trimSessionTools failed: " + e.getMessage());
        }
    }

    /**
     * 从工具定义 JSON 中按出现顺序提取工具名（仅 function 类型）。
     */
    private static List<String> extractToolNames(String toolsJson) {
        List<String> names = new ArrayList<>();
        if (toolsJson == null || toolsJson.isEmpty() || toolsJson.equals("[]")) return names;
        try {
            JsonArray arr = JsonParser.parseString(toolsJson).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject tool = arr.get(i).getAsJsonObject();
                if (tool.has("function") && tool.get("function").isJsonObject()) {
                    JsonObject fn = tool.get("function").getAsJsonObject();
                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                        names.add(fn.get("name").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {}
        return names;
    }

    /**
     * SHA-256 十六进制摘要（缓存诊断探针用）。
     */
    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "ERR";
        }
    }

    /**
     * 重置会话级工具缓存（新会话/清空历史时调用，避免跨会话复用旧工具集）。
     */
    private void resetToolsCache() {
        cachedToolNames.clear();
        cachedToolsJson = null;
        toolsCacheInitialized = false;
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
    /**
     * 动态获取工具标识符：AIToolManager 当前注册工具名 + 常用意图别名，
     * 始终与注册表同步（不再硬编码过时工具表）。
     */
    private String[] getToolIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        try {
            if (context != null) {
                ids.addAll(com.oilquiz.app.ai.tool.AIToolManager
                        .getInstance(context).getRegisteredToolNames());
            }
        } catch (Exception ignored) {
        }
        // 常见别名/伪代码名（非注册工具，仅用于识别"暗示调用"）
        ids.addAll(java.util.Arrays.asList(
                "weather", "web_search", "search_tool", "wiki", "wikipedia",
                "calculator", "calc", "note_tool", "drawing_tool",
                "get_weather", "translate", "翻译", "搜索", "查天气"));
        return ids.toArray(new String[0]);
    }

    private boolean hintForToolCall(String content) {
        if (content == null) return false;
        String text = content.trim();
        if (text.isEmpty()) return false;
        // 只取前 800 字做判断，避免长文本拖慢性能
        if (text.length() > 800) text = text.substring(0, 800);
        String lower = text.toLowerCase(java.util.Locale.ROOT);

        // 工具标识符列表（动态取注册工具名 + 别名）
        final String[] toolIds = getToolIds();

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

        // 3. 中文明确动作句式：动作动词 + 紧邻（≤15字符）的注册工具名
        // （不再匹配"工具/函数/接口"宽泛词——"我将使用工具计算"这类正常回复会误触发）
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
    /**
     * 判断模型是否具备 Agent 能力（是否启用接管模式）。
     * 优先"询问模型"：向模型发送最小 function calling 探针请求，
     * 看它是否真的返回 tool_calls（而非硬编码模型名匹配——模型训练时见过的格式
     * 与真实能力可能不符，且新模型无法预判）。
     *
     * 探针结果优先级：
     * 1. 探针明确支持（true）→ 接管模式
     * 2. 探针明确不支持（false）→ 辅助模式
     * 3. 探针未知/失败（null）→ 回退配置字段/模型名推断（不降级能力）
     *
     * @param cfg 在线模型配置
     * @return true 表示模型具备 agent 能力，应启用接管模式
     */
    private boolean detectAgentCapability(OnlineModelManager.OnlineModelConfig cfg) {
        if (cfg == null) return false;
        // 探针询问模型（结果持久化缓存：不换模型不重复探测；失败默认支持不降级）
        boolean probe = onlineInferenceService.probeFunctionCalling(cfg);
        AILogger.i(TAG, "Function calling probe: " + (cfg.modelName != null ? cfg.modelName : "?")
                + " supports=" + probe);
        return probe;
    }

    /**
     * 判断是否为闲聊消息（无需工具意图识别）。
     * 简短问候/情绪/无任务诉求的消息跳过模型意图识别，避免浪费一次 API 调用。
     */
    /**
     * 「继续」类消息接续（2026-09-23）：用户发"继续/接着/再来/往下"时，
     * 若会话仍有活跃任务，转成【继续任务】引导消息——带任务上下文、触发工具轮，
     * 避免模型在 tools=null 下用 DSML 文本硬调工具、或空转找上文。
     */
    private String maybeResolveContinueMessage(String userMessage) {
        if (userMessage == null) return null;
        String m = userMessage.trim();
        if (!isContinueIntent(m)) return userMessage;
        try {
            TaskStateTracker tracker = TaskStateTracker.getInstance(context);
            boolean hasActive = false;
            for (TaskStateTracker.TaskEntry e : tracker.getAll()) {
                if (TaskStateTracker.STATUS_IN_PROGRESS.equals(e.status)
                        || TaskStateTracker.STATUS_TODO.equals(e.status)) {
                    hasActive = true;
                    break;
                }
            }
            if (hasActive) {
                AILogger.i(TAG, "User '" + m + "' resolved to goal-continue prompt (active tasks remain)");
                return "【继续任务】用户说\"" + m + "\"。请检查任务进展：已完成的部分不要重复；"
                        + "还有剩余步骤就继续调用工具推进（一次推进即可）；"
                        + "需要用户提供信息/做选择则停下来询问。";
            }
            AILogger.i(TAG, "User '" + m + "' has no active tasks, pass through as-is");
        } catch (Throwable t) {
            AILogger.w(TAG, "Resolve continue failed: " + t.getMessage());
        }
        return userMessage;
    }

    /** 判断消息是否为"继续"类意图（短、无其他诉求） */
    private static boolean isContinueIntent(String m) {
        if (m == null || m.isEmpty()) return false;
        String s = m.toLowerCase();
        if (s.contains("继续") || s.contains("接着") || s.contains("再来")
                || s.contains("往下") || s.contains("继续生成") || s.contains("继续做")) {
            // 排除"继续帮我看一下XX"这类明确新诉求（含具体动词+对象的继续，按普通消息处理）
            return s.length() <= 12 || !s.contains("帮");
        }
        return false;
    }

    /**
     * 闲聊模式已废弃（2026-09-23，用户决定）：不再区分闲聊/任务轮，
     * 每一轮都按正常任务处理——恒带工具、恒带记忆、恒做知识库检索。
     * 收益：模型能力全开，"继续/接着"等短消息不再被误判闲聊导致 tools=null
     * （根除 DSML 文本硬调工具问题）；代价是闲聊轮多带 ~3K token（tools），
     * 但缓存命中后实际增量很小。恒返回 false = 永远非闲聊。
     */
    private boolean isCasualChat(String message) {
        return false;
    }

    private static boolean containsAnyCasual(String msg, String... keywords) {
        String m = msg.toLowerCase();
        for (String k : keywords) {
            if (m.contains(k)) return true;
        }
        return false;
    }

    /**
     * 询问模型识别用户消息的任务意图（精准意图判定兜底）。
     * 轻量一次调用（关闭 thinking，maxTokens 小），让模型从固定意图清单中选，
     * 返回 JSON 数组；失败/超时返回 null（调用方保持关键词结果，不阻塞）。
     */
    private java.util.Set<String> classifyIntentByModel(OnlineModelManager.OnlineModelConfig cfg,
                                                        String userMessage) {
        if (cfg == null || userMessage == null) return null;
        try {
            String intents = java.util.Arrays.asList(
                    "weather", "search", "file_read", "file_write",
                    "image_gen", "image_ocr", "database", "python", "calc",
                    "location", "time", "app", "system", "phone", "study_plan"
            ).toString();
            String prompt = "分析用户消息属于哪些任务意图，从以下意图中选出最匹配的（可多选，用逗号分隔）："
                    + intents + "\n"
                    + "用户消息: " + userMessage + "\n"
                    + "只输出意图名，用逗号分隔，如: search,file_write。若无匹配输出: none";
            String result = onlineInferenceService.generateOnceAsync(prompt, cfg, 64)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (result == null) return null;
            String clean = result.trim().toLowerCase();
            if (clean.isEmpty() || "none".equals(clean)) return null;
            java.util.Set<String> matched = new java.util.LinkedHashSet<>();
            java.util.Map<String, java.util.List<String>> intentMap =
                    com.oilquiz.app.ai.agent.online.OnlineToolManager.getIntentToToolMap();
            for (String intent : clean.split("[,\\s]+")) {
                if (intentMap.containsKey(intent)) matched.add(intent);
            }
            if (!matched.isEmpty()) {
                AILogger.i(TAG, "Model intent: " + matched);
            }
            return matched.isEmpty() ? null : matched;
        } catch (Exception e) {
            AILogger.w(TAG, "Intent classification failed: " + e.getMessage());
            return null;
        }
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
        endDebugRun("cancelled", null);
        if (lastTurnMeta != null) {
            lastTurnMeta.put("reason", "user_cancel");
        }
    }

    public void shutdown() {
        cancel();
        messageHistory.clear();
        thinkingChain.clear();
        resetToolsCache();
    }

    /** 获取上一回合审计 meta（reason/turns/toolCalls/tokens/model），无则返回 null。dsh turn/end 对齐 */
    public String getLastTurnMetaJson() {
        if (lastTurnMeta == null) return null;
        try {
            return new com.google.gson.Gson().toJson(lastTurnMeta);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 获取上一回合结束原因（final_complete / max_iterations / user_cancel / error / running） */
    public String getLastTurnReason() {
        if (lastTurnMeta == null) return null;
        Object r = lastTurnMeta.get("reason");
        return r != null ? String.valueOf(r) : null;
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
        // 生成中禁止清空：执行线程正在读写 messageHistory，并发清空会导致 CME/状态错乱
        if (isGenerating.get()) {
            AILogger.w(TAG, "clearHistory ignored: generating in progress");
            return;
        }
        messageHistory.clear();
        thinkingChain.clear();
        deleteHistoryFile();
        resetToolsCache();
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
            AILogger.i(TAG, "compressHistory skipped: generating in progress");
            if (callback != null) callback.accept(null);
            return;
        }
        final OnlineModelManager.OnlineModelConfig cfg = onlineInferenceService.getActiveConfig();
        if (cfg == null) {
            AILogger.w(TAG, "compressHistory skipped: no active online model config");
            if (callback != null) callback.accept(null);
            return;
        }

        executor.submit(() -> {
            try {
                // 2026-09-23 修复：压缩前先兜底恢复历史——新开页面未发消息时 messageHistory
                // 内存为空（历史在磁盘），直接按内存计数会误判"对话太短"→ 压缩永远失败。
                // ensureHistoryLoadedOnce 只在 execute 调用，此处补一次（幂等）。
                ensureHistoryLoadedOnce();
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
                    AILogger.i(TAG, "compressHistory skipped: dialogue too short (userMsgs=" + userMsgCount + ")");
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
        // 生成中禁止清空（并发读写 messageHistory 风险）
        if (isGenerating.get()) {
            AILogger.w(TAG, "clearAllHistory ignored: generating in progress");
            return;
        }
        messageHistory.clear();
        thinkingChain.clear();
        resetToolsCache();
        try {
            java.io.File dir = context.getFilesDir();
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
     * 缓存命中优化：旧 env 消息【原位不动】（保持 messages 前缀字节稳定），
     * 仅把最新环境上下文追加到历史末尾，模型仍能读到新日期。
     * 若历史末尾已有含今日日期的 env（上次刷新已追加），直接跳过避免累积。
     */
    private void refreshEnvIfStale() {
        try {
            String today = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(new Date());
            boolean staleEnvFound = false;
            for (JsonObject msg : messageHistory) {
                String role = msg.has("role") ? msg.get("role").getAsString() : "";
                if ("system".equals(role)) {
                    String content = msg.has("content") ? msg.get("content").getAsString() : "";
                    if (content != null && content.startsWith("【环境上下文】")) {
                        if (content.contains(today)) {
                            return; // 已存在今日日期的 env（本次会话已刷新过），无需再处理
                        }
                        staleEnvFound = true; // 找到过期 env，继续检查末尾是否已有新 env
                    }
                }
            }
            if (!staleEnvFound) return;
            String envContext = buildEnvironmentContext();
            if (envContext != null && !envContext.isEmpty()) {
                JsonObject envMsg = new JsonObject();
                envMsg.addProperty("role", "system");
                envMsg.addProperty("content", envContext);
                messageHistory.add(envMsg);
                AILogger.i(TAG, "Environment context appended (date changed to " + today
                        + ", old env kept for prefix stability)");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "refreshEnvIfStale failed: " + e.getMessage());
        }
    }

    // ==================== 对话历史持久化（按会话隔离，跨 Activity 重建/重启保持前缀稳定，利于缓存命中） ====================

    /**
     * 历史文件路径：按「会话 × 模型」双维度隔离。
     * 会话维度：非空 sessionId 时用 online_agent_history_{id}.json；
     * 模型维度：activeModelId 非空时追加 _{model} 后缀，不同模型各自独立上下文，
     * 避免 A 模型的历史（system 提示词/工具调用记录/日期前缀）污染 B 模型。
     */
    private java.io.File getHistoryFile() {
        String modelTag = (activeModelId != null && !activeModelId.isEmpty())
                ? "_" + activeModelId.replaceAll("[^a-zA-Z0-9_-]", "_")
                : "";
        if (sessionId != null && !sessionId.isEmpty()) {
            String safeId = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
            return new java.io.File(context.getFilesDir(), "online_agent_history_" + safeId + modelTag + ".json");
        }
        return new java.io.File(context.getFilesDir(), "online_agent_history" + modelTag + ".json");
    }

    /**
     * 设置当前在线模型 ID（模型切换时调用）。
     * 与 setSessionId 同款「保存当前 → 清空内存 → 恢复目标」模式：
     * 模型 A → B 切换时，先把 A 的当前历史持久化到 A 专属文件，
     * 再清空内存并按 B 的专属文件恢复 —— 各模型上下文互不污染、可独立续聊。
     * 传 null 表示未知/本地路径（回退到无模型后缀的文件）。
     */
    public void setModelId(String newModelId) {
        if (isGenerating.get()) {
            AILogger.w(TAG, "setModelId ignored: generating in progress");
            return;
        }
        String old = this.activeModelId;
        boolean changed = (old == null) ? (newModelId != null && !newModelId.isEmpty())
                : !old.equals(newModelId);
        if (!changed) return;

        // 先持久化当前模型的历史，避免切换丢失
        persistHistory();
        this.activeModelId = newModelId;
        // 清空并恢复目标模型历史
        messageHistory.clear();
        thinkingChain.clear();
        resetToolsCache();
        restoreHistory();
        AILogger.i(TAG, "Model history switched: " + old + " -> " + newModelId);
    }

    /**
     * 设置当前会话 ID（会话切换时调用）。
     * 保存当前会话历史 → 清空内存 → 切换目标文件 → 恢复目标会话历史。
     * 传 null/空 表示回到默认会话。
     */
    public void setSessionId(String newSessionId) {
        // 生成中禁止切换会话：messageHistory 正被执行线程读写，切换会污染会话内容
        if (isGenerating.get()) {
            AILogger.w(TAG, "setSessionId ignored: generating in progress");
            return;
        }
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
        resetToolsCache();
        restoreHistory();
        AILogger.i(TAG, "Session switched: " + old + " -> " + newSessionId
            + ", restored=" + messageHistory.size() + " messages");
    }

    /** 保存当前对话历史到私有文件（按会话隔离） */
    private void persistHistory() {
        try {
            // 回合审计 meta 落盘（dsh turn/end 对齐）：按 会话×模型 隔离，Activity 可查询
            if (lastTurnMeta != null) {
                try {
                    lastTurnMeta.put("promptTokens", execTotalPromptTokens);
                    lastTurnMeta.put("completionTokens", execTotalCompletionTokens);
                    lastTurnMeta.put("model", onlineInferenceService.getActiveConfig() != null
                            ? onlineInferenceService.getActiveConfig().modelName : "");
                    android.content.SharedPreferences sp = context.getSharedPreferences("ai_prefs", android.content.Context.MODE_PRIVATE);
                    sp.edit().putString("agent_turn_meta_" + sessionId + "_" + activeModelId,
                            new com.google.gson.Gson().toJson(lastTurnMeta)).apply();
                } catch (Throwable metaErr) {
                    AILogger.w(TAG, "persist turn meta failed: " + metaErr.getMessage());
                }
            }
            // 持久化前清洗悬空 tool 消息，避免脏数据落盘后每次恢复都带进来
            removeOrphanToolMessages();
            if (messageHistory.isEmpty()) {
                deleteHistoryFile();
                return;
            }
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonArray arr = new JsonArray();
            for (JsonObject msg : messageHistory) {
                // 缓存命中优化：system 消息落盘时用【副本】打版本标记（pv 字段仅存于文件，
                // 不修改内存对象，避免下一轮请求体带出该字段破坏前缀）；
                // restore 时按版本决定保留（前缀稳定）或重建（提示词升级）
                String role = msg.has("role") && !msg.get("role").isJsonNull()
                        ? msg.get("role").getAsString() : "";
                if ("system".equals(role) && !msg.has("pv")) {
                    JsonObject copy = msg.deepCopy();
                    copy.addProperty("pv", PROMPT_VERSION);
                    arr.add(copy);
                } else {
                    arr.add(msg);
                }
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
     * 清洗 messageHistory 中的悬空 tool 消息（无配对 assistant.tool_calls 的 role=tool 消息）。
     * 悬空 tool 消息会让 GLM/OpenAI 兼容接口返回 HTTP 400
     * ("Messages with role 'tool' must be a response to a preceding message with 'tool_calls'")。
     * 与 buildOutgoingMessagesArray 的发送层跳过互为兜底：这里修本体，发送层防漏网。
     */
    private void removeOrphanToolMessages() {
        boolean toolCallsOpen = false;
        java.util.Iterator<JsonObject> it = messageHistory.iterator();
        int removed = 0;
        while (it.hasNext()) {
            JsonObject msg = it.next();
            String role = msg.has("role") && !msg.get("role").isJsonNull()
                    ? msg.get("role").getAsString() : "";
            if ("assistant".equals(role)) {
                boolean hasToolCalls = msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()
                        && msg.get("tool_calls").isJsonArray()
                        && msg.getAsJsonArray("tool_calls").size() > 0;
                toolCallsOpen = hasToolCalls;
            } else if ("tool".equals(role)) {
                if (!toolCallsOpen) {
                    it.remove();
                    removed++;
                }
                // tool 消息不关闭配对段：多个 tool 消息可与同一 assistant.tool_calls 配对
            } else {
                toolCallsOpen = false;
            }
        }
        if (removed > 0) {
            AILogger.i(TAG, "Removed " + removed + " orphan tool messages from history");
        }
    }

    /**
     * 从私有文件恢复对话历史（按会话隔离）。
     * 缓存命中优化：持久化时 system 消息带版本标记（pv）。
     * - 版本匹配（同版本提示词）：原样保留 system 提示词/环境上下文/长期记忆摘要，
     *   切换模型再切回（或 Activity 重建）后，请求前缀与切换前逐字节一致，
     *   服务商前缀缓存命中最大化（不重建 env/memory，避免前缀从注入点断裂）；
     * - 版本缺失/不匹配（提示词升级或旧数据）：丢弃 system（含过期提示词与环境上下文），
     *   下次 execute 按当前版本重建 —— 缓存 miss 一次属合理（提示词确实变了）。
     * 【对话历史摘要】无论版本一律保留：被压缩掉的旧消息只存在于摘要中，丢弃即永久失忆。
     */
    private void restoreHistory() {
        try {
            java.io.File file = getHistoryFile();
            if (!file.exists()) return;
            java.io.FileReader reader = new java.io.FileReader(file);
            JsonArray arr = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();

            // 检测第一个 system 消息的版本标记，决定是否原样保留 system 块
            boolean keepSystem = false;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject m = arr.get(i).getAsJsonObject();
                String role = m.has("role") && !m.get("role").isJsonNull()
                        ? m.get("role").getAsString() : "";
                if ("system".equals(role)) {
                    if (m.has("pv") && !m.get("pv").isJsonNull()
                            && PROMPT_VERSION.equals(m.get("pv").getAsString())) {
                        keepSystem = true;
                    }
                    break; // 只看第一个 system 消息（提示词）
                }
            }

            messageHistory.clear();
            pendingSummaryMessage = null;
            int systemDiscarded = 0;
            int memorySkipped = 0;
            int orphanSkipped = 0;
            boolean toolCallsOpen = false;
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
                    if (content != null && content.contains("【长期记忆】")) {
                        memorySkipped++;
                        continue; // 长期记忆已移出历史（请求末尾 D 块动态注入），不占前缀
                    }
                    if (keepSystem) {
                        // 版本匹配：原样保留 system/环境上下文（前缀与切换前一致，缓存命中最大化）
                        msg.remove("pv"); // 版本标记仅落盘用，不进请求体
                        messageHistory.add(msg);
                        continue;
                    }
                    systemDiscarded++;
                    continue; // 版本不匹配：丢弃旧 system（提示词/环境上下文），下次 execute 重建
                }
                if ("assistant".equals(role)) {
                    boolean hasToolCalls = msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()
                            && msg.get("tool_calls").isJsonArray()
                            && msg.getAsJsonArray("tool_calls").size() > 0;
                    toolCallsOpen = hasToolCalls;
                    messageHistory.add(msg);
                } else if ("tool".equals(role)) {
                    if (toolCallsOpen) {
                        messageHistory.add(msg);
                    } else {
                        orphanSkipped++;
                    }
                } else {
                    toolCallsOpen = false;
                    messageHistory.add(msg);
                }
            }
            if (orphanSkipped > 0) {
                AILogger.i(TAG, "Restore: skipped " + orphanSkipped + " orphan tool messages");
            }
            if (!messageHistory.isEmpty()) {
                AILogger.i(TAG, "Restored agent history: " + messageHistory.size()
                    + " messages (session=" + sessionId + ", keep_system=" + keepSystem
                    + ", discarded_system=" + systemDiscarded + ", memory_skipped=" + memorySkipped
                    + ", summary=" + (pendingSummaryMessage != null) + ")");
            }
        } catch (Exception e) {
            // 2026-09-23 v7.1：解析异常只告警【不删文件】——删了历史就永久丢失；
            // 保留文件供下次 execute 兜底重试（restoreHistory 幂等）。
            AILogger.w(TAG, "Failed to restore agent history: " + e.getMessage()
                    + " (file kept for retry)");
        }
    }

    /** execute 前的一次性历史兜底恢复（见 execute 调用点） */
    private void ensureHistoryLoadedOnce() {
        if (historyLoadAttempted) return;
        historyLoadAttempted = true;
        try {
            if (sessionId == null || sessionId.isEmpty()) return;
            if (!messageHistory.isEmpty()) return;
            // 优先用引擎 activeModelId；若冷启动时序未设置（getActiveModel 晚到），
            // 回退用运行时配置的模型 id（cfg.id）——只读尝试，不写引擎字段。
            String tag = activeModelId;
            if (tag == null || tag.isEmpty()) {
                OnlineModelManager.OnlineModelConfig cfg = onlineInferenceService.getActiveConfig();
                if (cfg != null && cfg.id != null && !cfg.id.isEmpty()) tag = cfg.id;
            }
            if (tag == null || tag.isEmpty()) return;
            java.io.File f = new java.io.File(context.getFilesDir(),
                    "online_agent_history_"
                            + sessionId.replaceAll("[^a-zA-Z0-9_-]", "_")
                            + "_" + tag.replaceAll("[^a-zA-Z0-9_-]", "_") + ".json");
            if (!f.exists()) return;
            AILogger.i(TAG, "History fallback restore (cold-start modelId was late): " + f.getName());
            restoreHistory();
        } catch (Throwable t) {
            AILogger.w(TAG, "ensureHistoryLoadedOnce failed: " + t.getMessage());
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
        endDebugRun("ok", null);
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
        eventBus.post(AgentEventBus.EventType.TURN_END,
                lastTurnMeta != null && lastTurnMeta.get("reason") != null
                        ? String.valueOf(lastTurnMeta.get("reason")) : "final_complete");
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
        mainHandler.post(() -> {
            if (callback != null) callback.onComplete(finalOutput);
        });
    }

    private void notifyError(String error) {
        endDebugRun("error", error);
        finishGeneration();
        eventBus.post(AgentEventBus.EventType.TURN_END, "error: " + (error != null ? error : ""));
        eventBus.post(AgentEventBus.EventType.REQUEST_ERROR, error != null ? error : "");
        mainHandler.post(() -> {
            if (callback != null) callback.onError(error);
        });
    }

    private void notifyStep(String step, String detail) {
        mainHandler.post(() -> {
            if (callback != null) callback.onStepUpdate(step, detail);
        });
    }

    private void notifyExecutionStep(OnlineExecutionStep step, String detail) {
        mainHandler.post(() -> {
            if (callback != null) callback.onExecutionStep(step, detail);
        });
    }

    private void notifyProgress() {
        if (progressListener == null) return;
        long elapsed = System.currentTimeMillis() - inferenceStartTime;
        float tps = elapsed > 0 ? (totalTokenCount * 1000f / elapsed) : 0;
        final int tokens = totalTokenCount;
        final float tpsFinal = tps;
        mainHandler.post(() -> {
            if (progressListener != null) {
                progressListener.onProgressUpdate(tokens, tpsFinal);
            }
        });
    }

    /**
     * 目标自动续行（dsh goal-round-driver 对齐，2026-09-23）：
     * 本轮 execute 正常结束后，若任务清单仍有活跃任务（in_progress/todo）且本轮实际推进过
     * 工具调用，自动注入【继续任务】提示词再跑一轮，直到任务完成 / 达续行上限 / 用户打断。
     * 约束（防失控）：MAX_AUTO_CONTINUE 轮数上限、MIN_AUTO_CONTINUE_INTERVAL_MS 间隔、
     * isCancelled / isGenerating 门禁、活跃任务存在性检查——任何一条不满足即停止。
     */
    private void maybeAutoContinue() {
        try {
            if (isCancelled.get() || isGenerating.get()) return;
            if (autoContinueCount >= MAX_AUTO_CONTINUE) return;
            long now = System.currentTimeMillis();
            if (now - lastAutoContinueTs < MIN_AUTO_CONTINUE_INTERVAL_MS) return;
            // 上轮必须实际推进过工具（纯问答/闲聊不触发自动续行）
            Object tc = lastTurnMeta != null ? lastTurnMeta.get("toolCalls") : null;
            if (!(tc instanceof Number) || ((Number) tc).intValue() <= 0) return;
            // 必须仍有活跃任务（dsh：goal phase=active 才驱动 round；全部完成/失败则停）
            TaskStateTracker tracker = TaskStateTracker.getInstance(context);
            boolean hasActive = false;
            for (TaskStateTracker.TaskEntry e : tracker.getAll()) {
                if (TaskStateTracker.STATUS_IN_PROGRESS.equals(e.status)
                        || TaskStateTracker.STATUS_TODO.equals(e.status)) {
                    hasActive = true;
                    break;
                }
            }
            if (!hasActive) return;

            autoContinueCount++;
            lastAutoContinueTs = now;
            String goalMsg = "【继续任务】你的任务清单中仍有进行中的任务。请检查进展："
                    + "若任务已完成请用 task complete 标记完成；"
                    + "若还有剩余步骤请继续调用工具推进（一次推进即可，不要重复已完成的部分）；"
                    + "若需要用户提供信息/做选择，则停下来向用户询问。"
                    + "（本轮为自动续行第 " + autoContinueCount + " 次，最多 " + MAX_AUTO_CONTINUE + " 次）";
            AILogger.i(TAG, "Goal auto-continue round " + autoContinueCount + "/" + MAX_AUTO_CONTINUE
                    + " (active tasks remain)");
            execute(goalMsg, 0, false, true);
        } catch (Throwable t) {
            AILogger.w(TAG, "Auto-continue failed: " + t.getMessage());
        }
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
