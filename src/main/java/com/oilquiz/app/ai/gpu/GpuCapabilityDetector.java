package com.oilquiz.app.ai.gpu;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

public class GpuCapabilityDetector {
    private static final String TAG = "GpuCapabilityDetector";

    private final Context context;
    private final ActivityManager activityManager;
    private final GpuDatabase gpuDatabase;

    public GpuCapabilityDetector(Context context) {
        this.context = context.getApplicationContext();
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        this.gpuDatabase = GpuDatabase.getInstance();
    }

    public GpuInfo detectGpuInfo() {
        try {
            String renderer = detectRenderer();
            String vendor = detectVendor();
            String version = detectGpuVersion();
            GpuProfile gpuProfile = gpuDatabase.getProfile(renderer);
            String detectedVulkanVersion = detectVulkanVersionFromSystem();
            String apiVersion = detectedVulkanVersion != null ? detectedVulkanVersion :
                (gpuProfile != null ? gpuProfile.vulkanVersion : "1.1");
            MemoryUsageInfo memoryInfo = getMemoryUsageInfo();
            boolean vulkanSupport = checkVulkanSupportWithoutProfile();
            boolean fp16Support = gpuProfile != null && gpuProfile.supportsFP16;

            return new GpuInfo(renderer, vendor, version, apiVersion,
                memoryInfo.availableMemoryMB, memoryInfo.totalMemoryMB, 0, 0,
                apiVersion, vulkanSupport, fp16Support);
        } catch (Exception e) {
            Log.e(TAG, "Failed to detect GPU info", e);
            return null;
        }
    }

    public VulkanInfo detectVulkanInfo() {
        try {
            GpuInfo gpuInfo = detectGpuInfo();
            if (gpuInfo == null) return null;

            GpuProfile gpuProfile = gpuDatabase.getProfile(gpuInfo.renderer);

            return new VulkanInfo(
                gpuProfile != null ? gpuProfile.vulkanVersion : gpuInfo.apiVersion,
                String.valueOf(gpuInfo.driverVersion),
                gpuInfo.renderer,
                0x5143,
                gpuInfo.deviceId,
                1024,
                65536,
                gpuInfo.supportsFP16
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to detect Vulkan info", e);
            return null;
        }
    }

    public GpuProfile getGpuProfile() {
        GpuInfo gpuInfo = detectGpuInfo();
        if (gpuInfo == null) return null;
        return gpuDatabase.getProfile(gpuInfo.renderer);
    }

    public GpuConfig getOptimalConfig(long modelSizeMB) {
        GpuProfile gpuProfile = getGpuProfile();
        if (gpuProfile != null) {
            return gpuDatabase.getConfigForModel(gpuProfile, modelSizeMB);
        }
        return GpuConfig.conservative();
    }

    public GpuTier getGpuTier() {
        GpuInfo gpuInfo = detectGpuInfo();
        if (gpuInfo == null) return GpuTier.UNKNOWN;
        return gpuDatabase.getTier(gpuInfo.renderer);
    }

    public BenchmarkResult runBenchmark() {
        GpuInfo gpuInfo = detectGpuInfo();
        if (gpuInfo == null) return new BenchmarkResult();

        GpuProfile gpuProfile = getGpuProfile();

        return new BenchmarkResult(
            gpuInfo.renderer,
            getGpuTier(),
            gpuInfo.supportsVulkan,
            gpuInfo.supportsFP16,
            gpuInfo.availableMemoryMB,
            gpuProfile != null ? gpuProfile.peakPerformanceTFLOPS : 0f,
            gpuProfile != null ? gpuProfile.recommendedConfig.gpuLayers : 0
        );
    }

    private String detectRenderer() {
        try {
            File file = new File("/sys/class/kgsl/kgsl-3d0/device/model");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String renderer = reader.readLine();
                reader.close();
                if (renderer != null) return renderer.trim();
            }
        } catch (Exception e) {
        }

        try {
            File gpuInfoFile = new File("/proc/pu/0/gpuinfo");
            if (gpuInfoFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(gpuInfoFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("GPU model") || line.startsWith("model name")) {
                        reader.close();
                        int colonIndex = line.indexOf(":");
                        if (colonIndex >= 0) {
                            return line.substring(colonIndex + 1).trim();
                        }
                    }
                }
                reader.close();
            }
        } catch (Exception e) {
        }

