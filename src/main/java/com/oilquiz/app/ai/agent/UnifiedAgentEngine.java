package com.oilquiz.app.ai.agent;

import android.app.Activity;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.python.PythonToolManager;
import com.oilquiz.app.ai.refactor.UnifiedContextManager;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.refactor.AIInferenceCore;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // 使用 ChatModeManager 的统一提示词，保持与普通模式一致
    private static String getDefaultGlobalPrompt() {
        return "你是一个AI助手，请用中文回答。";
    }
    
    private static String getDefaultSystemPrompt() {
        // 优先使用 ChatModeManager 的 Agent 模式提示词
        try {
            return ChatModeManager.getModeSystemPromptStatic(ChatModeManager.ChatMode.AGENT);
        } catch (Exception e) {
            return "你是一个智能Agent助手。你可以调用各种工具来完成用户的任务。\n" +
                   "请根据用户需求：\n1. 分析任务并分解步骤\n2. 选择合适的工具执行\n3. 整合结果并给出反馈\n" +
                   "可用工具包括：文件操作、网络搜索、数据库查询、位置服务、天气查询、翻译等。";
        }
    }
    
    private static String getDefaultNormalPrompt() {
        return "根据对话上下文和当前模式，以自然、友好的方式回应用户。";
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

    public interface AgentCallback {
        void onToken(String token);
        void onThinkingToken(String token);
        void onThinkingEnd();
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, AgentService.ToolResult result);
        void onStepUpdate(String step, String detail);
        void onComplete(String fullText);
        void onError(String error);
        
        /**
         * 需要更多用户信息时调用
         * @param missingInfo 缺失信息的描述
         * @param context 当前上下文/任务描述
         * @param suggestions 可能的补充建议
         */
        default void onNeedMoreInfo(String missingInfo, String context, java.util.List<String> suggestions) {}
        
        /**
         * 执行已暂停，等待用户输入
         */
        default void onExecutionPaused(String reason, String currentState) {}
        
        /**
         * 用户已提供信息，执行即将恢复
         */
        default void onExecutionResuming(String userInput) {}
        
        /**
         * 思考过程输出
         * @param thought 思考内容
         */
        default void onThinking(String thought) {}
        
        /**
         * 思考阶段变化
         * @param stage 当前阶段描述
         */
        default void onThinkingStage(String stage) {}
        
        /**
         * 输入验证结果
         * @param paramName 参数名称
         * @param result 验证结果
         */
        default void onInputValidationResult(String paramName, com.oilquiz.app.ai.agent.InputValidator.ValidationResult result) {}
    }

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

    private final List<String> contextSummary = Collections.synchronizedList(new ArrayList<>());
    private final List<com.oilquiz.app.ai.chat.ChatMessage> onlineModelConversationHistory = Collections.synchronizedList(new ArrayList<>());

    private ReasoningMode currentMode = ReasoningMode.AUTO;
    private int maxToolLoops = 5;
    private AgentCallback callback;
    private ServiceRouter serviceRouter;  // AIAgentEngine 兼容

    private static final int TOOL_RESULT_MAX_LENGTH = 1500;
    private static final int MAX_PROMPT_LENGTH = 8000;           // Prompt 最大长度
    private static final int MAX_CONTEXT_ENTRIES = 20;            // 上下文条目最大数量
    private static final int CONTEXT_TRUNCATE_THRESHOLD = 6000;   // 触发警告的阈值

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
        this.executor = Executors.newFixedThreadPool(EXECUTOR_THREAD_COUNT, r -> {
            Thread t = new Thread(r, "UnifiedAgent-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.serviceRouter = new ServiceRouter(this);
        createSession();
    }
    
    public void destroy() {
        executor.shutdownNow();
        clearContext();
        sessions.clear();
        callback = null;
        AILogger.i(TAG, "UnifiedAgentEngine destroyed");
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

    public ReasoningMode selectBestMode(SmartIntentRecognizer.IntentResult intent,
                                        SmartIntentRecognizer.MultiIntentResult multiIntent) {
        if (currentMode != ReasoningMode.AUTO) return currentMode;

        if (intent == null || intent.intent == null) {
            return ReasoningMode.DIRECT;
        }

        if (multiIntent != null && multiIntent.hasMultipleIntents()) {
            int toolIntentCount = 0;
            for (SmartIntentRecognizer.IntentItem item : multiIntent.intents) {
                if (item.intent.needsTool && item.confidence >= 0.3) {
                    toolIntentCount++;
                }
            }
            if (toolIntentCount >= 2) {
                return ReasoningMode.PLAN_EXECUTE;
            } else if (toolIntentCount == 1) {
                return ReasoningMode.REACT;
            }
        }

        if (intent.intent.needsTool && intent.confidence >= 0.4) {
            return ReasoningMode.REACT;
        }

        switch (intent.intent) {
            case CREATIVE:
            case ANALYSIS:
                return ReasoningMode.CHAIN_OF_THOUGHT;
            case LEARNING:
                if (intent.confidence >= 0.6) {
                    return ReasoningMode.CHAIN_OF_THOUGHT;
                }
                return ReasoningMode.DIRECT;
            case TRANSLATE:
            case CHAT:
            case UNKNOWN:
                return ReasoningMode.DIRECT;
            default:
                if (intent.intent.needsTool) {
                    return ReasoningMode.REACT;
                }
                return ReasoningMode.DIRECT;
        }
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

        contextSummary.add("用户: " + truncateForContext(message));

        executor.submit(() -> {
            try {
                if (isCancelled.get()) {
                    finishGeneration();
                    return;
                }

                // ========== Python 自动执行 ==========
                // 如果消息包含 Python 触发关键词，自动执行并注入结果
                if (shouldUsePython(message)) {
                    AILogger.i(TAG, "Auto-detected Python task: " + message);
                    String pythonResult = autoExecutePython(message);
                    if (pythonResult != null) {
                        injectPythonResult(pythonResult);
                    }
                }
                // =====================================

                SmartIntentRecognizer.IntentResult intent = analyzeIntentWithContext(message);
                if (intent == null || intent.intent == null) {
                    AILogger.w(TAG, "Intent result is null, using default CHAT mode");
                    intent = SmartIntentRecognizer.IntentResult.defaultResult();
                }

                SmartIntentRecognizer.MultiIntentResult multiIntent = intentRecognizer.recognizeMultiIntent(message);
                ReasoningMode mode = selectBestMode(intent, multiIntent);

                AILogger.i(TAG, "Execute: intent=" + intent.intent.id + " conf=" + intent.confidence
            + " mode=" + mode.name() + " needsTool=" + intent.needsTool()
            + " multiIntents=" + (multiIntent != null ? multiIntent.intents.size() : 0));

        // ========== 智能思考 ==========
        // 在执行前进行智能思考分析
        if (enableThinking && !intent.intent.id.equals("chat")) {
            String thinking = performThinking(message);
            if (thinking != null && !thinking.isEmpty()) {
                makeDecisionBasedOnThinking(thinking, message);
            }
        }
        // =============================

        String intentHint = buildIntentHint(intent, multiIntent, message);

                StringBuilder stepInfo = new StringBuilder();
                stepInfo.append(intent.intent.displayName).append(" (置信度:")
                    .append(String.format("%.0f%%", intent.confidence * 100)).append(")")
                    .append(" → ").append(mode.displayName);
                if (multiIntent != null && multiIntent.hasMultipleIntents()) {
                    stepInfo.append(" [多任务: ").append(multiIntent.intents.size()).append("]");
                }
                notifyStep("意图识别", stepInfo.toString());

                switch (mode) {
                    case REACT:
                        executeReActLoop(message + intentHint, maxTokens, enableThinking);
                        break;
                    case CHAIN_OF_THOUGHT:
                        executeCoTLoop(message + intentHint, maxTokens, enableThinking);
                        break;
                    case PLAN_EXECUTE:
                        executePlanLoop(message + intentHint, maxTokens, enableThinking, intent, multiIntent);
                        break;
                    case DIRECT:
                    default:
                        executeDirect(message + intentHint, maxTokens, enableThinking);
                        break;
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Error in execute: " + e.getMessage(), e);
                notifyError("执行失败: " + e.getMessage());
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
        
        StringBuilder sb = new StringBuilder();
        sb.append("[ReAct推理模式 - 严格遵循以下规则]\n\n");
        sb.append("=== 重要规则 ===\n");
        sb.append("1. 你是一个AI助手，可以使用工具来完成任务。\n");
        sb.append("2. 每次只能调用一个工具，等待工具返回结果后再决定下一步。\n");
        sb.append("3. 严格按照指定格式输出，否则系统无法解析你的回复。\n");
        sb.append("4. 当你有足够信息回答用户问题时，直接给出答案，不需要调用工具。\n\n");
        
        sb.append("=== 工具调用格式（必须严格遵循） ===\n");
        sb.append("当你需要调用工具时，只输出以下内容，不要有其他文字：\n\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
        sb.append("TOOLS_END\n\n");
        
        sb.append("=== 格式说明 ===\n");
        sb.append("- TOOLS_CALL 和 TOOLS_END 必须独占一行\n");
        sb.append("- 中间必须是有效的JSON格式，包含 name 和 arguments\n");
        sb.append("- arguments 必须是一个JSON对象\n");
        sb.append("- JSON格式必须严格正确，引号、逗号都不能少\n\n");
        
        sb.append("=== 正确示例 ===\n");
        sb.append("查询北京天气：\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"weather\", \"arguments\": {\"city\": \"北京\"}}\n");
        sb.append("TOOLS_END\n\n");
        
        sb.append("数学计算：\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"calculator\", \"arguments\": {\"expression\": \"3+5*2\"}}\n");
        sb.append("TOOLS_END\n\n");
        
        sb.append("=== 错误示例（不要这样做） ===\n");
        sb.append("❌ \"我需要调用weather工具来查询北京天气\"\n");
        sb.append("❌ 让我查一下天气...\n");
        sb.append("❌ TOOLS_CALL\n");
        sb.append("   name: weather\n");
        sb.append("   city: 北京\n");
        sb.append("   TOOLS_END（不是JSON格式）\n\n");
        
        sb.append("=== 最终答案格式 ===\n");
        sb.append("当你有足够信息后，直接用自然语言回答用户问题。\n");
        sb.append("答案要清晰、完整、有帮助。\n\n");
        
        sb.append("=== 可用工具列表 ===\n");
        sb.append(buildToolListPrompt());
        sb.append("\n=== 用户问题 ===\n");
        sb.append(userMessage);
        sb.append("\n\n现在请开始处理。如果需要调用工具，请严格按照 TOOLS_CALL 格式输出；如果可以直接回答，请直接给出答案。");
        return sb.toString();
    }

    private void handleReActToolCall(String responseText, int maxTokens) {
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
            if (isCancelled.get()) { finishGeneration(); return; }

            Map<String, Object> params = validateAndPrepareToolCall(call);
            if (params == null) {
                AILogger.w(TAG, "用户取消了工具调用: " + call.name);
                activity.runOnUiThread(() -> {
                    if (callback != null) {
                        callback.onToolCallComplete(call.name,
                            new AgentService.ToolResult(call.name, "用户取消", false));
                    }
                });
                if (isCancelled.get()) { finishGeneration(); return; }
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
                activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, new AgentService.ToolResult(call.name, "Tool execution failed", false)); });
                contextSummary.add("工具结果: 执行失败");
                String nextPrompt = "[继续] 工具调用失败，请尝试其他方式完成。";
                resetBuffers();
                executeReActLoop(nextPrompt, maxTokens, false);
                return;
            }
            activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, result); });
            boolean success = result.success;
            String resultStr = result.result;
            String toolResultStr = success ? resultStr : "工具执行失败: " + resultStr;
            contextSummary.add("工具结果: " + truncateForContext(toolResultStr, TOOL_RESULT_MAX_LENGTH));

            if (toolLoopCount.get() >= maxToolLoops) {
                String summary = buildToolResultsSummaryPrompt();
                resetBuffers();
                executeReActLoop(summary, maxTokens, false);
                return;
            }
            String nextPrompt = "[继续] 工具返回：\n" + (resultStr != null ? resultStr : "无结果") + "\n\n请基于以上结果继续回答。";
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
        
        sb.append("=== 可用工具 ===\n");
        sb.append(buildToolListPrompt());
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
                                callback.onToolCallComplete(call.name,
                                    new AgentService.ToolResult(call.name, "用户取消", false));
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
                        activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, new AgentService.ToolResult(call.name, "Tool execution failed", false)); });
                        if (isCancelled.get()) { finishGeneration(); return; }
                        contextSummary.add("工具结果: 执行失败");
                        String nextPrompt = "[继续] 工具调用失败，请尝试其他方式完成。";
                        resetBuffers();
                        executeDirect(nextPrompt, maxTokens, false);
                        return;
                    }
                    activity.runOnUiThread(() -> { if (callback != null) callback.onToolCallComplete(call.name, result); });
                    if (isCancelled.get()) { finishGeneration(); return; }
                    boolean success = result.success;
                    String resultStr = result.result;
                    contextSummary.add("工具结果: " + truncateForContext(resultStr != null ? resultStr : "无结果", TOOL_RESULT_MAX_LENGTH));
                    if (toolLoopCount.get() >= maxToolLoops) {
                        String summary = buildToolResultsSummaryPrompt();
                        resetBuffers();
                        executeDirect(summary, maxTokens, false);
                        return;
                    }
                    String nextPrompt = "[继续] 工具返回：\n" + (resultStr != null ? resultStr : "无结果") + "\n\n请基于以上结果继续回答。";
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

        StringBuilder sb = new StringBuilder();
        sb.append("[链式思维模式]\n\n");
        sb.append("=== 思考步骤 ===\n");
        sb.append("1. 分析问题的核心和关键点\n");
        sb.append("2. 识别需要的信息和知识\n");
        sb.append("3. 逐步推理，展示思考过程\n");
        sb.append("4. 基于推理结果，决定是否需要使用工具\n\n");
        sb.append("=== 工具调用 ===\n");
        sb.append("如需工具，按以下格式调用：\n\n");
        sb.append("   TOOLS_CALL\n");
        sb.append("   {\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
        sb.append("   TOOLS_END\n\n");
        sb.append("=== 可用工具 ===\n");
        sb.append(buildToolListPrompt());
        sb.append("\n=== 用户问题 ===\n");
        sb.append(userMessage);
        sb.append("\n\n请开始链式思维推理：");
        return sb.toString();
    }

    private void handleCoTToolCall(String responseText, int maxTokens) {
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
                        callback.onToolCallComplete(call.name,
                            new AgentService.ToolResult(call.name, "用户取消", false));
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
                if (callback != null) callback.onToolCallComplete(call.name, result);
            });

            if (isCancelled.get()) { finishGeneration(); return; }

            boolean success = result != null && result.success;
            String resultStr = result != null && result.result != null ? result.result : "工具执行返回null";
            String toolResultStr = success ? resultStr : "工具执行失败: " + resultStr;
            contextSummary.add("工具结果: " + truncateForContext(toolResultStr, TOOL_RESULT_MAX_LENGTH));

            if (toolLoopCount.get() >= maxToolLoops) {
                String summary = buildToolResultsSummaryPrompt();
                resetBuffers();
                executeCoTLoop(summary, maxTokens, false);
                return;
            }

            String nextPrompt = "[继续] 工具返回：\n" + (resultStr != null ? resultStr : "无结果") + "\n\n请基于以上结果继续推理。";
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
        sb.append(buildToolListPrompt());
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
                        callback.onToolCallComplete(call.name,
                            new AgentService.ToolResult(call.name, "用户取消", false));
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
                if (callback != null) callback.onToolCallComplete(call.name, result);
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
                AILogger.e(TAG, "Online model generation error: " + error);
                finishGeneration();
                notifyError(error);
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
                
                AILogger.e(TAG, "Local model chatSend error: " + error);
                finishGeneration();
                notifyError(error);
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
                return new AgentService.ToolResult(validatedCall.name, "用户取消执行", false);
            }

            try {
                // 使用 Future 实现超时控制
                java.util.concurrent.Future<AgentService.ToolResult> future =
                    executor.submit(() -> agentService.executeTool(validatedCall));

                AgentService.ToolResult result = future.get(TOOL_EXECUTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

                if (result != null) {
                    long executionTime = System.currentTimeMillis() - startTime;
                    AILogger.i(TAG, "工具 " + validatedCall.name + " 执行成功，耗时: " + executionTime + "ms");
                    return result;
                } else {
                    AILogger.e(TAG, "工具 " + validatedCall.name + " 返回 null");
                    return new AgentService.ToolResult(validatedCall.name, "工具返回null", false);
                }

            } catch (java.util.concurrent.TimeoutException e) {
                AILogger.w(TAG, "工具 " + validatedCall.name + " 执行超时 (" + TOOL_EXECUTION_TIMEOUT_MS + "ms)，重试 " + (retryCount + 1) + "/" + TOOL_EXECUTION_MAX_RETRIES);
                retryCount++;

                if (retryCount > TOOL_EXECUTION_MAX_RETRIES) {
                    return new AgentService.ToolResult(validatedCall.name,
                        "工具执行超时，已重试" + TOOL_EXECUTION_MAX_RETRIES + "次", false);
                }

                // 短暂延迟后重试
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new AgentService.ToolResult(validatedCall.name, "执行被中断", false);
                }

            } catch (Exception e) {
                AILogger.e(TAG, "工具 " + validatedCall.name + " 执行异常: " + e.getMessage(), e);
                return new AgentService.ToolResult(validatedCall.name,
                    "工具执行异常: " + e.getMessage(), false);
            }
        }

        return new AgentService.ToolResult(validatedCall.name, "工具执行失败", false);
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
        contextSummary.clear();
        onlineModelConversationHistory.clear();
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
            if (contextSummary.size() > MAX_CONTEXT_ENTRIES) {
                int toRemove = contextSummary.size() - MAX_CONTEXT_ENTRIES;
                for (int i = 0; i < toRemove; i++) {
                    contextSummary.remove(0);
                }
                AILogger.w(TAG, "Context trimmed: removed " + toRemove + " entries, remaining: " + contextSummary.size());
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
        AILogger.e(TAG, "Error: " + error);
        activity.runOnUiThread(() -> { if (callback != null) callback.onError(error); });
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
        activity.runOnUiThread(() -> {
            if (callback != null) {
                callback.onThinking(thought);
            }
        });
    }
    
    /**
     * 通知 UI 思考阶段
     */
    private void notifyThinkingStage(String stage) {
        activity.runOnUiThread(() -> {
            if (callback != null) {
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
        
        // 尝试JSON解析
        try {
            // 简单的JSON解析（对于复杂JSON可能需要更完善的解析器）
            if (arguments.startsWith("{") && arguments.endsWith("}")) {
                String content = arguments.substring(1, arguments.length() - 1);
                String[] pairs = content.split(",");
                for (String pair : pairs) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replace("\"", "");
                        String value = kv[1].trim().replace("\"", "");
                        params.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to parse arguments as JSON: " + e.getMessage());
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
