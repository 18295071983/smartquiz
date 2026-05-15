package com.oilquiz.app.ai.model;

/**
 * ModelConfig - 模型配置参数类
 * 
 * 功能：
 * - 定义模型推理和生成的配置参数
 * - 包含推理参数和生成参数
 * 
 * InferenceParams (推理参数)：
 * - nThreads: CPU线程数
 * - nBatch: 批处理大小
 * - nUbatch: micro批处理大小
 * - nGpuLayers: GPU卸载层数
 * - fKvCacheType: KV缓存类型
 * - useMmap: 是否使用内存映射
 * - useMlock: 是否锁定内存
 * 
 * GenerationParams (生成参数)：
 * - nPredict: 最大预测token数
 * - temperature: 温度参数
 * - topP: Top-p采样参数
 * - topK: Top-k采样参数
 * 
 * @author AI Team
 * @since 2024
 */
public class ModelConfig {
    public static class InferenceParams {
        public int nThreads = 4;
        public int nBatch = 512;
        public int nUbatch = 128;
        public int nGpuLayers = 20;
        public int fKvCacheType = 0;
        public boolean useMmap = true;
        public boolean useMlock = false;
    }
    
    public static class GenerationParams {
        public int nPredict = 512;
        public float temperature = 0.7f;
        public float topP = 0.9f;
        public int topK = 40;
    }
    
    public String modelId;
    public InferenceParams inferenceParams;
    public GenerationParams generationParams;
}
