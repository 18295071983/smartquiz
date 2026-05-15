package com.oilquiz.app.ai.config;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.gpu.MemoryMonitor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

@Deprecated
public class DeviceCapabilityDetector {
    private static final String TAG = "DeviceDetector";

    private static MemoryMonitor cachedMemoryMonitor;

    private static MemoryMonitor getMemoryMonitor(Context context) {
        if (cachedMemoryMonitor == null) {
            cachedMemoryMonitor = new MemoryMonitor(context);
        }
        return cachedMemoryMonitor;
    }

    @Deprecated
    public static DeviceTier detectDeviceTier(Context context) {
        MemoryMonitor memoryMonitor = getMemoryMonitor(context);
        long totalMemMB = memoryMonitor.getTotalMemoryMB();
        int cpuCores = getCpuCores();
        boolean hasBigCores = hasBigCores();

        Log.d(TAG, String.format("Device: RAM=%dMB, Cores=%d, BigCores=%b, Model=%s, API=%d",
                                totalMemMB, cpuCores, hasBigCores, Build.MODEL, Build.VERSION.SDK_INT));

        if (totalMemMB >= 12288 && cpuCores >= 8 && hasBigCores) {
            return DeviceTier.FLAGSHIP;
        } else if (totalMemMB >= 8192 && cpuCores >= 8) {
            return DeviceTier.HIGH_END;
        } else if (totalMemMB >= 6144) {
            return DeviceTier.MID_RANGE;
        } else {
            return DeviceTier.BUDGET;
        }
    }

    @Deprecated
    public static DeviceTier getDeviceTierStatic(Context context) {
        return detectDeviceTier(context);
    }

    private static boolean hasBigCores() {
        int maxFreq = 0;
        int sampleCount = 0;

        for (int i = 0; i < 3; i++) {
            int freq = getMaxCpuFrequency();
            if (freq > 0) {
                maxFreq = Math.max(maxFreq, freq);
                sampleCount++;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (sampleCount == 0) {
            Log.w(TAG, "Failed to detect CPU frequency, using default value");
            return true;
        }

        Log.d(TAG, "Max CPU frequency: " + maxFreq + " MHz");
        return maxFreq > 2000;
    }

    private static int getMaxCpuFrequency() {
        int maxFreq = 0;

        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.contains("cpu MHz")) {
                    try {
                        String freqStr = line.split(":")[1].trim();
                        int freq = Integer.parseInt(freqStr.split("\\.")[0]);
                        maxFreq = Math.max(maxFreq, freq);
                    } catch (NumberFormatException e) {
                    }
                }
            }
            reader.close();

            if (maxFreq == 0) {
                for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
                    try {
                        String path = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq";
                        BufferedReader freqReader = new BufferedReader(new FileReader(path));
                        String freqStr = freqReader.readLine();
                        if (freqStr != null) {
                            int freq = Integer.parseInt(freqStr) / 1000;
                            maxFreq = Math.max(maxFreq, freq);
                        }
                        freqReader.close();
                    } catch (Exception e) {
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read CPU info", e);
        }

        return maxFreq;
    }

    @Deprecated
    public static long getAvailableMemoryMB(Context context) {
        MemoryMonitor memoryMonitor = getMemoryMonitor(context);
        return memoryMonitor.getAvailableMemoryMB();
    }

    @Deprecated
    public static long getTotalMemoryMB(Context context) {
        MemoryMonitor memoryMonitor = getMemoryMonitor(context);
        return memoryMonitor.getTotalMemoryMB();
    }

    @Deprecated
    public static int getCpuCores() {
        try {
            return Runtime.getRuntime().availableProcessors();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get CPU cores", e);
            return 4;
        }
    }

    @Deprecated
    public static String getDeviceInfo(Context context) {
        DeviceTier tier = detectDeviceTier(context);
        MemoryMonitor memoryMonitor = getMemoryMonitor(context);
        long totalMem = memoryMonitor.getTotalMemoryMB();
        long availableMem = memoryMonitor.getAvailableMemoryMB();
        int cores = getCpuCores();
        int maxFreq = getMaxCpuFrequency();

        return String.format("Device Info:\n" +
                            "Tier: %s\n" +
                            "Model: %s\n" +
                            "Android API: %d\n" +
                            "RAM: %dMB total, %dMB available\n" +
                            "CPU: %d cores, max frequency: %d MHz",
                            tier.name(),
                            Build.MODEL,
                            Build.VERSION.SDK_INT,
                            totalMem, availableMem,
                            cores, maxFreq);
    }

    @Deprecated
    public static DeviceInfo getDetailedDeviceInfo(Context context) {
        DeviceInfo info = new DeviceInfo();

        info.tier = detectDeviceTier(context);
        info.model = Build.MODEL;
        info.androidVersion = Build.VERSION.RELEASE;
        info.apiLevel = Build.VERSION.SDK_INT;

        MemoryMonitor memoryMonitor = getMemoryMonitor(context);
        info.totalMemoryMB = memoryMonitor.getTotalMemoryMB();
        info.availableMemoryMB = memoryMonitor.getAvailableMemoryMB();
        info.cpuCores = getCpuCores();
        info.maxCpuFrequency = getMaxCpuFrequency();
        info.hasBigCores = info.maxCpuFrequency > 2000;

        return info;
    }

    public enum DeviceTier {
        FLAGSHIP,
        HIGH_END,
        MID_RANGE,
        BUDGET
    }

    @Deprecated
    public static class DeviceInfo {
        public DeviceTier tier;
        public String model;
        public String androidVersion;
        public int apiLevel;
        public long totalMemoryMB;
        public long availableMemoryMB;
        public int cpuCores;
        public int maxCpuFrequency;
        public boolean hasBigCores;

        @Override
        public String toString() {
            return getDeviceInfo(null);
        }
    }
}