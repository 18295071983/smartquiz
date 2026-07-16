package com.oilquiz.app.ai.gpu;

public class VulkanInfo {
    public final String version;
    public final String driverVersion;
    public final String deviceName;
    public final int vendorId;
    public final int deviceId;
    public final int maxComputeWorkGroupSize;
    public final int maxSharedMemorySize;
    public final boolean supportsFP16;

    public VulkanInfo(String version, String driverVersion, String deviceName, int vendorId,
                     int deviceId, int maxComputeWorkGroupSize, int maxSharedMemorySize, boolean supportsFP16) {
        this.version = version;
        this.driverVersion = driverVersion;
        this.deviceName = deviceName;
        this.vendorId = vendorId;
        this.deviceId = deviceId;
        this.maxComputeWorkGroupSize = maxComputeWorkGroupSize;
        this.maxSharedMemorySize = maxSharedMemorySize;
        this.supportsFP16 = supportsFP16;
    }
}