package com.oilquiz.app.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class AILogger {
    private static final String TAG = "AILogger";
    private static final String LOG_DIR = "ai_logs";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_LOG_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    
    private static File logFile;
    private static ReentrantLock logLock = new ReentrantLock();
    private static Context appContext;
    
    /**
     * 初始化AILogger
     * @param context 应用上下文
     */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        initLogFile();
    }
    
    /**
     * 初始化日志文件
     */
    private static void initLogFile() {
        try {
            File logDir = getLogDirectory();
            if (logDir == null) {
                Log.e(TAG, "Failed to get log directory");
                return;
            }
            
            // 创建目录
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    Log.e(TAG, "Failed to create log directory");
                    return;
                }
            }
            
            // 创建日志文件
            String logFileName = "ai_log_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".txt";
            logFile = new File(logDir, logFileName);
            
            if (!logFile.exists()) {
                if (!logFile.createNewFile()) {
                    Log.e(TAG, "Failed to create log file");
                    return;
                }
            } else {
                // 检查日志文件大小
                checkLogFileSize();
            }
            
            Log.i(TAG, "AI logger initialized, log file: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AI logger: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取日志目录
     * @return 日志目录
     */
    private static File getLogDirectory() {
        if (appContext != null) {
            // 使用应用内部存储目录，不需要权限
            File appDir = appContext.getFilesDir();
            return new File(appDir, LOG_DIR);
        } else {
            // 备用方案：使用外部存储目录
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                File externalDir = Environment.getExternalStorageDirectory();
                File appDir = new File(externalDir, "OilQuiz");
                return new File(appDir, LOG_DIR);
            }
        }
        return null;
    }
    
    /**
     * 检查日志文件大小
     */
    private static void checkLogFileSize() {
        if (logFile != null && logFile.length() > MAX_LOG_FILE_SIZE) {
            // 日志文件过大，创建新文件
            try {
                String oldFileName = logFile.getName();
                String newFileName = oldFileName.replace(".txt", "_" + System.currentTimeMillis() + ".txt");
                File newFile = new File(logFile.getParent(), newFileName);
                
                // 重命名旧文件
                if (logFile.renameTo(newFile)) {
                    // 创建新的日志文件
                    logFile = new File(logFile.getParent(), oldFileName);
                    logFile.createNewFile();
                    Log.i(TAG, "Log file rotated, new file: " + logFile.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to rotate log file: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 记录AI功能的错误日志
     * @param tag 日志标签
     * @param message 日志消息
     * @param t 异常或错误对象
     */
    public static void e(String tag, String message, Throwable t) {
        String logMessage = formatLogMessage("ERROR", tag, message);
        if (t != null) {
            logMessage += "\nError: " + t.getMessage();
            // 获取异常堆栈信息
            StackTraceElement[] stackTrace = t.getStackTrace();
            for (StackTraceElement element : stackTrace) {
                logMessage += "\n" + element.toString();
            }
        }
        
        // 输出到Android日志
        Log.e(tag, message, t);
        
        // 写入到文件
        writeToFile(logMessage);
    }
    
    /**
     * 记录AI功能的错误日志
     * @param tag 日志标签
     * @param message 日志消息
     * @param e 异常对象
     */
    public static void e(String tag, String message, Exception e) {
        e(tag, message, (Throwable) e);
    }
    
    /**
     * 记录AI功能的错误日志（无异常）
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void e(String tag, String message) {
        e(tag, message, null);
    }
    
    /**
     * 记录AI功能的信息日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void i(String tag, String message) {
        String logMessage = formatLogMessage("INFO", tag, message);
        
        Log.i(tag, message);
        
        writeToFile(logMessage);
    }
    
    public static void d(String tag, String message) {
        String logMessage = formatLogMessage("DEBUG", tag, message);
        
        Log.d(tag, message);
        
        writeToFile(logMessage);
    }
    
    /**
     * 记录AI功能的警告日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    public static void w(String tag, String message) {
        String logMessage = formatLogMessage("WARN", tag, message);
        
        // 输出到Android日志
        Log.w(tag, message);
        
        // 写入到文件
        writeToFile(logMessage);
    }
    
    /**
     * 格式化日志消息
     * @param level 日志级别
     * @param tag 日志标签
     * @param message 日志消息
     * @return 格式化后的日志消息
     */
    private static String formatLogMessage(String level, String tag, String message) {
        String timestamp = DATE_FORMAT.format(new Date());
        return "[" + timestamp + "] [" + level + "] [" + tag + "] " + message;
    }
    
    /**
     * 将日志写入文件
     * @param message 日志消息
     */
    private static void writeToFile(String message) {
        if (logFile == null) {
            // 尝试重新初始化日志文件
            initLogFile();
            if (logFile == null) {
                return;
            }
        }
        
        logLock.lock();
        try {
            // 检查日志文件大小
            checkLogFileSize();
            
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(message + "\n\n");
                writer.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write log to file: " + e.getMessage(), e);
            }
        } finally {
            logLock.unlock();
        }
    }
    
    /**
     * 获取日志文件路径
     * @return 日志文件路径
     */
    public static String getLogFilePath() {
        if (logFile != null) {
            return logFile.getAbsolutePath();
        }
        return "Log file not initialized";
    }
    
    /**
     * 清空当前日志文件
     * @return 是否成功清空
     */
    public static boolean clearLogs() {
        logLock.lock();
        try {
            if (logFile != null && logFile.exists()) {
                try (FileWriter writer = new FileWriter(logFile, false)) {
                    // 空写入清空文件
                    writer.write("");
                    writer.flush();
                    Log.i(TAG, "Log file cleared successfully");
                    return true;
                } catch (IOException e) {
                    Log.e(TAG, "Failed to clear log file: " + e.getMessage(), e);
                    return false;
                }
            }
            return false;
        } finally {
            logLock.unlock();
        }
    }
    
    /**
     * 清理旧日志文件
     * @param days 保留天数
     */
    public static void cleanupOldLogs(int days) {
        logLock.lock();
        try {
            File logDir = getLogDirectory();
            if (logDir != null && logDir.exists()) {
                long cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000);
                File[] files = logDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.lastModified() < cutoffTime) {
                            if (file.delete()) {
                                Log.i(TAG, "Deleted old log file: " + file.getName());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to cleanup old logs: " + e.getMessage(), e);
        } finally {
            logLock.unlock();
        }
    }

    /**
     * 日志条目类
     */
    public static class LogEntry {
        public final String timestamp;
        public final String level;
        public final String tag;
        public final String message;
        public final String fullText;

        public LogEntry(String timestamp, String level, String tag, String message, String fullText) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.fullText = fullText;
        }
    }

    /**
     * 读取所有日志条目
     * @return 日志条目列表（按时间倒序）
     */
    public static List<LogEntry> getAllLogs() {
        List<LogEntry> entries = new ArrayList<>();
        logLock.lock();
        try {
            if (logFile == null || !logFile.exists()) {
                return entries;
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                StringBuilder fullText = new StringBuilder();
                String timestamp = "";
                String level = "";
                String tag = "";
                String message = "";

                while ((line = reader.readLine()) != null) {
                    fullText.append(line).append("\n");

                    // 解析日志行
                    // 格式: [timestamp] [level] [tag] message
                    if (line.startsWith("[") && line.contains("] [")) {
                        // 如果有之前的完整条目，先保存
                        if (!timestamp.isEmpty()) {
                            entries.add(new LogEntry(timestamp, level, tag, message, fullText.toString()));
                            fullText = new StringBuilder();
                        }

                        // 解析新行
                        String remaining = line.substring(1); // 移除开头的[
                        String[] parts = remaining.split("\\] \\[");
                        if (parts.length >= 3) {
                            timestamp = parts[0].trim();
                            level = parts[1].trim();
                            tag = parts[2].trim();
                            // 消息部分是剩余的所有内容
                            int tagEnd = line.indexOf("] ", tag.length() + 3);
                            if (tagEnd > 0) {
                                message = line.substring(tagEnd + 2);
                            } else {
                                message = "";
                            }
                        }
                    }
                }

                // 添加最后一条
                if (!timestamp.isEmpty()) {
                    entries.add(new LogEntry(timestamp, level, tag, message, fullText.toString()));
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to read log file: " + e.getMessage(), e);
            }
        } finally {
            logLock.unlock();
        }

        // 倒序返回（最新的在前）
        Collections.reverse(entries);
        return entries;
    }

    /**
     * 按日志级别筛选日志
     * @param level 日志级别 (INFO, WARN, ERROR)
     * @return 筛选后的日志条目列表
     */
    public static List<LogEntry> getLogsByLevel(String level) {
        List<LogEntry> allLogs = getAllLogs();
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : allLogs) {
            if (entry.level.equalsIgnoreCase(level)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * 按标签筛选日志
     * @param tag 标签
     * @return 筛选后的日志条目列表
     */
    public static List<LogEntry> getLogsByTag(String tag) {
        List<LogEntry> allLogs = getAllLogs();
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : allLogs) {
            if (entry.tag.equals(tag)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * 获取最近的N条日志
     * @param count 日志数量
     * @return 最近的日志条目列表
     */
    public static List<LogEntry> getRecentLogs(int count) {
        List<LogEntry> allLogs = getAllLogs();
        if (allLogs.size() <= count) {
            return allLogs;
        }
        return allLogs.subList(0, count);
    }

    /**
     * 搜索日志内容
     * @param keyword 关键词
     * @return 包含关键词的日志条目列表
     */
    public static List<LogEntry> searchLogs(String keyword) {
        List<LogEntry> allLogs = getAllLogs();
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : allLogs) {
            if (entry.message.contains(keyword) || entry.fullText.contains(keyword)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * 获取AI监控状态日志（包含内存、崩溃等信息）
     * @return AI监控相关的日志条目列表
     */
    public static List<LogEntry> getAIMonitorLogs() {
        List<LogEntry> allLogs = getAllLogs();
        List<LogEntry> filtered = new ArrayList<>();
        String[] keywords = {"AICrashHandler", "内存", "崩溃", "NATIVE", "OOM", "线程", "挂起", "ERROR"};
        for (LogEntry entry : allLogs) {
            for (String keyword : keywords) {
                if (entry.tag.contains(keyword) || entry.message.contains(keyword)) {
                    filtered.add(entry);
                    break;
                }
            }
        }
        return filtered;
    }

    /**
     * 获取日志文件内容（原始文本）
     * @return 日志文件的所有内容
     */
    public static String getLogContent() {
        logLock.lock();
        try {
            if (logFile == null || !logFile.exists()) {
                return "";
            }

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to read log file: " + e.getMessage(), e);
                return "Error reading log: " + e.getMessage();
            }
            return content.toString();
        } finally {
            logLock.unlock();
        }
    }

    /**
     * 获取日志统计信息
     * @return 包含日志统计的字符串
     */
    public static String getLogStats() {
        List<LogEntry> allLogs = getAllLogs();
        int infoCount = 0;
        int warnCount = 0;
        int errorCount = 0;

        for (LogEntry entry : allLogs) {
            switch (entry.level.toUpperCase()) {
                case "INFO":
                    infoCount++;
                    break;
                case "WARN":
                    warnCount++;
                    break;
                case "ERROR":
                    errorCount++;
                    break;
            }
        }

        return String.format(
            "日志统计: 总数=%d | INFO=%d | WARN=%d | ERROR=%d",
            allLogs.size(), infoCount, warnCount, errorCount
        );
    }
}
