package com.oilquiz.app.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 原生环境检测工具类
 * 提供全面的设备环境检测功能
 */
public class NativeEnvironmentChecker {

    private final Context context;

    public NativeEnvironmentChecker(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 获取完整的设备环境信息
     */
    public EnvironmentInfo getFullEnvironmentInfo() {
        EnvironmentInfo info = new EnvironmentInfo();

        // 设备信息
        info.deviceInfo = getDeviceInfo();

        // 系统信息
        info.systemInfo = getSystemInfo();

        // 屏幕信息
        info.screenInfo = getScreenInfo();

        // 内存信息
        info.memoryInfo = getMemoryInfo();

        // 存储信息
        info.storageInfo = getStorageInfo();

        // 应用信息
        info.appInfo = getAppInfo();

        // 运行时信息
        info.runtimeInfo = getRuntimeInfo();

        // 硬件信息
        info.hardwareInfo = getHardwareInfo();

        // WebView信息
        info.webViewInfo = getWebViewInfo();

        // 权限信息
        info.permissionInfo = getPermissionInfo();

        return info;
    }

    /**
     * 获取设备信息
     */
    public DeviceInfo getDeviceInfo() {
        DeviceInfo info = new DeviceInfo();
        info.manufacturer = Build.MANUFACTURER;
        info.brand = Build.BRAND;
        info.model = Build.MODEL;
        info.device = Build.DEVICE;
        info.product = Build.PRODUCT;
        info.hardware = Build.HARDWARE;
        info.board = Build.BOARD;
        info.bootloader = Build.BOOTLOADER;
        info.fingerprint = Build.FINGERPRINT;
        info.serial = Build.SERIAL;

        // 获取设备类型
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
            info.deviceType = "TV";
        } else if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            info.deviceType = "Watch";
        } else if (isTablet()) {
            info.deviceType = "Tablet";
        } else {
            info.deviceType = "Phone";
        }

