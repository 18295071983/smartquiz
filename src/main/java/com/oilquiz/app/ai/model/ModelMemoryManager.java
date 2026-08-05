package com.oilquiz.app.ai.model;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ModelMemoryManager - 本地模型内存管理器
 * 
 * 功能：
 * 1. 内存映射（mmap）控制 - 支持磁盘映射和缓存转换
 * 2. 内存压力监控 - 实时监控系统内存使用
 * 3. 安全模型切换 - 先卸载旧模型再加载新模型
 * 4. 内存优化策略 - 根据系统状态调整模型加载参数
 */
public class ModelMemoryManager {

    private static final String TAG = "ModelMemoryManager";

    // 单例
    private static volatile ModelMemoryManager INSTANCE;
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;

    // 内存配置
    private boolean useMmap = true;           // 是否使用内存映射
    private boolean useMlock = false;         // 是否锁定内存（防止换出）
    private int maxMemoryMB = 0;              // 最大内存限制
    private int currentModelMemoryMB = 0;    // 当前模型内存占用

    // 状态
    private final AtomicBoolean isSwitching = new AtomicBoolean(false);
    private final AtomicBoolean isUnloading = new AtomicBoolean(false);
    private final AtomicLong lastMemoryCheckTime = new AtomicLong(0);
    private WeakReference<MemoryStateListener> listenerRef;

    // 内存状态
    public enum MemoryState {
        NORMAL,         // 正常
        LOW,            // 内存不足
        CRITICAL,       // 内存严重不足
        OUT_OF_MEMORY   // 内存溢出
    }

    // 回调接口
    public interface MemoryStateListener {
        void onMemoryStateChanged(MemoryState state, long availableMB, long requiredMB);
        void onModelSwitchProgress(String modelName, int progress, String message);
        void onModelUnloadComplete(String modelName, long freedMB);
    }

