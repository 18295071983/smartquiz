package com.oilquiz.app.infra;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用级日志记录器
 * 实现应用级记录与崩溃日志记录方法类似
 * 支持日志级别、自动清理、日志拦截等功能
 */
public class AppLogger {

    private static final String TAG = "AppLogger";
    private static final String LOG_DIR = "oilquiz/logs";
    private static final String APP_LOG_FILE = "app_logs.txt";
    private static final String CRASH_LOG_FILE = "crash_logs.txt";
    private static final String AI_LOG_FILE = "ai_logs.txt";
    private static final int MAX_LOG_SIZE = 1024 * 1024; // 1MB
    private static final int MAX_LOG_LINES = 5000; // 最大保留日志行数
    private static final int MAX_CRASH_LOG_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_CRASH_LOG_LINES = 10000; // 最大保留崩溃日志行数
    private static final int MAX_AI_LOG_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_AI_LOG_LINES = 10000; // 最大保留AI日志行数

    // 日志级别
    public enum LogLevel {
        VERBOSE(0, "V"),
        DEBUG(1, "D"),
        INFO(2, "I"),
        WARN(3, "W"),
        ERROR(4, "E"),
        CRASH(5, "C");

        private final int level;
        private final String symbol;

        LogLevel(int level, String symbol) {
            this.level = level;
            this.symbol = symbol;
        }

        public int getLevel() {
            return level;
        }

        public String getSymbol() {
            return symbol;
        }
    }

    private static Context applicationContext;
    private static LogLevel minLogLevel = LogLevel.DEBUG; // 最小记录级别
    private static boolean isInitialized = false;
    private static final Object fileLock = new Object(); // 文件操作锁
    private static boolean logsModified = false; // 标记日志是否有修改
    private static StringBuilder pendingLogs = new StringBuilder(); // 待写入的日志缓冲区
    private static final int MAX_BUFFER_SIZE = 1024 * 10; // 10KB 缓冲区大小阈值
    private static final long FLUSH_INTERVAL = 5 * 60 * 1000; // 5分钟自动刷新间隔
    private static long lastFlushTime = System.currentTimeMillis();

    /**
     * 初始化日志记录器
     */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        applicationContext = context.getApplicationContext();
        isInitialized = true;

