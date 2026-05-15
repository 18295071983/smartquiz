package com.oilquiz.app.ai.engine;

import android.util.Log;

public class ALChatTest {
    private static final String TAG = "ALChatTest";
    private ALChat alChat;

    public ALChatTest() {
        alChat = new ALChat();
    }

    public void testGpuBackendSelection() {
        Log.i(TAG, "=== GPU Backend Selection Test ===");

        Log.i(TAG, "Is Snapdragon supported: " + ALChat.isSnapdragonSupported());

        Log.i(TAG, "Available GPU backends:");
        Log.i(TAG, "  CPU: " + ALChat.getGpuBackendName(ALChat.GPU_BACKEND_CPU));
        Log.i(TAG, "  Vulkan: " + ALChat.getGpuBackendName(ALChat.GPU_BACKEND_VULKAN));
        Log.i(TAG, "  OpenCL (Adreno GPU): " + ALChat.getGpuBackendName(ALChat.GPU_BACKEND_OPENCL));
        Log.i(TAG, "  Hexagon (NPU): " + ALChat.getGpuBackendName(ALChat.GPU_BACKEND_HEXAGON));
        Log.i(TAG, "  Auto: " + ALChat.getGpuBackendName(ALChat.GPU_BACKEND_AUTO));
    }

    public void testInitModel() {
        Log.i(TAG, "Initializing model...");

        // 根据设备类型设置GPU后端
        if (ALChat.isSnapdragonSupported()) {
            Log.i(TAG, "Detected Snapdragon device, using OpenCL backend");
            alChat.setGpuBackend(ALChat.GPU_BACKEND_OPENCL);
        } else {
            Log.i(TAG, "Using CPU backend");
            alChat.setGpuBackend(ALChat.GPU_BACKEND_CPU);
        }

        // 这里需要替换为实际的模型路径
        String modelPath = "/storage/emulated/0/Download/llama-2-7b-chat.Q4_K_M.gguf";
        int gpuLayers = 33;

        boolean success = alChat.initModel(modelPath, gpuLayers);
        Log.i(TAG, "Model initialization: " + (success ? "SUCCESS" : "FAILED"));

        if (success) {
            Log.i(TAG, "GPU Backend: " + alChat.getGpuBackend());
            Log.i(TAG, "Device Name: " + alChat.getDeviceName());
        }
    }

    public void testSendMessage() {
        if (!alChat.isInitialized()) {
            Log.e(TAG, "Model not initialized");
            return;
        }

        String message = "Hello, how are you?";
        int maxTokens = 512;
        float temperature = 0.7f;
        float topP = 0.9f;
        int topK = 40;

        Log.i(TAG, "Sending message: " + message);
        long startTime = System.currentTimeMillis();
        String response = alChat.sendMessage(message, maxTokens, temperature, topP, topK);
        long endTime = System.currentTimeMillis();

        Log.i(TAG, "Response received in " + (endTime - startTime) + "ms");
        Log.i(TAG, "Response: " + response);
    }

    public void testSystemPrompt() {
        String systemPrompt = "You are a helpful assistant that answers questions about oil and gas industry.";
        alChat.setSystemPrompt(systemPrompt);
        Log.i(TAG, "System prompt set");
    }

    public void testClearChatHistory() {
        alChat.clearChatHistory();
        Log.i(TAG, "Chat history cleared");
        Log.i(TAG, "Message count: " + alChat.getMessageCount());
    }

    public void testClose() {
        alChat.close();
        Log.i(TAG, "ALChat closed");
    }

    public void runAllTests() {
        testGpuBackendSelection();
        testInitModel();
        testSystemPrompt();
        testSendMessage();
        testSendMessage();
        testClearChatHistory();
        testClose();
    }

    public static void main(String[] args) {
        ALChatTest test = new ALChatTest();
        test.runAllTests();
    }
}
