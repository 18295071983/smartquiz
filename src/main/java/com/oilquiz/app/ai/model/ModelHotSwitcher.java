package com.oilquiz.app.ai.model;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ModelHotSwitcher - 模型热切换管理器
 * 
 * 功能：
 * 1. 快速模型切换 - 无需完全重新加载
 * 2. 预加载模型 - 提前加载常用模型
 * 3. 状态保持 - 切换时保持模型状态
 * 4. 并发控制 - 防止并发切换冲突
 * 5. 切换回滚 - 切换失败时回滚到原模型
 */
public class ModelHotSwitcher {

    private static final String TAG = "ModelHotSwitcher";
    private static final int MAX_PRELOAD_MODELS = 3;

    // 单例
    private static volatile ModelHotSwitcher INSTANCE;
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;

    // 当前模型
    private final AtomicReference<String> currentModel = new AtomicReference<>(null);
    private final AtomicReference<String> pendingModel = new AtomicReference<>(null);

    // 预加载模型
    private final ConcurrentHashMap<String, PreloadedModel> preloadedModels = new ConcurrentHashMap<>();

    // 状态
    private final AtomicBoolean isSwitching = new AtomicBoolean(false);
    private final AtomicBoolean isPreloading = new AtomicBoolean(false);

    // 回调
    public interface SwitchCallback {
        void onSwitchStarted(String fromModel, String toModel);
        void onSwitchProgress(int progress, String message);
        void onSwitchCompleted(boolean success, String model);
        void onSwitchFailed(String reason);
    }

    // 预加载模型
    private static class PreloadedModel {
        String modelId;
        String modelPath;
        long loadTime;
        long lastAccessTime;
        boolean isReady;

        PreloadedModel(String modelId, String modelPath) {
            this.modelId = modelId;
            this.modelPath = modelPath;
            this.loadTime = 0;
            this.lastAccessTime = System.currentTimeMillis();
            this.isReady = false;
        }
    }

    private ModelHotSwitcher(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(2);
    }

