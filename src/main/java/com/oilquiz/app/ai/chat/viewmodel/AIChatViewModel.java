package com.oilquiz.app.ai.chat.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.oilquiz.app.ai.ChatScene;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.live.MutableChatList;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.refactor.AIInferenceCore;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * AIChatViewModel - AI对话视图模型
 * 
 * 功能：
 * 1. 管理聊天消息数据
 * 2. 处理消息发送和接收
 * 3. 管理模型状态
 * 4. 处理生成状态
 * 5. 自动保存历史记录
 */
@HiltViewModel
public class AIChatViewModel extends AndroidViewModel {

    private static final String TAG = "AIChatViewModel";

    // 核心服务（通过 Hilt 注入）
    @Inject
    public AIService aiService;
    @Inject
    public InferenceRouter inferenceRouter;
    @Inject
    public OnlineModelManager onlineModelManager;
    @Inject
    public ChatHistoryManager chatHistoryManager;
    @Inject
    public AIConfig aiConfig;
    @Inject
    public ExecutorService executor;

    // 数据源
    private final MutableChatList chatMessages = new MutableChatList();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 状态
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private String currentModelName;
    private boolean isUsingOnlineModel;

    // 组2.5：场景隔离 —— 每个页面各 new 自己的 ViewModel，通过 setScene 设置场景
    // 默认 AI_CHAT，错题解析页设为 QUIZ_EXPLAIN，互不串历史
    private String scene = ChatScene.AI_CHAT;

    // ========== 状态机：统一 UI↔Service 状态契约 ==========
    /**
     * AI 状态机枚举 —— 覆盖从初始化到推理到回收的全生命周期
     *
     * 状态迁移路径：
     *   IDLE ──init()──▶ LOADING ──加载完成──▶ READY
     *                                         │
     *         ▲                                │generateStream()
     *         │                                ▼
     *         │         ◄────onComplete/onError──── INFERRING
     *         │                                │
     *         │    内存不足/手动卸载            │崩溃/信号
     *         │                                ▼
     *         └────────── UNLOADED ◀────────── ERROR
     */
    public enum AIState {
        /** 空闲：尚未初始化（初始态），等待 initialize() */
        IDLE,
        /** 加载中：模型文件准备/加载/GPU初始化 */
        LOADING,
        /** 就绪：初始化完成，可以接受推理请求（空闲态之一，语义=IDLE 已就绪） */
        READY,
        /** 推理中：正在执行 generateStream */
        INFERRING,
        /** 错误：初始化失败/推理崩溃，附带错误详情 */
        ERROR,
        /** 已卸载：因内存压力或手动卸载，需要重新加载 */
        UNLOADED
    }

    // ========== 状态机合法转移表（业内标准：非法迁移拒绝并告警） ==========
    private static final java.util.Set<String> VALID_TRANSITIONS = new java.util.HashSet<>();

    static {
        VALID_TRANSITIONS.add("IDLE->LOADING");
        VALID_TRANSITIONS.add("IDLE->INFERRING");
        VALID_TRANSITIONS.add("IDLE->ERROR");
        VALID_TRANSITIONS.add("IDLE->UNLOADED");
        VALID_TRANSITIONS.add("LOADING->READY");
        VALID_TRANSITIONS.add("LOADING->ERROR");
        VALID_TRANSITIONS.add("LOADING->UNLOADED");
        VALID_TRANSITIONS.add("READY->INFERRING");
        VALID_TRANSITIONS.add("READY->ERROR");
        VALID_TRANSITIONS.add("READY->UNLOADED");
        VALID_TRANSITIONS.add("INFERRING->READY");
        VALID_TRANSITIONS.add("INFERRING->ERROR");
        VALID_TRANSITIONS.add("INFERRING->UNLOADED");
        VALID_TRANSITIONS.add("ERROR->LOADING");   // 失败后重试初始化
        VALID_TRANSITIONS.add("ERROR->READY");     // 错误后人工恢复
        VALID_TRANSITIONS.add("ERROR->UNLOADED");
        VALID_TRANSITIONS.add("UNLOADED->LOADING"); // 重新加载
        VALID_TRANSITIONS.add("UNLOADED->READY");
    }

