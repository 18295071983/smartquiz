package com.oilquiz.app.ai.callback;

/**
 * 流式生成回调接口
 * 用于接收AI流式生成的实时输出
 */
public abstract class StreamCallback {
    
    /**
     * 流式生成开始
     */
    public void onStart() {}
    
    /**
     * 接收到新的token
     * @param token 生成的文本片段
     */
    public void onToken(String token) {}
    
    /**
     * 流式生成完成
     * @param fullText 完整的生成文本
     */
    public void onComplete(String fullText) {}
    
    /**
     * 生成过程中发生错误
     * @param error 错误信息
     */
    public void onError(String error) {}
    
    /**
     * Token 统计回调 - API 响应后调用
     * @param promptTokens 输入 token 数量
     * @param completionTokens 输出 token 数量
     */
    public void onTokenStats(int promptTokens, int completionTokens) {}
    
    /**
     * 进度回调
     * @param progress 进度百分比 0-100
     */
    public void onProgress(int progress) {}
}
