package com.oilquiz.app.ai.monitor;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;

/**
 * PerformanceMonitor - AI推理性能监控器
 * 
 * 功能：
 * - 实时监控TPS (Tokens Per Second)
 * - 监控CPU使用率和内存占用
 * - 检测设备温度和热节流状态
 * - 计算平均TPS和总token数
 * 
 * 监控指标：
 * - currentTPS: 当前TPS
 * - avgTPS: 平均TPS
 * - cpuUsage: CPU使用率
 * - memoryUsageMB: 内存使用(MB)
 * - isThermalThrottling: 是否热节流
 * 
 * 使用方式：
 * PerformanceMonitor monitor = new PerformanceMonitor(context);
 * monitor.startMonitoring();
 * 
 * @author AI Team
 * @since 2024
 */
public class PerformanceMonitor {
    private static final String TAG = "PerformanceMonitor";
    private static final int MONITOR_INTERVAL_MS = 1000;
    
    private final Context context;
    private final Handler handler;
    private Runnable monitorRunnable;
    private boolean isMonitoring = false;
    
    private float currentTPS = 0.0f;
    private float avgTPS = 0.0f;
    private long totalTokens = 0;
    private long totalTimeMs = 0;
    private float cpuUsage = 0.0f;
    private long memoryUsageMB = 0;
    private boolean isThermalThrottling = false;
    private long lastInferenceTime = 0;
    
    public PerformanceMonitor(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    public void startMonitoring() {
        if (isMonitoring) return;
        
        isMonitoring = true;
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isMonitoring) return;
                
                collectMetrics();
                handler.postDelayed(this, MONITOR_INTERVAL_MS);
            }
        };
        
        handler.post(monitorRunnable);
        Log.i(TAG, "Performance monitoring started");
    }
    
    public void stopMonitoring() {
        isMonitoring = false;
        if (monitorRunnable != null) {
            handler.removeCallbacks(monitorRunnable);
        }
        Log.i(TAG, "Performance monitoring stopped");
    }
    
    private void collectMetrics() {
        cpuUsage = collectCpuUsage();
        memoryUsageMB = collectMemoryUsageMB();
        isThermalThrottling = checkThermalThrottling();
        
        Log.v(TAG, String.format("Performance: TPS=%.2f, CPU=%.1f%%, Mem=%dMB, Throttling=%b",
                                currentTPS, cpuUsage, memoryUsageMB, isThermalThrottling));
    }
    
    public void recordInference(int tokenCount, long timeMs) {
        totalTokens += tokenCount;
        totalTimeMs += timeMs;
        
        if (timeMs > 0) {
            currentTPS = (tokenCount * 1000.0f) / timeMs;
            avgTPS = (totalTokens * 1000.0f) / totalTimeMs;
        }
        
        lastInferenceTime = System.currentTimeMillis();
        
        Log.d(TAG, String.format("Inference completed: tokens=%d, time=%dms, TPS=%.2f",
                                tokenCount, timeMs, currentTPS));
    }
    
    private float collectCpuUsage() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String load = reader.readLine();
            String[] toks = load.split(" ");
            
            long idle1 = Long.parseLong(toks[4]);
            long cpu1 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + 
                       Long.parseLong(toks[4]) + Long.parseLong(toks[5]);
            
            try {
                Thread.sleep(100);
            } catch (Exception e) {}
            
            reader.seek(0);
            load = reader.readLine();
            reader.close();
            
            toks = load.split(" ");
            long idle2 = Long.parseLong(toks[4]);
            long cpu2 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + 
                       Long.parseLong(toks[4]) + Long.parseLong(toks[5]);
            
            float usage = (cpu2 - cpu1) - (idle2 - idle1);
            usage = (usage / (cpu2 - cpu1)) * 100;
            
            return Math.max(0, usage);
        } catch (Exception e) {
            return 0.0f;
        }
    }
    
    private long collectMemoryUsageMB() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memoryInfo);
            
            return (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024);
        } catch (Exception e) {
            return 0;
        }
    }
    
    private boolean checkThermalThrottling() {
        try {
            java.io.File file = new java.io.File("/sys/class/thermal/thermal_zone0/temp");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String tempStr = reader.readLine();
                reader.close();
                
                if (tempStr != null) {
                    int temp = Integer.parseInt(tempStr) / 1000;
                    return temp > 75;
                }
            }
        } catch (Exception e) {
        }
        
        return false;
    }
    
    public float getCurrentTPS() {
        return currentTPS;
    }
    
    public float getAvgTPS() {
        return avgTPS;
    }
    
    public float getCpuUsage() {
        return cpuUsage;
    }
    
    public long getMemoryUsageMB() {
        return memoryUsageMB;
    }
    
    public boolean isThermalThrottling() {
        return isThermalThrottling;
    }
    
    public long getTotalTokens() {
        return totalTokens;
    }
    
    public long getTotalTimeMs() {
        return totalTimeMs;
    }
    
    public long getLastInferenceTime() {
        return lastInferenceTime;
    }
    
    public boolean isMonitoring() {
        return isMonitoring;
    }
    
    public String getPerformanceReport() {
        return String.format(
            "Performance Report:\n" +
            "  Current TPS: %.2f\n" +
            "  Average TPS: %.2f\n" +
            "  Total Tokens: %d\n" +
            "  Total Time: %dms\n" +
            "  CPU Usage: %.1f%%\n" +
            "  Memory Usage: %dMB\n" +
            "  Thermal Throttling: %b",
            currentTPS, avgTPS, totalTokens, totalTimeMs,
            cpuUsage, memoryUsageMB, isThermalThrottling
        );
    }
    
    public void reset() {
        currentTPS = 0.0f;
        avgTPS = 0.0f;
        totalTokens = 0;
        totalTimeMs = 0;
        lastInferenceTime = 0;
        Log.i(TAG, "Performance stats reset");
    }
}