    /** 结构化错误信息 —— UI 可以根据类型渲染不同卡片 */
    public static class AIError {
        public final String type;       // "INIT" / "INFERENCE" / "MEMORY" / "NATIVE_CRASH" / "TIMEOUT" / "CANCELLED"
        public final String message;    // 可读错误描述
        public final boolean retryable; // 是否可重试
        public final long timestamp;

        public AIError(String type, String message, boolean retryable) {
            this.type = type;
            this.message = message;
            this.retryable = retryable;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "AIError{type=" + type + ", msg=" + message + ", retry=" + retryable + "}";
        }
    }

    /** 推理进度信息 */
    public static class InferenceProgress {
        public final long elapsedMs;
        public final int tokenCount;
        public final long charCount;
        public final String phase; // "started" / "generating" / "thinking" / "complete"

        public InferenceProgress(long elapsedMs, int tokenCount, long charCount, String phase) {
            this.elapsedMs = elapsedMs;
            this.tokenCount = tokenCount;
            this.charCount = charCount;
            this.phase = phase;
        }
    }

    // ========== 状态机 LiveData ==========
    private final MutableLiveData<AIState> aiStateLiveData = new MutableLiveData<>(AIState.IDLE);
    private final MutableLiveData<AIError> aiErrorLiveData = new MutableLiveData<>();
    private final MutableLiveData<InferenceProgress> inferenceProgressLiveData = new MutableLiveData<>();

