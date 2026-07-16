package com.oilquiz.app.ai.service;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native崩溃分析器（Tombstone Analyzer）
 *
 * 功能：
 * 1. 收集Native崩溃时的寄存器状态
 * 2. 收集Native堆栈信息
 * 3. 收集内存映射信息
 * 4. 保存崩溃现场到日志文件
 *
 * 注意：真正的Tombstone文件（/data/tombstones/）需要root权限，
 * 但我们可以收集应用自身的信息并保存
 */
public class TombstoneAnalyzer {

    private static final String TAG = "TombstoneAnalyzer";

    /** 单例实例 */
    private static TombstoneAnalyzer instance;

    /** 上下文 */
    private final Context context;

    /** 崩溃信息保存目录 */
    private static final String CRASH_DUMP_DIR = "native_crashes";

    /**
     * 获取单例实例
     */
    public static synchronized TombstoneAnalyzer getInstance(Context context) {
        if (instance == null) {
            instance = new TombstoneAnalyzer(context.getApplicationContext());
        }
        return instance;
    }

    private TombstoneAnalyzer(Context context) {
        this.context = context;
    }

    /**
     * 收集Native崩溃信息
     *
     * @param signal 信号类型（如 SIGSEGV, SIGABRT）
     * @param addr 崩溃地址
     * @param threadName 崩溃的线程名
     * @param stackTrace 堆栈跟踪
     * @return 崩溃信息字符串
     */
    public String collectCrashInfo(String signal, long addr, String threadName, StackTraceElement[] stackTrace) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

        sb.append("========== Native崩溃信息 ==========\n");
        sb.append("崩溃时间: ").append(sdf.format(new Date())).append("\n");
        sb.append("信号类型: ").append(signal).append("\n");
        sb.append("崩溃地址: 0x").append(Long.toHexString(addr)).append("\n");
        sb.append("线程名称: ").append(threadName).append("\n");
        sb.append("进程ID: ").append(android.os.Process.myPid()).append("\n");
        sb.append("线程ID: ").append(android.os.Process.myTid()).append("\n");
        sb.append("Android版本: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK版本: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");

        // 收集Native寄存器信息（如果可能）
        sb.append("【寄存器状态】\n");
        collectRegisterInfo(sb);

        // 收集内存信息
        sb.append("\n【内存状态】\n");
        collectMemoryInfo(sb);

        // 收集内存映射
        sb.append("\n【内存映射】\n");
        collectMemoryMaps(sb);

        // 收集堆栈跟踪
        if (stackTrace != null && stackTrace.length > 0) {
            sb.append("\n【Java堆栈】\n");
            for (int i = 0; i < stackTrace.length; i++) {
                sb.append("  #").append(String.format("%02d", i)).append(" ");
                sb.append(stackTrace[i].toString()).append("\n");
            }
        }

        // 收集Native库信息
        sb.append("\n【已加载的Native库】\n");
        collectLoadedLibraries(sb);

        // 保存到文件
        String crashFile = saveCrashDump(sb.toString(), signal);

        // 同时写入日志
        String crashInfo = sb.toString();
        Log.e(TAG, "========== Native崩溃信息 ==========");
        logLongString(TAG, crashInfo);
        AILogger.e(TAG, "Native crash collected: " + signal + " at 0x" + Long.toHexString(addr));

