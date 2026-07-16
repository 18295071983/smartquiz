package com.oilquiz.app.ai.gpu;

public class BenchmarkResult {
    public final String gpuName;
    public final GpuTier tier;
    public final boolean supportsVulkan;
    public final boolean supportsFP16;
    public final long availableMemoryMB;
    public final float peakPerformanceTFLOPS;
    public final int recommendedLayers;

    public BenchmarkResult() {
        this.gpuName = "";
        this.tier = GpuTier.UNKNOWN;
        this.supportsVulkan = false;
        this.supportsFP16 = false;
        this.availableMemoryMB = 0;
        this.peakPerformanceTFLOPS = 0f;
        this.recommendedLayers = 0;
    }

    public BenchmarkResult(String gpuName, GpuTier tier, boolean supportsVulkan, boolean supportsFP16,
                          long availableMemoryMB, float peakPerformanceTFLOPS, int recommendedLayers) {
        this.gpuName = gpuName;
        this.tier = tier;
        this.supportsVulkan = supportsVulkan;
        this.supportsFP16 = supportsFP16;
        this.availableMemoryMB = availableMemoryMB;
        this.peakPerformanceTFLOPS = peakPerformanceTFLOPS;
        this.recommendedLayers = recommendedLayers;
    }
}