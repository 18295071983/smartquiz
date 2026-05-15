package com.oilquiz.app.ai.gpu;

public class GpuConfig {
    public final int gpuLayers;
    public final int batchSize;
    public final boolean kvCacheOnGpu;
    public final boolean useFp16;
    public final boolean useVulkan;
    public final boolean enableTensorCores;
    public final boolean enableMemoryPrefetch;
    public final boolean enableAsyncCompute;
    public final int maxComputeWorkGroupSize;
    public final int preferredSharedMemorySize;
    public final boolean conservativeSync;
    public final boolean tileBasedRendering;

    public GpuConfig(int gpuLayers, int batchSize, boolean kvCacheOnGpu, boolean useFp16, boolean useVulkan,
                     boolean enableTensorCores, boolean enableMemoryPrefetch, boolean enableAsyncCompute,
                     int maxComputeWorkGroupSize, int preferredSharedMemorySize, boolean conservativeSync, boolean tileBasedRendering) {
        this.gpuLayers = gpuLayers;
        this.batchSize = batchSize;
        this.kvCacheOnGpu = kvCacheOnGpu;
        this.useFp16 = useFp16;
        this.useVulkan = useVulkan;
        this.enableTensorCores = enableTensorCores;
        this.enableMemoryPrefetch = enableMemoryPrefetch;
        this.enableAsyncCompute = enableAsyncCompute;
        this.maxComputeWorkGroupSize = maxComputeWorkGroupSize;
        this.preferredSharedMemorySize = preferredSharedMemorySize;
        this.conservativeSync = conservativeSync;
        this.tileBasedRendering = tileBasedRendering;
    }

    public static GpuConfig cpuOnly() {
        return new GpuConfig(0, 64, false, false, false, false, true, true, 1024, 65536, false, false);
    }

    public static GpuConfig conservative() {
        return new GpuConfig(5, 64, false, false, false, false, true, true, 1024, 65536, true, false);
    }

    public int getGpuLayers() { return gpuLayers; }
    public int getBatchSize() { return batchSize; }
    public boolean isKvCacheOnGpu() { return kvCacheOnGpu; }
    public boolean getUseFp16() { return useFp16; }
    public boolean getUseVulkan() { return useVulkan; }
    public boolean isEnableTensorCores() { return enableTensorCores; }
    public boolean isEnableMemoryPrefetch() { return enableMemoryPrefetch; }
    public boolean isEnableAsyncCompute() { return enableAsyncCompute; }
    public int getMaxComputeWorkGroupSize() { return maxComputeWorkGroupSize; }
    public int getPreferredSharedMemorySize() { return preferredSharedMemorySize; }
    public boolean isConservativeSync() { return conservativeSync; }
    public boolean isTileBasedRendering() { return tileBasedRendering; }
}