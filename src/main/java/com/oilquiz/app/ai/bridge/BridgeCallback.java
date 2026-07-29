package com.oilquiz.app.ai.bridge;

/**
 * 模型执行回调 - UI通过此接口接收模型执行结果
 *
 * 所有回调在主线程执行，UI无需额外处理线程问题
 */
public interface BridgeCallback {

    // ========== 生成回调 ==========

    /** 模型开始生成 */
    void onGenerationStarted(String messageId);

    /** 收到一个token */
    void onToken(String messageId, String token);

    /** 生成完成 */
    void onGenerationComplete(String messageId, String fullContent, int tokenCount,
                                long elapsedMs, float tokensPerSecond);

    /** 生成失败 */
    void onGenerationError(String messageId, String error);

    /** 生成被停止 */
    void onGenerationStopped(String messageId);

    // ========== 推理进度 ==========

    /** 推理进度更新 */
    void onInferenceProgress(String messageId, int tokenCount, float tokensPerSecond);

    // ========== 上下文管理 ==========

    /** 上下文已清除 */
    void onContextCleared();

    /** 上下文初始化结果 */
    void onContextInitialized(boolean success);

    // ========== 模型生命周期 ==========

    /** 模型初始化结果 */
    void onModelInitialized(boolean success, String modelName);

    /** 模型重新加载结果 */
    void onModelReloaded(boolean success);

    // ========== 状态查询 ==========

    /** 模型信息 */
    void onModelInfo(String modelName, boolean isInitialized, boolean usingGPU, int gpuLayers);

    /** token计数结果 */
    void onTokenCount(int count);

    /** native状态检查结果 */
    void onNativeStateChecked(boolean isValid);

    /** 内存压力处理结果 */
    void onMemoryPressureHandled(int result);

    // ========== Agent工具调用 ==========

    /** Agent工具调用开始 */
    void onToolCallStart(String messageId, String toolName, String args);

    /** Agent工具调用完成 */
    void onToolCallComplete(String messageId, String toolName, boolean success, String result);

    /** Agent思考更新 */
    void onThinkingUpdate(String messageId, int stepNumber, String stepType,
                          String title, String content, int progress);
}