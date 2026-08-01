package com.oilquiz.app.ai.agent;

import android.app.Activity;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.python.PythonToolManager;
import com.oilquiz.app.ai.refactor.UnifiedContextManager;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.tool.AIToolUsageGuide;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.refactor.AIInferenceCore;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class UnifiedAgentEngine {

    /**
     * 可重置的 CountDownLatch
     * 支持多次 reset() 操作
     */
    public static class ResettableCountDownLatch {
        private final int count;
        private java.util.concurrent.CountDownLatch latch;

        public ResettableCountDownLatch(int count) {
            this.count = count;
            this.latch = new java.util.concurrent.CountDownLatch(count);
        }

        public void countDown() {
            latch.countDown();
        }

        public void await() throws InterruptedException {
            latch.await();
        }

        public boolean await(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        public void reset() {
            latch = new java.util.concurrent.CountDownLatch(count);
        }

        public long getCount() {
            return latch.getCount();
        }
    }

    private static final String TAG = "UnifiedAgentEngine";

    // 使用 ChatModeManager 的统一基础提示词
    private static String getDefaultGlobalPrompt() {
        return "你是一个AI助手，请用中文回答。";
    }

    private static String getDefaultSystemPrompt() {
        // 使用统一基础提示词
        return ChatModeManager.getBaseSystemPrompt();
    }

    private static String getDefaultNormalPrompt() {
        return "";
    }

    private static final int MAX_CONTEXT_INIT_RETRIES = 3;
    private static final long CONTEXT_INIT_RETRY_DELAY_MS = 200;

    // ========== 超时配置 ==========
    private static final long TOOL_EXECUTION_TIMEOUT_MS = 30000; // 工具执行超时：30秒
    private static final int TOOL_EXECUTION_MAX_RETRIES = 2;    // 工具执行最大重试次数
    private static final int EXECUTOR_THREAD_COUNT = 2;         // 线程池大小
    private static final long SESSION_EXPIRY_MS = 30 * 60 * 1000; // Session 过期时间：30分钟

    private final AtomicBoolean contextInitialized = new AtomicBoolean(false);

    public enum ReasoningMode {
        AUTO("自动选择"),
        REACT("ReAct推理"),
        CHAIN_OF_THOUGHT("链式思维"),
        PLAN_EXECUTE("计划执行"),
        DIRECT("直接生成");

        public final String displayName;
        ReasoningMode(String displayName) { this.displayName = displayName; }
    }

    // ========== Agent 状态管理 ==========
    
    /**
     * Agent 执行状态枚举
     */
    public enum ExecutionState {
        RUNNING,      // 运行中
        PAUSED,       // 已暂停，等待用户输入
        RESUMING,     // 恢复中
        COMPLETED,    // 已完成
        CANCELLED     // 已取消
    }
    
    // ========== AIAgentEngine 兼容类 ==========

    public enum AgentState {
        IDLE, THINKING, PLANNING, EXECUTING, VALIDATING, RESPONDING, COMPLETED, ERROR
    }

    public interface OnAgentStepListener {
        void onStepStart(String stepType, String description);
        void onStepComplete(String stepType, String result);
        void onStepError(String stepType, String error);
        void onThinking(String thought);
        void onAction(String action);
        void onObservation(String observation);
    }

    public static class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class AgentSession {
        public final String sessionId;
        public final long createdAt;
        public final List<ChatMessage> history;
        public final Map<String, Object> context;

        public AgentSession(String sessionId) {
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
            this.history = new ArrayList<>();
            this.context = new HashMap<>();
        }
    }

    public static class AgentResult {
        public String output;
        public boolean success;
        public long processingTimeMs;
        public String usedStrategy;
        public final List<String> toolCalls;

        public AgentResult(String output, boolean success) {
            this.output = output;
            this.success = success;
            this.toolCalls = new ArrayList<>();
        }

        public AgentResult(String output, boolean success, List<String> toolCalls) {
            this.output = output;
            this.success = success;
            this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
        }

        // 兼容旧 API
        public String getContent() { return output; }
        public void setContent(String content) { this.output = content; }
    }

    /**
     * Agent 回调接口 —— 继承独立接口，保持向后兼容。
     * 外部代码可继续使用 UnifiedAgentEngine.AgentCallback，
     * 也可直接使用 {@link com.oilquiz.app.ai.agent.AgentCallback}。
     */
    public interface AgentCallback extends com.oilquiz.app.ai.agent.AgentCallback {}

    /**
     * 推理进度监听器 —— 继承独立接口，保持向后兼容。
     */
    public interface InferenceProgressListener extends com.oilquiz.app.ai.agent.InferenceProgressListener {}

    private final Activity activity;
    private final AIService aiService;
    private final InferenceRouter inferenceRouter;
    private final AgentService agentService;
    private final SmartIntentRecognizer intentRecognizer;
    private final ExecutorService executor;
    private final boolean useOnlineModel;
    private final Object lock = new Object();

    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicInteger toolLoopCount = new AtomicInteger(0);
    private final AtomicInteger iterationCount = new AtomicInteger(0);
    private final AtomicReference<StringBuilder> currentResponse = new AtomicReference<>(new StringBuilder());
    private final AtomicReference<StringBuilder> currentThinking = new AtomicReference<>(new StringBuilder());
    private final AtomicBoolean isInThinking = new AtomicBoolean(false);
    
    // ========== 暂停/恢复状态管理 ==========
    private final AtomicReference<ExecutionState> executionState = new AtomicReference<>(ExecutionState.RUNNING);
    private final AtomicReference<String> pauseReason = new AtomicReference<>(null);
    private final AtomicReference<String> currentTask = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> suspendedParams = new AtomicReference<>(null);
    private final AtomicReference<String> suspendedToolName = new AtomicReference<>(null);
    private final ResettableCountDownLatch pauseLatch = new ResettableCountDownLatch(1);
    private final AtomicInteger validationRetryCount = new AtomicInteger(0);
    
    // ========== 智能思考状态管理 ==========
    private final AtomicBoolean isThinking = new AtomicBoolean(false);
    private final AtomicReference<StringBuilder> currentAnalysisThinking = new AtomicReference<>(new StringBuilder());

    // ========== Token 统计 ==========
    private final TokenStatsManager tokenStatsManager;
    private final AtomicInteger currentRequestTokens = new AtomicInteger(0);
    private long currentRequestStartTime = 0;

    private final List<String> contextSummary = Collections.synchronizedList(new ArrayList<>());
    private final List<com.oilquiz.app.ai.chat.ChatMessage> onlineModelConversationHistory = Collections.synchronizedList(new ArrayList<>());

    private ReasoningMode currentMode = ReasoningMode.AUTO;
    private int maxToolLoops = 8;
    private com.oilquiz.app.ai.agent.AgentCallback callback;
    private com.oilquiz.app.ai.agent.InferenceProgressListener inferenceProgressListener;
    private ServiceRouter serviceRouter;  // AIAgentEngine 兼容

    // ========== 工具索引缓存 ==========
    /** 工具名 -> ToolSchema 的 HashMap 索引，O(1) 查找 */
    private Map<String, AgentService.ToolSchema> toolIndexCache;
    /** 预构建的工具列表简版字符串 */
    private String toolListBriefCache;
    /** 预构建的工具列表完整字符串（含参数） */
    private String toolListPromptCache;
    /** 预构建的工具名速查字符串 */
    private String toolNamesOnlyCache;
    /** 预构建的关键词-工具映射 */
    private Map<String, String> keywordToToolCache;
    /** 缓存脏标记：工具列表变化时置 true，下次访问时重建 */
    private boolean isToolCacheDirty = true;

    private static final int TOOL_RESULT_MAX_LENGTH = 2000;
    private static final int MAX_PROMPT_LENGTH = 16000;          // Prompt 最大长度
    private static final int MAX_CONTEXT_ENTRIES = 30;            // 上下文条目最大数量
    private static final int CONTEXT_TRUNCATE_THRESHOLD = 12000;  // 触发警告的阈值

    // Python 自动触发关键词
    private static final String[] PYTHON_TRIGGERS = {
        "计算", "统计", "分析", "生成数据",
        "找出", "排序", "递归", "斐波那契",
        "阶乘", "平均", "求和", "最大值", "最小值",
        "数学", "公式", "算法", "随机数", "阶乘",
        "质数", "素数", "因数", "最大公约数", "最小公倍数"
    };

    /**
     * 本地模型构造函数（兼容旧API）
     */
    public UnifiedAgentEngine(Activity activity, AIService aiService, AgentService agentService) {
        this(activity, aiService, null, agentService, false);
    }
    
    /**
     * 支持在线模型的构造函数
     * @param activity Activity
     * @param aiService 本地模型服务（可为null，如果使用在线模型）
     * @param inferenceRouter 推理路由（支持本地和在线模型）
     * @param agentService Agent服务
     * @param useOnlineModel 是否使用在线模型
     */
    public UnifiedAgentEngine(Activity activity, AIService aiService, InferenceRouter inferenceRouter, AgentService agentService, boolean useOnlineModel) {
        if (!useOnlineModel && aiService == null) {
            throw new IllegalArgumentException("AIService cannot be null when using local model");
        }
        if (agentService == null) {
            throw new IllegalArgumentException("AgentService cannot be null");
        }
        if (activity == null) {
            throw new IllegalArgumentException("Activity cannot be null");
        }
        this.activity = activity;
        this.aiService = aiService;
        this.inferenceRouter = inferenceRouter;
        this.agentService = agentService;
        this.useOnlineModel = useOnlineModel;
        this.intentRecognizer = SmartIntentRecognizer.getInstance(activity);
        this.intentRecognizer.setAgentService(agentService);
        // 本地 Agent 模式下禁用 LLM 意图识别：
        // recognizeByLLM 调用 LlamaHelper.generate（nativeGenerate）会破坏 chat context 的 KV cache，
        // 导致后续 chatSend（nativeChatSend）解码时 SIGSEGV 崩溃。
        // ReAct/CoT 循环本身已具备完整推理能力，意图识别改用规则匹配即可。
        if (!useOnlineModel) {
            this.intentRecognizer.setLLMRecognitionEnabled(false);
        }
        this.executor = Executors.newFixedThreadPool(EXECUTOR_THREAD_COUNT, r -> {
            Thread t = new Thread(r, "UnifiedAgent-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.serviceRouter = new ServiceRouter(this);
        this.tokenStatsManager = TokenStatsManager.getInstance();
        createSession();
    }
    
    public void destroy() {
        executor.shutdownNow();
        clearContext();
        sessions.clear();
        callback = null;
        AILogger.i(TAG, "UnifiedAgentEngine destroyed");
    }
    

    public void setCallback(com.oilquiz.app.ai.agent.AgentCallback callback) {
        this.callback = callback;
    }

    public void setInferenceProgressListener(com.oilquiz.app.ai.agent.InferenceProgressListener listener) {
        this.inferenceProgressListener = listener;
    }

    public void setReasoningMode(ReasoningMode mode) {
        this.currentMode = mode;
    }

    public ReasoningMode getCurrentMode() {
        return currentMode;
    }

    public void setMaxToolLoops(int max) {
        this.maxToolLoops = Math.max(1, Math.min(10, max));
    }

    public Activity getActivity() {
        return activity;
    }

    public AIService getAIService() {
        return aiService;
    }

    public AgentService getAgentService() {
        return agentService;
    }

    public SmartIntentRecognizer.IntentResult analyzeIntent(String message) {
        return intentRecognizer.recognize(message);
    }

    public SmartIntentRecognizer.IntentResult analyzeIntentWithContext(String message) {
        return intentRecognizer.recognizeWithContext(message, getContextSummary());
    }

    /**
     * 选择最佳推理模式
     *
     * 核心逻辑：
     * 1. 需要工具 → 任务循环模式（REACT/PLAN_EXECUTE）
     * 2. 不需要工具 → 直接回答模式（DIRECT）
     *
     * @param intent 意图识别结果
     * @param multiIntent 多意图识别结果
     * @return 选择的推理模式
     */
    public ReasoningMode selectBestMode(SmartIntentRecognizer.IntentResult intent,
                                        SmartIntentRecognizer.MultiIntentResult multiIntent) {
        // 用户手动选择了模式，直接使用
        if (currentMode != ReasoningMode.AUTO) return currentMode;

        // 意图为空，直接回答
        if (intent == null || intent.intent == null) {
            AILogger.i(TAG, "Intent is null, using DIRECT mode");
            return ReasoningMode.DIRECT;
        }

        // ========== 核心判断：是否需要工具 ==========

        // 检查多意图中的工具需求
        if (multiIntent != null && multiIntent.hasMultipleIntents()) {
            int toolIntentCount = 0;
            for (SmartIntentRecognizer.IntentItem item : multiIntent.intents) {
                if (item.intent.needsTool && item.confidence >= 0.3) {
                    toolIntentCount++;
                }
            }

            AILogger.i(TAG, "Multi-intents: total=" + multiIntent.intents.size()
                + ", toolIntents=" + toolIntentCount);

            // 多个工具意图 → 计划执行模式
            if (toolIntentCount >= 2) {
                AILogger.i(TAG, "Multiple tool intents (" + toolIntentCount + "), using PLAN_EXECUTE");
                return ReasoningMode.PLAN_EXECUTE;
            }
            // 单个工具意图 → ReAct模式
            if (toolIntentCount == 1) {
                AILogger.i(TAG, "Single tool intent, using REACT");
                return ReasoningMode.REACT;
            }
        }

        // 检查单个意图的工具需求
        if (intent.intent.needsTool) {
            // 置信度足够高，需要工具
            if (intent.confidence >= 0.4) {
                AILogger.i(TAG, "Intent needs tool: " + intent.intent.displayName
                    + " (confidence=" + intent.confidence + "), using REACT");
                return ReasoningMode.REACT;
            }
            // 置信度低，可能需要工具但不确定，尝试直接回答
            AILogger.i(TAG, "Intent needs tool but low confidence: " + intent.intent.displayName
                + " (confidence=" + intent.confidence + "), trying DIRECT");
            return ReasoningMode.DIRECT;
        }

        // ========== 不需要工具，直接回答 ==========

        AILogger.i(TAG, "Intent doesn't need tool: " + intent.intent.displayName
            + ", using DIRECT mode");
        return ReasoningMode.DIRECT;
    }

    public ReasoningMode selectBestModeForMessage(String message) {
        SmartIntentRecognizer.IntentResult intent = intentRecognizer.recognize(message);
        SmartIntentRecognizer.MultiIntentResult multiIntent = intentRecognizer.recognizeMultiIntent(message);
        return selectBestMode(intent, multiIntent);
    }

    public boolean needsThinkingForIntent(SmartIntentRecognizer.IntentResult intent) {
        if (intent == null || intent.intent == null) return false;
        switch (intent.intent) {
            case ANALYSIS:
            case LEARNING:
                return intent.confidence >= 0.5;
            case CREATIVE:
                return intent.confidence >= 0.4;
            default:
                return false;
        }
    }

    public boolean needsThinkingForMessage(String message) {
        return needsThinkingForIntent(intentRecognizer.recognize(message));
    }

    public boolean shouldUseAgentForMessage(String message) {
        SmartIntentRecognizer.IntentResult intent = intentRecognizer.recognize(message);
        if (intent == null) return false;
        
        if (intent.confidence >= 0.3 && intent.intent.needsTool) {
            return true;
        }
        
        SmartIntentRecognizer.MultiIntentResult multiIntent = intentRecognizer.recognizeMultiIntent(message);
        return multiIntent != null && multiIntent.hasMultipleIntents() && multiIntent.needsAgent;
    }

    public void execute(String message, int maxTokens) {
        execute(message, maxTokens, true);
    }

    public void execute(String message, int maxTokens, boolean enableThinking) {
        if (message == null || message.trim().isEmpty()) {
            notifyError("消息不能为空");
            return;
        }

        if (!useOnlineModel && aiService == null) {
            notifyError("AI服务未初始化");
            return;
        }

        if (useOnlineModel && inferenceRouter == null) {
            notifyError("在线推理服务未初始化");
            return;
        }

        if (agentService == null) {
            notifyError("Agent服务未初始化");
            return;
        }

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
        contextSummary.clear();

        // 初始化Token统计
        currentRequestTokens.set(0);
        currentRequestStartTime = System.currentTimeMillis();
        if (tokenStatsManager != null) {
            tokenStatsManager.updateRequestStreamingStats(0);
        }

        contextSummary.add("用户: " + truncateForContext(message));

        executor.submit(() -> {
            try {
                if (isCancelled.get()) {
                    finishGeneration();
                    return;
                }

                // ========== Python 自动执行 ==========
                // 本地 Agent 模式下跳过：autoExecutePython -> generatePythonCodeForTask -> generateSync
                // 调用 nativeGenerateStream 会破坏 chat context 的 KV cache，
                // 导致后续 chatSend 解码时 native 层 SIGSEGV 崩溃。
                // Python 工具改由 ReAct/CoT 循环通过工具调用流程触发。
                if (!useOnlineModel && shouldUsePython(message)) {
                    AILogger.i(TAG, "Skipping auto Python execution in local Agent mode to protect chat context: " + message);
                } else if (useOnlineModel && shouldUsePython(message)) {
                    AILogger.i(TAG, "Auto-detected Python task: " + message);
                    String pythonResult = autoExecutePython(message);
                    if (pythonResult != null) {
                        injectPythonResult(pythonResult);
                    }
                }
                // =====================================

                // 离线Agent模式：直接走ReAct循环，模型通过TOOLS_CALL格式自主选择工具
                // 已移除意图识别（SmartIntentRecognizer），改用快捷输入引导用户选择工具
                notifyStep("Agent启动", "ReAct推理模式");
                AILogger.i(TAG, "Execute via ReAct loop (intent recognition removed): " + message);
                executeReActLoop(message, maxTokens, enableThinking);
            } catch (Throwable t) {
                AILogger.e(TAG, "Error in execute: " + t.getMessage(), t);
                finishGeneration();
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                notifyError("执行中断: " + errorMsg);
            }
        });
    }

    private String buildIntentHint(SmartIntentRecognizer.IntentResult intent,
                                   SmartIntentRecognizer.MultiIntentResult multiIntent,
                                   String originalMessage) {
        StringBuilder hint = new StringBuilder();

        if (intent == null) return "";

        if (intent.intent == SmartIntentRecognizer.Intent.CHAT) {
            return "";
        }

        hint.append("\n\n=== 任务分析 ===\n");

        hint.append("识别到的主要任务类型: ").append(intent.intent.displayName).append("\n");
        hint.append("置信度: ").append(String.format("%.0f%%", intent.confidence * 100)).append("\n");

        if (intent.extractedEntity != null && !intent.extractedEntity.isEmpty()) {
            hint.append("提取的关键信息: ").append(intent.extractedEntity).append("\n");
        }

        if (multiIntent != null && multiIntent.hasMultipleIntents()) {
            hint.append("\n检测到多个子任务:\n");
            for (int i = 0; i < multiIntent.intents.size(); i++) {
                SmartIntentRecognizer.IntentItem item = multiIntent.intents.get(i);
                hint.append("  ").append(i + 1).append(". ").append(item.intent.displayName);
                if (item.confidence > 0) {
                    hint.append(" (").append(String.format("%.0f%%", item.confidence * 100)).append(")");
                }
                if (item.entity != null) {
                    hint.append(": ").append(item.entity);
                }
                hint.append("\n");
            }
            hint.append("\n建议: 请按顺序处理这些子任务，可以组合使用多个工具。\n");
        }

        if (intent.intent.needsTool) {
            String recommendedTool = intentRecognizer.getRecommendedTool(intent.intent);
            if (recommendedTool != null) {
                hint.append("\n推荐工具: ").append(recommendedTool).append("\n");

                hint.append("\n=== 工具使用建议 ===\n");
                switch (intent.intent) {
                    case CALCULATOR:
                        hint.append("这是一个数学计算任务。\n");
                        hint.append("1. 如果需要精确计算，请调用 calculator 工具\n");
                        hint.append("2. 表达式格式: 3+5*2, sqrt(9), 10%2 等\n");
                        if (intent.extractedEntity != null) {
                            hint.append("3. 提取的表达式: ").append(intent.extractedEntity).append("\n");
                        }
                        break;
                    case WEATHER:
                        hint.append("这是一个天气查询任务。\n");
                        hint.append("1. 请调用 weather 工具查询天气\n");
                        if (intent.extractedEntity != null) {
                            hint.append("2. 查询城市: ").append(intent.extractedEntity).append("\n");
                        } else {
                            hint.append("2. 注意: 未提取到城市名，可能需要询问用户\n");
                        }
                        break;
                    case SEARCH:
                        hint.append("这是一个信息搜索任务。\n");
                        hint.append("1. 请调用 network_search 工具搜索相关信息\n");
                        if (intent.extractedEntity != null) {
                            hint.append("2. 搜索关键词: ").append(intent.extractedEntity).append("\n");
                        }
                        break;
                    case QUIZ:
                        hint.append("这是一个题目相关任务。\n");
                        hint.append("1. 如果用户需要找题，请调用 search_questions 工具\n");
                        if (intent.extractedEntity != null) {
                            hint.append("2. 科目/类型: ").append(intent.extractedEntity).append("\n");
                        }
                        break;
                    case FILE:
                        hint.append("这是一个文件处理任务。\n");
                        hint.append("1. 请调用 file_reader 或 file_analyzer 工具\n");
                        break;
                    case WEB:
                        hint.append("这是一个网页浏览任务。\n");
                        hint.append("1. 请调用 read_webpage 工具获取网页内容\n");
                        if (intent.extractedEntity != null) {
                            hint.append("2. 网址: ").append(intent.extractedEntity).append("\n");
                        }
                        break;
                    case OCR:
                        hint.append("这是一个图片识别任务。\n");
                        hint.append("1. 请调用 app_toolkit 工具进行图片识别\n");
                        break;
                    case DATABASE:
                        hint.append("这是一个数据库查询任务。\n");
                        hint.append("1. 请调用 database 工具查询相关数据\n");
                        break;
                    default:
                        break;
                }
            }
        } else {
            hint.append("\n=== 建议 ===\n");
            switch (intent.intent) {
                case CREATIVE:
                    hint.append("这是一个创意写作任务。\n");
                    hint.append("请充分发挥创造力，提供高质量的内容。\n");
                    break;
                case ANALYSIS:
                    hint.append("这是一个深度分析任务。\n");
                    hint.append("请多角度分析，提供全面的见解。\n");
                    break;
                case LEARNING:
                    hint.append("这是一个学习辅助任务。\n");
                    hint.append("请用通俗易懂的方式解释概念。\n");
                    break;
                case TRANSLATE:
                    hint.append("这是一个翻译任务。\n");
                    if (intent.extractedEntity != null) {
                        hint.append("目标语言: ").append(intent.extractedEntity).append("\n");
                    }
                    hint.append("请提供准确、自然的翻译。\n");
                    break;
                default:
                    break;
            }
        }

        hint.append("\n=== 注意 ===\n");
        hint.append("你可以根据实际情况选择是否使用工具。\n");
        hint.append("如果工具不可用或返回错误，请直接回答或尝试其他方法。\n");

        return hint.toString();
    }

    public String getContextSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("对话共").append(contextSummary.size()).append("条记录。");
        synchronized (contextSummary) {
            int userCount = 0;
            int toolCount = 0;
            for (String entry : contextSummary) {
                if (entry.startsWith("用户:")) userCount++;
                if (entry.startsWith("Observation:") || entry.startsWith("工具结果:")) toolCount++;
            }
            summary.append("用户提问").append(userCount).append("次，工具调用").append(toolCount).append("次。");
        }
        return summary.toString();
    }

    private String buildToolResultsSummaryPrompt() {
        StringBuilder summary = new StringBuilder();
        summary.append("[工具调用已达到最大次数]\\n\\n");
        summary.append("以下是之前工具调用的结果摘要：\\n\\n");
        synchronized (contextSummary) {
            for (String entry : contextSummary) {
                if (entry.startsWith("工具结果:") || entry.startsWith("Observation:")) {
                    summary.append(entry).append("\\n");
                }
            }
        }
        summary.append("\\n请基于以上结果总结并回答用户的问题。");
        return summary.toString();
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
                contextSummary.add("助手: " + truncateForContext(responseText));
                notifyComplete(responseText);
            }
        });
    }

    private String buildReActPrompt(String userMessage) {
        trimContextIfNeeded();

        boolean isFirstRound = (toolLoopCount.get() == 0);
        StringBuilder sb = new StringBuilder();

        if (isFirstRound) {
            // 首轮：格式说明 + 精简工具列表（只有名+描述，不含参数详情）
            sb.append("[ReAct模式] 每次只调一个工具，等结果后决定下一步。不需工具时直接回答。\n\n");
            sb.append("工具调用格式（TOOLS_CALL和TOOLS_END独占一行，中间是有效JSON）：\n");
            sb.append("TOOLS_CALL\n");
            sb.append("{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
            sb.append("TOOLS_END\n");
            sb.append("示例：TOOLS_CALL\n{\"name\": \"ai_weather\", \"arguments\": {\"city\": \"北京\"}}\nTOOLS_END\n\n");
            sb.append("如需了解某工具的详细参数，输出 [TOOL_INFO: 工具名]，系统将返回该工具的完整说明。\n");
            sb.append("如需查看完整工具调用指南（含示例和规则），输出 [TOOL_GUIDE]。\n");
            sb.append("工具可以组合使用，如查天气可先用 location 定位再用 ai_weather 查询。\n\n");
            sb.append("=== 可用工具 ===\n");
            sb.append(buildToolListBrief());
            sb.append("\n=== 用户问题 ===\n");
            sb.append(userMessage);
        } else {
            // 后续轮：工具结果/指令 + 工具名速查（极小），不重复完整列表和格式说明
            sb.append(userMessage);
            sb.append("\n\n[工具速查] ").append(buildToolNamesOnly());
            sb.append("\n如需调用工具，使用 TOOLS_CALL/TOOLS_END 格式。");
        }
        return sb.toString();
    }

    private void handleReActToolCall(String responseText, int maxTokens) {
        // 0a. 检测完整工具指南请求 [TOOL_GUIDE]
        if (responseText != null && responseText.contains("[TOOL_GUIDE]")) {
            AILogger.i(TAG, "AI requested full tool guide");
            String guidePrompt = "[工具调用指南]\n" + AIToolUsageGuide.getAgentToolUsageMethod(activity)
                + "\n请根据以上指南，继续完成用户任务。";
            resetBuffers();
            executeReActLoop(guidePrompt, maxTokens, false);
            return;
        }

        // 0b. 检测主动工具详情请求 [TOOL_INFO: 工具名]
        String toolInfoRequest = extractToolInfoRequest(responseText);
        if (toolInfoRequest != null) {
            AILogger.i(TAG, "AI requested tool info: " + toolInfoRequest);
            String infoPrompt = buildToolInfoResponsePrompt(toolInfoRequest);
            resetBuffers();
            executeReActLoop(infoPrompt, maxTokens, false);
            return;
        }

        List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
        if (toolCalls.isEmpty()) {
            if (likelyWantsToCallTool(responseText)) {
                AILogger.w(TAG, "AI可能想调用工具但格式不正确，提示并重试");
                notifyStep("格式修正", "检测到可能的工具调用意图，但格式不正确，正在提示AI修正...");
                
                String correctionPrompt = buildFormatCorrectionPrompt(responseText);
                resetBuffers();
                executeReActLoop(correctionPrompt, maxTokens, false);
                return;
            }
            
            contextSummary.add("助手: " + truncateForContext(responseText));
            notifyComplete(responseText);
            return;
        }

        AgentService.ToolCall call = toolCalls.get(0);
        toolLoopCount.incrementAndGet();
        iterationCount.incrementAndGet();

        AILogger.i(TAG, "ReAct tool call #" + toolLoopCount.get() + ": " + call.name);
        notifyToolCallStart(call.name, call.arguments);
        notifyStep("ReAct步骤" + iterationCount.get(), "调用工具: " + call.name);

        executor.execute(() -> {
            if (isCancelled.get()) { finishGeneration(); cleanupAfterCompletion(); return; }

            Map<String, Object> params = validateAndPrepareToolCall(call);
            if (params == null) {
                AILogger.w(TAG, "用户取消了工具调用: " + call.name);
                activity.runOnUiThread(() -> {
                    if (callback != null && isValid()) {
                        callback.onToolCallComplete(call.name, OnlineToolResult.failure(null, call.name, "用户取消", 0));
                    }
                });
                if (isCancelled.get()) { finishGeneration(); cleanupAfterCompletion(); return; }
                contextSummary.add("工具结果: 用户取消");
                String nextPrompt = "[继续] 用户取消了工具调用，请尝试其他方式完成。";
                resetBuffers();
                executeDirect(nextPrompt, maxTokens, false);
                return;
            }

            AgentService.ToolCall validatedCall = new AgentService.ToolCall(
                call.id, call.name, params, call.arguments
            );

            AgentService.ToolResult result = executeToolWithTimeout(validatedCall);
            if (result == null) {
                AILogger.e(TAG, "executeTool returned null for: " + call.name);
                activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onToolCallComplete(call.name, OnlineToolResult.failure(null, call.name, "Tool execution failed", 0)); });
                contextSummary.add("工具结果: 执行失败");
                String nextPrompt = buildFallbackSuggestionPrompt(call.name, "工具执行返回空结果", call.arguments);
                resetBuffers();
                executeReActLoop(nextPrompt, maxTokens, false);
                return;
            }
            activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onToolCallComplete(call.name, OnlineToolResult.fromAgentServiceResult(call.name, result)); });
            boolean success = result.success;
            String resultStr = result.result;
            String toolResultStr = success ? resultStr : "工具执行失败: " + resultStr;

            // 使用智能摘要处理长结果
            String summarizedResult = agentService.summarizeToolResult(call.name, toolResultStr);
            contextSummary.add("工具结果[" + call.name + "]: " + truncateForContext(summarizedResult, TOOL_RESULT_MAX_LENGTH));

            // 分析工具结果是否满足用户需求
            String userMessage = contextSummary.size() > 0 ? contextSummary.get(0) : "";
            ToolResultAnalysis analysis = analyzeToolResult(userMessage, call.name, resultStr, contextSummary.toString(), success);
            AILogger.i(TAG, "Tool result analysis: sufficient=" + analysis.sufficient + ", reason=" + analysis.reason);

            if (!analysis.sufficient && toolLoopCount.get() < maxToolLoops) {
                // 结果不满足，提示AI调整参数重试，并注入替代工具建议
                String retryPrompt = "[工具结果不满足] 工具 " + call.name + " 返回的结果不完整。\n"
                    + "原因: " + analysis.reason + "\n"
                    + "建议: " + analysis.suggestion + "\n\n"
                    + buildFallbackSuggestionPrompt(call.name, analysis.reason, call.arguments);
                resetBuffers();
                executeReActLoop(retryPrompt, maxTokens, false);
                return;
            }

            if (toolLoopCount.get() >= maxToolLoops) {
                // 达到最大循环次数，强制总结
                String summary = buildToolResultsSummaryPrompt();
                resetBuffers();
                executeReActLoop(summary, maxTokens, false);
                return;
            }

            // 结果满足，继续下一步
            String nextPrompt = "<tool_result>\n工具: " + call.name + "\n状态: " + (success ? "成功" : "失败") + "\n结果: " + (resultStr != null ? resultStr : "无结果") + "\n</tool_result>\n\n请基于以上工具返回的结果回答用户的问题。如果已有足够信息，请直接给出最终答案。";
            resetBuffers();
            executeReActLoop(nextPrompt, maxTokens, false);
        });
    }

    private boolean likelyWantsToCallTool(String responseText) {
        if (responseText == null || responseText.isEmpty()) return false;
        
        String lower = responseText.toLowerCase();
        
        String[] toolKeywords = {
            "weather", "计算器", "calculator", "计算", "天气", "查询",
            "search", "搜索", "文件", "file", "读取", "read",
            "工具", "tool", "调用", "需要", "我需要", "让我", "先"
        };
        
        for (String keyword : toolKeywords) {
            if (lower.contains(keyword.toLowerCase())) {
                if (!looksLikeFinalAnswer(responseText)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    private boolean looksLikeFinalAnswer(String responseText) {
        if (responseText == null || responseText.isEmpty()) return false;
        
        String lower = responseText.toLowerCase();
        
        String[] answerKeywords = {
            "答案", "结果", "因此", "所以", "综上", "总结",
            "根据", "这是", "以下是", "为您", "完成了",
            "北京天气", "上海天气", "天气是", "温度", "等于", "="
        };
        
        for (String keyword : answerKeywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        
        if (responseText.length() > 100) {
            return true;
        }
        
        return false;
    }

    private String buildFormatCorrectionPrompt(String incorrectResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append("[格式错误提示]\n\n");
        sb.append("你的回复格式不正确，系统无法识别你要调用哪个工具。\n\n");
        sb.append("=== 你的回复 ===\n");
        sb.append(incorrectResponse.substring(0, Math.min(200, incorrectResponse.length())));
        if (incorrectResponse.length() > 200) {
            sb.append("...\n");
        }
        sb.append("\n\n");

        sb.append("=== 正确格式 ===\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
        sb.append("TOOLS_END\n\n");

        sb.append("=== 重要提醒 ===\n");
        sb.append("1. TOOLS_CALL 和 TOOLS_END 必须独占一行\n");
        sb.append("2. 中间必须是有效的JSON格式\n");
        sb.append("3. JSON必须包含 name 和 arguments 两个字段\n");
        sb.append("4. arguments 必须是一个JSON对象\n\n");

        // 自动检测模型想调用的工具，注入该工具的详细参数定义
        String detectedTool = extractToolNameFromResponse(incorrectResponse);
        if (detectedTool != null) {
            String detail = buildToolDetailPrompt(detectedTool);
            if (!detail.isEmpty()) {
                sb.append("=== 你要调用的工具详情 ===\n");
                sb.append(detail).append("\n");
            }
        }
        // 其他工具只给名字列表（极小体积）
        sb.append("=== 其他可用工具 ===\n");
        sb.append(buildToolNamesOnly());
        sb.append("\n\n请重新使用正确的格式调用工具，或者直接回答用户问题。");

        return sb.toString();
    }

    private void executeDirect(String message, int maxTokens, boolean enableThinking) {
        sendAndProcess(message, maxTokens, enableThinking, new ResponseHandler() {
            @Override public void onThinkingToken(String token) { notifyThinkingToken(token); }
            @Override
            public void onToolCallDetected(String responseText) {
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
                if (toolCalls.isEmpty()) {
                    contextSummary.add("助手: " + truncateForContext(responseText));
                    notifyComplete(responseText);
                    return;
                }
                AgentService.ToolCall call = toolCalls.get(0);
                toolLoopCount.incrementAndGet();
                notifyToolCallStart(call.name, call.arguments);
                notifyStep("调用工具", call.name);

                executor.execute(() -> {
                    if (isCancelled.get()) { finishGeneration(); return; }

                    // ========== 参数检查 ==========
                    Map<String, Object> params = validateAndPrepareToolCall(call);
                    if (params == null) {
                        // 用户取消，记录结果并继续
                        AILogger.w(TAG, "用户取消了工具调用: " + call.name);
                        activity.runOnUiThread(() -> {
                            if (callback != null) {
                                callback.onToolCallComplete(call.name, OnlineToolResult.failure(null, call.name, "用户取消", 0));
                            }
                        });
                        if (isCancelled.get()) { finishGeneration(); return; }
                        contextSummary.add("工具结果: 用户取消");
                        String nextPrompt = "[继续] 用户取消了工具调用，请尝试其他方式完成。";
                        resetBuffers();
                        executeDirect(nextPrompt, maxTokens, false);
                        return;
                    }

                    // 使用验证通过的参数创建新的 ToolCall
                    AgentService.ToolCall validatedCall = new AgentService.ToolCall(
                        call.id, call.name, params, call.arguments
                    );

                    AgentService.ToolResult result = executeToolWithTimeout(validatedCall);
                    // =================================
                    if (result == null) {
                        AILogger.e(TAG, "executeTool returned null for: " + call.name);
                        activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, OnlineToolResult.failure(null, call.name, "Tool execution failed", 0)); });
                        if (isCancelled.get()) { finishGeneration(); return; }
                        contextSummary.add("工具结果: 执行失败");
                        String nextPrompt = "[继续] 工具调用失败，请尝试其他方式完成。";
                        resetBuffers();
                        executeDirect(nextPrompt, maxTokens, false);
                        return;
                    }
                    activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onToolCallComplete(call.name, OnlineToolResult.fromAgentServiceResult(call.name, result)); });
                    if (isCancelled.get()) { finishGeneration(); return; }
                    boolean success = result.success;
                    String resultStr = result.result;

                    // 使用智能摘要处理长结果
                    String summarizedResult = agentService.summarizeToolResult(call.name, resultStr != null ? resultStr : "无结果");
                    contextSummary.add("工具结果[" + call.name + "]: " + truncateForContext(summarizedResult, TOOL_RESULT_MAX_LENGTH));

                    if (toolLoopCount.get() >= maxToolLoops) {
                        String summary = buildToolResultsSummaryPrompt();
                        resetBuffers();
                        executeDirect(summary, maxTokens, false);
                        return;
                    }
                    String nextPrompt = "<tool_result>\n工具: " + call.name + "\n状态: " + (success ? "成功" : "失败") + "\n结果: " + (resultStr != null ? resultStr : "无结果") + "\n</tool_result>\n\n请基于以上工具返回的结果回答用户的问题。";
                    resetBuffers();
                    executeDirect(nextPrompt, maxTokens, false);
                });
            }
            @Override
            public void onFinalResponse(String responseText) {
                contextSummary.add("助手: " + truncateForContext(responseText));
                notifyComplete(responseText);
            }
        });
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
                contextSummary.add("助手: " + truncateForContext(responseText));
                notifyComplete(responseText);
            }
        });
    }

    private String buildCoTPrompt(String userMessage) {
        trimContextIfNeeded();

        boolean isFirstRound = (toolLoopCount.get() == 0);
        StringBuilder sb = new StringBuilder();

        if (isFirstRound) {
            sb.append("[链式思维模式]\n\n");
            sb.append("=== 思考步骤 ===\n");
            sb.append("1. 分析问题的核心和关键点\n");
            sb.append("2. 识别需要的信息和知识\n");
            sb.append("3. 逐步推理，展示思考过程\n");
            sb.append("4. 基于推理结果，决定是否需要使用工具\n\n");
            sb.append("=== 工具调用 ===\n");
            sb.append("如需工具，按以下格式调用（TOOLS_CALL和TOOLS_END必须独占一行）：\n\n");
            sb.append("TOOLS_CALL\n");
            sb.append("{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
            sb.append("TOOLS_END\n\n");
            sb.append("如需了解某工具的详细参数，输出 [TOOL_INFO: 工具名]，系统将返回该工具的完整说明。\n");
            sb.append("如需查看完整工具调用指南（含示例和规则），输出 [TOOL_GUIDE]。\n");
            sb.append("工具可以组合使用，如查天气可先用 location 定位再用 ai_weather 查询。\n\n");
            sb.append("=== 可用工具 ===\n");
            sb.append(buildToolListBrief());
            sb.append("\n=== 用户问题 ===\n");
            sb.append(userMessage);
            sb.append("\n\n请开始链式思维推理：");
        } else {
            // 后续轮：工具结果/指令 + 工具名速查（极小）
            sb.append(userMessage);
            sb.append("\n\n[工具速查] ").append(buildToolNamesOnly());
            sb.append("\n如需调用工具，使用 TOOLS_CALL/TOOLS_END 格式。");
        }
        return sb.toString();
    }

    private void handleCoTToolCall(String responseText, int maxTokens) {
        // 0a. 检测完整工具指南请求 [TOOL_GUIDE]
        if (responseText != null && responseText.contains("[TOOL_GUIDE]")) {
            AILogger.i(TAG, "CoT AI requested full tool guide");
            String guidePrompt = "[工具调用指南]\n" + AIToolUsageGuide.getAgentToolUsageMethod(activity)
                + "\n请根据以上指南，继续完成用户任务。";
            resetBuffers();
            executeCoTLoop(guidePrompt, maxTokens, false);
            return;
        }

        // 0b. 检测主动工具详情请求 [TOOL_INFO: 工具名]
        String toolInfoRequest = extractToolInfoRequest(responseText);
        if (toolInfoRequest != null) {
            AILogger.i(TAG, "CoT AI requested tool info: " + toolInfoRequest);
            String infoPrompt = buildToolInfoResponsePrompt(toolInfoRequest);
            resetBuffers();
            executeCoTLoop(infoPrompt, maxTokens, false);
            return;
        }

        List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
        if (toolCalls.isEmpty()) {
            contextSummary.add("助手: " + truncateForContext(responseText));
            notifyComplete(responseText);
            return;
        }

        AgentService.ToolCall call = toolCalls.get(0);
        toolLoopCount.incrementAndGet();
        iterationCount.incrementAndGet();

        AILogger.i(TAG, "CoT tool call #" + toolLoopCount.get() + ": " + call.name);
        notifyToolCallStart(call.name, call.arguments);
        notifyStep("推理中调用工具" + iterationCount.get(), "调用工具: " + call.name);

        executor.execute(() -> {
            if (isCancelled.get()) { finishGeneration(); return; }

            // ========== 参数检查 ==========
            Map<String, Object> params = validateAndPrepareToolCall(call);
            if (params == null) {
                AILogger.w(TAG, "用户取消了工具调用: " + call.name);
                activity.runOnUiThread(() -> {
                    if (callback != null) {
                        callback.onToolCallComplete(call.name, OnlineToolResult.failure(null, call.name, "用户取消", 0));
                    }
                });
                contextSummary.add("工具结果: 用户取消");
                String nextPrompt = "[继续] 用户取消了工具调用，请基于已有信息继续推理并给出最终答案。";
                resetBuffers();
                executeCoTLoop(nextPrompt, maxTokens, false);
                return;
            }

            AgentService.ToolCall validatedCall = new AgentService.ToolCall(
                call.id, call.name, params, call.arguments
            );

            AgentService.ToolResult result = agentService.executeTool(validatedCall);
            // =================================

            activity.runOnUiThread(() -> {
                if (callback != null && isValid()) callback.onToolCallComplete(call.name, OnlineToolResult.fromAgentServiceResult(call.name, result));
            });

            if (isCancelled.get()) { finishGeneration(); cleanupAfterCompletion(); return; }

            boolean success = result != null && result.success;
            String resultStr = result != null && result.result != null ? result.result : "工具执行返回null";
            String toolResultStr = success ? resultStr : "工具执行失败: " + resultStr;

            // 使用智能摘要处理长结果
            String summarizedResult = agentService.summarizeToolResult(call.name, toolResultStr);
            contextSummary.add("工具结果[" + call.name + "]: " + truncateForContext(summarizedResult, TOOL_RESULT_MAX_LENGTH));

            // 分析工具结果是否满足用户需求
            String userMessage = contextSummary.size() > 0 ? contextSummary.get(0) : "";
            ToolResultAnalysis analysis = analyzeToolResult(userMessage, call.name, resultStr, contextSummary.toString(), success);
            AILogger.i(TAG, "CoT Tool result analysis: sufficient=" + analysis.sufficient + ", reason=" + analysis.reason);

            if (!analysis.sufficient && toolLoopCount.get() < maxToolLoops) {
                // 结果不满足，提示AI调整参数重试，并注入替代工具建议
                String retryPrompt = "[工具结果不满足] 工具 " + call.name + " 返回的结果不完整。\n"
                    + "原因: " + analysis.reason + "\n"
                    + "建议: " + analysis.suggestion + "\n\n"
                    + buildFallbackSuggestionPrompt(call.name, analysis.reason, call.arguments);
                resetBuffers();
                executeCoTLoop(retryPrompt, maxTokens, false);
                return;
            }

            if (toolLoopCount.get() >= maxToolLoops) {
                String summary = buildToolResultsSummaryPrompt();
                resetBuffers();
                executeCoTLoop(summary, maxTokens, false);
                return;
            }

            String nextPrompt = "<tool_result>\n工具: " + call.name + "\n状态: " + (success ? "成功" : "失败") + "\n结果: " + (resultStr != null ? resultStr : "无结果")
                + "\n</tool_result>\n\n请基于以上工具返回的结果继续推理。如果已有足够信息，请直接给出最终答案。";
            resetBuffers();
            executeCoTLoop(nextPrompt, maxTokens, false);
        });
    }

    private void executePlanLoop(String message, int maxTokens, boolean enableThinking,
                                 SmartIntentRecognizer.IntentResult intent,
                                 SmartIntentRecognizer.MultiIntentResult multiIntent) {
        String prompt = buildPlanPrompt(message, intent, multiIntent);
        sendAndProcess(prompt, maxTokens, enableThinking, new ResponseHandler() {
            @Override
            public void onThinkingToken(String token) {
                notifyThinkingToken(token);
            }

            @Override
            public void onToolCallDetected(String responseText) {
                // 在计划生成阶段不应该有工具调用
                contextSummary.add("助手: " + truncateForContext(responseText));
            }

            @Override
            public void onFinalResponse(String responseText) {
                contextSummary.add("计划: " + truncateForContext(responseText));
                String plan = extractPlan(responseText);
                if (plan != null && !plan.isEmpty()) {
                    resetBuffers();
                    executePlanStep(plan, 0, maxTokens, message, intent, multiIntent);
                } else {
                    // 降级到 ReAct 模式
                    AILogger.w(TAG, "计划提取失败，降级到 ReAct 模式");
                    resetBuffers();
                    executeReActLoop(message, maxTokens, enableThinking);
                }
            }
        });
    }

    private String buildPlanPrompt(String userMessage, SmartIntentRecognizer.IntentResult intent,
                                   SmartIntentRecognizer.MultiIntentResult multiIntent) {
        trimContextIfNeeded();

        StringBuilder sb = new StringBuilder();
        sb.append("[计划执行模式]\n\n");

        if (multiIntent != null && multiIntent.hasMultipleIntents()) {
            sb.append("=== 多任务分解 ===\n");
            for (int i = 0; i < multiIntent.intents.size(); i++) {
                SmartIntentRecognizer.IntentItem item = multiIntent.intents.get(i);
                sb.append((i + 1)).append(". ").append(item.intent.displayName);
                if (item.entity != null) {
                    sb.append(" (").append(item.entity).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("=== 任务分解 ===\n");
        sb.append("1. 分析原始任务的各个组成部分\n");
        sb.append("2. 识别需要完成的子任务\n");
        sb.append("3. 排列子任务的执行顺序\n");
        sb.append("4. 为每个子任务规划执行步骤\n\n");

        sb.append("=== 计划格式 ===\n");
        sb.append("请按以下格式输出计划：\n\n");
        sb.append("步骤1: [子任务描述]\n");
        sb.append("步骤2: [子任务描述]\n");
        sb.append("步骤3: [子任务描述]\n");
        sb.append("...\n\n");

        sb.append("=== 可用工具 ===\n");
        sb.append(buildToolListBrief());
        sb.append("\n=== 工具调用格式 ===\n");
        sb.append("执行步骤时如需调用工具，使用以下格式：\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
        sb.append("TOOLS_END\n");
        sb.append("\n=== 原始任务 ===\n");
        sb.append(userMessage);
        sb.append("\n\n请先制定执行计划：");
        return sb.toString();
    }

    private String extractPlan(String responseText) {
        String[] lines = responseText.split("\n");
        StringBuilder plan = new StringBuilder();
        boolean foundPlan = false;

        // 改进的正则表达式，支持多种格式：
        // 步骤1: Step 1: step1: 1. 第一步: 第1步: 一、 (一) 1)
        String stepPattern = "^(步骤|Step|step|第|第[一二三四五六七八九十]|[一二三四五六七八九十])[：:.、)\\s]*\\d*[：:.、)\\s]*.*";

        for (String line : lines) {
            if (line.trim().matches(stepPattern)) {
                foundPlan = true;
                plan.append(line).append("\n");
            } else if (foundPlan && !line.trim().isEmpty()) {
                plan.append(line).append("\n");
            }
        }

        return foundPlan ? plan.toString() : "";
    }

    private void executePlanStep(String plan, int index, int maxTokens, String originalMessage,
                                SmartIntentRecognizer.IntentResult intent,
                                SmartIntentRecognizer.MultiIntentResult multiIntent) {
        String[] steps = plan.split("\n");
        List<String> validSteps = new ArrayList<>();
        // 改进的步骤识别正则表达式
        String stepPattern = "^(步骤|Step|step|第|第[一二三四五六七八九十]|[一二三四五六七八九十])[：:.、)\\s]*\\d*[：:.、)\\s]*.*";

        for (String step : steps) {
            String trimmed = step.trim();
            if (!trimmed.isEmpty() && trimmed.matches(stepPattern)) {
                validSteps.add(trimmed);
            }
        }

        if (index >= validSteps.size()) {
            String summary = "[计划总结] 原始任务: " + originalMessage + "\n\n执行结果:\n"
                + contextSummary.toString() + "\n请整合结果给出最终答案。";
            resetBuffers();
            executeReActLoop(summary, maxTokens, false);
            return;
        }

        String step = validSteps.get(index);
        notifyStep("步骤 " + (index + 1) + "/" + validSteps.size(), step);

        String stepPrompt = buildPlanStepPrompt(originalMessage, step, index);
        sendAndProcess(stepPrompt, maxTokens, false, new ResponseHandler() {
            @Override
            public void onThinkingToken(String token) {}

            @Override
            public void onToolCallDetected(String responseText) {
                handlePlanStepToolCall(responseText, plan, index, maxTokens, originalMessage, intent, multiIntent);
            }

            @Override
            public void onFinalResponse(String responseText) {
                contextSummary.add("步骤" + (index + 1) + ": " + truncateForContext(responseText));
                resetBuffers();
                executePlanStep(plan, index + 1, maxTokens, originalMessage, intent, multiIntent);
            }
        });
    }

    private String buildPlanStepPrompt(String originalMessage, String step, int index) {
        return "[执行步骤 " + (index + 1) + "]\n" + step
            + "\n\n原始任务: " + originalMessage + "\n请执行此步骤。";
    }

    private void handlePlanStepToolCall(String responseText, String plan, int index,
                                         int maxTokens, String originalMessage,
                                         SmartIntentRecognizer.IntentResult intent,
                                         SmartIntentRecognizer.MultiIntentResult multiIntent) {
        List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
        if (toolCalls.isEmpty()) {
            return;
        }

        AgentService.ToolCall call = toolCalls.get(0);
        toolLoopCount.incrementAndGet();
        notifyToolCallStart(call.name, call.arguments);

        executor.execute(() -> {
            if (isCancelled.get()) { finishGeneration(); return; }

            // ========== 参数检查 ==========
            Map<String, Object> params = validateAndPrepareToolCall(call);
            if (params == null) {
                AILogger.w(TAG, "用户取消了工具调用: " + call.name);
                activity.runOnUiThread(() -> {
                    if (callback != null) {
                        callback.onToolCallComplete(call.name, OnlineToolResult.failure(null, call.name, "用户取消", 0));
                    }
                });
                // 继续下一步
                resetBuffers();
                executePlanStep(plan, index + 1, maxTokens, originalMessage, intent, multiIntent);
                return;
            }

            AgentService.ToolCall validatedCall = new AgentService.ToolCall(
                call.id, call.name, params, call.arguments
            );

            AgentService.ToolResult result = agentService.executeTool(validatedCall);
            // =================================

            activity.runOnUiThread(() -> {
                if (callback != null) callback.onToolCallComplete(call.name, OnlineToolResult.fromAgentServiceResult(call.name, result));
            });

            if (isCancelled.get()) { finishGeneration(); return; }

            boolean success = result != null && result.success;
            String resultStr = result != null && result.result != null ? result.result : "无结果";
            contextSummary.add("步骤" + (index + 1) + "工具结果: " + truncateForContext(resultStr, TOOL_RESULT_MAX_LENGTH));

            if (toolLoopCount.get() >= maxToolLoops) {
                String summary = "[计划总结] " + contextSummary.toString() + "\n请给出最终答案。";
                resetBuffers();
                executeReActLoop(summary, maxTokens, false);
                return;
            }

            resetBuffers();
            executePlanStep(plan, index + 1, maxTokens, originalMessage, intent, multiIntent);
        });
    }

    private interface ResponseHandler {
        void onThinkingToken(String token);
        void onToolCallDetected(String responseText);
        void onFinalResponse(String responseText);
    }

    private void sendAndProcess(String message, int maxTokens, boolean enableThinking, ResponseHandler handler) {
        String processedMessage = truncateAndValidatePrompt(message);
        trimContextIfNeeded();
        logContextStatus("sendAndProcess");

        if (isCancelled.get()) {
            handler.onFinalResponse(currentResponse.get().toString());
            return;
        }

        if (agentService == null) {
            handler.onFinalResponse(currentResponse.get().toString());
            return;
        }

        if (useOnlineModel) {
            sendAndProcessWithOnlineModel(processedMessage, maxTokens, handler);
        } else {
            sendAndProcessWithLocalModel(processedMessage, maxTokens, enableThinking, handler);
        }
    }

    private void sendAndProcessWithOnlineModel(String message, int maxTokens, final ResponseHandler handler) {
        if (inferenceRouter == null) {
            AILogger.e(TAG, "InferenceRouter is null for online model");
            finishGeneration();
            notifyError("在线推理服务未初始化");
            return;
        }

        AIInferenceCore.InferenceConfig config = new AIInferenceCore.InferenceConfig();
        config.maxTokens = maxTokens;
        config.history = new ArrayList<>(onlineModelConversationHistory);

        AILogger.i(TAG, "Online model inference with history: " + config.history.size() + " messages");

        inferenceRouter.generateStream(message, config, new StreamCallback() {
            @Override
            public void onStart() {
                AILogger.i(TAG, "Online model generation started");
            }

            @Override
            public void onToken(final String token) {
                if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
                    return;
                }
                activity.runOnUiThread(() -> {
                    if (token == null) return;
                    if (isCancelled.get()) return;
                    currentResponse.get().append(token);
                    if (callback != null) callback.onToken(token);
                });
            }

            @Override
            public void onComplete(final String fullText) {
                String responseText = currentResponse.get().toString();
                if (responseText == null || responseText.isEmpty()) {
                    responseText = fullText;
                }
                AILogger.i(TAG, "Online model onComplete: len=" + responseText.length());
                
                com.oilquiz.app.ai.chat.ChatMessage userMsg = com.oilquiz.app.ai.chat.ChatMessage.createUserMessage(message);
                onlineModelConversationHistory.add(userMsg);
                
                com.oilquiz.app.ai.chat.ChatMessage aiMsg = com.oilquiz.app.ai.chat.ChatMessage.createAIMessage(responseText);
                onlineModelConversationHistory.add(aiMsg);
                
                AILogger.i(TAG, "History updated, total messages: " + onlineModelConversationHistory.size());
                
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
                if (!toolCalls.isEmpty()) {
                    handler.onToolCallDetected(responseText);
                } else {
                    handler.onFinalResponse(responseText);
                }
            }

            @Override
            public void onError(final String error) {
                // 【核心】返回部分结果而非完全失败
                String partialResponse = currentResponse.get().toString();
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    AILogger.w(TAG, "Online model error, returning partial response: " + error + ", partial_len=" + partialResponse.length());
                    handler.onFinalResponse(partialResponse);
                } else {
                    AILogger.e(TAG, "Online model generation error: " + error);
                    finishGeneration();
                    notifyError("执行中断: " + error);
                }
            }
        });
    }

    private void sendAndProcessWithLocalModel(String message, int maxTokens, boolean enableThinking, final ResponseHandler handler) {
        if (aiService == null) {
            handler.onFinalResponse(currentResponse.get().toString());
            return;
        }

        if (!ensureChatContextReady(handler)) {
            return;
        }

        // Agent 模式：只发送用户原始消息，不注入 Agent 内部数据到聊天上下文
        // Agent 的工具调用结果、思考步骤等保留在 Agent 内部的 contextSummary 中
        aiService.chatSend(message, maxTokens, enableThinking, new LlamaHelper.TokenCallback() {
            @Override
            public void onToken(String token) {
                if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
                    return;
                }
                activity.runOnUiThread(() -> {
                    if (token == null) return;
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
                AILogger.i(TAG, "Local model onComplete: len=" + responseText.length());
                if (agentService == null) {
                    handler.onFinalResponse(responseText);
                    return;
                }
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(responseText);
                if (!toolCalls.isEmpty()) {
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
                
                if (shouldRetryContextError(error)) {
                    AILogger.w(TAG, "Chat context error detected, attempting recovery...");
                    contextInitialized.set(false);

                    try {
                        UnifiedContextManager ctxManager = getContextManager();
                        if (ctxManager != null) {
                            boolean recovered = ctxManager.checkAndRecoverAllIfNeeded(aiService);
                            if (recovered && ensureChatContextReady(handler)) {
                                AILogger.i(TAG, "Context recovered, retrying sendAndProcess");
                                sendAndProcessWithLocalModel(message, maxTokens, enableThinking, handler);
                                return;
                            }
                        }
                    } catch (Exception e) {
                        AILogger.w(TAG, "Context recovery attempt failed: " + e.getMessage());
                    }
                }

                // 【核心】返回部分结果而非完全失败
                String partialResponse = currentResponse.get().toString();
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    AILogger.w(TAG, "Returning partial response due to error: " + error + ", partial_len=" + partialResponse.length());
                    handler.onFinalResponse(partialResponse);
                } else {
                    AILogger.e(TAG, "Local model chatSend error: " + error);
                    finishGeneration();
                    notifyError("执行中断: " + error);
                }
            }
        });
    }
    
    private boolean shouldRetryContextError(String error) {
        if (error == null) return false;
        String lowerError = error.toLowerCase();
        return lowerError.contains("chat context") || 
               lowerError.contains("context not active") ||
               lowerError.contains("not initialized") ||
               lowerError.contains("native") ||
               lowerError.contains("invalid");
    }
    
    private UnifiedContextManager getContextManager() {
        try {
            UnifiedContextManager manager = UnifiedContextManager.getInstance();
            if (manager == null && activity != null) {
                manager = UnifiedContextManager.getInstance(activity);
            }
            return manager;
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to get UnifiedContextManager: " + e.getMessage());
            return null;
        }
    }
    
    private boolean ensureChatContextReady(ResponseHandler handler) {
        if (aiService == null) {
            return false;
        }

        try {
            UnifiedContextManager ctxManager = getContextManager();

            // 如果上下文已经初始化过，直接复用，不重新初始化
            if (ctxManager != null && contextInitialized.get()) {
                if (ctxManager.isChatContextReady() && LlamaHelper.isChatContextActive()) {
                    return true;
                }

                if (!ctxManager.checkAndRecoverAllIfNeeded(aiService)) {
                    AILogger.w(TAG, "Context recovery failed, will try manual initialization");
                }

                if (ctxManager.isChatContextReady() && LlamaHelper.isChatContextActive()) {
                    return true;
                }
            }

            AILogger.i(TAG, "Initializing chat context for Agent...");
            notifyStep("初始化", "准备聊天上下文...");
            
            for (int attempt = 0; attempt < MAX_CONTEXT_INIT_RETRIES; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(CONTEXT_INIT_RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
                if (LlamaHelper.isChatContextActive()) {
                    if (ctxManager != null) {
                        ctxManager.setChatContextReady(true);
                    }
                    contextInitialized.set(true);
                    return true;
                }
                
                AILogger.i(TAG, "Chat context init attempt " + (attempt + 1) + "/" + MAX_CONTEXT_INIT_RETRIES);
                
                boolean initialized = false;
                try {
                    if (ctxManager != null) {
                        initialized = ctxManager.ensureChatContext(
                            aiService,
                            getDefaultGlobalPrompt(),
                            getDefaultSystemPrompt(),
                            getDefaultNormalPrompt()
                        );
                    } else {
                        initialized = aiService.initChatContext(
                            getDefaultGlobalPrompt(),
                            getDefaultSystemPrompt(),
                            getDefaultNormalPrompt()
                        );
                    }
                } catch (Exception e) {
                    AILogger.w(TAG, "Context init exception (attempt " + (attempt + 1) + "): " + e.getMessage());
                    continue;
                }
                
                if (initialized && LlamaHelper.isChatContextActive()) {
                    contextInitialized.set(true);
                    AILogger.i(TAG, "Chat context initialized successfully");
                    return true;
                }
            }

            if (LlamaHelper.isChatContextActive()) {
                if (ctxManager != null) {
                    ctxManager.setChatContextReady(true);
                }
                contextInitialized.set(true);
                return true;
            }
            
            String error = "无法初始化聊天上下文，AI服务可能未就绪";
            AILogger.e(TAG, error);
            finishGeneration();
            notifyError(error);
            return false;
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error ensuring chat context ready: " + e.getMessage(), e);
            String error = "上下文初始化异常: " + e.getMessage();
            finishGeneration();
            notifyError(error);
            return false;
        }
    }

    private String truncateForContext(String text) {
        return truncateForContext(text, 1000);
    }

    private String truncateForContext(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 验证并准备工具调用参数
     * @param call 工具调用信息
     * @return 验证通过后的参数，如果用户取消则返回 null
     */
    private Map<String, Object> validateAndPrepareToolCall(AgentService.ToolCall call) {
        // 解析参数
        Map<String, Object> params = call.resolvedArgs != null
            ? call.resolvedArgs
            : parseToolArguments(call.arguments);

        // 检查缺失参数
        List<String> missingParams = checkMissingParams(call.name, params);

        if (!missingParams.isEmpty()) {
            AILogger.i(TAG, "工具 " + call.name + " 缺失参数: " + String.join(", ", missingParams));

            // 暂停执行并请求用户补充参数
            pauseExecution(
                "参数缺失: " + String.join(", ", missingParams),
                "执行工具 " + call.name,
                params,
                call.name
            );

            // 等待用户输入
            waitForUserInput();

            // 如果用户取消，返回 null
            if (executionState.get() == ExecutionState.CANCELLED) {
                AILogger.i(TAG, "用户取消了工具调用: " + call.name);
                return null;
            }

            // 使用用户补充的参数
            Map<String, Object> resultParams = suspendedParams.get();
            if (resultParams == null) {
                AILogger.w(TAG, "用户补充的参数为空，使用原参数");
                return params;
            }
            return resultParams;
        }

        return params;
    }

    /**
     * 带超时控制的工具执行
     * @param validatedCall 验证通过的工具调用
     * @return 工具执行结果
     */
    private AgentService.ToolResult executeToolWithTimeout(AgentService.ToolCall validatedCall) {
        long startTime = System.currentTimeMillis();
        int retryCount = 0;

        while (retryCount <= TOOL_EXECUTION_MAX_RETRIES) {
            if (isCancelled.get()) {
                return new AgentService.ToolResult(validatedCall.name, "用户取消执行", false, 0, 0);
            }

            try {
                // 使用 Future 实现超时控制
                java.util.concurrent.Future<AgentService.ToolResult> future =
                    executor.submit(() -> agentService.executeTool(validatedCall));

                AgentService.ToolResult result = future.get(TOOL_EXECUTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

                long executionTime = System.currentTimeMillis() - startTime;
                if (result != null) {
                    AILogger.i(TAG, "工具 " + validatedCall.name + " 执行成功，耗时: " + executionTime + "ms");
                    // 返回带执行时间的结果
                    return new AgentService.ToolResult(
                        result.toolName, result.result, result.success,
                        executionTime, retryCount
                    );
                } else {
                    AILogger.e(TAG, "工具 " + validatedCall.name + " 返回 null");
                    return new AgentService.ToolResult(validatedCall.name, "工具返回null", false, executionTime, retryCount);
                }

            } catch (java.util.concurrent.TimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                AILogger.w(TAG, "工具 " + validatedCall.name + " 执行超时 (" + TOOL_EXECUTION_TIMEOUT_MS + "ms)，重试 " + (retryCount + 1) + "/" + TOOL_EXECUTION_MAX_RETRIES);
                retryCount++;

                if (retryCount > TOOL_EXECUTION_MAX_RETRIES) {
                    return new AgentService.ToolResult(validatedCall.name,
                        "工具执行超时，已重试" + TOOL_EXECUTION_MAX_RETRIES + "次", false, elapsed, retryCount);
                }

                // 短暂延迟后重试
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new AgentService.ToolResult(validatedCall.name, "执行被中断", false, elapsed, retryCount);
                }

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                AILogger.e(TAG, "工具 " + validatedCall.name + " 执行异常: " + e.getMessage(), e);
                return new AgentService.ToolResult(validatedCall.name,
                    "工具执行异常: " + e.getMessage(), false, elapsed, retryCount);
            }
        }

        return new AgentService.ToolResult(validatedCall.name, "工具执行失败", false, 0, TOOL_EXECUTION_MAX_RETRIES);
    }

    /**
     * 刷新工具索引缓存。仅在 dirty 时重建，确保高效。
     */
    private void refreshToolCacheIfNeeded() {
        if (!isToolCacheDirty || agentService == null) return;

        List<AgentService.ToolSchema> schemas = agentService.getMainToolSchemas();

        // 1. 构建 HashMap 索引 —— O(1) 查找工具名
        toolIndexCache = new HashMap<>();
        for (AgentService.ToolSchema schema : schemas) {
            toolIndexCache.put(schema.name, schema);
        }

        // 2. 预构建完整工具列表（含参数）
        StringBuilder promptSb = new StringBuilder();
        promptSb.append("【可用工具】\n");
        for (AgentService.ToolSchema schema : schemas) {
            promptSb.append("- ").append(schema.name).append(": ").append(schema.description);
            if (!schema.paramDesc.isEmpty()) {
                promptSb.append(" | ").append(schema.paramDesc);
            }
            promptSb.append("\n");
        }
        toolListPromptCache = promptSb.toString();

        // 3. 预构建精简工具列表（不含参数）
        StringBuilder briefSb = new StringBuilder();
        for (AgentService.ToolSchema schema : schemas) {
            briefSb.append("- ").append(schema.name).append(": ").append(schema.description).append("\n");
        }
        toolListBriefCache = briefSb.toString();

        // 4. 预构建工具名速查
        StringBuilder namesSb = new StringBuilder();
        for (AgentService.ToolSchema schema : schemas) {
            if (namesSb.length() > 0) namesSb.append(" | ");
            namesSb.append(schema.name);
        }
        toolNamesOnlyCache = namesSb.toString();

        // 5. 预构建关键词-工具映射（用于联想匹配）
        keywordToToolCache = new HashMap<>();
        String[][] keywordMap = {
            {"weather", "天气", "气温", "温度", "预报", "下雨", "storm", "降水", "ai_weather"},
            {"search", "搜索", "查找", "检索", "网上查", "network_search"},
            {"research", "研究", "smart_research"},
            {"webpage", "网页", "网站", "url", "webpage_reader"},
            {"translate", "翻译", "translation"},
            {"calculate", "计算", "算", "数学", "python_calculate"},
            {"python", "代码执行", "python_execute"},
            {"analyze", "分析数据", "python_analyze_data"},
            {"location", "位置", "定位", "gps", "经纬度", "location"},
            {"file", "文件", "读取文件", "file_reader"},
            {"ocr", "识别图片", "文字识别", "app_toolkit"},
            {"database", "数据库", "题库", "错题", "database"},
            {"open", "打开应用", "启动", "system_resource"},
            {"permission", "权限", "permission_manager"},
            {"generate", "生成文件", "file_generator"},
        };
        for (String[] entry : keywordMap) {
            String toolName = entry[entry.length - 1];
            if (!toolIndexCache.containsKey(toolName)) continue;
            for (int i = 0; i < entry.length - 1; i++) {
                keywordToToolCache.put(entry[i].toLowerCase(), toolName);
            }
        }

        isToolCacheDirty = false;
    }

    /**
     * 标记工具缓存为脏，下次访问时自动重建。
     * 在工具列表变化（如注册/卸载工具）时调用。
     */
    public void markToolCacheDirty() {
        isToolCacheDirty = true;
    }

    private String buildToolListPrompt() {
        refreshToolCacheIfNeeded();
        return toolListPromptCache == null ? "" : toolListPromptCache;
    }

    /**
     * 精简工具列表 —— 只有工具名和一行描述，不含参数详情。
     * 用于首次推理，大幅减少提示词体积。
     */
    private String buildToolListBrief() {
        refreshToolCacheIfNeeded();
        return toolListBriefCache == null ? "" : toolListBriefCache;
    }

    /**
     * 单个工具的详细参数定义。
     * 通过 HashMap 索引 O(1) 查找，避免线性遍历。
     *
     * @param toolName 工具名
     * @return 工具详情文本，找不到时返回空串
     */
    private String buildToolDetailPrompt(String toolName) {
        refreshToolCacheIfNeeded();
        if (toolIndexCache == null || toolName == null) return "";
        AgentService.ToolSchema schema = toolIndexCache.get(toolName);
        if (schema == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("工具: ").append(toolName).append("\n");
        sb.append("功能: ").append(schema.description).append("\n");
        if (!schema.paramDesc.isEmpty()) {
            sb.append("参数: ").append(schema.paramDesc).append("\n");
        }
        return sb.toString();
    }

    /**
     * 极简工具名列表 —— 只有工具名，空格分隔，单行。
     * 用于后续推理轮的"工具速查"，让 agent 知道有哪些工具可选，体积极小。
     */
    private String buildToolNamesOnly() {
        refreshToolCacheIfNeeded();
        return toolNamesOnlyCache == null ? "" : toolNamesOnlyCache;
    }

    /**
     * 通过 HashMap 索引直接获取 ToolSchema，O(1) 操作。
     */
    private AgentService.ToolSchema getToolSchema(String toolName) {
        refreshToolCacheIfNeeded();
        if (toolIndexCache == null) return null;
        return toolIndexCache.get(toolName);
    }

    /**
     * 从缓存的关键词映射中查找工具名。
     */
    private String lookupToolByKeyword(String keyword) {
        refreshToolCacheIfNeeded();
        if (keywordToToolCache == null || keyword == null) return null;
        return keywordToToolCache.get(keyword.toLowerCase());
    }

    /**
     * 从模型的不正确回复中提取它想调用的工具名。
     * 使用缓存索引进行高效匹配。
     *
     * @param response 模型的不正确回复
     * @return 匹配到的工具名，未匹配返回 null
     */
    private String extractToolNameFromResponse(String response) {
        if (response == null || response.isEmpty() || agentService == null) return null;
        refreshToolCacheIfNeeded();
        if (toolIndexCache == null) return null;

        String lower = response.toLowerCase();

        // 1. 精确匹配：遍历缓存的工具名集合
        for (String toolName : toolIndexCache.keySet()) {
            if (response.contains(toolName)) {
                return toolName;
            }
        }

        // 2. 模糊匹配：工具名去前缀后的核心词（如 ai_weather → weather）
        for (AgentService.ToolSchema schema : toolIndexCache.values()) {
            String core = schema.name;
            int underscore = core.indexOf('_');
            if (underscore > 0 && underscore < core.length() - 1) {
                core = core.substring(underscore + 1);
            }
            if (core.length() >= 3 && lower.contains(core.toLowerCase())) {
                return schema.name;
            }
        }

        // 3. 关键词联想：直接从缓存的关键词映射中查找
        for (Map.Entry<String, String> entry : keywordToToolCache.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    // ========== 工具链组合推理 ==========

    /**
     * 工具关联关系映射 —— 当某工具失败/不满足时，建议的替代/补充工具。
     * 每行第一个是失败工具，后续是建议的替代工具（按优先级排列）。
     */
    private static final String[][] TOOL_FALLBACK_MAP = {
        {"ai_weather", "location", "network_search"},
        {"network_search", "smart_research", "webpage_reader"},
        {"file_reader", "app_toolkit", "file"},
        {"python_calculate", "python_execute"},
        {"translation", "network_search"},
        {"location", "network_search"},
        {"smart_research", "network_search", "webpage_reader"},
        {"database", "network_search"},
        {"app_toolkit", "file_reader", "network_search"},
    };

    /**
     * 获取某工具失败时的替代工具列表（已验证工具存在）。
     */
    private List<String> getFallbackTools(String failedTool) {
        List<String> result = new ArrayList<>();
        if (failedTool == null || agentService == null) return result;
        refreshToolCacheIfNeeded();
        for (String[] entry : TOOL_FALLBACK_MAP) {
            if (entry[0].equals(failedTool)) {
                for (int i = 1; i < entry.length; i++) {
                    if (toolIndexCache != null && toolIndexCache.containsKey(entry[i])) {
                        result.add(entry[i]);
                    }
                }
                break;
            }
        }
        return result;
    }

    /**
     * 构建工具失败时的提示词（不带已提供参数，委托给带参数版本）。
     */
    private String buildFallbackSuggestionPrompt(String failedTool, String failReason) {
        return buildFallbackSuggestionPrompt(failedTool, failReason, null);
    }

    /**
     * 构建工具失败时的提示词。
     * - 参数错误：分析缺失参数，判断能否自动补充/需调工具获取/需问用户，让模型修正后重试
     * - 工具不适用：联想替代工具（预定义映射 → 通用全量列表），让模型自行判断
     *
     * @param providedArgsJson 模型提供的参数 JSON（可为 null）
     */
    private String buildFallbackSuggestionPrompt(String failedTool, String failReason, String providedArgsJson) {
        StringBuilder sb = new StringBuilder();

        boolean likelyParamError = isLikelyParamError(failReason);

        if (likelyParamError) {
            // 参数错误：分析缺失参数，给出具体补充建议
            sb.append("[工具参数可能错误] ").append(failedTool).append(" 执行失败。\n");
            sb.append("原因: ").append(failReason).append("\n\n");
            String detail = buildToolDetailPrompt(failedTool);
            if (!detail.isEmpty()) {
                sb.append("=== 该工具的详细参数 ===\n");
                sb.append(detail).append("\n");
            }

            // 智能分析缺失参数
            List<String> requiredParams = parseParamNames(failedTool);
            Set<String> providedParams = parseProvidedParams(providedArgsJson);
            List<String> missingParams = new ArrayList<>();
            for (String p : requiredParams) {
                if (!providedParams.contains(p)) {
                    missingParams.add(p);
                }
            }

            if (!missingParams.isEmpty()) {
                sb.append("=== 缺失参数分析 ===\n");
                for (String param : missingParams) {
                    sb.append("- ").append(param);
                    if (isLocationParam(param)) {
                        sb.append(": 位置参数，可先调用 location 工具获取当前位置\n");
                    } else if (isTimeParam(param)) {
                        sb.append(": 时间参数，可使用当前时间\n");
                    } else {
                        sb.append(": 需要从用户输入或上下文获取\n");
                    }
                }
                sb.append("\n请根据以上分析：\n");
                sb.append("1. 能自动补充的参数（如当前时间）请直接填入\n");
                sb.append("2. 需要调工具获取的参数（如位置）请先调用对应工具\n");
                sb.append("3. 修正参数后重新调用该工具\n\n");
            } else {
                // 参数齐全但值不对（如枚举值错误）
                sb.append("参数齐全但值可能有误，请检查参数值是否正确后重新调用。\n\n");
            }
        } else {
            // 工具本身不适用：建议替代工具
            sb.append("[工具失败] ").append(failedTool).append(" 未能完成任务。\n");
            sb.append("原因: ").append(failReason).append("\n\n");
        }

        // 联想替代工具：优先用预定义映射，无匹配时给全量简列表让模型自行判断
        List<String> fallbacks = getFallbackTools(failedTool);
        if (!fallbacks.isEmpty()) {
            sb.append("=== 替代工具建议 ===\n");
            for (String toolName : fallbacks) {
                String detail = buildToolDetailPrompt(toolName);
                if (!detail.isEmpty()) {
                    sb.append(detail).append("\n");
                }
            }
        } else {
            // 通用联想：所有工具的简列表，让模型自行判断哪些可能有帮助
            sb.append("=== 其他可用工具 ===\n");
            sb.append(buildToolListBrief());
        }
        sb.append("\n请判断以上工具是否适合完成当前任务。如适合请调用，不适合可直接回答用户。\n\n");
        sb.append("[工具速查] ").append(buildToolNamesOnly());
        sb.append("\n如需调用工具，使用 TOOLS_CALL/TOOLS_END 格式。");
        sb.append("\n如需了解某工具的详细参数，输出 [TOOL_INFO: 工具名]。");
        sb.append("\n如需查看完整工具调用指南，输出 [TOOL_GUIDE]。");
        return sb.toString();
    }

    /**
     * 判断工具失败原因是否可能是参数错误（而非工具本身不适用）。
     * 参数错误时应优先修正参数重试，而非武断更换工具。
     */
    private boolean isLikelyParamError(String failReason) {
        if (failReason == null) return false;
        String lower = failReason.toLowerCase();
        return lower.contains("参数") || lower.contains("parameter") || lower.contains("argument")
            || lower.contains("无效") || lower.contains("invalid") || lower.contains("缺失") || lower.contains("missing")
            || lower.contains("格式") || lower.contains("format") || lower.contains("类型错误")
            || lower.contains("空值") || lower.contains("null") || lower.contains("empty")
            || lower.contains("找不到") || lower.contains("not found") || lower.contains("未找到")
            || lower.contains("非法") || lower.contains("illegal");
    }

    /**
     * 从工具的 paramDesc 中解析参数名列表。
     * paramDesc 格式: "city(城市名), action(current/forecast/hourly/air/alert)"
     */
    private List<String> parseParamNames(String toolName) {
        List<String> params = new ArrayList<>();
        if (agentService == null || toolName == null) return params;
        AgentService.ToolSchema schema = getToolSchema(toolName);
        if (schema == null) return params;
        String desc = schema.paramDesc;
        if (desc == null || desc.equals("无参数")) return params;
        // 解析 paramName(description) 格式
        int i = 0;
        while (i < desc.length()) {
            int parenStart = desc.indexOf('(', i);
            if (parenStart <= 0) break;
            String name = desc.substring(i, parenStart).trim();
            // 去除前导逗号空格
            if (name.endsWith(",")) name = name.substring(0, name.length() - 1).trim();
            if (!name.isEmpty()) params.add(name);
            int parenEnd = desc.indexOf(')', parenStart);
            if (parenEnd < 0) break;
            i = parenEnd + 1;
            // 跳过逗号空格
            while (i < desc.length() && (desc.charAt(i) == ',' || desc.charAt(i) == ' ')) i++;
        }
        return params;
    }

    /**
     * 从模型提供的 JSON 参数中提取已提供的参数名集合。
     */
    private Set<String> parseProvidedParams(String argsJson) {
        Set<String> params = new HashSet<>();
        if (argsJson == null || argsJson.isEmpty()) return params;
        try {
            org.json.JSONObject json = new org.json.JSONObject(argsJson);
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                params.add(keys.next());
            }
        } catch (Exception e) {
            // JSON 解析失败，返回空集
        }
        return params;
    }

    /**
     * 判断参数是否为位置相关参数（可通过 location 工具获取）。
     */
    private boolean isLocationParam(String paramName) {
        if (paramName == null) return false;
        String lower = paramName.toLowerCase();
        return lower.contains("city") || lower.contains("lat") || lower.contains("lon")
            || lower.contains("location") || lower.contains("address") || lower.contains("位置")
            || lower.contains("城市");
    }

    /**
     * 判断参数是否为时间相关参数（可自动填充当前时间）。
     */
    private boolean isTimeParam(String paramName) {
        if (paramName == null) return false;
        String lower = paramName.toLowerCase();
        return lower.contains("date") || lower.contains("time") || lower.contains("timestamp");
    }

    /**
     * 从模型回复中提取主动工具详情请求 [TOOL_INFO: 工具名]。
     *
     * @return 请求的工具名，无请求返回 null
     */
    private String extractToolInfoRequest(String response) {
        if (response == null) return null;
        int idx = response.indexOf("[TOOL_INFO:");
        if (idx < 0) return null;
        int end = response.indexOf("]", idx);
        if (end < 0) return null;
        return response.substring(idx + 11, end).trim();
    }

    /**
     * 构建工具详情响应提示词（用于模型主动请求工具详情后注入）。
     */
    private String buildToolInfoResponsePrompt(String requestedTool) {
        String detail = buildToolDetailPrompt(requestedTool);
        StringBuilder sb = new StringBuilder();
        if (detail.isEmpty()) {
            sb.append("[工具详情] 未找到工具: ").append(requestedTool).append("\n");
            sb.append("[工具速查] ").append(buildToolNamesOnly());
        } else {
            sb.append("[工具详情]\n").append(detail).append("\n");
        }
        sb.append("\n请根据以上信息，决定是否调用该工具。如需调用，使用 TOOLS_CALL/TOOLS_END 格式。");
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

    /**
     * 分析工具结果是否满足用户需求
     * @param userMessage 用户原始消息
     * @param toolName 工具名称
     * @param toolResult 工具执行结果
     * @param currentContext 当前上下文
     * @return 分析结果
     */
    private ToolResultAnalysis analyzeToolResult(String userMessage, String toolName,
                                                  String toolResult, String currentContext,
                                                  boolean toolSuccess) {
        ToolResultAnalysis analysis = new ToolResultAnalysis();

        if (toolResult == null || toolResult.isEmpty()) {
            analysis.sufficient = false;
            analysis.reason = "工具返回空结果";
            analysis.suggestion = "请检查参数是否正确，或尝试其他工具";
            return analysis;
        }

        // 仅当工具执行本身失败时标记为不满足
        // 工具返回的API错误（如403）应交给LLM判断如何处理，而不是自动重试
        if (!toolSuccess) {
            analysis.sufficient = false;
            analysis.reason = "工具执行失败: " + toolResult.substring(0, Math.min(100, toolResult.length()));
            analysis.suggestion = "请重试或使用其他工具";
            return analysis;
        }

        // 检查结果是否太短（可能是无效结果）
        if (toolResult.length() < 10 && !toolName.contains("calculator")) {
            analysis.sufficient = false;
            analysis.reason = "工具返回结果过短，可能不是有效结果";
            analysis.suggestion = "请检查参数或尝试更详细的查询";
            return analysis;
        }

        // 默认认为结果可用，让AI判断是否需要继续
        analysis.sufficient = true;
        analysis.reason = "工具执行成功，结果已获取";
        analysis.summarizedResult = toolResult;
        return analysis;
    }

    /**
     * 工具结果分析类
     */
    private static class ToolResultAnalysis {
        boolean sufficient = true;
        String reason = "";
        String suggestion = "";
        String summarizedResult = "";
    }

    /**
     * 清理Agent内部缓存，准备下一次调用
     */
    public void cleanupAfterCompletion() {
        resetBuffers();
        toolLoopCount.set(0);
        iterationCount.set(0);
        isCancelled.set(false);
        executionState.set(ExecutionState.COMPLETED);
        suspendedParams.set(null);
        suspendedToolName.set(null);
        pauseReason.set(null);
        currentTask.set(null);
        validationRetryCount.set(0);
        AILogger.i(TAG, "Agent cleanup completed, ready for next call");
    }

    public void cancel() {
        isCancelled.set(true);
        aiService.chatStop();
        finishGeneration();
        cleanupAfterCompletion();
    }

    public void clearContext() {
        contextSummary.clear();
        onlineModelConversationHistory.clear();
        cleanupAfterCompletion();
        AILogger.i(TAG, "Context and online model history cleared");
    }

    public List<String> getContextSummaryList() {
        synchronized (contextSummary) {
            return new ArrayList<>(contextSummary);
        }
    }

    /**
     * 上下文状态类
     */
    public class ContextStatus {
        public int entryCount;
        public int totalChars;
        public int userQueryCount;
        public int toolCallCount;
        public boolean isHealthy;
    }

    /**
     * 获取当前上下文状态
     */
    public ContextStatus getContextStatus() {
        ContextStatus status = new ContextStatus();
        status.entryCount = contextSummary.size();
        status.totalChars = 0;
        status.userQueryCount = 0;
        status.toolCallCount = 0;

        synchronized (contextSummary) {
            for (String entry : contextSummary) {
                status.totalChars += entry.length();
                if (entry.startsWith("用户:")) status.userQueryCount++;
                if (entry.startsWith("Observation:") || entry.startsWith("工具结果:")) status.toolCallCount++;
            }
        }

        status.isHealthy = status.totalChars < CONTEXT_TRUNCATE_THRESHOLD && status.entryCount < MAX_CONTEXT_ENTRIES;
        return status;
    }

    /**
     * 截断并验证 prompt 长度
     */
    private String truncateAndValidatePrompt(String message) {
        if (message == null) return "";
        if (message.length() > MAX_PROMPT_LENGTH) {
            AILogger.w(TAG, "Prompt truncated: " + message.length() + " -> " + MAX_PROMPT_LENGTH);
            return message.substring(0, MAX_PROMPT_LENGTH) + "\n...[已截断]";
        }
        return message;
    }

    /**
     * 裁剪上下文，防止无限增长
     */
    private void trimContextIfNeeded() {
        synchronized (contextSummary) {
            // 按条目数量裁剪
            if (contextSummary.size() > MAX_CONTEXT_ENTRIES) {
                int toRemove = contextSummary.size() - MAX_CONTEXT_ENTRIES;
                for (int i = 0; i < toRemove; i++) {
                    contextSummary.remove(0);
                }
                AILogger.w(TAG, "Context trimmed by count: removed " + toRemove + " entries, remaining: " + contextSummary.size());
            }

            // 按总字符数裁剪
            int totalChars = 0;
            for (String entry : contextSummary) {
                totalChars += entry.length();
            }
            while (totalChars > CONTEXT_TRUNCATE_THRESHOLD && contextSummary.size() > 5) {
                String removed = contextSummary.remove(0);
                totalChars -= removed.length();
            }
            if (totalChars > CONTEXT_TRUNCATE_THRESHOLD) {
                AILogger.w(TAG, "Context still over threshold after trim: " + totalChars + " chars");
            }
        }
    }

    /**
     * 记录上下文状态日志
     */
    private void logContextStatus(String stage) {
        int size = contextSummary.size();
        int totalChars = 0;
        for (String entry : contextSummary) {
            totalChars += entry.length();
        }
        AILogger.i(TAG, "Context [" + stage + "]: " + size + " entries, " + totalChars + " chars");
    }

    public boolean isGenerating() { return isGenerating.get(); }
    public int getToolLoopCount() { return toolLoopCount.get(); }
    public String getCurrentResponse() { return currentResponse.get().toString(); }
    public String getCurrentThinking() { return currentThinking.get().toString(); }

    /**
     * 检查引擎是否有效（可用于回调）
     */
    public boolean isValid() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void notifyToken(String token) {
        if (!isValid()) return;
        int tokenCount = currentRequestTokens.incrementAndGet();
        activity.runOnUiThread(() -> {
            if (callback != null && isValid()) callback.onToken(token);
            // 更新流式token统计
            if (tokenStatsManager != null) {
                tokenStatsManager.updateRequestStreamingStats(tokenCount);
            }
            // 计算并通知推理速度（每10个token更新一次）
            if (tokenCount % 10 == 0 && currentRequestStartTime > 0) {
                long elapsed = System.currentTimeMillis() - currentRequestStartTime;
                float tokensPerSecond = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
                if (inferenceProgressListener != null) {
                    inferenceProgressListener.onProgressUpdate(tokenCount, tokensPerSecond);
                }
            }
        });
    }

    private void notifyThinkingToken(String token) {
        if (!isValid()) return;
        activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onThinkingToken(token); });
    }

    private void notifyToolCallStart(String name, String args) {
        if (!isValid()) return;
        activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onToolCallStart(name, args); });
    }

    private void notifyStep(String step, String detail) {
        if (!isValid()) return;
        activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onStepUpdate(step, detail); });
    }

    private void notifyComplete(String text) {
        // 更新最终token统计
        int tokens = currentRequestTokens.get();
        if (tokenStatsManager != null && tokens > 0) {
            int promptTokens = contextSummary.toString().length() / 4;
            tokenStatsManager.updateRequestStats(promptTokens, tokens);
        }
        finishGeneration();
        // 清理Agent内部缓存，准备下一次调用
        cleanupAfterCompletion();
        AILogger.i(TAG, "Complete: mode=" + currentMode + " len=" + text.length() + " tokens=" + tokens);
        if (!isValid()) return;
        activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onComplete(text); });
    }

    private void notifyError(String error) {
        finishGeneration();
        AILogger.e(TAG, "Error: " + error);
        if (!isValid()) return;
        activity.runOnUiThread(() -> { if (callback != null && isValid()) callback.onError(error); });
    }

    // ========== Python 自动执行能力 ==========

    /**
     * 判断是否应该使用 Python 自动执行
     */
    public boolean shouldUsePython(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        for (String trigger : PYTHON_TRIGGERS) {
            if (lower.contains(trigger.toLowerCase())) {
                AILogger.i(TAG, "shouldUsePython: triggered by '" + trigger + "' in '" + message + "'");
                return true;
            }
        }
        return false;
    }

    /**
     * 自动执行 Python 代码并返回结果（带错误处理）
     */
    public String autoExecutePython(String task) {
        AILogger.i(TAG, "autoExecutePython: " + task);

        try {
            // 1. 生成或获取 Python 代码
            String code = generatePythonCodeForTask(task);
            if (code == null || code.isEmpty()) {
                AILogger.w(TAG, "Failed to generate code");
                return "代码生成失败，无法完成任务";
            }
            AILogger.i(TAG, "Generated code: " + code.substring(0, Math.min(100, code.length())));

            // 2. 执行代码
            PythonToolManager manager = PythonToolManager.getInstance(activity);
            if (!manager.isInitialized()) {
                if (!manager.initialize()) {
                    return "Python 解释器初始化失败";
                }
            }

            // 3. 执行并自动修复（最多3次尝试）
            PythonToolManager.ExecutionResult result = manager.executeWithRetry(code, 3, 60);

            // 4. 处理结果
            if (result != null && result.success) {
                String output = result.stdout;
                if (output == null || output.isEmpty()) {
                    output = result.result;
                }
                if (output != null && !output.isEmpty()) {
                    AILogger.i(TAG, "Python execution output: " + output.substring(0, Math.min(200, output.length())));
                    return output;
                }
                return "执行成功（无输出）";
            } else {
                // 执行失败，返回错误信息
                String errorMsg = result != null ? result.error : "未知错误";
                String stderrMsg = result != null ? result.stderr : "";
                
                AILogger.w(TAG, "Python execution failed: " + errorMsg);
                
                // 格式化错误信息给用户
                StringBuilder errorBuilder = new StringBuilder();
                errorBuilder.append("Python 执行失败: ").append(errorMsg);
                
                if (stderrMsg != null && !stderrMsg.isEmpty()) {
                    // 截取关键错误信息
                    String[] lines = stderrMsg.split("\n");
                    for (int i = 0; i < Math.min(5, lines.length); i++) {
                        if (lines[i].contains("Error") || lines[i].contains("Exception")) {
                            errorBuilder.append("\n").append(lines[i].trim());
                        }
                    }
                }
                
                return errorBuilder.toString();
            }
        } catch (Exception e) {
            AILogger.e(TAG, "autoExecutePython error: " + e.getMessage(), e);
            return "Python 执行异常: " + e.getMessage();
        }
    }

    /**
     * 为任务生成 Python 代码
     */
    private String generatePythonCodeForTask(String task) {
        // 优先使用 LLM 生成
        try {
            String prompt = "为以下任务生成 Python 代码。要求：\n1. 只输出纯代码，不要 markdown 标记\n2. 使用 print() 输出结果\n3. 代码简洁可执行\n\n任务：" + task;
            String code = aiService.generateSync(prompt, 500);
            if (code != null && !code.isEmpty()) {
                // 清理 markdown 标记
                code = code.replaceAll("```python\\s*", "");
                code = code.replaceAll("```\\s*", "");
                code = code.replaceAll("```py\\s*", "");
                return code.trim();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "LLM code generation failed, using template: " + e.getMessage());
        }

        // 回退到模板生成
        try {
            PythonToolManager manager = PythonToolManager.getInstance(activity);
            return manager.generateCode(task, null);
        } catch (Exception e) {
            AILogger.e(TAG, "Template code generation failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * 注入 Python 执行结果到上下文
     */
    private void injectPythonResult(String result) {
        String observation = "Observation: Python执行结果 = " + result.replace("\n", " ");
        contextSummary.add(observation);
        AILogger.i(TAG, "Injected Python result to context");
    }

    // ========== Python 工具执行 ==========

    private static final String TOOL_PYTHON_EXECUTE = "python_execute";
    private static final String TOOL_PYTHON_CALCULATE = "python_calculate";
    private static final String TOOL_PYTHON_ANALYZE = "python_analyze_data";

    /**
     * 执行 Python 工具
     */
    public String executePythonTool(String toolName, Map<String, Object> params) {
        AILogger.i(TAG, "executePythonTool: " + toolName);

        try {
            PythonToolManager pythonManager = PythonToolManager.getInstance(activity);
            if (!pythonManager.isInitialized()) {
                pythonManager.initialize();
            }

            switch (toolName) {
                case TOOL_PYTHON_EXECUTE: {
                    String task = (String) params.getOrDefault("task", params.getOrDefault("code", ""));
                    if (task == null || task.isEmpty()) {
                        return "错误：缺少 task 参数";
                    }
                    PythonToolManager.ExecutionResult result = pythonManager.processTask(task, params);
                    return formatPythonResult(result);
                }
                case TOOL_PYTHON_CALCULATE: {
                    String expression = (String) params.get("expression");
                    if (expression == null || expression.isEmpty()) {
                        return "错误：缺少 expression 参数";
                    }
                    PythonToolManager.ExecutionResult result = pythonManager.processTask(
                        "计算: " + expression, null);
                    return formatPythonResult(result);
                }
                case TOOL_PYTHON_ANALYZE: {
                    String data = (String) params.get("data");
                    PythonToolManager.ExecutionResult result = pythonManager.processTask(
                        "分析数据: " + (data != null ? data : ""), params);
                    return formatPythonResult(result);
                }
                default:
                    return "未知 Python 工具: " + toolName;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Python tool error: " + e.getMessage(), e);
            return "Python 工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 格式化 Python 执行结果
     */
    private String formatPythonResult(PythonToolManager.ExecutionResult result) {
        if (result == null) {
            return "执行结果为空";
        }
        if (result.success) {
            StringBuilder sb = new StringBuilder();
            if (result.stdout != null && !result.stdout.isEmpty()) {
                sb.append(result.stdout);
            }
            if (result.result != null && !result.result.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(result.result);
            }
            return sb.length() > 0 ? sb.toString() : "执行成功（无输出）";
        } else {
            return "执行失败: " + (result.error != null ? result.error : result.stderr);
        }
    }

    /**
     * 生成 Python 代码
     */
    public String generatePythonCode(String task) {
        try {
            String prompt = "请为以下任务生成 Python 代码：\n" + task + "\n\n要求：\n1. 代码完整可运行\n2. 使用中文注释\n3. 添加必要的错误处理";
            
            if (aiService != null) {
                return aiService.generateSync(prompt, null, 2000);
            }
            
            return "代码生成失败：AI 服务未初始化";
        } catch (Exception e) {
            AILogger.e(TAG, "generatePythonCode error: " + e.getMessage(), e);
            return "代码生成失败: " + e.getMessage();
        }
    }

    // ========== AIAgentEngine 兼容方法 ==========

    // ========== AIAgentEngine 兼容方法 ==========

    private final Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.UUID uuid = java.util.UUID.randomUUID();
    private String currentSessionId;
    private AgentState currentState = AgentState.IDLE;
    private OnAgentStepListener stepListener;

    public String createSession() {
        cleanupExpiredSessions();
        String sessionId = java.util.UUID.randomUUID().toString();
        sessions.put(sessionId, new AgentSession(sessionId));
        currentSessionId = sessionId;
        return sessionId;
    }
    
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        java.util.List<String> expiredIds = new ArrayList<>();
        
        for (java.util.Map.Entry<String, AgentSession> entry : sessions.entrySet()) {
            if (now - entry.getValue().createdAt > SESSION_EXPIRY_MS) {
                expiredIds.add(entry.getKey());
            }
        }
        
        for (String id : expiredIds) {
            sessions.remove(id);
            AILogger.i(TAG, "Expired session removed: " + id);
        }
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public java.util.concurrent.CompletableFuture<AgentResult> processInput(String message) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                AgentSession session = sessions.get(currentSessionId);
                if (session != null) {
                    session.history.add(new ChatMessage("user", message));
                }

                // 尝试使用 ServiceRouter 处理
                if (serviceRouter != null) {
                    AgentResult weatherResult = serviceRouter.executeWeatherTask(message);
                    if (weatherResult != null && weatherResult.success) {
                        return weatherResult;
                    }
                    AgentResult searchResult = serviceRouter.executeSearchTask(message);
                    if (searchResult != null && searchResult.success) {
                        return searchResult;
                    }
                    AgentResult dbResult = serviceRouter.executeDatabaseTask(message);
                    if (dbResult != null && dbResult.success) {
                        return dbResult;
                    }
                }

                // 默认使用 AI 生成
                currentState = AgentState.THINKING;
                notifyStepStart("thinking", "分析任务...");

                String response = aiService.generateSync(message, 512);
                currentState = AgentState.COMPLETED;
                notifyStepComplete("thinking", response);

                AgentResult result = new AgentResult(response, true);
                result.processingTimeMs = System.currentTimeMillis() - startTime;
                return result;
            } catch (Exception e) {
                AILogger.e(TAG, "Error processing input: " + e.getMessage(), e);
                currentState = AgentState.ERROR;
                return new AgentResult("处理出错: " + e.getMessage(), false);
            }
        }, executor);
    }

    public AgentResult executeToolCommand(String toolName, java.util.Map<String, Object> params) {
        currentState = AgentState.EXECUTING;
        notifyStepStart("tool_call", "调用工具: " + toolName);

        // ========== 参数检查 ==========
        List<String> missingParams = checkMissingParams(toolName, params);
        if (!missingParams.isEmpty()) {
            String errorMsg = "参数缺失: " + String.join(", ", missingParams);
            notifyStepError("tool_call", errorMsg);
            currentState = AgentState.ERROR;
            return new AgentResult(errorMsg, false);
        }
        // =================================

        try {
            AgentService.ToolResult toolResult = agentService.executeTool(toolName, params);
            if (toolResult.success) {
                currentState = AgentState.COMPLETED;
                notifyStepComplete("tool_call", String.valueOf(toolResult.result));
                return new AgentResult(String.valueOf(toolResult.result), true);
            } else {
                currentState = AgentState.ERROR;
                return new AgentResult(toolResult.errorMessage, false);
            }
        } catch (Exception e) {
            currentState = AgentState.ERROR;
            return new AgentResult("工具执行失败: " + e.getMessage(), false);
        }
    }

    public String generateSmartFallbackAnswer(String message) {
        if (message.contains("你好") || message.contains("hi") || message.contains("hello")) {
            return "你好！我是AI助手，有什么可以帮你的吗？";
        }
        if (message.contains("谢谢") || message.contains("感谢")) {
            return "不客气！如果还有其他问题，随时问我。";
        }
        return "抱歉，我暂时无法处理这个请求。请尝试换一种方式描述。";
    }

    private void notifyStepStart(String stepType, String description) {
        if (stepListener != null) stepListener.onStepStart(stepType, description);
    }

    private void notifyStepComplete(String stepType, String result) {
        if (stepListener != null) stepListener.onStepComplete(stepType, result);
    }

    private void notifyStepError(String stepType, String error) {
        if (stepListener != null) stepListener.onStepError(stepType, error);
    }

    public void setStepListener(OnAgentStepListener listener) { this.stepListener = listener; }
    public AgentState getCurrentState() { return currentState; }
    public OnAgentStepListener getStepListener() { return stepListener; }
    
    // ========== 智能思考功能 ==========
    
    /**
     * 构建思考提示
     * @param message 用户消息
     * @return 思考提示
     */
    private String buildThinkingPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下用户请求，给出详细的思考过程：\n\n");
        prompt.append("用户消息：").append(message).append("\n\n");
        prompt.append("思考要点：\n");
        prompt.append("1. 用户真正想要什么？\n");
        prompt.append("2. 需要哪些信息？\n");
        prompt.append("3. 可能的执行策略有哪些？\n");
        prompt.append("4. 哪种策略最好？\n");
        prompt.append("5. 需要调用哪些工具？\n");
        prompt.append("6. 缺少哪些参数？\n");
        prompt.append("7. 是否需要补充信息？\n\n");
        prompt.append("请用结构化的方式输出思考过程，每一项用 ✓ 或 ✗ 标记。\n");
        return prompt.toString();
    }
    
    /**
     * 执行智能思考
     * @param message 用户消息
     * @return 思考结果
     */
    private String performThinking(String message) {
        AILogger.i(TAG, "Performing thinking for: " + message);
        isThinking.set(true);
        currentAnalysisThinking.set(new StringBuilder());
        
        String prompt = buildThinkingPrompt(message);
        
        // 通知思考开始
        notifyThinkingStage("开始分析用户请求...");
        
        try {
            // 使用 AIService 生成思考内容
            String thinking = aiService.generateSync(prompt, 500);
            
            AILogger.i(TAG, "Thinking completed: " + (thinking != null ? thinking.length() : 0) + " chars");
            
            // 通知 UI 思考内容
            if (thinking != null && !thinking.isEmpty()) {
                notifyThinking(thinking);
            }
            
            return thinking;
        } catch (Exception e) {
            AILogger.e(TAG, "Thinking failed: " + e.getMessage(), e);
            return null;
        } finally {
            isThinking.set(false);
        }
    }
    
    /**
     * 基于思考结果做决策
     * @param thinking 思考内容
     * @param message 原始消息
     */
    private void makeDecisionBasedOnThinking(String thinking, String message) {
        AILogger.i(TAG, "Making decision based on thinking");
        
        if (thinking == null || thinking.isEmpty()) {
            AILogger.w(TAG, "No thinking content, using default decision");
            return;
        }
        
        // 分析思考结果
        // 这里可以解析思考内容，提取关键决策点
        // 例如：是否需要调用工具、缺少哪些参数等
        
        notifyThinkingStage("思考完成，准备执行...");
    }
    
    /**
     * 通知 UI 思考内容
     */
    private void notifyThinking(String thought) {
        if (!isValid()) return;
        activity.runOnUiThread(() -> {
            if (callback != null && isValid()) {
                callback.onThinking(thought);
            }
        });
    }

    /**
     * 通知 UI 思考阶段
     */
    private void notifyThinkingStage(String stage) {
        if (!isValid()) return;
        activity.runOnUiThread(() -> {
            if (callback != null && isValid()) {
                callback.onThinkingStage(stage);
            }
        });
    }
    
    // ========== 暂停/恢复功能 ==========
    
    /**
     * 获取当前执行状态
     */
    public ExecutionState getExecutionState() {
        return executionState.get();
    }
    
    /**
     * 暂停执行并等待用户输入
     * @param reason 暂停原因
     * @param task 当前任务描述
     * @param params 当前参数状态
     * @param toolName 当前工具名称
     */
    public void pauseExecution(String reason, String task, Map<String, Object> params, String toolName) {
        AILogger.i(TAG, "Pausing execution: " + reason);
        executionState.set(ExecutionState.PAUSED);
        pauseReason.set(reason);
        currentTask.set(task);
        suspendedParams.set(new HashMap<>(params));
        suspendedToolName.set(toolName);
        
        // 通知 UI 层
        activity.runOnUiThread(() -> {
            if (callback != null) {
                callback.onExecutionPaused(reason, task);
                
                // 生成建议
                List<String> suggestions = ToolParameterValidator.getSuggestions(toolName, params);
                String missingInfo = ToolParameterValidator.getMissingDescription(toolName, params);
                callback.onNeedMoreInfo(missingInfo, task, suggestions);
            }
        });
    }
    
    /**
     * 用户输入后恢复执行（带验证）
     * @param userInput 用户补充的信息
     */
    public void resumeExecution(String userInput) {
        AILogger.i(TAG, "Resuming execution with user input: " + userInput);
        
        ExecutionState prevState = executionState.getAndSet(ExecutionState.RESUMING);
        if (prevState != ExecutionState.PAUSED) {
            AILogger.w(TAG, "Cannot resume - not in PAUSED state");
            return;
        }
        
        // 验证用户输入
        if (!validateAndResume(userInput)) {
            // 验证失败，重置为 PAUSED 状态
            executionState.set(ExecutionState.PAUSED);
            return;
        }
        
        // 通知 UI 层即将恢复
        activity.runOnUiThread(() -> {
            if (callback != null) {
                callback.onExecutionResuming(userInput);
            }
        });
        
        // 解析用户输入并更新参数
        Map<String, Object> params = suspendedParams.get();
        String toolName = suspendedToolName.get();
        if (params != null && toolName != null) {
            // 使用 LLM 解析用户输入，更新参数
            parseUserInputAndUpdateParams(userInput, toolName, params);
        }
        
        // 释放锁，让执行继续
        pauseLatch.countDown();
    }
    
    /**
     * 验证用户输入并恢复执行
     * @param userInput 用户输入
     * @return 验证是否通过
     */
    private boolean validateAndResume(String userInput) {
        String toolName = suspendedToolName.get();
        Map<String, Object> params = suspendedParams.get();
        
        if (toolName == null || params == null) {
            AILogger.w(TAG, "No tool or params for validation");
            return false;
        }
        
        // 检查重试次数
        if (validationRetryCount.get() >= 3) {
            AILogger.w(TAG, "Validation retry limit reached");
            activity.runOnUiThread(() -> {
                if (callback != null) {
                    callback.onError("验证失败次数过多，任务已取消");
                }
            });
            executionState.set(ExecutionState.CANCELLED);
            pauseLatch.countDown();
            return false;
        }
        
        // 获取缺失的参数
        List<String> missingParams = checkMissingParams(toolName, params);
        if (missingParams.isEmpty()) {
            return true; // 所有参数都已提供
        }
        
        // 验证第一个缺失参数（假设用户只补充了一个参数）
        String paramName = missingParams.get(0);
        InputValidator.ValidationResult result = InputValidator.validateInput(toolName, paramName, userInput);
        
        // 通知 UI 验证结果
        String paramNameDisplay = getParamDisplayName(paramName);
        activity.runOnUiThread(() -> {
            if (callback != null) {
                callback.onInputValidationResult(paramNameDisplay, result);
            }
        });
        
        if (!result.valid) {
            // 验证失败
            validationRetryCount.incrementAndGet();
            AILogger.w(TAG, "Validation failed for " + paramName + ": " + result.errorMessage);
            
            String errorMsg = InputValidator.generateErrorMessage(result, paramNameDisplay);
            
            // 继续暂停
            return false;
        }
        
        // 验证成功，重置重试次数
        validationRetryCount.set(0);
        return true;
    }
    
    /**
     * 解析用户输入并更新参数
     */
    private void parseUserInputAndUpdateParams(String userInput, String toolName, Map<String, Object> params) {
        if (userInput == null || userInput.isEmpty()) {
            return;
        }
        
        List<String> missingParams = checkMissingParams(toolName, params);
        if (!missingParams.isEmpty()) {
            String firstMissing = missingParams.get(0);
            params.put(firstMissing, userInput);
            AILogger.i(TAG, "Updated param " + firstMissing + " with: " + userInput);
        }
        
        // 尝试解析更多参数
        parseAndMergeParams(userInput, params);
    }
    
    /**
     * 获取参数显示名称
     */
    private String getParamDisplayName(String paramName) {
        switch (paramName.toLowerCase()) {
            case "city":
            case "location":
                return "城市";
            case "time":
            case "datetime":
            case "start_time":
                return "时间";
            case "email":
                return "邮箱";
            case "phone":
                return "手机号";
            case "query":
            case "keyword":
                return "关键词";
            case "expression":
                return "表达式";
            default:
                return paramName;
        }
    }
    
    /**
     * 获取重试次数
     */
    public int getRetryCount() {
        return validationRetryCount.get();
    }
    
    /**
     * 取消暂停，直接完成
     */
    public void cancelPause() {
        AILogger.i(TAG, "Cancelling pause");
        executionState.set(ExecutionState.CANCELLED);
        pauseLatch.countDown();
    }
    
    /**
     * 等待用户输入（阻塞当前线程）
     */
    private void waitForUserInput() {
        pauseLatch.reset();
        try {
            // 等待最多 5 分钟
            pauseLatch.await(5, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            AILogger.w(TAG, "Wait interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 解析工具参数
     */
    private void parseAndMergeParams(String response, Map<String, Object> params) {
        try {
            // 简单解析：key=value,key2=value2
            String[] pairs = response.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().toLowerCase();
                    String value = kv[1].trim();
                    params.put(key, value);
                    AILogger.i(TAG, "Updated param: " + key + " = " + value);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to parse params: " + e.getMessage());
        }
    }
    
    /**
     * 解析工具调用参数字符串
     * @param arguments 参数字符串，可能是JSON格式
     * @return 参数Map
     */
    private Map<String, Object> parseToolArguments(String arguments) {
        Map<String, Object> params = new HashMap<>();
        
        if (arguments == null || arguments.trim().isEmpty()) {
            return params;
        }
        
        try {
            org.json.JSONObject json = new org.json.JSONObject(arguments.trim());
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                // 保留原始类型：数字、布尔值、字符串
                if (value instanceof org.json.JSONObject) {
                    // 嵌套对象转为字符串
                    params.put(key, value.toString());
                } else if (value instanceof org.json.JSONArray) {
                    // 数组转为字符串
                    params.put(key, value.toString());
                } else {
                    params.put(key, value);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to parse arguments as JSON: " + e.getMessage() + ", raw: " + arguments);
        }
        
        return params;
    }
    
    /**
     * 检查工具参数是否完整
     */
    private List<String> checkMissingParams(String toolName, Map<String, Object> params) {
        return ToolParameterValidator.checkMissingParams(toolName, params);
    }
    
    public void shutdown() {
        executor.shutdownNow();
    }
}
