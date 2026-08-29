package com.oilquiz.app.ai.service;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.oilquiz.app.util.AILogger;

import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.model.Model;
import com.oilquiz.app.ai.model.ModelChunkLoader;
import com.oilquiz.app.ai.model.ModelDownloadManager;
import com.oilquiz.app.ai.model.ModelManager;
import com.oilquiz.app.ai.model.ModelMemoryManager;
import com.oilquiz.app.ai.model.ModelStateCache;
import com.oilquiz.app.ai.model.ModelRegistry;
import com.oilquiz.app.ai.model.ModelTransferManager;
import com.oilquiz.app.ai.optimization.DeviceDetector;
import com.oilquiz.app.ai.optimization.ModelColdStartOptimizer;
import com.oilquiz.app.ai.optimization.ResourceConfig;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.gpu.GpuDatabase;
import com.oilquiz.app.ai.gpu.GpuInfo;
import com.oilquiz.app.ai.gpu.GpuProfile;
import com.oilquiz.app.ai.gpu.GpuTier;
import com.oilquiz.app.ai.gpu.MemoryUsageInfo;
import com.oilquiz.app.ai.util.PromptBuilder;
import com.oilquiz.app.ai.db.ChatRepository;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.refactor.AIConfig.OptimizationMode;
import com.oilquiz.app.ai.refactor.UnifiedContextManager;
import java.util.ArrayList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AIService implements ComponentCallbacks2 {
    private static final String TAG = "AIService";
    private static final int N_CTX_DEFAULT = 8192;
    private static final int N_CTX_LOW_MEMORY = 4096;
    private static final int N_CTX_HIGH_END = 12288;
    public static final int INFERENCE_N_CTX = N_CTX_DEFAULT;
    public static final int CHAT_N_CTX = N_CTX_DEFAULT;
    private static final String MODEL_DIR_NAME = "ai_models";
    private static final String PREFS_NAME = "ai_service_prefs";
    private static final String PREF_CURRENT_MODEL = "current_model";
    private static final String PREF_OPTIMIZATION_MODE = "optimization_mode";
    private static AIService instance;

    // Native 状态自动恢复回调
    public interface NativeStateRecoveryListener {
        void onRecoveryStarted(int attemptCount);
        void onRecoverySuccess(long recoveryTimeMs);
        void onRecoveryFailed(int attemptCount, int maxAttempts, String reason);
    }
    private volatile NativeStateRecoveryListener nativeStateRecoveryListener = null;

    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private AICrashHandler crashHandler;
    private Model currentModel;
    private String currentModelName = null;
    private boolean isLoading = false;
    private boolean isInitialized = false;
    private OptimizationMode optimizationMode = OptimizationMode.BALANCED;
    private AIConfig aiConfig;
    private boolean isAppInBackground = false;
    private long lastUsedTimestamp = System.currentTimeMillis();
    private ChatRepository chatRepository;
    // 组4.4：推理期间 WakeLock，防止灭屏/切后台时 CPU 低功耗挂起导致 decode 停顿
    private PowerManager.WakeLock inferenceWakeLock;

    // 分块加载器
    private ModelChunkLoader modelChunkLoader;

    // 冷启动优化器
    private ModelColdStartOptimizer coldStartOptimizer;

    // 增强的服务状态管理
    private final AIServiceState serviceState = new AIServiceState();

    // 状态观察者列表
    private List<StatusObserver> statusObservers = new ArrayList<>();
    private List<DetailedStatusObserver> detailedStatusObservers = new ArrayList<>();

    // 预加载和热启动管理
    private volatile boolean isPreloading = false;
    private volatile boolean hotStartEnabled = true;
    private ScheduledFuture<?> idleMonitorFuture = null;
    
    // 热启动状态缓存，避免频繁调用 JNI 和重复日志
    private static volatile long lastHotStartCheckTime = 0;
    private static volatile boolean cachedModelInMemory = false;
    private static final long HOT_START_CHECK_INTERVAL_MS = 2000;

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
    private volatile boolean isChatActive = false;
    private final Object chatContextLock = new Object();
    private volatile long lastChatContextHandle = 0;
    private volatile long activeChatGenerationCount = 0;

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

        // 从SharedPreferences加载优化模式
        loadOptimizationMode();

        // 模型状态缓存：禁用自动恢复——App启动不自动加载模型，后台内存归零
        // （模型在用户首次进入AI页面时按需加载，不再在构造函数中自动 restoreModelState）
        ModelStateCache cache = ModelStateCache.getInstance(context);
        // 原来的自动恢复代码已注释——防止进程被杀后 START_STICKY 重启时又自动加载模型导致崩溃循环
        // if (cache.hasRestorableState() && !cache.isCacheExpired()) {
        //     AILogger.i(TAG, "Found restorable model state, attempting to restore...");
        //     cache.restoreModelState((success, message) -> {
        //         AILogger.i(TAG, "Model state restore: " + message);
        //     });
        // }
        
        // 初始化聊天记录仓库
        chatRepository = new ChatRepository(context);

        // 初始化统一上下文管理器
        UnifiedContextManager.getInstance(context);

        // 组4.4：初始化推理 WakeLock
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                inferenceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartQuiz:AIInference");
                inferenceWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to init WakeLock: " + e.getMessage());
        }

        // 初始化分块加载器
        this.modelChunkLoader = new ModelChunkLoader();

        // 初始化冷启动优化器
        this.coldStartOptimizer = new ModelColdStartOptimizer(context);

        AILogger.i(TAG, "ThreadPool initialized with " + threadPoolSize + " threads");
        AILogger.i(TAG, "ChatRepository initialized");
        AILogger.i(TAG, "UnifiedContextManager initialized");
        AILogger.i(TAG, "ModelChunkLoader initialized");
        AILogger.i(TAG, "ModelColdStartOptimizer initialized");

        // 注册 ComponentCallbacks2 —— 系统内存紧张时主动释放模型，不等 OOM
        try {
            context.registerComponentCallbacks(this);
            AILogger.i(TAG, "ComponentCallbacks2 registered for memory pressure handling");
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to register ComponentCallbacks2: " + e.getMessage());
        }
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
     * 从SharedPreferences加载优化模式
     */
    private void loadOptimizationMode() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int modeId = OptimizationMode.BALANCED.id;
        boolean needReset = false;
        
        try {
            if (prefs.contains(PREF_OPTIMIZATION_MODE)) {
                modeId = prefs.getInt(PREF_OPTIMIZATION_MODE, OptimizationMode.BALANCED.id);
            } else if (prefs.contains("optimization_enabled")) {
                AILogger.w(TAG, "Old boolean optimization_enabled found, migrating to optimization_mode");
                needReset = true;
            }
        } catch (ClassCastException e) {
            AILogger.w(TAG, "Invalid type for optimization_mode (Boolean stored as Integer?): " + e.getMessage());
            needReset = true;
        }
        
        if (needReset) {
            try {
                prefs.edit()
                    .remove(PREF_OPTIMIZATION_MODE)
                    .remove("optimization_enabled")
                    .putInt(PREF_OPTIMIZATION_MODE, modeId)
                    .apply();
                AILogger.i(TAG, "Reset optimization_mode to default value: " + modeId);
            } catch (Exception e) {
                AILogger.e(TAG, "Failed to reset optimization_mode: " + e.getMessage(), e);
            }
        }
        
        optimizationMode = OptimizationMode.fromId(modeId);
        AILogger.i(TAG, "Loaded optimization mode: " + optimizationMode.displayName);
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
     * 保存优化模式到SharedPreferences
     */
    private void saveOptimizationMode(OptimizationMode mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREF_OPTIMIZATION_MODE, mode.id);
        editor.apply();
        optimizationMode = mode;
        AILogger.i(TAG, "Saved optimization mode: " + mode.displayName);
    }
    
    /**
     * 获取优化模式
     */
    public OptimizationMode getOptimizationMode() {
        return optimizationMode;
    }
    
    /**
     * 设置优化模式
     */
    public void setOptimizationMode(OptimizationMode mode) {
        saveOptimizationMode(mode);
    }

    public static synchronized AIService getInstance(Context context) {
        if (instance == null) {
            instance = new AIService(context);
        }

        if (!instance.hotStartEnabled) {
            return instance;
        }

        long now = System.currentTimeMillis();
        boolean modelInMemory;
        boolean shouldCheck = (now - lastHotStartCheckTime) > HOT_START_CHECK_INTERVAL_MS;
        
        if (shouldCheck) {
            modelInMemory = LlamaHelper.isModelInitialized();
            cachedModelInMemory = modelInMemory;
            lastHotStartCheckTime = now;
        } else {
            modelInMemory = cachedModelInMemory;
        }

        if (modelInMemory && !instance.isInitialized) {
            instance.isInitialized = true;
        }

        if (instance.isInitialized && !modelInMemory) {
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
     * 原子重载当前模型：释放旧资源 + 重新加载，在同一个串行执行器中完成。
     *
     * ⚠ 切勿使用 release() + initializeAsync() 组合：
     * release() 走多线程 executorService，initializeAsync() 走 modelInitSerialExecutor，
     * 两个任务并发抢 modelInitLock 顺序不定——若 initialize 先完成、release 后执行，
     * 模型会被卸载且 UI 仍显示"重载完成"。本方法在同一串行线程内完成两步，顺序有保证。
     */
    public void reloadModelAsync(InitializeCallback callback) {
        modelInitSerialExecutor.execute(() -> {
            boolean success = false;
            synchronized (modelInitLock) {
                try {
                    releaseNativeResourcesLocked(false);
                    AILogger.i(TAG, "reloadModelAsync: released old resources");
                } catch (Exception e) {
                    AILogger.e(TAG, "reloadModelAsync release error: " + e.getMessage(), e);
                }
                // initialize() 内部 synchronized(modelInitLock) 可重入
                success = initialize();
            }
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
            // 模型文件不存在时明确提示，而不是反复报"加载失败"
            if (!isCurrentModelFileExists()) {
                String err = "模型文件不存在: " + currentModelName + "（文件可能已被删除，请重新导入或切换模型）";
                AILogger.e(TAG, err);
                serviceState.setError(err);
                notifyError(err);
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
                return Boolean.TRUE.equals(task.get(30, TimeUnit.SECONDS));
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
                return Boolean.TRUE.equals(task.get(30, TimeUnit.SECONDS));
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
                return Boolean.TRUE.equals(task.get(30, TimeUnit.SECONDS));
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
                return Boolean.TRUE.equals(task.get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                AILogger.e(TAG, "reloadCurrentModelSafe: " + e.getMessage(), e);
                return false;
            }
        }
        return reloadCurrentModel();
    }

    /**
     * 设置 Native 状态自动恢复监听器
     */
    public void setNativeStateRecoveryListener(NativeStateRecoveryListener listener) {
        this.nativeStateRecoveryListener = listener;
    }

    /**
     * 自动检测 Native 层状态并尝试恢复
     * 
     * @param pendingMessage 待发送的消息（恢复成功后用于重新发送），可为 null
     * @param callback 恢复结果回调
     */
    public void autoRecoverNativeState(String pendingMessage, NativeStateRecoveryCallback callback) {
        if (currentModelName == null) {
            AILogger.w(TAG, "autoRecoverNativeState: no model selected");
            if (callback != null) callback.onRecoveryFailed("未选择模型");
            return;
        }

        if (!LlamaHelper.canAttemptRecovery()) {
            int attempts = LlamaHelper.getRecoveryAttemptCount();
            String reason = "恢复尝试过于频繁，请稍后再试（已尝试 " + attempts + " 次）";
            AILogger.w(TAG, reason);
            notifyRecoveryFailed(attempts, LlamaHelper.getRecoveryAttemptCount(), reason);
            if (callback != null) callback.onRecoveryFailed(reason);
            return;
        }

        LlamaHelper.recordRecoveryAttempt();
        int attemptCount = LlamaHelper.getRecoveryAttemptCount();
        AILogger.i(TAG, "Auto-recovering native state, attempt #" + attemptCount);
        notifyRecoveryStarted(attemptCount);

        modelInitSerialExecutor.execute(() -> {
            long recoveryStartTime = System.currentTimeMillis();
            
            // 优先尝试轻量恢复：如果模型本身还有效，只重建chat context
            boolean modelValid = LlamaHelper.isModelInitialized();
            boolean chatValid = LlamaHelper.isChatContextActive();
            boolean success = false;
            String recoveryMethod = "";

            if (modelValid && !chatValid) {
                // 模型有效但chat context无效，只重建chat context（快速恢复）
                AILogger.i(TAG, "Model still valid, rebuilding chat context only (lightweight recovery)");
                recoveryMethod = "chat context rebuild";
                try {
                    UnifiedContextManager ctxManager = UnifiedContextManager.getInstance();
                    ctxManager.setChatContextReady(false);
                    success = initChatContext(buildDefaultChatSystemPrompt(), "", "");
                } catch (Exception e) {
                    AILogger.e(TAG, "Chat context rebuild failed: " + e.getMessage(), e);
                    success = false;
                }
                
                // 如果chat context重建失败，再尝试完整重新加载模型
                if (!success) {
                    AILogger.w(TAG, "Chat context rebuild failed, falling back to full model reload");
                    recoveryMethod = "full model reload (fallback)";
                    success = reloadCurrentModel();
                }
            } else if (!modelValid) {
                // 模型本身无效，必须重新加载
                AILogger.i(TAG, "Model invalid, performing full model reload");
                recoveryMethod = "full model reload";
                success = reloadCurrentModel();
            } else {
                // 模型和chat context都有效，无需恢复
                AILogger.i(TAG, "Native state already valid, no recovery needed");
                success = true;
                recoveryMethod = "none needed";
            }

            long recoveryTimeMs = System.currentTimeMillis() - recoveryStartTime;

            if (success && LlamaHelper.isNativeStateValid()) {
                LlamaHelper.recordRecoverySuccess();
                AILogger.i(TAG, "Native state recovery successful (" + recoveryMethod + "), took " + recoveryTimeMs + "ms");
                notifyRecoverySuccess(recoveryTimeMs);
                if (callback != null) callback.onRecoverySuccess(pendingMessage);
            } else {
                int maxAttempts = LlamaHelper.getRecoveryAttemptCount();
                String reason = "恢复失败 (" + recoveryMethod + ")" + (!success ? "" : " (Native 状态仍无效)");
                AILogger.e(TAG, "Native state recovery failed: " + reason);
                notifyRecoveryFailed(attemptCount, maxAttempts, reason);
                if (callback != null) callback.onRecoveryFailed(reason);
            }
        });
    }

    private void notifyRecoveryStarted(int attemptCount) {
        NativeStateRecoveryListener listener = nativeStateRecoveryListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onRecoveryStarted(attemptCount));
        }
    }

    private void notifyRecoverySuccess(long recoveryTimeMs) {
        NativeStateRecoveryListener listener = nativeStateRecoveryListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onRecoverySuccess(recoveryTimeMs));
        }
    }

    private void notifyRecoveryFailed(int attemptCount, int maxAttempts, String reason) {
        NativeStateRecoveryListener listener = nativeStateRecoveryListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onRecoveryFailed(attemptCount, maxAttempts, reason));
        }
    }

    /**
     * Native 状态自动恢复回调
     */
    public interface NativeStateRecoveryCallback {
        void onRecoverySuccess(String pendingMessage);
        void onRecoveryFailed(String reason);
    }

    /**
     * 释放 native 模型与聊天上下文。调用方须已持有 {@link #modelInitLock}，或与 {@link #switchModel} 等串行路径配合。
     */
    /**
     * 自动加载多模态投影文件（mmproj）——按 GGUF 权重元数据识别，不依赖模型名：
     * 1. 先释放旧投影（防止切换模型后残留）
     * 2. 读模型 general.architecture：非视觉架构直接跳过；视觉架构才尝试 mmproj
     * 3. 预设精确配对优先；配对失败则逐个尝试目录中所有 mmproj（加载成功 = 与模型匹配）
     * 失败仅记录日志，不影响模型加载主流程。
     */
    private void autoLoadMultimodalIfAvailable(java.io.File modelFile) {
        try {
            // 先清除旧投影（关键：切换模型时旧 mmproj 必须卸载，否则 isMultimodalLoaded 误报）
            LlamaHelper.releaseMultimodal();
            if (modelFile == null) return;
            java.io.File modelDir = modelFile.getParentFile();
            if (modelDir == null || !modelDir.isDirectory()) return;

            // 1) 按 GGUF 权重元数据判断架构（不依赖模型名）
            String arch = LlamaHelper.getModelArchitecture().toLowerCase();
            if (arch.isEmpty()) {
                AILogger.w(TAG, "Multimodal: cannot read model architecture, skip mmproj");
                return;
            }
            if (!isVisionArchitecture(arch)) {
                AILogger.i(TAG, "Multimodal: architecture " + arch + " is not vision, skip mmproj");
                return;
            }
            AILogger.i(TAG, "Multimodal: vision architecture '" + arch + "' detected from GGUF metadata");

            // 2) 预设精确配对优先（下载时 mmproj 自动下载到同目录）
            String mmprojName = findPresetMmprojName(modelFile.getName());
            if (mmprojName != null) {
                java.io.File mmprojFile = new java.io.File(modelDir, mmprojName);
                if (mmprojFile.exists()) {
                    boolean ok = LlamaHelper.loadMultimodal(mmprojFile.getAbsolutePath());
                    AILogger.i(TAG, "Multimodal: preset mmproj " + mmprojFile.getName() + " loaded=" + ok);
                    if (ok) return;
                }
            }

            // 3) 视觉架构下逐个尝试目录中所有 mmproj（loadMultimodal 成功即匹配，
            //    解决"目录多个 mmproj 无法确定归属"的场景——mtmd 加载失败会安全返回 false）
            java.io.File[] files = modelDir.listFiles();
            if (files == null) return;
            java.util.List<java.io.File> mmprojs = new java.util.ArrayList<>();
            for (java.io.File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase();
                if (name.contains("mmproj") && name.endsWith(".gguf")) {
                    mmprojs.add(f);
                }
            }
            for (java.io.File f : mmprojs) {
                boolean ok = LlamaHelper.loadMultimodal(f.getAbsolutePath());
                AILogger.i(TAG, "Multimodal: try mmproj " + f.getName() + " -> " + ok);
                if (ok) return;
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Auto load multimodal failed: " + e.getMessage());
        }
    }

    /** 按 GGUF 架构判断是否为视觉（多模态）架构 */
    private static boolean isVisionArchitecture(String arch) {
        if (arch == null || arch.isEmpty()) return false;
        return arch.contains("qwen2vl") || arch.contains("qwen3vl") || arch.contains("gemma3v")
                || arch.contains("gemma4v") || arch.contains("llava") || arch.contains("mllama")
                || arch.contains("minicpmv") || arch.contains("glm4v") || arch.contains("internvl")
                || arch.contains("hunyuanvl") || arch.contains("kimivl") || arch.contains("pixtral")
                || arch.contains("siglip") || arch.contains("cogvlm") || arch.contains("step3vl")
                || arch.contains("granite4-vision") || arch.contains("exaone4_5")
                || arch.contains("nemotron-v2-vl") || arch.contains("minimax-m3")
                || arch.contains("mimovl") || arch.contains("youtuvl") || arch.contains("yasa2")
                || arch.contains("mobilenetv5") || arch.contains("llama4");
    }

    /** 按模型文件名在预设中查找对应 mmproj 文件名（无则返回 null） */
    private String findPresetMmprojName(String modelFileName) {
        try {
            com.oilquiz.app.ai.model.ModelDownloadManager mgr =
                    com.oilquiz.app.ai.model.ModelDownloadManager.getInstance(context);
            java.util.List<com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo> presets =
                    mgr.getPresetDomesticModels();
            if (presets == null) return null;
            for (com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo p : presets) {
                if (p.mmprojUrl == null || p.mmprojUrl.isEmpty()) continue;
                // 预设模型下载文件名（URL 尾部）与当前模型文件名比对
                String presetModelName = p.downloadUrl.substring(p.downloadUrl.lastIndexOf('/') + 1);
                if (presetModelName.equals(modelFileName)) {
                    return p.mmprojUrl.substring(p.mmprojUrl.lastIndexOf('/') + 1);
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Find preset mmproj failed: " + e.getMessage());
        }
        return null;
    }

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
        UnifiedContextManager.getInstance().resetAll();
    }

    /**
     * 在已持有 {@link #modelInitLock} 的前提下执行模型加载。
     */
    private boolean loadModelLocked(String modelName) {
        long totalStartTime = System.currentTimeMillis();
        long phaseStartTime = totalStartTime;
        
        serviceState.startTiming();
        serviceState.setCurrentModelName(modelName);

        AILogger.i(TAG, "========== MODEL LOADING START ==========");
        AILogger.i(TAG, "Loading model: " + modelName);

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
            
            long fileSizeMB = modelFile.length() / (1024 * 1024);
            AILogger.i(TAG, "[PERF] Model file check: size=" + fileSizeMB + "MB, time="
                    + (System.currentTimeMillis() - phaseStartTime) + "ms");
            phaseStartTime = System.currentTimeMillis();

            updateServiceStage(AIServiceState.ServiceStage.MODEL_LOADING, "加载模型到内存中...", 40);

            AILogger.i(TAG, "Applying optimizations BEFORE model initialization...");
            applyOptimizations();
            AILogger.i(TAG, "[PERF] Optimization apply time: " + (System.currentTimeMillis() - phaseStartTime) + "ms");
            phaseStartTime = System.currentTimeMillis();

            // 使用 ResourceConfig 获取安全的参数
            ResourceConfig resourceConfig = new ResourceConfig(context);
            GpuCapabilityDetector gpuDetector = new GpuCapabilityDetector(context);
            MemoryUsageInfo memoryInfo = gpuDetector.getMemoryUsageInfo();

            // 获取 GPU 显存信息用于计算最优 GPU 层数
            long gpuMemoryMB = 0;
            long maxMemAllocSizeMB = LlamaHelper.getGpuMaxMemAllocSizeMB();
            GpuInfo gpuInfo = gpuDetector.detectGpuInfo();

            AILogger.i(TAG, "========== GPU DETECTION ==========");
            if (gpuInfo != null) {
                AILogger.i(TAG, "GPU Info: renderer=" + gpuInfo.renderer + ", totalMemoryMB=" + gpuInfo.totalMemoryMB);
            } else {
                AILogger.i(TAG, "GPU Info: null");
            }

            if (gpuInfo != null && gpuInfo.totalMemoryMB > 0) {
                gpuMemoryMB = gpuInfo.totalMemoryMB;
                AILogger.i(TAG, "Using GPU total memory: " + gpuMemoryMB + "MB");
            } else {
                // 尝试从 OpenCL 获取 GPU 内存
                gpuMemoryMB = gpuDetector.getAvailableGpuMemoryMB();
                AILogger.i(TAG, "Using OpenCL detected memory: " + gpuMemoryMB + "MB");
            }
            AILogger.i(TAG, "maxMemAllocSize: " + maxMemAllocSizeMB + "MB");
            AILogger.i(TAG, "====================================");

            // 获取模型文件大小用于估算层数与上下文（大模型需降低上下文减少 KV 内存）
            long modelSizeMB = modelFile.length() / (1024 * 1024);

            // 获取上下文大小用于计算 KV 缓存（模型尺寸感知：8B 级大模型自动降档）
            int contextSize = calculateOptimalContextSize(memoryInfo.totalMemoryMB, memoryInfo.availableMemoryMB, gpuMemoryMB > 0, modelSizeMB);

            // 检查 GPU 是否支持（使用 gpuMemoryMB > 0 判断，而不是 getGPULayers()）
            boolean hasGpuSupport = gpuMemoryMB > 0 || LlamaHelper.getGPULayers() > 0;
            int gpuLayers = resourceConfig.getOptimalGpuLayers(
                    hasGpuSupport, gpuMemoryMB, maxMemAllocSizeMB, contextSize, modelSizeMB, modelFile.getName());

            // 根据系统剩余可用内存动态调整 GPU 层数，防止内存不足导致卡顿或 OOM
            long availMemMB = memoryInfo.availableMemoryMB;
            if (gpuLayers > 0 && availMemMB > 0) {
                int originalGpuLayers = gpuLayers;
                if (availMemMB < 800) {
                    // 可用内存极低：大幅降低 GPU 层数，优先保证系统稳定
                    gpuLayers = Math.min(gpuLayers, 10);
                    AILogger.w(TAG, "Low available memory (" + availMemMB + "MB), reducing GPU layers to " + gpuLayers);
                } else if (availMemMB < 1500) {
                    // 可用内存较低：限制到 15 层
                    gpuLayers = Math.min(gpuLayers, 15);
                    AILogger.w(TAG, "Moderate-low available memory (" + availMemMB + "MB), reducing GPU layers to " + gpuLayers);
                } else if (availMemMB < 2500) {
                    // 可用内存一般：限制到 20 层
                    gpuLayers = Math.min(gpuLayers, 20);
                    AILogger.i(TAG, "Moderate available memory (" + availMemMB + "MB), limiting GPU layers to " + gpuLayers);
                }
                if (gpuLayers != originalGpuLayers) {
                    AILogger.i(TAG, "GPU layers adjusted: " + originalGpuLayers + " -> " + gpuLayers + " (available memory: " + availMemMB + "MB)");
                }
            }

            int threadCount = resourceConfig.getOptimalThreadCount();
            int batchSize = resourceConfig.getOptimalBatchSize(LlamaHelper.getBatchSize());
            int memoryPoolSize = resourceConfig.getOptimalMemoryPoolSize(LlamaHelper.getMemoryPoolSize());

            AILogger.i(TAG, "Initializing model with optimized parameters: " +
                    "gpuLayers=" + gpuLayers +
                    ", contextSize=" + contextSize +
                    ", threadCount=" + threadCount +
                    ", batchSize=" + batchSize +
                    ", memoryPoolSize=" + memoryPoolSize + "MB");

            if (gpuLayers > 0) {
                updateServiceStage(AIServiceState.ServiceStage.GPU_INITIALIZATION, "初始化GPU加速...", 70);
            } else {
                updateServiceStage(AIServiceState.ServiceStage.MODEL_LOADING, "初始化模型...", 60);
            }

            AILogger.i(TAG, "[PERF] Starting model initialization (native)...");
            long modelLoadStart = System.currentTimeMillis();

            // 设置 GPU 层数（必须在 initModel 之前）
            // 用户手动 GPU 层数优先：若设置了 gpu_layers_manual（状态页/性能面板写入），
            // 覆盖自动计算值；未设置（或恢复自动=删除该 key）才用自动计算。
            // 用独立 key 避免与 ModelStateCache 的 gpu_layers（自动保存）混淆。
            int effectiveGpuLayers = gpuLayers;
            try {
                android.content.SharedPreferences modelPrefs = context.getSharedPreferences(
                        "model_state_cache", android.content.Context.MODE_PRIVATE);
                if (modelPrefs.contains("gpu_layers_manual")) {
                    int manual = modelPrefs.getInt("gpu_layers_manual", -1);
                    if (manual >= 0 && manual <= 36) {
                        effectiveGpuLayers = manual;
                        AILogger.i(TAG, "User manual GPU layers override: auto=" + gpuLayers + " -> manual=" + manual);
                    }
                }
            } catch (Exception e) {
                AILogger.w(TAG, "Read manual GPU layers failed: " + e.getMessage());
            }
            gpuLayers = effectiveGpuLayers;

            LlamaHelper.setGPULayers(gpuLayers);
            AILogger.i(TAG, "Set GPU layers to " + gpuLayers + " before initModel");

            // 设置内存池大小（KV cache 内存预算，native 创建 context 时按预算钳制 n_ctx）
            // 之前只算不打日志，从未真正应用
            LlamaHelper.setMemoryPoolSize(memoryPoolSize);
            AILogger.i(TAG, "Set memory pool size to " + memoryPoolSize + "MB (KV cache budget)");

            // KV cache 类型：默认 F16（最稳）。
            // Q8_0 量化（setKvCacheType(0)）在部分设备解码时触发 SIGABRT（Vulkan kernel assert），
            // 暂不自动启用；setKvCacheType API 保留供未来按设备白名单手动开启。
            int kvCacheType = 1; // F16
            LlamaHelper.setKvCacheType(kvCacheType);

            int result = LlamaHelper.initModel(
                    modelFile.getAbsolutePath(),
                    contextSize,
                    threadCount
            );
            long modelLoadTime = System.currentTimeMillis() - modelLoadStart;
            AILogger.i(TAG, "[PERF] Native model initialization time: " + modelLoadTime + "ms ("
                    + String.format("%.2f", modelLoadTime / 1000.0) + "s)");

            // 记录模型加载时间
            coldStartOptimizer.recordModelLoad(modelFile.getAbsolutePath(), modelLoadTime, result == 0);

            if (result != 0 && LlamaHelper.getGPULayers() > 0) {
                AILogger.w(TAG, "GPU initialization failed (code: " + result + "), trying CPU-only mode...");
                updateServiceStage(AIServiceState.ServiceStage.CPU_FALLBACK, "GPU初始化失败，切换到CPU模式...", 75);
                
                // 使用 ResourceConfig 计算安全的 CPU 模式参数
                int cpuThreadCount = resourceConfig.getOptimalThreadCount();
                int cpuBatchSize = resourceConfig.getOptimalBatchSize(64);
                int cpuContextSize = resourceConfig.getOptimalContextSize(contextSize, cpuBatchSize);
                
                LlamaHelper.setGPULayers(0);
                LlamaHelper.setThreadCount(cpuThreadCount);
                LlamaHelper.setBatchSize(cpuBatchSize);
                AILogger.i(TAG, "Switching to CPU mode: threads=" + cpuThreadCount + ", batchSize=" + cpuBatchSize);
                result = LlamaHelper.initModel(
                        modelFile.getAbsolutePath(),
                        cpuContextSize,
                        cpuThreadCount
                );
                if (result == 0) {
                    AILogger.i(TAG, "Successfully initialized in CPU-only mode after GPU failure");
                } else {
                    AILogger.e(TAG, "CPU fallback also failed with code: " + result);
                }
            }

            if (result == 0) {
                updateServiceStage(AIServiceState.ServiceStage.INITIALIZED, "AI服务已就绪", 90);
                isInitialized = true;
                currentModelName = modelName;
                saveModelName(modelName);
                if (crashHandler != null) {
                    crashHandler.startMonitoring();
                }

                // 模型加载成功后，重新验证 GPU 状态
                int finalGpuLayers = LlamaHelper.getGPULayers();
                boolean gpuWorking = finalGpuLayers > 0 && LlamaHelper.isGPUWorking();
                AILogger.i(TAG, "Post-init GPU status - layers: " + finalGpuLayers + ", working: " + gpuWorking);
                // 如果 native 层 GPU 状态与预期不符，同步更新
                if (finalGpuLayers > 0 && !gpuWorking) {
                    AILogger.w(TAG, "GPU layers configured but not working, checking OpenCL...");
                    if (!LlamaHelper.isOpenCLLoaded()) {
                        AILogger.w(TAG, "OpenCL not loaded after model init, attempting reload...");
                        preloadOpenClIfNeeded();
                    }
                }

                UnifiedContextManager.getInstance().setModelContextReady(true);

                // 多模态：vision 模型自动加载 mmproj 投影文件（下载预设时已自动下载到同目录）
                autoLoadMultimodalIfAvailable(modelFile);

                long totalLoadTimeMs = System.currentTimeMillis() - totalStartTime;
                AILogger.i(TAG, "AI service initialized successfully with model: " + modelName);
                AILogger.i(TAG, "[PERF] ========== MODEL LOADING COMPLETE ==========");
                AILogger.i(TAG, "[PERF] Total load time: " + totalLoadTimeMs + "ms (" 
                        + String.format("%.2f", totalLoadTimeMs / 1000.0) + "s)");
                AILogger.i(TAG, "[PERF] GPU layers: " + LlamaHelper.getGPULayers());
                AILogger.i(TAG, "[PERF] Context size: " + contextSize);
                
                AILogger.i(TAG, "Creating default chat context...");
                updateServiceStage(AIServiceState.ServiceStage.CHAT_CONTEXT_CREATING, "创建对话上下文...", 95);
                boolean ctxCreated = initChatContext(buildDefaultChatSystemPrompt(), "", "");
                if (ctxCreated) {
                    AILogger.i(TAG, "Default chat context created successfully");
                    UnifiedContextManager.getInstance().setChatContextReady(true);
                } else {
                    AILogger.w(TAG, "Failed to create default chat context, but model is ready");
                }

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
     * 使用分块加载加载模型（优化大模型加载）
     */
    public void loadModelWithChunking(String modelPath, int contextSize, int nThreads, ModelChunkLoader.ChunkLoadCallback callback) {
        if (modelChunkLoader == null) {
            modelChunkLoader = new ModelChunkLoader();
        }

        AILogger.i(TAG, "Starting chunked model load: " + modelPath);
        isLoading = true;
        notifyStatusChange();

        modelChunkLoader.loadModel(modelPath, contextSize, nThreads, new ModelChunkLoader.ChunkLoadCallback() {
            @Override
            public void onProgress(int chunkIndex, int totalChunks, int progressPercent) {
                // 映射到服务状态进度 (40-80%)
                int serviceProgress = 40 + (progressPercent * 40 / 100);
                updateServiceStage(AIServiceState.ServiceStage.MODEL_LOADING,
                    "加载模型块 " + (chunkIndex + 1) + "/" + totalChunks + "...", serviceProgress);
                if (callback != null) {
                    callback.onProgress(chunkIndex, totalChunks, progressPercent);
                }
            }

            @Override
            public void onChunkLoaded(int chunkIndex, int totalChunks, long bytesLoaded) {
                AILogger.i(TAG, "Chunk " + (chunkIndex + 1) + "/" + totalChunks + " loaded, bytes: " + bytesLoaded);
                if (callback != null) {
                    callback.onChunkLoaded(chunkIndex, totalChunks, bytesLoaded);
                }
            }

            @Override
            public void onComplete(boolean success, String error) {
                isLoading = false;
                notifyStatusChange();
                if (callback != null) {
                    callback.onComplete(success, error);
                }
            }
        });
    }

    /**
     * 启动模型预加载服务
     */
    public void startModelPreload(String modelPath, String modelName) {
        AILogger.i(TAG, "Starting model preload service for: " + modelName);
        Intent intent = new Intent(context, ModelPreloadService.class);
        intent.setAction("PRELOAD_MODEL");
        intent.putExtra("model_path", modelPath);
        intent.putExtra("model_name", modelName);
        context.startService(intent);
    }

    /**
     * 取消模型加载
     */
    public void cancelModelLoading() {
        if (modelChunkLoader != null && modelChunkLoader.isLoading()) {
            AILogger.i(TAG, "Cancelling model loading");
            modelChunkLoader.cancel();
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
                // 组4.4：推理期间持有 WakeLock
                if (inferenceWakeLock != null && !inferenceWakeLock.isHeld()) {
                    try { inferenceWakeLock.acquire(600000); } catch (Exception e) { AILogger.w(TAG, "WakeLock acquire failed: " + e.getMessage()); }
                }

                int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                final String[] result = {null};
                final Exception[] error = {null};

                // 构建消息列表，由 native 层 llama_chat_apply_template 自动适配模型格式
                List<PromptBuilder.Message> messages = buildMessagesForModel(prompt, history, null);

                // nativeGenerateStream 是同步阻塞调用，回调在当前线程同步触发
                // 不需要 wait/notify 机制，直接在回调里 post 结果到主线程
                LlamaHelper.generateStream(messages, adjustedMaxTokens, 0.7f, 0.9f, 40, false, new LlamaHelper.TokenCallback() {
                    private StringBuilder fullResponse = new StringBuilder();

                    @Override
                    public void onToken(String token) {
                        fullResponse.append(token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        result[0] = fullText != null ? fullText : fullResponse.toString();
                        mainHandler.post(() -> {
                            callback.onSuccess(result[0]);
                            saveChatMessage(0, "user", prompt, false);
                            saveChatMessage(0, "assistant", result[0], false);
                        });
                    }

                    @Override
                    public void onError(String msg) {
                        error[0] = new Exception(msg);
                        AILogger.e(TAG, "Chat send error: " + msg);
                        mainHandler.post(() -> callback.onError(error[0]));
                    }
                });

                // 如果回调都没触发（native 异常退出），给出超时提示
                if (result[0] == null && error[0] == null) {
                    mainHandler.post(() -> callback.onError(new Exception("生成无响应，可能是模型问题")));
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Error generating text: " + e.getMessage(), e);
                crashHandler.recordCrashInfo("GENERATE_ERROR", "生成文本时出错", e);
                mainHandler.post(() -> callback.onError(e));
            } catch (Throwable t) {
                AILogger.e(TAG, "Throwable caught in generate: possible native crash", t);
                crashHandler.recordCrashInfo("NATIVE_CRASH", "生成时发生Native崩溃", t);
                mainHandler.post(() -> callback.onError(new Exception("生成失败，可能是内存或模型问题")));
            } finally {
                // 组1.3：非流式推理结束也清 KV，防止跨轮累积
                try {
                    if (LlamaHelper.isChatContextActive()) {
                        LlamaHelper.chatDestroy();
                        AILogger.i(TAG, "KV cache cleared after generate (per-turn cleanup)");
                    }
                } catch (Throwable t) {
                    AILogger.w(TAG, "Post-inference KV cleanup error: " + t.getMessage());
                }
                // 组4.4：推理结束释放 WakeLock
                if (inferenceWakeLock != null && inferenceWakeLock.isHeld()) {
                    try { inferenceWakeLock.release(); } catch (Throwable t) { AILogger.w(TAG, "WakeLock release failed: " + t.getMessage()); }
                }
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
                // 组4.4：推理期间持有 WakeLock，防止灭屏/切后台 CPU 挂起导致 decode 停顿
                if (inferenceWakeLock != null && !inferenceWakeLock.isHeld()) {
                    try { inferenceWakeLock.acquire(600000); } catch (Exception e) { AILogger.w(TAG, "WakeLock acquire failed: " + e.getMessage()); }
                }

                int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                AILogger.i(TAG, "调整生成 token 数: 原始 " + maxTokens + ", 调整后 " + adjustedMaxTokens);
                sendLogBroadcast("INFO", "[AIService] 使用聊天上下文进行流式生成");
                
                final long startTime = System.currentTimeMillis();
                final long timeoutMs = 600000;
                final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
                
                ScheduledFuture<?> watchdogFuture = watchdogScheduler.schedule(() -> {
                    if (!completed.get()) {
                        AILogger.w(TAG, "Generate stream timeout after " + timeoutMs + "ms, stopping...");
                        LlamaHelper.stopGeneration();
                        mainHandler.post(() -> callback.onError(new Exception("生成超时，请重试")));
                        if (taskFuture[0] != null && !taskFuture[0].isDone()) {
                            taskFuture[0].cancel(true);
                        }
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);

                // 统一使用消息列表路径，由 native 层 llama_chat_apply_template 自动适配模型格式
                // 避免字符串路径不应用聊天模板导致非 ChatML 模型格式不匹配
                List<PromptBuilder.Message> messages;
                if (promptRequest != null) {
                    messages = promptRequest.buildMessages();
                    AILogger.i(TAG, "使用 promptRequest.buildMessages() 构建 " + messages.size() + " 条消息");
                } else if (history != null && !history.isEmpty()) {
                    messages = new ArrayList<>(history);
                    messages.add(new PromptBuilder.Message("user", prompt));
                    AILogger.i(TAG, "使用 history+user 构建 " + messages.size() + " 条消息");
                } else {
                    // 无 history 且无 promptRequest：构建 system + user 消息
                    messages = new ArrayList<>();
                    messages.add(new PromptBuilder.Message("system", "你是一个乐于助人的AI助手。请用中文回答用户的问题。"));
                    messages.add(new PromptBuilder.Message("user", prompt));
                    AILogger.i(TAG, "使用 system+user 构建 " + messages.size() + " 条消息");
                }
                if (messages.isEmpty()) {
                    completed.set(true);
                    watchdogFuture.cancel(false);
                    mainHandler.post(() -> callback.onError(new IllegalArgumentException("消息列表为空")));
                    return;
                }
                LlamaHelper.generateStream(messages, adjustedMaxTokens, 0.7f, 0.9f, 40, false,
                    buildStreamCallback(completed, watchdogFuture, startTime, prompt, callback));
                
                AILogger.i(TAG, "Generation setup completed!");
            } catch (OutOfMemoryError e) {
                AILogger.e(TAG, "OutOfMemoryError in generateStream task: " + e.getMessage(), e);
                sendLogBroadcast("ERROR", "[AIService] 内存溢出: " + e.getMessage());
                try {
                    LlamaHelper.stopGeneration();
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
            } finally {
                // 组1.3 止血补丁：本轮推理结束立刻释放 KV 缓存，不等下一轮才清
                // 防止 KV 缓存跨轮累积导致内存无限增长 → OOM → 崩溃循环
                try {
                    if (LlamaHelper.isChatContextActive()) {
                        LlamaHelper.chatDestroy();
                        AILogger.i(TAG, "KV cache cleared after generateStream (per-turn cleanup)");
                    }
                } catch (Throwable t) {
                    AILogger.w(TAG, "Post-inference KV cleanup error: " + t.getMessage());
                }
                try {
                    UnifiedContextManager.getInstance().resetAll();
                } catch (Throwable t) {
                    AILogger.w(TAG, "Post-inference context reset error: " + t.getMessage());
                }
                // 组4.4：推理结束释放 WakeLock
                if (inferenceWakeLock != null && inferenceWakeLock.isHeld()) {
                    try { inferenceWakeLock.release(); } catch (Throwable t) { AILogger.w(TAG, "WakeLock release failed: " + t.getMessage()); }
                }
                // 推理结束，标记 AI 空闲（供 AICrashHandler 挂起检测判断）
                if (crashHandler != null) {
                    crashHandler.markIdle();
                }
            }
        });
        
        AILogger.i(TAG, "generateStream setup completed, returning...");
    }

    /**
     * 组3.7：纯净推理入口 —— 只做三件事：
     * 1) 存DB + UI气泡
     * 2) 用 Repository.getContextMessages 拼 system + 场景历史 + 本轮
     * 3) LlamaHelper.runInferenceOnce（单次纯净推理，前清KV+后清KV+串行化+信号兜底）
     * 绝不出现 chatDestroy/setPrompt（那些由 C++ runPureInference 内部处理）
     */
    public void sendUserMessagePureInference(String scene, long conversationId, String userText, GenerateStreamCallback callback) {
        updateLastUsedTime();

        if (!isInitialized || !LlamaHelper.isModelInitialized()) {
            mainHandler.post(() -> callback.onError(new IllegalStateException("AI model not initialized")));
            return;
        }

        executorService.submit(() -> {
            try {
                // 1) 存DB
                if (chatRepository != null) {
                    chatRepository.saveMessage(conversationId, "user", userText, false);
                }

                // 2) 拼上下文：system + 场景历史 + 本轮
                List<PromptBuilder.Message> contextMessages = chatRepository.getContextMessages(
                        com.oilquiz.app.ai.ChatScene.normalize(scene), conversationId, 6);
                contextMessages.add(new PromptBuilder.Message("user", userText));

                // 3) 纯净推理
                int safeCtx = LlamaHelper.getSafeContextReference(CHAT_N_CTX);
                int maxTokens = 1024;

                // 使用消息列表调用流式生成（复用现有 generateStream 逻辑，
                // 但组1.3 的 finally 块会保证 KV 在本轮结束后被清理）
                generateStream(userText, contextMessages, maxTokens, callback);

            } catch (Exception e) {
                AILogger.e(TAG, "sendUserMessagePureInference error: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
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

        // 构建消息列表，由 native 层 llama_chat_apply_template 自动适配模型格式
        // 避免 Java 层硬编码 ChatML 格式导致非 ChatML 模型（Llama3/Gemma/Phi-3）格式不匹配
        List<PromptBuilder.Message> messages;
        if (promptRequest != null) {
            messages = promptRequest.buildMessages();
        } else {
            messages = buildMessagesForModel(prompt, PromptBuilder.truncateHistory(history, 8), systemPrompt);
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
                if (messages == null || messages.isEmpty()) {
                    AILogger.e(TAG, "Messages list is null or empty");
                    throw new IllegalArgumentException("Prompt cannot be null or empty");
                }
                // 预留 64 个 token 作为余量
                int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                AILogger.i(TAG, "调整生成 token 数: 原始 " + maxTokens + ", 调整后 " + adjustedMaxTokens);
                
                AILogger.i(TAG, "Calling LlamaHelper.generate with messages (auto chat template)...");
                String result = LlamaHelper.generate(messages, adjustedMaxTokens, 0.7f);
                AILogger.i(TAG, "LlamaHelper.generate completed, result length: " + (result != null ? result.length() : 0));
                
                // 清理模型输出中的乱码/非法字符
                String cleaned = ToolResultInterpreter.cleanModelOutput(result);
                if (cleaned != null) {
                    result = cleaned;
                } else if (result != null) {
                    result = ToolResultInterpreter.sanitize(result);
                    AILogger.w(TAG, "generateAsync: 模型输出检测为乱码，已清理非法字符");
                }
                
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
        }, executorService).whenComplete((result, ex) -> {
            // 无论成功或失败（含提前抛出的初始化异常），结束后都标记 AI 空闲
            if (crashHandler != null) {
                crashHandler.markIdle();
            }
        });
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
            // 组4.4：推理期间持有 WakeLock
            if (inferenceWakeLock != null && !inferenceWakeLock.isHeld()) {
                try { inferenceWakeLock.acquire(600000); } catch (Exception e) { AILogger.w(TAG, "WakeLock acquire failed: " + e.getMessage()); }
            }

            if (prompt == null || prompt.trim().isEmpty()) {
                AILogger.e(TAG, "Prompt is null or empty");
                throw new IllegalArgumentException("Prompt cannot be null or empty");
            }

            int adjustedMaxTokens = Math.max(1, maxTokens - 64);

            // 构建消息列表，由 native 层 llama_chat_apply_template 自动适配模型格式
            List<PromptBuilder.Message> messages = buildMessagesForModel(prompt, history, null);
            AILogger.i(TAG, "Calling LlamaHelper.generate with messages (auto chat template)...");

            String result = LlamaHelper.generate(messages, adjustedMaxTokens, 0.7f);

            // 清理模型输出中的乱码/非法字符
            String cleaned = ToolResultInterpreter.cleanModelOutput(result);
            if (cleaned != null) {
                result = cleaned;
            } else if (result != null) {
                result = ToolResultInterpreter.sanitize(result);
                AILogger.w(TAG, "generateSync: 模型输出检测为乱码，已清理非法字符");
            }

            if (result != null && !result.isEmpty()) {
                saveChatMessage(0, "user", prompt, false);
                saveChatMessage(0, "assistant", result, false);
                return result;
            }

            throw new Exception("生成超时或无响应");
        } catch (OutOfMemoryError e) {
            AILogger.e(TAG, "OutOfMemoryError in generateSync: " + e.getMessage(), e);
            throw new RuntimeException("内存溢出，请尝试减小模型大小或清理内存", e);
        } catch (Exception e) {
            AILogger.e(TAG, "Error generating text: " + e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            // 组1.3：同步推理结束也清 KV
            try {
                if (LlamaHelper.isChatContextActive()) {
                    LlamaHelper.chatDestroy();
                    AILogger.i(TAG, "KV cache cleared after generateSync (per-turn cleanup)");
                }
            } catch (Throwable t) {
                AILogger.w(TAG, "Post-inference KV cleanup error: " + t.getMessage());
            }
            // 组4.4：释放 WakeLock
            if (inferenceWakeLock != null && inferenceWakeLock.isHeld()) {
                try { inferenceWakeLock.release(); } catch (Throwable t) { AILogger.w(TAG, "WakeLock release failed: " + t.getMessage()); }
            }
        }
    }
    
    /**
     * 构建消息列表，由 native 层 llama_chat_apply_template 自动适配模型格式
     * 替代旧的 formatPromptForModel（硬编码 ChatML），支持所有模型架构
     */
    private List<PromptBuilder.Message> buildMessagesForModel(String prompt) {
        return buildMessagesForModel(prompt, new ArrayList<>());
    }

    private List<PromptBuilder.Message> buildMessagesForModel(String prompt, List<PromptBuilder.Message> history) {
        return buildMessagesForModel(prompt, history, null);
    }

    private List<PromptBuilder.Message> buildMessagesForModel(String prompt, List<PromptBuilder.Message> history, String systemPrompt) {
        return buildMessagesForModel(prompt, history, systemPrompt, false);
    }
    
    private List<PromptBuilder.Message> buildMessagesForModel(String prompt, List<PromptBuilder.Message> history, String systemPrompt, boolean forceJavaTruncation) {
        AILogger.i(TAG, "================= 构建消息列表 =================");
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
        
        // 构建消息列表：system + history + user
        // 由 native 层 llama_chat_apply_template 根据模型内置模板自动格式化
        List<PromptBuilder.Message> messages = new ArrayList<>();
        messages.add(new PromptBuilder.Message("system", sys));
        if (truncated != null) {
            messages.addAll(truncated);
        }
        messages.add(new PromptBuilder.Message("user", prompt));
        
        AILogger.i(TAG, "构建消息列表完成，共 " + messages.size() + " 条消息");
        return messages;
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

            // 使用内存管理器检查内存
            ModelMemoryManager memManager = ModelMemoryManager.getInstance(context);
            if (!memManager.isSwitching()) {
                // 检查是否有足够内存
                String modelPath = findModelPath(modelName);
                if (modelPath != null && !memManager.hasEnoughMemoryForModel(modelPath)) {
                    AILogger.w(TAG, "Insufficient memory for model, attempting to free memory");
                    // 尝试释放内存
                    if (LlamaHelper.isModelInitialized()) {
                        releaseNativeResourcesLocked(false);
                    }
                    System.gc();
                }
            }

            // 先卸载旧模型
            if (LlamaHelper.isModelInitialized()) {
                AILogger.i(TAG, "Unloading old model before switching");
                try {
                    LlamaHelper.chatDestroy();
                } catch (Exception e) {
                    AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                }
                LlamaHelper.release();
                // 等待内存释放
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.gc();
            }

            saveModelName(modelName);
            boolean success = loadModelLocked(modelName);

            // 切换成功后保存状态到缓存
            if (success) {
                ModelStateCache.getInstance(context).saveModelState(null);
            }

            return success;
        }
    }

    /**
     * 查找模型文件路径
     */
    private String findModelPath(String modelName) {
        File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
        File modelFile = new File(modelDir, modelName);
        if (modelFile.exists()) {
            return modelFile.getAbsolutePath();
        }
        File rootModelFile = new File(context.getFilesDir(), modelName);
        if (rootModelFile.exists()) {
            return rootModelFile.getAbsolutePath();
        }
        return null;
    }

    /**
     * 当前所选模型的模型文件是否存在。
     * 模型文件被删除/移动后返回 false——UI 应据此提示"模型文件不存在，请重新导入或切换模型"，
     * 避免每次操作都触发初始化失败报错。
     */
    public boolean isCurrentModelFileExists() {
        if (currentModelName == null || currentModelName.isEmpty()) return false;
        return findModelPath(currentModelName) != null;
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

    // ========== 模型热切换 ==========

    /**
     * 热切换回调接口
     */
    public interface HotSwitchCallback {
        void onSwitchStarted(String fromModel, String toModel);
        void onSwitchProgress(int progress, String message);
        void onSwitchCompleted(boolean success, String model);
        void onSwitchFailed(String reason);
    }

    /**
     * 热切换模型 - 快速切换，无需完全重新加载
     * 与 switchModel() 的区别：
     * - switchModel(): 完全释放旧模型 + 重新加载新模型（安全但慢）
     * - hotSwitchModel(): 尝试快速切换，如果模型已缓存则直接切换（快但可能失败）
     *
     * @param targetModelName 目标模型名称
     * @param callback 切换回调
     * @return 是否开始切换
     */
    public boolean hotSwitchModel(String targetModelName, HotSwitchCallback callback) {
        if (targetModelName == null || targetModelName.isEmpty()) {
            if (callback != null) callback.onSwitchFailed("目标模型名称为空");
            return false;
        }

        String fromModel = currentModelName;
        if (targetModelName.equals(fromModel)) {
            if (callback != null) callback.onSwitchCompleted(true, targetModelName);
            return true;
        }

        AILogger.i(TAG, "Hot switching from " + fromModel + " to " + targetModelName);

        executorService.execute(() -> {
            synchronized (modelInitLock) {
                try {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSwitchStarted(fromModel, targetModelName));
                    }

                    // 步骤1: 查找模型文件
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSwitchProgress(10, "查找模型文件..."));
                    }
                    String modelPath = findModelPath(targetModelName);
                    if (modelPath == null) {
                        AILogger.e(TAG, "Model file not found: " + targetModelName);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onSwitchFailed("模型文件不存在: " + targetModelName));
                        }
                        return;
                    }

                    // 步骤2: 检查内存
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSwitchProgress(20, "检查内存..."));
                    }
                    ModelMemoryManager memManager = ModelMemoryManager.getInstance(context);
                    File modelFile = new File(modelPath);
                    long modelSizeMB = modelFile.length() / (1024 * 1024);
                    if (!memManager.hasEnoughMemory(modelSizeMB)) {
                        AILogger.w(TAG, "Insufficient memory for hot switch, falling back to full switch");
                        if (callback != null) {
                            mainHandler.post(() -> callback.onSwitchProgress(30, "内存不足，使用标准切换..."));
                        }
                        // 回退到标准切换
                        boolean success = doSwitchModelInternal(targetModelName, callback);
                        return;
                    }

                    // 步骤3: 释放当前模型（如果需要）
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSwitchProgress(40, "释放当前模型..."));
                    }
                    if (LlamaHelper.isModelInitialized()) {
                        try {
                            LlamaHelper.chatDestroy();
                        } catch (Exception e) {
                            AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                        }
                        LlamaHelper.release();
                        // 等待内存释放
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    // 步骤4: 加载新模型
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSwitchProgress(60, "加载新模型..."));
                    }
                    boolean success = loadModelLocked(targetModelName);

                    if (success) {
                        if (callback != null) {
                            mainHandler.post(() -> callback.onSwitchProgress(100, "切换完成"));
                            mainHandler.post(() -> callback.onSwitchCompleted(true, targetModelName));
                        }
                        AILogger.i(TAG, "Hot switch completed successfully");
                    } else {
                        // 切换失败，尝试回滚
                        AILogger.e(TAG, "Hot switch failed, attempting rollback to: " + fromModel);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onSwitchProgress(80, "切换失败，回滚中..."));
                        }
                        if (fromModel != null) {
                            rollbackToModel(fromModel);
                        }
                        if (callback != null) {
                            mainHandler.post(() -> callback.onSwitchFailed("模型加载失败，已回滚"));
                        }
                    }
                } catch (Exception e) {
                    AILogger.e(TAG, "Hot switch exception: " + e.getMessage(), e);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSwitchFailed("切换异常: " + e.getMessage()));
                    }
                }
            }
        });

        return true;
    }

    /**
     * 标准切换（内部方法，带完整状态同步）
     */
    private boolean doSwitchModelInternal(String modelName, HotSwitchCallback callback) {
        try {
            boolean success = switchModel(modelName);
            if (success) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onSwitchCompleted(true, modelName));
                }
            } else {
                if (callback != null) {
                    mainHandler.post(() -> callback.onSwitchFailed("标准切换失败"));
                }
            }
            return success;
        } catch (Exception e) {
            AILogger.e(TAG, "Standard switch failed: " + e.getMessage(), e);
            if (callback != null) {
                mainHandler.post(() -> callback.onSwitchFailed("标准切换异常: " + e.getMessage()));
            }
            return false;
        }
    }

    /**
     * 回滚到指定模型
     */
    private void rollbackToModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            AILogger.w(TAG, "No model to rollback to");
            return;
        }

        AILogger.i(TAG, "Rolling back to model: " + modelId);
        try {
            boolean success = loadModelLocked(modelId);
            if (success) {
                AILogger.i(TAG, "Rollback successful");
            } else {
                AILogger.e(TAG, "Rollback failed");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Rollback exception: " + e.getMessage(), e);
        }
    }

    // ========== 模型预加载 ==========

    /**
     * 预加载模型到内存
     * 实际加载模型文件，为后续快速切换做准备
     *
     * @param modelName 模型名称
     * @param callback 预加载回调
     * @return 是否开始预加载
     */
    public boolean preloadModel(String modelName, PreloadCallback callback) {
        if (modelName == null || modelName.isEmpty()) {
            if (callback != null) callback.onPreloadFailed("模型名称为空");
            return false;
        }

        if (modelName.equals(currentModelName) && LlamaHelper.isModelInitialized()) {
            AILogger.i(TAG, "Model already loaded and active: " + modelName);
            if (callback != null) callback.onPreloadCompleted(modelName);
            return true;
        }

        AILogger.i(TAG, "Preloading model: " + modelName);

        executorService.execute(() -> {
            synchronized (modelInitLock) {
                try {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onPreloadProgress(10, "准备预加载..."));
                    }

                    // 查找模型文件
                    String modelPath = findModelPath(modelName);
                    if (modelPath == null) {
                        AILogger.e(TAG, "Model file not found for preloading: " + modelName);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onPreloadFailed("模型文件不存在: " + modelName));
                        }
                        return;
                    }

                    // 检查内存
                    if (callback != null) {
                        mainHandler.post(() -> callback.onPreloadProgress(20, "检查内存..."));
                    }
                    ModelMemoryManager memManager = ModelMemoryManager.getInstance(context);
                    File modelFile = new File(modelPath);
                    long modelSizeMB = modelFile.length() / (1024 * 1024);
                    if (!memManager.hasEnoughMemory(modelSizeMB)) {
                        AILogger.w(TAG, "Not enough memory for preloading: " + modelSizeMB + "MB");
                        if (callback != null) {
                            mainHandler.post(() -> callback.onPreloadFailed("内存不足，无法预加载: " + modelSizeMB + "MB"));
                        }
                        return;
                    }

                    // 释放当前模型（如果需要）
                    if (callback != null) {
                        mainHandler.post(() -> callback.onPreloadProgress(40, "释放当前模型..."));
                    }
                    if (LlamaHelper.isModelInitialized()) {
                        try {
                            LlamaHelper.chatDestroy();
                        } catch (Exception e) {
                            AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                        }
                        LlamaHelper.release();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    // 加载模型
                    if (callback != null) {
                        mainHandler.post(() -> callback.onPreloadProgress(60, "加载模型到内存..."));
                    }
                    boolean success = loadModelLocked(modelName);

                    if (success) {
                        AILogger.i(TAG, "Model preloaded successfully: " + modelName);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onPreloadProgress(100, "预加载完成"));
                            mainHandler.post(() -> callback.onPreloadCompleted(modelName));
                        }
                    } else {
                        AILogger.e(TAG, "Failed to preload model: " + modelName);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onPreloadFailed("模型加载失败"));
                        }
                    }
                } catch (Exception e) {
                    AILogger.e(TAG, "Preload exception: " + e.getMessage(), e);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onPreloadFailed("预加载异常: " + e.getMessage()));
                    }
                }
            }
        });

        return true;
    }

    /**
     * 预加载回调接口
     */
    public interface PreloadCallback {
        void onPreloadProgress(int progress, String message);
        void onPreloadCompleted(String modelName);
        void onPreloadFailed(String reason);
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
                    // 同步 native 层的 OpenCL 状态
                    LlamaHelper.setOpenCLLoaded(true);
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
     * 根据当前优化模式应用设置
     * 只设置线程数、批处理大小、内存池大小
     * GPU 层�数由 loadModelLocked() 根据模型信息计算
     */
    private void applyOptimizations() {
        try {
            OptimizationMode mode = optimizationMode;

            // 使用 ResourceConfig 计算安全的参数
            ResourceConfig resourceConfig = new ResourceConfig(context);

            // 只计算线程数、批处理大小、内存池大小（不计算 GPU 层数）
            int threadCount = resourceConfig.getOptimalThreadCount();
            int batchSize = resourceConfig.getOptimalBatchSize(mode.batchSize);
            int memoryPoolSize = resourceConfig.getOptimalMemoryPoolSize(mode.memoryPoolMB);

            // 应用计算后的参数（只设置线程数、批处理大小、内存池大小）
            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(batchSize);
            LlamaHelper.setMemoryPoolSize(memoryPoolSize);
            // GPU 层数由 loadModelLocked() 根据模型信息计算，这里不设置

            AILogger.i(TAG, "=== Optimizations Applied: " + mode.displayName + " ===");
            AILogger.i(TAG, "Thread Count: " + threadCount);
            AILogger.i(TAG, "Batch Size: " + batchSize);
            AILogger.i(TAG, "Memory Pool: " + memoryPoolSize + "MB");
            AILogger.i(TAG, "GPU Layers: (deferred to loadModelLocked)");
        } catch (Exception e) {
            AILogger.e(TAG, "Error applying settings: " + e.getMessage(), e);
            applyFallbackDefaults();
        }
    }

    /**
     * 回退默认设置（异常情况下使用）
     */
    private void applyFallbackDefaults() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int threadCount = Math.min(cpuCores, 4);
        int batchSize = 64;
        int memoryPoolSize = 256;
        int gpuLayers = 0;

        LlamaHelper.setThreadCount(threadCount);
        LlamaHelper.setBatchSize(batchSize);
        LlamaHelper.setMemoryPoolSize(memoryPoolSize);
        LlamaHelper.setGPULayers(gpuLayers);

        AILogger.i(TAG, "=== Fallback Defaults Applied ===");
        AILogger.i(TAG, "Thread Count: " + threadCount + ", Batch: " + batchSize + ", Pool: " + memoryPoolSize + "MB");
    }
    
    /**
     * 根据当前优化模式返回 Context Size
     */
    private int calculateOptimalContextSize(long totalMemoryMB, long availableMemoryMB, boolean hasGpuSupport, long modelSizeMB) {
        // 使用 ResourceConfig 计算安全的上下文大小
        ResourceConfig resourceConfig = new ResourceConfig(context);
        int contextSize = resourceConfig.getOptimalContextSize(optimizationMode.contextSize, 128);

        // 模型尺寸感知：大模型权重占用大量内存，降低上下文上限避免 KV 内存高压卡顿
        // 8B Q4_K_M≈4700MB + KV(8192)≈1.2GB → 常驻 6GB+，12GB 设备可用内存波动时明显卡顿
        if (modelSizeMB >= 7000) {
            contextSize = Math.min(contextSize, 4096);
            AILogger.i(TAG, "Very large model (" + modelSizeMB + "MB): context capped to " + contextSize);
        } else if (modelSizeMB >= 4500) {
            contextSize = Math.min(contextSize, 6144);
            AILogger.i(TAG, "Large model (" + modelSizeMB + "MB): context capped to " + contextSize);
        } else if (modelSizeMB >= 3000) {
            contextSize = Math.min(contextSize, 8192);
            AILogger.i(TAG, "Mid-large model (" + modelSizeMB + "MB): context capped to " + contextSize);
        }

        AILogger.i(TAG, "calculateOptimalContextSize: mode=" + optimizationMode.displayName 
                + ", totalMem=" + totalMemoryMB + "MB, availableMem=" + availableMemoryMB + "MB"
                + ", modelSize=" + modelSizeMB + "MB, contextSize=" + contextSize);
        
        return contextSize;
    }
    
    /**
     * 计算最优 GPU layers
     */
    private int calculateOptimalGpuLayers(boolean hasGpuSupport, MemoryUsageInfo memoryInfo, 
            GpuInfo gpuInfo, GpuCapabilityDetector gpuDetector) {
        if (!hasGpuSupport) {
            AILogger.i(TAG, "No GPU support detected, using CPU only (gpuLayers=0)");
            return 0;
        }
        
        // 使用 ResourceConfig 计算安全的 GPU 层数
        ResourceConfig resourceConfig = new ResourceConfig(context);
        
        long gpuMemoryMB = 0;
        if (gpuInfo != null && gpuInfo.totalMemoryMB > 0) {
            gpuMemoryMB = gpuInfo.totalMemoryMB;
        } else {
            gpuMemoryMB = gpuDetector.getAvailableGpuMemoryMB();
        }
        
        int gpuLayers = resourceConfig.getOptimalGpuLayers(true, gpuMemoryMB);
        
        AILogger.i(TAG, "calculateOptimalGpuLayers: gpuMem=" + gpuMemoryMB + "MB, gpuLayers=" + gpuLayers);
        
        return gpuLayers;
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
        
        // 使用 ResourceConfig 计算安全参数
        ResourceConfig resourceConfig = new ResourceConfig(context);
        int threadCount = resourceConfig.getOptimalThreadCount();
        int memoryPoolSize = resourceConfig.getOptimalMemoryPoolSize(2048);
        int batchSize = resourceConfig.getOptimalBatchSize(256);
        int gpuLayers = resourceConfig.getOptimalGpuLayers(true, 0);
        
        LlamaHelper.setMemoryPoolSize(memoryPoolSize);
        LlamaHelper.setThreadCount(threadCount);
        LlamaHelper.setBatchSize(batchSize);
        LlamaHelper.setGPULayers(gpuLayers);
        
        AILogger.i(TAG, "Xiaomi 14 optimizations applied: GPU layers=" + gpuLayers + 
                ", MemPool=" + memoryPoolSize + "MB, Batch=" + batchSize + ", Threads=" + threadCount);
    }
    
    /**
     * Adreno GPU优化
     * 使用 ResourceConfig 确保参数在安全范围内
     */
    private void applyAdrenoOptimizations(String series, long totalDeviceMem) {
        AILogger.i(TAG, "Applying Adreno optimizations for series: " + series);
        
        ResourceConfig resourceConfig = new ResourceConfig(context);
        int threadCount = resourceConfig.getOptimalThreadCount();
        int gpuLayers = resourceConfig.getOptimalGpuLayers(true, 0);
        
        switch (series) {
            case "A7XX":
                // Adreno 740/750 - 高端GPU
                LlamaHelper.setMemoryPoolSize(resourceConfig.getOptimalMemoryPoolSize(2048));
                LlamaHelper.setThreadCount(threadCount);
                LlamaHelper.setBatchSize(resourceConfig.getOptimalBatchSize(256));
                LlamaHelper.setGPULayers(gpuLayers);
                AILogger.i(TAG, "A7XX optimizations applied: GPU layers=" + gpuLayers + 
                        ", MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=256, Threads=" + threadCount);
                break;
                
            case "A6XX":
                // Adreno 650 - 中端GPU
                LlamaHelper.setMemoryPoolSize(resourceConfig.getOptimalMemoryPoolSize(1024));
                LlamaHelper.setThreadCount(threadCount);
                LlamaHelper.setBatchSize(resourceConfig.getOptimalBatchSize(128));
                LlamaHelper.setGPULayers(gpuLayers);
                AILogger.i(TAG, "A6XX optimizations applied: GPU layers=" + gpuLayers + 
                        ", MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=128, Threads=" + threadCount);
                break;
                
            case "A5XX":
                // Adreno 5xx - 低端GPU
                LlamaHelper.setMemoryPoolSize(resourceConfig.getOptimalMemoryPoolSize(512));
                LlamaHelper.setThreadCount(resourceConfig.getOptimalThreadCount());
                LlamaHelper.setBatchSize(resourceConfig.getOptimalBatchSize(64));
                LlamaHelper.setGPULayers(resourceConfig.getOptimalGpuLayers(true, 0));
                AILogger.i(TAG, "A5XX optimizations applied: GPU layers=" + LlamaHelper.getGPULayers() + 
                        ", MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=64, Threads=" + LlamaHelper.getThreadCount());
                break;
                
            default:
                // 通用优化
                LlamaHelper.setMemoryPoolSize(resourceConfig.getOptimalMemoryPoolSize(256));
                LlamaHelper.setThreadCount(threadCount);
                LlamaHelper.setBatchSize(resourceConfig.getOptimalBatchSize(64));
                LlamaHelper.setGPULayers(gpuLayers);
                AILogger.i(TAG, "Generic Adreno optimizations applied: GPU layers=" + gpuLayers + 
                        ", MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=64, Threads=" + threadCount);
                break;
        }
    }
    
    /**
     * 基于设备能力的自动调优
     * 使用 ResourceConfig 确保参数在安全范围内
     */
    private void autoTuneBasedOnCapabilities() {
        AILogger.i(TAG, "Auto-tuning based on device capabilities");
        
        try {
            ResourceConfig resourceConfig = new ResourceConfig(context);
            ResourceConfig.OptimalConfig optimalConfig = resourceConfig.calculateOptimalConfig(false, 0, 0, 4096);
            
            LlamaHelper.setMemoryPoolSize(optimalConfig.memoryPoolMB);
            LlamaHelper.setThreadCount(optimalConfig.threadCount);
            LlamaHelper.setBatchSize(optimalConfig.batchSize);
            LlamaHelper.setGPULayers(0); // CPU 模式
            
            AILogger.i(TAG, "Auto-tuned parameters - Threads: " + optimalConfig.threadCount + 
                    ", Batch: " + optimalConfig.batchSize + 
                    ", MemPool: " + optimalConfig.memoryPoolMB + "MB");
            
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to auto-tune, using safe defaults: " + e.getMessage());
            // 使用安全的默认值
            ResourceConfig resourceConfig = new ResourceConfig(context);
            int threadCount = resourceConfig.getOptimalThreadCount();
            
            LlamaHelper.setMemoryPoolSize(256);
            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(64);
            LlamaHelper.setGPULayers(0);
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

    // ========== 冷启动优化 ==========

    /**
     * 获取冷启动优化器
     */
    public ModelColdStartOptimizer getColdStartOptimizer() {
        return coldStartOptimizer;
    }

    /**
     * 预加载模型文件到系统缓存
     * 这不会加载模型到内存，只是让系统缓存文件内容，加速后续加载
     *
     * @param modelPath 模型路径
     * @param callback 预加载回调
     */
    public void preloadModelFile(String modelPath, ModelColdStartOptimizer.PreloadCallback callback) {
        if (coldStartOptimizer != null) {
            coldStartOptimizer.preloadModelFile(modelPath, callback);
        }
    }

    /**
     * 预热模型加载
     * 使用小上下文预加载模型，然后释放，让文件在系统缓存中
     *
     * @param modelPath 模型路径
     * @param callback 预热回调
     */
    public void warmUpModel(String modelPath, ModelColdStartOptimizer.WarmUpCallback callback) {
        if (coldStartOptimizer != null) {
            coldStartOptimizer.warmUpModel(modelPath, callback);
        }
    }

    /**
     * 获取模型加载统计信息
     */
    public ModelColdStartOptimizer.LoadStats getModelLoadStats(String modelPath) {
        if (coldStartOptimizer != null) {
            String modelKey = new File(modelPath).getName();
            return coldStartOptimizer.getLoadStats(modelKey);
        }
        return null;
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
     * 处理内存紧张情况 —— ComponentCallbacks2 实现
     * TRIM_MEMORY_RUNNING_LOW：系统内存低，主动释放模型避免 OOM 崩溃
     * TRIM_MEMORY_UI_HIDDEN_UI：UI 不可见时释放非必要资源
     * TRIM_MEMORY_MODERATE：后台进程受限，释放所有可释放资源
     */
    @Override
    public void onTrimMemory(int level) {
        AILogger.i(TAG, "收到内存紧张通知，级别: " + level);

        // 正在推理时不释放（防止推理中断）
        if (activeChatGenerationCount > 0) {
            AILogger.i(TAG, "有活跃推理任务(count=" + activeChatGenerationCount + ")，跳过内存回收");
            return;
        }

        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                // 系统内存低：主动释放模型，让 UI 显示 UNLOADED 状态
                AILogger.w(TAG, "TRIM_MEMORY_RUNNING_LOW: 主动释放模型避免 OOM");
                unloadModelForMemoryPressure("系统内存不足，已自动释放AI模型");
                break;

            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                // UI 不可见：释放聊天上下文（保留模型）
                AILogger.i(TAG, "TRIM_MEMORY_UI_HIDDEN: 释放聊天上下文");
                try {
                    if (LlamaHelper.isChatContextActive()) {
                        LlamaHelper.chatDestroy();
                        AILogger.i(TAG, "聊天上下文已释放");
                    }
                } catch (Throwable t) {
                    AILogger.w(TAG, "释放聊天上下文失败: " + t.getMessage());
                }
                break;

            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // 严重内存压力：释放所有资源
                AILogger.w(TAG, "严重内存压力级别 " + level + ": 释放所有AI资源");
                unloadModelForMemoryPressure("严重内存压力，已释放AI模型");
                break;

            default:
                AILogger.i(TAG, "内存级别 " + level + " 无需特殊处理");
                break;
        }
    }

    /**
     * 因内存压力释放模型 —— 推送到 ViewModel 让 UI 显示 UNLOADED 状态
     */
    private void unloadModelForMemoryPressure(String reason) {
        try {
            // 停掉推理（如果有）
            try {
                LlamaHelper.stopGeneration();
            } catch (Throwable ignored) {}

            // 释放聊天上下文
            try {
                if (LlamaHelper.isChatContextActive()) {
                    LlamaHelper.chatDestroy();
                }
            } catch (Throwable ignored) {}

            // 释放模型
            if (LlamaHelper.isModelInitialized()) {
                LlamaHelper.release();
                AILogger.w(TAG, "模型已释放: " + reason);
            }

            isInitialized = false;
            currentModelName = null;

            // 广播 UNLOADED 状态给所有观察者
            synchronized (statusObservers) {
                for (StatusObserver obs : statusObservers) {
                    try {
                        obs.onStatusChanged(false, null);
                    } catch (Throwable t) {
                        AILogger.w(TAG, "状态通知失败: " + t.getMessage());
                    }
                }
            }

        } catch (Throwable t) {
            AILogger.e(TAG, "unloadModelForMemoryPressure failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        // 不处理配置变更
    }

    @Override
    public void onLowMemory() {
        AILogger.w(TAG, "onLowMemory: 系统内存严重不足！");
        unloadModelForMemoryPressure("系统内存严重不足，已紧急释放AI模型");
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
        // 首值 0 = 使用模型加载时的完整上下文（native chatCreate 对 ctxSize<=0 取模型 n_ctx，
        // 避免此前 {16384,8192,...} 与模型上下文脱节的误导性尝试）；失败再逐级减半降级。
        // 防崩溃加固：低内存设备上 0（=模型完整 n_ctx，如 Qwen3-4B 32768）的 KV 缓存
        // 峰值可达数 GB（实测 16K≈4.8GB），创建阶段即被系统杀进程，fallback 链根本来不及执行。
        // 因此按设备总内存钳制首试值：<6GB 先试 4096，<10GB 先试 8192，高内存才用完整 n_ctx。
        int firstCtx = 0;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMemMB = mi.totalMem / (1024L * 1024L);
                if (totalMemMB > 0 && totalMemMB < 6144) {
                    firstCtx = 4096;
                } else if (totalMemMB < 10240) {
                    firstCtx = 8192;
                }
                AILogger.i(TAG, "Device total RAM=" + totalMemMB + "MB, memory-aware first ctxSize=" + firstCtx);
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "Memory-aware ctxSize detection failed: " + t.getMessage());
        }
        // 按从大到小构造降序候选（去重，且只取 ≤ firstCtx 的值），保证后续降级只减不增
        int[] ctxSizes;
        if (firstCtx <= 0) {
            ctxSizes = new int[]{0, 8192, 4096, 2048, 1024};
        } else {
            java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
            set.add(firstCtx);
            for (int c : new int[]{8192, 4096, 2048, 1024}) {
                if (c < firstCtx) set.add(c);
            }
            ctxSizes = new int[set.size()];
            int idx = 0;
            for (int c : set) ctxSizes[idx++] = c;
        }
        AILogger.i(TAG, "Chat context candidate ctxSizes=" + java.util.Arrays.toString(ctxSizes));

        for (int ctxSize : ctxSizes) {
            AILogger.i(TAG, "Trying to create chat context with ctxSize=" + ctxSize);
            try {
                if (!LlamaHelper.isModelInitialized()) {
                    AILogger.e(TAG, "Model not initialized, skipping ctxSize=" + ctxSize);
                    continue;
                }
                
                long handle = LlamaHelper.chatCreate("", ctxSize, nThreads, globalPrompt, systemPrompt, normalPrompt);
                if (handle != 0) {
                    AILogger.i(TAG, "Successfully created chat context with ctxSize=" + ctxSize);
                    return handle;
                }
                AILogger.w(TAG, "Failed to create context with ctxSize=" + ctxSize + ", trying smaller size");
            } catch (UnsatisfiedLinkError e) {
                AILogger.e(TAG, "Native error creating context with ctxSize=" + ctxSize + ": " + e.getMessage(), e);
            } catch (Exception e) {
                AILogger.w(TAG, "Exception creating context with ctxSize=" + ctxSize + ": " + e.getMessage());
            }
        }
        return 0;
    }

    /**
     * 构建默认对话系统提示词（参照 Agent 的系统信息注入机制）。
     * 包含：角色定义 + 环境上下文（当前日期时间）。
     * 用于所有默认/恢复路径创建的聊天上下文，使本地模型知道自身角色和当前时间，
     * 支持时间相关提问（"今天星期几"等）。位置/天气由 Agent 工具链按需获取，
     * 不在此处同步拉取，避免拖慢上下文创建。
     */
    private String buildDefaultChatSystemPrompt() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("你是答题宝智能助手，一个集成在答题宝App中的AI助手。请用中文简洁、准确地回答用户问题。\n");
            sb.append("【环境上下文】\n");
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA);
            sb.append("当前时间：").append(sdf.format(new java.util.Date()));
            return sb.toString();
        } catch (Throwable t) {
            AILogger.w(TAG, "buildDefaultChatSystemPrompt failed: " + t.getMessage());
            return "你是答题宝智能助手，请用中文回答。";
        }
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
        AILogger.i(TAG, "initChatContext called, isChatActive=" + isChatActive + ", activeGenerationCount=" + activeChatGenerationCount);
        
        synchronized (chatContextLock) {
            if (activeChatGenerationCount > 0) {
                AILogger.w(TAG, "initChatContext: waiting for " + activeChatGenerationCount + " active generations to complete");
                for (int waitAttempt = 0; waitAttempt < 50; waitAttempt++) {
                    if (activeChatGenerationCount <= 0) break;
                    try {
                        chatContextLock.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        AILogger.w(TAG, "initChatContext: interrupted while waiting");
                        return false;
                    }
                }
            }
        }
        
        synchronized (modelInitLock) {
            if (!isInitialized) {
                AILogger.e(TAG, "AIService not initialized, cannot create chat context");
                return false;
            }

            if (!LlamaHelper.isModelInitialized()) {
                AILogger.e(TAG, "Native model not initialized, cannot create chat context");
                return false;
            }

            try {
                if (LlamaHelper.isChatContextActive()) {
                    AILogger.i(TAG, "initChatContext: destroying existing chat context");
                    LlamaHelper.chatDestroy();
                    
                    // 轮询等待 native 清理完成（替代固定 sleep，最多 2 秒）
                    for (int w = 0; w < 20 && !LlamaHelper.isModelInitialized(); w++) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    if (!LlamaHelper.isModelInitialized()) {
                        AILogger.e(TAG, "Model no longer initialized after destroying chat context");
                        return false;
                    }
                }
            } catch (UnsatisfiedLinkError e) {
                AILogger.e(TAG, "Native error destroying chat context: " + e.getMessage(), e);
                return false;
            } catch (Exception e) {
                AILogger.w(TAG, "Error destroying chat context (safe to ignore): " + e.getMessage());
            }

            this.chatSystemPrompt = systemPrompt;
            int nThreads = LlamaHelper.getThreadCount();
            if (nThreads <= 0) nThreads = 4;

            long handle = tryCreateChatContextWithFallback(globalPrompt, systemPrompt, normalPrompt, nThreads);
            if (handle == 0) {
                AILogger.e(TAG, "Failed to create native chat context after all fallbacks");
                UnifiedContextManager.getInstance().setChatContextReady(false);
                isChatActive = false;
                lastChatContextHandle = 0;
                return false;
            }

            lastChatContextHandle = handle;
            isChatActive = true;
            UnifiedContextManager.getInstance().setChatContextReady(true);
            AILogger.i(TAG, "Native chat context created: handle=" + handle + ", " + LlamaHelper.chatGetInfo());
            return true;
        }
    }

    public boolean updateChatPrompts(String globalPrompt, String systemPrompt, String normalPrompt) {
        synchronized (chatContextLock) {
            try {
                // 如果聊天上下文已激活，直接更新提示词（不销毁上下文）
                if (LlamaHelper.isChatContextActive()) {
                    AILogger.i(TAG, "Updating chat prompts without destroying context");
                    return LlamaHelper.chatUpdatePrompts(globalPrompt, systemPrompt, normalPrompt);
                } else {
                    // 如果上下文未激活，创建新上下文
                    AILogger.i(TAG, "Chat context not active, creating new context");
                    return initChatContext(globalPrompt, systemPrompt, normalPrompt);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Error updating chat prompts: " + e.getMessage(), e);
                return false;
            }
        }
    }

    /**
     * 追加系统指令（用于模式切换等）：在现有 system 提示词后追加指令，
     * 不污染对话历史、不触发生成（原实现用 chatSend 会把指令当用户消息进上下文）。
     */
    public boolean appendSystemInstruction(String instruction) {
        if (instruction == null || instruction.isEmpty()) return false;
        synchronized (chatContextLock) {
            try {
                String current = chatSystemPrompt != null ? chatSystemPrompt : "";
                String updated = current.isEmpty() ? instruction : current + "\n\n" + instruction;
                boolean ok = updateChatPrompts(null, updated, null);
                if (ok) {
                    chatSystemPrompt = updated;
                }
                return ok;
            } catch (Exception e) {
                AILogger.e(TAG, "Error appending system instruction: " + e.getMessage(), e);
                return false;
            }
        }
    }

    public void clearChatContext() {
        synchronized (chatContextLock) {
            try {
                LlamaHelper.chatClear();
                UnifiedContextManager.getInstance().resetChatContext();
                AILogger.i(TAG, "Native chat context cleared (token history reset)");
            } catch (Exception e) {
                AILogger.w(TAG, "Error clearing chat context: " + e.getMessage());
            }
        }
    }
    
    private boolean canSendChat() {
        if (isLoading) return false;
        if (!isInitialized) return false;
        if (!LlamaHelper.isModelInitialized()) return false;
        if (!LlamaHelper.isChatContextActive()) return false;
        return true;
    }
    
    private String getChatSendErrorReason() {
        if (isLoading) return "AI service is loading, please wait";
        if (!isInitialized) return "AI service not initialized";
        if (!LlamaHelper.isModelInitialized()) return "AI model is still loading, please wait";
        if (!LlamaHelper.isChatContextActive()) return "Chat context not active";
        return "Unknown error";
    }

    public void chatSend(String message, int maxTokens, boolean enableThinking, LlamaHelper.TokenCallback callback) {
        updateLastUsedTime();

        // 同步检查并创建上下文，避免竞态条件
        synchronized (chatContextLock) {
            if (!canSendChat()) {
                String error = getChatSendErrorReason();
                AILogger.w(TAG, "chatSend rejected: " + error);
                
                // 尝试自动恢复：如果模型已初始化但聊天上下文未激活，尝试创建上下文
                if (isInitialized && LlamaHelper.isModelInitialized() && !LlamaHelper.isChatContextActive()) {
                    AILogger.i(TAG, "Chat context not active, attempting to create...");
                    try {
                        boolean ctxCreated = initChatContext(buildDefaultChatSystemPrompt(), "", "");
                        if (ctxCreated && canSendChat()) {
                            AILogger.i(TAG, "Chat context created successfully, continuing chatSend");
                            // 不递归重试，直接继续执行
                        } else {
                            if (callback != null) callback.onError(error);
                            return;
                        }
                    } catch (Exception e) {
                        AILogger.e(TAG, "Failed to create chat context: " + e.getMessage(), e);
                        if (callback != null) callback.onError("Failed to create chat context: " + e.getMessage());
                        return;
                    }
                } else {
                    if (callback != null) callback.onError(error);
                    return;
                }
            }

            if (crashHandler != null) {
                crashHandler.recordActivity();
            }
            
            activeChatGenerationCount++;
            AILogger.i(TAG, "chatSend started, activeGenerationCount=" + activeChatGenerationCount);
        }

        final long startTime = System.currentTimeMillis();
        final long timeoutMs = 600000;
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.Future<?>[] taskFuture = new java.util.concurrent.Future<?>[1];
        final long expectedContextHandle = lastChatContextHandle;

        final LlamaHelper.TokenCallback wrappedCallback = new LlamaHelper.TokenCallback() {
            @Override
            public void onToken(String token) {
                if (completed.get()) {
                    AILogger.w(TAG, "onToken called after completion, ignoring");
                    return;
                }
                if (callback != null) {
                    try {
                        callback.onToken(token);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Error in onToken callback: " + e.getMessage(), e);
                    }
                }
            }

            @Override
            public void onComplete(String fullText) {
                if (!completed.compareAndSet(false, true)) {
                    AILogger.w(TAG, "onComplete called multiple times, ignoring duplicate call");
                    return;
                }
                if (crashHandler != null) {
                    crashHandler.markIdle();
                }
                synchronized (chatContextLock) {
                    activeChatGenerationCount = Math.max(0, activeChatGenerationCount - 1);
                    chatContextLock.notifyAll();
                    AILogger.i(TAG, "chatSend completed, activeGenerationCount=" + activeChatGenerationCount);
                }
                if (callback != null) {
                    try {
                        callback.onComplete(fullText);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Error in onComplete callback: " + e.getMessage(), e);
                    }
                }
            }

            @Override
            public void onError(String error) {
                if (!completed.compareAndSet(false, true)) {
                    AILogger.w(TAG, "onError called after completion, ignoring duplicate: " + error);
                    return;
                }
                if (crashHandler != null) {
                    crashHandler.markIdle();
                }
                synchronized (chatContextLock) {
                    activeChatGenerationCount = Math.max(0, activeChatGenerationCount - 1);
                    chatContextLock.notifyAll();
                    AILogger.i(TAG, "chatSend error, activeGenerationCount=" + activeChatGenerationCount + ", error=" + error);
                }
                if (callback != null) {
                    try {
                        callback.onError(error);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Error in onError callback: " + e.getMessage(), e);
                    }
                }
            }
        };

        taskFuture[0] = executorService.submit(() -> {
            try {
                if (expectedContextHandle != lastChatContextHandle || !LlamaHelper.isChatContextActive()) {
                    AILogger.w(TAG, "Chat context changed before execution, aborting");
                    wrappedCallback.onError("Chat context changed, please retry");
                    return;
                }
                
                ScheduledFuture<?> watchdogFuture = watchdogScheduler.schedule(() -> {
                    if (!completed.get()) {
                        AILogger.w(TAG, "chatSend timeout after " + timeoutMs + "ms, stopping...");
                        try {
                            LlamaHelper.chatStop();
                        } catch (Exception e) {
                            AILogger.w(TAG, "Error stopping chat: " + e.getMessage());
                        }
                        if (callback != null) {
                            mainHandler.post(() -> wrappedCallback.onError("生成超时，请重试"));
                        }
                        if (taskFuture[0] != null && !taskFuture[0].isDone()) {
                            taskFuture[0].cancel(true);
                        }
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);

                // 获取推理锁，确保不与 generate 并发执行
                if (!LlamaHelper.acquireInferenceWriteLock()) {
                    AILogger.w(TAG, "chatSend: Failed to acquire inference lock");
                    if (callback != null) {
                        mainHandler.post(() -> callback.onError("推理锁获取超时"));
                    }
                    return;
                }
                
                try {
                // 检查上下文空间是否足够
                int promptTokens = LlamaHelper.countTokens(message);
                int remainingTokens = LlamaHelper.getContextRemainingTokens();
                int requiredTokens = promptTokens + maxTokens;

                AILogger.i(TAG, "chatSend context check: promptTokens=" + promptTokens +
                        ", remaining=" + remainingTokens + ", required=" + requiredTokens);

                // 上下文空间不足：不再全清上下文（全清会导致多轮对话"失忆"），
                // native 层 chatSend 内置滑动窗口（shiftContext）会自动裁剪最旧消息保留最近对话。
                // 这里仅降低 maxTokens 作为保护，剩余空间由 native 滑动窗口处理。
                final int effectiveMaxTokens;
                if (remainingTokens < requiredTokens) {
                    AILogger.w(TAG, "chatSend 上下文空间不足（native 滑动窗口将自动裁剪最旧消息），降低 maxTokens 保护");
                    effectiveMaxTokens = Math.max(128, remainingTokens - promptTokens - 256);
                    AILogger.i(TAG, "Reduced maxTokens to " + effectiveMaxTokens);
                } else {
                    effectiveMaxTokens = maxTokens;
                }

                LlamaHelper.chatSend(message, effectiveMaxTokens, 0.7f, 0.9f, 40, enableThinking, new LlamaHelper.TokenCallback() {
                    @Override
                    public void onToken(String token) {
                        if (wrappedCallback != null) {
                            mainHandler.post(() -> wrappedCallback.onToken(token));
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        watchdogFuture.cancel(false);
                        long elapsed = System.currentTimeMillis() - startTime;
                        AILogger.i(TAG, "chatSend: onComplete called, fullText length: " + 
                                (fullText != null ? fullText.length() : 0) + ", elapsed: " + elapsed + "ms");
                        
                        if (wrappedCallback != null) {
                            mainHandler.post(() -> {
                                try {
                                    wrappedCallback.onComplete(fullText);
                                } catch (Exception e) {
                                    AILogger.e(TAG, "Error in chatSend onComplete callback: " + e.getMessage(), e);
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        watchdogFuture.cancel(false);
                        AILogger.e(TAG, "chatSend: onError called, error: " + error);
                        
                        if (wrappedCallback != null) {
                            mainHandler.post(() -> wrappedCallback.onError(error));
                        }
                    }
                });
                } finally {
                    // 释放推理锁
                    LlamaHelper.releaseInferenceWriteLock();
                }
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

    private LlamaHelper.TokenCallback buildStreamCallback(
            final java.util.concurrent.atomic.AtomicBoolean completed,
            final ScheduledFuture<?> watchdogFuture,
            final long startTime,
            final String prompt,
            final GenerateStreamCallback callback) {
        return new LlamaHelper.TokenCallback() {
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

                AILogger.i(TAG, "LlamaHelper.generateStream: onComplete called, fullText length: " + (fullText != null ? fullText.length() : 0) + ", elapsed: " + elapsed + "ms");
                AILogger.i(TAG, "Performance metrics - Speed: " + String.format("%.2f", inferenceSpeed) + " t/s, Tokens: " + tokenCount);
                sendLogBroadcast("INFO", "[AIService] 生成完成: 完整文本长度=" + (fullText != null ? fullText.length() : 0) + ", 耗时=" + elapsed + "ms");
                sendLogBroadcast("INFO", "[AIService] 性能监控: 推理速度=" + String.format("%.2f", inferenceSpeed) + " tokens/s, token数=" + tokenCount);

                // 清理模型输出中的乱码/非法字符
                String outputText = fullText;
                String cleaned = ToolResultInterpreter.cleanModelOutput(fullText);
                if (cleaned != null) {
                    outputText = cleaned;
                } else if (fullText != null) {
                    outputText = ToolResultInterpreter.sanitize(fullText);
                    AILogger.w(TAG, "generateStream: 模型输出检测为乱码，已清理非法字符");
                }
                final String finalOutput = outputText;

                mainHandler.post(() -> {
                    try {
                        callback.onSuccess(finalOutput);
                        saveChatMessage(0, "user", prompt, false);
                        saveChatMessage(0, "assistant", finalOutput, false);
                    } catch (Exception e) {
                        AILogger.e(TAG, "Error in onComplete callback: " + e.getMessage(), e);
                    }
                });
            }

            @Override
            public void onError(String error) {
                completed.set(true);
                watchdogFuture.cancel(false);
                AILogger.e(TAG, "LlamaHelper.generateStream: onError called, error: " + error);
                sendLogBroadcast("ERROR", "[AIService] 生成错误: " + error);
                mainHandler.post(() -> callback.onError(new Exception(error)));
            }
        };
    }

    public void chatStop() {
        try {
            AILogger.i(TAG, "chatStop called");
            LlamaHelper.chatStop();
        } catch (Exception e) {
            AILogger.w(TAG, "Error in chatStop (safe to ignore): " + e.getMessage());
        }
    }

    public void chatClear() {
        synchronized (chatContextLock) {
            try {
                AILogger.i(TAG, "chatClear called");
                LlamaHelper.chatClear();
                UnifiedContextManager.getInstance().notifyChatHistoryCleared();
            } catch (Exception e) {
                AILogger.w(TAG, "Error in chatClear: " + e.getMessage());
            }
        }
    }

    public void chatDestroy() {
        synchronized (chatContextLock) {
            if (activeChatGenerationCount > 0) {
                AILogger.w(TAG, "chatDestroy: waiting for " + activeChatGenerationCount + " active generations");
                try {
                    for (int i = 0; i < 50 && activeChatGenerationCount > 0; i++) {
                        chatContextLock.wait(100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            try {
                AILogger.i(TAG, "chatDestroy called, isChatActive=" + isChatActive);
                LlamaHelper.chatDestroy();
                chatSystemPrompt = null;
                isChatActive = false;
                lastChatContextHandle = 0;
                UnifiedContextManager.getInstance().setChatContextReady(false);
            } catch (Exception e) {
                AILogger.w(TAG, "Error in chatDestroy: " + e.getMessage());
            }
        }
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

    // ==================== 模型下载功能 ====================

    public String downloadModelFromPreset(String modelId, String presetName, ModelDownloadManager.DownloadCallback callback) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        List<ModelDownloadManager.ModelPresetInfo> presets = downloadManager.getPresetDomesticModels();
        
        for (ModelDownloadManager.ModelPresetInfo preset : presets) {
            if (preset.id.equals(modelId) || preset.name.equals(presetName)) {
                return downloadManager.downloadPresetModel(modelId, preset, callback);
            }
        }
        
        if (callback != null) {
            callback.onError(modelId, "未找到预设模型: " + presetName);
        }
        return null;
    }

    public String downloadModelFromUrl(String modelId, String url, ModelDownloadManager.DownloadCallback callback) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.downloadFromCustomUrl(modelId, url, callback);
    }

    public List<ModelDownloadManager.ModelPresetInfo> getAvailableDomesticModels() {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.getPresetDomesticModels();
    }

    public void setUseDomesticMirror(boolean enabled) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        downloadManager.setUseDomesticMirror(enabled);
    }

    public boolean isUseDomesticMirror() {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.isUseDomesticMirror();
    }

    public void setMirrorSource(ModelDownloadManager.MirrorSource source) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        downloadManager.setCurrentMirrorSource(source);
    }

    public ModelDownloadManager.MirrorSource getMirrorSource() {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.getCurrentMirrorSource();
    }

    public String convertUrlToDomesticMirror(String originalUrl) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.convertToDomesticMirror(originalUrl);
    }

    public ModelDownloadManager.DownloadProgress getDownloadProgress(String modelId) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.getProgress(modelId);
    }

    public void pauseDownload(String modelId) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        downloadManager.pause(modelId);
    }

    public void cancelDownload(String modelId) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        downloadManager.cancel(modelId);
    }

    public boolean isDownloading(String modelId) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.isDownloading(modelId);
    }

    // ==================== 模型互传功能 ====================

    public String shareModel(String modelName, String title) {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        
        String modelPath = getModelFullPath(modelName);
        if (modelPath == null) {
            AILogger.e(TAG, "无法找到模型文件: " + modelName);
            return null;
        }
        
        return transferManager.shareModelViaFileProvider(modelPath, title);
    }

    public String exportModel(String modelName, ModelTransferManager.TransferCallback callback) {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        return transferManager.exportModelForBackup(modelName, callback);
    }

    public boolean startModelTransferServer(int port) {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        return transferManager.startServer(port);
    }

    public void stopModelTransferServer() {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        transferManager.stopServer();
    }

    public boolean isTransferServerRunning() {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        return transferManager.isServerRunning();
    }

    public String getTransferServerLocalIp() {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        return transferManager.getLocalIpAddress();
    }

    public int getTransferServerPort() {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        return transferManager.getServerPort();
    }

    public String sendModelToDevice(String modelName, String targetHost, int targetPort, 
                                    ModelTransferManager.TransferCallback callback) {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        String modelPath = getModelFullPath(modelName);
        
        if (modelPath == null) {
            if (callback != null) {
                callback.onError(null, "无法找到模型文件: " + modelName);
            }
            return null;
        }
        
        return transferManager.sendModelToServer(modelPath, targetHost, targetPort, callback);
    }

    public ModelTransferManager.TransferProgress getTransferProgress(String taskId) {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        return transferManager.getProgress(taskId);
    }

    public void cancelTransfer(String taskId) {
        ModelTransferManager transferManager = ModelTransferManager.getInstance(context);
        transferManager.cancelTransfer(taskId);
    }

    private String getModelFullPath(String modelName) {
        ModelManager modelManager = new ModelManager(context);
        String modelDir = modelManager.getModelSaveDirectory();
        File modelFile = new File(modelDir, modelName);
        
        if (modelFile.exists()) {
            return modelFile.getAbsolutePath();
        }
        
        File defaultDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
        File defaultModelFile = new File(defaultDir, modelName);
        if (defaultModelFile.exists()) {
            return defaultModelFile.getAbsolutePath();
        }
        
        File rootModelFile = new File(context.getFilesDir(), modelName);
        if (rootModelFile.exists()) {
            return rootModelFile.getAbsolutePath();
        }
        
        return null;
    }

    // ==================== 模型目录配置功能 ====================

    public boolean setModelSaveDirectory(String directoryPath) {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.setModelSaveDirectory(directoryPath);
    }

    public String getModelSaveDirectory() {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.getModelSaveDirectory();
    }

    public boolean isUsingCustomModelDirectory() {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.isUsingCustomDirectory();
    }

    public void resetToDefaultModelDirectory() {
        ModelManager modelManager = new ModelManager(context);
        modelManager.resetToDefaultDirectory();
    }

    // ==================== 模型导入功能 ====================

    public String importModelFromLocalPath(String modelPath, ModelManager.ImportCallback callback) {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.importModelFromLocalPath(modelPath, callback);
    }

    public List<String> autoImportFromDirectory(String directoryPath, ModelManager.ImportCallback callback) {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.autoImportFromDirectory(directoryPath, callback);
    }

    public List<String> scanExternalStorageForModels() {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.scanExternalStorageForModels();
    }

    public void setAutoImportEnabled(boolean enabled) {
        ModelManager modelManager = new ModelManager(context);
        modelManager.setAutoImportEnabled(enabled);
    }

    public boolean isAutoImportEnabled() {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.isAutoImportEnabled();
    }

    // ==================== 已下载模型列表功能 ====================

    public String[] getDownloadedModelList() {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.listAvailableModels();
    }

    public List<ModelManager.ModelFileInfo> getDownloadedModelListWithInfo() {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.listAvailableModelsWithInfo();
    }

    public int getDownloadedModelCount() {
        ModelRegistry registry = ModelRegistry.getInstance(context);
        return registry.getDownloadedCount();
    }

    public boolean isModelDownloaded(String modelName) {
        ModelManager modelManager = new ModelManager(context);
        return modelManager.isModelAvailable(modelName);
    }

    public Map<String, ModelRegistry.ModelMetadata> getDownloadedModelsWithMetadata() {
        ModelRegistry registry = ModelRegistry.getInstance(context);
        return registry.getDownloadedModels();
    }

    // ==================== 预设模型分类功能 ====================

    public List<ModelDownloadManager.ModelPresetInfo> getPresetModelsByCategory(ModelDownloadManager.ModelCategory category) {
        ModelDownloadManager downloadManager = ModelDownloadManager.getInstance(context);
        return downloadManager.getPresetModelsByCategory(category);
    }

    public List<ModelDownloadManager.ModelPresetInfo> getChinesePresetModels() {
        return getPresetModelsByCategory(ModelDownloadManager.ModelCategory.CHINESE);
    }

    public List<ModelDownloadManager.ModelPresetInfo> getCodePresetModels() {
        return getPresetModelsByCategory(ModelDownloadManager.ModelCategory.CODE);
    }

    public List<ModelDownloadManager.ModelPresetInfo> getLightweightPresetModels() {
        return getPresetModelsByCategory(ModelDownloadManager.ModelCategory.LIGHTWEIGHT);
    }

    public List<ModelDownloadManager.ModelPresetInfo> getPerformancePresetModels() {
        return getPresetModelsByCategory(ModelDownloadManager.ModelCategory.PERFORMANCE);
    }

    public int getPresetModelCount() {
        return getAvailableDomesticModels().size();
    }
}