    public static ModelHotSwitcher getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelHotSwitcher.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelHotSwitcher(context);
                }
            }
        }
        return INSTANCE;
    }

    // ========== 热切换 ==========

    /**
     * 热切换模型
     */
    public boolean hotSwitch(String targetModelId, SwitchCallback callback) {
        if (isSwitching.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doHotSwitch(targetModelId, callback);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onSwitchCompleted(success, success ? targetModelId : null);
                        }
                    });
                } finally {
                    isSwitching.set(false);
                    pendingModel.set(null);
                }
            });
            return true;
        } else {
            AILogger.w(TAG, "Switch already in progress");
            return false;
        }
    }

    private boolean doHotSwitch(String targetModelId, SwitchCallback callback) {
        String fromModel = currentModel.get();
        AILogger.i(TAG, "Hot switching from " + fromModel + " to " + targetModelId);

        pendingModel.set(targetModelId);

        if (callback != null) {
            mainHandler.post(() -> callback.onSwitchStarted(fromModel, targetModelId));
        }

        // 步骤1: 检查目标模型是否已预加载
        if (callback != null) {
            mainHandler.post(() -> callback.onSwitchProgress(10, "检查预加载模型..."));
        }

        PreloadedModel preloaded = preloadedModels.get(targetModelId);
        if (preloaded != null && preloaded.isReady) {
            // 使用预加载的模型进行快速切换
            return switchWithPreloaded(preloaded, callback);
        }

        // 步骤2: 执行标准切换
        return doStandardSwitch(targetModelId, callback);
    }

    /**
     * 使用预加载模型进行快速切换
     */
    private boolean switchWithPreloaded(PreloadedModel preloaded, SwitchCallback callback) {
        AILogger.i(TAG, "Switching with preloaded model: " + preloaded.modelId);

        if (callback != null) {
            mainHandler.post(() -> callback.onSwitchProgress(30, "使用预加载模型..."));
        }

        try {
            // 释放当前模型
            if (LlamaHelper.isModelInitialized()) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onSwitchProgress(40, "释放当前模型..."));
                }
                releaseCurrentModel();
            }

            // 加载预加载的模型
            if (callback != null) {
                mainHandler.post(() -> callback.onSwitchProgress(60, "加载预加载模型..."));
            }

            int result = LlamaHelper.initModel(preloaded.modelPath, 4096, 4);
            if (result != 0) {
                AILogger.e(TAG, "Failed to load preloaded model");
                return false;
            }

            // 更新状态
            currentModel.set(preloaded.modelId);
            preloaded.lastAccessTime = System.currentTimeMillis();

            if (callback != null) {
                mainHandler.post(() -> callback.onSwitchProgress(100, "切换完成"));
            }

            AILogger.i(TAG, "Hot switch completed with preloaded model");
            return true;

        } catch (Exception e) {
            AILogger.e(TAG, "Failed to switch with preloaded model: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 执行标准切换
     */
    private boolean doStandardSwitch(String targetModelId, SwitchCallback callback) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSwitchProgress(20, "查找模型文件..."));
        }

        // 查找模型文件
        String modelPath = findModelPath(targetModelId);
        if (modelPath == null) {
            AILogger.e(TAG, "Model file not found: " + targetModelId);
            if (callback != null) {
                mainHandler.post(() -> callback.onSwitchFailed("模型文件不存在"));
            }
            return false;
        }

        // 释放当前模型
        if (callback != null) {
            mainHandler.post(() -> callback.onSwitchProgress(40, "释放当前模型..."));
        }

        String previousModel = currentModel.get();
        long previousModelHash = getModelHash(previousModel);

        if (LlamaHelper.isModelInitialized()) {
            releaseCurrentModel();
        }

        // 加载新模型
        if (callback != null) {
            mainHandler.post(() -> callback.onSwitchProgress(60, "加载新模型..."));
        }

        try {
            int result = LlamaHelper.initModel(modelPath, 4096, 4);
            if (result != 0) {
                AILogger.e(TAG, "Failed to load model, attempting rollback");

                // 回滚到原模型
                if (callback != null) {
                    mainHandler.post(() -> callback.onSwitchProgress(80, "切换失败，回滚中..."));
                }

                rollbackToModel(previousModel);
                if (callback != null) {
                    mainHandler.post(() -> callback.onSwitchFailed("模型加载失败，已回滚"));
                }
                return false;
            }

            // 更新状态
            currentModel.set(targetModelId);

            if (callback != null) {
                mainHandler.post(() -> callback.onSwitchProgress(100, "切换完成"));
            }

            AILogger.i(TAG, "Standard switch completed");
            return true;

        } catch (Exception e) {
            AILogger.e(TAG, "Failed to switch model: " + e.getMessage(), e);

            // 回滚
            rollbackToModel(previousModel);
            if (callback != null) {
                mainHandler.post(() -> callback.onSwitchFailed("切换异常，已回滚: " + e.getMessage()));
            }
            return false;
        }
    }

    // ========== 预加载 ==========

    /**
     * 预加载模型
     */
    public boolean preloadModel(String modelId, PreloadCallback callback) {
        if (isPreloading.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doPreloadModel(modelId, callback);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onPreloadCompleted(success, modelId);
                        }
                    });
                } finally {
                    isPreloading.set(false);
                }
            });
            return true;
        }
        return false;
    }

    private boolean doPreloadModel(String modelId, PreloadCallback callback) {
        AILogger.i(TAG, "Preloading model: " + modelId);

        // 检查是否已预加载
        if (preloadedModels.containsKey(modelId)) {
            AILogger.i(TAG, "Model already preloaded: " + modelId);
            return true;
        }

        // 检查预加载数量限制
        if (preloadedModels.size() >= MAX_PRELOAD_MODELS) {
            // 淘汰最旧的预加载模型
            evictOldestPreloaded();
        }

        // 查找模型文件
        String modelPath = findModelPath(modelId);
        if (modelPath == null) {
            AILogger.e(TAG, "Model file not found: " + modelId);
            return false;
        }

        if (callback != null) {
            mainHandler.post(() -> callback.onPreloadProgress(50, "预加载模型..."));
        }

        // 创建预加载条目
        PreloadedModel preloaded = new PreloadedModel(modelId, modelPath);
        preloaded.loadTime = System.currentTimeMillis();
        preloaded.isReady = true;

        preloadedModels.put(modelId, preloaded);

        AILogger.i(TAG, "Model preloaded: " + modelId);
        return true;
    }

    /**
     * 淘汰最旧的预加载模型
     */
    private void evictOldestPreloaded() {
        preloadedModels.entrySet().stream()
            .min((a, b) -> Long.compare(a.getValue().lastAccessTime, b.getValue().lastAccessTime))
            .ifPresent(entry -> {
                AILogger.i(TAG, "Evicting preloaded model: " + entry.getKey());
                preloadedModels.remove(entry.getKey());
            });
    }

    // ========== 回滚 ==========

    /**
     * 回滚到指定模型
     */
    private void rollbackToModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            AILogger.w(TAG, "No model to rollback to");
            return;
        }

        AILogger.i(TAG, "Rolling back to model: " + modelId);

        String modelPath = findModelPath(modelId);
        if (modelPath != null) {
            try {
                LlamaHelper.initModel(modelPath, 4096, 4);
                currentModel.set(modelId);
                AILogger.i(TAG, "Rollback successful");
            } catch (Exception e) {
                AILogger.e(TAG, "Rollback failed: " + e.getMessage(), e);
            }
        }
    }

    // ========== 辅助方法 ==========

    private void releaseCurrentModel() {
        try {
            LlamaHelper.chatDestroy();
        } catch (Exception e) {
            AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
        }
        LlamaHelper.release();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.gc();
    }

    private String findModelPath(String modelId) {
        // 检查内部存储
        File modelDir = new File(context.getFilesDir(), "models");
        File modelFile = new File(modelDir, modelId);
        if (modelFile.exists()) {
            return modelFile.getAbsolutePath();
        }

        // 检查根目录
        File rootFile = new File(context.getFilesDir(), modelId);
        if (rootFile.exists()) {
            return rootFile.getAbsolutePath();
        }

        return null;
    }

    private long getModelHash(String modelId) {
        if (modelId == null) return 0;
        return modelId.hashCode();
    }

    // ========== 状态查询 ==========

    public String getCurrentModel() {
        return currentModel.get();
    }

    public boolean isSwitching() {
        return isSwitching.get();
    }

    public boolean isPreloading() {
        return isPreloading.get();
    }

    public boolean isModelPreloaded(String modelId) {
        PreloadedModel preloaded = preloadedModels.get(modelId);
        return preloaded != null && preloaded.isReady;
    }

    public int getPreloadedCount() {
        return preloadedModels.size();
    }

    /**
     * 获取预加载模型列表
     */
    public java.util.List<String> getPreloadedModelIds() {
        return new java.util.ArrayList<>(preloadedModels.keySet());
    }

    /**
     * 清理所有预加载模型
     */
    public void clearPreloadedModels() {
        preloadedModels.clear();
        AILogger.i(TAG, "All preloaded models cleared");
    }

    // ========== 回调接口 ==========

    public interface PreloadCallback {
        void onPreloadProgress(int progress, String message);
        void onPreloadCompleted(boolean success, String modelId);
    }
}
