package com.oilquiz.app.ai.service;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Debug;
import android.util.Log;

import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI崩溃处理器
 *
 * 功能：
 * 1. 监控AI处理线程是否挂起或超时
 * 2. 监控内存使用情况，防止OOM
 * 3. 提供崩溃前的日志记录
 * 4. 定期检查系统资源状态
 *
 * 注意：Native SIGSEGV等信号无法被Java完全捕获，
 * 但我们可以监控线程状态和内存使用来预防和诊断问题
 */
public class AICrashHandler {

    private static final String TAG = "AICrashHandler";

    /** 单例实例 */
    private static AICrashHandler instance;

    /** 上下文 */
    private final Context context;

    /** 主线程Handler */
    private final Handler mainHandler;

    /** Watchdog定时器 */
    private Timer watchdogTimer;

    /** 是否正在监控 */
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);

    /** AI 是否处于活跃状态（recordActivity 置忙，markIdle 置空闲） */
    private final AtomicBoolean aiActive = new AtomicBoolean(false);

    /** 最后一次AI活动的时间戳 */
    private final AtomicLong lastActivityTime = new AtomicLong(0);

    /** 默认超时时间（毫秒）- 60秒无响应认为挂起 */
    private static final long DEFAULT_TIMEOUT_MS = 60000;

    /** 同一问题两次记录的最小间隔（毫秒），避免连续刷日志 */
    private static final long REPORT_INTERVAL_MS = 5 * 60 * 1000;

    /** 内存监控阈值（百分比）- 超过90%认为内存不足 */
    private static final float MEMORY_THRESHOLD = 0.90f;

    /** 回调接口 */
    private CrashCallback crashCallback;

    /** 已记录的问题 */
    private final Map<String, Long> recordedIssues = new HashMap<>();

    /**
     * 崩溃回调接口
     */
    public interface CrashCallback {
        void onAIHang(long waitTimeMs);
        void onMemoryWarning(float usagePercent, long usedMemory, long maxMemory);
        void onThreadDead(String threadName, String reason);
        void onNativeCrash(String signal, String stackTrace);
    }

    /**
     * 获取单例实例
     */
    public static synchronized AICrashHandler getInstance(Context context) {
        if (instance == null) {
            instance = new AICrashHandler(context.getApplicationContext());
        }
        return instance;
    }

    private AICrashHandler(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置崩溃回调
     */
    public void setCrashCallback(CrashCallback callback) {
        this.crashCallback = callback;
    }

    /**
     * 开始监控AI处理
     */
    public void startMonitoring() {
        if (isMonitoring.get()) {
            Log.w(TAG, "Already monitoring");
            return;
        }

        isMonitoring.set(true);
        lastActivityTime.set(System.currentTimeMillis());

        // 启动Watchdog定时器，每10秒检查一次
        watchdogTimer = new Timer("AI-Watchdog");
        watchdogTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkAIHealth();
            }
        }, 10000, 10000);

        AILogger.i(TAG, "AI crash handler started monitoring");
    }

    /**
     * 停止监控
     */
    public void stopMonitoring() {
        if (!isMonitoring.get()) {
            return;
        }

        isMonitoring.set(false);

        if (watchdogTimer != null) {
            watchdogTimer.cancel();
            watchdogTimer = null;
        }

        AILogger.i(TAG, "AI crash handler stopped");
    }

    /**
     * 记录AI活动（每次AI开始处理时调用）
     */
    public void recordActivity() {
        aiActive.set(true);
        lastActivityTime.set(System.currentTimeMillis());
    }

    /**
     * 标记AI空闲（每次AI处理结束后调用）。
     * 只有活跃期间才做挂起检测，避免空闲时误报。
     */
    public void markIdle() {
        aiActive.set(false);
    }

    /**
     * 检查AI健康状态
     */
    private void checkAIHealth() {
        if (!isMonitoring.get()) {
            return;
        }

        mainHandler.post(() -> {
            try {
                // 1. 检查内存使用
                checkMemoryUsage();

                // 2. 检查AI处理线程状态
                checkAIThreadStatus();

                // 3. 检查系统资源
                checkSystemResources();
            } catch (Exception e) {
                Log.e(TAG, "Error in health check", e);
            }
        });
    }

    /**
     * 检查内存使用情况
     */
    private void checkMemoryUsage() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);

        long totalMemory = memInfo.totalMem;
        long availMem = memInfo.availMem;
        long usedMemory = totalMemory - availMem;
        float usagePercent = (float) usedMemory / totalMemory;

        // 检查内存使用率
        if (usagePercent > MEMORY_THRESHOLD) {
            String key = "memory_warning";
            long now = System.currentTimeMillis();

            // 避免重复记录（每分钟最多记录一次）
            if (!recordedIssues.containsKey(key) ||
                (now - recordedIssues.get(key)) > 60000) {
                recordedIssues.put(key, now);

                String msg = String.format(
                    "内存使用警告: %.1f%% (已用: %s, 总共: %s)",
                    usagePercent * 100,
                    formatMemory(usedMemory),
                    formatMemory(totalMemory)
                );

                AILogger.w(TAG, msg);
                Log.w(TAG, msg);

                if (crashCallback != null) {
                    crashCallback.onMemoryWarning(usagePercent, usedMemory, totalMemory);
                }

                // 如果内存严重不足，尝试触发GC
                if (usagePercent > 0.95f) {
                    AILogger.w(TAG, "内存严重不足，尝试触发GC");
                    System.gc();
                }
            }
        }

        // 检查可用内存是否低于阈值
        if (memInfo.lowMemory) {
            String msg = "系统内存不足 (可用: " + formatMemory(availMem) + ")";
            AILogger.e(TAG, msg);
            Log.e(TAG, msg);

            if (crashCallback != null) {
                crashCallback.onMemoryWarning(usagePercent, usedMemory, totalMemory);
            }
        }
    }

    /**
     * 检查AI处理线程状态
     * 仅在 AI 活跃（正在生成）且长时间无新心跳时才判定为可能挂起，
     * 避免把空闲时的线程池 WAITING 线程、或长时间未活动的正常空闲状态误判。
     */
    private void checkAIThreadStatus() {
        if (!aiActive.get()) {
            // AI 空闲时不做挂起检测，防止误报
            return;
        }

        long now = System.currentTimeMillis();
        long lastActivity = lastActivityTime.get();
        long waitTime = now - lastActivity;

        if (waitTime <= DEFAULT_TIMEOUT_MS) {
            return;
        }

        // 限频：同一问题 5 分钟内最多记录一次
        String key = "ai_possible_hang";
        Long lastReport = recordedIssues.get(key);
        if (lastReport != null && (now - lastReport) < REPORT_INTERVAL_MS) {
            return;
        }
        recordedIssues.put(key, now);

        String msg = String.format(
                "AI处理可能挂起: 活跃中但 %d 秒无新心跳",
                waitTime / 1000
        ) + "\n" + captureThreadDump();

        AILogger.w(TAG, msg);
        Log.w(TAG, msg);

        if (crashCallback != null) {
            crashCallback.onAIHang(waitTime);
        }
    }

    /**
     * 捕获当前线程栈快照，用于挂起诊断（限制长度，避免刷屏）
     */
    private String captureThreadDump() {
        StringBuilder sb = new StringBuilder();
        try {
            Thread[] threads = new Thread[Thread.activeCount() * 2];
            int count = Thread.enumerate(threads);
            int shown = 0;
            for (int i = 0; i < count && shown < 12; i++) {
                Thread thread = threads[i];
                if (thread == null) {
                    continue;
                }
                sb.append("· ").append(thread.getName())
                        .append(" [").append(thread.getState().name()).append("]");
                StackTraceElement[] stack = thread.getStackTrace();
                if (stack != null && stack.length > 0) {
                    sb.append("\n      ").append(stack[0].toString());
                }
                sb.append("\n");
                shown++;
            }
        } catch (Throwable t) {
            sb.append("[线程快照获取失败: ").append(t.getMessage()).append("]");
        }
        return sb.toString();
    }

    /**
     * 检查系统资源
     * 仅在进程驻留内存出现明显增长时才记录，避免每个 watchdog 节拍都刷一条日志。
     */
    private void checkSystemResources() {
        BufferedReader reader = null;
        try {
            // 检查 /proc/self/statm 来获取进程内存使用
            File statm = new File("/proc/self/statm");
            if (statm.exists()) {
                reader = new BufferedReader(new FileReader(statm));
                String line = reader.readLine();
                reader.close();
                reader = null;

                if (line != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        long size = Long.parseLong(parts[0]) * 4096; // 页大小通常是4KB
                        long resident = Long.parseLong(parts[1]) * 4096;

                        // 仅在驻留内存相对上次记录显著增长时记录（默认 +64MB），避免刷屏
                        Long lastResident = recordedIssues.get("last_resident");
                        if (lastResident == null || (resident - lastResident) > 64L * 1024 * 1024) {
                            recordedIssues.put("last_resident", resident);
                            AILogger.w(TAG, String.format(
                                    "进程内存增长明显: resident=%s, size=%s",
                                    formatMemory(resident),
                                    formatMemory(size)
                            ));
                        }
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            // 忽略读取错误
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 获取当前内存信息
     */
    public String getMemoryInfo() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);

        Runtime runtime = Runtime.getRuntime();
        long javaHeap = runtime.totalMemory() - runtime.freeMemory();
        long javaMaxHeap = runtime.maxMemory();

        return String.format(
            "系统: 可用=%s/%s | Java堆: 已用=%s/%s | 低内存=%s",
            formatMemory(memInfo.availMem),
            formatMemory(memInfo.totalMem),
            formatMemory(javaHeap),
            formatMemory(javaMaxHeap),
            memInfo.lowMemory
        );
    }

    /**
     * 格式化内存大小
     */
    private String formatMemory(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 记录崩溃信息
     */
    public void recordCrashInfo(String type, String message, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== AI崩溃信息 ==========\n");
        sb.append("时间: ").append(new java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            java.util.Locale.getDefault()
        ).format(new java.util.Date())).append("\n");
        sb.append("类型: ").append(type).append("\n");
        sb.append("消息: ").append(message).append("\n");

        if (throwable != null) {
            sb.append("异常: ").append(throwable.getClass().getName()).append("\n");
            sb.append("详情: ").append(throwable.getMessage()).append("\n");

            // 堆栈跟踪
            sb.append("堆栈:\n");
            for (StackTraceElement element : throwable.getStackTrace()) {
                sb.append("  at ").append(element.toString()).append("\n");
            }
        }

        // 添加内存信息
        sb.append("\n内存状态:\n");
        sb.append(getMemoryInfo()).append("\n");

        // 添加线程信息
        sb.append("\n活动线程:\n");
        sb.append("活动线程数: ").append(Thread.activeCount()).append("\n");

        String log = sb.toString();
        Log.e(TAG, log);
        AILogger.e(TAG, "AI Crash: " + type + " - " + message, throwable);

        if (crashCallback != null && throwable != null) {
            crashCallback.onNativeCrash(type, log);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stopMonitoring();
        crashCallback = null;
        recordedIssues.clear();
    }
}
