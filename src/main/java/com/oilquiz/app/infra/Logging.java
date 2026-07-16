package com.oilquiz.app.infra;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logging {

    private static final String TAG = "Logging";
    private static final String LOG_DIR = "oilquiz/logs";
    private static final String APP_LOG_FILE = "app_logs.txt";
    private static final String CRASH_LOG_FILE = "crash_logs.txt";
    private static final int MAX_LOG_SIZE = 1024 * 1024; // 1MB
    private static Context applicationContext;

    // 初始化Logging类，设置应用Context
    public static void init(Context context) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
        }
    }

    // 记录应用日志
    public static void log(String tag, String message) {
        String logMessage = formatLogMessage(tag, message);
        Log.d(tag, message);
        writeToLogFile(APP_LOG_FILE, logMessage);
    }

    // 记录错误日志
    public static void logError(String tag, String message, Throwable throwable) {
        String logMessage = formatLogMessage(tag, message + "\n" + Log.getStackTraceString(throwable));
        Log.e(tag, message, throwable);
        writeToLogFile(APP_LOG_FILE, logMessage);
        writeToLogFile(CRASH_LOG_FILE, logMessage);
    }

    // 记录信息日志（兼容旧代码）
    public static void i(String message) {
        log("INFO", message);
    }

    // 记录错误日志（兼容旧代码）
    public static void e(String message, Throwable throwable) {
        logError("ERROR", message, throwable);
    }

    // 记录崩溃日志
    public static void logCrash(String message, Throwable throwable) {
        String logMessage = formatLogMessage("CRASH", message + "\n" + Log.getStackTraceString(throwable));
        Log.e("CRASH", message, throwable);
        writeToLogFile(CRASH_LOG_FILE, logMessage);
    }

    // 获取应用日志
    public static String getAppLogs() {
        return readLogFile(APP_LOG_FILE);
    }

    // 获取崩溃日志
    public static String getCrashLogs() {
        return readLogFile(CRASH_LOG_FILE);
    }

    // 清空日志
    public static boolean clearLogs() {
        boolean appLogCleared = clearLogFile(APP_LOG_FILE);
        boolean crashLogCleared = clearLogFile(CRASH_LOG_FILE);
        return appLogCleared && crashLogCleared;
    }

    // 格式化日志消息
    private static String formatLogMessage(String tag, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        return timestamp + " | " + tag + " | " + message + "\n";
    }

    // 写入日志文件
    private static void writeToLogFile(String fileName, String message) {
        File logFile = getLogFile(fileName);
        if (logFile == null) {
            return;
        }

        // 检查文件大小，如果超过限制则清空
        if (logFile.length() > MAX_LOG_SIZE) {
            clearLogFile(fileName);
        }

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(message);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to log file: " + e.getMessage());
        }
    }

    // 读取日志文件
    private static String readLogFile(String fileName) {
        File logFile = getLogFile(fileName);
        if (logFile == null || !logFile.exists()) {
            return "";
        }

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading log file: " + e.getMessage());
        }
        return logs.toString();
    }

    // 清空日志文件
    private static boolean clearLogFile(String fileName) {
        File logFile = getLogFile(fileName);
        if (logFile == null) {
            return false;
        }

        try (FileWriter writer = new FileWriter(logFile, false)) {
            writer.write("");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error clearing log file: " + e.getMessage());
            return false;
        }
    }

    // 获取日志文件
    private static File getLogFile(String fileName) {
        // 尝试使用应用Context
        if (applicationContext != null) {
            File logDir;
            try {
                // 优先使用内部存储，避免权限问题
                logDir = new File(applicationContext.getFilesDir(), LOG_DIR);

                if (!logDir.exists()) {
                    if (!logDir.mkdirs()) {
                        Log.e(TAG, "Failed to create log directory");
                        return null;
                    }
                }

                return new File(logDir, fileName);
            } catch (Exception e) {
                Log.e(TAG, "Error getting log file: " + e.getMessage());
                return null;
            }
        }

        // 如果没有Context，返回null
        Log.e(TAG, "Context is null");
        return null;
    }

    // 导出日志到外部存储（便于复制）
    public static File exportLogsForCopy() {
        if (applicationContext == null) {
            Log.e(TAG, "Context is null, cannot export logs");
            return null;
        }

        try {
            // 创建导出目录
            File exportDir = new File(applicationContext.getExternalFilesDir(null), "exported_logs");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }

            // 创建合并的日志文件
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            File exportFile = new File(exportDir, "logs_" + timestamp + ".txt");

            StringBuilder allLogs = new StringBuilder();
            allLogs.append("========== 应用日志 ==========\n\n");
            allLogs.append(getAppLogs());
            allLogs.append("\n\n========== 崩溃日志 ==========\n\n");
            allLogs.append(getCrashLogs());

            try (FileWriter writer = new FileWriter(exportFile)) {
                writer.write(allLogs.toString());
            }

            Log.i(TAG, "Logs exported to: " + exportFile.getAbsolutePath());
            return exportFile;
        } catch (Exception e) {
            Log.e(TAG, "Error exporting logs: " + e.getMessage());
            return null;
        }
    }

    // 分享日志文件
    public static void shareLogs(Context context) {
        File exportedFile = exportLogsForCopy();
        if (exportedFile == null) {
            Log.e(TAG, "Failed to export logs for sharing");
            return;
        }

        try {
            Uri fileUri = FileProvider.getUriForFile(context, 
                    context.getPackageName() + ".fileprovider", exportedFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "应用日志");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "请查看附件中的应用日志");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(shareIntent, "分享日志");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            Log.e(TAG, "Error sharing logs: " + e.getMessage());
        }
    }

    // 获取日志文件路径（便于复制）
    public static String getLogFilePath() {
        File logFile = getLogFile(APP_LOG_FILE);
        return logFile != null ? logFile.getAbsolutePath() : "";
    }

    // 获取崩溃日志文件路径
    public static String getCrashLogFilePath() {
        File logFile = getLogFile(CRASH_LOG_FILE);
        return logFile != null ? logFile.getAbsolutePath() : "";
    }
}
