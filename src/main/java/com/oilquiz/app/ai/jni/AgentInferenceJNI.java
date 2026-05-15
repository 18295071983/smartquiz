package com.oilquiz.app.ai.jni;

public class AgentInferenceJNI {
    private volatile long nativeHandle = 0;

    static {
        try {
            System.loadLibrary("OpenCL");
        } catch (UnsatisfiedLinkError e) {
        }
        System.loadLibrary("llama-jni");
    }

    public static native String nativeGetVersion();

    public boolean init(String modelPath, int contextSize, int nThreads) {
        nativeHandle = nativeInit(modelPath, contextSize, nThreads);
        return nativeHandle != 0;
    }

    public boolean initWithGpuLayers(String modelPath, int contextSize, int nThreads, int gpuLayers) {
        nativeHandle = nativeInitWithGpuLayers(modelPath, contextSize, nThreads, gpuLayers);
        return nativeHandle != 0;
    }

    public void setGpuLayers(int gpuLayers) {
        if (nativeHandle != 0) {
            nativeSetGpuLayers(nativeHandle, gpuLayers);
        }
    }

    public int getGpuLayers() {
        if (nativeHandle != 0) {
            return nativeGetGpuLayers(nativeHandle);
        }
        return 0;
    }

    public String generate(String prompt, int maxTokens, float temperature,
                           float topP, int topK, String mode,
                           String[] toolNames, String[] toolDescs) {
        if (nativeHandle == 0) return "Error: Not initialized";
        return nativeGenerate(nativeHandle, prompt, maxTokens, temperature,
                              topP, topK, mode, toolNames, toolDescs);
    }

    public String generateAgent(String userInput, int maxTokens, float temperature,
                                float topP, int topK, String mode, int maxLoops,
                                String[] toolNames, String[] toolDescs) {
        if (nativeHandle == 0) return "Error: Not initialized";
        return nativeGenerateAgent(nativeHandle, userInput, maxTokens, temperature,
                                   topP, topK, mode, maxLoops, toolNames, toolDescs);
    }

    public void cancel() {
        if (nativeHandle != 0) {
            nativeCancel(nativeHandle);
        }
    }

    public void reset() {
        if (nativeHandle != 0) {
            nativeReset(nativeHandle);
        }
    }

    public void release() {
        if (nativeHandle != 0) {
            nativeRelease(nativeHandle);
            nativeHandle = 0;
        }
    }

    public int getTokensUsed() {
        if (nativeHandle != 0) {
            return nativeGetTokensUsed(nativeHandle);
        }
        return 0;
    }

    public boolean isInitialized() {
        return nativeHandle != 0;
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    private native long nativeInit(String modelPath, int contextSize, int nThreads);
    private native long nativeInitWithGpuLayers(String modelPath, int contextSize, int nThreads, int gpuLayers);
    private native void nativeSetGpuLayers(long handle, int gpuLayers);
    private native int nativeGetGpuLayers(long handle);
    private native String nativeGenerate(long handle, String prompt, int maxTokens,
                                         float temperature, float topP, int topK,
                                         String mode, String[] toolNames, String[] toolDescs);
    private native String nativeGenerateAgent(long handle, String userInput, int maxTokens,
                                              float temperature, float topP, int topK,
                                              String mode, int maxLoops,
                                              String[] toolNames, String[] toolDescs);
    private native void nativeCancel(long handle);
    private native void nativeReset(long handle);
    private native void nativeRelease(long handle);
    private native int nativeGetTokensUsed(long handle);
}