        return info;
    }

    /**
     * 获取系统信息
     */
    public SystemInfo getSystemInfo() {
        SystemInfo info = new SystemInfo();
        info.androidVersion = Build.VERSION.RELEASE;
        info.sdkInt = Build.VERSION.SDK_INT;
        info.codename = Build.VERSION.CODENAME;
        info.incremental = Build.VERSION.INCREMENTAL;
        info.securityPatch = Build.VERSION.SECURITY_PATCH;
        info.baseOs = Build.VERSION.BASE_OS;
        info.previewSdkInt = Build.VERSION.PREVIEW_SDK_INT;

        // 语言信息
        Locale locale = context.getResources().getConfiguration().locale;
        info.language = locale.getLanguage();
        info.country = locale.getCountry();
        info.displayLanguage = locale.getDisplayLanguage();

        // 时区信息
        TimeZone timeZone = TimeZone.getDefault();
        info.timeZone = timeZone.getID();
        info.timeZoneDisplayName = timeZone.getDisplayName();

        // 系统特性
        info.isRooted = isRooted();
        info.isEmulator = isEmulator();

        return info;
    }

    /**
     * 获取屏幕信息
     */
    public ScreenInfo getScreenInfo() {
        ScreenInfo info = new ScreenInfo();

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        info.widthPixels = metrics.widthPixels;
        info.heightPixels = metrics.heightPixels;
        info.density = metrics.density;
        info.densityDpi = metrics.densityDpi;
        info.scaledDensity = metrics.scaledDensity;
        info.xdpi = metrics.xdpi;
        info.ydpi = metrics.ydpi;

        // 计算屏幕尺寸
        double x = Math.pow(metrics.widthPixels / metrics.xdpi, 2);
        double y = Math.pow(metrics.heightPixels / metrics.ydpi, 2);
        info.screenSizeInches = Math.sqrt(x + y);

        // 屏幕方向
        int orientation = context.getResources().getConfiguration().orientation;
        info.orientation = orientation == Configuration.ORIENTATION_LANDSCAPE ? "Landscape" : "Portrait";

        // 获取真实屏幕尺寸（包含导航栏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Point realSize = new Point();
            display.getRealSize(realSize);
            info.realWidthPixels = realSize.x;
            info.realHeightPixels = realSize.y;
        }

        return info;
    }

    /**
     * 获取内存信息
     */
    public MemoryInfo getMemoryInfo() {
        MemoryInfo info = new MemoryInfo();

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);

        info.totalMemory = mi.totalMem;
        info.availableMemory = mi.availMem;
        info.threshold = mi.threshold;
        info.lowMemory = mi.lowMemory;

        // 获取应用内存限制
        info.memoryClass = am.getMemoryClass();
        info.largeMemoryClass = am.getLargeMemoryClass();

        // 计算使用率
        info.usagePercent = (int) ((info.totalMemory - info.availableMemory) * 100 / info.totalMemory);

        // 运行时内存
        Runtime runtime = Runtime.getRuntime();
        info.runtimeMaxMemory = runtime.maxMemory();
        info.runtimeTotalMemory = runtime.totalMemory();
        info.runtimeFreeMemory = runtime.freeMemory();
        info.runtimeUsedMemory = info.runtimeTotalMemory - info.runtimeFreeMemory;

        return info;
    }

    /**
     * 获取存储信息
     */
    public StorageInfo getStorageInfo() {
        StorageInfo info = new StorageInfo();

        // 内部存储
        File internalPath = Environment.getDataDirectory();
        StatFs internalStat = new StatFs(internalPath.getPath());
        info.internalTotal = internalStat.getTotalBytes();
        info.internalAvailable = internalStat.getAvailableBytes();
        info.internalUsed = info.internalTotal - info.internalAvailable;

        // 外部存储
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            File externalPath = Environment.getExternalStorageDirectory();
            StatFs externalStat = new StatFs(externalPath.getPath());
            info.externalTotal = externalStat.getTotalBytes();
            info.externalAvailable = externalStat.getAvailableBytes();
            info.externalUsed = info.externalTotal - info.externalAvailable;
            info.externalMounted = true;
        } else {
            info.externalMounted = false;
        }

        // 应用专用存储
        File appFilesDir = context.getFilesDir();
        if (appFilesDir != null) {
            info.appFilesSize = getFolderSize(appFilesDir);
        }

        File appCacheDir = context.getCacheDir();
        if (appCacheDir != null) {
            info.appCacheSize = getFolderSize(appCacheDir);
        }

        return info;
    }

    /**
     * 获取应用信息
     */
    public AppEnvironmentInfo getAppInfo() {
        AppEnvironmentInfo info = new AppEnvironmentInfo();

        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = context.getApplicationInfo();

            info.packageName = context.getPackageName();
            info.appName = pm.getApplicationLabel(ai).toString();
            info.versionName = pm.getPackageInfo(context.getPackageName(), 0).versionName;
            info.versionCode = pm.getPackageInfo(context.getPackageName(), 0).versionCode;
            info.targetSdkVersion = ai.targetSdkVersion;
            info.minSdkVersion = ai.minSdkVersion;
            info.processName = ai.processName;
            info.dataDir = ai.dataDir;
            info.sourceDir = ai.sourceDir;
            info.nativeLibraryDir = ai.nativeLibraryDir;

            // 应用类型
            info.isSystemApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            info.isDebuggable = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        return info;
    }

    /**
     * 获取运行时信息
     */
    public RuntimeInfo getRuntimeInfo() {
        RuntimeInfo info = new RuntimeInfo();

        Runtime runtime = Runtime.getRuntime();
        info.availableProcessors = runtime.availableProcessors();
        info.maxMemory = runtime.maxMemory();
        info.totalMemory = runtime.totalMemory();
        info.freeMemory = runtime.freeMemory();

        // 线程信息
        info.threadCount = Thread.activeCount();

        return info;
    }

    /**
     * 获取硬件信息
     */
    public HardwareInfo getHardwareInfo() {
        HardwareInfo info = new HardwareInfo();

        // CPU 信息
        info.cpuAbi = Build.CPU_ABI;
        info.cpuAbi2 = Build.CPU_ABI2;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            info.supportedAbis = Build.SUPPORTED_ABIS;
            info.supported32BitAbis = Build.SUPPORTED_32_BIT_ABIS;
            info.supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS;
        }

        // 读取 CPU 详细信息
        info.cpuInfo = readCpuInfo();

        // 检查硬件特性
        PackageManager pm = context.getPackageManager();
        info.hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA);
        info.hasCameraFlash = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        info.hasGPS = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        info.hasNFC = pm.hasSystemFeature(PackageManager.FEATURE_NFC);
        info.hasBluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
        info.hasWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI);
        info.hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        info.hasMicrophone = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
        info.hasAccelerometer = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER);

        return info;
    }

    /**
     * 检查设备是否已 Root
     */
    private boolean isRooted() {
        String[] paths = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sbin/su",
                "/su/bin/su",
                "/su/bin",
                "/system/bin/.ext/.su"
        };

        for (String path : paths) {
            if (new File(path).exists()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查是否在模拟器上运行
     */
    private boolean isEmulator() {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator");
    }

    /**
     * 检查是否为平板
     */
    private boolean isTablet() {
        Configuration configuration = context.getResources().getConfiguration();
        int screenLayout = configuration.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenLayout >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    /**
     * 读取 CPU 信息
     */
    private String readCpuInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("processor") || line.contains("Hardware") || line.contains("model name")) {
                    sb.append(line).append("\n");
                }
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sb.toString().trim();
    }

    /**
     * 获取文件夹大小
     */
    private long getFolderSize(File file) {
        long size = 0;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    size += getFolderSize(f);
                }
            }
        } else {
            size = file.length();
        }
        return size;
    }

    /**
     * 格式化字节大小
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.getDefault(), "%.2f %sB", bytes / Math.pow(1024, exp), unit);
    }

    /**
     * 获取WebView信息
     */
    public WebViewInfo getWebViewInfo() {
        WebViewInfo info = new WebViewInfo();
        
        try {
            // 使用反射获取WebView版本
            Class<?> webViewClass = Class.forName("android.webkit.WebView");
            Object webView = webViewClass.getConstructor(Context.class).newInstance(context);
            Class<?> webViewPackageClass = Class.forName("android.webkit.WebView$WebViewPackage");
            Object webViewPackage = webViewClass.getMethod("getWebViewPackage").invoke(webView);
            if (webViewPackage != null) {
                info.versionName = (String) webViewPackageClass.getMethod("getVersionName").invoke(webViewPackage);
                info.packageName = (String) webViewPackageClass.getMethod("getPackageName").invoke(webViewPackage);
            }
            info.isAvailable = true;
        } catch (Exception e) {
            info.isAvailable = false;
            info.errorMessage = e.getMessage();
        }
        
        // 检查WebView是否启用
        try {
            Class<?> webViewFactoryClass = Class.forName("android.webkit.WebViewFactory");
            Object provider = webViewFactoryClass.getMethod("getProvider").invoke(null);
            info.isEnabled = provider != null;
        } catch (Exception e) {
            info.isEnabled = false;
        }
        
        return info;
    }

    /**
     * 获取权限信息
     */
    public PermissionInfo getPermissionInfo() {
        PermissionInfo info = new PermissionInfo();
        
        try {
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            
            // 获取应用声明的所有权限
            android.content.pm.PackageInfo packageInfo = pm.getPackageInfo(packageName, 
                    PackageManager.GET_PERMISSIONS);
            
            if (packageInfo.requestedPermissions != null) {
                info.requestedPermissions = packageInfo.requestedPermissions;
                info.requestedPermissionsCount = packageInfo.requestedPermissions.length;
            } else {
                info.requestedPermissions = new String[0];
                info.requestedPermissionsCount = 0;
            }
            
            // 检查常用权限的授权状态
            info.hasInternet = checkPermission(android.Manifest.permission.INTERNET);
            info.hasNetworkState = checkPermission(android.Manifest.permission.ACCESS_NETWORK_STATE);
            info.hasWifiState = checkPermission(android.Manifest.permission.ACCESS_WIFI_STATE);
            info.hasReadStorage = checkPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            info.hasWriteStorage = checkPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            info.hasCamera = checkPermission(android.Manifest.permission.CAMERA);
            info.hasLocation = checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
            info.hasLocationCoarse = checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION);
            info.hasPhoneState = checkPermission(android.Manifest.permission.READ_PHONE_STATE);
            info.hasRecordAudio = checkPermission(android.Manifest.permission.RECORD_AUDIO);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return info;
    }
    
    /**
     * 检查权限是否已授权
     */
    private boolean checkPermission(String permission) {
        try {
            int result = context.checkSelfPermission(permission);
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    // ========== 数据类 ==========

    public static class EnvironmentInfo {
        public DeviceInfo deviceInfo;
        public SystemInfo systemInfo;
        public ScreenInfo screenInfo;
        public MemoryInfo memoryInfo;
        public StorageInfo storageInfo;
        public AppEnvironmentInfo appInfo;
        public RuntimeInfo runtimeInfo;
        public HardwareInfo hardwareInfo;
        public WebViewInfo webViewInfo;
        public PermissionInfo permissionInfo;
    }

    public static class DeviceInfo {
        public String manufacturer;
        public String brand;
        public String model;
        public String device;
        public String product;
        public String hardware;
        public String board;
        public String bootloader;
        public String fingerprint;
        public String serial;
        public String deviceType;
    }

    public static class SystemInfo {
        public String androidVersion;
        public int sdkInt;
        public String codename;
        public String incremental;
        public String securityPatch;
        public String baseOs;
        public int previewSdkInt;
        public String language;
        public String country;
        public String displayLanguage;
        public String timeZone;
        public String timeZoneDisplayName;
        public boolean isRooted;
        public boolean isEmulator;
    }

    public static class ScreenInfo {
        public int widthPixels;
        public int heightPixels;
        public float density;
        public int densityDpi;
        public float scaledDensity;
        public float xdpi;
        public float ydpi;
        public double screenSizeInches;
        public String orientation;
        public int realWidthPixels;
        public int realHeightPixels;
    }

    public static class MemoryInfo {
        public long totalMemory;
        public long availableMemory;
        public long threshold;
        public boolean lowMemory;
        public int memoryClass;
        public int largeMemoryClass;
        public int usagePercent;
        public long runtimeMaxMemory;
        public long runtimeTotalMemory;
        public long runtimeFreeMemory;
        public long runtimeUsedMemory;
    }

    public static class StorageInfo {
        public long internalTotal;
        public long internalAvailable;
        public long internalUsed;
        public long externalTotal;
        public long externalAvailable;
        public long externalUsed;
        public boolean externalMounted;
        public long appFilesSize;
        public long appCacheSize;
    }

    public static class AppEnvironmentInfo {
        public String packageName;
        public String appName;
        public String versionName;
        public int versionCode;
        public int targetSdkVersion;
        public int minSdkVersion;
        public String processName;
        public String dataDir;
        public String sourceDir;
        public String nativeLibraryDir;
        public boolean isSystemApp;
        public boolean isDebuggable;
    }

    public static class RuntimeInfo {
        public int availableProcessors;
        public long maxMemory;
        public long totalMemory;
        public long freeMemory;
        public int threadCount;
    }

    public static class HardwareInfo {
        public String cpuAbi;
        public String cpuAbi2;
        public String[] supportedAbis;
        public String[] supported32BitAbis;
        public String[] supported64BitAbis;
        public String cpuInfo;
        public boolean hasCamera;
        public boolean hasCameraFlash;
        public boolean hasGPS;
        public boolean hasNFC;
        public boolean hasBluetooth;
        public boolean hasWifi;
        public boolean hasTouchscreen;
        public boolean hasMicrophone;
        public boolean hasAccelerometer;
    }

    public static class WebViewInfo {
        public String versionName;
        public String packageName;
        public boolean isAvailable;
        public boolean isEnabled;
        public String errorMessage;
    }

    public static class PermissionInfo {
        public String[] requestedPermissions;
        public int requestedPermissionsCount;
        public boolean hasInternet;
        public boolean hasNetworkState;
        public boolean hasWifiState;
        public boolean hasReadStorage;
        public boolean hasWriteStorage;
        public boolean hasCamera;
        public boolean hasLocation;
        public boolean hasLocationCoarse;
        public boolean hasPhoneState;
        public boolean hasRecordAudio;
    }
}