        return Build.HARDWARE;
    }

    private String detectVendor() {
        String renderer = detectRenderer();
        if (renderer.contains("Adreno")) return "Qualcomm";
        if (renderer.contains("Mali")) return "ARM";
        if (renderer.contains("Immortalis")) return "ARM";
        if (renderer.contains("PowerVR")) return "Imagination";
        if (renderer.contains("Vivante")) return "VeriSilicon";
        if (renderer.contains("Xclipse")) return "Samsung";
        return "Unknown";
    }

    private String detectGpuVersion() {
        try {
            File file = new File("/sys/class/kgsl/kgsl-3d0/device/driver_version");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String version = reader.readLine();
                reader.close();
                if (version != null) return version.trim();
            }
        } catch (Exception e) {
        }
        return "Unknown";
    }

    private String detectApiVersion() {
        try {
            String renderer = detectRenderer();
            GpuProfile gpuProfile = gpuDatabase.getProfile(renderer);
            if (gpuProfile != null) {
                return gpuProfile.vulkanVersion;
            }
        } catch (Exception e) {
        }
        return "1.1";
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
            getMemoryUsagePercent(),
            javaHeapUsed,
            javaHeapMax,
            memoryInfo.lowMemory
        );
    }

    private float getMemoryUsagePercent() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long used = memoryInfo.totalMem - memoryInfo.availMem;
        return (used * 100f) / memoryInfo.totalMem;
    }

    private boolean checkVulkanSupportWithoutProfile() {
        try {
            File vulkanDir = new File("/sys/class/vulkan");
            if (vulkanDir.exists() && vulkanDir.listFiles() != null && vulkanDir.listFiles().length > 0) {
                return true;
            }
        } catch (Exception e) {
        }

        try {
            File vulkanLib64 = new File("/system/lib64/libvulkan.so");
            File vulkanLib32 = new File("/system/lib/libvulkan.so");
            if (vulkanLib64.exists() || vulkanLib32.exists()) {
                Log.i(TAG, "Vulkan support detected via system library");
                return true;
            }
        } catch (Exception e) {
        }

        try {
            File vulkanLibVendor64 = new File("/vendor/lib64/libvulkan.so");
            File vulkanLibVendor32 = new File("/vendor/lib/libvulkan.so");
            if (vulkanLibVendor64.exists() || vulkanLibVendor32.exists()) {
                Log.i(TAG, "Vulkan support detected via vendor library");
                return true;
            }
        } catch (Exception e) {
        }

        try {
            Process process = Runtime.getRuntime().exec("getprop ro.hardware.vulkan.version");
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            if (line != null && !line.isEmpty() && !line.equals("0")) {
                Log.i(TAG, "Vulkan support detected via system property: " + line);
                return true;
            }
        } catch (Exception e) {
        }

        try {
            String renderer = detectRenderer();
            GpuProfile gpuProfile = gpuDatabase.getProfile(renderer);
            if (gpuProfile != null && gpuProfile.vulkanVersion != null && !gpuProfile.vulkanVersion.isEmpty()) {
                Log.i(TAG, "Vulkan support detected via GPU profile for: " + renderer);
                return true;
            }
        } catch (Exception e) {
        }

        return false;
    }

    private String detectVulkanVersionFromSystem() {
        try {
            String[] versionPaths = {
                "/sys/class/vulkan/version",
                "/sys/class/kgsl/kgsl-3d0/device/vulkan_version",
                "/system/vendor/lib/libvulkan.so"
            };

            for (String path : versionPaths) {
                File file = new File(path);
                if (file.exists() && !file.isDirectory()) {
                    try {
                        BufferedReader reader = new BufferedReader(new FileReader(file));
                        String line = reader.readLine();
                        reader.close();
                        if (line != null) {
                            String version = parseVulkanVersionString(line.trim());
                            if (version != null) {
                                Log.i(TAG, "Detected Vulkan version from " + path + ": " + version);
                                return version;
                            }
                        }
                    } catch (Exception e) {
                        // continue
                    }
                }
            }

            try {
                Process process = Runtime.getRuntime().exec("getprop ro.hardware.vulkan.version");
                BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.isEmpty()) {
                    String version = parseVulkanVersionString(line.trim());
                    if (version != null) return version;
                }
            } catch (Exception e) {
                // continue
            }

            try {
                Process process = Runtime.getRuntime().exec("getprop ro.vulkan.api");
                BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.isEmpty()) {
                    String version = parseVulkanVersionString(line.trim());
                    if (version != null) return version;
                }
            } catch (Exception e) {
                // continue
            }

            try {
                String renderer = detectRenderer();
                GpuProfile gpuProfile = gpuDatabase.getProfile(renderer);
                if (gpuProfile != null) {
                    return gpuProfile.vulkanVersion;
                }
            } catch (Exception e) {
                // fallback
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to detect Vulkan version from system", e);
        }
        return null;
    }

    private String parseVulkanVersionString(String versionStr) {
        if (versionStr == null || versionStr.isEmpty()) return null;

        try {
            if (versionStr.contains("1.3")) return "1.3";
            if (versionStr.contains("1.2")) return "1.2";
            if (versionStr.contains("1.1")) return "1.1";
            if (versionStr.contains("1.0")) return "1.0";

            if (versionStr.matches("\\d+")) {
                int versionInt = Integer.parseInt(versionStr);
                int major = (versionInt >> 22) & 0x7F;
                int minor = (versionInt >> 12) & 0x3FF;
                return major + "." + minor;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private boolean checkVulkanSupport() {
        try {
            File vulkanDir = new File("/sys/class/vulkan");
            boolean hasVulkanDir = vulkanDir.exists() && vulkanDir.listFiles() != null && vulkanDir.listFiles().length > 0;
            if (hasVulkanDir) {
                return true;
            }
            // 如果没有目录，才检查profile
            try {
                GpuInfo gpuInfo = new GpuInfo(detectRenderer(), detectVendor(), detectGpuVersion(), "1.1", 0, 0, 0, 0, "1.0", false, false);
                GpuProfile gpuProfile = gpuDatabase.getProfile(gpuInfo.renderer);
                return gpuProfile != null && !gpuProfile.vulkanVersion.isEmpty();
            } catch (Exception e2) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkFP16Support() {
        try {
            String renderer = detectRenderer();
            GpuProfile gpuProfile = gpuDatabase.getProfile(renderer);
            return gpuProfile != null && gpuProfile.supportsFP16;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, String> getVulkanDeviceProperties() {
        Map<String, String> props = new HashMap<>();
        try {
            File vulkanDir = new File("/sys/class/vulkan");
            if (vulkanDir.exists() && vulkanDir.listFiles() != null) {
                for (File device : vulkanDir.listFiles()) {
                    if (device.isDirectory()) {
                        String name = device.getName();
                        File deviceNameFile = new File(device, "device/name");
                        File driverVersionFile = new File(device, "device/driver_version");

                        if (deviceNameFile.exists()) {
                            BufferedReader reader = new BufferedReader(new FileReader(deviceNameFile));
                            String line = reader.readLine();
                            reader.close();
                            if (line != null) props.put(name + "/device_name", line);
                        }

                        if (driverVersionFile.exists()) {
                            BufferedReader reader = new BufferedReader(new FileReader(driverVersionFile));
                            String line = reader.readLine();
                            reader.close();
                            if (line != null) props.put(name + "/driver_version", line);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get Vulkan device properties", e);
        }
        return props;
    }

    public long getAvailableGpuMemoryMB() {
        try {
            File gpuMemoryFile = new File("/sys/class/kgsl/kgsl-3d0/device/gpu_memory");
            if (gpuMemoryFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(gpuMemoryFile));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    long value = Long.parseLong(line.trim());
                    return value / 1024;
                }
            }
        } catch (Exception e) {
        }
        return 0;
    }

    public float getGpuUtilization() {
        try {
            File usageFile = new File("/sys/class/kgsl/kgsl-3d0/device/gpu_busy_percentage");
            if (usageFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(usageFile));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    float value = Float.parseFloat(line.trim());
                    return value / 100f;
                }
            }
        } catch (Exception e) {
        }
        return 0f;
    }

    public int[] getGpuFrequency() {
        try {
            File curFreqFile = new File("/sys/class/kgsl/kgsl-3d0/device/gpuclk");
            File maxFreqFile = new File("/sys/class/kgsl/kgsl-3d0/device/max_gpuclk");

            int curFreq = 0;
            int maxFreq = 0;

            if (curFreqFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(curFreqFile));
                String line = reader.readLine();
                reader.close();
                if (line != null) curFreq = Integer.parseInt(line.trim());
            }

            if (maxFreqFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(maxFreqFile));
                String line = reader.readLine();
                reader.close();
                if (line != null) maxFreq = Integer.parseInt(line.trim());
            } else {
                maxFreq = curFreq;
            }

            return new int[] { curFreq, maxFreq };
        } catch (Exception e) {
            return new int[] { 0, 0 };
        }
    }

    public int getTemperature() {
        try {
            File thermalDir = new File("/sys/class/thermal");
            if (thermalDir.exists() && thermalDir.listFiles() != null) {
                for (File zone : thermalDir.listFiles()) {
                    if (zone.getName().contains("gpu") || zone.getName().contains("thermal_zone")) {
                        File tempFile = new File(zone, "temp");
                        if (tempFile.exists()) {
                            BufferedReader reader = new BufferedReader(new FileReader(tempFile));
                            String line = reader.readLine();
                            reader.close();
                            if (line != null) {
                                return Integer.parseInt(line.trim());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return 0;
    }
}