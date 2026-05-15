package com.oilquiz.app.ai.gpu;

public class GpuInfo {
    public final String renderer;
    public final String vendor;
    public final String version;
    public final String apiVersion;
    public final long availableMemoryMB;
    public final long totalMemoryMB;
    public final int deviceId;
    public final int driverVersion;
    public final String vulkanVersion;
    public final boolean supportsVulkan;
    public final boolean supportsFP16;

    public GpuInfo(String renderer, String vendor, String version, String apiVersion,
                  long availableMemoryMB, long totalMemoryMB, int deviceId, int driverVersion,
                  String vulkanVersion, boolean supportsVulkan, boolean supportsFP16) {
        this.renderer = renderer;
        this.vendor = vendor;
        this.version = version;
        this.apiVersion = apiVersion;
        this.availableMemoryMB = availableMemoryMB;
        this.totalMemoryMB = totalMemoryMB;
        this.deviceId = deviceId;
        this.driverVersion = driverVersion;
        this.vulkanVersion = vulkanVersion;
        this.supportsVulkan = supportsVulkan;
        this.supportsFP16 = supportsFP16;
    }

    public String getRenderer() { return renderer; }
    public String getVendor() { return vendor; }
    public String getVersion() { return version; }
    public String getApiVersion() { return apiVersion; }
    public long getAvailableMemoryMB() { return availableMemoryMB; }
    public long getTotalMemoryMB() { return totalMemoryMB; }
    public int getDeviceId() { return deviceId; }
    public int getDriverVersion() { return driverVersion; }
    public String getVulkanVersion() { return vulkanVersion; }
    public boolean isSupportsVulkan() { return supportsVulkan; }
    public boolean isSupportsFP16() { return supportsFP16; }
}