package com.oilquiz.app.infra;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局异常处理器
 * 捕获并处理应用中未捕获的异常，提供完善的错误日志记录
 */
public class GlobalExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "GlobalExceptionHandler";
    private static final String CRASH_DIR = "crashes";
    private static final String LOG_DIR = "logs";
    private static final long MAX_LOG_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_LOG_FILES = 10;
    
    private final Application application;
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final SimpleDateFormat dateFormat;

    public GlobalExceptionHandler(Application application) {
        this.application = application;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    }

    /**
     * 初始化全局异常处理器
     */
    public static void init(Application application) {
        Thread.setDefaultUncaughtExceptionHandler(new GlobalExceptionHandler(application));
        Log.d(TAG, "全局异常处理器已初始化");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            // 刷新待写入的日志
            com.oilquiz.app.infra.AppLogger.flushLogs();
            
            // 检查AppLogger是否已经初始化
            if (com.oilquiz.app.infra.AppLogger.isInitialized()) {
                // 使用AppLogger记录崩溃日志
                com.oilquiz.app.infra.AppLogger.logCrash("应用崩溃", throwable);
            } else {
                // AppLogger未初始化，直接记录到系统日志
                Log.e(TAG, "应用崩溃", throwable);
            }
        } catch (Exception e) {
            // 确保即使AppLogger出现问题也不会导致二次崩溃
            Log.e(TAG, "记录崩溃日志时出错", e);
        }
        
        // 调用默认处理器（让系统处理崩溃）
        if (defaultHandler != null) {
            try {
                defaultHandler.uncaughtException(thread, throwable);
            } catch (Exception e) {
                Log.e(TAG, "调用默认异常处理器时出错", e);
            }
        }
    }

    /**
     * 记录错误日志
     */
    public void logError(String message, Throwable throwable) {
        com.oilquiz.app.infra.AppLogger.e(TAG, message, throwable);
    }

    /**
     * 记录警告日志
     */
    public void logWarning(String message) {
        com.oilquiz.app.infra.AppLogger.w(TAG, message);
    }

    /**
     * 记录信息日志
     */
    public void logInfo(String message) {
        com.oilquiz.app.infra.AppLogger.i(TAG, message);
    }

    /**
     * 记录调试日志
     */
    public void logDebug(String message) {
        com.oilquiz.app.infra.AppLogger.d(TAG, message);
    }

    /**
     * 保存详细的崩溃日志
     */
    private void saveCrashLog(Thread thread, Throwable throwable) {
        try {
            StringBuilder sb = new StringBuilder();
            
            // 添加崩溃报告头部信息
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║                    应用崩溃报告                              ║\n");
            sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");
            
            // 添加时间信息
            sb.append("【时间信息】\n");
            sb.append("崩溃时间: ").append(dateFormat.format(new Date())).append("\n");
            sb.append("线程名称: ").append(thread.getName()).append("\n");
            sb.append("线程ID: ").append(Thread.currentThread().getId()).append("\n\n");
            
            // 添加设备信息
            sb.append("【设备信息】\n");
            sb.append("设备型号: ").append(Build.MODEL).append("\n");
            sb.append("设备厂商: ").append(Build.MANUFACTURER).append("\n");
            sb.append("Android版本: ").append(Build.VERSION.RELEASE).append("\n");
            sb.append("SDK版本: ").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("CPU架构: ").append(Build.CPU_ABI).append("\n\n");
            
            // 添加应用信息
            sb.append("【应用信息】\n");
            try {
                PackageInfo packageInfo = application.getPackageManager().getPackageInfo(
                        application.getPackageName(), 0);
                sb.append("应用包名: ").append(application.getPackageName()).append("\n");
                sb.append("应用版本: ").append(packageInfo.versionName).append("\n");
                sb.append("版本代码: ").append(packageInfo.versionCode).append("\n");
            } catch (PackageManager.NameNotFoundException e) {
                sb.append("应用信息获取失败\n");
            }
            sb.append("\n");
            
            // 添加异常信息
            sb.append("【异常信息】\n");
            sb.append("异常类型: ").append(throwable.getClass().getName()).append("\n");
            sb.append("异常消息: ").append(throwable.getMessage()).append("\n\n");
            
            // 添加完整堆栈跟踪
            sb.append("【堆栈跟踪】\n");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            sb.append(sw.toString()).append("\n");
            
            // 添加内存信息
            sb.append("【内存信息】\n");
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            sb.append("最大内存: ").append(formatBytes(maxMemory)).append("\n");
            sb.append("已分配内存: ").append(formatBytes(totalMemory)).append("\n");
            sb.append("已使用内存: ").append(formatBytes(usedMemory)).append("\n");
            sb.append("空闲内存: ").append(formatBytes(freeMemory)).append("\n\n");
            
            sb.append("═══════════════════════════════════════════════════════════════\n");

            // 保存到崩溃日志目录
            File crashDir = new File(application.getFilesDir(), CRASH_DIR);
            if (!crashDir.exists()) {
                crashDir.mkdirs();
            }

            // 清理旧的崩溃日志
            cleanupOldFiles(crashDir, MAX_LOG_FILES);
            
            String fileName = "crash_" + System.currentTimeMillis() + ".txt";
            File crashFile = new File(crashDir, fileName);

            BufferedWriter writer = new BufferedWriter(new FileWriter(crashFile));
            writer.write(sb.toString());
            writer.close();

            Log.d(TAG, "崩溃日志已保存: " + crashFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "保存崩溃日志失败", e);
        }
    }

    /**
     * 保存错误日志
     */
    private void saveErrorLog(String message, Throwable throwable) {
        saveLog("ERROR", message, throwable);
    }

    /**
     * 保存日志到文件
     */
    private void saveLog(String level, String message, Throwable throwable) {
        try {
            File logDir = new File(application.getFilesDir(), LOG_DIR);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            // 按日期命名日志文件
            SimpleDateFormat fileNameFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String fileName = "app_" + fileNameFormat.format(new Date()) + ".log";
            File logFile = new File(logDir, fileName);

            // 检查文件大小，如果超过限制则创建新文件
            if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                String newFileName = "app_" + fileNameFormat.format(new Date()) + "_" + System.currentTimeMillis() + ".log";
                logFile = new File(logDir, newFileName);
            }

            // 构建日志内容
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(dateFormat.format(new Date())).append("]");
            sb.append("[").append(level).append("] ");
            sb.append(message);
            
            if (throwable != null) {
                sb.append(" - ").append(throwable.getMessage());
            }
            sb.append("\n");

            // 追加写入日志文件
            BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true));
            writer.write(sb.toString());
            
            // 如果有异常，写入堆栈跟踪
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                writer.write(sw.toString());
                writer.write("\n");
            }
            
            writer.close();

        } catch (IOException e) {
            Log.e(TAG, "保存日志失败", e);
        }
    }

    /**
     * 清理旧文件，只保留指定数量的最新文件
     */
    private void cleanupOldFiles(File directory, int maxFiles) {
        File[] files = directory.listFiles();
        if (files == null || files.length <= maxFiles) {
            return;
        }

        // 按修改时间排序
        java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        // 删除旧文件
        for (int i = maxFiles; i < files.length; i++) {
            files[i].delete();
        }
    }

    /**
     * 格式化字节大小
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 获取崩溃日志目录
     */
    public static File getCrashDir(Application application) {
        return new File(application.getFilesDir(), CRASH_DIR);
    }

    /**
     * 获取日志目录
     */
    public static File getLogDir(Application application) {
        return new File(application.getFilesDir(), LOG_DIR);
    }

    /**
     * 清理所有崩溃日志
     */
    public static void clearCrashLogs(Application application) {
        File crashDir = getCrashDir(application);
        if (crashDir.exists()) {
            File[] files = crashDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    /**
     * 清理所有应用日志
     */
    public static void clearAppLogs(Application application) {
        File logDir = getLogDir(application);
        if (logDir.exists()) {
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }
}
