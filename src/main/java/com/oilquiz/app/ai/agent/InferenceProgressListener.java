package com.oilquiz.app.ai.agent;

/**
 * 推理进度监听器接口（独立于具体引擎实现）。
 * 用于更新 UI 上的推理速度（token 数、TPS）显示。
 */
public interface InferenceProgressListener {
    /**
     * 推理进度更新
     * @param tokenCount 已处理的 token 数量
     * @param tokensPerSecond 每秒处理 token 数
     */
    void onProgressUpdate(int tokenCount, float tokensPerSecond);
}
