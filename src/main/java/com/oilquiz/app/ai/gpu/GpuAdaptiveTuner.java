package com.oilquiz.app.ai.gpu;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GpuAdaptiveTuner {
    private static final String TAG = "GpuAdaptiveTuner";
    private static final long TUNING_INTERVAL_MS = 60000L;
    private static final float MIN_TPS_IMPROVEMENT = 0.05f;

    private final Context context;
    private final GpuCapabilityDetector gpuDetector;
    private final MemoryMonitor memoryMonitor;
    private final GpuDatabase gpuDatabase;

    private final AtomicBoolean isTuning = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private GpuConfig currentConfig;
    private final List<PerformanceSnapshot> performanceHistory = new ArrayList<>();

    private int gpuTemperature = 0;
    private float gpuUtilization = 0f;
    private int[] gpuFrequency = new int[] {0, 0};

    private TuningListener listener;
    private ExecutorService tuningExecutor;

    public GpuAdaptiveTuner(Context context) {
        this.context = context.getApplicationContext();
        this.gpuDetector = new GpuCapabilityDetector(context);
        this.memoryMonitor = new MemoryMonitor(context);
        this.gpuDatabase = GpuDatabase.getInstance();
        this.currentConfig = GpuConfig.cpuOnly();
    }

    public void setListener(TuningListener listener) {
        this.listener = listener;
    }

    public synchronized void startTuning(GpuConfig initialConfig) {
        if (tuningExecutor != null && !tuningExecutor.isShutdown()) {
            return;
        }

        if (isTuning.get()) {
            Log.w(TAG, "Tuning already running");
            return;
        }

        GpuProfile gpuProfile = gpuDetector.getGpuProfile();
        GpuTier gpuTier = gpuDetector.getGpuTier();

        currentConfig = initialConfig;
        isTuning.set(true);
        isRunning.set(true);

        tuningExecutor = Executors.newSingleThreadExecutor();
        tuningExecutor.execute(this::tuningLoop);

        Log.i(TAG, "GPU adaptive tuning started with config: " + currentConfig);
    }

    public void stopTuning() {
        isTuning.set(false);
        isRunning.set(false);
        if (tuningExecutor != null) {
            tuningExecutor.shutdown();
        }
        Log.i(TAG, "GPU adaptive tuning stopped");
    }

    private void tuningLoop() {
        while (isTuning.get()) {
            try {
                collectMetrics();
                analyzeAndTune();
                Thread.sleep(TUNING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Error in tuning loop", e);
            }
        }
    }

    private void collectMetrics() {
        try {
            gpuTemperature = gpuDetector.getTemperature();
            gpuUtilization = gpuDetector.getGpuUtilization();
            gpuFrequency = gpuDetector.getGpuFrequency();
        } catch (Exception e) {
            Log.w(TAG, "Failed to collect GPU metrics", e);
        }
    }

    private void analyzeAndTune() {
        if (!isTuning.get()) return;

        PerformanceSnapshot snapshot = createSnapshot();
        performanceHistory.add(snapshot);

        if (performanceHistory.size() > 10) {
            performanceHistory.remove(0);
        }

        TuningRecommendation recommendation = analyzePerformance(snapshot);

        if (recommendation != null && recommendation.newConfig != null) {
            currentConfig = recommendation.newConfig;
        }

        if (listener != null) {
            TuningState state = new TuningState(
                isRunning.get(),
                currentConfig,
                gpuDatabase.getTier(gpuDetector.getGpuProfile() != null ? gpuDetector.getGpuProfile().getGpuName() : ""),
                gpuUtilization,
                gpuTemperature,
                snapshot.tps,
                recommendation
            );
            listener.onTuningStateChanged(state);
        }
    }

    private PerformanceSnapshot createSnapshot() {
        return new PerformanceSnapshot(
            System.currentTimeMillis(),
            currentConfig,
            measureCurrentTps(),
            gpuUtilization,
            gpuTemperature
        );
    }

    private float measureCurrentTps() {
        try {
            GpuProfile gpuProfile = gpuDetector.getGpuProfile();
            if (gpuProfile != null) {
                return gpuProfile.peakPerformanceTFLOPS / 10;
            }
        } catch (Exception e) {
        }
        return 5f;
    }

    private TuningRecommendation analyzePerformance(PerformanceSnapshot snapshot) {
        if (snapshot.gpuUtilization < 0.3f) {
            if (currentConfig.gpuLayers < 30 && currentConfig.gpuLayers > 0) {
                return new TuningRecommendation(
                    TuningAction.INCREASE_LAYERS,
                    String.format("GPU utilization low (%.0f%%), consider increasing GPU layers", snapshot.gpuUtilization * 100),
                    new GpuConfig(
                        currentConfig.gpuLayers + 5,
                        currentConfig.batchSize,
                        currentConfig.kvCacheOnGpu,
                        currentConfig.useFp16,
                        currentConfig.useVulkan,
                        currentConfig.enableTensorCores,
                        currentConfig.enableMemoryPrefetch,
                        currentConfig.enableAsyncCompute,
                        currentConfig.maxComputeWorkGroupSize,
                        currentConfig.preferredSharedMemorySize,
                        currentConfig.conservativeSync,
                        currentConfig.tileBasedRendering
                    ),
                    0.15f
                );
            }
        }

        if (snapshot.gpuUtilization > 0.95f) {
            if (currentConfig.gpuLayers > 10) {
                return new TuningRecommendation(
                    TuningAction.DECREASE_LAYERS,
                    String.format("GPU utilization very high (%.0f%%), risk of throttling", snapshot.gpuUtilization * 100),
                    new GpuConfig(
                        currentConfig.gpuLayers - 5,
                        currentConfig.batchSize,
                        currentConfig.kvCacheOnGpu,
                        currentConfig.useFp16,
                        currentConfig.useVulkan,
                        currentConfig.enableTensorCores,
                        currentConfig.enableMemoryPrefetch,
                        currentConfig.enableAsyncCompute,
                        currentConfig.maxComputeWorkGroupSize,
                        currentConfig.preferredSharedMemorySize,
                        currentConfig.conservativeSync,
                        currentConfig.tileBasedRendering
                    ),
                    0.05f
                );
            }
        }

        if (snapshot.temperature > 45) {
            if (currentConfig.gpuLayers > 5) {
                return new TuningRecommendation(
                    TuningAction.DECREASE_LAYERS,
                    String.format("GPU temperature high (%dC), reducing heat", snapshot.temperature),
                    new GpuConfig(
                        currentConfig.gpuLayers - 3,
                        currentConfig.batchSize,
                        currentConfig.kvCacheOnGpu,
                        currentConfig.useFp16,
                        currentConfig.useVulkan,
                        currentConfig.enableTensorCores,
                        currentConfig.enableMemoryPrefetch,
                        currentConfig.enableAsyncCompute,
                        currentConfig.maxComputeWorkGroupSize,
                        currentConfig.preferredSharedMemorySize,
                        currentConfig.conservativeSync,
                        currentConfig.tileBasedRendering
                    ),
                    0.02f
                );
            }
        }

        return new TuningRecommendation(
            TuningAction.NO_CHANGE,
            "Performance is optimal",
            null,
            0f
        );
    }

    public GpuConfig getOptimalConfigForModel(long modelSizeMB) {
        GpuProfile gpuProfile = gpuDetector.getGpuProfile();
        if (gpuProfile != null) {
            return gpuDatabase.getConfigForModel(gpuProfile, modelSizeMB);
        }
        return GpuConfig.conservative();
    }

    public GpuConfig autoConfigure() {
        GpuInfo gpuInfo = gpuDetector.detectGpuInfo();
        GpuProfile gpuProfile = gpuDatabase.getProfile(gpuInfo != null ? gpuInfo.getRenderer() : "");

        if (gpuProfile != null) {
            MemoryUsageInfo memoryInfo = memoryMonitor.getMemoryUsageInfo();
            long availableMemoryMB = memoryInfo.availableMemoryMB;

            long modelSizeMB = 4200;
            if (availableMemoryMB < 4096) {
                modelSizeMB = 2000;
            } else if (availableMemoryMB < 6144) {
                modelSizeMB = 3000;
            }

            GpuConfig config = gpuDatabase.getConfigForModel(gpuProfile, modelSizeMB);

            if (memoryInfo.isLowMemory()) {
                config = new GpuConfig(
                    (int)(config.gpuLayers * 0.5),
                    config.batchSize,
                    false,
                    config.useFp16,
                    config.useVulkan,
                    config.enableTensorCores,
                    config.enableMemoryPrefetch,
                    config.enableAsyncCompute,
                    config.maxComputeWorkGroupSize,
                    config.preferredSharedMemorySize,
                    config.conservativeSync,
                    config.tileBasedRendering
                );
            }

            currentConfig = config;
            Log.i(TAG, "Auto-configured GPU: " + gpuInfo.getRenderer() + ", config: " + config);
            return config;
        }

        return GpuConfig.conservative();
    }

    public ValidationResult validateConfig(GpuConfig config) {
        List<String> issues = new ArrayList<>();

        MemoryUsageInfo memoryInfo = memoryMonitor.getMemoryUsageInfo();
        if (memoryInfo.availableMemoryMB < 2048 && config.gpuLayers > 0) {
            issues.add("Low memory, reducing GPU layers");
        }

        if (gpuTemperature > 50 && config.gpuLayers > 15) {
            issues.add("High temperature, consider reducing GPU layers");
        }

        GpuProfile gpuProfile = gpuDetector.getGpuProfile();
        if (gpuProfile != null) {
            if (config.gpuLayers > 35) {
                issues.add("Excessive GPU layers for " + gpuProfile.getGpuName());
            }

            if (!gpuProfile.isSupportsFP16() && config.useFp16) {
                issues.add("GPU does not support FP16");
            }
        }

        GpuConfig adjustedConfig = issues.isEmpty() ? null : adjustConfig(config);

        return new ValidationResult(issues.isEmpty(), issues, adjustedConfig);
    }

    private GpuConfig adjustConfig(GpuConfig config) {
        return new GpuConfig(
            Math.min(config.gpuLayers, 20),
            config.batchSize,
            config.kvCacheOnGpu,
            gpuDetector.detectGpuInfo() != null && gpuDetector.detectGpuInfo().isSupportsFP16(),
            config.useVulkan,
            config.enableTensorCores,
            config.enableMemoryPrefetch,
            config.enableAsyncCompute,
            config.maxComputeWorkGroupSize,
            config.preferredSharedMemorySize,
            config.conservativeSync,
            config.tileBasedRendering
        );
    }

    public PerformanceReport getPerformanceReport() {
        List<PerformanceSnapshot> recentHistory = performanceHistory.size() > 5
            ? performanceHistory.subList(performanceHistory.size() - 5, performanceHistory.size())
            : performanceHistory;

        float avgTps = 0f;
        float avgGpuUtil = 0f;

        if (!recentHistory.isEmpty()) {
            float sumTps = 0f;
            float sumGpuUtil = 0f;
            for (PerformanceSnapshot s : recentHistory) {
                sumTps += s.tps;
                sumGpuUtil += s.gpuUtilization;
            }
            avgTps = sumTps / recentHistory.size();
            avgGpuUtil = sumGpuUtil / recentHistory.size();
        }

        GpuProfile gpuProfile = gpuDetector.getGpuProfile();
        GpuTier gpuTier = gpuDatabase.getTier(gpuProfile != null ? gpuProfile.getGpuName() : "");

        return new PerformanceReport(
            gpuProfile != null ? gpuProfile.getGpuName() : "Unknown",
            gpuTier,
            currentConfig,
            avgTps,
            avgGpuUtil,
            gpuTemperature,
            performanceHistory.size(),
            null
        );
    }

    public void cleanup() {
        stopTuning();
    }

    public interface TuningListener {
        void onTuningStateChanged(TuningState state);
    }

    public static class TuningState {
        public final boolean isRunning;
        public final GpuConfig currentConfig;
        public final GpuTier gpuTier;
        public final float gpuUtilization;
        public final int gpuTemperature;
        public final float lastTps;
        public final TuningRecommendation recommendation;

        public TuningState(boolean isRunning, GpuConfig currentConfig, GpuTier gpuTier,
                          float gpuUtilization, int gpuTemperature, float lastTps,
                          TuningRecommendation recommendation) {
            this.isRunning = isRunning;
            this.currentConfig = currentConfig;
            this.gpuTier = gpuTier;
            this.gpuUtilization = gpuUtilization;
            this.gpuTemperature = gpuTemperature;
            this.lastTps = lastTps;
            this.recommendation = recommendation;
        }
    }

    public static class PerformanceSnapshot {
        public final long timestamp;
        public final GpuConfig config;
        public final float tps;
        public final float gpuUtilization;
        public final int temperature;

        public PerformanceSnapshot(long timestamp, GpuConfig config, float tps,
                                float gpuUtilization, int temperature) {
            this.timestamp = timestamp;
            this.config = config;
            this.tps = tps;
            this.gpuUtilization = gpuUtilization;
            this.temperature = temperature;
        }
    }

    public static class TuningRecommendation {
        public final TuningAction action;
        public final String reason;
        public final GpuConfig newConfig;
        public final float expectedImprovement;

        public TuningRecommendation(TuningAction action, String reason, GpuConfig newConfig, float expectedImprovement) {
            this.action = action;
            this.reason = reason;
            this.newConfig = newConfig;
            this.expectedImprovement = expectedImprovement;
        }
    }

    public enum TuningAction {
        INCREASE_LAYERS, DECREASE_LAYERS, INCREASE_BATCH, DECREASE_BATCH,
        ENABLE_FP16, DISABLE_FP16, SWITCH_TO_CPU, NO_CHANGE
    }

    public static class ValidationResult {
        public final boolean isValid;
        public final List<String> issues;
        public final GpuConfig adjustedConfig;

        public ValidationResult(boolean isValid, List<String> issues, GpuConfig adjustedConfig) {
            this.isValid = isValid;
            this.issues = issues;
            this.adjustedConfig = adjustedConfig;
        }
    }

    public static class PerformanceReport {
        public final String gpuName;
        public final GpuTier gpuTier;
        public final GpuConfig currentConfig;
        public final float avgTps;
        public final float avgGpuUtilization;
        public final int gpuTemperature;
        public final int tuningHistory;
        public final TuningRecommendation recommendation;

        public PerformanceReport(String gpuName, GpuTier gpuTier, GpuConfig currentConfig,
                               float avgTps, float avgGpuUtilization, int gpuTemperature,
                               int tuningHistory, TuningRecommendation recommendation) {
            this.gpuName = gpuName;
            this.gpuTier = gpuTier;
            this.currentConfig = currentConfig;
            this.avgTps = avgTps;
            this.avgGpuUtilization = avgGpuUtilization;
            this.gpuTemperature = gpuTemperature;
            this.tuningHistory = tuningHistory;
            this.recommendation = recommendation;
        }
    }
}

class GpuPerformanceMonitor {
    private final GpuCapabilityDetector gpuDetector;
    private final MemoryMonitor memoryMonitor;

    public GpuPerformanceMonitor(Context context) {
        this.gpuDetector = new GpuCapabilityDetector(context);
        this.memoryMonitor = new MemoryMonitor(context);
    }

    public GpuStats getCurrentStats() {
        return new GpuStats(
            gpuDetector.getGpuUtilization(),
            gpuDetector.getTemperature(),
            gpuDetector.getGpuFrequency(),
            memoryMonitor.getMemoryUsageInfo()
        );
    }

    public PerformanceAnalysis analyzePerformanceTrends() {
        return new PerformanceAnalysis(
            false,
            gpuDetector.getTemperature() > 45,
            gpuDetector.getGpuUtilization(),
            0f,
            new ArrayList<>()
        );
    }

    public static class GpuStats {
        public final float gpuUtilization;
        public final int gpuTemperature;
        public final int[] gpuFrequency;
        public final MemoryUsageInfo memoryUsage;

        public GpuStats(float gpuUtilization, int gpuTemperature, int[] gpuFrequency, MemoryUsageInfo memoryUsage) {
            this.gpuUtilization = gpuUtilization;
            this.gpuTemperature = gpuTemperature;
            this.gpuFrequency = gpuFrequency;
            this.memoryUsage = memoryUsage;
        }
    }

    public static class PerformanceAnalysis {
        public final boolean insufficientData;
        public final boolean isOverheating;
        public final float avgGpuUtilization;
        public final float avgTps;
        public final List<String> recommendations;

        public PerformanceAnalysis(boolean insufficientData, boolean isOverheating,
                                  float avgGpuUtilization, float avgTps, List<String> recommendations) {
            this.insufficientData = insufficientData;
            this.isOverheating = isOverheating;
            this.avgGpuUtilization = avgGpuUtilization;
            this.avgTps = avgTps;
            this.recommendations = recommendations;
        }
    }
}

class GpuAutoAdapterStandalone {
    private final GpuCapabilityDetector gpuDetector;
    private final GpuDatabase gpuDatabase;
    private final MemoryMonitor memoryMonitor;

    public GpuAutoAdapterStandalone(Context context) {
        this.gpuDetector = new GpuCapabilityDetector(context);
        this.gpuDatabase = GpuDatabase.getInstance();
        this.memoryMonitor = new MemoryMonitor(context);
    }

    public GpuConfig autoConfigure(long modelSizeMB) {
        GpuProfile gpuProfile = gpuDetector.getGpuProfile();

        if (gpuProfile != null) {
            GpuConfig config = gpuDatabase.getConfigForModel(gpuProfile, modelSizeMB);

            MemoryUsageInfo memoryInfo = memoryMonitor.getMemoryUsageInfo();
            if (memoryInfo.availableMemoryMB < 2048) {
                Log.w("GpuAutoAdapter", "Low memory, reducing GPU layers");
                config = new GpuConfig(
                    (int)(config.getGpuLayers() * 0.5),
                    config.getBatchSize(),
                    false,
                    config.getUseFp16(),
                    config.getUseVulkan(),
                    config.isEnableTensorCores(),
                    config.isEnableMemoryPrefetch(),
                    config.isEnableAsyncCompute(),
                    config.getMaxComputeWorkGroupSize(),
                    config.getPreferredSharedMemorySize(),
                    config.isConservativeSync(),
                    config.isTileBasedRendering()
                );
            }

            Log.i("GpuAutoAdapter", "Auto-configured GPU: " + gpuProfile.getGpuName());
            Log.i("GpuAutoAdapter", "  GPU Layers: " + config.getGpuLayers());
            Log.i("GpuAutoAdapter", "  Batch Size: " + config.getBatchSize());
            Log.i("GpuAutoAdapter", "  FP16: " + config.getUseFp16());
            Log.i("GpuAutoAdapter", "  Vulkan: " + config.getUseVulkan());

            return config;
        }

        return GpuConfig.conservative();
    }

    public DeviceTier getDeviceTier() {
        GpuInfo gpuInfo = gpuDetector.detectGpuInfo();
        GpuTier gpuTier = gpuDatabase.getTier(gpuInfo != null ? gpuInfo.getRenderer() : "");

        switch (gpuTier) {
            case HIGH: return DeviceTier.FLAGSHIP;
            case MID: return DeviceTier.HIGH_END;
            case LOW: return DeviceTier.MID_RANGE;
            default: return DeviceTier.BUDGET;
        }
    }

    public enum DeviceTier {
        FLAGSHIP, HIGH_END, MID_RANGE, BUDGET
    }
}