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
import org.json.JSONObject;
import com.oilquiz.app.ai.jni.LlamaHelper;

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
            String nativeGpuInfoJson = LlamaHelper.detectGPUInfo();
            if (nativeGpuInfoJson != null && !nativeGpuInfoJson.isEmpty() && !nativeGpuInfoJson.equals("{}")) {
                try {
                    JSONObject json = new JSONObject(nativeGpuInfoJson);
                    
                    String name = json.optString("name", "Unknown GPU");
                    String vendor = json.optString("vendor", "Unknown");
                    String version = json.optString("version", "1.0");
                    String vulkanVersion = json.optString("vulkanVersion", "1.1");
                    long globalMemoryMB = json.optLong("globalMemoryMB", 0);
                    long maxMemory = json.optLong("maxMemory", 0);
                    int maxComputeUnits = json.optInt("maxComputeUnits", 0);
                    int maxFrequencyMHz = json.optInt("maxFrequencyMHz", 0);
                    boolean supportsFP16 = json.optBoolean("supportsFP16", false);
                    boolean supportsVulkan = json.optBoolean("supportsVulkan", false);
                    
                    MemoryUsageInfo memoryInfo = getMemoryUsageInfo();
                    
                    Log.i(TAG, "GPU info from Native: name=" + name + ", vendor=" + vendor + 
                          ", FP16=" + supportsFP16 + ", Vulkan=" + supportsVulkan +
                          ", memory=" + globalMemoryMB + "MB");
                    
                    return new GpuInfo(
                        name, 
                        vendor, 
                        version, 
                        vulkanVersion,
                        memoryInfo.availableMemoryMB, 
                        memoryInfo.totalMemoryMB, 
                        maxComputeUnits, 
                        maxFrequencyMHz,
                        vulkanVersion, 
                        supportsVulkan, 
                        supportsFP16
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse Native GPU info JSON", e);
                }
            }
            
            String renderer = detectRenderer();
            String vendor = detectVendor();
            String version = detectGpuVersion();
            GpuProfile gpuProfile = gpuDatabase.getProfile(renderer);
            String detectedVulkanVersion = detectVulkanVersionFromSystem();
            String apiVersion = detectedVulkanVersion != null ? detectedVulkanVersion :
                (gpuProfile != null ? gpuProfile.vulkanVersion : "1.1");
            MemoryUsageInfo memoryInfo = getMemoryUsageInfo();
            boolean vulkanSupport = checkVulkanSupportWithoutProfile();
            
            boolean fp16Support = false;
            if (gpuProfile != null) {
                fp16Support = gpuProfile.supportsFP16;
            } else {
                if (renderer != null) {
                    if (renderer.contains("Adreno 7") || renderer.contains("A7xx")) {
                        fp16Support = true;
                    }
                }
            }

            return new GpuInfo(renderer, vendor, version, apiVersion,
                memoryInfo.availableMemoryMB, memoryInfo.totalMemoryMB, 0, 0,
                apiVersion, vulkanSupport, fp16Support);
        } catch (Exception e) {
            Log.e(TAG, "Failed to detect GPU info", e);
            return null;
        }
    }

    public static class OpenCLInfo {
        public final String deviceName;
        public final String vendor;
        public final String openclVersion;
        public final long globalMemoryMB;
        public final int maxComputeUnits;
        public final int maxFrequencyMHz;
        public final boolean supportsFP16;
        public final boolean isAdreno;

        public OpenCLInfo(String deviceName, String vendor, String openclVersion,
                         long globalMemoryMB, int maxComputeUnits, int maxFrequencyMHz,
                         boolean supportsFP16, boolean isAdreno) {
            this.deviceName = deviceName;
            this.vendor = vendor;
            this.openclVersion = openclVersion;
            this.globalMemoryMB = globalMemoryMB;
            this.maxComputeUnits = maxComputeUnits;
            this.maxFrequencyMHz = maxFrequencyMHz;
            this.supportsFP16 = supportsFP16;
            this.isAdreno = isAdreno;
        }
    }

    public OpenCLInfo detectOpenCLInfo() {
        try {
            String nativeGpuInfoJson = LlamaHelper.detectGPUInfo();
            if (nativeGpuInfoJson == null || nativeGpuInfoJson.isEmpty() || nativeGpuInfoJson.equals("{}")) {
                return null;
            }

            org.json.JSONObject json = new org.json.JSONObject(nativeGpuInfoJson);

            String name = json.optString("name", "Unknown GPU");
            String vendor = json.optString("vendor", "Unknown");
            String openclVersion = json.optString("openclVersion", "");
            String version = json.optString("version", "");
            long globalMemoryMB = json.optLong("globalMemoryMB", 0);
            int maxComputeUnits = json.optInt("maxComputeUnits", 0);
            int maxFrequencyMHz = json.optInt("maxFrequencyMHz", 0);
            boolean supportsFP16 = json.optBoolean("supportsFP16", false);
            boolean isAdreno = json.optBoolean("isAdreno", false);

            if (openclVersion.isEmpty() && version.contains("OpenCL")) {
                openclVersion = version;
            }

            if (openclVersion.isEmpty()) {
                return null;
            }

            Log.i(TAG, "OpenCL detected: " + name + ", version: " + openclVersion);
            return new OpenCLInfo(name, vendor, openclVersion, globalMemoryMB, maxComputeUnits,
                    maxFrequencyMHz, supportsFP16, isAdreno);
        } catch (Exception e) {
            Log.e(TAG, "Failed to detect OpenCL info: " + e.getMessage());
            return null;
        }
    }

    public boolean isOpenCLAvailable() {
        if (LlamaHelper.isLibraryLoaded()) {
            if (LlamaHelper.isOpenCLLoaded()) {
                OpenCLInfo info = detectOpenCLInfo();
                if (info != null) {
                    Log.i(TAG, "OpenCL is available on device: " + info.deviceName);
                    return true;
                }
            }
        }
        return false;
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
        GpuProfile nativeProfile = getGpuProfileFromNative();
        if (nativeProfile != null) {
            Log.i(TAG, "Using GPU profile from Native layer detection: " + nativeProfile.gpuName);
            return nativeProfile;
        }
        
        GpuInfo gpuInfo = detectGpuInfo();
        if (gpuInfo == null) return null;
        return gpuDatabase.getProfile(gpuInfo.renderer);
    }
    
    private GpuProfile getGpuProfileFromNative() {
        try {
            String gpuInfoJson = LlamaHelper.detectGPUInfo();
            if (gpuInfoJson == null || gpuInfoJson.isEmpty() || gpuInfoJson.equals("{}")) {
                return null;
            }
            
            JSONObject json = new JSONObject(gpuInfoJson);
            
            String name = json.optString("name", "Unknown GPU");
            String vendor = json.optString("vendor", "Unknown");
            String version = json.optString("version", "1.0");
            String vulkanVersion = json.optString("vulkanVersion", "1.1");
            long globalMemoryMB = json.optLong("globalMemoryMB", 0);
            int maxComputeUnits = json.optInt("maxComputeUnits", 0);
            int maxFrequencyMHz = json.optInt("maxFrequencyMHz", 0);
            boolean supportsFP16 = json.optBoolean("supportsFP16", false);
            boolean isAdreno = json.optBoolean("isAdreno", false);
            
            GpuSeries series = detectGpuSeries(name, vendor);
            String architecture = detectArchitecture(name, series);
            
            float peakTFLOPS = estimatePeakPerformance(name, maxComputeUnits, maxFrequencyMHz);
            int memoryBandwidthGBps = estimateMemoryBandwidth(name, globalMemoryMB);
            int l2CacheMB = estimateL2Cache(name, maxComputeUnits);
            
            boolean supportsTensorCores = detectTensorCores(name, series);
            boolean supportsAsyncCompute = detectAsyncCompute(series);
            
            GpuConfig recommendedConfig = generateRecommendedConfig(
                series, 
                globalMemoryMB, 
                supportsFP16, 
                supportsTensorCores
            );
            
            return new GpuProfile(
                name,
                series,
                architecture,
                vulkanVersion,
                maxComputeUnits,
                peakTFLOPS,
                memoryBandwidthGBps,
                l2CacheMB,
                supportsFP16,
                supportsTensorCores,
                supportsAsyncCompute,
                recommendedConfig
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create GPU profile from Native: " + e.getMessage(), e);
            return null;
        }
    }
    
    private GpuSeries detectGpuSeries(String name, String vendor) {
        String lowerName = name.toLowerCase();
        String lowerVendor = vendor.toLowerCase();
        
        if (lowerName.contains("adreno") || lowerVendor.contains("qualcomm")) {
            if (lowerName.contains("750") || lowerName.contains("740") || 
                lowerName.contains("730") || lowerName.contains("720") || 
                lowerName.contains("710")) {
                return GpuSeries.ADRENO_700;
            } else if (lowerName.contains("6")) {
                return GpuSeries.ADRENO_600;
            } else if (lowerName.contains("5")) {
                return GpuSeries.ADRENO_500;
            }
        }
        
        if (lowerName.contains("immortalis") || lowerName.contains("g715")) {
            return GpuSeries.IMMORTALIS_G700;
        }
        if (lowerName.contains("mali-g7")) {
            return GpuSeries.MALI_G700;
        }
        if (lowerName.contains("mali-g6")) {
            return GpuSeries.MALI_G600;
        }
        if (lowerName.contains("mali-g5")) {
            return GpuSeries.MALI_G500;
        }
        
        return GpuSeries.UNKNOWN;
    }
    
    private String detectArchitecture(String name, GpuSeries series) {
        switch (series) {
            case ADRENO_700: return "A7xx";
            case ADRENO_600: return "A6xx";
            case ADRENO_500: return "A5xx";
            case IMMORTALIS_G700: return "Valhall 5th Gen";
            case MALI_G700:
            case MALI_G600:
            case MALI_G500:
                return "Valhall";
            default:
                return "Unknown";
        }
    }
    
    private float estimatePeakPerformance(String name, int computeUnits, int maxFreqMHz) {
        float frequencyGHz = maxFreqMHz > 0 ? maxFreqMHz / 1000.0f : 1.0f;
        
        if (name.contains("Adreno 750")) return 5.53f;
        if (name.contains("Adreno 740")) return 4.5f;
        if (name.contains("Adreno 730")) return 3.5f;
        if (name.contains("Adreno 720")) return 2.5f;
        if (name.contains("Adreno 710")) return 1.8f;
        if (name.contains("Adreno 650")) return 2.0f;
        if (name.contains("Adreno 640")) return 1.5f;
        if (name.contains("Adreno 630")) return 1.0f;
        
        if (computeUnits > 0 && maxFreqMHz > 0) {
            return computeUnits * frequencyGHz * 0.5f;
        }
        
        return 1.0f;
    }
    
    private int estimateMemoryBandwidth(String name, long memoryMB) {
        if (name.contains("Adreno 750")) return 35;
        if (name.contains("Adreno 740")) return 30;
        if (name.contains("Adreno 730")) return 25;
        if (name.contains("Adreno 720")) return 20;
        if (name.contains("Adreno 650")) return 20;
        if (name.contains("Adreno 640")) return 15;
        
        if (memoryMB >= 8192) return 30;
        if (memoryMB >= 4096) return 20;
        if (memoryMB >= 2048) return 15;
        
        return 10;
    }
    
    private int estimateL2Cache(String name, int computeUnits) {
        if (name.contains("Adreno 750")) return 4;
        if (name.contains("Adreno 740")) return 3;
        if (name.contains("Adreno 730")) return 2;
        if (name.contains("Adreno 650")) return 2;
        
        if (computeUnits >= 8) return 2;
        
        return 1;
    }
    
    private boolean detectTensorCores(String name, GpuSeries series) {
        if (series == GpuSeries.ADRENO_700) {
            return true;
        }
        if (series == GpuSeries.IMMORTALIS_G700) {
            return true;
        }
        return name.contains("7") || name.contains("Immortalis");
    }
    
    private boolean detectAsyncCompute(GpuSeries series) {
        return series == GpuSeries.ADRENO_700 || 
               series == GpuSeries.IMMORTALIS_G700 ||
               series == GpuSeries.MALI_G700;
    }
    
    private GpuConfig generateRecommendedConfig(
            GpuSeries series, 
            long globalMemoryMB, 
            boolean supportsFP16, 
            boolean supportsTensorCores) {
        
        int gpuLayers = calculateAutoGpuLayers(globalMemoryMB);
        int batchSize = calculateBatchSize(globalMemoryMB, supportsFP16);
        
        boolean isHighEndGPU = series == GpuSeries.ADRENO_700 || 
                               series == GpuSeries.IMMORTALIS_G700;
        
        boolean useFp16 = supportsFP16;
        boolean enableTensorCores = supportsTensorCores && isHighEndGPU;
        boolean enableAsyncCompute = isHighEndGPU;
        
        return new GpuConfig(
            gpuLayers,
            batchSize,
            gpuLayers > 0,
            useFp16,
            true,
            enableTensorCores,
            true,
            enableAsyncCompute,
            1024,
            65536,
            !isHighEndGPU,
            false
        );
    }
    
    private int calculateAutoGpuLayers(long globalMemoryMB) {
        if (globalMemoryMB < 512) return 0;
        if (globalMemoryMB >= 6144) return 99;
        
        double gpuMemGB = globalMemoryMB / 1024.0;
        int layers = (int) ((gpuMemGB / 6.0) * 99.0);
        return Math.max(8, layers);
    }
    
    private int calculateBatchSize(long globalMemoryMB, boolean supportsFP16) {
        int multiplier = supportsFP16 ? 2 : 1;
        
        if (globalMemoryMB >= 8192) return 512 * multiplier;
        if (globalMemoryMB >= 4096) return 256 * multiplier;
        if (globalMemoryMB >= 2048) return 128 * multiplier;
        
        return 64;
    }

    public GpuConfig getOptimalConfig(long modelSizeMB) {
        GpuProfile gpuProfile = getGpuProfile();
        if (gpuProfile != null) {
            return gpuDatabase.getConfigForModel(gpuProfile, modelSizeMB);
        }
        return GpuConfig.conservative();
    }

    public GpuTier getGpuTier() {
        GpuProfile nativeProfile = getGpuProfileFromNative();
        if (nativeProfile != null) {
            return getTierFromSeries(nativeProfile.series);
        }
        
        GpuInfo gpuInfo = detectGpuInfo();
        if (gpuInfo == null) return GpuTier.UNKNOWN;
        return gpuDatabase.getTier(gpuInfo.renderer);
    }
    
    private GpuTier getTierFromSeries(GpuSeries series) {
        if (series == GpuSeries.ADRENO_700 || series == GpuSeries.IMMORTALIS_G700) {
            return GpuTier.HIGH;
        } else if (series == GpuSeries.ADRENO_600 || series == GpuSeries.MALI_G700) {
            return GpuTier.MID;
        } else if (series == GpuSeries.UNKNOWN) {
            return GpuTier.UNKNOWN;
        }
        return GpuTier.LOW;
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