        // 记录启动日志
        log(LogLevel.INFO, TAG, "应用日志记录器已初始化");
        log(LogLevel.INFO, TAG, "应用版本: " + getAppVersion());
        log(LogLevel.INFO, TAG, "Android版本: " + android.os.Build.VERSION.RELEASE);
        log(LogLevel.INFO, TAG, "设备型号: " + android.os.Build.MODEL);
    }

    /**
     * 设置最小记录级别
     */
    public static void setMinLogLevel(LogLevel level) {
        minLogLevel = level;
    }

    /**
     * 详细日志
     */
    public static void v(String tag, String message) {
        log(LogLevel.VERBOSE, tag, message);
    }

    /**
     * 调试日志
     */
    public static void d(String tag, String message) {
        log(LogLevel.DEBUG, tag, message);
    }

    /**
     * 信息日志
     */
    public static void i(String tag, String message) {
        log(LogLevel.INFO, tag, message);
    }

    /**
     * 警告日志
     */
    public static void w(String tag, String message) {
        log(LogLevel.WARN, tag, message);
    }

    public static void w(String tag, String message, Throwable throwable) {
        log(LogLevel.WARN, tag, message + "\n" + getStackTraceString(throwable));
    }

    /**
     * 错误日志
     */
    public static void e(String tag, String message) {
        log(LogLevel.ERROR, tag, message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        log(LogLevel.ERROR, tag, message + "\n" + getStackTraceString(throwable));
        // 错误日志同时记录到崩溃日志文件
        logCrash(message, throwable);
    }
    
    /**
     * AI日志 - 记录AI相关操作
     */
    public static void ai(String tag, String message) {
        logAI(LogLevel.INFO, tag, message);
    }
    
    public static void aiD(String tag, String message) {
        logAI(LogLevel.DEBUG, tag, message);
    }
    
    public static void aiE(String tag, String message) {
        logAI(LogLevel.ERROR, tag, message);
    }
    
    /**
     * 核心AI日志记录方法
     */
    private static void logAI(LogLevel level, String tag, String message) {
        if (!isInitialized) {
            Log.e(TAG, "AppLogger未初始化");
            return;
        }
        
        // 检查是否达到最小记录级别
        if (level.getLevel() < minLogLevel.getLevel()) {
            return;
        }

        // 格式化日志条目
        String logEntry = formatLogEntry(level, tag, "[AI] " + message);
        
        // 写入AI日志文件
        writeToFile(AI_LOG_FILE, logEntry, true);
        
        // 同时输出到系统日志
        switch (level) {
            case VERBOSE:
                Log.v(tag, "[AI] " + message);
                break;
            case DEBUG:
                Log.d(tag, "[AI] " + message);
                break;
            case INFO:
                Log.i(tag, "[AI] " + message);
                break;
            case WARN:
                Log.w(tag, "[AI] " + message);
                break;
            case ERROR:
            case CRASH:
                Log.e(tag, "[AI] " + message);
                break;
        }
    }
    
    /**
     * 记录崩溃日志（与崩溃日志记录方法类似）
     */
    public static void logCrash(String message, Throwable throwable) {
        if (!isInitialized) {
            Log.e(TAG, "AppLogger未初始化");
            return;
        }

        StringBuilder crashInfo = new StringBuilder();
        crashInfo.append("═══════════════════════════════════════\n");
        crashInfo.append("           应用崩溃报告                 \n");
        crashInfo.append("═══════════════════════════════════════\n\n");
        crashInfo.append("崩溃时间: ").append(getCurrentTimestamp()).append("\n");
        crashInfo.append("应用版本: ").append(getAppVersion()).append("\n");
        crashInfo.append("Android版本: ").append(android.os.Build.VERSION.RELEASE).append("\n");
        crashInfo.append("SDK级别: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
        crashInfo.append("设备型号: ").append(android.os.Build.MODEL).append("\n");
        crashInfo.append("制造商: ").append(android.os.Build.MANUFACTURER).append("\n\n");
        crashInfo.append("崩溃信息: ").append(message).append("\n\n");
        crashInfo.append("堆栈跟踪:\n");
        crashInfo.append(getStackTraceString(throwable)).append("\n");
        crashInfo.append("═══════════════════════════════════════\n\n");

        // 立即写入崩溃日志（崩溃日志需要立即保存）
        writeToFile(CRASH_LOG_FILE, crashInfo.toString(), true);

        // 记录到系统日志
        Log.e("CRASH", message, throwable);
    }
    
    /**
     * 刷新并保存所有待写入的日志到文件
     * 在应用退出或崩溃时调用
     */
    public static void flushLogs() {
        synchronized (fileLock) {
            if (logsModified && pendingLogs.length() > 0) {
                writeToFile(APP_LOG_FILE, pendingLogs.toString(), true);
                pendingLogs.setLength(0);
                logsModified = false;
                lastFlushTime = System.currentTimeMillis();
            }
        }
    }
    
    /**
     * 检查是否需要自动刷新日志缓冲区
     */
    private static void checkAndFlush() {
        long currentTime = System.currentTimeMillis();
        boolean bufferExceedsThreshold = pendingLogs.length() > MAX_BUFFER_SIZE;
        boolean timeExceedsInterval = currentTime - lastFlushTime > FLUSH_INTERVAL;
        
        if (bufferExceedsThreshold || timeExceedsInterval) {
            flushLogs();
        }
    }

    /**
     * 核心日志记录方法
     */
    private static void log(LogLevel level, String tag, String message) {
        // 检查是否达到最小记录级别
        if (level.getLevel() < minLogLevel.getLevel()) {
            return;
        }

        // 输出到系统日志
        switch (level) {
            case VERBOSE:
                Log.v(tag, message);
                break;
            case DEBUG:
                Log.d(tag, message);
                break;
            case INFO:
                Log.i(tag, message);
                break;
            case WARN:
                Log.w(tag, message);
                break;
            case ERROR:
            case CRASH:
                Log.e(tag, message);
                break;
        }

        // 格式化日志条目并添加到缓冲区
        String logEntry = formatLogEntry(level, tag, message);
        synchronized (fileLock) {
            pendingLogs.append(logEntry);
            logsModified = true;
            
            // 检查是否需要自动刷新
            checkAndFlush();
        }
    }

    /**
     * 格式化日志条目
     */
    private static String formatLogEntry(LogLevel level, String tag, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append(getCurrentTimestamp());
        sb.append(" | ");
        sb.append(level.getSymbol());
        sb.append(" | ");
        sb.append(padRight(tag, 20));
        sb.append(" | ");
        sb.append(message);
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 获取当前时间戳
     */
    private static String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date());
    }

    /**
     * 获取堆栈跟踪字符串
     */
    private static String getStackTraceString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * 字符串右填充
     */
    private static String padRight(String s, int n) {
        if (s.length() >= n) {
            return s.substring(0, n);
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) {
            sb.append(" ");
        }
        return sb.toString();
    }

    /**
     * 写入文件
     */
    private static void writeToFile(String fileName, String content, boolean append) {
        if (applicationContext == null) {
            return;
        }

        File logFile = getLogFile(fileName);
        if (logFile == null) {
            return;
        }

        synchronized (fileLock) {
            try {
                // 检查文件大小，如果超过限制则进行清理
                if (logFile.exists()) {
                    if (fileName.equals(CRASH_LOG_FILE) && logFile.length() > MAX_CRASH_LOG_SIZE) {
                        trimLogFileInternal(logFile, MAX_CRASH_LOG_LINES);
                    } else if (fileName.equals(AI_LOG_FILE) && logFile.length() > MAX_AI_LOG_SIZE) {
                        trimLogFileInternal(logFile, MAX_AI_LOG_LINES);
                    } else if (logFile.length() > MAX_LOG_SIZE) {
                        trimLogFileInternal(logFile, MAX_LOG_LINES);
                    }
                }

                FileWriter writer = new FileWriter(logFile, append);
                try {
                    writer.write(content);
                } finally {
                    writer.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "写入日志文件失败: " + e.getMessage());
            }
        }
    }

    /**
     * 清理日志文件，保留最新的日志（内部方法，已加锁）
     */
    private static void trimLogFileInternal(File logFile, int maxLines) {
        BufferedReader reader = null;
        FileWriter writer = null;
        try {
            reader = new BufferedReader(new FileReader(logFile));
            java.util.List<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }

            // 只保留最新的日志行
            int startIndex = Math.max(0, lines.size() - maxLines);
            writer = new FileWriter(logFile, false);
            for (int i = startIndex; i < lines.size(); i++) {
                writer.write(lines.get(i) + "\n");
            }
            
            // 记录清理信息，但不调用log方法避免递归
            Log.i(TAG, "日志文件已清理，保留 " + (lines.size() - startIndex) + " 行");
        } catch (IOException e) {
            Log.e(TAG, "清理日志文件失败: " + e.getMessage());
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "关闭文件流失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 清理日志文件，保留最新的日志（公共方法）
     */
    private static void trimLogFile(File logFile, int maxLines) {
        synchronized (fileLock) {
            trimLogFileInternal(logFile, maxLines);
        }
    }

    /**
     * 获取日志文件
     */
    private static File getLogFile(String fileName) {
        if (applicationContext == null) {
            Log.e(TAG, "获取日志文件失败: 应用上下文为空");
            return null;
        }

        try {
            // 获取应用文件目录
            File filesDir = applicationContext.getFilesDir();
            if (filesDir == null) {
                Log.e(TAG, "获取日志文件失败: 无法获取应用文件目录");
                return null;
            }
            
            // 创建日志目录
            File logDir = new File(filesDir, LOG_DIR);
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    Log.e(TAG, "创建日志目录失败: " + logDir.getAbsolutePath());
                    return null;
                }
                Log.i(TAG, "日志目录创建成功: " + logDir.getAbsolutePath());
            }
            
            // 创建日志文件
            File logFile = new File(logDir, fileName);
            if (!logFile.exists()) {
                try {
                    if (logFile.createNewFile()) {
                        Log.i(TAG, "日志文件创建成功: " + logFile.getAbsolutePath());
                    } else {
                        Log.w(TAG, "日志文件创建失败: " + logFile.getAbsolutePath());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "创建日志文件失败: " + e.getMessage());
                }
            }
            
            return logFile;
        } catch (Exception e) {
            Log.e(TAG, "获取日志文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取应用日志
     */
    public static String getAppLogs() {
        return readLogFile(APP_LOG_FILE);
    }

    /**
     * 获取崩溃日志
     */
    public static String getCrashLogs() {
        return readLogFile(CRASH_LOG_FILE);
    }
    
    /**
     * 获取AI日志
     */
    public static String getAILogs() {
        return readLogFile(AI_LOG_FILE);
    }
    
    /**
     * 读取日志文件
     */
    private static String readLogFile(String fileName) {
        File logFile = getLogFile(fileName);
        if (logFile == null) {
            Log.w(TAG, "读取日志文件失败: 日志文件对象为空");
            return "[日志文件不存在或无法访问]";
        }
        
        synchronized (fileLock) {
            if (!logFile.exists()) {
                Log.i(TAG, "日志文件不存在: " + logFile.getAbsolutePath());
                return "[日志文件不存在]";
            }

            // 检查文件大小，避免读取过大的文件
            final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
            if (logFile.length() > MAX_FILE_SIZE) {
                Log.w(TAG, "日志文件过大: " + logFile.length() + " bytes，仅读取部分内容");
                return "[日志文件过大，仅显示最近的日志内容]";
            }

            StringBuilder logs = new StringBuilder();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(logFile));
                String line;
                int lineCount = 0;
                final int MAX_LINES = 5000; // 限制读取的行数
                
                while ((line = reader.readLine()) != null && lineCount < MAX_LINES) {
                    logs.append(line).append("\n");
                    lineCount++;
                }
                
                if (lineCount >= MAX_LINES) {
                    logs.append("[日志内容过多，已截断]\n");
                }
                
                if (logs.length() == 0) {
                    Log.i(TAG, "日志文件为空: " + logFile.getAbsolutePath());
                    return "[日志文件为空]";
                }
            } catch (IOException e) {
                Log.e(TAG, "读取日志文件失败: " + e.getMessage());
                return "[读取日志文件失败: " + e.getMessage() + "]";
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "关闭文件流失败: " + e.getMessage());
                }
            }
            return logs.toString();
        }
    }
    
    /**
     * 清空应用日志
     */
    public static boolean clearAppLogs() {
        return clearLogFile(APP_LOG_FILE);
    }

    /**
     * 清空崩溃日志
     */
    public static boolean clearCrashLogs() {
        return clearLogFile(CRASH_LOG_FILE);
    }
    
    /**
     * 清空AI日志
     */
    public static boolean clearAILogs() {
        return clearLogFile(AI_LOG_FILE);
    }
    
    /**
     * 清空所有日志
     */
    public static boolean clearAllLogs() {
        boolean appLogCleared = clearLogFile(APP_LOG_FILE);
        boolean crashLogCleared = clearLogFile(CRASH_LOG_FILE);
        boolean aiLogCleared = clearLogFile(AI_LOG_FILE);
        if (appLogCleared && crashLogCleared && aiLogCleared) {
            log(LogLevel.INFO, TAG, "所有日志已清空");
        }
        return appLogCleared && crashLogCleared && aiLogCleared;
    }
    
    /**
     * 清空日志文件
     */
    private static boolean clearLogFile(String fileName) {
        File logFile = getLogFile(fileName);
        if (logFile == null) {
            return false;
        }

        try (FileWriter writer = new FileWriter(logFile, false)) {
            writer.write("");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "清空日志文件失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取应用版本
     */
    private static String getAppVersion() {
        if (applicationContext == null) {
            return "未知";
        }
        try {
            android.content.pm.PackageInfo packageInfo = applicationContext.getPackageManager()
                    .getPackageInfo(applicationContext.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "未知";
        }
    }

    /**
     * 获取日志文件路径
     */
    public static String getAppLogFilePath() {
        File logFile = getLogFile(APP_LOG_FILE);
        return logFile != null ? logFile.getAbsolutePath() : "";
    }

    /**
     * 获取崩溃日志文件路径
     */
    public static String getCrashLogFilePath() {
        File logFile = getLogFile(CRASH_LOG_FILE);
        return logFile != null ? logFile.getAbsolutePath() : "";
    }
    
    /**
     * 获取日志目录路径
     */
    public static String getLogDirectoryPath() {
        if (applicationContext == null) {
            return "";
        }
        
        try {
            File filesDir = applicationContext.getFilesDir();
            if (filesDir == null) {
                return "";
            }
            
            File logDir = new File(filesDir, LOG_DIR);
            return logDir.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "获取日志目录路径失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 获取日志统计信息
     */
    public static LogStats getLogStats() {
        LogStats stats = new LogStats();

        File appLogFile = getLogFile(APP_LOG_FILE);
        if (appLogFile != null && appLogFile.exists()) {
            stats.appLogSize = appLogFile.length();
            stats.appLogLines = countLines(appLogFile);
            stats.appLogLastModified = appLogFile.lastModified();
        }

        File crashLogFile = getLogFile(CRASH_LOG_FILE);
        if (crashLogFile != null && crashLogFile.exists()) {
            stats.crashLogSize = crashLogFile.length();
            stats.crashLogLines = countLines(crashLogFile);
            stats.crashLogLastModified = crashLogFile.lastModified();
        }

        stats.logDirectoryPath = getLogDirectoryPath();

        return stats;
    }

    /**
     * 计算文件行数
     */
    private static int countLines(File file) {
        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            while (reader.readLine() != null) {
                lines++;
            }
        } catch (IOException e) {
            Log.e(TAG, "计算行数失败: " + e.getMessage());
        }
        return lines;
    }

    /**
     * 日志统计信息类
     */
    public static class LogStats {
        public long appLogSize = 0;
        public int appLogLines = 0;
        public long appLogLastModified = 0;
        public long crashLogSize = 0;
        public int crashLogLines = 0;
        public long crashLogLastModified = 0;
        public String logDirectoryPath = "";

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("应用日志: " + appLogLines + " 行 (" + formatBytes(appLogSize) + ")\n");
            if (appLogLastModified > 0) {
                sb.append("应用日志最后修改: " + new Date(appLogLastModified) + "\n");
            }
            sb.append("崩溃日志: " + crashLogLines + " 行 (" + formatBytes(crashLogSize) + ")\n");
            if (crashLogLastModified > 0) {
                sb.append("崩溃日志最后修改: " + new Date(crashLogLastModified) + "\n");
            }
            if (!logDirectoryPath.isEmpty()) {
                sb.append("日志目录: " + logDirectoryPath + "\n");
            }
            sb.append("总计: " + (appLogLines + crashLogLines) + " 行 (" + formatBytes(appLogSize + crashLogSize) + ")");
            return sb.toString();
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            char unit = "KMGTPE".charAt(exp - 1);
            return String.format(Locale.getDefault(), "%.2f %sB", bytes / Math.pow(1024, exp), unit);
        }
    }
    
    /**
     * 清空所有日志
     */
    public static void clearLogs() {
        clearAllLogs();
    }
    
    /**
     * 导出日志文件
     */
    public static File exportLogs(Context context) {
        if (context == null) {
            return null;
        }
        
        try {
            // 创建导出目录
            File exportDir = new File(context.getExternalFilesDir(null), "logs");
            if (!exportDir.exists()) {
                if (!exportDir.mkdirs()) {
                    Log.e(TAG, "创建导出目录失败");
                    return null;
                }
            }
            
            // 导出应用日志
            File appLogFile = getLogFile(APP_LOG_FILE);
            File exportAppLogFile = new File(exportDir, "app_logs_" + getCurrentTimestamp().replace(":", "-") + ".txt");
            if (appLogFile != null && appLogFile.exists()) {
                java.nio.file.Files.copy(
                    appLogFile.toPath(),
                    exportAppLogFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }
            
            // 导出崩溃日志
            File crashLogFile = getLogFile(CRASH_LOG_FILE);
            File exportCrashLogFile = new File(exportDir, "crash_logs_" + getCurrentTimestamp().replace(":", "-") + ".txt");
            if (crashLogFile != null && crashLogFile.exists()) {
                java.nio.file.Files.copy(
                    crashLogFile.toPath(),
                    exportCrashLogFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }
            
            // 返回导出的应用日志文件
            return exportAppLogFile;
        } catch (Exception e) {
            Log.e(TAG, "导出日志失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取指定日期范围内的日志
     */
    public static String getLogsByDateRange(long startTime, long endTime) {
        StringBuilder logs = new StringBuilder();
        
        // 读取应用日志
        String appLogs = getAppLogs();
        if (appLogs != null && !appLogs.startsWith("[")) {
            String[] lines = appLogs.split("\n");
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) continue;
                
                try {
                    // 解析时间戳
                    String timestampStr = line.substring(0, 23);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                    Date date = sdf.parse(timestampStr);
                    if (date != null) {
                        long timestamp = date.getTime();
                        if (timestamp >= startTime && timestamp <= endTime) {
                            logs.append(line).append("\n");
                        }
                    }
                } catch (Exception e) {
                    // 解析失败，跳过该行
                }
            }
        }
        
        // 读取崩溃日志
        String crashLogs = getCrashLogs();
        if (crashLogs != null && !crashLogs.startsWith("[")) {
            String[] lines = crashLogs.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.trim().isEmpty()) continue;
                
                // 查找崩溃时间行
                if (line.startsWith("崩溃时间: ")) {
                    try {
                        String timestampStr = line.substring(5);
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                        Date date = sdf.parse(timestampStr);
                        if (date != null) {
                            long timestamp = date.getTime();
                            if (timestamp >= startTime && timestamp <= endTime) {
                                // 提取整个崩溃报告
                                StringBuilder crashReport = new StringBuilder();
                                crashReport.append(line).append("\n");
                                
                                // 继续读取直到下一个崩溃报告或文件结束
                                for (int j = i + 1; j < lines.length; j++) {
                                    String nextLine = lines[j];
                                    if (nextLine.startsWith("═══════════════════════════════════════")) {
                                        crashReport.append(nextLine).append("\n");
                                        if (j + 1 < lines.length && lines[j + 1].startsWith("           应用崩溃报告                 ")) {
                                            break; // 下一个崩溃报告开始
                                        }
                                    } else {
                                        crashReport.append(nextLine).append("\n");
                                    }
                                }
                                
                                logs.append(crashReport.toString());
                            }
                        }
                    } catch (Exception e) {
                        // 解析失败，跳过
                    }
                }
            }
        }
        
        if (logs.length() == 0) {
            return "[指定时间范围内无日志]";
        }
        
        return logs.toString();
    }
    
    /**
     * 设置日志级别
     */
    public static void setLogLevel(String level) {
        try {
            LogLevel logLevel = LogLevel.valueOf(level);
            setMinLogLevel(logLevel);
            log(LogLevel.INFO, TAG, "日志级别已设置为: " + level);
        } catch (Exception e) {
            Log.e(TAG, "设置日志级别失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查是否已初始化
     */
    public static boolean isInitialized() {
        return isInitialized;
    }
}

