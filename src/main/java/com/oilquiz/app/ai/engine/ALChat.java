package com.oilquiz.app.ai.engine;

public class ALChat {
    static {
        try {
            System.loadLibrary("OpenCL");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.w("ALChat", "libOpenCL.so not found, GPU acceleration may not be available");
        }
        System.loadLibrary("llama-jni");
    }

    public static final int GPU_BACKEND_CPU = 0;
    public static final int GPU_BACKEND_VULKAN = 1;
    public static final int GPU_BACKEND_OPENCL = 2;
    public static final int GPU_BACKEND_HEXAGON = 3;
    public static final int GPU_BACKEND_AUTO = 4;

    /**
     * 初始化模型
     * @param modelPath 模型文件路径
     * @param nGpuLayers GPU 层数
     * @return 是否初始化成功
     */
    public native boolean initModel(String modelPath, int nGpuLayers);

    /**
     * 使用配置初始化模型
     * @param modelPath 模型文件路径
     * @param nGpuLayers GPU 层数
     * @param nThreads 线程数
     * @param nCtx 上下文大小
     * @param nBatch 批处理大小
     * @return 是否初始化成功
     */
    public native boolean initModelWithConfig(String modelPath, int nGpuLayers, int nThreads, int nCtx, int nBatch);

    /**
     * 发送消息并获取回复
     * @param message 用户消息
     * @param maxTokens 最大生成 tokens
     * @param temperature 温度参数
     * @param topP top-p 参数
     * @param topK top-k 参数
     * @return AI 回复
     */
    public native String sendMessage(String message, int maxTokens, float temperature, float topP, int topK);

    /**
     * 关闭模型
     */
    public native void close();

    /**
     * 获取 GPU 层数
     * @return GPU 层数
     */
    public native int getGpuLayers();

    /**
     * 获取线程数
     * @return 线程数
     */
    public native int getThreadCount();

    /**
     * 获取上下文大小
     * @return 上下文大小
     */
    public native int getContextSize();

    /**
     * 获取批处理大小
     * @return 批处理大小
     */
    public native int getBatchSize();

    /**
     * 获取 GPU 后端类型
     * @return GPU 后端类型 (0=CPU, 1=Vulkan, 2=OpenCL, 3=Hexagon, 4=Auto)
     */
    public native int getGpuBackend();

    /**
     * 获取设备名称
     * @return 设备名称
     */
    public native String getDeviceName();

    /**
     * 设置 GPU 层数
     * @param layers GPU 层数
     */
    public native void setGpuLayers(int layers);

    /**
     * 设置线程数
     * @param threads 线程数
     */
    public native void setThreadCount(int threads);

    /**
     * 设置上下文大小
     * @param size 上下文大小
     */
    public native void setContextSize(int size);

    /**
     * 设置批处理大小
     * @param size 批处理大小
     */
    public native void setBatchSize(int size);

    /**
     * 设置 GPU 后端类型
     * @param backend GPU 后端类型 (0=CPU, 1=Vulkan, 2=OpenCL, 3=Hexagon, 4=Auto)
     */
    public native void setGpuBackend(int backend);

    /**
     * 检查模型是否初始化
     * @return 是否初始化
     */
    public native boolean isInitialized();

    /**
     * 设置系统提示词
     * @param prompt 系统提示词
     */
    public native void setSystemPrompt(String prompt);

    /**
     * 清空聊天历史
     */
    public native void clearChatHistory();

    /**
     * 获取消息数量
     * @return 消息数量
     */
    public native int getMessageCount();

    /**
     * 设置是否流式输出
     * @param streaming 是否流式输出
     */
    public native void setStreaming(boolean streaming);

    /**
     * 检查是否流式输出
     * @return 是否流式输出
     */
    public native boolean isStreaming();

    /**
     * 获取 GPU 后端描述
     * @param backend GPU 后端类型
     * @return GPU 后端描述
     */
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

    /**
     * 检查是否支持 Qualcomm 设备
     * @return 是否支持
     */
    public static boolean isSnapdragonSupported() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String hardware = android.os.Build.HARDWARE;
        return manufacturer.equalsIgnoreCase("qualcomm") ||
               hardware.toLowerCase().contains("qcom") ||
               hardware.toLowerCase().contains("snapdragon");
    }
}
