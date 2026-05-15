package com.oilquiz.app.ai.gpu;

public class MemoryUsageInfo {
    public final long totalMemoryMB;
    public final long availableMemoryMB;
    public final long usedMemoryMB;
    public final float usagePercent;
    public final long javaHeapUsedMB;
    public final long javaHeapMaxMB;
    public final boolean isLowMemory;

    public MemoryUsageInfo(long totalMemoryMB, long availableMemoryMB, long usedMemoryMB,
                          float usagePercent, long javaHeapUsedMB, long javaHeapMaxMB, boolean isLowMemory) {
        this.totalMemoryMB = totalMemoryMB;
        this.availableMemoryMB = availableMemoryMB;
        this.usedMemoryMB = usedMemoryMB;
        this.usagePercent = usagePercent;
        this.javaHeapUsedMB = javaHeapUsedMB;
        this.javaHeapMaxMB = javaHeapMaxMB;
        this.isLowMemory = isLowMemory;
    }

    public MemoryUsageInfo(long totalMemoryMB, long availableMemoryMB, long usedMemoryMB,
                          long javaHeapUsedMB, long javaHeapMaxMB, boolean isLowMemory) {
        this.totalMemoryMB = totalMemoryMB;
        this.availableMemoryMB = availableMemoryMB;
        this.usedMemoryMB = usedMemoryMB;
        this.usagePercent = (totalMemoryMB - availableMemoryMB) * 100f / totalMemoryMB;
        this.javaHeapUsedMB = javaHeapUsedMB;
        this.javaHeapMaxMB = javaHeapMaxMB;
        this.isLowMemory = isLowMemory;
    }

    public long getTotalMemoryMB() { return totalMemoryMB; }
    public long getAvailableMemoryMB() { return availableMemoryMB; }
    public long getUsedMemoryMB() { return usedMemoryMB; }
    public float getUsagePercent() { return usagePercent; }
    public long getJavaHeapUsedMB() { return javaHeapUsedMB; }
    public long getJavaHeapMaxMB() { return javaHeapMaxMB; }
    public boolean isLowMemory() { return isLowMemory; }
}