        return crashInfo;
    }

    /**
     * 收集寄存器信息
     */
    private void collectRegisterInfo(StringBuilder sb) {
        try {
            // 尝试读取进程的寄存器状态
            // 注意：这需要root权限或debuggable应用
            File procFile = new File("/proc/self/status");
            if (procFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(procFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Name:") ||
                            line.startsWith("State:") ||
                            line.startsWith("Tgid:") ||
                            line.startsWith("Pid:") ||
                            line.startsWith("PPid:") ||
                            line.startsWith("TracerPid:")) {
                            sb.append("  ").append(line).append("\n");
                        }
                    }
                }
            }
        } catch (IOException e) {
            sb.append("  无法读取寄存器信息: ").append(e.getMessage()).append("\n");
        }
    }

    /**
     * 收集内存信息
     */
    private void collectMemoryInfo(StringBuilder sb) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);

            Runtime runtime = Runtime.getRuntime();

            sb.append("  系统内存:\n");
            sb.append("    总内存: ").append(formatMemory(memInfo.totalMem)).append("\n");
            sb.append("    可用内存: ").append(formatMemory(memInfo.availMem)).append("\n");
            sb.append("    低内存: ").append(memInfo.lowMemory).append("\n");
            sb.append("    内存阈值: ").append(formatMemory(memInfo.threshold)).append("\n\n");

            sb.append("  Java堆内存:\n");
            sb.append("    最大堆: ").append(formatMemory(runtime.maxMemory())).append("\n");
            sb.append("    总堆: ").append(formatMemory(runtime.totalMemory())).append("\n");
            sb.append("    已用堆: ").append(formatMemory(runtime.totalMemory() - runtime.freeMemory())).append("\n");
            sb.append("    可用堆: ").append(formatMemory(runtime.freeMemory())).append("\n");

            // 读取 /proc/self/statm
            File statm = new File("/proc/self/statm");
            if (statm.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(statm))) {
                    String line = reader.readLine();
                    if (line != null) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            long sizePages = Long.parseLong(parts[0]);
                            long residentPages = Long.parseLong(parts[1]);
                            long pageSize = 4096; // 通常4KB
                            sb.append("\n  进程内存 (来自 /proc/self/statm):\n");
                            sb.append("    虚拟内存大小: ").append(formatMemory(sizePages * pageSize)).append("\n");
                            sb.append("    物理内存(驻留): ").append(formatMemory(residentPages * pageSize)).append("\n");
                        }
                    }
                } catch (IOException e) {
                    sb.append("  无法读取 /proc/self/statm: ").append(e.getMessage()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("  收集内存信息失败: ").append(e.getMessage()).append("\n");
        }
    }

    /**
     * 收集内存映射
     */
    private void collectMemoryMaps(StringBuilder sb) {
        try {
            File maps = new File("/proc/self/maps");
            if (maps.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
                    int count = 0;
                    String line;
                    // 只读取前50行，避免信息过多
                    while ((line = reader.readLine()) != null && count < 50) {
                        // 过滤掉不需要的映射
                        if (line.contains("/data/app/") ||
                            line.contains("/system/lib/") ||
                            line.contains(".so") ||
                            line.contains("[heap]") ||
                            line.contains("[stack]")) {
                            sb.append("  ").append(line).append("\n");
                            count++;
                        }
                    }
                    if (count >= 50) {
                        sb.append("  ... (省略部分映射, 共 ").append(count).append("+ 行)\n");
                    }
                }
            } else {
                sb.append("  /proc/self/maps 不可访问\n");
            }
        } catch (IOException e) {
            sb.append("  收集内存映射失败: ").append(e.getMessage()).append("\n");
        }
    }

    /**
     * 收集已加载的Native库
     */
    private void collectLoadedLibraries(StringBuilder sb) {
        try {
            String mapsPath = "/proc/self/maps";
            File maps = new File(mapsPath);
            if (maps.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
                    List<String> libraries = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.endsWith(".so")) {
                            String libPath = line.substring(line.lastIndexOf("/") + 1);
                            if (!libraries.contains(libPath)) {
                                libraries.add(libPath);
                            }
                        }
                    }
                    for (String lib : libraries) {
                        sb.append("  ").append(lib).append("\n");
                    }
                    if (libraries.isEmpty()) {
                        sb.append("  未找到已加载的Native库\n");
                    }
                }
            }
        } catch (IOException e) {
            sb.append("  收集Native库信息失败: ").append(e.getMessage()).append("\n");
        }
    }

    /**
     * 保存崩溃转储到文件
     */
    private String saveCrashDump(String content, String signal) {
        try {
            File crashDir = new File(context.getFilesDir(), CRASH_DUMP_DIR);
            if (!crashDir.exists()) {
                crashDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(new Date());
            String fileName = "crash_" + signal + "_" + timestamp + ".txt";
            File crashFile = new File(crashDir, fileName);

            try (java.io.FileWriter writer = new java.io.FileWriter(crashFile)) {
                writer.write(content);
            }

            Log.i(TAG, "Crash dump saved to: " + crashFile.getAbsolutePath());
            AILogger.i(TAG, "Native crash dump saved: " + crashFile.getName());

            // 清理旧文件（只保留最近10个）
            cleanupOldDumps(crashDir, 10);

            return crashFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save crash dump: " + e.getMessage());
            return null;
        }
    }

    /**
     * 清理旧的崩溃转储文件
     */
    private void cleanupOldDumps(File crashDir, int keepCount) {
        try {
            File[] files = crashDir.listFiles();
            if (files != null && files.length > keepCount) {
                // 按修改时间排序
                List<File> sortedFiles = new ArrayList<>();
                for (File f : files) {
                    sortedFiles.add(f);
                }
                sortedFiles.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));

                // 删除旧文件
                for (int i = keepCount; i < sortedFiles.size(); i++) {
                    if (sortedFiles.get(i).delete()) {
                        Log.i(TAG, "Deleted old crash dump: " + sortedFiles.get(i).getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to cleanup old dumps: " + e.getMessage());
        }
    }

    /**
     * 获取崩溃转储目录
     */
    public String getCrashDumpDir() {
        File crashDir = new File(context.getFilesDir(), CRASH_DUMP_DIR);
        if (!crashDir.exists()) {
            crashDir.mkdirs();
        }
        return crashDir.getAbsolutePath();
    }

    /**
     * 获取所有崩溃转储文件
     */
    public List<File> getCrashDumpFiles() {
        List<File> files = new ArrayList<>();
        File crashDir = new File(context.getFilesDir(), CRASH_DUMP_DIR);
        if (crashDir.exists()) {
            File[] allFiles = crashDir.listFiles();
            if (allFiles != null) {
                for (File f : allFiles) {
                    if (f.isFile() && f.getName().startsWith("crash_")) {
                        files.add(f);
                    }
                }
                // 按时间倒序
                files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
        }
        return files;
    }

    /**
     * 读取崩溃转储文件内容
     */
    public String readCrashDump(File file) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            return "Error reading crash dump: " + e.getMessage();
        }
        return content.toString();
    }

    /**
     * 格式化内存大小
     */
    private String formatMemory(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1fMB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 格式化的堆栈跟踪字符串
     */
    public static String formatStackTrace(Throwable t) {
        if (t == null) return "No stack trace available";

        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");

        StackTraceElement[] elements = t.getStackTrace();
        if (elements != null) {
            for (int i = 0; i < elements.length && i < 30; i++) { // 限制30行
                sb.append("  at ").append(elements[i].toString()).append("\n");
            }
        }

        // 递归获取根本原因
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("\nCaused by: ");
            sb.append(formatStackTrace(cause));
        }

        return sb.toString();
    }

    /**
     * 输出长字符串到系统日志（避免被Logcat截断）
     */
    private void logLongString(String tag, String content) {
        int maxLogSize = 4000;
        if (content.length() <= maxLogSize) {
            Log.e(tag, content);
            return;
        }
        // 按行分割输出
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.length() > maxLogSize) {
                // 如果单行太长，进一步分割
                for (int i = 0; i < line.length(); i += maxLogSize) {
                    int end = Math.min(i + maxLogSize, line.length());
                    Log.e(tag, line.substring(i, end));
                }
            } else {
                Log.e(tag, line);
            }
        }
    }
}
