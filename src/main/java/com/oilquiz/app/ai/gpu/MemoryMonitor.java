package com.oilquiz.app.ai.gpu;

import android.app.ActivityManager;
import android.content.Context;

public class MemoryMonitor {
    private final ActivityManager activityManager;

    public MemoryMonitor(Context context) {
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    public long getAvailableMemoryMB() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.availMem / (1024 * 1024);
    }

    public long getTotalMemoryMB() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.totalMem / (1024 * 1024);
    }

    public float getMemoryUsagePercent() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long used = memoryInfo.totalMem - memoryInfo.availMem;
        return (used * 100f) / memoryInfo.totalMem;
    }

    public boolean isLowMemory() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.lowMemory;
    }

    public MemoryUsageInfo getMemoryUsageInfo() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        Runtime runtime = Runtime.getRuntime();
        long javaHeapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long javaHeapMax = runtime.maxMemory() / (1024 * 1024);

        return new MemoryUsageInfo(
            memoryInfo.totalMem / (1024 * 1024),
            memoryInfo.availMem / (1024 * 1024),
            (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024),
            javaHeapUsed,
            javaHeapMax,
            memoryInfo.lowMemory
        );
    }
}