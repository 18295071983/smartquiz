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
        // 死亡归因（2026-10-25）：先把"本次启动发生过 Java 未捕获崩溃"同步落进崩溃保护 prefs，
        // 让 SmartQuizApplication 下次启动能把它计入模型相关崩溃，而不是把 ANR/被划掉也一视同仁。
        // 放在最前、用 commit 同步写：进程马上要被默认处理器终止。
        try {
            com.oilquiz.app.SmartQuizApplication.recordJavaCrashForAttribution();
        } catch (Throwable ignored) {
        }

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
