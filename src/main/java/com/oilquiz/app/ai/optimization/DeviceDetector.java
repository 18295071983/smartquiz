package com.oilquiz.app.ai.optimization;

import android.os.Build;
import android.util.Log;

import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class DeviceDetector {
    private static final String TAG = "DeviceDetector";
    
    private static String cachedBrand = null;
    private static String cachedModel = null;
    private static String cachedDevice = null;
    private static String cachedHardware = null;
    private static String cachedGPU = null;
    
    static {
        // 缓存设备信息
        cachedBrand = Build.BRAND;
        cachedModel = Build.MODEL;
        cachedDevice = Build.DEVICE;
        cachedHardware = Build.HARDWARE;
    }

    /**
     * 检测设备品牌
     */
    public static String getDeviceBrand() {
        return cachedBrand;
    }

    /**
     * 检测设备型号
     */
    public static String getDeviceModel() {
        return cachedModel;
    }

    /**
     * 检测设备名称
     */
    public static String getDeviceName() {
        return cachedDevice;
    }
    
    /**
     * 获取设备硬件信息
     */
    public static String getHardware() {
        return cachedHardware;
    }
    
    /**
     * 获取Android版本
     */
    public static int getAndroidVersion() {
        return Build.VERSION.SDK_INT;
    }
    
    /**
     * 获取ABI列表
     */
    public static String getSupportedABIs() {
        StringBuilder sb = new StringBuilder();
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (int i = 0; i < abis.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(abis[i]);
            }
        }
        return sb.toString();
    }

    /**
     * 检测是否为小米设备
     */
    public static boolean isXiaomiDevice() {
        String brand = getDeviceBrand();
        if (brand == null) return false;
        brand = brand.toLowerCase();
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco");
    }

    /**
     * 检测是否为小米14系列
     */
    public static boolean isXiaomi14Series() {
        if (!isXiaomiDevice()) {
            return false;
        }
        String model = getDeviceModel();
        String device = getDeviceName();
        String hardware = getHardware();
        
        if (model == null && device == null && hardware == null) {
            return false;
        }
        
        model = (model != null) ? model.toLowerCase() : "";
        device = (device != null) ? device.toLowerCase() : "";
        hardware = (hardware != null) ? hardware.toLowerCase() : "";
        
        // 扩展小米14系列识别
        return model.contains("xiaomi 14") || 
               model.contains("mi 14") ||
               model.contains("m2312") ||
               model.contains("14") && model.contains("ultra") ||
               model.contains("14") && model.contains("pro") ||
               device.contains("xiaomi14") ||
               device.contains("xun") ||
               device.contains("2312") ||
               hardware.contains("xun") ||
               hardware.contains("2312") ||
               device.equals("23127pn0cc") ||
               device.equals("23128pn0dc") ||
               device.equals("23127pn0ce") ||
               device.equals("23128pn0dd");
    }

    /**
     * 获取GPU型号
     */
    public static String getGPUModel() {
        if (cachedGPU != null) {
            return cachedGPU;
        }
        
        try {
            // 尝试多种方式获取GPU信息
            String[] gpuProps = {
                "ro.product.board",
                "ro.hardware",
                "gpu.renderer",
                "ro.vendor.gpu.renderer",
                "ro.vendor.product.board",
                "ro.vendor.hardware"
            };
            
            for (String prop : gpuProps) {
                String value = SystemProperties.get(prop, "");
                if (value != null && !value.isEmpty()) {
                    cachedGPU = value;
                    return cachedGPU;
                }
            }
            
            // 检查Build.HARDWARE
            String hardwareInfo = Build.HARDWARE;
            if (hardwareInfo != null && !hardwareInfo.isEmpty()) {
                cachedGPU = hardwareInfo;
                return cachedGPU;
            }
            
            // 检查Build.BOARD
            String boardInfo = Build.BOARD;
            if (boardInfo != null && !boardInfo.isEmpty()) {
                cachedGPU = boardInfo;
                return cachedGPU;
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to get GPU model via system properties: " + e.getMessage());
        }
        
        cachedGPU = getGPUFromCpuInfo();
        return (cachedGPU != null) ? cachedGPU : "Unknown";
    }
    
    /**
     * 从/proc/cpuinfo获取GPU信息
     */
    private static String getGPUFromCpuInfo() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("Hardware")) {
                    br.close();
                    String[] parts = line.split(":");
                    if (parts.length >= 2) {
                        return parts[1].trim();
                    }
                }
                if (line.contains("model name")) {
                    br.close();
                    String[] parts = line.split(":");
                    if (parts.length >= 2) {
                        return parts[1].trim();
                    }
                }
            }
            br.close();
        } catch (IOException e) {
            AILogger.w(TAG, "Failed to read /proc/cpuinfo: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 系统属性工具类
     */
    private static class SystemProperties {
        public static String get(String key, String defaultValue) {
            try {
                Class<?> clazz = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method method = clazz.getMethod("get", String.class, String.class);
                return (String) method.invoke(null, key, defaultValue);
            } catch (Exception e) {
                return defaultValue;
            }
        }
    }

    /**
     * 检测是否为Adreno GPU
     */
    public static boolean isAdrenoGPU() {
        String gpuModel = getGPUModel();
        if (gpuModel == null) return false;
        gpuModel = gpuModel.toLowerCase();
        
        // 检查常见的Adreno标识
        if (gpuModel.contains("adreno") || gpuModel.contains("qualcomm")) {
            return true;
        }
        
        // 检查骁龙处理器型号
        String model = getDeviceModel();
        if (model != null) {
            model = model.toLowerCase();
            if (model.contains("snapdragon") || model.contains("sd8") || model.contains("sd7") || model.contains("sd6")) {
                return true;
            }
        }
        
        // 检查硬件信息
        String hardware = getHardware();
        if (hardware != null) {
            hardware = hardware.toLowerCase();
            if (hardware.contains("qcom") || hardware.contains("msm") || hardware.contains("sm8")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 获取Adreno GPU系列
     */
    public static String getAdrenoSeries() {
        String gpuModel = getGPUModel();
        if (gpuModel == null) return "UNKNOWN";
        
        if (gpuModel.contains("Adreno 7") || gpuModel.contains("750") || gpuModel.contains("740")) {
            return "A7XX";
        } else if (gpuModel.contains("Adreno 6") || gpuModel.contains("660") || gpuModel.contains("650") || gpuModel.contains("730")) {
            return "A6XX";
        } else if (gpuModel.contains("Adreno 5") || gpuModel.contains("620") || gpuModel.contains("615")) {
            return "A5XX";
        } else if (gpuModel.contains("Adreno 4") || gpuModel.contains("430")) {
            return "A4XX";
        }
        return "UNKNOWN";
    }
    
    /**
     * 获取设备内存总量（MB）
     */
    public static long getTotalMemoryMB() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal")) {
                    br.close();
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) / 1024;
                    }
                }
            }
            br.close();
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to get total memory: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * 获取设备可用内存总量（MB）
     */
    public static long getFreeMemoryMB() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemAvailable")) {
                    br.close();
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) / 1024;
                    }
                }
            }
            br.close();
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to get free memory: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * 获取CPU核心数
     */
    public static int getCPUCores() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * 获取设备信息摘要
     */
    public static String getDeviceInfo() {
        try {
            StringBuilder info = new StringBuilder();
            info.append("Brand: ").append(getDeviceBrand() != null ? getDeviceBrand() : "Unknown").append("\n");
            info.append("Model: ").append(getDeviceModel() != null ? getDeviceModel() : "Unknown").append("\n");
            info.append("Device: ").append(getDeviceName() != null ? getDeviceName() : "Unknown").append("\n");
            info.append("Hardware: ").append(getHardware() != null ? getHardware() : "Unknown").append("\n");
            info.append("GPU: ").append(getGPUModel() != null ? getGPUModel() : "Unknown").append("\n");
            info.append("Android: ").append(getAndroidVersion()).append("\n");
            info.append("CPU Cores: ").append(getCPUCores()).append("\n");
            info.append("Total Memory: ").append(getTotalMemoryMB()).append(" MB\n");
            info.append("Free Memory: ").append(getFreeMemoryMB()).append(" MB\n");
            info.append("Is Xiaomi: ").append(isXiaomiDevice()).append("\n");
            info.append("Is Xiaomi 14: ").append(isXiaomi14Series()).append("\n");
            info.append("Is Adreno: ").append(isAdrenoGPU()).append("\n");
            if (isAdrenoGPU()) {
                info.append("Adreno Series: ").append(getAdrenoSeries()).append("\n");
            }
            info.append("Supported ABIs: ").append(getSupportedABIs() != null ? getSupportedABIs() : "Unknown").append("\n");
            String result = info.toString();
            if (result == null || result.trim().isEmpty()) {
                AILogger.w(TAG, "Device info is empty, returning fallback info");
                return "Brand: Unknown\nModel: Unknown\nDevice: Unknown\nHardware: Unknown\nGPU: Unknown\nAndroid: " + getAndroidVersion() + "\nCPU Cores: " + getCPUCores() + "\nTotal Memory: " + getTotalMemoryMB() + " MB\nFree Memory: " + getFreeMemoryMB() + " MB\nIs Xiaomi: " + isXiaomiDevice() + "\nIs Xiaomi 14: " + isXiaomi14Series() + "\nIs Adreno: " + isAdrenoGPU() + "\nSupported ABIs: Unknown\n";
            }
            return result;
        } catch (Exception e) {
            AILogger.w(TAG, "Error getting device info: " + e.getMessage());
            return "Brand: Unknown\nModel: Unknown\nDevice: Unknown\nHardware: Unknown\nGPU: Unknown\nAndroid: " + getAndroidVersion() + "\nCPU Cores: " + getCPUCores() + "\nTotal Memory: " + getTotalMemoryMB() + " MB\nFree Memory: " + getFreeMemoryMB() + " MB\nIs Xiaomi: " + isXiaomiDevice() + "\nIs Xiaomi 14: " + isXiaomi14Series() + "\nIs Adreno: " + isAdrenoGPU() + "\nSupported ABIs: Unknown\n";
        }
    }
    
    /**
     * 获取简短的设备信息（用于日志）
     */
    public static String getShortDeviceInfo() {
        try {
            StringBuilder info = new StringBuilder();
            info.append("Brand=").append(getDeviceBrand() != null ? getDeviceBrand() : "?");
            info.append(", Model=").append(getDeviceModel() != null ? getDeviceModel() : "?");
            info.append(", GPU=").append(getGPUModel() != null ? getGPUModel() : "?");
            info.append(", Cores=").append(getCPUCores());
            info.append(", Mem=").append(getTotalMemoryMB()).append("MB");
            String result = info.toString();
            if (result == null || result.trim().isEmpty()) {
                AILogger.w(TAG, "Short device info is empty, returning fallback");
                return "Brand=?, Model=?, GPU=?, Cores=" + getCPUCores() + ", Mem=" + getTotalMemoryMB() + "MB";
            }
            return result;
        } catch (Exception e) {
            AILogger.w(TAG, "Error getting short device info: " + e.getMessage());
            return "Brand=?, Model=?, GPU=?, Cores=" + getCPUCores() + ", Mem=" + getTotalMemoryMB() + "MB";
        }
    }
}