    private ModelMemoryManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        initMemoryConfig();
    }

    public static ModelMemoryManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelMemoryManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelMemoryManager(context);
                }
            }
        }
        return INSTANCE;
    }

    // ========== 初始化 ==========

    private void initMemoryConfig() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);
            maxMemoryMB = (int) (memInfo.totalMem / 1024 / 1024);
            AILogger.i(TAG, "Total device memory: " + maxMemoryMB + "MB");
        }

        // 根据设备内存设置默认配置
        if (maxMemoryMB <= 2048) {
            // 2GB以下设备：启用mmap（按需从磁盘加载模型权重，减少物理内存占用）
            // 注意：之前 useMmap=false 是反的——低内存设备更需要 mmap 来避免一次性把模型权重全读进物理内存
            useMmap = true;
            useMlock = false;
        } else if (maxMemoryMB <= 4096) {
            // 2-4GB设备：启用mmap，禁用mlock
            useMmap = true;
            useMlock = false;
        } else {
            // 4GB以上设备：启用mmap，可选mlock
            useMmap = true;
            useMlock = false;
        }

        AILogger.i(TAG, "Memory config: mmap=" + useMmap + ", mlock=" + useMlock);
    }

    // ========== 内存映射控制 ==========

    /**
     * 设置是否使用内存映射（mmap）
     * mmap允许模型文件从磁盘按需加载到内存，减少初始内存占用
     */
    public void setUseMmap(boolean useMmap) {
        this.useMmap = useMmap;
        AILogger.i(TAG, "mmap mode set to: " + useMmap);
    }

    public boolean isUseMmap() {
        return useMmap;
    }

    /**
     * 设置是否锁定内存（mlock）
     * mlock防止模型数据被换出到磁盘，提高推理速度但增加内存占用
     */
    public void setUseMlock(boolean useMlock) {
        this.useMlock = useMlock;
        AILogger.i(TAG, "mlock mode set to: " + useMlock);
    }

    public boolean isUseMlock() {
        return useMlock;
    }

    // ========== 内存监控 ==========

    /**
     * 获取可用内存（MB）
     */
    public long getAvailableMemoryMB() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        return memInfo.availMem / 1024 / 1024;
    }

    /**
     * 获取当前内存状态
     */
    public MemoryState getMemoryState() {
        long availableMB = getAvailableMemoryMB();
        long requiredMB = currentModelMemoryMB > 0 ? currentModelMemoryMB : 512; // 默认512MB

        if (availableMB < requiredMB * 0.25) {
            return MemoryState.OUT_OF_MEMORY;
        } else if (availableMB < requiredMB * 0.5) {
            return MemoryState.CRITICAL;
        } else if (availableMB < requiredMB * 0.75) {
            return MemoryState.LOW;
        }
        return MemoryState.NORMAL;
    }

    /**
     * 检查是否有足够内存加载模型
     */
    public boolean hasEnoughMemory(long requiredMB) {
        long availableMB = getAvailableMemoryMB();
        boolean hasEnough = availableMB >= requiredMB * 1.2; // 预留20%余量
        AILogger.i(TAG, "Memory check: available=" + availableMB + "MB, required=" + requiredMB + "MB, hasEnough=" + hasEnough);
        return hasEnough;
    }

    /**
     * 检查是否有足够内存（带模型大小估算）
     */
    public boolean hasEnoughMemoryForModel(String modelPath) {
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            AILogger.w(TAG, "Model file not found: " + modelPath);
            return false;
        }

        long modelSizeMB = modelFile.length() / 1024 / 1024;
        // 模型加载通常需要 1.5-2 倍模型大小的内存
        long requiredMB = useMmap ? (long) (modelSizeMB * 1.5) : (long) (modelSizeMB * 2);
        return hasEnoughMemory(requiredMB);
    }

    // ========== 模型切换 ==========

    /**
     * 安全切换模型 - 先卸载旧模型，再加载新模型
     * 这是推荐的模型切换方式，可以防止内存溢出
     */
    public boolean switchModelSafely(String newModelPath, ModelSwitchCallback callback) {
        if (isSwitching.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doSwitchModel(newModelPath, callback);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onComplete(success, success ? "模型切换成功" : "模型切换失败");
                        }
                    });
                } finally {
                    isSwitching.set(false);
                }
            });
            return true;
        } else {
            AILogger.w(TAG, "Model switch already in progress");
            return false;
        }
    }

    private boolean doSwitchModel(String newModelPath, ModelSwitchCallback callback) {
        String modelName = new File(newModelPath).getName();
        AILogger.i(TAG, "Starting safe model switch to: " + modelName);

        // 步骤1：检查新模型文件
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(0, "检查模型文件..."));
        }
        File modelFile = new File(newModelPath);
        if (!modelFile.exists()) {
            AILogger.e(TAG, "Model file not found: " + newModelPath);
            return false;
        }

        // 步骤2：检查内存
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(10, "检查内存..."));
        }
        long modelSizeMB = modelFile.length() / 1024 / 1024;
        if (!hasEnoughMemory(modelSizeMB)) {
            AILogger.e(TAG, "Not enough memory for model: " + modelSizeMB + "MB");
            // 尝试释放一些内存
            System.gc();
            System.runFinalization();
            if (!hasEnoughMemory(modelSizeMB)) {
                AILogger.e(TAG, "Still not enough memory after GC");
                return false;
            }
        }

        // 步骤3：卸载旧模型
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(20, "卸载旧模型..."));
        }
        if (LlamaHelper.isModelInitialized()) {
            long memBefore = (long) LlamaHelper.getMemoryUsage();
            AILogger.i(TAG, "Unloading old model, current memory: " + memBefore + "MB");

            try {
                LlamaHelper.chatDestroy();
            } catch (Exception e) {
                AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
            }
            LlamaHelper.release();

            // 等待内存释放
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.gc();
            long memAfter = (long) LlamaHelper.getMemoryUsage();
            long freedMB = (long) (memBefore - memAfter);
            AILogger.i(TAG, "Old model unloaded, freed: " + freedMB + "MB");

            if (callback != null) {
                mainHandler.post(() -> callback.onProgress(40, "已释放 " + freedMB + "MB 内存"));
            }
        }

        // 步骤4：配置加载参数
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(50, "配置加载参数..."));
        }
        configureLoadParams();

        // 步骤5：加载新模型
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(60, "加载新模型..."));
        }
        AILogger.i(TAG, "Loading new model: " + newModelPath);
        long loadStart = System.currentTimeMillis();

        int result = LlamaHelper.initModel(newModelPath, 4096, 4);
        long loadTime = System.currentTimeMillis() - loadStart;

        if (result != 0) {
            AILogger.e(TAG, "Failed to load model, error code: " + result);
            return false;
        }

        AILogger.i(TAG, "Model loaded successfully in " + loadTime + "ms");
        currentModelMemoryMB = (int) (LlamaHelper.getMemoryUsage());

        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(90, "模型加载完成，耗时: " + (loadTime / 1000.0) + "秒"));
        }

        // 步骤6：验证加载
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(95, "验证模型状态..."));
        }
        if (!LlamaHelper.isModelInitialized()) {
            AILogger.e(TAG, "Model loaded but not initialized");
            return false;
        }

        AILogger.i(TAG, "Model switch completed successfully. Memory usage: " + currentModelMemoryMB + "MB");
        return true;
    }

    /**
     * 配置加载参数
     */
    private void configureLoadParams() {
        // 根据内存状态调整参数
        MemoryState state = getMemoryState();
        switch (state) {
            case CRITICAL:
            case OUT_OF_MEMORY:
                // 内存不足时使用最小配置
                LlamaHelper.setGPULayers(0);
                LlamaHelper.setMemoryPoolSize(256);
                LlamaHelper.setBatchSize(128);
                LlamaHelper.setThreadCount(2);
                AILogger.i(TAG, "Using minimal config due to low memory");
                break;
            case LOW:
                // 内存较低时使用中等配置
                LlamaHelper.setGPULayers(10);
                LlamaHelper.setMemoryPoolSize(512);
                LlamaHelper.setBatchSize(256);
                LlamaHelper.setThreadCount(4);
                AILogger.i(TAG, "Using medium config due to low memory");
                break;
            default:
                // 正常内存使用默认配置
                LlamaHelper.setGPULayers(20);
                LlamaHelper.setMemoryPoolSize(1024);
                LlamaHelper.setBatchSize(512);
                LlamaHelper.setThreadCount(4);
                AILogger.i(TAG, "Using default config");
                break;
        }
    }

    /**
     * 卸载当前模型
     */
    public boolean unloadCurrentModel(UnloadModelCallback callback) {
        if (isUnloading.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doUnloadModel(callback);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onComplete(success, success ? "模型卸载成功" : "模型卸载失败");
                        }
                    });
                } finally {
                    isUnloading.set(false);
                }
            });
            return true;
        }
        return false;
    }

    private boolean doUnloadModel(UnloadModelCallback callback) {
        if (!LlamaHelper.isModelInitialized()) {
            AILogger.i(TAG, "No model to unload");
            return true;
        }

        long memBefore = (long) LlamaHelper.getMemoryUsage();
        AILogger.i(TAG, "Unloading model, current memory: " + memBefore + "MB");

        try {
            LlamaHelper.chatDestroy();
        } catch (Exception e) {
            AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
        }
        LlamaHelper.release();

        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.gc();

        long memAfter = (long) LlamaHelper.getMemoryUsage();
        long freedMB = memBefore - memAfter;
        AILogger.i(TAG, "Model unloaded, freed: " + freedMB + "MB");

        currentModelMemoryMB = 0;

        if (callback != null) {
            mainHandler.post(() -> callback.onUnloaded(freedMB));
        }

        return true;
    }

    // ========== 缓存管理 ==========

    /**
     * 清理模型缓存
     */
    public void clearModelCache() {
        AILogger.i(TAG, "Clearing model cache");
        System.gc();
        System.runFinalization();
    }

    /**
     * 获取当前内存使用情况
     */
    public MemoryInfo getCurrentMemoryInfo() {
        MemoryInfo info = new MemoryInfo();
        info.availableMB = getAvailableMemoryMB();
        info.totalMB = maxMemoryMB;
        info.currentModelMB = currentModelMemoryMB;
        info.memoryState = getMemoryState();
        info.isUsingMmap = useMmap;
        info.isUsingMlock = useMlock;
        return info;
    }

    // ========== 状态查询 ==========

    public boolean isSwitching() {
        return isSwitching.get();
    }

    public boolean isUnloading() {
        return isUnloading.get();
    }

    public int getCurrentModelMemoryMB() {
        return currentModelMemoryMB;
    }

    // ========== 监听器 ==========

    public void setListener(MemoryStateListener listener) {
        this.listenerRef = new WeakReference<>(listener);
    }

    // ========== 数据类 ==========

    public static class MemoryInfo {
        public long availableMB;
        public long totalMB;
        public int currentModelMB;
        public MemoryState memoryState;
        public boolean isUsingMmap;
        public boolean isUsingMlock;

        @Override
        public String toString() {
            return String.format("MemoryInfo{available=%dMB, total=%dMB, model=%dMB, state=%s, mmap=%b, mlock=%b}",
                availableMB, totalMB, currentModelMB, memoryState, isUsingMmap, isUsingMlock);
        }
    }

    // ========== 回调接口 ==========

    public interface ModelSwitchCallback {
        void onProgress(int progress, String message);
        void onComplete(boolean success, String message);
    }

    public interface UnloadModelCallback {
        void onUnloaded(long freedMB);
        void onComplete(boolean success, String message);
    }
}
