package com.oilquiz.app.ai.config;

import android.content.Context;
import android.util.Log;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.gpu.GpuProfile;
import com.oilquiz.app.ai.gpu.GpuTier;

@Deprecated
public class GpuCompatibilityAdapter {
    private static final String TAG = "GpuCompatibilityAdapter";

    private final Context context;
    private final GpuCapabilityDetector gpuDetector;

    @Deprecated
    public GpuCompatibilityAdapter(Context context) {
        this.context = context;
        this.gpuDetector = new GpuCapabilityDetector(context);
    }

    @Deprecated
    public static class GpuConfigResult {
        public int gpuLayers;
        public int batchSize;
        public boolean kvCacheOnGpu;
        public boolean useFp16;
        public boolean useVulkan;

        public static GpuConfigResult cpuOnly() {
            GpuConfigResult config = new GpuConfigResult();
            config.gpuLayers = 0;
            config.batchSize = 256;
            config.kvCacheOnGpu = false;
            config.useFp16 = false;
            config.useVulkan = false;
            return config;
        }

        public static GpuConfigResult conservative() {
            GpuConfigResult config = new GpuConfigResult();
            config.gpuLayers = 10;
            config.batchSize = 128;
            config.kvCacheOnGpu = false;
            config.useFp16 = true;
            config.useVulkan = false;
            return config;
        }
    }

    @Deprecated
    public static class GpuInfoResult {
        public String renderer;
        public String vendor;
        public int majorVersion;
        public int minorVersion;
        public boolean supportsVulkan;
        public boolean supportsFp16;
    }

    @Deprecated
    public GpuInfoResult detectGpuInfo() {
        GpuInfoResult info = new GpuInfoResult();

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
    public GpuConfigResult autoConfigure(long modelSizeMB) {
        var gpuProfile = gpuDetector.getGpuProfile();

        if (gpuProfile != null) {
            var gpuConfig = gpuDetector.getOptimalConfig(modelSizeMB);
            GpuConfigResult result = new GpuConfigResult();
            result.gpuLayers = gpuConfig.getGpuLayers();
            result.batchSize = gpuConfig.getBatchSize();
            result.kvCacheOnGpu = gpuConfig.isKvCacheOnGpu();
            result.useFp16 = gpuConfig.getUseFp16();
            result.useVulkan = gpuConfig.getUseVulkan();

            Log.i(TAG, "Auto-configured using new GpuDatabase for GPU: " + gpuProfile.getGpuName());
            Log.i(TAG, "  GPU Layers: " + result.gpuLayers);
            Log.i(TAG, "  Batch Size: " + result.batchSize);
            Log.i(TAG, "  FP16: " + result.useFp16);
            Log.i(TAG, "  Vulkan: " + result.useVulkan);

            return result;
        }

        Log.w(TAG, "Unknown GPU, using conservative config");
        return GpuConfigResult.conservative();
    }

    @Deprecated
    public GpuInfoResult getDetectedGpu() {
        return detectGpuInfo();
    }

    @Deprecated
    public String getGpuDescription() {
        GpuInfoResult info = detectGpuInfo();
        if (info == null) {
            return "Unknown GPU";
        }

        return String.format("GPU: %s (%s)\nVulkan: %s | FP16: %s",
                info.renderer,
                info.vendor,
                info.supportsVulkan ? "Yes" : "No",
                info.supportsFp16 ? "Yes" : "No");
    }

    public GpuCapabilityDetector getGpuDetector() {
        return gpuDetector;
    }
}