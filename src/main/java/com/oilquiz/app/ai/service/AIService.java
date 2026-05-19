package com.oilquiz.app.ai.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.oilquiz.app.util.AILogger;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.model.Model;
import com.oilquiz.app.ai.optimization.DeviceDetector;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.gpu.GpuDatabase;
import com.oilquiz.app.ai.gpu.GpuInfo;
import com.oilquiz.app.ai.gpu.GpuProfile;
import com.oilquiz.app.ai.gpu.GpuTier;
import com.oilquiz.app.ai.gpu.MemoryUsageInfo;
import com.oilquiz.app.ai.util.PromptBuilder;
import com.oilquiz.app.ai.db.ChatRepository;
import java.util.ArrayList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AIService {
    private static final String TAG = "AIService";
    public static final int INFERENCE_N_CTX = 16384;
    public static final int CHAT_N_CTX = 16384;
    private static final String MODEL_DIR_NAME = "ai_models";
    private static final String PREFS_NAME = "ai_service_prefs";
    private static final String PREF_CURRENT_MODEL = "current_model";
    private static final String PREF_OPTIMIZATION_ENABLED = "optimization_enabled";
    private static AIService instance;

    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private AICrashHandler crashHandler;
    private Model currentModel;
    private String currentModelName = null;
    private boolean isLoading = false;
    private boolean isInitialized = false;
    private boolean isOptimizationEnabled = false; // 默认不优化
    private boolean isAppInBackground = false;
    private long lastUsedTimestamp = System.currentTimeMillis();
    private ChatRepository chatRepository;

    // 增强的服务状态管理
    private final AIServiceState serviceState = new AIServiceState();

    // 状态观察者列表
    private List<StatusObserver> statusObservers = new ArrayList<>();
    private List<DetailedStatusObserver> detailedStatusObservers = new ArrayList<>();

    // 预加载和热启动管理
    private volatile boolean isPreloading = false;
    private volatile boolean hotStartEnabled = true;
    private ScheduledFuture<?> idleMonitorFuture = null;

    // 推理性能统计
    private volatile long lastInferenceStartTime = 0;
    private volatile long lastInferenceElapsedMs = 0;
    private volatile int lastTokenCount = 0;
    private volatile float lastInferenceSpeed = 0;
    private final Object inferenceStatsLock = new Object();

    // 动态优化配置
    private boolean dynamicOptimizationEnabled = true;
    private int lastPerformanceLevel = -1;
    private static final int PERF_LEVEL_LOW = 0;
    private static final int PERF_LEVEL_MEDIUM = 1;
    private static final int PERF_LEVEL_HIGH = 2;

    /** 模型加载/释放串行化，避免与异步 release 交错导致 native 状态错乱 */
    private final Object modelInitLock = new Object();
    private volatile boolean openClPreloaded = false;

    /**
     * 供 {@link #initializeSafe} / {@link #switchModelSafe} 排队执行，避免每次 new Thread 且与主线程提交顺序一致。
     */
    private final ExecutorService modelInitSerialExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AIService-model-init-serial");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService watchdogScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AIService-watchdog");
        t.setDaemon(true);
        return t;
    });

    private AIService(Context context) {
        this.context = context.getApplicationContext();
        // 优化线程池配置，根据CPU核心数动态调整
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int threadPoolSize = Math.min(cpuCores + 2, 10); // 核心数+2，最多10线程
        this.executorService = new java.util.concurrent.ThreadPoolExecutor(
                threadPoolSize, // 核心线程数
                threadPoolSize, // 最大线程数
                60L, java.util.concurrent.TimeUnit.SECONDS, // 线程空闲超时
                new java.util.concurrent.LinkedBlockingQueue<>(100), // 工作队列
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
        );
        this.mainHandler = new Handler(Looper.getMainLooper());

        // 初始化崩溃处理器
        this.crashHandler = AICrashHandler.getInstance(context);
        this.crashHandler.startMonitoring();

        // 设置 Native 日志回调
        setupNativeLogCallback();

        // 从SharedPreferences加载保存的模型名称
        loadSavedModelName();
        
        // 从SharedPreferences加载优化开关状态
        loadOptimizationEnabled();
        
        // 初始化聊天记录仓库
        chatRepository = new ChatRepository(context);
        
        AILogger.i(TAG, "ThreadPool initialized with " + threadPoolSize + " threads");
        AILogger.i(TAG, "ChatRepository initialized");
    }
    
    private void setupNativeLogCallback() {
        LlamaHelper.setNativeLogCallback(new LlamaHelper.NativeLogCallback() {
            @Override
            public void onLog(int level, String tag, String message) {
                String logLevel;
                switch (level) {
                    case 3: logLevel = AIProcessingService.LOG_LEVEL_INFO; break;
                    case 4: logLevel = AIProcessingService.LOG_LEVEL_INFO; break;
                    case 5: logLevel = AIProcessingService.LOG_LEVEL_WARN; break;
                    case 6: logLevel = AIProcessingService.LOG_LEVEL_ERROR; break;
                    default: logLevel = AIProcessingService.LOG_LEVEL_INFO;
                }
                
                // 保存到 AILogger
                switch (level) {
                    case 3:
                    case 4:
                        AILogger.i(tag, message);
                        break;
                    case 5:
                        AILogger.w(tag, message);
                        break;
                    case 6:
                        AILogger.e(tag, message);
                        break;
                }
                
                // 高频 INFO 日志不再广播到 UI，避免初始化/推理时拖垮主线程
                if (level >= 5) {
                    Intent logIntent = new Intent(AIProcessingService.ACTION_AI_LOG_UPDATE);
                    logIntent.putExtra(AIProcessingService.EXTRA_LOG_LEVEL, logLevel);
                    logIntent.putExtra(AIProcessingService.EXTRA_LOG_MESSAGE, "[" + tag + "] " + message);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(logIntent);
                }
            }
        });
    }
    
    private void sendLogBroadcast(String level, String message) {
        Intent logIntent = new Intent(AIProcessingService.ACTION_AI_LOG_UPDATE);
        logIntent.putExtra(AIProcessingService.EXTRA_LOG_LEVEL, level);
        logIntent.putExtra(AIProcessingService.EXTRA_LOG_MESSAGE, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(logIntent);
    }
    
    /**
     * 从SharedPreferences加载保存的模型名称
     */
    private void loadSavedModelName() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentModelName = prefs.getString(PREF_CURRENT_MODEL, null);
        if (currentModelName != null) {
            AILogger.i(TAG, "Loaded saved model name: " + currentModelName);
        }
    }
    
    /**
     * 保存模型名称到SharedPreferences
     */
    private void saveModelName(String modelName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_CURRENT_MODEL, modelName);
        editor.apply();
        AILogger.i(TAG, "Saved model name: " + modelName);
    }
    
    /**
     * 从SharedPreferences加载优化开关状态
     */
    private void loadOptimizationEnabled() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isOptimizationEnabled = prefs.getBoolean(PREF_OPTIMIZATION_ENABLED, false); // 默认不优化
        AILogger.i(TAG, "Loaded optimization enabled: " + isOptimizationEnabled);
    }
    
    // 计算历史消息的 token 数量
    private int calculateHistoryTokenCount(List<PromptBuilder.Message> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        
        StringBuilder historyText = new StringBuilder();
        for (PromptBuilder.Message msg : history) {
            historyText.append(msg.role()).append(": ").append(msg.content()).append("\n");
        }
        
        return LlamaHelper.countTokens(historyText.toString());
    }
    
    /**
     * 压缩历史：通过裁剪消息条数控制 token，避免嵌套再跑一轮推理导致延迟与死锁风险。
     */
    private List<PromptBuilder.Message> compressHistory(List<PromptBuilder.Message> history, int maxTokens) {
        if (history == null || history.isEmpty()) {
            return history != null ? history : new ArrayList<>();
        }
        if (calculateHistoryTokenCount(history) <= maxTokens) {
            return history;
        }
        for (int pairs = 8; pairs >= 1; pairs--) {
            List<PromptBuilder.Message> truncated = PromptBuilder.truncateHistory(history, pairs);
            if (calculateHistoryTokenCount(truncated) <= maxTokens) {
                AILogger.i(TAG, "History compressed by truncation: pairs=" + pairs);
                return truncated;
            }
        }
        List<PromptBuilder.Message> one = PromptBuilder.truncateHistory(history, 1);
        AILogger.w(TAG, "History still over budget after truncation, keeping last pair only");
        return one;
    }
    
    // 保存聊天消息
    public void saveChatMessage(long conversationId, String role, String content, boolean isCompressed) {
        if (chatRepository != null) {
            try {
                long messageId = chatRepository.saveMessage(conversationId, role, content, isCompressed);
                AILogger.i(TAG, "Saved chat message: id=" + messageId + ", role=" + role + ", content length=" + content.length());
            } catch (Exception e) {
                AILogger.e(TAG, "Error saving chat message: " + e.getMessage(), e);
            }
        }
    }
    
    // 获取聊天历史
    public List<PromptBuilder.Message> getChatHistory(long conversationId) {
        if (chatRepository != null) {
            try {
                return chatRepository.getMessages(conversationId);
            } catch (Exception e) {
                AILogger.e(TAG, "Error getting chat history: " + e.getMessage(), e);
            }
        }
        return new ArrayList<>();
    }
    
    // 创建新会话
    public long createConversation(String title) {
        if (chatRepository != null) {
            try {
                return chatRepository.createConversation(title);
            } catch (Exception e) {
                AILogger.e(TAG, "Error creating conversation: " + e.getMessage(), e);
            }
        }
        return 0;
    }
    
    // 获取所有会话
    public List<ChatRepository.Conversation> getConversations() {
        if (chatRepository != null) {
            try {
                return chatRepository.getConversations();
            } catch (Exception e) {
                AILogger.e(TAG, "Error getting conversations: " + e.getMessage(), e);
            }
        }
        return new ArrayList<>();
    }
    
    // 删除会话
    public void deleteConversation(long conversationId) {
        if (chatRepository != null) {
            try {
                chatRepository.deleteConversation(conversationId);
            } catch (Exception e) {
                AILogger.e(TAG, "Error deleting conversation: " + e.getMessage(), e);
            }
        }
    }
    
    // 清空所有聊天记录
    public void clearAllChats() {
        if (chatRepository != null) {
            try {
                chatRepository.clearAllChats();
            } catch (Exception e) {
                AILogger.e(TAG, "Error clearing all chats: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 保存优化开关状态到SharedPreferences
     */
    private void saveOptimizationEnabled(boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(PREF_OPTIMIZATION_ENABLED, enabled);
        editor.apply();
        isOptimizationEnabled = enabled;
        AILogger.i(TAG, "Saved optimization enabled: " + enabled);
    }
    
    /**
     * 获取优化开关状态
     */
    public boolean isOptimizationEnabled() {
        return isOptimizationEnabled;
    }
    
    /**
     * 设置优化开关状态
     */
    public void setOptimizationEnabled(boolean enabled) {
        saveOptimizationEnabled(enabled);
    }

    public static synchronized AIService getInstance(Context context) {
        if (instance == null) {
            instance = new AIService(context);
        }

        if (!instance.hotStartEnabled) {
            AILogger.i(TAG, "热启动已禁用，跳过热启动检测");
            return instance;
        }

        boolean modelInMemory = LlamaHelper.isModelInitialized();

        if (modelInMemory && !instance.isInitialized) {
            AILogger.i(TAG, "检测到模型在内存中，但Java状态未标记，立即同步状态");
            instance.isInitialized = true;
            AILogger.i(TAG, "AI服务状态已同步，模型在内存中");
        }

        if (instance.isInitialized && modelInMemory) {
            AILogger.i(TAG, "AI服务已处于热状态（模型在内存中），跳过初始化");
            return instance;
        }

        if (instance.isInitialized && !modelInMemory) {
            AILogger.w(TAG, "检测到 native 模型已释放，重置 Java 初始化标记（请重新加载模型）");
            instance.isInitialized = false;
        }

        return instance;
    }

    /**
     * 初始化结果回调
     */
    public interface InitializeCallback {
        void onResult(boolean success);
    }

    /**
     * 异步初始化AI服务，结果通过回调返回（主线程安全）
     */
    public void initializeAsync(InitializeCallback callback) {
        if (currentModelName == null) {
            AILogger.w(TAG, "initializeAsync: no model selected");
            if (callback != null) callback.onResult(false);
            return;
        }
        modelInitSerialExecutor.execute(() -> {
            boolean success = initialize();
            if (callback != null) {
                callback.onResult(success);
            }
        });
    }

    /**
     * 异步初始化指定模型，结果通过回调返回（主线程安全）
     */
    public void initializeAsync(String modelName, InitializeCallback callback) {
        modelInitSerialExecutor.execute(() -> {
            boolean success = initialize(modelName);
            if (callback != null) {
                callback.onResult(success);
            }
        });
    }

    /**
     * 初始化AI服务（须在后台线程调用；会阻塞直至加载完成）
     */
    public boolean initialize() {
        synchronized (modelInitLock) {
            if (currentModelName == null) {
                AILogger.e(TAG, "No model selected, cannot initialize AI service");
                return false;
            }
            return loadModelLocked(currentModelName);
        }
    }

    /**
     * 按指定文件名加载模型（须在后台线程调用）
     */
    public boolean initialize(String modelName) {
        synchronized (modelInitLock) {
            return loadModelLocked(modelName);
        }
    }

    /**
     * 若当前在主线程则转到后台线程执行 {@link #initialize()}，避免阻塞 UI。
     * @deprecated 使用 {@link #initializeAsync(InitializeCallback)} 替代，避免主线程阻塞
     */
    @Deprecated
    public boolean initializeSafe() {
        if (Looper.getMainLooper().isCurrentThread()) {
            if (currentModelName == null) {
                AILogger.w(TAG, "initializeSafe: no model selected");
                return false;
            }
            FutureTask<Boolean> task = new FutureTask<>(this::initialize);
            modelInitSerialExecutor.execute(task);
            try {
                return Boolean.TRUE.equals(task.get(45, TimeUnit.MINUTES));
            } catch (Exception e) {
                AILogger.e(TAG, "initializeSafe: " + e.getMessage(), e);
                return false;
            }
        }
        return initialize();
    }

    /**
     * 主线程安全版本的 {@link #initialize(String)}。
     */
    public boolean initializeSafe(String modelName) {
        if (Looper.getMainLooper().isCurrentThread()) {
            FutureTask<Boolean> task = new FutureTask<>(() -> initialize(modelName));
            modelInitSerialExecutor.execute(task);
            try {
                return Boolean.TRUE.equals(task.get(45, TimeUnit.MINUTES));
            } catch (Exception e) {
                AILogger.e(TAG, "initializeSafe(model): " + e.getMessage(), e);
                return false;
            }
        }
        return initialize(modelName);
    }

    /**
     * 主线程安全版本的 {@link #switchModel(String)}（模型加载可能耗时数分钟）。
     */
    public boolean switchModelSafe(String modelName) {
        if (Looper.getMainLooper().isCurrentThread()) {
            FutureTask<Boolean> task = new FutureTask<>(() -> switchModel(modelName));
            modelInitSerialExecutor.execute(task);
            try {
                return Boolean.TRUE.equals(task.get(45, TimeUnit.MINUTES));
            } catch (Exception e) {
                AILogger.e(TAG, "switchModelSafe: " + e.getMessage(), e);
                return false;
            }
        }
        return switchModel(modelName);
    }

    /**
     * 主线程安全版本的 {@link #reloadCurrentModel()}。
     */
    public boolean reloadCurrentModelSafe() {
        if (Looper.getMainLooper().isCurrentThread()) {
            FutureTask<Boolean> task = new FutureTask<>(this::reloadCurrentModel);
            modelInitSerialExecutor.execute(task);
            try {
                return Boolean.TRUE.equals(task.get(45, TimeUnit.MINUTES));
            } catch (Exception e) {
                AILogger.e(TAG, "reloadCurrentModelSafe: " + e.getMessage(), e);
                return false;
            }
        }
        return reloadCurrentModel();
    }

    /**
     * 释放 native 模型与聊天上下文。调用方须已持有 {@link #modelInitLock}，或与 {@link #switchModel} 等串行路径配合。
     */
    private void releaseNativeResourcesLocked(boolean stopCrashMonitoring) {
        try {
            if (stopCrashMonitoring && crashHandler != null) {
                crashHandler.stopMonitoring();
            }
            try {
                LlamaHelper.chatDestroy();
            } catch (Exception ignored) {
            }
            LlamaHelper.release();
        } catch (Exception e) {
            AILogger.e(TAG, "releaseNativeResourcesLocked: " + e.getMessage(), e);
        }
        isInitialized = false;
    }

    /**
     * 在已持有 {@link #modelInitLock} 的前提下执行模型加载。
     */
    private boolean loadModelLocked(String modelName) {
        serviceState.startTiming();
        serviceState.setCurrentModelName(modelName);

        if (LlamaHelper.isChatContextActive()) {
            AILogger.i(TAG, "Destroying existing chat context before loading new model");
            try {
                LlamaHelper.chatDestroy();
            } catch (Exception ignored) {
            }
        }

        if (!LlamaHelper.isLibraryLoaded()) {
            String errorMsg = "LlamaHelper library not loaded, cannot initialize AI service";
            AILogger.e(TAG, errorMsg);
            serviceState.setError(errorMsg);
            notifyError(errorMsg);
            return false;
        }

        isLoading = true;
        notifyStatusChange();

        try {
            updateServiceStage(AIServiceState.ServiceStage.MODEL_FILE_PREPARING, "准备模型文件...", 15);

            File modelFile = copyModelToInternalStorage(modelName);
            if (modelFile == null) {
                String errorMsg = "Failed to locate model file: " + modelName;
                AILogger.e(TAG, errorMsg);
                serviceState.setError(errorMsg);
                notifyError(errorMsg);
                return false;
            }
            if (!modelFile.exists()) {
                String errorMsg = "Model file does not exist: " + modelFile.getAbsolutePath();
                AILogger.e(TAG, errorMsg);
                serviceState.setError(errorMsg);
                notifyError(errorMsg);
                return false;
            }
            if (!modelFile.canRead()) {
                String errorMsg = "Model file is not readable: " + modelFile.getAbsolutePath();
                AILogger.e(TAG, errorMsg);
                serviceState.setError(errorMsg);
                notifyError(errorMsg);
                return false;
            }

            updateServiceStage(AIServiceState.ServiceStage.MODEL_LOADING, "加载模型到内存中...", 40);

            AILogger.i(TAG, "Applying optimizations BEFORE model initialization...");
            applyOptimizations();

            int gpuLayers = LlamaHelper.getGPULayers();
            int threadCount = LlamaHelper.getThreadCount();
            int batchSize = LlamaHelper.getBatchSize();
            int memoryPoolSize = LlamaHelper.getMemoryPoolSize();

            AILogger.i(TAG, "Initializing model with optimized parameters: " +
                    "gpuLayers=" + gpuLayers +
                    ", threadCount=" + threadCount +
                    ", batchSize=" + batchSize +
                    ", memoryPoolSize=" + memoryPoolSize + "MB");

            int contextSize = INFERENCE_N_CTX;
            
            if (gpuLayers > 0) {
                updateServiceStage(AIServiceState.ServiceStage.GPU_INITIALIZATION, "初始化GPU加速...", 70);
            } else {
                updateServiceStage(AIServiceState.ServiceStage.MODEL_LOADING, "初始化模型...", 60);
            }

            int result = LlamaHelper.initModel(
                    modelFile.getAbsolutePath(),
                    contextSize,
                    threadCount
            );

            if (result != 0 && LlamaHelper.getGPULayers() > 0) {
                AILogger.w(TAG, "GPU initialization failed (code: " + result + "), trying CPU-only mode...");
                updateServiceStage(AIServiceState.ServiceStage.CPU_FALLBACK, "GPU初始化失败，切换到CPU模式...", 75);
                LlamaHelper.setGPULayers(0);
                int cpuThreadCount = Math.min(Runtime.getRuntime().availableProcessors(), 6);
                LlamaHelper.setThreadCount(cpuThreadCount);
                LlamaHelper.setBatchSize(128);
                AILogger.i(TAG, "Switching to CPU mode: threads=" + cpuThreadCount + ", batchSize=128");
                result = LlamaHelper.initModel(
                        modelFile.getAbsolutePath(),
                        contextSize,
                        cpuThreadCount
                );
                if (result == 0) {
                    AILogger.i(TAG, "Successfully initialized in CPU-only mode after GPU failure");
                } else {
                    AILogger.e(TAG, "CPU fallback also failed with code: " + result);
                }
            }

            if (result == 0) {
                updateServiceStage(AIServiceState.ServiceStage.CHAT_CONTEXT_CREATING, "创建对话上下文...", 90);
                isInitialized = true;
                currentModelName = modelName;
                saveModelName(modelName);
                if (crashHandler != null) {
                    crashHandler.startMonitoring();
                }
                
                AILogger.i(TAG, "Creating default chat context after model load...");
                boolean chatContextCreated = initChatContext("", "", "");
                if (chatContextCreated) {
                    AILogger.i(TAG, "Default chat context created successfully");
                } else {
                    AILogger.w(TAG, "Failed to create default chat context, but model is loaded");
                }
                
                AILogger.i(TAG, "AI service initialized successfully with model: " + modelName);

                long loadTimeMs = serviceState.getElapsedTimeMs();
                serviceState.setCurrentStage(AIServiceState.ServiceStage.INITIALIZED, "AI服务已就绪", 100);
                notifyInitialized(loadTimeMs);
                return true;
            }

            String errorMsg = "Failed to initialize AI service: " + result;
            AILogger.e(TAG, errorMsg);
            serviceState.setError(errorMsg);
            notifyError(errorMsg);
            isInitialized = false;
            currentModelName = null;
            return false;
        } catch (Exception e) {
            String errorMsg = "Error initializing AI service: " + e.getMessage();
            AILogger.e(TAG, errorMsg, e);
            serviceState.setError(errorMsg);
            notifyError(errorMsg);
            isInitialized = false;
            currentModelName = null;
            return false;
        } finally {
            isLoading = false;
            notifyStatusChange();
        }
    }

    /**
     * 生成文本
     */
    public void generate(String prompt, GenerateCallback callback) {
        generate(prompt, new ArrayList<>(), 256, callback);
    }
    
    /**
     * 生成文本，支持历史消息
     */
    public void generate(String prompt, List<PromptBuilder.Message> history, int maxTokens, GenerateCallback callback) {
        updateLastUsedTime();
        
        if (isLoading) {
            callback.onError(new IllegalStateException("AI service is loading, please wait"));
            return;
        }
        
        if (!isInitialized) {
            callback.onError(new IllegalStateException("AI service not initialized"));
            return;
        }
        
        if (!LlamaHelper.isModelInitialized()) {
            callback.onError(new IllegalStateException("AI model is still loading, please wait"));
            return;
        }

        crashHandler.recordActivity();

        executorService.execute(() -> {
            try {
                if (!LlamaHelper.isChatContextActive()) {
                    AILogger.i(TAG, "generate: creating chat context");
                    boolean created = initChatContext("", "", "");
                    if (!created) {
                        mainHandler.post(() -> callback.onError(new Exception("Failed to create chat context")));
                        return;
                    }
                }

                int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                final String[] result = {null};
                final Exception[] error = {null};
                final Object lock = new Object();
                
                synchronized (lock) {
                    LlamaHelper.chatSend(prompt, adjustedMaxTokens, 0.7f, 0.9f, 40, false, new LlamaHelper.TokenCallback() {
                        private StringBuilder fullResponse = new StringBuilder();
                        
                        @Override
                        public void onToken(String token) {
                            fullResponse.append(token);
                        }
                        
                        @Override
                        public void onComplete(String fullText) {
                            result[0] = fullText != null ? fullText : fullResponse.toString();
                            synchronized (lock) {
                                lock.notify();
                            }
                        }
                        
                        @Override
                        public void onError(String msg) {
                            error[0] = new Exception(msg);
                            synchronized (lock) {
                                lock.notify();
                            }
                        }
                    });
                    
                    try {
                        lock.wait(120000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                if (error[0] != null) {
                    AILogger.e(TAG, "Chat send error: " + error[0].getMessage());
                    mainHandler.post(() -> callback.onError(error[0]));
                } else if (result[0] != null) {
                    mainHandler.post(() -> {
                        callback.onSuccess(result[0]);
                        saveChatMessage(0, "user", prompt, false);
                        saveChatMessage(0, "assistant", result[0], false);
                    });
                } else {
                    mainHandler.post(() -> callback.onError(new Exception("生成超时或无响应")));
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Error generating text: " + e.getMessage(), e);
                crashHandler.recordCrashInfo("GENERATE_ERROR", "生成文本时出错", e);
                mainHandler.post(() -> callback.onError(e));
            } catch (Throwable t) {
                AILogger.e(TAG, "Throwable caught in generate: possible native crash", t);
                crashHandler.recordCrashInfo("NATIVE_CRASH", "生成时发生Native崩溃", t);
                mainHandler.post(() -> callback.onError(new Exception("生成失败，可能是内存或模型问题")));
            }
        });
    }

    /**
     * 流式生成文本
     */
    public void generateStream(String prompt, int maxTokens, GenerateStreamCallback callback) {
        generateStream(prompt, new ArrayList<>(), maxTokens, callback);
    }

    /**
     * 流式生成文本，支持历史消息
     */
    public void generateStream(String prompt, List<PromptBuilder.Message> history, int maxTokens, GenerateStreamCallback callback) {
        generateStream(prompt, history, maxTokens, null, callback);
    }

    public void generateStream(String prompt, List<PromptBuilder.Message> history, int maxTokens, PromptBuilder.PromptRequest promptRequest, GenerateStreamCallback callback) {
        updateLastUsedTime();
        
        AILogger.i(TAG, "generateStream called!");
        sendLogBroadcast("INFO", "[AIService] 开始流式生成: prompt长度=" + (prompt != null ? prompt.length() : 0) + ", maxTokens=" + maxTokens);
        
        if (isLoading) {
            AILogger.e(TAG, "AI service is loading, returning error");
            sendLogBroadcast("ERROR", "[AIService] 服务正在加载，请等待");
            callback.onError(new IllegalStateException("AI service is loading, please wait"));
            return;
        }
        
        if (!isInitialized) {
            AILogger.e(TAG, "AI service not initialized, returning error");
            sendLogBroadcast("ERROR", "[AIService] 服务未初始化");
            callback.onError(new IllegalStateException("AI service not initialized"));
            return;
        }
        
        if (!LlamaHelper.isModelInitialized()) {
            AILogger.e(TAG, "AI model not initialized, returning error");
            sendLogBroadcast("ERROR", "[AIService] 模型正在加载，请等待");
            callback.onError(new IllegalStateException("AI model is still loading, please wait"));
            return;
        }
        
        AILogger.i(TAG, "Pre-checks passed, preparing generation...");
        sendLogBroadcast("INFO", "[AIService] 预检查通过");

        final java.util.concurrent.Future<?>[] taskFuture = new java.util.concurrent.Future<?>[1];
        
        taskFuture[0] = executorService.submit(() -> {
            try {
                String actualPrompt;
                if (promptRequest != null) {
                    actualPrompt = promptRequest.build();
                } else {
                    actualPrompt = prompt;
                }

                if (!LlamaHelper.isChatContextActive()) {
                    AILogger.i(TAG, "generateStream: creating chat context");
                    sendLogBroadcast("INFO", "[AIService] 创建聊天上下文");
                    boolean created = initChatContext("", "", "");
                    if (!created) {
                        mainHandler.post(() -> callback.onError(new Exception("Failed to create chat context")));
                        return;
                    }
                    sendLogBroadcast("INFO", "[AIService] 聊天上下文创建成功");
                }

                int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                AILogger.i(TAG, "调整生成 token 数: 原始 " + maxTokens + ", 调整后 " + adjustedMaxTokens);
                sendLogBroadcast("INFO", "[AIService] 使用聊天上下文进行流式生成");
                
                final long startTime = System.currentTimeMillis();
                final long timeoutMs = 600000;
                final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
                
                ScheduledFuture<?> watchdogFuture = watchdogScheduler.schedule(() -> {
                    if (!completed.get()) {
                        AILogger.w(TAG, "Chat send timeout after " + timeoutMs + "ms, stopping...");
                        LlamaHelper.chatStop();
                        mainHandler.post(() -> callback.onError(new Exception("生成超时，请重试")));
                        if (taskFuture[0] != null && !taskFuture[0].isDone()) {
                            taskFuture[0].cancel(true);
                        }
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);
                
                LlamaHelper.chatSend(actualPrompt, adjustedMaxTokens, 0.7f, 0.9f, 40, false, new LlamaHelper.TokenCallback() {
                    @Override
                    public void onToken(String token) {
                        mainHandler.post(() -> callback.onToken(token));
                    }

                    @Override
                    public void onComplete(String fullText) {
                        completed.set(true);
                        watchdogFuture.cancel(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        float inferenceSpeed = LlamaHelper.getInferenceSpeed();
                        int tokenCount = LlamaHelper.getTokenCount();
                        
                        AILogger.i(TAG, "LlamaHelper.chatSend: onComplete called, fullText length: " + (fullText != null ? fullText.length() : 0) + ", elapsed: " + elapsed + "ms");
                        AILogger.i(TAG, "Performance metrics - Speed: " + String.format("%.2f", inferenceSpeed) + " t/s, Tokens: " + tokenCount);
                        sendLogBroadcast("INFO", "[AIService] 生成完成: 完整文本长度=" + (fullText != null ? fullText.length() : 0) + ", 耗时=" + elapsed + "ms");
                        sendLogBroadcast("INFO", "[AIService] 性能监控: 推理速度=" + String.format("%.2f", inferenceSpeed) + " tokens/s, token数=" + tokenCount);
                        
                        mainHandler.post(() -> {
                            try {
                                callback.onSuccess(fullText);
                                saveChatMessage(0, "user", prompt, false);
                                saveChatMessage(0, "assistant", fullText, false);
                            } catch (Exception e) {
                                AILogger.e(TAG, "Error in onComplete callback: " + e.getMessage(), e);
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        completed.set(true);
                        watchdogFuture.cancel(false);
                        AILogger.e(TAG, "LlamaHelper.chatSend: onError called, error: " + error);
                        sendLogBroadcast("ERROR", "[AIService] 生成错误: " + error);
                        mainHandler.post(() -> callback.onError(new Exception(error)));
                    }
                });
                
                AILogger.i(TAG, "Generation setup completed!");
            } catch (OutOfMemoryError e) {
                AILogger.e(TAG, "OutOfMemoryError in generateStream task: " + e.getMessage(), e);
                sendLogBroadcast("ERROR", "[AIService] 内存溢出: " + e.getMessage());
                try {
                    LlamaHelper.stopGeneration();
                    LlamaHelper.chatStop();
                } catch (Exception ex) {
                    AILogger.e(TAG, "Error stopping generation: " + ex.getMessage());
                }
                mainHandler.post(() -> callback.onError(new Exception("内存溢出，请尝试减小模型大小或清理内存")));
            } catch (Exception e) {
                AILogger.e(TAG, "Exception in generateStream task: " + e.getMessage(), e);
                sendLogBroadcast("ERROR", "[AIService] 生成异常: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e));
            } catch (Throwable t) {
                AILogger.e(TAG, "Throwable in generateStream task: " + t.getMessage(), t);
                sendLogBroadcast("ERROR", "[AIService] 生成异常: " + t.getMessage());
                mainHandler.post(() -> callback.onError(new Exception(t)));
            }
        });
        
        AILogger.i(TAG, "generateStream setup completed, returning...");
    }

    /**
     * 停止生成
     */
    public void stopGeneration() {
        executorService.execute(() -> {
            try {
                LlamaHelper.stopGeneration();
                AILogger.i(TAG, "Generation stopped");
            } catch (Exception e) {
                AILogger.e(TAG, "Error stopping generation: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 清空历史
     */
    public void clearHistory() {
        executorService.execute(() -> {
            try {
                LlamaHelper.clearHistory();
                AILogger.i(TAG, "History cleared");
            } catch (Exception e) {
                AILogger.e(TAG, "Error clearing history: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 异步生成文本
     */
    public CompletableFuture<String> generateAsync(String prompt, int maxTokens) {
        return generateAsync(prompt, new ArrayList<>(), maxTokens, null);
    }
    
    public CompletableFuture<String> generateAsync(String prompt, List<PromptBuilder.Message> history, int maxTokens) {
        return generateAsync(prompt, history, maxTokens, null);
    }

    public CompletableFuture<String> generateAsync(String prompt, List<PromptBuilder.Message> history, int maxTokens, String systemPrompt) {
        return generateAsync(prompt, history, maxTokens, systemPrompt, null);
    }

    public CompletableFuture<String> generateAsync(String prompt, List<PromptBuilder.Message> history, int maxTokens, String systemPrompt, PromptBuilder.PromptRequest promptRequest) {
        updateLastUsedTime();
        
        AILogger.i(TAG, "generateAsync called with prompt length: " + (prompt != null ? prompt.length() : 0));

        if (crashHandler != null) {
            crashHandler.recordActivity();
        }

        if (isLoading) {
            CompletableFuture<String> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("AI service is loading, please wait"));
            return failedFuture;
        }

        String formattedPrompt;
        if (promptRequest != null) {
            formattedPrompt = promptRequest.build();
        } else {
            formattedPrompt = formatPromptForModel(prompt, PromptBuilder.truncateHistory(history, 8), systemPrompt);
        }

        return CompletableFuture.supplyAsync(() -> {
            AILogger.i(TAG, "supplyAsync started, isInitialized: " + isInitialized);
            if (!isInitialized) {
                throw new IllegalStateException("AI service not initialized");
            }
            
            // 检查模型是否真正加载完成（不仅仅是isInitialized标志）
            if (!LlamaHelper.isModelInitialized()) {
                AILogger.e(TAG, "Model not fully loaded yet, isInitialized=" + isInitialized);
                throw new IllegalStateException("AI model is still loading, please wait");
            }

            try {
                if (formattedPrompt == null || formattedPrompt.trim().isEmpty()) {
                    AILogger.e(TAG, "Formatted prompt is null or empty");
                    throw new IllegalArgumentException("Prompt cannot be null or empty");
                }
                // 预留 64 个 token 作为余量
                int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                AILogger.i(TAG, "调整生成 token 数: 原始 " + maxTokens + ", 调整后 " + adjustedMaxTokens);
                
                AILogger.i(TAG, "Calling LlamaHelper.generate...");
                String result = LlamaHelper.generate(formattedPrompt, adjustedMaxTokens, 0.7f);
                AILogger.i(TAG, "LlamaHelper.generate completed, result length: " + (result != null ? result.length() : 0));
                
                // 保存聊天记录
                saveChatMessage(0, "user", prompt, false);
                saveChatMessage(0, "assistant", result, false);
                
                return result;
            } catch (OutOfMemoryError e) {
                AILogger.e(TAG, "OutOfMemoryError in generateAsync: " + e.getMessage(), e);
                if (crashHandler != null) {
                    crashHandler.recordCrashInfo("ASYNC_OOM_ERROR", "异步生成时内存溢出", e);
                }
                throw new RuntimeException("内存溢出，请尝试减小模型大小或清理内存", e);
            } catch (Exception e) {
                AILogger.e(TAG, "Error generating text: " + e.getMessage(), e);
                if (crashHandler != null) {
                    crashHandler.recordCrashInfo("ASYNC_GENERATE_ERROR", "异步生成文本时出错", e);
                }
                throw e;
            } catch (Throwable t) {
                // 捕获可能的Native崩溃
                AILogger.e(TAG, "Throwable caught in generateAsync: possible native crash", t);
                if (crashHandler != null) {
                    crashHandler.recordCrashInfo("ASYNC_NATIVE_CRASH", "异步生成时发生Native崩溃", t);
                }
                throw new RuntimeException("生成失败，可能是内存或模型问题", t);
            }
        }, executorService);
    }

    /**
     * 同步生成文本
     */
    public String generateSync(String prompt, int maxTokens) {
        return generateSync(prompt, new ArrayList<>(), maxTokens);
    }
    
    /**
     * 同步生成文本，支持历史消息
     */
    public String generateSync(String prompt, List<PromptBuilder.Message> history, int maxTokens) {
        updateLastUsedTime();
        
        if (isLoading) {
            throw new IllegalStateException("AI service is loading, please wait");
        }
        
        if (!isInitialized) {
            throw new IllegalStateException("AI service not initialized");
        }
        
        if (!LlamaHelper.isModelInitialized()) {
            AILogger.e(TAG, "Model not fully loaded yet, isInitialized=" + isInitialized);
            throw new IllegalStateException("AI model is still loading, please wait");
        }

        try {
            if (prompt == null || prompt.trim().isEmpty()) {
                AILogger.e(TAG, "Prompt is null or empty");
                throw new IllegalArgumentException("Prompt cannot be null or empty");
            }

            if (!LlamaHelper.isChatContextActive()) {
                AILogger.i(TAG, "generateSync: creating chat context");
                boolean created = initChatContext("", "", "");
                if (!created) {
                    throw new IllegalStateException("Failed to create chat context");
                }
            }

            int adjustedMaxTokens = Math.max(1, maxTokens - 64);
            final String[] result = {null};
            final Exception[] error = {null};
            final Object lock = new Object();
            
            synchronized (lock) {
                LlamaHelper.chatSend(prompt, adjustedMaxTokens, 0.7f, 0.9f, 40, false, new LlamaHelper.TokenCallback() {
                    private StringBuilder fullResponse = new StringBuilder();
                    
                    @Override
                    public void onToken(String token) {
                        fullResponse.append(token);
                    }
                    
                    @Override
                    public void onComplete(String fullText) {
                        result[0] = fullText != null ? fullText : fullResponse.toString();
                        synchronized (lock) {
                            lock.notify();
                        }
                    }
                    
                    @Override
                    public void onError(String msg) {
                        error[0] = new Exception(msg);
                        synchronized (lock) {
                            lock.notify();
                        }
                    }
                });
                
                try {
                    lock.wait(120000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (error[0] != null) {
                throw error[0];
            }
            
            if (result[0] != null) {
                saveChatMessage(0, "user", prompt, false);
                saveChatMessage(0, "assistant", result[0], false);
                return result[0];
            }
            
            throw new Exception("生成超时或无响应");
        } catch (OutOfMemoryError e) {
            AILogger.e(TAG, "OutOfMemoryError in generateSync: " + e.getMessage(), e);
            throw new RuntimeException("内存溢出，请尝试减小模型大小或清理内存", e);
        } catch (Exception e) {
            AILogger.e(TAG, "Error generating text: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
    
    /**
     * 根据模型类型调整prompt格式
     */
    private String formatPromptForModel(String prompt) {
        return formatPromptForModel(prompt, new ArrayList<>());
    }

    /**
     * 根据模型类型调整prompt格式，支持历史消息
     */
    private String formatPromptForModel(String prompt, List<PromptBuilder.Message> history) {
        return formatPromptForModel(prompt, history, null);
    }

    private String formatPromptForModel(String prompt, List<PromptBuilder.Message> history, String systemPrompt) {
        return formatPromptForModel(prompt, history, systemPrompt, false);
    }
    
    private String formatPromptForModel(String prompt, List<PromptBuilder.Message> history, String systemPrompt, boolean forceJavaTruncation) {
        AILogger.i(TAG, "================= 格式化提示词 =================");
        AILogger.i(TAG, "原始提示词长度: " + (prompt != null ? prompt.length() : 0));
        AILogger.i(TAG, "历史消息数量: " + (history != null ? history.size() : 0));
        
        String sys = (systemPrompt != null && !systemPrompt.isBlank()) 
            ? systemPrompt 
            : "你是一个乐于助人的AI助手。请用中文回答用户的问题。";
        
        // 只有在非Native模式或强制Java裁剪时才进行Java层裁剪
        List<PromptBuilder.Message> truncated;
        if (forceJavaTruncation || !LlamaHelper.isChatContextActive()) {
            truncated = PromptBuilder.truncateHistory(history, 8);
            AILogger.i(TAG, "Java层裁剪历史: " + (history != null ? history.size() : 0) + " -> " + truncated.size());
        } else {
            truncated = history != null ? history : new ArrayList<>();
            AILogger.i(TAG, "使用Native上下文，跳过Java层裁剪");
        }
        
        String formattedPrompt = PromptBuilder.build(sys, truncated, prompt);
        AILogger.i(TAG, "格式化后提示词长度: " + formattedPrompt.length());
        return formattedPrompt;
    }

    /**
     * 使用升级后的Prompt块系统格式化提示词（与本地库完全兼容）
     * 支持global、system、normal三种prompt块
     */
    private String formatPromptForNativeLib(String prompt, List<PromptBuilder.Message> history, String globalPrompt, String systemPrompt, String normalPrompt) {
        AILogger.i(TAG, "================= 格式化提示词（本地库兼容模式） =================");
        AILogger.i(TAG, "原始提示词长度: " + (prompt != null ? prompt.length() : 0));
        AILogger.i(TAG, "历史消息数量: " + (history != null ? history.size() : 0));
        AILogger.i(TAG, "Global Prompt: " + (globalPrompt != null ? globalPrompt.length() : 0) + " chars");
        AILogger.i(TAG, "System Prompt: " + (systemPrompt != null ? systemPrompt.length() : 0) + " chars");
        AILogger.i(TAG, "Normal Prompt: " + (normalPrompt != null ? normalPrompt.length() : 0) + " chars");

        // Native库会自己处理上下文裁剪，这里不进行Java层裁剪
        // 如果没有活跃的Native上下文，则进行Java层裁剪作为降级
        List<PromptBuilder.Message> truncated;
        if (LlamaHelper.isChatContextActive()) {
            truncated = history != null ? history : new ArrayList<>();
            AILogger.i(TAG, "使用Native上下文，跳过Java层裁剪");
        } else {
            truncated = PromptBuilder.truncateHistory(history, 8);
            AILogger.i(TAG, "Native上下文未激活，Java层裁剪历史: " + (history != null ? history.size() : 0) + " -> " + truncated.size());
        }
        
        String formattedPrompt = PromptBuilder.buildForNativeLib(globalPrompt, systemPrompt, normalPrompt, truncated, prompt);
        AILogger.i(TAG, "格式化后提示词长度: " + formattedPrompt.length());
        return formattedPrompt;
    }
    
    /**
     * 使用升级后的Prompt块系统格式化提示词（简化版本）
     */
    private String formatPromptForNativeLib(String prompt, List<PromptBuilder.Message> history) {
        return formatPromptForNativeLib(prompt, history, null, null, null);
    }
    
    /**
     * 获取当前模型名称（用于prompt格式化）
     */
    private String getModelNameForPrompt() {
        if (currentModelName != null) {
            return currentModelName.toLowerCase();
        }
        
        // 如果currentModelName为null，尝试获取可用模型列表并返回第一个
        String[] availableModels = getAvailableModels();
        if (availableModels != null && availableModels.length > 0) {
            return availableModels[0].toLowerCase();
        }
        
        return null;
    }
    
    /**
     * 获取当前日期，格式为 "dd MMM yyyy"
     */
    private String getCurrentDate() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM yyyy");
        return sdf.format(new java.util.Date());
    }

    /**
     * 获取当前模型名称
     */
    public String getCurrentModelName() {
        return currentModelName;
    }

    /**
     * 获取可用模型列表
     */
    public String[] getAvailableModels() {
        java.util.List<String> models = new java.util.ArrayList<>();
        
        // 添加用户添加的模型
        File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
        if (modelDir.exists() && modelDir.isDirectory()) {
            String[] files = modelDir.list();
            if (files != null) {
                for (String file : files) {
                    if (file.endsWith(".gguf")) {
                        models.add(file);
                    }
                }
            }
        }
        
        return models.toArray(new String[models.size()]);
    }

    /**
     * 切换模型
     */
    public boolean switchModel(String modelName) {
        synchronized (modelInitLock) {
            AILogger.i(TAG, "Switching to model: " + modelName);
            if (LlamaHelper.isModelInitialized()) {
                releaseNativeResourcesLocked(false);
            }
            saveModelName(modelName);
            return loadModelLocked(modelName);
        }
    }

    /**
     * 重新加载当前模型到热状态
     * 用于模型更新后重新初始化模型
     * @return 是否重新加载成功
     */
    public boolean reloadCurrentModel() {
        synchronized (modelInitLock) {
            if (currentModelName == null) {
                AILogger.e(TAG, "No model to reload, currentModelName is null");
                return false;
            }
            AILogger.i(TAG, "Reloading current model to hot state: " + currentModelName);
            if (LlamaHelper.isModelInitialized()) {
                releaseNativeResourcesLocked(true);
            }
            boolean success = loadModelLocked(currentModelName);
            if (success) {
                AILogger.i(TAG, "Model reloaded successfully to hot state: " + currentModelName);
            } else {
                AILogger.e(TAG, "Failed to reload model: " + currentModelName);
            }
            return success;
        }
    }

    /**
     * 释放资源（异步；与模型加载互斥）
     */
    public void release() {
        if (crashHandler != null) {
            crashHandler.stopMonitoring();
        }
        watchdogScheduler.shutdownNow();
        executorService.execute(() -> {
            synchronized (modelInitLock) {
                try {
                    releaseNativeResourcesLocked(false);
                    AILogger.i(TAG, "AI service released successfully");
                    mainHandler.post(this::notifyStatusChange);
                } catch (Exception e) {
                    AILogger.e(TAG, "Error releasing AI service: " + e.getMessage(), e);
                }
            }
        });
    }

    /**
     * 复制模型到内部存储
     */
    private File copyModelToInternalStorage(String modelName) {
        // 首先检查模型目录中的模型文件
        File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
        File modelFile = new File(modelDir, modelName);
        
        if (modelFile.exists()) {
            AILogger.i(TAG, "Model file found in models directory: " + modelFile.getAbsolutePath());
            return modelFile;
        }
        
        // 检查内部存储根目录中的模型文件
        File rootModelFile = new File(context.getFilesDir(), modelName);
        if (rootModelFile.exists()) {
            AILogger.i(TAG, "Model file found in root directory: " + rootModelFile.getAbsolutePath());
            return rootModelFile;
        }

        // 可选：若打包了同名 gguf 到 assets，则直接 open 拷贝，避免 list("") 全量扫描
        try (InputStream input = context.getAssets().open(modelName)) {
            try (OutputStream output = context.openFileOutput(modelName, Context.MODE_PRIVATE)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }
            AILogger.i(TAG, "Model file copied from assets: " + rootModelFile.getAbsolutePath());
            return rootModelFile;
        } catch (IOException e) {
            AILogger.d(TAG, "Model not in assets (expected if only using imported files): " + modelName);
        }

        AILogger.e(TAG, "No model file found: " + modelName);
        return null;
    }

    /**
     * 延迟加载 OpenCL，避免 AIService 构造阶段阻塞首屏
     * OpenCL库由系统提供（/vendor/lib64/libOpenCL.so）
     * llama-jni的JNI_OnLoad会自动尝试从系统路径加载
     */
    private void preloadOpenClIfNeeded() {
        if (openClPreloaded) {
            return;
        }
        synchronized (this) {
            if (openClPreloaded) {
                return;
            }
            
            if (!LlamaHelper.isLibraryLoaded()) {
                AILogger.i(TAG, "llama-jni not loaded yet, OpenCL will be loaded via JNI_OnLoad");
                openClPreloaded = true;
                return;
            }
            
            if (LlamaHelper.isOpenCLLoaded()) {
                AILogger.i(TAG, "OpenCL already loaded via JNI_OnLoad");
                openClPreloaded = true;
                return;
            }
            
            String[] openclPaths = {
                "/vendor/lib64/libOpenCL.so",
                "/vendor/lib/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/system/lib/libOpenCL.so",
                "/vendor/lib64/libOpenCL_adreno.so",
                "/vendor/lib/libOpenCL_adreno.so"
            };
            
            for (String path : openclPaths) {
                try {
                    System.load(path);
                    AILogger.i(TAG, "libOpenCL.so loaded from " + path);
                    openClPreloaded = true;
                    return;
                } catch (UnsatisfiedLinkError e) {
                    AILogger.d(TAG, "Failed to load OpenCL from " + path + ": " + e.getMessage());
                }
            }
            
            AILogger.w(TAG, "libOpenCL.so not found in system paths - GPU acceleration may be disabled");
            openClPreloaded = true;
        }
    }

    /**
     * 应用优化设置（GPU优先，失败回退到CPU）
     */
    private void applyOptimizations() {
        try {
            preloadOpenClIfNeeded();
            GpuCapabilityDetector gpuDetector = new GpuCapabilityDetector(context);
            GpuInfo gpuInfo = gpuDetector.detectGpuInfo();
            GpuProfile gpuProfile = gpuDetector.getGpuProfile();
            MemoryUsageInfo memoryInfo = gpuDetector.getMemoryUsageInfo();

            boolean hasVulkanSupport = gpuInfo != null && gpuInfo.supportsVulkan;
            boolean hasFP16Support = gpuInfo != null && gpuInfo.supportsFP16;
            
            long availableMemoryMB = memoryInfo.availableMemoryMB;
            long totalMemoryMB = memoryInfo.totalMemoryMB;
            int cpuCores = Runtime.getRuntime().availableProcessors();
            
            AILogger.i(TAG, "=== Hardware Capability Detection ===");
            AILogger.i(TAG, "GPU Renderer: " + (gpuInfo != null ? gpuInfo.renderer : "Unknown"));
            AILogger.i(TAG, "Vulkan Support: " + hasVulkanSupport + ", FP16 Support: " + hasFP16Support);
            AILogger.i(TAG, "Total Memory: " + totalMemoryMB + "MB, Available: " + availableMemoryMB + "MB");
            AILogger.i(TAG, "CPU Cores: " + cpuCores);

            boolean hasGpuSupport = checkGpuSupport(gpuInfo, gpuDetector);
            
            int threadCount = hasGpuSupport ? Math.min(cpuCores, 8) : Math.min(cpuCores, 6);
            int batchSize = calculateOptimalBatchSize(hasGpuSupport, availableMemoryMB, totalMemoryMB);
            int memoryPoolSize = calculateOptimalMemoryPoolSize(hasGpuSupport, availableMemoryMB, totalMemoryMB);

            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(batchSize);
            LlamaHelper.setMemoryPoolSize(memoryPoolSize);

            AILogger.i(TAG, "=== Optimizations Applied (GPU auto-detected by Native) ===");
            AILogger.i(TAG, "Mode: " + (hasGpuSupport ? "GPU (auto-detect)" : "CPU"));
            AILogger.i(TAG, "Memory Pool: " + memoryPoolSize + "MB");
            AILogger.i(TAG, "Batch Size: " + batchSize);
            AILogger.i(TAG, "Thread Count: " + threadCount);
        } catch (Exception e) {
            AILogger.e(TAG, "Error applying settings: " + e.getMessage(), e);
            LlamaHelper.setThreadCount(Runtime.getRuntime().availableProcessors());
            LlamaHelper.setBatchSize(64);
            LlamaHelper.setMemoryPoolSize(512);
        }
    }
    
    /**
     * 检查设备是否有 GPU 加速支持
     * OpenCL 优先（Adreno GPU 对 OpenCL 支持更好），Vulkan 作为后备
     */
    private boolean checkGpuSupport(GpuInfo gpuInfo, GpuCapabilityDetector gpuDetector) {
        GpuCapabilityDetector.OpenCLInfo openclInfo = gpuDetector.detectOpenCLInfo();
        if (openclInfo != null) {
            AILogger.i(TAG, "GPU support detected via OpenCL (prioritized): " + openclInfo.deviceName + 
                    ", version: " + openclInfo.openclVersion);
            return true;
        }
        
        if (gpuDetector.isOpenCLAvailable()) {
            AILogger.i(TAG, "GPU support detected via OpenCL library loaded");
            return true;
        }
        
        long availableGpuMem = gpuDetector.getAvailableGpuMemoryMB();
        if (availableGpuMem > 0) {
            AILogger.i(TAG, "GPU support detected via available GPU memory: " + availableGpuMem + "MB");
            return true;
        }
        
        if (gpuInfo != null && gpuInfo.supportsVulkan) {
            AILogger.i(TAG, "GPU support detected via Vulkan (fallback)");
            return true;
        }
        
        AILogger.w(TAG, "No GPU support detected");
        return false;
    }
    
    /**
     * 计算最优批处理大小
     * 根据设备总内存和GPU/CPU模式动态计算
     */
    private int calculateOptimalBatchSize(boolean isGpuMode, long availableMB, long totalMB) {
        int batchSize;
        
        if (isGpuMode) {
            if (totalMB >= 12288) {
                batchSize = 512;
            } else if (totalMB >= 8192) {
                batchSize = 512;
            } else if (totalMB >= 6144) {
                batchSize = 256;
            } else if (totalMB >= 4096) {
                batchSize = 256;
            } else {
                batchSize = 128;
            }
        } else {
            if (totalMB >= 12288) {
                batchSize = 256;
            } else if (totalMB >= 8192) {
                batchSize = 256;
            } else if (totalMB >= 6144) {
                batchSize = 128;
            } else if (totalMB >= 4096) {
                batchSize = 128;
            } else {
                batchSize = 64;
            }
        }
        
        AILogger.i(TAG, "calculateOptimalBatchSize: mode=" + (isGpuMode ? "GPU" : "CPU") + 
                ", totalMem=" + totalMB + "MB, availableMem=" + availableMB + "MB, batchSize=" + batchSize);
        
        return batchSize;
    }
    
    /**
     * 计算最优内存池大小
     */
    private int calculateOptimalMemoryPoolSize(boolean isGpuMode, long availableMB, long totalMB) {
        // 内存池不能超过可用内存的25%
        long maxPoolSize = (long) (availableMB * 0.25);
        
        if (isGpuMode) {
            // GPU模式需要更多内存
            int target = Math.min((int) maxPoolSize, 1024);
            return Math.max(512, target);
        } else {
            // CPU模式使用保守值
            int target = Math.min((int) maxPoolSize, 512);
            return Math.max(256, target);
        }
    }
    
    /**
     * 安全地设置内存池大小，确保不超过设备内存的安全阈值
     */
    private int safeMemoryPoolSize(long totalDeviceMem, int requestedSize) {
        // 强制内存池大小上限，防止虚拟内存过度膨胀
        final int MAX_MEMORY_POOL_SIZE = 4096; // 4GB上限
        final int MIN_MEMORY_POOL_SIZE = 512;  // 512MB下限
        
        // 如果无法获取设备内存，使用保守值
        if (totalDeviceMem <= 0) {
            AILogger.w(TAG, "Cannot get device memory, using conservative memory pool size: " + MIN_MEMORY_POOL_SIZE + " MB");
            return MIN_MEMORY_POOL_SIZE;
        }
        
        long totalMemMB = totalDeviceMem / (1024 * 1024);
        
        // 安全阈值：不超过总内存的30%
        long maxSafePoolSize = (long) (totalMemMB * 0.3);
        
        // 确保不超过强制上限
        maxSafePoolSize = Math.min(maxSafePoolSize, MAX_MEMORY_POOL_SIZE);
        
        int safeSize = (int) Math.min(requestedSize, maxSafePoolSize);
        safeSize = Math.max(safeSize, MIN_MEMORY_POOL_SIZE);
        
        if (safeSize < requestedSize) {
            AILogger.w(TAG, "Memory pool size adjusted from " + requestedSize + " MB to " + safeSize + " MB for safety");
        }
        
        AILogger.i(TAG, "Memory pool size set to " + safeSize + " MB (max allowed: " + MAX_MEMORY_POOL_SIZE + " MB)");
        return safeSize;
    }
    
    /**
     * 小米14专用优化
     */
    private void applyXiaomi14Optimizations(long totalDeviceMem) {
        AILogger.i(TAG, "Applying Xiaomi 14 specific optimizations");
        
        // 小米14搭载Snapdragon 8 Gen 3，Adreno 750
        // GPU layers 由 Native 层自动检测
        LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 4096));
        LlamaHelper.setThreadCount(8);
        LlamaHelper.setBatchSize(512);
        
        AILogger.i(TAG, "Xiaomi 14 optimizations applied: GPU=auto-detect, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=512, Threads=8");
    }
    
    /**
     * Adreno GPU优化
     */
    private void applyAdrenoOptimizations(String series, long totalDeviceMem) {
        AILogger.i(TAG, "Applying Adreno optimizations for series: " + series);
        
        switch (series) {
            case "A7XX":
                // Adreno 740/750 - 高端GPU
                // GPU layers 由 Native 层自动检测
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 4096));
                LlamaHelper.setThreadCount(8);
                LlamaHelper.setBatchSize(512);
                AILogger.i(TAG, "A7XX optimizations applied: GPU=auto-detect, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=512, Threads=8");
                break;
                
            case "A6XX":
                // Adreno 650 - 中端GPU
                // GPU layers 由 Native 层自动检测
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 1024));
                LlamaHelper.setThreadCount(6);
                LlamaHelper.setBatchSize(256);
                AILogger.i(TAG, "A6XX optimizations applied: GPU=auto-detect, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=256, Threads=6");
                break;
                
            case "A5XX":
                // Adreno 5xx - 低端GPU
                // GPU layers 由 Native 层自动检测
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 512));
                LlamaHelper.setThreadCount(4);
                LlamaHelper.setBatchSize(128);
                AILogger.i(TAG, "A5XX optimizations applied: GPU=auto-detect, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=128, Threads=4");
                break;
                
            default:
                // 通用优化
                // GPU layers 由 Native 层自动检测
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 256));
                LlamaHelper.setThreadCount(4);
                LlamaHelper.setBatchSize(64);
                AILogger.i(TAG, "Generic Adreno optimizations applied: GPU=auto-detect, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=64, Threads=4");
                break;
        }
    }
    
    /**
     * 基于设备能力的自动调优
     */
    private void autoTuneBasedOnCapabilities() {
        AILogger.i(TAG, "Auto-tuning based on device capabilities");
        
        try {
            // 基于设备能力设置参数 - GPU layers 由 Native 层自动检测
            int cpuCores = DeviceDetector.getCPUCores();
            int threadCount = Math.min(cpuCores, 4); // 默认4线程
            int batchSize = 16; // 默认批处理大小
            
            AILogger.i(TAG, "Auto-tuned parameters - CPU Cores: " + cpuCores + ", Threads: " + threadCount + ", Batch: " + batchSize);
            
            // 设置优化参数 - GPU layers 由 Native 层自动检测
            LlamaHelper.setMemoryPoolSize(1024); // 默认内存池大小
            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(batchSize);
            
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to auto-tune, using default optimizations: " + e.getMessage());
            // 使用默认值
            int cpuCores = DeviceDetector.getCPUCores();
            int threadCount = Math.min(cpuCores, 4); 
            int batchSize = 16;
            
            LlamaHelper.setMemoryPoolSize(1024);
            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(batchSize);
        }
    }

    /**
     * 获取性能指标
     */
    public PerformanceMetrics getPerformanceMetrics() {
        try {
            float inferenceSpeed = LlamaHelper.getInferenceSpeed();
            int tokenCount = LlamaHelper.getTokenCount();

            return new PerformanceMetrics(inferenceSpeed, tokenCount);
        } catch (Exception e) {
            AILogger.e(TAG, "Error getting performance metrics: " + e.getMessage(), e);
            return new PerformanceMetrics(0, 0);
        }
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * 设置当前模型
     */
    public void setCurrentModel(Model model) {
        this.currentModel = model;
    }

    /**
     * 获取当前模型
     */
    public Model getCurrentModel() {
        return currentModel;
    }

    /**
     * 基础状态观察者接口
     */
    public interface StatusObserver {
        void onStatusChanged(boolean isInitialized, String modelName);
    }

    /**
     * 增强的详细状态观察者接口
     */
    public interface DetailedStatusObserver {
        void onStateChanged(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs);
        void onError(String errorMessage);
        void onInitialized(String modelName, long loadTimeMs);
    }

    /**
     * 生成回调接口
     */
    public interface GenerateCallback {
        void onSuccess(String response);
        void onError(Exception error);
    }

    public interface GenerateStreamCallback {
        void onToken(String token);
        void onSuccess(String fullText);
        void onError(Exception error);
    }

    /**
     * 详细加载进度回调
     */
    public interface LoadProgressCallback {
        void onProgress(AIServiceState.ServiceStage stage, String message, int progress);
        void onComplete(boolean success, String modelName, long totalTimeMs);
        void onError(String errorMessage);
    }
    
    /**
     * 注册状态观察者
     */
    public void registerStatusObserver(StatusObserver observer) {
        synchronized (statusObservers) {
            if (!statusObservers.contains(observer)) {
                statusObservers.add(observer);
            }
        }
    }
    
    /**
     * 移除状态观察者
     */
    public void unregisterStatusObserver(StatusObserver observer) {
        synchronized (statusObservers) {
            statusObservers.remove(observer);
        }
    }

    /**
     * 注册详细状态观察者
     */
    public void registerDetailedStatusObserver(DetailedStatusObserver observer) {
        synchronized (detailedStatusObservers) {
            if (!detailedStatusObservers.contains(observer)) {
                detailedStatusObservers.add(observer);
            }
        }
    }

    /**
     * 移除详细状态观察者
     */
    public void unregisterDetailedStatusObserver(DetailedStatusObserver observer) {
        synchronized (detailedStatusObservers) {
            detailedStatusObservers.remove(observer);
        }
    }
    
    /**
     * 通知所有基础状态观察者状态变化
     */
    private void notifyStatusChange() {
        final boolean init = isInitialized;
        final String name = currentModelName;
        final List<StatusObserver> snapshot;
        synchronized (statusObservers) {
            snapshot = new ArrayList<>(statusObservers);
        }
        mainHandler.post(() -> {
            for (StatusObserver observer : snapshot) {
                try {
                    observer.onStatusChanged(init, name);
                } catch (Exception e) {
                    AILogger.e(TAG, "StatusObserver error: " + e.getMessage(), e);
                }
            }
        });
    }

    /**
     * 通知详细状态变化
     */
    private void notifyDetailedStatusChange() {
        final AIServiceState.ServiceStage stage = serviceState.getCurrentStage();
        final String message = serviceState.getStageMessage();
        final int progress = serviceState.getProgressPercent();
        final long elapsed = serviceState.getElapsedTimeMs();
        final List<DetailedStatusObserver> snapshot;
        synchronized (detailedStatusObservers) {
            snapshot = new ArrayList<>(detailedStatusObservers);
        }
        if (!snapshot.isEmpty()) {
            mainHandler.post(() -> {
                for (DetailedStatusObserver observer : snapshot) {
                    try {
                        observer.onStateChanged(stage, message, progress, elapsed);
                    } catch (Exception e) {
                        AILogger.e(TAG, "DetailedStatusObserver error: " + e.getMessage(), e);
                    }
                }
            });
        }
    }

    /**
     * 通知初始化完成
     */
    private void notifyInitialized(long loadTimeMs) {
        final String modelName = currentModelName;
        final List<DetailedStatusObserver> snapshot;
        synchronized (detailedStatusObservers) {
            snapshot = new ArrayList<>(detailedStatusObservers);
        }
        if (!snapshot.isEmpty()) {
            mainHandler.post(() -> {
                for (DetailedStatusObserver observer : snapshot) {
                    try {
                        observer.onInitialized(modelName, loadTimeMs);
                    } catch (Exception e) {
                        AILogger.e(TAG, "notifyInitialized error: " + e.getMessage(), e);
                    }
                }
            });
        }
    }

    /**
     * 通知错误
     */
    private void notifyError(String errorMessage) {
        final List<DetailedStatusObserver> snapshot;
        synchronized (detailedStatusObservers) {
            snapshot = new ArrayList<>(detailedStatusObservers);
        }
        if (!snapshot.isEmpty()) {
            mainHandler.post(() -> {
                for (DetailedStatusObserver observer : snapshot) {
                    try {
                        observer.onError(errorMessage);
                    } catch (Exception e) {
                        AILogger.e(TAG, "notifyError error: " + e.getMessage(), e);
                    }
                }
            });
        }
    }

    /**
     * 获取当前服务状态
     */
    public AIServiceState getServiceState() {
        return serviceState;
    }

    /**
     * 获取当前加载阶段
     */
    public AIServiceState.ServiceStage getCurrentStage() {
        return serviceState.getCurrentStage();
    }

    /**
     * 获取当前加载进度
     */
    public int getLoadingProgress() {
        return serviceState.getProgressPercent();
    }

    /**
     * 获取当前阶段消息
     */
    public String getStageMessage() {
        return serviceState.getStageMessage();
    }

    /**
     * 获取已消耗的加载时间
     */
    public long getLoadingElapsedTimeMs() {
        return serviceState.getElapsedTimeMs();
    }

    /**
     * 是否启用热启动
     */
    public boolean isHotStartEnabled() {
        return hotStartEnabled;
    }

    /**
     * 设置热启动开关
     */
    public void setHotStartEnabled(boolean enabled) {
        this.hotStartEnabled = enabled;
    }

    /**
     * 更新服务阶段并通知观察者
     */
    private void updateServiceStage(AIServiceState.ServiceStage stage, String message) {
        serviceState.setCurrentStage(stage, message);
        notifyDetailedStatusChange();
    }

    /**
     * 更新服务阶段和进度并通知观察者
     */
    private void updateServiceStage(AIServiceState.ServiceStage stage, String message, int progress) {
        serviceState.setCurrentStage(stage, message, progress);
        notifyDetailedStatusChange();
    }

    /**
     * 更新进度
     */
    private void updateProgress(int progress) {
        serviceState.setProgressPercent(progress);
        notifyDetailedStatusChange();
    }

    // ==================== 性能统计和动态优化 ====================

    /**
     * 更新推理性能统计
     */
    private void updateInferenceStats(long elapsedMs, int tokenCount, float speed) {
        synchronized (inferenceStatsLock) {
            this.lastInferenceElapsedMs = elapsedMs;
            this.lastTokenCount = tokenCount;
            this.lastInferenceSpeed = speed;

            int newPerformanceLevel = calculatePerformanceLevel(speed);
            if (newPerformanceLevel != lastPerformanceLevel && dynamicOptimizationEnabled) {
                lastPerformanceLevel = newPerformanceLevel;
                applyPerformanceOptimization(newPerformanceLevel);
            }

            AILogger.i(TAG, "推理性能统计 - 耗时: " + elapsedMs + "ms, Token数: " + tokenCount + 
                    ", 速度: " + String.format("%.2f", speed) + " t/s, 性能等级: " + newPerformanceLevel);
        }
    }

    /**
     * 计算性能等级
     */
    private int calculatePerformanceLevel(float speed) {
        if (speed > 15.0f) return PERF_LEVEL_HIGH;
        if (speed > 5.0f) return PERF_LEVEL_MEDIUM;
        return PERF_LEVEL_LOW;
    }

    /**
     * 根据性能等级应用优化
     */
    private void applyPerformanceOptimization(int performanceLevel) {
        AILogger.i(TAG, "应用性能等级 " + performanceLevel + " 的优化配置");
        
        switch (performanceLevel) {
            case PERF_LEVEL_HIGH:
                LlamaHelper.setBatchSize(512);
                AILogger.i(TAG, "高性能模式: batchSize=512");
                break;
            case PERF_LEVEL_MEDIUM:
                LlamaHelper.setBatchSize(256);
                AILogger.i(TAG, "中等性能模式: batchSize=256");
                break;
            case PERF_LEVEL_LOW:
                LlamaHelper.setBatchSize(128);
                int cpuCores = Runtime.getRuntime().availableProcessors();
                int optimizedThreads = Math.max(2, Math.min(cpuCores, 4));
                LlamaHelper.setThreadCount(optimizedThreads);
                AILogger.i(TAG, "低性能模式: batchSize=128, threads=" + optimizedThreads);
                break;
        }
    }

    /**
     * 获取最近的推理性能统计
     */
    public InferenceStats getLastInferenceStats() {
        synchronized (inferenceStatsLock) {
            return new InferenceStats(
                lastInferenceElapsedMs,
                lastTokenCount,
                lastInferenceSpeed,
                lastPerformanceLevel
            );
        }
    }

    /**
     * 开始推理计时
     */
    private void startInferenceTiming() {
        lastInferenceStartTime = System.currentTimeMillis();
    }

    /**
     * 结束推理计时并更新统计
     */
    private void endInferenceTiming(int tokenCount) {
        if (lastInferenceStartTime > 0) {
            long elapsed = System.currentTimeMillis() - lastInferenceStartTime;
            float speed = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
            updateInferenceStats(elapsed, tokenCount, speed);
            lastInferenceStartTime = 0;
        }
    }

    /**
     * 推理性能统计类
     */
    public static class InferenceStats {
        public final long elapsedMs;
        public final int tokenCount;
        public final float tokensPerSecond;
        public final int performanceLevel;

        public InferenceStats(long elapsedMs, int tokenCount, float tokensPerSecond, int performanceLevel) {
            this.elapsedMs = elapsedMs;
            this.tokenCount = tokenCount;
            this.tokensPerSecond = tokensPerSecond;
            this.performanceLevel = performanceLevel;
        }

        @Override
        public String toString() {
            return "InferenceStats{" +
                    "elapsedMs=" + elapsedMs +
                    ", tokenCount=" + tokenCount +
                    ", tokensPerSecond=" + String.format("%.2f", tokensPerSecond) +
                    ", performanceLevel=" + performanceLevel +
                    '}';
        }
    }

    /**
     * 启用/禁用动态优化
     */
    public void setDynamicOptimizationEnabled(boolean enabled) {
        this.dynamicOptimizationEnabled = enabled;
    }

    /**
     * 获取是否启用动态优化
     */
    public boolean isDynamicOptimizationEnabled() {
        return dynamicOptimizationEnabled;
    }
    
    // ==================== 生命周期管理 ====================

    /**
     * 尝试热启动恢复
     * @param callback 恢复完成回调，可为null
     * @return true 如果启动了恢复流程，false 如果模型已在内存中
     */
    public boolean tryHotStart(HotStartCallback callback) {
        if (!hotStartEnabled) {
            AILogger.i(TAG, "热启动已禁用");
            return false;
        }

        boolean modelInMemory = LlamaHelper.isModelInitialized();
        if (modelInMemory && isInitialized) {
            AILogger.i(TAG, "模型已在内存中，无需热启动");
            if (callback != null) {
                callback.onHotStartComplete(true, "模型已就绪");
            }
            return false;
        }

        if (currentModelName == null) {
            AILogger.w(TAG, "没有保存的模型名称，无法热启动");
            if (callback != null) {
                callback.onHotStartComplete(false, "未选择模型");
            }
            return false;
        }

        if (isLoading || isPreloading) {
            AILogger.w(TAG, "模型正在加载中，跳过热启动");
            return true;
        }

        AILogger.i(TAG, "开始热启动恢复，模型: " + currentModelName);

        modelInitSerialExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            boolean success = initialize(currentModelName);
            long loadTime = System.currentTimeMillis() - startTime;

            AILogger.i(TAG, "热启动" + (success ? "成功" : "失败") + "，耗时: " + loadTime + "ms");

            if (callback != null) {
                mainHandler.post(() -> 
                    callback.onHotStartComplete(success, success ? "热启动成功，耗时 " + (loadTime/1000) + "秒" : "热启动失败")
                );
            }
        });

        return true;
    }

    /**
     * 后台预加载模型
     */
    public void preloadInBackground() {
        if (isPreloading || isLoading || isInitialized) {
            return;
        }

        if (currentModelName == null) {
            AILogger.i(TAG, "没有保存的模型，跳过后台预加载");
            return;
        }

        if (isAppInBackground) {
            AILogger.i(TAG, "应用在后台，开始后台预加载模型: " + currentModelName);
            isPreloading = true;

            modelInitSerialExecutor.execute(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    boolean success = initialize(currentModelName);
                    long loadTime = System.currentTimeMillis() - startTime;
                    AILogger.i(TAG, "后台预加载" + (success ? "成功" : "失败") + "，耗时: " + loadTime + "ms");
                } finally {
                    isPreloading = false;
                }
            });
        }
    }

    /**
     * 检查是否可以热启动
     */
    public boolean canHotStart() {
        return hotStartEnabled && currentModelName != null && !isInitialized;
    }

    /**
     * 热启动回调接口
     */
    public interface HotStartCallback {
        void onHotStartComplete(boolean success, String message);
    }

    /**
     * 应用进入后台时调用
     */
    public void onAppEnterBackground() {
        isAppInBackground = true;
        AILogger.i(TAG, "应用进入后台，AI服务状态: " + (isInitialized ? "已初始化" : "未初始化"));

        // 启动空闲监控
        if (idleMonitorFuture == null) {
            idleMonitorFuture = watchdogScheduler.scheduleWithFixedDelay(() -> {
                if (isAppInBackground) {
                    long idleTime = getTimeSinceLastUse();
                    AILogger.d(TAG, "后台空闲监控: " + (idleTime / 1000) + "秒");
                    smartResourceManagement(5 * 60 * 1000, false);
                }
            }, 30, 60, TimeUnit.SECONDS);
        }
    }
    
    /**
     * 应用回到前台时调用
     */
    public void onAppEnterForeground() {
        isAppInBackground = false;
        lastUsedTimestamp = System.currentTimeMillis();
        AILogger.i(TAG, "应用回到前台，检查AI服务状态...");

        // 取消空闲监控
        if (idleMonitorFuture != null) {
            idleMonitorFuture.cancel(false);
            idleMonitorFuture = null;
        }
        
        // 检查模型是否在内存中
        boolean modelInMemory = LlamaHelper.isModelInitialized();
        AILogger.i(TAG, "模型在内存中: " + modelInMemory + ", Java状态: " + isInitialized);
        
        // 如果模型不在内存但Java状态显示已初始化，同步状态
        if (isInitialized && !modelInMemory) {
            AILogger.w(TAG, "模型已从内存中释放，重置Java状态");
            isInitialized = false;
            notifyStatusChange();
        }
        
        // 如果模型在内存但Java状态未标记，同步状态
        if (!isInitialized && modelInMemory) {
            AILogger.i(TAG, "检测到模型在内存中，同步Java状态");
            isInitialized = true;
            notifyStatusChange();
        }
        
        // 如果模型不在内存但有保存的模型，尝试热启动
        if (!isInitialized && !modelInMemory && currentModelName != null && hotStartEnabled) {
            AILogger.i(TAG, "检测到模型未在内存中，将自动尝试热启动");
            // 这里不自动加载，而是通知观察者让UI显示加载状态
            // 实际加载由调用者决定（如在用户需要时）
        }

        AILogger.i(TAG, "应用回到前台处理完成，当前状态: " + getStatusInfo());
    }
    
    /**
     * 更新使用时间戳（每次使用AI时调用）
     */
    public void updateLastUsedTime() {
        lastUsedTimestamp = System.currentTimeMillis();
    }
    
    /**
     * 获取距离上次使用的时间（毫秒）
     */
    public long getTimeSinceLastUse() {
        return System.currentTimeMillis() - lastUsedTimestamp;
    }
    
    /**
     * 智能资源管理 - 根据使用情况决定是否释放资源
     * @param maxIdleTime 最大空闲时间（毫秒），超过这个时间可能释放资源
     * @param force 强制立即释放资源
     */
    public void smartResourceManagement(long maxIdleTime, boolean force) {
        if (force) {
            AILogger.i(TAG, "强制释放AI资源");
            release();
            return;
        }
        
        long idleTime = getTimeSinceLastUse();
        if (idleTime > maxIdleTime && isInitialized && isAppInBackground) {
            AILogger.i(TAG, "AI服务已空闲" + (idleTime / 1000) + "秒且应用在后台，考虑释放资源");
            // 注意：这里不立即释放，因为重新加载代价大
            // 只是记录日志，让系统决定是否回收
        }
    }
    
    /**
     * 处理内存紧张情况
     * @param level 内存紧张级别
     */
    public void onTrimMemory(int level) {
        AILogger.i(TAG, "收到内存紧张通知，级别: " + level);
        
        if (isInitialized) {
            long idleTime = getTimeSinceLastUse();
            int idleMinutes = (int) (idleTime / 1000 / 60);
            AILogger.i(TAG, "模型已加载，空闲时间: " + idleMinutes + "分钟，保持模型在内存中（热启动）");
        }
    }
    
    /**
     * 获取AI服务当前状态信息
     */
    public String getStatusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("AI服务状态: ").append(isInitialized ? "运行中" : "未初始化").append("\n");
        sb.append("当前模型: ").append(currentModelName != null ? currentModelName : "无").append("\n");
        sb.append("模型在内存: ").append(LlamaHelper.isModelInitialized() ? "是" : "否").append("\n");
        sb.append("应用状态: ").append(isAppInBackground ? "后台" : "前台").append("\n");
        sb.append("空闲时间: ").append(getTimeSinceLastUse() / 1000).append("秒");
        return sb.toString();
    }

    /**
     * 性能指标类
     */
    public static class PerformanceMetrics {
        public final float inferenceSpeed;
        public final int tokenCount;

        public PerformanceMetrics(float inferenceSpeed, int tokenCount) {
            this.inferenceSpeed = inferenceSpeed;
            this.tokenCount = tokenCount;
        }
    }

    // ========== Native Chat Context API ==========
    private String chatSystemPrompt = null;

    private void initDefaultChatContext() {
        try {
            String globalPrompt = "你是一个智能AI助手，精通多种领域知识。请使用中文与用户交流。";
            String systemPrompt = "你是一个乐于助人的AI助手。请用中文回答用户的问题。";
            String normalPrompt = "";

            if (!LlamaHelper.isModelInitialized()) {
                AILogger.e(TAG, "Cannot create chat context: model not initialized");
                return;
            }

            int nThreads = LlamaHelper.getThreadCount();
            if (nThreads <= 0) nThreads = 4;

            long handle = tryCreateChatContextWithFallback(globalPrompt, systemPrompt, normalPrompt, nThreads);
            if (handle != 0) {
                AILogger.i(TAG, "Default chat context created successfully: " + LlamaHelper.chatGetInfo());
            } else {
                AILogger.w(TAG, "Failed to create default chat context after all fallbacks");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error creating default chat context: " + e.getMessage(), e);
        }
    }

    private long tryCreateChatContextWithFallback(String globalPrompt, String systemPrompt, String normalPrompt, int nThreads) {
        int[] ctxSizes = { 8192, 4096, 2048, 1024 };

        for (int ctxSize : ctxSizes) {
            AILogger.i(TAG, "Trying to create chat context with ctxSize=" + ctxSize);
            try {
                long handle = LlamaHelper.chatCreate("", ctxSize, nThreads, globalPrompt, systemPrompt, normalPrompt);
                if (handle != 0) {
                    AILogger.i(TAG, "Successfully created chat context with ctxSize=" + ctxSize);
                    return handle;
                }
                AILogger.w(TAG, "Failed to create context with ctxSize=" + ctxSize + ", trying smaller size");
            } catch (Exception e) {
                AILogger.w(TAG, "Exception creating context with ctxSize=" + ctxSize + ": " + e.getMessage());
            }
        }
        return 0;
    }

    public boolean restoreChatHistory(long conversationId) {
        if (chatRepository == null) {
            AILogger.w(TAG, "Cannot restore history: chatRepository is null");
            return false;
        }
        try {
            List<PromptBuilder.Message> messages = chatRepository.getMessages(conversationId);
            if (messages == null || messages.isEmpty()) {
                AILogger.i(TAG, "No history to restore for conversation " + conversationId);
                return true;
            }

            int maxRestorePairs = 4;
            int startIdx = Math.max(0, messages.size() - maxRestorePairs * 2);
            StringBuilder historyBuilder = new StringBuilder();
            historyBuilder.append("\n\n[之前的对话历史]\n");

            for (int i = startIdx; i < messages.size(); i++) {
                PromptBuilder.Message msg = messages.get(i);
                String role = msg.role();
                String content = msg.content();
                if (content == null || content.trim().isEmpty()) continue;
                if (content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                if ("user".equals(role)) {
                    historyBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    historyBuilder.append("助手: ").append(content).append("\n");
                }
            }

            historyBuilder.append("[历史结束，以下是新对话]\n");

            if (LlamaHelper.isChatContextActive()) {
                String currentPrompt = chatSystemPrompt != null ? chatSystemPrompt : "";
                String updatedPrompt = currentPrompt + historyBuilder.toString();
                LlamaHelper.chatUpdatePrompts(null, updatedPrompt, null);
                AILogger.i(TAG, "Chat history restored into system prompt: " + (messages.size() - startIdx) + " messages");
            } else {
                AILogger.w(TAG, "Chat context not active, history will be used via prompt building");
            }
            return true;
        } catch (Exception e) {
            AILogger.e(TAG, "Error restoring chat history: " + e.getMessage());
            return false;
        }
    }

    public boolean initChatContext(String globalPrompt, String systemPrompt, String normalPrompt) {
        synchronized (modelInitLock) {
            if (!isInitialized) {
                AILogger.e(TAG, "AIService not initialized, cannot create chat context");
                return false;
            }

            if (!LlamaHelper.isModelInitialized()) {
                AILogger.e(TAG, "Native model not initialized, cannot create chat context");
                return false;
            }

            LlamaHelper.chatDestroy();

            this.chatSystemPrompt = systemPrompt;
            int nThreads = LlamaHelper.getThreadCount();
            if (nThreads <= 0) nThreads = 4;

            long handle = tryCreateChatContextWithFallback(globalPrompt, systemPrompt, normalPrompt, nThreads);
            if (handle == 0) {
                AILogger.e(TAG, "Failed to create native chat context after all fallbacks");
                return false;
            }

            AILogger.i(TAG, "Native chat context created: " + LlamaHelper.chatGetInfo());
            return true;
        }
    }

    public boolean updateChatPrompts(String globalPrompt, String systemPrompt, String normalPrompt) {
        return LlamaHelper.chatUpdatePrompts(globalPrompt, systemPrompt, normalPrompt);
    }

    public void clearChatContext() {
        LlamaHelper.chatClear();
        AILogger.i(TAG, "Native chat context cleared (token history reset)");
    }

    public void chatSend(String message, int maxTokens, boolean enableThinking, LlamaHelper.TokenCallback callback) {
        updateLastUsedTime();

        if (isLoading) {
            if (callback != null) callback.onError("AI service is loading, please wait");
            return;
        }

        if (!isInitialized) {
            if (callback != null) callback.onError("AI service not initialized");
            return;
        }

        if (!LlamaHelper.isModelInitialized()) {
            if (callback != null) callback.onError("AI model is still loading, please wait");
            return;
        }

        if (!LlamaHelper.isChatContextActive()) {
            if (callback != null) callback.onError("Chat context not active");
            return;
        }

        if (crashHandler != null) {
            crashHandler.recordActivity();
        }

        final long startTime = System.currentTimeMillis();
        final long timeoutMs = 600000;
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.Future<?>[] taskFuture = new java.util.concurrent.Future<?>[1];

        taskFuture[0] = executorService.submit(() -> {
            try {
                ScheduledFuture<?> watchdogFuture = watchdogScheduler.schedule(() -> {
                    if (!completed.get()) {
                        AILogger.w(TAG, "chatSend timeout after " + timeoutMs + "ms, stopping...");
                        LlamaHelper.chatStop();
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError("生成超时，请重试"));
                        }
                        if (taskFuture[0] != null && !taskFuture[0].isDone()) {
                            taskFuture[0].cancel(true);
                        }
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);

                LlamaHelper.chatSend(message, maxTokens, 0.7f, 0.9f, 40, enableThinking, new LlamaHelper.TokenCallback() {
                    @Override
                    public void onToken(String token) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onToken(token));
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        if (!completed.compareAndSet(false, true)) return;
                        watchdogFuture.cancel(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        AILogger.i(TAG, "chatSend: onComplete called, fullText length: " + 
                                (fullText != null ? fullText.length() : 0) + ", elapsed: " + elapsed + "ms");
                        
                        if (callback != null) {
                            mainHandler.post(() -> {
                                try {
                                    callback.onComplete(fullText);
                                } catch (Exception e) {
                                    AILogger.e(TAG, "Error in chatSend onComplete callback: " + e.getMessage(), e);
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (!completed.compareAndSet(false, true)) return;
                        watchdogFuture.cancel(false);
                        AILogger.e(TAG, "chatSend: onError called, error: " + error);
                        
                        if (callback != null) {
                            mainHandler.post(() -> callback.onError(error));
                        }
                    }
                });
            } catch (Exception e) {
                AILogger.e(TAG, "Exception in chatSend task: " + e.getMessage(), e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
            } catch (Throwable t) {
                AILogger.e(TAG, "Throwable in chatSend task: " + t.getMessage(), t);
                if (crashHandler != null) {
                    crashHandler.recordCrashInfo("CHATSEND_NATIVE_CRASH", "chatSend时发生Native崩溃", t);
                }
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("生成失败，可能是内存或模型问题"));
                }
            }
        });
    }

    private void fallbackToSimpleGenerate(String message, int maxTokens, boolean enableThinking, LlamaHelper.TokenCallback callback) {
        AILogger.i(TAG, "Fallback to generateStream: messageLen=" + message.length() + ", maxTokens=" + maxTokens);
        List<PromptBuilder.Message> history = new ArrayList<>();
        generateStream(message, history, maxTokens, new GenerateStreamCallback() {
            @Override
            public void onToken(String token) {
                if (callback != null) callback.onToken(token);
            }
            @Override
            public void onSuccess(String fullText) {
                if (callback != null) callback.onComplete(fullText);
            }
            @Override
            public void onError(Exception e) {
                if (callback != null) callback.onError(e != null ? e.getMessage() : "Unknown error");
            }
        });
    }

    public void chatStop() {
        LlamaHelper.chatStop();
    }

    public void chatClear() {
        LlamaHelper.chatClear();
    }

    public void chatDestroy() {
        LlamaHelper.chatDestroy();
        chatSystemPrompt = null;
    }

    public boolean isChatContextActive() {
        return LlamaHelper.isChatContextActive();
    }

    public String getChatInfo() {
        return LlamaHelper.chatGetInfo();
    }
    
    public int getContextSize() {
        return LlamaHelper.getContextSize();
    }
    
    public int getContextUsedTokens() {
        return LlamaHelper.getContextUsedTokens();
    }
    
    public int getContextRemainingTokens() {
        return LlamaHelper.getContextRemainingTokens();
    }
    
    public float getContextUsagePercent() {
        return LlamaHelper.getContextUsagePercent();
    }
    
    public void logContextStats() {
        if (!isChatContextActive()) {
            AILogger.i(TAG, "Context Stats: No active chat context");
            return;
        }
        
        int total = getContextSize();
        int used = getContextUsedTokens();
        int remaining = getContextRemainingTokens();
        float percent = getContextUsagePercent();
        
        AILogger.i(TAG, "========== Context Statistics ==========");
        AILogger.i(TAG, "Total Context Size: " + total + " tokens");
        AILogger.i(TAG, "Used Tokens: " + used + " tokens");
        AILogger.i(TAG, "Remaining Tokens: " + remaining + " tokens");
        AILogger.i(TAG, "Usage: " + String.format("%.2f", percent) + "%");
        AILogger.i(TAG, "=========================================");
    }
}
