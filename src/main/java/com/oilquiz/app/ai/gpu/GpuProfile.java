package com.oilquiz.app.ai.gpu;

public class GpuProfile {
    public final String gpuName;
    public final GpuSeries series;
    public final String architecture;
    public final String vulkanVersion;
    public final int computeUnits;
    public final float peakPerformanceTFLOPS;
    public final int memoryBandwidthGBps;
    public final int l2CacheMB;
    public final boolean supportsFP16;
    public final boolean supportsTensorCores;
    public final boolean supportsAsyncCompute;
    public final GpuConfig recommendedConfig;

    public GpuProfile(String gpuName, GpuSeries series, String architecture, String vulkanVersion,
                      int computeUnits, float peakPerformanceTFLOPS, int memoryBandwidthGBps, int l2CacheMB,
                      boolean supportsFP16, boolean supportsTensorCores, boolean supportsAsyncCompute,
                      GpuConfig recommendedConfig) {
        this.gpuName = gpuName;
        this.series = series;
        this.architecture = architecture;
        this.vulkanVersion = vulkanVersion;
        this.computeUnits = computeUnits;
        this.peakPerformanceTFLOPS = peakPerformanceTFLOPS;
        this.memoryBandwidthGBps = memoryBandwidthGBps;
        this.l2CacheMB = l2CacheMB;
        this.supportsFP16 = supportsFP16;
        this.supportsTensorCores = supportsTensorCores;
        this.supportsAsyncCompute = supportsAsyncCompute;
        this.recommendedConfig = recommendedConfig;
    }

    public String getGpuName() { return gpuName; }
    public GpuSeries getSeries() { return series; }
    public String getArchitecture() { return architecture; }
    public String getVulkanVersion() { return vulkanVersion; }
    public int getComputeUnits() { return computeUnits; }
    public float getPeakPerformanceTFLOPS() { return peakPerformanceTFLOPS; }
    public int getMemoryBandwidthGBps() { return memoryBandwidthGBps; }
    public int getL2CacheMB() { return l2CacheMB; }
    public boolean isSupportsFP16() { return supportsFP16; }
    public boolean isSupportsTensorCores() { return supportsTensorCores; }
    public boolean isSupportsAsyncCompute() { return supportsAsyncCompute; }
    public GpuConfig getRecommendedConfig() { return recommendedConfig; }
}