    // Watchdog：推理超时检测
    private static final long INFERENCE_TIMEOUT_MS = 20_000L; // 20秒无心跳则超时
    private long inferenceStartTime = 0;
    private long lastHeartbeatTime = 0;
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            AIState current = aiStateLiveData.getValue();
            if (current == AIState.INFERRING) {
                long elapsed = System.currentTimeMillis() - lastHeartbeatTime;
                if (elapsed > INFERENCE_TIMEOUT_MS) {
                    AILogger.w(TAG, "Watchdog timeout: no heartbeat for " + elapsed + "ms during inference");
                    setState(AIState.ERROR);
                    aiErrorLiveData.postValue(new AIError("TIMEOUT",
                            "AI 响应超时（" + (elapsed / 1000) + " 秒无新输出），可能模型计算较慢或已卡死",
                            true));
                    // 强制取消
                    try { aiService.stopGeneration(); } catch (Exception ignored) {}
                    mainHandler.postDelayed(this, INFERENCE_TIMEOUT_MS);
                } else {
                    mainHandler.postDelayed(this, 5_000); // 每5秒检查一次
                }
            }
        }
    };

    /**
     * 统一状态迁移入口 —— 所有状态变更必须通过此方法
     * 自动 post 到主线程 LiveData，并记录日志；非法迁移按转移表拒绝并告警
     */
    public void setState(AIState newState) {
        AIState old = aiStateLiveData.getValue();
        if (old == newState) return;
        // 合法转移校验：非法迁移拒绝并告警（业内标准转移表）
        String transitionKey = old.name() + "->" + newState.name();
        if (!VALID_TRANSITIONS.contains(transitionKey)) {
            AILogger.w(TAG, "AIState 非法迁移被拒绝: " + transitionKey);
            return;
        }
        AILogger.i(TAG, "AIState: " + old + " → " + newState);
        aiStateLiveData.postValue(newState);

        // 状态迁移副作用
        switch (newState) {
            case INFERRING:
                inferenceStartTime = System.currentTimeMillis();
                lastHeartbeatTime = inferenceStartTime;
                mainHandler.removeCallbacks(watchdogRunnable);
                mainHandler.postDelayed(watchdogRunnable, INFERENCE_TIMEOUT_MS);
                break;
            case IDLE:
            case READY:
            case UNLOADED:
            case ERROR:
                mainHandler.removeCallbacks(watchdogRunnable);
                break;
        }
    }

    /** 推送心跳 —— 推理线程每收到 token 时调用，重置 watchdog */
    public void pushHeartbeat(int tokenCount, long charCount, String phase) {
        lastHeartbeatTime = System.currentTimeMillis();
        long elapsed = lastHeartbeatTime - inferenceStartTime;
        inferenceProgressLiveData.postValue(new InferenceProgress(elapsed, tokenCount, charCount, phase));
    }

    /** 获取当前 AI 状态 */
    public AIState getCurrentState() {
        AIState s = aiStateLiveData.getValue();
        return s != null ? s : AIState.IDLE;
    }

    // LiveData
    private final MutableLiveData<String> modelNameLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> generatingStateLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> initializationLiveData = new MutableLiveData<>(false);
    /** 在线思考实时流：postValue 增量 token；postValue(null) 表示思考结束（顶部单行据此显示/隐藏） */
    private final MutableLiveData<String> onlineThinkingStream = new MutableLiveData<>();

    // 流式生成状态
    private StringBuilder currentStreamingContent;
    private StringBuilder currentThinkingContent;
    private boolean isInThinking = false;
    /** 本轮是否已实时收到 thinking 增量事件：思考区已实时累积，reasoning 全文跳过防重复 */
    private volatile boolean thinkingStreamedLocal = false;
    private int currentStreamingMessageIndex = -1;

    @Inject
    public AIChatViewModel(@NonNull Application application) {
        super(application);
    }

    // ========== 组2.5：场景隔离 ==========

    public String getScene() {
        return scene;
    }

    /**
     * 设置当前场景（必须在 initialize() 之前调用）。
     * 不同页面设置不同场景，DB 查询和上下文构建都会按场景过滤。
     */
    public void setScene(String scene) {
        this.scene = ChatScene.normalize(scene);
        AILogger.i(TAG, "Scene set to: " + this.scene);
    }

    // ========== 初始化 ==========

    /**
     * 初始化 ViewModel（依赖已通过 Hilt 注入）
     * 状态机：IDLE → LOADING → READY/ERROR
     */
    public void initialize() {
        if (isInitialized.get()) return;

        setState(AIState.LOADING);

        try {
        executor.execute(() -> {
            try {
                // 同步在线模型（可能在本地服务检查前就有可用的在线配置）
                syncOnlineModels();

                // 本地 AI 服务检查：在线模型可用时不视为失败（本地/在线解绑）
                if (aiService == null) {
                    boolean online = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
                    // NPU（GenieX）引擎也不需要 llama.cpp 的那套本地服务：它自己管模型加载
                    boolean npu = inferenceRouter != null && inferenceRouter.isNpuEngineEnabled();
                    if (!online && !npu) {
                        mainHandler.post(() -> {
                            errorLiveData.setValue("未选择任何模型");
                            setState(AIState.ERROR);
                            aiErrorLiveData.postValue(new AIError("INIT", "未选择任何模型（本地/在线/NPU）", false));
                        });
                        return;
                    }
                }

                // 更新模型状态
                updateModelState();

                // 加载聊天历史
                loadChatHistory();

                isInitialized.set(true);
                mainHandler.post(() -> {
                    initializationLiveData.setValue(true);
                    modelNameLiveData.setValue(currentModelName);
                    setState(AIState.READY);
                });

                AILogger.i(TAG, "ViewModel initialized successfully");

            } catch (Exception e) {
                AILogger.e(TAG, "Failed to initialize ViewModel", e);
                mainHandler.post(() -> {
                    errorLiveData.setValue("初始化失败: " + e.getMessage());
                    setState(AIState.ERROR);
                    aiErrorLiveData.postValue(new AIError("INIT", "初始化失败: " + e.getMessage(), true));
                });
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.e(TAG, "Executor rejected initialization task", e);
            mainHandler.post(() -> {
                errorLiveData.setValue("初始化失败: 线程池已关闭");
                setState(AIState.ERROR);
                aiErrorLiveData.postValue(new AIError("INIT", "初始化失败: 线程池已关闭", false));
            });
        }
    }

    private void syncOnlineModels() {
        if (onlineModelManager == null) return;
        try {
            int imported = onlineModelManager.importFromAPIKeyManager();
            if (imported > 0) {
                AILogger.i(TAG, "Synced " + imported + " online model configs");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to sync online models: " + e.getMessage());
        }
    }

    private void updateModelState() {
        if (inferenceRouter != null) {
            currentModelName = inferenceRouter.getCurrentModelName();
            isUsingOnlineModel = inferenceRouter.isUsingOnlineModel();
        } else if (aiService != null) {
            currentModelName = aiService.getCurrentModelName();
            isUsingOnlineModel = false;
        }
    }

    private void loadChatHistory() {
        if (chatHistoryManager == null) return;
        try {
            List<ChatMessage> loaded = chatHistoryManager.loadAIChatHistory();
            if (loaded != null && !loaded.isEmpty()) {
                // NPU（GenieX）引擎下，历史里的"本地服务初始化失败 / 模型文件不存在 / 自动恢复失败"
                // 全是伪故障（本地那份模型没下载而已，NPU 推理并不需要它）→ 加载时直接过滤掉，
                // 免得用户每次进来都看到"AI服务初始化失败"以为 App 坏了。
                final boolean npuEngineOn = inferenceRouter != null && inferenceRouter.isNpuEngineEnabled();
                int dropped = 0;
                for (ChatMessage m : loaded) {
                    if (npuEngineOn && isStaleLocalServiceError(m)) {
                        dropped++;
                        continue;
                    }
                    chatMessages.add(m);
                }
                if (dropped > 0) {
                    AILogger.i(TAG, "已过滤 " + dropped + " 条本地服务失败历史（NPU 引擎下与推理无关）");
                }
                AILogger.i(TAG, "Loaded " + chatMessages.size() + " chat messages");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to load chat history: " + e.getMessage());
        }
    }

    /** 是否是"本地 llama.cpp 服务"的历史噪声消息（NPU 引擎下应过滤） */
    private boolean isStaleLocalServiceError(ChatMessage m) {
        String c = (m == null) ? null : m.content;
        if (c == null || c.isEmpty()) return false;
        return c.contains("AI服务初始化失败")
                || c.contains("Failed to locate model file")
                || c.contains("模型文件不存在")
                || c.contains("自动恢复失败")
                || c.contains("正在恢复AI服务")
                || c.contains("检测到AI模型状态异常");
    }

    // ========== 消息操作 ==========

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        if (content == null || content.isEmpty()) return;

        ChatMessage message = ChatMessage.createUserMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            System.currentTimeMillis()
        );
        chatMessages.add(message);
        saveHistoryAsync();
    }

    /**
     * 添加 AI 消息
     */
    public void addAIMessage(String content) {
        if (content == null) content = "";

        ChatMessage message = ChatMessage.createAIMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            System.currentTimeMillis(),
            null, 0, 0
        );
        chatMessages.add(message);
        saveHistoryAsync();
    }

    /**
     * 添加系统消息
     */
    public void addSystemMessage(String content) {
        if (content == null || content.isEmpty()) return;

        ChatMessage message = ChatMessage.createSystemMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            ChatMessage.SystemMessageType.INFO,
            System.currentTimeMillis()
        );
        chatMessages.add(message);
    }

    /**
     * 添加错误消息
     */
    public void addErrorMessage(String title, String detail, boolean retryable) {
        ChatMessage message = ChatMessage.createErrorMessage(title, detail, retryable);
        chatMessages.add(message);
    }

    /**
     * 清空聊天历史
     */
    public void clearChatHistory() {
        chatMessages.clear();
        if (chatHistoryManager != null) {
            try {
                executor.execute(() -> chatHistoryManager.clearAIChatHistory());
            } catch (java.util.concurrent.RejectedExecutionException e) {
                AILogger.w(TAG, "Executor rejected clear history task: " + e.getMessage());
            }
        }
        if (aiService != null) {
            aiService.chatClear();
        }
    }

    // ========== 消息发送 ==========

    /**
     * 发送消息
     */
    public void sendMessage(String content) {
        if (content == null || content.isEmpty()) return;
        if (isGenerating.get()) {
            mainHandler.post(() -> errorLiveData.setValue("正在生成中，请稍候"));
            return;
        }

        // 添加用户消息
        addUserMessage(content);

        // 开始生成
        startGeneration(content);
    }

    private void startGeneration(String message) {
        isGenerating.set(true);
        mainHandler.post(() -> generatingStateLiveData.setValue(true));
        setState(AIState.INFERRING);  // 状态机：进入推理中

        // 创建 AI 消息占位
        String messageId = java.util.UUID.randomUUID().toString();
        ChatMessage aiMessage = ChatMessage.createAIMessage(messageId, "", System.currentTimeMillis(), null, 0, 0);
        aiMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
        aiMessage.status = ChatMessage.MessageStatus.GENERATING;
        chatMessages.add(aiMessage);
        currentStreamingMessageIndex = chatMessages.size() - 1;

        // 初始化流式内容
        currentStreamingContent = new StringBuilder();
        currentThinkingContent = new StringBuilder();

        // 立即推送一次心跳，phase=started
        pushHeartbeat(0, 0, "started");

        // 根据模型类型选择推理方式
        if (isUsingOnlineModel && inferenceRouter != null) {
            startOnlineInference(message);
        } else if (aiService != null) {
            startLocalInference(message);
        } else {
            mainHandler.post(() -> {
                errorLiveData.setValue("AI服务未初始化");
                setState(AIState.ERROR);
                aiErrorLiveData.postValue(new AIError("INFERENCE", "AI服务未初始化，无法执行推理", true));
                endGeneration();
            });
        }
    }

    private void startOnlineInference(String message) {
        try {
        executor.execute(() -> {
            try {
                AIInferenceCore.InferenceConfig config = new AIInferenceCore.InferenceConfig();
                config.maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 8192;
                config.temperature = 0.7f;
                // 深度思考开关：跟随 UI 独立开关（ChatModeManager 持久化），在线对话请求侧生效，
                // reasoning_content 增量经 onThinkingToken 分流思考区、content 分流回复区
                try {
                    config.enableThinking = com.oilquiz.app.ai.chat.ChatModeManager
                            .getInstance(getApplication()).isDeepThinkingEnabled();
                } catch (Exception e) {
                    config.enableThinking = false;
                }

                // 构建历史上下文
                List<ChatMessage> history = new ArrayList<>();
                for (ChatMessage msg : chatMessages.toImmutableList()) {
                    if (msg.type == ChatMessage.MessageType.USER || msg.type == ChatMessage.MessageType.AI) {
                        history.add(msg);
                    }
                }
                if (history.size() > 20) {
                    history = history.subList(history.size() - 20, history.size());
                }
                config.history = history;

                inferenceRouter.generateStream(message, config, new StreamCallback() {
                    @Override
                    public void onStart() {
                        updateInferencePhase(ChatMessage.InferencePhase.ENCODING);
                    }

                    @Override
                    public void onToken(String token) {
                        handleStreamingToken(token);
                    }

                    @Override
                    public void onThinkingToken(String token) {
                        // 在线思考：气泡思考区（handleThinkingToken 同步 msg.thinkingContent）
                        // + 顶部单行实时流（增量 token，null 由 onComplete 发）
                        if (token != null && !token.isEmpty()) {
                            handleThinkingToken(token);
                            onlineThinkingStream.postValue(token);
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        completeGeneration(fullText);
                        onlineThinkingStream.postValue(null);   // 思考结束，顶部单行隐藏
                    }

                    @Override
                    public void onError(String error) {
                        // 在线模型失败：本地模型已加载时提示可切换，避免用户以为 AI 不可用
                        String err = error;
                        if (isAIServiceInitialized()) {
                            err = (error == null ? "" : error)
                                    + "（在线模型失败，本地模型已就绪，可切换后重试）";
                        }
                        handleGenerationError(err);
                        onlineThinkingStream.postValue(null);
                    }
                });
            } catch (Exception e) {
                AILogger.e(TAG, "Online inference failed", e);
                mainHandler.post(() -> handleGenerationError("推理失败: " + e.getMessage()));
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.e(TAG, "Executor rejected online inference task", e);
            handleGenerationError("线程池已关闭，请重启应用");
        }
    }

    private void startLocalInference(String message) {
        try {
        thinkingStreamedLocal = false;
        executor.execute(() -> {
            try {
                int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 1024;
                // 普通对话改用 chatJson 统一协议（与 Agent 同源）：
                // C++ 侧完成模板格式化 + 思考剥离 + reasoning 事件广播 + 干净正文 complete；
                // Java 侧 token->正文、reasoning->思考区（折叠显示）、complete->终态。
                String requestJson = buildChatJsonRequest(message, maxTokens);
                if (requestJson == null) {
                    handleGenerationError("构建推理请求失败");
                    return;
                }
                LlamaHelper.chatJson(requestJson, new LlamaHelper.JsonCallback() {
                    @Override
                    public void onJson(String json) {
                        try {
                            org.json.JSONObject event = new org.json.JSONObject(json);
                            String type = event.optString("type", "");
                            switch (type) {
                                case "token": {
                                    String token = event.optString("content", "");
                                    if (!token.isEmpty()) handleStreamingToken(token);
                                    break;
                                }
                                case "thinking": {
                                    String tk = event.optString("content", "");
                                    if (!tk.isEmpty()) {
                                        thinkingStreamedLocal = true;
                                        handleThinkingToken(tk);
                                    }
                                    break;
                                }
                                case "reasoning": {
                                    // 思考已实时累积（thinkingStreamedLocal）则全文跳过防重复
                                    if (thinkingStreamedLocal) break;
                                    String reasoning = event.optString("content", "");
                                    if (!reasoning.isEmpty()) handleThinkingToken(reasoning);
                                    break;
                                }
                                case "complete": {
                                    String content = event.optString("content", "");
                                    completeGeneration(content);
                                    break;
                                }
                                case "error": {
                                    String err = event.optString("message", "未知错误");
                                    handleGenerationError(err);
                                    break;
                                }
                                default:
                                    // meta 等事件暂不处理
                                    break;
                            }
                        } catch (Exception e) {
                            AILogger.e(TAG, "chatJson event parse error: " + e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                AILogger.e(TAG, "Local inference failed", e);
                handleGenerationError("推理失败: " + e.getMessage());
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.e(TAG, "Executor rejected local inference task", e);
            handleGenerationError("线程池已关闭，请重启应用");
        }
    }

    /**
     * 构造普通对话的 chatJson 请求：无工具（tool_choice=none）、启用思考（enable_thinking=true，
     * 思考经 reasoning 事件折叠显示）。历史取 chatMessages 中 USER/AI 消息最近 20 条 + 当前消息。
     */
    private String buildChatJsonRequest(String message, int maxTokens) {
        try {
            org.json.JSONObject req = new org.json.JSONObject();
            req.put("action", "chat");
            org.json.JSONArray msgs = new org.json.JSONArray();
            // 注入当前日期（权威事实）：防止模型用训练截止时间回答"今天几号/最新"类问题
            try {
                org.json.JSONObject sys = new org.json.JSONObject();
                sys.put("role", "system");
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "yyyy年M月d日 EEEE", java.util.Locale.CHINA);
                sys.put("content", "当前日期：" + sdf.format(new java.util.Date())
                        + "。这是系统实时提供的当前时间，回答今天/几号/当前时间/最新等问题以它为准，不要使用训练数据中的旧时间。");
                msgs.put(sys);
            } catch (Exception ignored) {}
            int historyEnd = currentStreamingMessageIndex >= 0 ? currentStreamingMessageIndex : chatMessages.size();
            List<ChatMessage> snapshot = chatMessages.toImmutableList();
            int start = Math.max(0, historyEnd - 20);
            for (int i = start; i < historyEnd; i++) {
                ChatMessage m = snapshot.get(i);
                if (m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI) {
                    org.json.JSONObject msg = new org.json.JSONObject();
                    msg.put("role", m.getRole());
                    msg.put("content", m.content != null ? m.content : "");
                    msgs.put(msg);
                }
            }
            org.json.JSONObject cur = new org.json.JSONObject();
            cur.put("role", "user");
            cur.put("content", message);
            msgs.put(cur);
            req.put("messages", msgs);
            req.put("enable_thinking", true);
            req.put("max_tokens", maxTokens);
            req.put("temperature", 0.6f);
            req.put("top_p", 0.9f);
            req.put("top_k", 40);
            req.put("tool_choice", "none");
            return req.toString();
        } catch (Exception e) {
            AILogger.e(TAG, "buildChatJsonRequest failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * chatJson reasoning 事件：思考内容实时写入思考区（UI 折叠显示）。
     * 与旧 chatSend 的 [THINK_BEGIN]/[THINK_END] 标记协议不同——chatJson 走 reasoning 事件直接追加。
     */
    private void handleThinkingToken(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) return;
        if (!isInThinking) {
            isInThinking = true;
            if (currentThinkingContent == null) {
                currentThinkingContent = new StringBuilder();
            }
        }
        currentThinkingContent.append(reasoning);
        mainHandler.post(this::updateStreamingMessage);
    }

    // ========== 流式处理 ==========

    private void handleStreamingToken(String token) {
        if (token == null || token.isEmpty()) return;

        // 处理思考标签
        if (token.equals("[THINK_BEGIN]")) {
            isInThinking = true;
            pushHeartbeat(0, currentStreamingContent.length(), "thinking");
            return;
        }
        if (token.equals("[THINK_END]")) {
            isInThinking = false;
            updateMessageThinkingContent();
            pushHeartbeat(0, currentStreamingContent.length(), "generating");
            return;
        }

        // 处理内容
        if (isInThinking) {
            currentThinkingContent.append(token);
        } else {
            currentStreamingContent.append(token);
        }

        // 每收到 token 推送心跳 —— 重置 watchdog
        int totalTokens = currentStreamingContent.length() + currentThinkingContent.length();
        pushHeartbeat(totalTokens, currentStreamingContent.length(), isInThinking ? "thinking" : "generating");

        // 更新 UI
        mainHandler.post(this::updateStreamingMessage);
    }

    private void updateStreamingMessage() {
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatMessages.size()) {
            return;
        }

        ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
        if (currentStreamingContent.length() > 0) {
            msg.content = currentStreamingContent.toString();
        }
        if (currentThinkingContent.length() > 0) {
            msg.thinkingContent = currentThinkingContent.toString();
        }
        msg.status = ChatMessage.MessageStatus.GENERATING;

        chatMessages.notifyItemChanged(currentStreamingMessageIndex);
    }

    private void updateMessageThinkingContent() {
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatMessages.size()) {
            return;
        }

        ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
        msg.thinkingContent = currentThinkingContent.toString();
        chatMessages.notifyItemChanged(currentStreamingMessageIndex);
    }

    private void updateInferencePhase(ChatMessage.InferencePhase phase) {
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatMessages.size()) {
            return;
        }

        ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
        msg.inferenceProgress = new ChatMessage.InferenceProgress(phase);
        chatMessages.notifyItemChanged(currentStreamingMessageIndex);
    }

    private void completeGeneration(String fullText) {
        mainHandler.post(() -> {
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatMessages.size()) {
                ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
                // 终态正文优先用流式累积的干净 content；不能用 native onComplete 的 fullText 直接覆盖——
                // 它含原始 <think>…</think> 思考段（本路径只认 [THINK_BEGIN]/[THINK_END] 协议标记，
                // 模型直接输出的标签会漏分），直接赋值会把思考永久绑进主消息气泡。
                // 统一再走一遍模板标签剥离兜底：正文干净时 stripThinking 是无操作，不改变行为。
                String body = currentStreamingContent.length() > 0
                        ? currentStreamingContent.toString()
                        : (fullText != null ? fullText : "");
                msg.content = com.oilquiz.app.ai.jni.LlamaHelper.getThinkingTags().stripThinking(body);
                msg.status = ChatMessage.MessageStatus.COMPLETED;
                msg.inferenceProgress = null;
                chatMessages.notifyItemChanged(currentStreamingMessageIndex);
            }

            pushHeartbeat(fullText != null ? fullText.length() : currentStreamingContent.length(),
                    currentStreamingContent.length(), "complete");
            setState(AIState.READY);  // 状态机：推理完成，回到就绪
            endGeneration();
            saveHistoryAsync();
        });
    }

    private void handleGenerationError(String error) {
        mainHandler.post(() -> {
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatMessages.size()) {
                ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
                msg.content = error != null ? "生成失败: " + error : "生成失败";
                msg.status = ChatMessage.MessageStatus.FAILED;
                msg.inferenceProgress = null;
                chatMessages.notifyItemChanged(currentStreamingMessageIndex);
            }

            // 状态机：推理错误
            setState(AIState.ERROR);

            // 判断错误类型
            String errMsg = error != null ? error : "未知错误";
            String errType = "INFERENCE";
            boolean retryable = true;
            if (errMsg.contains("超时") || errMsg.contains("timeout")) {
                errType = "TIMEOUT";
            } else if (errMsg.contains("native") || errMsg.contains("crash") || errMsg.contains("信号") || errMsg.contains("Native")) {
                errType = "NATIVE_CRASH";
                retryable = true;
            } else if (errMsg.contains("内存") || errMsg.contains("memory") || errMsg.contains("OOM")) {
                errType = "MEMORY";
                retryable = true;
            } else if (errMsg.contains("取消") || errMsg.contains("cancel")) {
                errType = "CANCELLED";
                retryable = false;
            }

            aiErrorLiveData.postValue(new AIError(errType, errMsg, retryable));
            errorLiveData.setValue(error);
            endGeneration();
        });
    }

    private void endGeneration() {
        isGenerating.set(false);
        currentStreamingContent = null;
        currentThinkingContent = null;
        isInThinking = false;
        currentStreamingMessageIndex = -1;
        mainHandler.post(() -> generatingStateLiveData.setValue(false));
        // 状态机清理
        mainHandler.post(() -> setState(AIState.READY));
    }

    // ========== 模型操作 ==========

    /**
     * 停止生成 —— 双通道取消
     * 通道1: aiService.chatStop() → LlamaHelper.stopGeneration() → C++ shouldStopAtom 原子标志
     * 通道2: 状态机清理 → setState(IDLE) + endGeneration() 清理 UI 状态
     */
    public void stopGeneration() {
        // 通道1：通知底层停止推理
        if (aiService != null) {
            try {
                aiService.chatStop();
            } catch (Throwable t) {
                AILogger.w(TAG, "chatStop failed: " + t.getMessage());
            }
        }

        // 通道2：状态机迁移 + UI 清理
        if (getCurrentState() == AIState.INFERRING) {
            aiErrorLiveData.postValue(new AIError("CANCELLED", "推理已取消", false));
            setState(AIState.READY);
        }
        endGeneration();
    }

    /**
     * 获取当前模型名称
     */
    public String getCurrentModelName() {
        return currentModelName;
    }

    /**
     * 是否使用在线模型
     */
    public boolean isUsingOnlineModel() {
        return isUsingOnlineModel;
    }

    /**
     * AI 服务是否已初始化
     */
    public boolean isAIServiceInitialized() {
        return aiService != null && aiService.isInitialized();
    }

    // ========== 数据保存 ==========

    private void saveHistoryAsync() {
        if (chatHistoryManager == null) return;
        try {
        executor.execute(() -> {
            try {
                List<ChatMessage> copy = chatMessages.toMutableList();
                chatHistoryManager.saveAIChatHistory(copy);
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to save chat history: " + e.getMessage());
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.w(TAG, "Executor rejected save history task: " + e.getMessage());
        }
    }

    // ========== LiveData 获取 ==========

    /**
     * 获取 AI 状态机（可观察）—— UI 层的主状态数据源
     * 覆盖 IDLE/LOADING/READY/INFERRING/ERROR/UNLOADED 全生命周期
     */
    public LiveData<AIState> getAIState() {
        return aiStateLiveData;
    }

    /**
     * 获取结构化错误信息（可观察）—— UI 可根据类型渲染不同错误卡片
     */
    public LiveData<AIError> getAIError() {
        return aiErrorLiveData;
    }

    /**
     * 获取推理进度（可观察）—— 包含 elapsedMs/tokenCount/phase 等心跳数据
     */
    public LiveData<InferenceProgress> getInferenceProgress() {
        return inferenceProgressLiveData;
    }

    /**
     * 获取聊天消息列表（可观察）
     */
    public LiveData<List<ChatMessage>> getChatMessages() {
        return chatMessages.asLiveData();
    }

    /**
     * 获取聊天消息列表（用于 Adapter）
     */
    public MutableChatList getChatMessageList() {
        return chatMessages;
    }

    /**
     * 获取模型名称（可观察）
     */
    public LiveData<String> getModelName() {
        return modelNameLiveData;
    }

    /**
     * 获取生成状态（可观察）
     */
    public LiveData<String> getOnlineThinkingStream() {
        return onlineThinkingStream;
    }

    public LiveData<Boolean> isGenerating() {
        return generatingStateLiveData;
    }

    /**
     * 获取错误信息（可观察）
     */
    public LiveData<String> getError() {
        return errorLiveData;
    }

    /**
     * 获取初始化状态（可观察）
     */
    public LiveData<Boolean> isInitialized() {
        return initializationLiveData;
    }

    // ========== 清理 ==========

    @Override
    protected void onCleared() {
        super.onCleared();
        // 注意：executor 是 @Singleton 共享实例，不能在此关闭
        // 否则会导致后续创建的 ViewModel 无法使用 executor（RejectedExecutionException）
        AILogger.i(TAG, "ViewModel cleared");
    }
}
