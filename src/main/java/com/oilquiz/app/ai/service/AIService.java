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
import com.oilquiz.app.ai.gpu.GPUAccelerationManager;
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
    private GPUAccelerationManager gpuAccelerationManager;

    // 状态观察者列表
    private List<StatusObserver> statusObservers = new ArrayList<>();

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

    public GPUAccelerationManager getGPUAccelerationManager() {
        return gpuAccelerationManager;
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
        
        // 检查模型是否真正在内存中初始化（更准确的检查）
        boolean modelInMemory = LlamaHelper.isModelInitialized();
        
        // 如果模型在内存中但Java状态未标记，则同步状态
        if (modelInMemory && !instance.isInitialized) {
            AILogger.i(TAG, "检测到模型在内存中，但Java状态未标记，立即同步状态");
            instance.isInitialized = true;
            AILogger.i(TAG, "AI服务状态已同步，模型在内存中");
        }
        
        // 如果实例已初始化且模型在内存中，跳过初始化
        if (instance.isInitialized && modelInMemory) {
            AILogger.i(TAG, "AI服务已处于热状态（模型在内存中），跳过初始化");
            return instance;
        }
        
        // 如果实例已初始化但模型不在内存中（可能被释放），仅同步 Java 标记；加载模型由显式 initialize/switchModel 或后台预加载完成
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
        if (!LlamaHelper.isLibraryLoaded()) {
            AILogger.e(TAG, "LlamaHelper library not loaded, cannot initialize AI service");
            return false;
        }
        isLoading = true;
        notifyStatusChange();
        try {
            File modelFile = copyModelToInternalStorage(modelName);
            if (modelFile == null) {
                AILogger.e(TAG, "Failed to locate model file: " + modelName);
                return false;
            }
            if (!modelFile.exists()) {
                AILogger.e(TAG, "Model file does not exist: " + modelFile.getAbsolutePath());
                return false;
            }
            if (!modelFile.canRead()) {
                AILogger.e(TAG, "Model file is not readable: " + modelFile.getAbsolutePath());
                return false;
            }

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
            int result = LlamaHelper.initModel(
                    modelFile.getAbsolutePath(),
                    contextSize,
                    threadCount
            );

            if (result != 0 && LlamaHelper.getGPULayers() > 0) {
                AILogger.w(TAG, "GPU initialization failed (code: " + result + "), trying CPU-only mode...");
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
                isInitialized = true;
                currentModelName = modelName;
                saveModelName(modelName);
                if (crashHandler != null) {
                    crashHandler.startMonitoring();
                }
                AILogger.i(TAG, "AI service initialized successfully with model: " + modelName);
                initDefaultChatContext();
                return true;
            }
            isInitialized = false;
            currentModelName = null;
            AILogger.e(TAG, "Failed to initialize AI service: " + result);
            return false;
        } catch (Exception e) {
            isInitialized = false;
            currentModelName = null;
            AILogger.e(TAG, "Error initializing AI service: " + e.getMessage(), e);
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
        // 更新使用时间戳
        updateLastUsedTime();
        
        if (isLoading) {
            callback.onError(new IllegalStateException("AI service is loading, please wait"));
            return;
        }
        
        if (!isInitialized) {
            callback.onError(new IllegalStateException("AI service not initialized"));
            return;
        }
        
        // 检查模型是否真正加载完成（不仅仅是isInitialized标志）
        if (!LlamaHelper.isModelInitialized()) {
            callback.onError(new IllegalStateException("AI model is still loading, please wait"));
            return;
        }

        // 记录AI活动
        crashHandler.recordActivity();

        executorService.execute(() -> {
            try {
                // 使用Native chat context进行生成，由Native层管理上下文
                boolean contextActive = LlamaHelper.isChatContextActive();
                
                if (!contextActive) {
                    // 如果没有活跃的chat context，使用简单生成模式
                    AILogger.d(TAG, "No active chat context, using simple generate mode");
                    String formattedPrompt = formatPromptForModel(prompt, history);
                    int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                    String response = LlamaHelper.generate(formattedPrompt, adjustedMaxTokens, 0.7f);
                    
                    if (response != null && (response.startsWith("Error:") || response.contains("not available") || response.contains("failed"))) {
                        AILogger.e(TAG, "AI generation error: " + response);
                        mainHandler.post(() -> callback.onError(new Exception(response)));
                    } else {
                        mainHandler.post(() -> {
                            callback.onSuccess(response);
                            saveChatMessage(0, "user", prompt, false);
                            saveChatMessage(0, "assistant", response, false);
                        });
                    }
                } else {
                    // 使用Native chat context，由Native管理历史和裁剪
                    AILogger.d(TAG, "Using native chat context for generation");
                    int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                    
                    // 使用同步方式获取结果
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
                            lock.wait(60000); // 最多等待60秒
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
        // 更新使用时间戳
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
        
        // 检查模型是否真正加载完成（不仅仅是isInitialized标志）
        if (!LlamaHelper.isModelInitialized()) {
            AILogger.e(TAG, "AI model not initialized, returning error");
            sendLogBroadcast("ERROR", "[AIService] 模型正在加载，请等待");
            callback.onError(new IllegalStateException("AI model is still loading, please wait"));
            return;
        }
        
        AILogger.i(TAG, "Pre-checks passed, preparing generation...");
        sendLogBroadcast("INFO", "[AIService] 预检查通过");

        // 创建一个可取消的任务
        final java.util.concurrent.Future<?>[] taskFuture = new java.util.concurrent.Future<?>[1];
        
        taskFuture[0] = executorService.submit(() -> {
            try {
                // 使用Native chat context进行生成，由Native层管理上下文和裁剪
                boolean contextActive = LlamaHelper.isChatContextActive();
                
                if (!contextActive) {
                    // 如果没有活跃的chat context，使用简单生成模式
                    AILogger.i(TAG, "No active chat context, using simple generateStream mode");
                    
                    String formattedPrompt;
                    if (promptRequest != null) {
                        formattedPrompt = promptRequest.build();
                    } else {
                        formattedPrompt = formatPromptForModel(prompt, history);
                    }
                    sendLogBroadcast("INFO", "[AIService] 提示词格式化完成: 格式化后长度=" + (formattedPrompt != null ? formattedPrompt.length() : 0));
                    
                    AILogger.i(TAG, "================= 生成文本开始 =================");
                    AILogger.i(TAG, "原始提示词长度: " + (prompt != null ? prompt.length() : 0));
                    AILogger.i(TAG, "格式化后提示词长度: " + (formattedPrompt != null ? formattedPrompt.length() : 0));
                    
                    if (formattedPrompt != null) {
                        int logLength = Math.min(formattedPrompt.length(), 500);
                        String logPrompt = formattedPrompt.substring(0, logLength);
                        AILogger.i(TAG, "格式化提示词内容:\n" + logPrompt + (formattedPrompt.length() > 500 ? "... (已截断)" : ""));
                    }
                    
                    int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                    AILogger.i(TAG, "调整生成 token 数: 原始 " + maxTokens + ", 调整后 " + adjustedMaxTokens);
                    
                    final long startTime = System.currentTimeMillis();
                    final long timeoutMs = 600000;
                    final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    
                    ScheduledFuture<?> watchdogFuture = watchdogScheduler.schedule(() -> {
                        if (!completed.get()) {
                            AILogger.w(TAG, "Generation timeout after " + timeoutMs + "ms, stopping...");
                            LlamaHelper.stopGeneration();
                            mainHandler.post(() -> callback.onError(new Exception("生成超时，请重试")));
                            if (taskFuture[0] != null && !taskFuture[0].isDone()) {
                                taskFuture[0].cancel(true);
                            }
                        }
                    }, timeoutMs, TimeUnit.MILLISECONDS);
                    
                    LlamaHelper.generateStream(formattedPrompt, adjustedMaxTokens, 0.7f, 0.9f, 40, new LlamaHelper.TokenCallback() {
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
                            
                            AILogger.i(TAG, "LlamaHelper.TokenCallback: onComplete called, fullText length: " + (fullText != null ? fullText.length() : 0) + ", elapsed: " + elapsed + "ms");
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
                            AILogger.e(TAG, "LlamaHelper.TokenCallback: onError called, error: " + error);
                            sendLogBroadcast("ERROR", "[AIService] 生成错误: " + error);
                            mainHandler.post(() -> callback.onError(new Exception(error)));
                        }
                    });
                } else {
                    // 使用Native chat context，由Native管理历史和裁剪
                    AILogger.i(TAG, "Using native chat context for streaming generation");
                    sendLogBroadcast("INFO", "[AIService] 使用Native chat context进行流式生成");
                    
                    int adjustedMaxTokens = Math.max(1, maxTokens - 64);
                    AILogger.i(TAG, "调整生成 token 数: 原始 " + maxTokens + ", 调整后 " + adjustedMaxTokens);
                    
                    final long startTime = System.currentTimeMillis();
                    final long timeoutMs = 600000;
                    final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    
                    ScheduledFuture<?> watchdogFuture2 = watchdogScheduler.schedule(() -> {
                        if (!completed.get()) {
                            AILogger.w(TAG, "Chat send timeout after " + timeoutMs + "ms, stopping...");
                            LlamaHelper.chatStop();
                            mainHandler.post(() -> callback.onError(new Exception("生成超时，请重试")));
                            if (taskFuture[0] != null && !taskFuture[0].isDone()) {
                                taskFuture[0].cancel(true);
                            }
                        }
                    }, timeoutMs, TimeUnit.MILLISECONDS);
                    
                    // 使用chatSend，由Native层管理上下文
                    LlamaHelper.chatSend(prompt, adjustedMaxTokens, 0.7f, 0.9f, 40, false, new LlamaHelper.TokenCallback() {
                        @Override
                        public void onToken(String token) {
                            mainHandler.post(() -> callback.onToken(token));
                        }

                        @Override
                        public void onComplete(String fullText) {
                            completed.set(true);
                            watchdogFuture2.cancel(false);
                            AILogger.i(TAG, "LlamaHelper.chatSend: onComplete called");
                            sendLogBroadcast("INFO", "[AIService] 生成完成");
                            
                            mainHandler.post(() -> {
                                try {
                                    // Native层已经保存了上下文，这里不需要额外处理
                                    callback.onSuccess(fullText);
                                } catch (Exception e) {
                                    AILogger.e(TAG, "Error in onComplete callback: " + e.getMessage(), e);
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            completed.set(true);
                            watchdogFuture2.cancel(false);
                            AILogger.e(TAG, "LlamaHelper.chatSend: onError called, error: " + error);
                            sendLogBroadcast("ERROR", "[AIService] 生成错误: " + error);
                            mainHandler.post(() -> callback.onError(new Exception(error)));
                        }
                    });
                }
                
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
        // 更新使用时间戳
        updateLastUsedTime();
        
        if (isLoading) {
            throw new IllegalStateException("AI service is loading, please wait");
        }
        
        if (!isInitialized) {
            throw new IllegalStateException("AI service not initialized");
        }
        
        // 检查模型是否真正加载完成（不仅仅是isInitialized标志）
        if (!LlamaHelper.isModelInitialized()) {
            AILogger.e(TAG, "Model not fully loaded yet, isInitialized=" + isInitialized);
            throw new IllegalStateException("AI model is still loading, please wait");
        }

        try {
            if (prompt == null || prompt.trim().isEmpty()) {
                AILogger.e(TAG, "Prompt is null or empty");
                throw new IllegalArgumentException("Prompt cannot be null or empty");
            }
            
            // 压缩历史消息，防止token溢出
            int maxHistoryTokens = Math.min(8192, Math.max(512, INFERENCE_N_CTX / 2));
            List<PromptBuilder.Message> compressedHistory = compressHistory(history, maxHistoryTokens);
            
            // 根据模型类型调整prompt格式
            String formattedPrompt = formatPromptForModel(prompt, compressedHistory);
            
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
            AILogger.e(TAG, "OutOfMemoryError in generateSync: " + e.getMessage(), e);
            throw new RuntimeException("内存溢出，请尝试减小模型大小或清理内存", e);
        } catch (Exception e) {
            AILogger.e(TAG, "Error generating text: " + e.getMessage(), e);
            throw e;
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
     */
    private void preloadOpenClIfNeeded() {
        if (openClPreloaded) {
            return;
        }
        synchronized (this) {
            if (openClPreloaded) {
                return;
            }
            try {
                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
                System.load(nativeLibDir + "/libOpenCL.so");
                AILogger.i(TAG, "libOpenCL.so loaded from " + nativeLibDir);
            } catch (UnsatisfiedLinkError e) {
                AILogger.w(TAG, "libOpenCL.so not available: " + e.getMessage());
            }
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

            try {
                gpuAccelerationManager = GPUAccelerationManager.getInstance(context);
                if (gpuAccelerationManager.isAvailable()) {
                    AILogger.i(TAG, "GPUAccelerationManager: " + gpuAccelerationManager.getGPUInfo());
                }
            } catch (Exception e) {
                AILogger.w(TAG, "GPUAccelerationManager init failed: " + e.getMessage());
            }

            boolean hasVulkanSupport = gpuInfo != null && gpuInfo.supportsVulkan;
            boolean hasFP16Support = gpuInfo != null && gpuInfo.supportsFP16;

            if (gpuAccelerationManager != null && gpuAccelerationManager.isAvailable()) {
                hasVulkanSupport = true;
                GPUAccelerationManager.GPUDeviceInfo gpuDeviceInfo = gpuAccelerationManager.getGPUInfo();
                if (gpuDeviceInfo != null) {
                    hasFP16Support = gpuDeviceInfo.supportsFP16;
                }
                AILogger.i(TAG, "Using GPUAccelerationManager detection: Vulkan=" + hasVulkanSupport + ", FP16=" + hasFP16Support);
            }
            
            long availableMemoryMB = memoryInfo.availableMemoryMB;
            long totalMemoryMB = memoryInfo.totalMemoryMB;
            int cpuCores = Runtime.getRuntime().availableProcessors();
            
            AILogger.i(TAG, "=== Hardware Capability Detection ===");
            AILogger.i(TAG, "GPU Renderer: " + (gpuInfo != null ? gpuInfo.renderer : "Unknown"));
            AILogger.i(TAG, "Vulkan Support: " + hasVulkanSupport + ", FP16 Support: " + hasFP16Support);
            AILogger.i(TAG, "Total Memory: " + totalMemoryMB + "MB, Available: " + availableMemoryMB + "MB");
            AILogger.i(TAG, "CPU Cores: " + cpuCores);

            // 动态计算GPU layers（根据设备资源）
            int gpuLayers = calculateOptimalGPULayers(gpuDetector, gpuInfo, gpuProfile, memoryInfo);
            
            LlamaHelper.setGPULayers(gpuLayers);
            
            // 设置线程数（考虑GPU负载）
            int threadCount = gpuLayers > 0 ? Math.min(cpuCores, 4) : Math.min(cpuCores, 6);
            
            // 设置批处理大小（GPU模式使用更大的batch）
            int batchSize = calculateOptimalBatchSize(gpuLayers > 0, availableMemoryMB, totalMemoryMB);
            
            // 设置内存池大小（根据可用内存动态调整）
            int memoryPoolSize = calculateOptimalMemoryPoolSize(gpuLayers > 0, availableMemoryMB, totalMemoryMB);

            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(batchSize);
            LlamaHelper.setMemoryPoolSize(memoryPoolSize);

            AILogger.i(TAG, "=== Optimizations Applied ===");
            AILogger.i(TAG, "Mode: " + (gpuLayers > 0 ? "GPU" : "CPU"));
            AILogger.i(TAG, "GPU Layers: " + gpuLayers);
            AILogger.i(TAG, "Memory Pool: " + memoryPoolSize + "MB");
            AILogger.i(TAG, "Batch Size: " + batchSize);
            AILogger.i(TAG, "Thread Count: " + threadCount);
        } catch (Exception e) {
            AILogger.e(TAG, "Error applying settings: " + e.getMessage(), e);
            // 异常时回退到CPU模式
            LlamaHelper.setGPULayers(0);
            LlamaHelper.setThreadCount(Runtime.getRuntime().availableProcessors());
            LlamaHelper.setBatchSize(64);
            LlamaHelper.setMemoryPoolSize(512);
        }
    }
    
    /**
     * 根据设备资源动态计算最优GPU layers
     * 考虑因素：GPU能力、可用内存、显存大小、GPU等级
     */
    private int calculateOptimalGPULayers(GpuCapabilityDetector gpuDetector, GpuInfo gpuInfo, 
                                          GpuProfile gpuProfile, MemoryUsageInfo memoryInfo) {
        boolean vulkanAvailable = (gpuInfo != null && gpuInfo.supportsVulkan);
        if (!vulkanAvailable && gpuAccelerationManager != null && gpuAccelerationManager.isAvailable()) {
            vulkanAvailable = true;
            AILogger.i(TAG, "Vulkan available via GPUAccelerationManager override");
        }

        if (!vulkanAvailable) {
            AILogger.i(TAG, "No Vulkan support, returning 0 layers");
            return 0;
        }
        
        long availableMemoryMB = memoryInfo.availableMemoryMB;
        long totalMemoryMB = memoryInfo.totalMemoryMB;
        GpuTier gpuTier = gpuDetector.getGpuTier();
        
        // 基础layers值（基于GPU等级）
        int baseLayers = getBaseLayersForTier(gpuTier);
        
        // 根据可用内存调整系数
        float memoryFactor = getMemoryFactor(availableMemoryMB, totalMemoryMB);
        
        // 根据GPU显存调整系数
        float gpuMemoryFactor = getGpuMemoryFactor(gpuDetector);
        
        boolean fp16Supported = (gpuInfo != null && gpuInfo.supportsFP16);
        if (!fp16Supported && gpuAccelerationManager != null && gpuAccelerationManager.isAvailable()) {
            GPUAccelerationManager.GPUDeviceInfo devInfo = gpuAccelerationManager.getGPUInfo();
            if (devInfo != null) fp16Supported = devInfo.supportsFP16;
        }
        float fp16Factor = fp16Supported ? 1.2f : 0.8f;
        
        // 综合计算
        int calculatedLayers = (int) (baseLayers * memoryFactor * gpuMemoryFactor * fp16Factor);
        
        // 应用GPU profile的限制
        if (gpuProfile != null) {
            calculatedLayers = Math.min(calculatedLayers, gpuProfile.recommendedConfig.gpuLayers);
        }
        
        // 确保在合理范围内
        calculatedLayers = Math.max(0, calculatedLayers);
        calculatedLayers = Math.min(50, calculatedLayers); // 最大50层
        
        AILogger.i(TAG, "GPU Layers Calculation: base=" + baseLayers + 
                   ", memoryFactor=" + String.format("%.2f", memoryFactor) +
                   ", gpuMemFactor=" + String.format("%.2f", gpuMemoryFactor) +
                   ", fp16Factor=" + String.format("%.2f", fp16Factor) +
                   ", result=" + calculatedLayers);
        
        return calculatedLayers;
    }
    
    /**
     * 根据GPU等级获取基础layers值
     */
    private int getBaseLayersForTier(GpuTier tier) {
        if (tier == GpuTier.HIGH) {
            return 35;
        } else if (tier == GpuTier.MID) {
            return 20;
        } else if (tier == GpuTier.LOW) {
            return 8;
        } else {
            return 10;
        }
    }
    
    /**
     * 根据可用内存计算调整系数
     */
    private float getMemoryFactor(long availableMB, long totalMB) {
        // 可用内存越少，系数越小
        float availableRatio = (float) availableMB / totalMB;
        
        if (availableRatio > 0.6f) {
            return 1.2f; // 内存充足，可以增加layers
        } else if (availableRatio > 0.4f) {
            return 1.0f; // 内存适中
        } else if (availableRatio > 0.2f) {
            return 0.7f; // 内存紧张
        } else {
            return 0.4f; // 内存非常紧张
        }
    }
    
    /**
     * 根据GPU显存大小计算调整系数
     */
    private float getGpuMemoryFactor(GpuCapabilityDetector gpuDetector) {
        long gpuMemoryMB = gpuDetector.getAvailableGpuMemoryMB();
        
        if (gpuMemoryMB >= 8192) {
            return 1.3f; // 8GB+ 显存
        } else if (gpuMemoryMB >= 4096) {
            return 1.1f; // 4GB 显存
        } else if (gpuMemoryMB >= 2048) {
            return 1.0f; // 2GB 显存
        } else if (gpuMemoryMB >= 1024) {
            return 0.8f; // 1GB 显存
        } else if (gpuMemoryMB > 0) {
            return 0.5f; // 小于1GB显存
        } else {
            // 无法获取显存信息，使用保守值
            return 0.9f;
        }
    }
    
    /**
     * 计算最优批处理大小
     */
    private int calculateOptimalBatchSize(boolean isGpuMode, long availableMB, long totalMB) {
        float memoryRatio = (float) availableMB / totalMB;
        
        if (isGpuMode) {
            if (memoryRatio > 0.6f) {
                return 512;
            } else if (memoryRatio > 0.4f) {
                return 256;
            } else {
                return 128;
            }
        } else {
            if (memoryRatio > 0.5f) {
                return 128;
            } else {
                return 64;
            }
        }
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
     * 优化批处理大小
     */
    private void optimizeBatchSize(long totalDeviceMem) {
        int batchSize = 32; // 默认值
        long totalMemoryMB = totalDeviceMem / (1024 * 1024);
        
        if (totalMemoryMB >= 8192) {
            batchSize = 64;
        } else if (totalMemoryMB >= 4096) {
            batchSize = 48;
        } else if (totalMemoryMB >= 2048) {
            batchSize = 32;
        } else {
            batchSize = 16;
        }
        
        LlamaHelper.setBatchSize(batchSize);
        AILogger.i(TAG, "Optimized batch size: " + batchSize + " based on total memory: " + totalMemoryMB + " MB");
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
        LlamaHelper.setGPULayers(30);
        LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 4096));
        LlamaHelper.setThreadCount(8);
        LlamaHelper.setBatchSize(512);
        
        AILogger.i(TAG, "Xiaomi 14 optimizations applied: GPU=30, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=512, Threads=8");
    }
    
    /**
     * Adreno GPU优化
     */
    private void applyAdrenoOptimizations(String series, long totalDeviceMem) {
        AILogger.i(TAG, "Applying Adreno optimizations for series: " + series);
        
        switch (series) {
            case "A7XX":
                // Adreno 740/750 - 高端GPU
                LlamaHelper.setGPULayers(25);
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 4096));
                LlamaHelper.setThreadCount(8);
                LlamaHelper.setBatchSize(512);
                AILogger.i(TAG, "A7XX optimizations applied: GPU=25, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=512, Threads=8");
                break;
                
            case "A6XX":
                // Adreno 650 - 中端GPU
                LlamaHelper.setGPULayers(15);
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 1024));
                LlamaHelper.setThreadCount(6);
                LlamaHelper.setBatchSize(256);
                AILogger.i(TAG, "A6XX optimizations applied: GPU=15, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=256, Threads=6");
                break;
                
            case "A5XX":
                // Adreno 5xx - 低端GPU
                LlamaHelper.setGPULayers(10);
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 512));
                LlamaHelper.setThreadCount(4);
                LlamaHelper.setBatchSize(128);
                AILogger.i(TAG, "A5XX optimizations applied: GPU=10, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=128, Threads=4");
                break;
                
            default:
                // 通用优化
                LlamaHelper.setGPULayers(5);
                LlamaHelper.setMemoryPoolSize(safeMemoryPoolSize(totalDeviceMem, 256));
                LlamaHelper.setThreadCount(4);
                LlamaHelper.setBatchSize(64);
                AILogger.i(TAG, "Generic Adreno optimizations applied: GPU=5, MemPool=" + LlamaHelper.getMemoryPoolSize() + "MB, Batch=64, Threads=4");
                break;
        }
    }
    
    /**
     * 基于设备能力的自动调优
     */
    private void autoTuneBasedOnCapabilities() {
        AILogger.i(TAG, "Auto-tuning based on device capabilities");
        
        try {
            // 基于设备能力设置参数 - CPU模式
            int cpuCores = DeviceDetector.getCPUCores();
            int threadCount = Math.min(cpuCores, 4); // 默认4线程
            int batchSize = 16; // 默认批处理大小
            
            AILogger.i(TAG, "Auto-tuned parameters - CPU Cores: " + cpuCores + ", Threads: " + threadCount + ", Batch: " + batchSize);
            
            // 设置优化参数 - CPU模式
            LlamaHelper.setGPULayers(0); // 禁用 GPU
            LlamaHelper.setMemoryPoolSize(1024); // 默认内存池大小
            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(batchSize);
            
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to auto-tune, using default optimizations: " + e.getMessage());
            // 使用默认值
            int cpuCores = DeviceDetector.getCPUCores();
            int threadCount = Math.min(cpuCores, 4); 
            int batchSize = 16;
            
            LlamaHelper.setGPULayers(0); // 禁用 GPU
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
     * 状态观察者接口
     */
    public interface StatusObserver {
        void onStatusChanged(boolean isInitialized, String modelName);
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
     * 通知所有观察者状态变化
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
    
    // ==================== 生命周期管理 ====================
    
    /**
     * 应用进入后台时调用
     */
    public void onAppEnterBackground() {
        isAppInBackground = true;
        AILogger.i(TAG, "应用进入后台，AI服务状态: " + (isInitialized ? "已初始化" : "未初始化"));
    }
    
    /**
     * 应用回到前台时调用
     */
    public void onAppEnterForeground() {
        isAppInBackground = false;
        lastUsedTimestamp = System.currentTimeMillis();
        AILogger.i(TAG, "应用回到前台，检查AI服务状态...");
        
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
        
        if (!isInitialized) {
            AILogger.i(TAG, "模型未初始化；请用户在 AI 中心选择模型后加载（不在此处自动阻塞加载）");
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
        
        // 内存紧张时的处理逻辑
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            AILogger.w(TAG, "内存中度紧张，考虑释放资源");
            
            // 如果应用在后台且模型已加载，考虑释放
            if (isAppInBackground && isInitialized) {
                AILogger.i(TAG, "应用在后台且模型已加载，释放AI资源");
                release();
            } else {
                AILogger.i(TAG, "应用在前台，保留AI资源");
            }
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            AILogger.w(TAG, "内存严重紧张，强制释放资源");
            // 即使应用在前台，也释放资源以避免崩溃
            if (isInitialized) {
                release();
            }
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
            
            int ctxSize = CHAT_N_CTX;
            int nThreads = LlamaHelper.getThreadCount();
            if (nThreads <= 0) nThreads = 4;
            
            long handle = LlamaHelper.chatCreate("", ctxSize, nThreads, globalPrompt, systemPrompt, normalPrompt);
            if (handle != 0) {
                AILogger.i(TAG, "Default chat context created successfully");
            } else {
                AILogger.w(TAG, "Failed to create default chat context");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error creating default chat context: " + e.getMessage());
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
        synchronized (modelInitLock) {
            if (!isInitialized) {
                AILogger.e(TAG, "AIService not initialized, cannot create chat context");
                return false;
            }

            LlamaHelper.chatDestroy();

            this.chatSystemPrompt = systemPrompt;
            int ctxSize = CHAT_N_CTX;
            int nThreads = LlamaHelper.getThreadCount();
            if (nThreads <= 0) nThreads = 4;

            long handle = LlamaHelper.chatCreate("", ctxSize, nThreads, globalPrompt, systemPrompt, normalPrompt);
            if (handle == 0) {
                AILogger.e(TAG, "Failed to create native chat context");
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
        if (!LlamaHelper.isChatContextActive()) {
            if (callback != null) callback.onError("Chat context not active");
            return;
        }
        LlamaHelper.chatSend(message, maxTokens, 0.7f, 0.9f, 40, enableThinking, callback);
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
