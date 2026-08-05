package com.oilquiz.app.ai.engine;

import android.util.Log;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.util.PromptBuilder;

import java.util.ArrayList;
import java.util.List;

public class ALChat {
    private static final String TAG = "ALChat";
    private boolean isStreamingEnabled = false;
    
    public static final int GPU_BACKEND_CPU = 0;
    public static final int GPU_BACKEND_VULKAN = 1;
    public static final int GPU_BACKEND_OPENCL = 2;
    public static final int GPU_BACKEND_HEXAGON = 3;
    public static final int GPU_BACKEND_AUTO = 4;

    public boolean initModel(String modelPath, int nGpuLayers) {
        LlamaHelper.setGPULayers(nGpuLayers);
        int result = LlamaHelper.initModel(modelPath, 2048, 4);
        return result == 0;
    }

    public boolean initModelWithConfig(String modelPath, int nGpuLayers, int nThreads, int nCtx, int nBatch) {
        LlamaHelper.setGPULayers(nGpuLayers);
        LlamaHelper.setThreadCount(nThreads);
        LlamaHelper.setBatchSize(nBatch);
        int result = LlamaHelper.initModel(modelPath, nCtx, nThreads);
        return result == 0;
    }

    public String sendMessage(String message, int maxTokens, float temperature, float topP, int topK) {
        // 构建消息列表，由 native 层 llama_chat_apply_template 自动适配模型格式
        // 避免硬编码 ChatML 格式导致非 ChatML 模型格式不匹配
        List<PromptBuilder.Message> messages = new ArrayList<>();
        messages.add(new PromptBuilder.Message("user", message));
        return LlamaHelper.generate(messages, maxTokens, temperature, topP, topK);
    }

    public void close() {
        LlamaHelper.release();
    }

    public int getGpuLayers() {
        return LlamaHelper.getGPULayers();
    }

    public int getThreadCount() {
        return LlamaHelper.getThreadCount();
    }

    public int getContextSize() {
        return LlamaHelper.getContextSize();
    }

    public int getBatchSize() {
        return LlamaHelper.getBatchSize();
    }

    public int getGpuBackend() {
        return GPU_BACKEND_OPENCL;
    }

    public String getDeviceName() {
        return LlamaHelper.getDeviceInfo();
    }

    public void setGpuLayers(int layers) {
        LlamaHelper.setGPULayers(layers);
    }

    public void setThreadCount(int threads) {
        LlamaHelper.setThreadCount(threads);
    }

    public void setContextSize(int size) {
    }

    public void setBatchSize(int size) {
        LlamaHelper.setBatchSize(size);
    }

    public void setGpuBackend(int backend) {
    }

    public boolean isInitialized() {
        return LlamaHelper.isModelInitialized();
    }

    public void setSystemPrompt(String prompt) {
    }

    public void clearChatHistory() {
        LlamaHelper.clearHistory();
    }

    public int getMessageCount() {
        return 0;
    }

    public void setStreaming(boolean streaming) {
        this.isStreamingEnabled = streaming;
    }

    public boolean isStreaming() {
        return this.isStreamingEnabled;
    }

    public static String getGpuBackendName(int backend) {
        switch (backend) {
            case GPU_BACKEND_CPU:
                return "CPU";
            case GPU_BACKEND_VULKAN:
                return "Vulkan";
            case GPU_BACKEND_OPENCL:
                return "OpenCL (Adreno GPU)";
            case GPU_BACKEND_HEXAGON:
                return "Hexagon (NPU)";
            case GPU_BACKEND_AUTO:
                return "Auto-detect";
            default:
                return "Unknown";
        }
    }

    public static boolean isSnapdragonSupported() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String hardware = android.os.Build.HARDWARE;
        return manufacturer.equalsIgnoreCase("qualcomm") ||
               hardware.toLowerCase().contains("qcom") ||
               hardware.toLowerCase().contains("snapdragon");
    }
}