package com.oilquiz.app.ai.config;

import android.content.Context;
import android.util.Log;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.gpu.GpuProfile;

@Deprecated
public class GpuAutoAdapter {
    private static final String TAG = "GpuAutoAdapter";

    private final Context context;
    private final GpuCapabilityDetector gpuDetector;
    private GpuInfo detectedGpu;

    @Deprecated
    public GpuAutoAdapter(Context context) {
        this.context = context;
        this.gpuDetector = new GpuCapabilityDetector(context);
        this.detectedGpu = detectGpuInfo();
    }

    @Deprecated
    public static class GpuConfig {
        public int gpuLayers;
        public int batchSize;
        public boolean kvCacheOnGpu;
        public boolean useFp16;
        public boolean useVulkan;

        public static GpuConfig cpuOnly() {
            GpuConfig config = new GpuConfig();
            config.gpuLayers = 0;
            config.batchSize = 256;
            config.kvCacheOnGpu = false;
            config.useFp16 = false;
            config.useVulkan = false;
            return config;
        }

        public static GpuConfig conservative() {
            GpuConfig config = new GpuConfig();
            config.gpuLayers = 10;
            config.batchSize = 128;
            config.kvCacheOnGpu = false;
            config.useFp16 = true;
            config.useVulkan = false;
            return config;
        }
    }

    @Deprecated
    public static class GpuInfo {
        public String renderer;
        public String vendor;
        public int majorVersion;
        public int minorVersion;
        public boolean supportsVulkan;
        public boolean supportsFp16;
    }

    @Deprecated
    public GpuInfo detectGpuInfo() {
        GpuInfo info = new GpuInfo();

        var gpuInfo = gpuDetector.detectGpuInfo();
        if (gpuInfo != null) {
            info.renderer = gpuInfo.getRenderer();
            info.vendor = gpuInfo.getVendor();
            info.supportsVulkan = gpuInfo.isSupportsVulkan();
            info.supportsFp16 = gpuInfo.isSupportsFP16();
        } else {
            info.renderer = "Unknown";
            info.vendor = "Unknown";
            info.supportsVulkan = false;
            info.supportsFp16 = false;
        }

        Log.i(TAG, "Detected GPU: " + info.renderer + ", Vendor: " + info.vendor);
        Log.i(TAG, "Vulkan: " + info.supportsVulkan + ", FP16: " + info.supportsFp16);

        return info;
    }

    @Deprecated
    public GpuConfig autoConfigure(long modelSizeMB) {
        GpuProfile profile = gpuDetector.getGpuProfile();

        if (profile != null) {
            var gpuConfig = gpuDetector.getOptimalConfig(modelSizeMB);
            GpuConfig config = new GpuConfig();
            config.gpuLayers = gpuConfig.getGpuLayers();
            config.batchSize = gpuConfig.getBatchSize();
            config.kvCacheOnGpu = gpuConfig.isKvCacheOnGpu();
            config.useFp16 = gpuConfig.getUseFp16();
            config.useVulkan = gpuConfig.getUseVulkan();

            Log.i(TAG, "Auto-configured using GpuDatabase for GPU: " + profile.getGpuName());
            Log.i(TAG, "  GPU Layers: " + config.gpuLayers);
            Log.i(TAG, "  Batch Size: " + config.batchSize);
            Log.i(TAG, "  FP16: " + config.useFp16);
            Log.i(TAG, "  Vulkan: " + config.useVulkan);

            return config;
        }

        Log.w(TAG, "Unknown GPU, using conservative config");
        return GpuConfig.conservative();
    }

    @Deprecated
    public GpuInfo getDetectedGpu() {
        if (detectedGpu == null) {
            detectedGpu = detectGpuInfo();
        }
        return detectedGpu;
    }

    @Deprecated
    public String getGpuDescription() {
        if (detectedGpu == null) {
            detectedGpu = detectGpuInfo();
        }

        if (detectedGpu == null) {
            return "Unknown GPU";
        }

        return String.format("GPU: %s (%s)\nVulkan: %s | FP16: %s",
                detectedGpu.renderer,
                detectedGpu.vendor,
                detectedGpu.supportsVulkan ? "Yes" : "No",
                detectedGpu.supportsFp16 ? "Yes" : "No");
    }

    public GpuCapabilityDetector getGpuDetector() {
        return gpuDetector;
    }
}