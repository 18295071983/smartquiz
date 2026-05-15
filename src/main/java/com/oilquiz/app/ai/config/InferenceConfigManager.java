package com.oilquiz.app.ai.config;

import android.content.Context;
import android.util.Log;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.gpu.GpuDatabase;
import com.oilquiz.app.ai.gpu.GpuTier;
import com.oilquiz.app.ai.service.AIService;

import java.io.File;

public class InferenceConfigManager {
    private static final String TAG = "ConfigManager";
    private final Context context;
    private final GpuCapabilityDetector gpuDetector;
    private final GpuDatabase gpuDatabase;
    private final DeviceTier deviceTier;
    private ModelConfig currentConfig;

    public InferenceConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.gpuDetector = new GpuCapabilityDetector(context);
        this.gpuDatabase = GpuDatabase.getInstance();
        this.deviceTier = detectDeviceTier();
        loadOptimalConfig();
    }

    private DeviceTier detectDeviceTier() {
        long totalMemMB = gpuDetector.getMemoryUsageInfo().totalMemoryMB;
        int cpuCores = Runtime.getRuntime().availableProcessors();

        if (totalMemMB >= 12288 && cpuCores >= 8) {
            return DeviceTier.FLAGSHIP;
        } else if (totalMemMB >= 8192 && cpuCores >= 8) {
            return DeviceTier.HIGH_END;
        } else if (totalMemMB >= 6144) {
            return DeviceTier.MID_RANGE;
        } else {
            return DeviceTier.BUDGET;
        }
    }

    private void loadOptimalConfig() {
        String currentModelName = AIService.getInstance(context).getCurrentModelName();

        currentConfig = new ModelConfig();

        if (currentModelName != null) {
            currentConfig.modelId = currentModelName;
            currentConfig.modelFilename = currentModelName;
            currentConfig.modelSizeMB = getModelSizeMB(currentModelName);
            currentConfig.quantization = extractQuantizationFromModelName(currentModelName);
        } else {
            currentConfig.modelId = "default-model";
            currentConfig.modelFilename = "model.gguf";
            currentConfig.modelSizeMB = 2000;
            currentConfig.quantization = "Q4_K_M";
        }

        setDeviceSpecificParams();

        Log.d(TAG, "Loaded config for tier: " + deviceTier.name() + ", model: " + currentConfig.modelId);
    }

    private int getModelSizeMB(String modelName) {
        File modelDir = new File(context.getFilesDir(), "ai_models");
        File modelFile = new File(modelDir, modelName);
        if (modelFile.exists()) {
            long sizeBytes = modelFile.length();
            return (int) (sizeBytes / (1024 * 1024));
        }
        return 2000;
    }

    private String extractQuantizationFromModelName(String modelName) {
        if (modelName.contains("q2")) return "Q2_K";
        if (modelName.contains("q3")) return "Q3_K_M";
        if (modelName.contains("q4")) return "Q4_K_M";
        if (modelName.contains("q5")) return "Q5_K_M";
        if (modelName.contains("q6")) return "Q6_K";
        if (modelName.contains("q8")) return "Q8_0";
        return "Unknown";
    }

    private void setDeviceSpecificParams() {
        currentConfig.inferenceParams = new InferenceParams();
        currentConfig.generationParams = new GenerationParams();

        var gpuProfile = gpuDetector.getGpuProfile();
        var gpuTier = gpuDatabase.getTier(gpuProfile != null ? gpuProfile.getGpuName() : "");

        if (gpuProfile != null) {
            var gpuConfig = gpuDatabase.getConfigForModel(gpuProfile, currentConfig.modelSizeMB);

            currentConfig.inferenceParams.nGpuLayers = gpuConfig.getGpuLayers();
            currentConfig.inferenceParams.nBatch = gpuConfig.getBatchSize();
            currentConfig.inferenceParams.useFp16 = gpuConfig.getUseFp16();
            currentConfig.inferenceParams.useVulkan = gpuConfig.getUseVulkan();
        }

        switch (deviceTier) {
            case FLAGSHIP:
                currentConfig.contextSize = 8192;
                currentConfig.inferenceParams.nThreads = 6;
                if (currentConfig.inferenceParams.nBatch == 0) {
                    currentConfig.inferenceParams.nBatch = 1024;
                }
                currentConfig.inferenceParams.nUbatch = 256;
                if (currentConfig.inferenceParams.nGpuLayers == 0) {
                    currentConfig.inferenceParams.nGpuLayers = 20;
                }
                currentConfig.inferenceParams.fKvCacheType = 0;
                currentConfig.generationParams.nPredict = 1024;
                currentConfig.generationParams.temperature = 0.8f;
                currentConfig.generationParams.topP = 0.9f;
                currentConfig.generationParams.topK = 40;
                currentConfig.generationParams.minP = 0.0f;
                currentConfig.generationParams.repeatPenalty = 1.15f;
                break;
            case HIGH_END:
                currentConfig.contextSize = 4096;
                currentConfig.inferenceParams.nThreads = 6;
                if (currentConfig.inferenceParams.nBatch == 0) {
                    currentConfig.inferenceParams.nBatch = 512;
                }
                currentConfig.inferenceParams.nUbatch = 256;
                if (currentConfig.inferenceParams.nGpuLayers == 0) {
                    currentConfig.inferenceParams.nGpuLayers = 15;
                }
                currentConfig.inferenceParams.fKvCacheType = 1;
                currentConfig.generationParams.nPredict = 512;
                currentConfig.generationParams.temperature = 0.7f;
                currentConfig.generationParams.topP = 0.9f;
                currentConfig.generationParams.topK = 30;
                currentConfig.generationParams.minP = 0.05f;
                currentConfig.generationParams.repeatPenalty = 1.1f;
                break;
            case MID_RANGE:
                currentConfig.contextSize = 4096;
                currentConfig.inferenceParams.nThreads = 4;
                if (currentConfig.inferenceParams.nBatch == 0) {
                    currentConfig.inferenceParams.nBatch = 512;
                }
                currentConfig.inferenceParams.nUbatch = 128;
                if (currentConfig.inferenceParams.nGpuLayers == 0) {
                    currentConfig.inferenceParams.nGpuLayers = 10;
                }
                currentConfig.inferenceParams.fKvCacheType = 1;
                currentConfig.generationParams.nPredict = 512;
                currentConfig.generationParams.temperature = 0.7f;
                currentConfig.generationParams.topP = 0.85f;
                currentConfig.generationParams.topK = 30;
                currentConfig.generationParams.minP = 0.05f;
                currentConfig.generationParams.repeatPenalty = 1.1f;
                break;
            case BUDGET:
            default:
                currentConfig.contextSize = 2048;
                currentConfig.inferenceParams.nThreads = 2;
                if (currentConfig.inferenceParams.nBatch == 0) {
                    currentConfig.inferenceParams.nBatch = 256;
                }
                currentConfig.inferenceParams.nUbatch = 128;
                currentConfig.inferenceParams.nGpuLayers = 0;
                currentConfig.inferenceParams.fKvCacheType = 2;
                currentConfig.generationParams.nPredict = 256;
                currentConfig.generationParams.temperature = 0.6f;
                currentConfig.generationParams.topP = 0.8f;
                currentConfig.generationParams.topK = 20;
                currentConfig.generationParams.minP = 0.1f;
                currentConfig.generationParams.repeatPenalty = 1.1f;
                break;
        }

        currentConfig.inferenceParams.useMmap = true;
        currentConfig.inferenceParams.useMlock = false;
    }

    public ModelConfig getCurrentConfig() {
        return currentConfig;
    }

    public DeviceTier getDeviceTier() {
        return deviceTier;
    }

    public void adjustForThermal(boolean isThrottling) {
        if (isThrottling) {
            InferenceParams params = currentConfig.inferenceParams;
            params.nThreads = Math.max(1, params.nThreads / 2);
            params.nBatch = Math.max(128, params.nBatch / 2);
            currentConfig.generationParams.nPredict = Math.max(128, currentConfig.generationParams.nPredict / 2);
            Log.d(TAG, "Adjusted params for thermal throttling");
        }
    }

    public void adjustForMemory(long availableMemMB) {
        long requiredMem = currentConfig.modelSizeMB + 1024;

        if (availableMemMB < requiredMem) {
            Log.w(TAG, "Low memory: available=" + availableMemMB + ", required=" + requiredMem);
            int newContext = Math.max(1024, currentConfig.contextSize / 2);
            currentConfig.contextSize = newContext;
            Log.d(TAG, "Adjusted context size to: " + newContext);
        }
    }

    public void adjustForUserPreference(String preference) {
        GenerationParams params = currentConfig.generationParams;

        switch (preference) {
            case "creative":
                params.temperature = 0.9f;
                params.topP = 0.95f;
                params.topK = 50;
                break;
            case "balanced":
                params.temperature = 0.7f;
                params.topP = 0.9f;
                params.topK = 40;
                break;
            case "precise":
                params.temperature = 0.5f;
                params.topP = 0.8f;
                params.topK = 20;
                break;
        }
    }

    public GpuCapabilityDetector getGpuDetector() {
        return gpuDetector;
    }

    public GpuDatabase getGpuDatabase() {
        return gpuDatabase;
    }

    public enum DeviceTier {
        FLAGSHIP,
        HIGH_END,
        MID_RANGE,
        BUDGET
    }

    public static class ModelConfig {
        public String modelId;
        public String modelFilename;
        public int modelSizeMB;
        public String quantization;
        public int contextSize;
        public InferenceParams inferenceParams;
        public GenerationParams generationParams;
    }

    public static class InferenceParams {
        public int nThreads = 4;
        public int nBatch = 512;
        public int nUbatch = 128;
        public int nGpuLayers = 0;
        public int fKvCacheType = 0;
        public boolean useMmap = true;
        public boolean useMlock = false;
        public boolean useFp16 = false;
        public boolean useVulkan = false;
    }

    public static class GenerationParams {
        public int nPredict = 512;
        public float temperature = 0.7f;
        public float topP = 0.9f;
        public int topK = 40;
        public float minP = 0.05f;
        public float repeatPenalty = 1.1f;
    }
}