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
        try {
            return nativeInitModel(modelPath, nCtx, nThreads);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error initializing model: " + e.getMessage(), e);
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
        try {
            return nativeIsModelInitialized();
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error checking if model is initialized: " + e.getMessage(), e);
            return false;
        }
    }

    private static native boolean nativeIsModelInitialized();

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
        if (!libraryLoaded || chatContextHandle == 0) {
            if (callback != null) callback.onError("Chat context not initialized");
            return;
        }
        try {
            nativeChatSend(chatContextHandle, message, maxTokens, temperature, topP, topK, enableThinking, callback);
        } catch (UnsatisfiedLinkError e) {
            AILogger.e(TAG, "Error in chat send: " + e.getMessage(), e);
            if (callback != null) callback.onError("Chat send failed");
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
}
