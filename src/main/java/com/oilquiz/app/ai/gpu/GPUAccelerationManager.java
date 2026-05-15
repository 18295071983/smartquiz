package com.oilquiz.app.ai.gpu;

import android.content.Context;
import android.os.Build;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.infra.AppLogger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GPUAccelerationManager {
    private static final String TAG = "GPUAcceleration";

    public enum GPUBackend {
        NONE("无GPU"),
        VULKAN("Vulkan"),
        OPENCL("OpenCL");

        public final String displayName;
        GPUBackend(String displayName) { this.displayName = displayName; }
    }

    public static class LayerConfig {
        public final int totalLayers;
        public final int gpuLayers;
        public final int cpuLayers;

        public LayerConfig(int totalLayers, int gpuLayers) {
            this.totalLayers = totalLayers;
            this.gpuLayers = Math.min(gpuLayers, totalLayers);
            this.cpuLayers = totalLayers - this.gpuLayers;
        }

        public static LayerConfig autoConfigure(int totalLayers, long gpuMemoryMB) {
            if (gpuMemoryMB <= 0) {
                return new LayerConfig(totalLayers, 0);
            }

            int estimatedLayersPerGB = (int)(gpuMemoryMB / 100);

            if (estimatedLayersPerGB >= totalLayers) {
                return new LayerConfig(totalLayers, totalLayers);
            } else if (estimatedLayersPerGB > totalLayers / 2) {
                return new LayerConfig(totalLayers, (int)(totalLayers * 0.8));
            } else if (estimatedLayersPerGB > 10) {
                return new LayerConfig(totalLayers, estimatedLayersPerGB);
            } else {
                return new LayerConfig(totalLayers, Math.min(20, totalLayers));
            }
        }

        @Override
        public String toString() {
            return String.format("LayerConfig[total=%d, GPU=%d (%.1f%%), CPU=%d]",
                totalLayers, gpuLayers,
                totalLayers > 0 ? (gpuLayers * 100.0 / totalLayers) : 0,
                cpuLayers);
        }
    }

    public static class GPUDeviceInfo {
        public final GPUBackend backend;
        public final String deviceName;
        public final long totalMemoryMB;
        public final long availableMemoryMB;
        public final int computeUnits;
        public final boolean supportsFP16;
        public final String apiVersion;

        public GPUDeviceInfo(GPUBackend backend, String deviceName, long totalMemoryMB,
                      long availableMemoryMB, int computeUnits,
                      boolean supportsFP16, String apiVersion) {
            this.backend = backend;
            this.deviceName = deviceName;
            this.totalMemoryMB = totalMemoryMB;
            this.availableMemoryMB = availableMemoryMB;
            this.computeUnits = computeUnits;
            this.supportsFP16 = supportsFP16;
            this.apiVersion = apiVersion;
        }

        @Override
        public String toString() {
            return String.format(
                "GPUDeviceInfo[%s: %s, %d/%d MB, %d CU, FP16=%b, API=%s]",
                backend.displayName, deviceName,
                availableMemoryMB, totalMemoryMB,
                computeUnits, supportsFP16, apiVersion);
        }
    }

    public static class PerformanceStats {
        public final long totalInferences;
        public final long gpuInferences;
        public final long cpuInferences;
        public final double avgGPUTimeMs;
        public final double avgCPUTimeMs;
        public final double speedupRatio;
        public final double memoryUsagePercent;
        public final double temperatureCelsius;
        public final long timestamp;

        public PerformanceStats(long totalInferences, long gpuInferences, long cpuInferences,
                               double avgGPUTimeMs, double avgCPUTimeMs,
                               double memoryUsagePercent, double temperatureCelsius) {
            this.totalInferences = totalInferences;
            this.gpuInferences = gpuInferences;
            this.cpuInferences = cpuInferences;
            this.avgGPUTimeMs = avgGPUTimeMs;
            this.avgCPUTimeMs = avgCPUTimeMs;
            this.speedupRatio = avgCPUTimeMs > 0 ? (avgCPUTimeMs / avgGPUTimeMs) : 1.0;
            this.memoryUsagePercent = memoryUsagePercent;
            this.temperatureCelsius = temperatureCelsius;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format(
                "Performance[inferences=%d (GPU=%d, CPU=%d), " +
                "avgTime=%.1fms(GPU)/%.1fms(CPU), speedup=%.2fx, mem=%.1f%%, temp=%.1f°C]",
                totalInferences, gpuInferences, cpuInferences,
                avgGPUTimeMs, avgCPUTimeMs,
                speedupRatio, memoryUsagePercent, temperatureCelsius);
        }
    }

    public static class InferenceResult {
        public final String text;
        public final double elapsedTimeMs;
        public final boolean usedGPU;
        public final LayerConfig layerConfig;
        public final long timestamp;

        public InferenceResult(String text, double elapsedTimeMs, boolean usedGPU,
                             LayerConfig layerConfig) {
            this.text = text;
            this.elapsedTimeMs = elapsedTimeMs;
            this.usedGPU = usedGPU;
            this.layerConfig = layerConfig;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Context context;
    private GPUDeviceInfo currentGPU;
    private LayerConfig layerConfig;
    private GPUBackend activeBackend = GPUBackend.NONE;

    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicInteger gpuLayerCount = new AtomicInteger(0);

    private final AtomicInteger totalInferenceCount = new AtomicInteger(0);
    private final AtomicInteger gpuInferenceCount = new AtomicInteger(0);
    private final AtomicInteger cpuInferenceCount = new AtomicInteger(0);
    private final double[] gpuTimes = new double[100];
    private final double[] cpuTimes = new double[100];
    private int timeIndex = 0;

    private static volatile GPUAccelerationManager instance;

    public static GPUAccelerationManager getInstance(Context context) {
        if (instance == null) {
            synchronized (GPUAccelerationManager.class) {
                if (instance == null) {
                    instance = new GPUAccelerationManager(context);
                }
            }
        }
        return instance;
    }

    public GPUAccelerationManager(Context context) {
        this.context = context.getApplicationContext();
        detectAndInitialize();
    }

    private void detectAndInitialize() {
        AppLogger.i(TAG, "Detecting GPU acceleration capabilities...");

        try {
            if (!LlamaHelper.isLibraryLoaded()) {
                AppLogger.w(TAG, "LlamaHelper library not loaded yet, GPU detection deferred");
                return;
            }

            String deviceInfo = LlamaHelper.getDeviceInfo();
            AppLogger.i(TAG, "LlamaHelper device info: " + deviceInfo);

            int deviceCount = LlamaHelper.getDeviceCount();
            long totalMem = LlamaHelper.getTotalDeviceMemory();
            long freeMem = LlamaHelper.getFreeDeviceMemory();

            AppLogger.i(TAG, "Device count: " + deviceCount + ", totalMem: " + totalMem + ", freeMem: " + freeMem);

            if (deviceCount > 0 || totalMem > 0) {
                GpuCapabilityDetector detector = new GpuCapabilityDetector(context);
                GpuInfo gpuInfo = detector.detectGpuInfo();

                String deviceName = "Unknown GPU";
                boolean supportsFP16 = false;
                String apiVersion = "OpenCL 3.0";

                if (gpuInfo != null) {
                    deviceName = gpuInfo.renderer;
                    supportsFP16 = gpuInfo.supportsFP16;
                    if (gpuInfo.vulkanVersion != null && !gpuInfo.vulkanVersion.isEmpty()) {
                        apiVersion = "OpenCL 3.0 / " + gpuInfo.vulkanVersion;
                    }
                }

                currentGPU = new GPUDeviceInfo(
                    GPUBackend.OPENCL,
                    deviceName,
                    totalMem > 0 ? totalMem : (gpuInfo != null ? gpuInfo.totalMemoryMB : 0),
                    freeMem > 0 ? freeMem : (gpuInfo != null ? gpuInfo.availableMemoryMB : 0),
                    0,
                    supportsFP16,
                    apiVersion
                );

                activeBackend = GPUBackend.OPENCL;
                isInitialized.set(true);

                long gpuMemMB = currentGPU.totalMemoryMB > 0 ? currentGPU.totalMemoryMB : 2048;
                layerConfig = LayerConfig.autoConfigure(32, gpuMemMB);
                gpuLayerCount.set(layerConfig.gpuLayers);

                AppLogger.i(TAG, "OpenCL backend initialized via LlamaHelper: " + currentGPU);
                AppLogger.i(TAG, "Layer config: " + layerConfig);

                LlamaHelper.setGPULayers(layerConfig.gpuLayers);
                AppLogger.i(TAG, "GPU layers set to " + layerConfig.gpuLayers + " in LlamaHelper");
            } else {
                AppLogger.i(TAG, "No GPU devices detected via LlamaHelper, checking Java fallback...");
                initializeFromJava();
            }

        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to initialize GPU acceleration, trying Java fallback", e);
            initializeFromJava();
        }
    }

    private void initializeFromJava() {
        try {
            GpuCapabilityDetector detector = new GpuCapabilityDetector(context);
            GpuInfo gpuInfo = detector.detectGpuInfo();

            if (gpuInfo != null) {
                GPUBackend backend = GPUBackend.OPENCL;
                String apiVersion = "OpenCL 3.0";

                if (gpuInfo.vendor != null) {
                    if (gpuInfo.vendor.contains("Qualcomm") || gpuInfo.renderer.contains("Adreno")) {
                        apiVersion = "OpenCL 3.0 (Qualcomm Adreno)";
                    } else if (gpuInfo.vendor.contains("ARM") || gpuInfo.renderer.contains("Mali") || gpuInfo.renderer.contains("Immortalis")) {
                        apiVersion = "OpenCL 3.0 (ARM Mali)";
                    }
                }

                if (gpuInfo.supportsVulkan) {
                    apiVersion += " / " + gpuInfo.vulkanVersion;
                }

                currentGPU = new GPUDeviceInfo(
                    backend,
                    gpuInfo.renderer,
                    gpuInfo.totalMemoryMB,
                    gpuInfo.availableMemoryMB,
                    0,
                    gpuInfo.supportsFP16,
                    apiVersion
                );

                activeBackend = backend;
                isInitialized.set(true);

                layerConfig = LayerConfig.autoConfigure(32, currentGPU.availableMemoryMB);
                gpuLayerCount.set(layerConfig.gpuLayers);

                AppLogger.i(TAG, backend.displayName + " detected via Java: " + currentGPU);

                if (LlamaHelper.isLibraryLoaded()) {
                    LlamaHelper.setGPULayers(layerConfig.gpuLayers);
                }
            } else {
                AppLogger.i(TAG, "No GPU info detected via Java, using CPU only");
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Java GPU detection failed", e);
        }
    }

    public void configureLayers(int totalModelLayers) {
        if (!isInitialized.get() || currentGPU == null) {
            AppLogger.w(TAG, "Cannot configure layers: GPU not initialized");
            return;
        }

        layerConfig = LayerConfig.autoConfigure(totalModelLayers, currentGPU.totalMemoryMB);
        gpuLayerCount.set(layerConfig.gpuLayers);

        AppLogger.i(TAG, "Layer configuration updated: " + layerConfig);

        if (LlamaHelper.isLibraryLoaded()) {
            LlamaHelper.setGPULayers(layerConfig.gpuLayers);
            AppLogger.i(TAG, "GPU layers set in LlamaHelper: " + layerConfig.gpuLayers);
        }
    }

    public void setGPULayers(int layers) {
        gpuLayerCount.set(layers);
        if (LlamaHelper.isLibraryLoaded()) {
            LlamaHelper.setGPULayers(layers);
            AppLogger.i(TAG, "GPU layers set manually: " + layers);
        }
    }

    public int getCurrentGPULayers() {
        if (LlamaHelper.isLibraryLoaded()) {
            return LlamaHelper.getGPULayers();
        }
        return gpuLayerCount.get();
    }

    public synchronized PerformanceStats getPerformanceStats() {
        double sumGPU = 0, sumCPU = 0;
        int countGPU = 0, countCPU = 0;

        for (int i = 0; i < 100; i++) {
            if (gpuTimes[i] > 0) { sumGPU += gpuTimes[i]; countGPU++; }
            if (cpuTimes[i] > 0) { sumCPU += cpuTimes[i]; countCPU++; }
        }

        double avgGPU = countGPU > 0 ? (sumGPU / countGPU) : 0;
        double avgCPU = countCPU > 0 ? (sumCPU / countCPU) : 0;

        double memoryUsage = getMemoryUsagePercent();
        double temperature = getTemperature();

        return new PerformanceStats(
            totalInferenceCount.get(),
            gpuInferenceCount.get(),
            cpuInferenceCount.get(),
            avgGPU, avgCPU,
            memoryUsage, temperature
        );
    }

    public double getMemoryUsagePercent() {
        if (LlamaHelper.isLibraryLoaded()) {
            return LlamaHelper.getMemoryUsage();
        }
        return 0.0;
    }

    public double getTemperature() {
        return 0.0;
    }

    public boolean isAvailable() { return isInitialized.get(); }
    public GPUBackend getActiveBackend() { return activeBackend; }
    public GPUDeviceInfo getGPUInfo() { return currentGPU; }
    public LayerConfig getLayerConfig() { return layerConfig; }
    public int getGPULayerCount() { return gpuLayerCount.get(); }

    public String getSystemReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== GPU Acceleration System Report ===\n\n");
        report.append("Android: ").append(Build.VERSION.RELEASE)
           .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");

        if (currentGPU != null) {
            report.append("GPU: ").append(currentGPU).append("\n\n");
            report.append("Layers: ").append(layerConfig != null ? layerConfig.toString() : "Not configured").append("\n");
            report.append("Current GPU Layers: ").append(getCurrentGPULayers()).append("\n\n");
        } else {
            report.append("GPU: Not available\n\n");
        }

        if (LlamaHelper.isLibraryLoaded()) {
            report.append("LlamaHelper: Loaded\n");
            report.append("Inference Speed: ").append(String.format("%.2f tokens/s", LlamaHelper.getInferenceSpeed())).append("\n");
            report.append("Memory Usage: ").append(String.format("%.1f%%", LlamaHelper.getMemoryUsage())).append("\n");
        } else {
            report.append("LlamaHelper: Not loaded\n");
        }

        report.append("Performance:\n").append(getPerformanceStats()).append("\n");

        return report.toString();
    }
}
