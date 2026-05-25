package com.oilquiz.app.ai.jni;

import android.util.Log;
import com.oilquiz.app.util.AILogger;

/**
 * LlamaHelper - Llama.cpp Native JNI接口封装
 * 
 * 功能：
 * - 封装Llama.cpp原生库的JNI调用
 * - 提供模型加载、文本生成、历史管理功能
 * - 支持流式生成和批处理生成
 * - Native层日志回调集成
 * - GPU/设备能力检测
 * 
 * 主要功能：
 * 1. 模型管理：initModel, release, isModelInitialized
 * 2. 文本生成：generate, generateStream, generateBatch
 * 3. 参数设置：setGPULayers, setMemoryPoolSize, setBatchSize, setThreadCount
 * 4. 设备检测：getDeviceCount, getDeviceInfo, getFreeDeviceMemory
 * 5. 性能监控：getInferenceSpeed, getMemoryUsage, getTokenCount
 * 
 * Native层对应：
 * - llama-jni native库
 * - native-lib.cpp 实现
 * 
 * 使用示例：
 * // 初始化
 * int result = LlamaHelper.initModel("/path/to/model.gguf", 4096, 4);
 * 
 * // 生成
 * String response = LlamaHelper.generate("Hello", 512, 0.7f);
 * 
 * @author AI Team
 * @since 2024
 */
public class LlamaHelper {
    private static final String TAG = "LlamaHelper";
    private static final String LIBRARY_NAME = "llama-jni";
    private static volatile boolean libraryLoaded = false;
    
    private static volatile NativeLogCallback sLogCallback = null;
    
    private static volatile long lastModelInitCheckTime = 0;
    private static volatile boolean cachedModelInitialized = false;
    private static final long MODEL_INIT_CHECK_INTERVAL_MS = 1000;
    
    // Native 状态恢复跟踪
    private static volatile int nativeRecoveryAttemptCount = 0;
    private static volatile int nativeRecoverySuccessCount = 0;
    private static volatile long lastNativeRecoveryTime = 0;
    private static final int MAX_RECOVERY_ATTEMPTS_PER_HOUR = 5;
    private static final long RECOVERY_COOLDOWN_MS = 30000; // 30秒冷却
    
    public interface NativeLogCallback {
        void onLog(int level, String tag, String message);
    }
    
    public static void setNativeLogCallback(NativeLogCallback callback) {
        sLogCallback = callback;
    }
    
    public static void onNativeLog(int level, String tag, String message) {
        if (sLogCallback != null) {
            sLogCallback.onLog(level, tag, message);
        }
    }

    static {
        try {
            System.loadLibrary(LIBRARY_NAME);
            libraryLoaded = true;
            AILogger.i(TAG, "Successfully loaded llama-jni library");
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
            AILogger.e(TAG, "Failed to load llama-jni library: " + e.getMessage(), e);
        }
    }

    // 检查库是否已加载
    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    // 初始化模型
    public static int initModel(String modelPath, int nCtx, int nThreads) {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot initialize model");
            return -1;
        }
        
        ValidationResult validation = validateInitModelParams(modelPath, nCtx, nThreads);
        if (!validation.valid) {
            AILogger.e(TAG, "initModel param validation failed: " + validation.errorMessage);
            return -1;
        }
        
        if (validation.warningMessage != null) {
            AILogger.w(TAG, "initModel warning: " + validation.warningMessage);
        }
        
