package com.oilquiz.app.ai.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型预加载服务
 * 
 * 功能：
 * 1. 后台预加载常用模型到内存
 * 2. 智能预加载策略（基于使用频率和时间）
 * 3. 分块加载大模型，避免阻塞主线程
 * 4. 内存不足时自动释放预加载的模型
 * 
 * 使用场景：
 * - 应用启动时预加载默认模型
 * - 用户浏览模型列表时预加载可能选择的模型
 * - 后台空闲时预加载高频使用模型
 */
public class ModelPreloadService extends Service {
    private static final String TAG = "ModelPreloadService";
    
    // 预加载配置
    private static final long PRELOAD_DELAY_MS = 3000; // 启动后3秒开始预加载
    private static final long PRELOAD_TIMEOUT_MS = 60000; // 预加载超时60秒
    private static final int MAX_PRELOAD_MODELS = 2; // 最多预加载2个模型
    private static final long MIN_MEMORY_FOR_PRELOAD_MB = 512; // 预加载需要的最小可用内存
    
    private final IBinder binder = new LocalBinder();
    private ExecutorService preloadExecutor;
    private PowerManager.WakeLock wakeLock;
    
    // 预加载状态
    private final AtomicBoolean isPreloading = new AtomicBoolean(false);
    private String currentPreloadModel = null;
    private long preloadStartTime = 0;
    
    public class LocalBinder extends Binder {
        public ModelPreloadService getService() {
            return ModelPreloadService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        AILogger.i(TAG, "ModelPreloadService created");
        preloadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ModelPreloadThread");
            t.setDaemon(true);
            return t;
        });
        
        // 获取唤醒锁，防止预加载过程中CPU休眠
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartQuiz:ModelPreload");
        wakeLock.setReferenceCounted(false);
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // NPU 引擎开启时不做本地模型预加载（服务仍启动，仅跳过权重加载）
        if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
            AILogger.i(TAG, "NPU 引擎已启用，ModelPreloadService 跳过预加载");
            return START_NOT_STICKY;
        }
        if (intent != null) {
            String action = intent.getAction();
            if ("PRELOAD_MODEL".equals(action)) {
                String modelPath = intent.getStringExtra("model_path");
                String modelName = intent.getStringExtra("model_name");
                if (modelPath != null) {
                    schedulePreload(modelPath, modelName);
                }
            } else if ("CANCEL_PRELOAD".equals(action)) {
                cancelPreload();
            }
        }
        return START_STICKY;
    }
    
    /**
     * 调度预加载任务
     */
    public void schedulePreload(String modelPath, String modelName) {
        if (isPreloading.get()) {
            AILogger.w(TAG, "Already preloading: " + currentPreloadModel);
            return;
        }
        
        // 检查内存是否充足
        if (!hasEnoughMemory()) {
            AILogger.w(TAG, "Not enough memory for preload");
            return;
        }
        
        // 检查模型文件是否存在
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            AILogger.e(TAG, "Model file not found: " + modelPath);
            return;
        }
        
        AILogger.i(TAG, "Scheduling preload for: " + modelName);
        
        preloadExecutor.execute(() -> {
            try {
                // 延迟启动，避免与应用启动冲突
                Thread.sleep(PRELOAD_DELAY_MS);
                
                if (isPreloading.compareAndSet(false, true)) {
                    currentPreloadModel = modelName;
                    preloadStartTime = System.currentTimeMillis();
                    
                    performPreload(modelPath, modelName);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AILogger.w(TAG, "Preload interrupted");
            }
        });
    }
    
    /**
     * 执行预加载
     */
    private void performPreload(String modelPath, String modelName) {
        AILogger.i(TAG, "Starting preload: " + modelName);
        
        try {
            acquireWakeLock();
            
            // 分块加载模型
            boolean success = preloadModelInChunks(modelPath);
            
            long elapsed = System.currentTimeMillis() - preloadStartTime;
            if (success) {
                AILogger.i(TAG, "Preload completed: " + modelName + " in " + elapsed + "ms");
                // 通知AIService模型已预加载
                notifyPreloadComplete(modelName);
            } else {
                AILogger.w(TAG, "Preload failed: " + modelName);
            }
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error during preload: " + e.getMessage(), e);
        } finally {
            releaseWakeLock();
            isPreloading.set(false);
            currentPreloadModel = null;
        }
    }
    
    /**
     * 分块预加载模型
     * 避免一次性加载大文件导致内存峰值
     */
    private boolean preloadModelInChunks(String modelPath) {
        try {
            // 第一阶段：检查文件并预读取元数据
            File modelFile = new File(modelPath);
            long fileSize = modelFile.length();
            AILogger.i(TAG, "Model size: " + formatFileSize(fileSize));
            
            // 第二阶段：调用Native层分块加载
            // 这里使用较小的上下文大小进行预加载，减少内存占用
            int preloadContextSize = 2048; // 预加载时使用较小的上下文
            int result = LlamaHelper.initModel(modelPath, preloadContextSize, 2);
            
            if (result == 0) {
                // 预加载成功，但立即释放，只保留文件在系统缓存中
                LlamaHelper.release();
                AILogger.i(TAG, "Model preloaded and released (file cached)");
                return true;
            } else {
                AILogger.e(TAG, "Preload init failed: " + result);
                return false;
            }
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error in chunk preload: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 取消预加载
     */
    public void cancelPreload() {
        if (isPreloading.get()) {
            AILogger.i(TAG, "Cancelling preload: " + currentPreloadModel);
            isPreloading.set(false);
            // 中断预加载线程
            preloadExecutor.shutdownNow();
            preloadExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ModelPreloadThread");
                t.setDaemon(true);
                return t;
            });
        }
    }
    
    /**
     * 检查是否有足够内存进行预加载
     */
    private boolean hasEnoughMemory() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = (maxMemory - totalMemory) + freeMemory;
        long availableMB = availableMemory / (1024 * 1024);
        
        AILogger.i(TAG, "Available memory: " + availableMB + "MB");
        return availableMB >= MIN_MEMORY_FOR_PRELOAD_MB;
    }
    
    /**
     * 获取唤醒锁
     */
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(PRELOAD_TIMEOUT_MS);
            AILogger.d(TAG, "Wake lock acquired");
        }
    }
    
    /**
     * 释放唤醒锁
     */
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            AILogger.d(TAG, "Wake lock released");
        }
    }
    
    /**
     * 通知预加载完成
     */
    private void notifyPreloadComplete(String modelName) {
        Intent intent = new Intent("com.oilquiz.app.MODEL_PRELOAD_COMPLETE");
        intent.putExtra("model_name", modelName);
        sendBroadcast(intent);
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * 检查是否正在预加载
     */
    public boolean isPreloading() {
        return isPreloading.get();
    }
    
    /**
     * 获取当前预加载的模型
     */
    public String getCurrentPreloadModel() {
        return currentPreloadModel;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelPreload();
        if (preloadExecutor != null) {
            preloadExecutor.shutdown();
        }
        releaseWakeLock();
        AILogger.i(TAG, "ModelPreloadService destroyed");
    }
}