        try {
            int result = nativeInitModel(modelPath, nCtx, nThreads);
            if (result == 0) {
                cachedModelInitialized = true;
                lastModelInitCheckTime = System.currentTimeMillis();
            }
            return result;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error initializing model: " + e.getMessage(), e);
            return -1;
        } catch (Exception e) {
            AILogger.e(TAG, "Exception initializing model: " + e.getMessage(), e);
            return -1;
        }
    }

    private static native int nativeInitModel(String modelPath, int nCtx, int nThreads);

    // 生成文本（同步）
    public static String generate(String prompt, int maxTokens, float temperature) {
        return generate(prompt, maxTokens, temperature, 0.9f, 40);
    }

    public static String generate(String prompt, int maxTokens, float temperature, float topP, int topK) {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot generate text");
            return "Error: AI model not available";
        }
        try {
            return nativeGenerate(prompt, maxTokens, temperature, topP, topK);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error generating text: " + e.getMessage(), e);
            return "Error: AI generation failed";
        }
    }

    private static native String nativeGenerate(String prompt, int maxTokens, float temperature, float topP, int topK);

    // 生成文本（流式）
    public static void generateStream(String prompt, int maxTokens, float temperature, float topP, int topK, TokenCallback callback) {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot generate stream");
            if (callback != null) {
                callback.onError("AI model not available");
            }
            return;
        }
        try {
            nativeGenerateStream(prompt, maxTokens, temperature, topP, topK, callback);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error generating stream: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("AI generation failed");
            }
        }
    }

    private static native void nativeGenerateStream(String prompt, int maxTokens, float temperature, float topP, int topK, TokenCallback callback);

    // 生成文本（流式）- 使用ChatRequest批量传递参数，解决中文编码问题
    public static void generateStream(ChatRequest request, TokenCallback callback) {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot generate stream");
            if (callback != null) {
                callback.onError("AI model not available");
            }
            return;
        }
        if (request == null || request.getFullPromptUtf8() == null) {
            AILogger.e(TAG, "Invalid ChatRequest");
            if (callback != null) {
                callback.onError("Invalid request");
            }
            return;
        }
        try {
            nativeGenerateStreamBytes(
                request.getFullPromptUtf8(),
                request.getMaxTokens(),
                request.getTemperature(),
                request.getTopP(),
                request.getTopK(),
                request.isEnableThinking(),
                callback
            );
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error generating stream: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("AI generation failed");
            }
        }
    }

    private static native void nativeGenerateStreamBytes(byte[] promptUtf8, int maxTokens, float temperature,
                                                         float topP, int topK, boolean enableThinking, TokenCallback callback);

    // 停止生成
    public static void stopGeneration() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot stop generation");
            return;
        }
        try {
            nativeStopGeneration();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error stopping generation: " + e.getMessage(), e);
        }
    }

    private static native void nativeStopGeneration();

    // 清空历史
    public static void clearHistory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot clear history");
            return;
        }
        try {
            nativeClearHistory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error clearing history: " + e.getMessage(), e);
        }
    }

    private static native void nativeClearHistory();

    // 释放资源
    public static void release() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot release resources");
            return;
        }
        try {
            nativeRelease();
            cachedModelInitialized = false;
            lastModelInitCheckTime = System.currentTimeMillis();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error releasing resources: " + e.getMessage(), e);
        }
    }

    private static native void nativeRelease();

    // 获取模型信息
    public static String getModelInfo() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get model info");
            return "Error: AI model not available";
        }
        try {
            return nativeGetModelInfo();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting model info: " + e.getMessage(), e);
            return "Error: Failed to get model info";
        }
    }

    private static native String nativeGetModelInfo();

    // 性能监控相关方法
    public static float getInferenceSpeed() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get inference speed");
            return 0;
        }
        try {
            return nativeGetInferenceSpeed();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting inference speed: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native float nativeGetInferenceSpeed();

    public static float getMemoryUsage() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get memory usage");
            return 0;
        }
        try {
            return nativeGetMemoryUsage();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting memory usage: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native float nativeGetMemoryUsage();

    public static int getTokenCount() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get token count");
            return 0;
        }
        try {
            return nativeGetTokenCount();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting token count: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetTokenCount();

    // 性能优化相关方法
    public static void optimizeForPerformance() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot optimize for performance");
            return;
        }
        try {
            nativeOptimizeForPerformance();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error optimizing for performance: " + e.getMessage(), e);
        }
    }

    private static native void nativeOptimizeForPerformance();

    public static void optimizeForMemory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot optimize for memory");
            return;
        }
        try {
            nativeOptimizeForMemory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error optimizing for memory: " + e.getMessage(), e);
        }
    }

    private static native void nativeOptimizeForMemory();

    // GPU优化相关方法
    public static void setGPULayers(int layers) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set GPU layers");
            return;
        }
        try {
            nativeSetGPULayers(layers);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting GPU layers: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetGPULayers(int layers);

    public static int getGPULayers() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get GPU layers");
            return 0;
        }
        try {
            return nativeGetGPULayers();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting GPU layers: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetGPULayers();

    // 内存池设置
    public static void setMemoryPoolSize(int size) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set memory pool size");
            return;
        }
        try {
            nativeSetMemoryPoolSize(size);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting memory pool size: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetMemoryPoolSize(int size);

    public static int getMemoryPoolSize() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get memory pool size");
            return 0;
        }
        try {
            return nativeGetMemoryPoolSize();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting memory pool size: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetMemoryPoolSize();

    // 批处理大小设置
    public static void setBatchSize(int size) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set batch size");
            return;
        }
        try {
            nativeSetBatchSize(size);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting batch size: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetBatchSize(int size);

    public static int getBatchSize() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get batch size");
            return 0;
        }
        try {
            return nativeGetBatchSize();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting batch size: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetBatchSize();

    // 线程数设置
    public static void setThreadCount(int count) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot set thread count");
            return;
        }
        try {
            nativeSetThreadCount(count);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error setting thread count: " + e.getMessage(), e);
        }
    }

    private static native void nativeSetThreadCount(int count);

    public static int getThreadCount() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get thread count");
            return 0;
        }
        try {
            return nativeGetThreadCount();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting thread count: " + e.getMessage(), e);
            return 0;
        }
    }

    private static native int nativeGetThreadCount();

    // 检查模型是否已初始化
    public static boolean isModelInitialized() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot check if model is initialized");
            return false;
        }
        
        long now = System.currentTimeMillis();
        boolean shouldCheck = (now - lastModelInitCheckTime) > MODEL_INIT_CHECK_INTERVAL_MS;
        
        if (shouldCheck) {
            try {
                cachedModelInitialized = nativeIsModelInitialized();
                lastModelInitCheckTime = now;
            } catch (UnsatisfiedLinkError e) {
                AILogger.e(TAG, "Error checking if model is initialized: " + e.getMessage(), e);
                cachedModelInitialized = false;
                lastModelInitCheckTime = now;
            }
        }
        
        return cachedModelInitialized;
    }

    private static native boolean nativeIsModelInitialized();

    /**
     * 检查 Native 层状态是否有效（比 isModelInitialized 更严格）
     * 同时检查模型初始化和上下文活性
     */
    public static boolean isNativeStateValid() {
        if (!libraryLoaded) {
            return false;
        }
        boolean modelOk = isModelInitialized();
        if (!modelOk) {
            return false;
        }
        // 如果 chatContextHandle 为 0，说明还没创建聊天上下文，不算无效
        if (chatContextHandle == 0) {
            return true;
        }
        // 如果已经创建了聊天上下文，检查它是否仍然有效
        return isChatContextActive();
    }

    /**
     * 记录一次 Native 恢复尝试，返回是否可以继续尝试
     */
    public static boolean canAttemptRecovery() {
        long now = System.currentTimeMillis();
        // 冷却期检查
        if (now - lastNativeRecoveryTime < RECOVERY_COOLDOWN_MS) {
            AILogger.w(TAG, "Recovery attempt blocked by cooldown");
            return false;
        }
        // 每小时最大尝试次数检查
        if (nativeRecoveryAttemptCount >= MAX_RECOVERY_ATTEMPTS_PER_HOUR) {
            long timeSinceFirstAttempt = now - lastNativeRecoveryTime;
            if (timeSinceFirstAttempt < 3600000) { // 1小时内
                AILogger.w(TAG, "Recovery attempts exhausted for this hour: " + nativeRecoveryAttemptCount);
                return false;
            }
            // 超过1小时，重置计数
            nativeRecoveryAttemptCount = 0;
        }
        return true;
    }

    /**
     * 记录恢复尝试
     */
    public static void recordRecoveryAttempt() {
        nativeRecoveryAttemptCount++;
        lastNativeRecoveryTime = System.currentTimeMillis();
        AILogger.i(TAG, "Recovery attempt recorded, total: " + nativeRecoveryAttemptCount);
    }

    /**
     * 记录恢复成功
     */
    public static void recordRecoverySuccess() {
        nativeRecoverySuccessCount++;
        AILogger.i(TAG, "Recovery success recorded, total success: " + nativeRecoverySuccessCount);
    }

    /**
     * 获取恢复尝试次数
     */
    public static int getRecoveryAttemptCount() {
        return nativeRecoveryAttemptCount;
    }

    /**
     * 获取恢复成功次数
     */
    public static int getRecoverySuccessCount() {
        return nativeRecoverySuccessCount;
    }

    /**
     * 重置恢复计数
     */
    public static void resetRecoveryCount() {
        nativeRecoveryAttemptCount = 0;
        nativeRecoverySuccessCount = 0;
        lastNativeRecoveryTime = 0;
    }

    // 错误处理
    public static String getLastError() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get last error");
            return "Library not loaded";
        }
        try {
            return nativeGetLastError();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting last error: " + e.getMessage(), e);
            return "Error getting last error";
        }
    }

    private static native String nativeGetLastError();
    
    // 获取设备信息
    public static String getDeviceInfo() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get device info");
            return "Library not loaded";
        }
        try {
            String info = nativeGetDeviceInfo();
            AILogger.i(TAG, "Device info: " + info.replace("\n", ", "));
            return info;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting device info: " + e.getMessage(), e);
            return "Error getting device info";
        }
    }
    
    private static native String nativeGetDeviceInfo();
    
    // 获取设备数量
    public static int getDeviceCount() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get device count");
            return 0;
        }
        try {
            return nativeGetDeviceCount();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting device count: " + e.getMessage(), e);
            return 0;
        }
    }
    
    private static native int nativeGetDeviceCount();
    
    // 获取空闲设备内存
    public static long getFreeDeviceMemory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get free device memory");
            return 0;
        }
        try {
            return nativeGetFreeDeviceMemory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting free device memory: " + e.getMessage(), e);
            return 0;
        }
    }
    
    private static native long nativeGetFreeDeviceMemory();
    
    // 获取总设备内存
    public static long getTotalDeviceMemory() {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot get total device memory");
            return 0;
        }
        try {
            return nativeGetTotalDeviceMemory();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting total device memory: " + e.getMessage(), e);
            return 0;
        }
    }
    
    private static native long nativeGetTotalDeviceMemory();

    // 批处理生成
    public static String[] generateBatch(String[] prompts, int maxTokens, float temperature) {
        return generateBatch(prompts, maxTokens, temperature, 0.9f, 40);
    }

    public static String[] generateBatch(String[] prompts, int maxTokens, float temperature, float topP, int topK) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot generate batch");
            return new String[0];
        }
        try {
            return nativeGenerateBatch(prompts, maxTokens, temperature, topP, topK);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error generating batch: " + e.getMessage(), e);
            return new String[0];
        }
    }

    private static native String[] nativeGenerateBatch(String[] prompts, int maxTokens, float temperature, float topP, int topK);

    // Token回调接口
    public interface TokenCallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String error);
    }
    
    // 基于 llama_tokenize 的精确 Token 计数
    public static int countTokens(String text) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "Library not loaded, cannot count tokens");
            return 0;
        }
        try {
            return nativeCountTokens(text);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error counting tokens: " + e.getMessage(), e);
            return 0;
        }
    }
    
    private static native int nativeCountTokens(String text);

    // ========== Native Chat Context API ==========
    private static volatile long chatContextHandle = 0;
    private static int contextTotalSize = 0;
    private static int contextUsedTokens = 0;

    // 活跃生成任务标志，防止并发调用native层
    private static volatile boolean hasActiveGeneration = false;

    public static long chatCreate(String modelPath, int ctxSize, int nThreads, String globalPrompt, String systemPrompt, String normalPrompt) {
        if (!libraryLoaded) {
            AILogger.e(TAG, "chatCreate: library not loaded");
            return 0;
        }

        if (ctxSize <= 0) {
            AILogger.e(TAG, "chatCreate: invalid ctxSize=" + ctxSize);
            return 0;
        }

        if (ctxSize > 16384) {
            AILogger.w(TAG, "chatCreate: ctxSize " + ctxSize + " may be too large for mobile devices");
        }

        if (nThreads <= 0) {
            nThreads = 4;
        }

        String gPrompt = globalPrompt != null ? globalPrompt : "";
        String sPrompt = systemPrompt != null ? systemPrompt : "";
        String nPrompt = normalPrompt != null ? normalPrompt : "";

        AILogger.i(TAG, "chatCreate: ctxSize=" + ctxSize + ", nThreads=" + nThreads +
                ", globalPromptLen=" + gPrompt.length() +
                ", systemPromptLen=" + sPrompt.length() +
                ", normalPromptLen=" + nPrompt.length());

        try {
            chatContextHandle = nativeChatCreate(modelPath, ctxSize, nThreads, gPrompt, sPrompt, nPrompt);
            if (chatContextHandle != 0) {
                contextTotalSize = ctxSize;
                contextUsedTokens = 0;
                AILogger.i(TAG, "chatCreate: success, handle=" + chatContextHandle);
            } else {
                AILogger.e(TAG, "chatCreate: nativeChatCreate returned 0");
            }
            return chatContextHandle;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "chatCreate: UnsatisfiedLinkError: " + e.getMessage(), e);
            return 0;
        } catch (Exception e) {
            AILogger.e(TAG, "chatCreate: Exception: " + e.getMessage(), e);
            return 0;
        }
    }

    public static boolean chatUpdatePrompts(String globalPrompt, String systemPrompt, String normalPrompt) {
        if (!libraryLoaded || chatContextHandle == 0) return false;
        try {
            return nativeChatUpdatePrompts(chatContextHandle, globalPrompt, systemPrompt, normalPrompt);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error updating chat prompts: " + e.getMessage(), e);
            return false;
        }
    }

    public static void chatSend(String message, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback) {
        if (!libraryLoaded) {
            handleValidationError(callback, "Native库未加载，无法发送消息");
            return;
        }

        if (chatContextHandle == 0) {
            handleValidationError(callback, "Chat上下文未初始化");
            return;
        }

        // 防止并发调用native层
        if (hasActiveGeneration) {
            AILogger.w(TAG, "已有活跃的生成任务，拒绝新的chatSend调用");
            handleValidationError(callback, "已有活跃的生成任务");
            return;
        }

        // 检查Native层状态
        if (!isNativeStateValid()) {
            AILogger.e(TAG, "Native状态异常，拒绝chatSend调用");
            handleValidationError(callback, "Native状态异常");
            return;
        }
        
        ValidationResult validation = validateChatSendParams(message, maxTokens, temperature, topP, topK);
        if (!validation.valid) {
            handleValidationError(callback, validation.errorMessage);
            return;
        }
        
        if (validation.warningMessage != null) {
            AILogger.w(TAG, "chatSend warning: " + validation.warningMessage);
        }
        
        String safeMessage = sanitizeMessage(message);
        if (safeMessage == null) {
            handleValidationError(callback, "消息内容格式错误");
            return;
        }

        // 设置活跃生成标志
        hasActiveGeneration = true;
        SafeTokenCallbackWrapper safeCallback = new SafeTokenCallbackWrapper(callback);
        
        try {
            nativeChatSend(chatContextHandle, safeMessage, maxTokens, temperature, topP, topK, enableThinking, safeCallback);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Native链接错误: " + e.getMessage(), e);
            safeCallback.invalidate();
            handleValidationError(callback, "Native层不可用: " + e.getMessage());
            resetNativeState();
        } catch (Exception e) {
            AILogger.e(TAG, "chatSend调用异常: " + e.getMessage(), e);
            safeCallback.invalidate();
            handleValidationError(callback, "调用异常: " + e.getMessage());
            hasActiveGeneration = false;
        }
    }

    public static void chatStop() {
        long handle = chatContextHandle;
        if (!libraryLoaded || handle == 0) return;
        try { nativeChatStop(handle); } catch (UnsatisfiedLinkError e) {}
    }

    public static void chatClear() {
        long handle = chatContextHandle;
        if (!libraryLoaded || handle == 0) return;
        try { nativeChatClear(handle); } catch (UnsatisfiedLinkError e) {}
    }

    // 活跃生成标志管理
    public static void setHasActiveGeneration(boolean active) {
        hasActiveGeneration = active;
    }

    public static boolean hasActiveGeneration() {
        return hasActiveGeneration;
    }

    // 消息内容清理
    private static String sanitizeMessage(String message) {
        if (message == null) return "";
        // 移除null字符等潜在危险字符
        String cleaned = message.replace("\u0000", "");
        // 限制长度
        if (cleaned.length() > 32768) {
            AILogger.w(TAG, "消息过长，截断到32768字符，原长度=" + cleaned.length());
            cleaned = cleaned.substring(0, 32768);
        }
        if (cleaned.isEmpty()) return null;
        return cleaned;
    }

    // 重置Native状态
    private static void resetNativeState() {
        AILogger.w(TAG, "重置native状态");
        hasActiveGeneration = false;
        if (chatContextHandle != 0) {
            chatDestroy();
        }
    }

    // 清理Native层回调引用
    public static void cleanupNativeCallback() {
        if (!libraryLoaded) return;
        try {
            nativeCleanupCallback();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error cleaning up native callback: " + e.getMessage());
        }
    }

    public static synchronized void chatDestroy() {
        if (!libraryLoaded || chatContextHandle == 0) return;
        long handle = chatContextHandle;
        chatContextHandle = 0;
        try {
            nativeChatDestroy(handle);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "chatDestroy failed: " + e.getMessage());
        }
    }

    public static String chatGetInfo() {
        if (!libraryLoaded || chatContextHandle == 0) return "No chat context";
        try { return nativeChatGetInfo(chatContextHandle); } catch (UnsatisfiedLinkError e) { return "Error"; }
    }

    public static boolean isChatContextActive() {
        return chatContextHandle != 0;
    }
    
    public static int getContextSize() {
        if (!libraryLoaded || chatContextHandle == 0) return 0;
        try { 
            int nativeValue = nativeGetContextSize(chatContextHandle);
            return nativeValue > 0 ? nativeValue : contextTotalSize;
        } catch (UnsatisfiedLinkError e) { 
            return contextTotalSize; 
        }
    }
    
    public static int getContextUsedTokens() {
        if (!libraryLoaded || chatContextHandle == 0) return 0;
        try { 
            int nativeValue = nativeGetContextUsedTokens(chatContextHandle);
            return nativeValue > 0 ? nativeValue : contextUsedTokens;
        } catch (UnsatisfiedLinkError e) { 
            return contextUsedTokens; 
        }
    }
    
    public static int getContextRemainingTokens() {
        int total = getContextSize();
        int used = getContextUsedTokens();
        return Math.max(0, total - used);
    }
    
    public static float getContextUsagePercent() {
        int total = getContextSize();
        int used = getContextUsedTokens();
        if (total <= 0) return 0.0f;
        return (float) used / total * 100;
    }

    private static native long nativeChatCreate(String modelPath, int ctxSize, int nThreads, String globalPrompt, String systemPrompt, String normalPrompt);
    private static native void nativeChatSend(long handle, String message, int maxTokens, float temperature, float topP, int topK, boolean enableThinking, TokenCallback callback);
    private static native void nativeChatStop(long handle);
    private static native void nativeChatClear(long handle);
    private static native void nativeChatDestroy(long handle);
    private static native String nativeChatGetInfo(long handle);
    private static native boolean nativeChatUpdatePrompts(long handle, String globalPrompt, String systemPrompt, String normalPrompt);
    private static native int nativeHandleMemoryPressure(int level);
    private static native int nativeGetContextSize(long handle);
    private static native int nativeGetContextUsedTokens(long handle);
    private static native int nativeGetContextRemainingTokens(long handle);
    private static native void nativeCleanupCallback();

    public static int handleMemoryPressure(int level) {
        if (!libraryLoaded) return 0;
        try {
            return nativeHandleMemoryPressure(level);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }
    
    public static boolean isOpenCLLoaded() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot check OpenCL status");
            return false;
        }
        try {
            return nativeIsOpenCLLoaded();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error checking OpenCL loaded: " + e.getMessage(), e);
            return false;
        }
    }
    
    public static boolean isGPUWorking() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot check GPU status");
            return false;
        }
        try {
            return nativeIsGPUWorking();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error checking GPU working: " + e.getMessage(), e);
            return false;
        }
    }
    
    public static String getOpenCLInfo() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot get OpenCL info");
            return "Library not loaded";
        }
        try {
            return nativeGetOpenCLInfo();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error getting OpenCL info: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }
    
    public static String getFullDeviceInfoSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AI 加速信息\n");
        sb.append("================\n");
        sb.append("Native库: ").append(libraryLoaded ? "已加载" : "未加载").append("\n");
        sb.append("模型初始化: ").append(isModelInitialized() ? "是" : "否").append("\n");
        sb.append("\n=== OpenCL/GPU状态\n");
        sb.append("================\n");
        
        if (libraryLoaded) {
            sb.append("OpenCL库: ").append(isOpenCLLoaded() ? "已加载" : "未加载").append("\n");
            sb.append("GPU加速: ").append(isGPUWorking() ? "启用" : "未启用").append("\n");
            sb.append("\n=== 设备信息\n");
            sb.append("================\n");
            try {
                String deviceInfo = getDeviceInfo();
                sb.append(deviceInfo);
            } catch (Exception e) {
                sb.append("获取设备信息失败: ").append(e.getMessage());
            }
        } else {
            sb.append("Native库未加载，无法获取GPU状态\n");
        }
        
        return sb.toString();
    }
    
    private static native boolean nativeIsOpenCLLoaded();
    private static native boolean nativeIsGPUWorking();
    private static native String nativeGetOpenCLInfo();
    private static native String nativeDetectGPUInfo();
    
    public static String detectGPUInfo() {
        if (!libraryLoaded) {
            AILogger.w(TAG, "Library not loaded, cannot detect GPU info");
            return "{}";
        }
        try {
            String info = nativeDetectGPUInfo();
            AILogger.i(TAG, "GPU info detected: " + info);
            return info;
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error detecting GPU info: " + e.getMessage(), e);
            return "{}";
        }
    }
    
    public static class ValidationResult {
        public final boolean valid;
        public final String errorMessage;
        public final String warningMessage;
        
        public ValidationResult(boolean valid, String errorMessage, String warningMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.warningMessage = warningMessage;
        }
        
        public static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }
        
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message, null);
        }
        
        public static ValidationResult warning(String message) {
            return new ValidationResult(true, null, message);
        }
    }
    
    private static ValidationResult validateChatSendParams(String message, int maxTokens, 
            float temperature, float topP, int topK) {
        if (message == null) {
            return ValidationResult.error("消息内容不能为null");
        }
        
        if (message.length() > 32768) {
            return ValidationResult.error("消息内容过长: " + message.length() + "字符，最大支持32768");
        }
        
        if (maxTokens <= 0) {
            return ValidationResult.error("maxTokens必须大于0: " + maxTokens);
        }
        
        if (maxTokens > 32768) {
            return ValidationResult.warning("maxTokens可能过大: " + maxTokens + "，建议不超过4096");
        }
        
        if (temperature < 0.0f || temperature > 2.0f) {
            return ValidationResult.error("temperature必须在0.0-2.0之间: " + temperature);
        }
        
        if (topP < 0.0f || topP > 1.0f) {
            return ValidationResult.error("topP必须在0.0-1.0之间: " + topP);
        }
        
        if (topK < 0 || topK > 1000) {
            return ValidationResult.error("topK必须在0-1000之间: " + topK);
        }
        
        return ValidationResult.ok();
    }
    
    private static ValidationResult validateGenerateParams(String prompt, int maxTokens, 
            float temperature, float topP, int topK) {
        if (prompt == null) {
            return ValidationResult.error("prompt不能为null");
        }
        
        if (prompt.length() > 32768) {
            return ValidationResult.error("prompt过长: " + prompt.length() + "字符，最大支持32768");
        }
        
        if (maxTokens <= 0) {
            return ValidationResult.error("maxTokens必须大于0: " + maxTokens);
        }
        
        if (temperature < 0.0f || temperature > 2.0f) {
            return ValidationResult.error("temperature必须在0.0-2.0之间: " + temperature);
        }
        
        if (topP < 0.0f || topP > 1.0f) {
            return ValidationResult.error("topP必须在0.0-1.0之间: " + topP);
        }
        
        if (topK < 0 || topK > 1000) {
            return ValidationResult.error("topK必须在0-1000之间: " + topK);
        }
        
        return ValidationResult.ok();
    }
    
    private static ValidationResult validateInitModelParams(String modelPath, int nCtx, int nThreads) {
        if (modelPath == null || modelPath.isEmpty()) {
            return ValidationResult.error("模型路径不能为空");
        }
        
        if (nCtx <= 0 || nCtx > 16384) {
            return ValidationResult.error("nCtx必须在1-16384之间: " + nCtx);
        }
        
        if (nThreads <= 0 || nThreads > 32) {
            return ValidationResult.error("nThreads必须在1-32之间: " + nThreads);
        }
        
        return ValidationResult.ok();
    }
    
    private static void handleValidationError(TokenCallback callback, String errorMessage) {
        AILogger.e(TAG, "参数验证失败: " + errorMessage);
        if (callback != null) {
            try {
                callback.onError("参数错误: " + errorMessage);
            } catch (Exception e) {
                AILogger.e(TAG, "Error calling onError callback: " + e.getMessage());
            }
        }
    }

    /**
     * 安全的Token回调包装器，防止回调中异常导致native层崩溃
     */
    private static class SafeTokenCallbackWrapper implements TokenCallback {
        private final TokenCallback wrapped;
        private volatile boolean isActive = true;

        SafeTokenCallbackWrapper(TokenCallback wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public void onToken(String token) {
            if (!isActive || wrapped == null) return;
            try {
                wrapped.onToken(token);
            } catch (Exception e) {
                AILogger.e(TAG, "Token回调异常: " + e.getMessage());
            }
        }

        @Override
        public void onComplete(String fullText) {
            if (!isActive || wrapped == null) return;
            isActive = false;
            hasActiveGeneration = false;
            try {
                wrapped.onComplete(fullText);
            } catch (Exception e) {
                AILogger.e(TAG, "Complete回调异常: " + e.getMessage());
            }
        }

        @Override
        public void onError(String error) {
            if (!isActive || wrapped == null) return;
            isActive = false;
            hasActiveGeneration = false;
            try {
                wrapped.onError(error);
            } catch (Exception e) {
                AILogger.e(TAG, "Error回调异常: " + e.getMessage());
            }
        }

        void invalidate() {
            isActive = false;
        }
    }
}
