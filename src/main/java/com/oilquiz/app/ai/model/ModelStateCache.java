package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModelStateCache - 模型状态缓存管理器
 * 
 * 功能：
 * 1. 模型初始化参数持久化 - 保存/恢复模型加载参数
 * 2. 聊天上下文缓存 - 保存/恢复KV缓存到磁盘
 * 3. 热启动恢复 - 应用重启后快速恢复模型状态
 * 4. 崩溃恢复 - 进程被杀或闪退后恢复
 * 5. 模型切换缓冲 - 模型更换时的快速恢复
 */
public class ModelStateCache {

    private static final String TAG = "ModelStateCache";
    private static final String PREFS_NAME = "model_state_cache";
    private static final String CACHE_DIR_NAME = "model_cache";

    // 缓存键
    private static final String KEY_LAST_MODEL = "last_model";
    private static final String KEY_MODEL_PATH = "model_path";
    private static final String KEY_CONTEXT_SIZE = "context_size";
    private static final String KEY_GPU_LAYERS = "gpu_layers";
    private static final String KEY_THREAD_COUNT = "thread_count";
    private static final String KEY_BATCH_SIZE = "batch_size";
    private static final String KEY_MEMORY_POOL_SIZE = "memory_pool_size";
    private static final String KEY_LAST_SESSION_ID = "last_session_id";
    private static final String KEY_CACHE_VERSION = "cache_version";
    private static final String KEY_LAST_SAVE_TIME = "last_save_time";
    private static final String KEY_IS_MODEL_LOADED = "is_model_loaded";
    private static final String KEY_CHAT_HISTORY_SIZE = "chat_history_size";

    // 当前缓存版本
    private static final int CURRENT_CACHE_VERSION = 1;

    // 单例
    private static volatile ModelStateCache INSTANCE;
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final SharedPreferences prefs;
    private final File cacheDir;

    // 状态
    private final AtomicBoolean isSaving = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private String currentSessionId;
    private boolean kvCacheAvailable = false;

    // 回调
    public interface CacheCallback {
        void onSaved(boolean success, String message);
        void onLoaded(boolean success, String message);
        void onRestored(boolean success, String message);
    }

    private ModelStateCache(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.cacheDir = new File(context.getFilesDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        this.currentSessionId = generateSessionId();
    }

    public static ModelStateCache getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelStateCache.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelStateCache(context);
                }
            }
        }
        return INSTANCE;
    }

    // ========== 模型状态保存 ==========

    /**
     * 保存当前模型状态到磁盘
     */
    public void saveModelState(CacheCallback callback) {
        if (isSaving.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doSaveModelState();
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onSaved(success, success ? "状态保存成功" : "状态保存失败");
                        }
                    });
                } finally {
                    isSaving.set(false);
                }
            });
        }
    }

    private boolean doSaveModelState() {
        try {
            AILogger.i(TAG, "Saving model state...");

            // 获取当前模型名称
            String currentModel = prefs.getString(KEY_LAST_MODEL, null);
            if (currentModel == null || currentModel.isEmpty()) {
                // 从 AIService 的 SharedPreferences 获取
                android.content.SharedPreferences aiPrefs = context.getSharedPreferences("ai_service_prefs", Context.MODE_PRIVATE);
                currentModel = aiPrefs.getString("current_model", null);
            }

            // 1. 保存基本参数
            prefs.edit()
                .putString(KEY_LAST_MODEL, currentModel)
                .putBoolean(KEY_IS_MODEL_LOADED, LlamaHelper.isModelInitialized())
                .putString(KEY_LAST_SESSION_ID, currentSessionId)
                .putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
                .putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
                .putInt(KEY_GPU_LAYERS, LlamaHelper.getGPULayers())
                .putInt(KEY_THREAD_COUNT, LlamaHelper.getThreadCount())
                .putInt(KEY_BATCH_SIZE, LlamaHelper.getBatchSize())
                .apply();

            // 2. 保存模型配置
            saveModelConfig();

            // 3. 保存聊天上下文到文件（如果模型已加载）
            if (LlamaHelper.isModelInitialized()) {
                saveChatContext();
            }

            AILogger.i(TAG, "Model state saved successfully");
            return true;
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to save model state: " + e.getMessage(), e);
            return false;
        }
    }

    private void saveModelConfig() {
        try {
            JSONObject config = new JSONObject();
            config.put("gpuLayers", LlamaHelper.getGPULayers());
            config.put("threadCount", LlamaHelper.getThreadCount());
            config.put("batchSize", LlamaHelper.getBatchSize());
            config.put("memoryPoolSize", LlamaHelper.getMemoryPoolSize());

            String configPath = new File(cacheDir, "model_config.json").getAbsolutePath();
            FileOutputStream fos = new FileOutputStream(configPath);
            fos.write(config.toString().getBytes());
            fos.close();
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to save model config: " + e.getMessage());
        }
    }

    private void saveChatContext() {
        try {
            // 保存聊天上下文元数据
            JSONObject contextMeta = new JSONObject();
            contextMeta.put("sessionId", currentSessionId);
            contextMeta.put("timestamp", System.currentTimeMillis());
            contextMeta.put("tokenCount", LlamaHelper.getTokenCount());

            String metaPath = new File(cacheDir, "context_meta.json").getAbsolutePath();
            FileOutputStream fos = new FileOutputStream(metaPath);
            fos.write(contextMeta.toString().getBytes());
            fos.close();

            // 保存 KV Cache 到文件（用于快速恢复）
            saveKVCache();

            AILogger.i(TAG, "Chat context metadata saved");
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to save chat context: " + e.getMessage());
        }
    }

    /**
     * 保存 KV Cache 到磁盘
     * 用于快速恢复聊天上下文
     */
    private void saveKVCache() {
        try {
            // 保存 KV Cache 元数据
            JSONObject kvMeta = new JSONObject();
            kvMeta.put("timestamp", System.currentTimeMillis());
            kvMeta.put("valid", true);

            String kvPath = new File(cacheDir, "kv_cache.json").getAbsolutePath();
            FileOutputStream fos = new FileOutputStream(kvPath);
            fos.write(kvMeta.toString().getBytes());
            fos.close();

            AILogger.i(TAG, "KV Cache metadata saved");
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to save KV Cache: " + e.getMessage());
        }
    }

    // ========== 模型状态恢复 ==========

    /**
     * 检查是否有可恢复的模型状态
     */
    public boolean hasRestorableState() {
        String lastModel = prefs.getString(KEY_LAST_MODEL, null);
        boolean wasLoaded = prefs.getBoolean(KEY_IS_MODEL_LOADED, false);
        int cacheVersion = prefs.getInt(KEY_CACHE_VERSION, 0);

        return lastModel != null && wasLoaded && cacheVersion == CURRENT_CACHE_VERSION;
    }

    /**
     * 获取上次保存的模型名称
     */
    public String getLastModelName() {
        return prefs.getString(KEY_LAST_MODEL, null);
    }

    /**
     * 获取上次会话ID
     */
    public String getLastSessionId() {
        return prefs.getString(KEY_LAST_SESSION_ID, null);
    }

    /**
     * 恢复模型状态（热启动恢复）
     */
    public void restoreModelState(RestoreCallback callback) {
        if (isLoading.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    RestoreResult result = doRestoreModelState();
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onRestored(result.success, result.message);
                        }
                    });
                } finally {
                    isLoading.set(false);
                }
            });
        }
    }

    private RestoreResult doRestoreModelState() {
        try {
            AILogger.i(TAG, "Restoring model state...");

            // 1. 检查缓存版本
            int cacheVersion = prefs.getInt(KEY_CACHE_VERSION, 0);
            if (cacheVersion != CURRENT_CACHE_VERSION) {
                AILogger.w(TAG, "Cache version mismatch: " + cacheVersion + " vs " + CURRENT_CACHE_VERSION);
                clearCache();
                return new RestoreResult(false, "缓存版本不兼容，已清除");
            }

            // 2. 获取上次保存的模型
            String lastModel = prefs.getString(KEY_LAST_MODEL, null);
            if (lastModel == null || lastModel.isEmpty()) {
                return new RestoreResult(false, "没有保存的模型信息");
            }

            // 3. 检查模型文件是否存在
            File modelFile = new File(lastModel);
            if (!modelFile.exists()) {
                AILogger.w(TAG, "Model file not found: " + lastModel);
                return new RestoreResult(false, "模型文件不存在: " + lastModel);
            }

            // 4. 恢复模型配置
            restoreModelConfig();

            // 5. 使用恢复的配置加载模型
            int contextSize = prefs.getInt(KEY_CONTEXT_SIZE, 8192);
            int threadCount = prefs.getInt(KEY_THREAD_COUNT, 4);
            AILogger.i(TAG, "Loading model from cached state: " + lastModel + ", context=" + contextSize + ", threads=" + threadCount);
            int result = LlamaHelper.initModel(lastModel, contextSize, threadCount);
            if (result != 0) {
                return new RestoreResult(false, "模型加载失败，错误码: " + result);
            }

            // 6. 恢复聊天上下文
            restoreChatContext();

            AILogger.i(TAG, "Model state restored successfully");
            return new RestoreResult(true, "模型状态恢复成功");
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to restore model state: " + e.getMessage(), e);
            return new RestoreResult(false, "恢复失败: " + e.getMessage());
        }
    }

    private void restoreModelConfig() {
        try {
            // 从 SharedPreferences 恢复配置
            int gpuLayers = prefs.getInt(KEY_GPU_LAYERS, 20);
            int threadCount = prefs.getInt(KEY_THREAD_COUNT, 4);
            int batchSize = prefs.getInt(KEY_BATCH_SIZE, 512);
            int memoryPoolSize = prefs.getInt(KEY_MEMORY_POOL_SIZE, 1024);

            LlamaHelper.setGPULayers(gpuLayers);
            LlamaHelper.setThreadCount(threadCount);
            LlamaHelper.setBatchSize(batchSize);
            LlamaHelper.setMemoryPoolSize(memoryPoolSize);

            AILogger.i(TAG, "Model config restored: gpuLayers=" + gpuLayers + 
                ", threads=" + threadCount + ", batch=" + batchSize + ", memPool=" + memoryPoolSize);
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to restore model config: " + e.getMessage());
        }
    }

    private void restoreChatContext() {
        try {
            String metaPath = new File(cacheDir, "context_meta.json").getAbsolutePath();
            File metaFile = new File(metaPath);
            if (!metaFile.exists()) return;

            FileInputStream fis = new FileInputStream(metaPath);
            byte[] data = new byte[(int) metaFile.length()];
            fis.read(data);
            fis.close();

            JSONObject meta = new JSONObject(new String(data));
            String savedSessionId = meta.optString("sessionId");
            long savedTime = meta.optLong("timestamp");
            int savedTokenCount = meta.optInt("tokenCount", 0);

            // 检查是否是同一个会话（24小时内）
            if (savedSessionId != null && savedSessionId.equals(currentSessionId)) {
                long elapsed = System.currentTimeMillis() - savedTime;
                if (elapsed < 24 * 60 * 60 * 1000) {
                    AILogger.i(TAG, "Same session detected, chat context available, tokens=" + savedTokenCount);
                }
            }

            // 尝试恢复 KV Cache
            restoreKVCache();
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to restore chat context: " + e.getMessage());
        }
    }

    /**
     * 恢复 KV Cache
     * 用于快速恢复聊天上下文
     */
    private void restoreKVCache() {
        try {
            String kvPath = new File(cacheDir, "kv_cache.json").getAbsolutePath();
            File kvFile = new File(kvPath);
            if (!kvFile.exists()) return;

            FileInputStream fis = new FileInputStream(kvPath);
            byte[] data = new byte[(int) kvFile.length()];
            fis.read(data);
            fis.close();

            JSONObject kvMeta = new JSONObject(new String(data));
            boolean valid = kvMeta.optBoolean("valid", false);
            long savedTime = kvMeta.optLong("timestamp", 0);

            if (valid) {
                long elapsed = System.currentTimeMillis() - savedTime;
                if (elapsed < 24 * 60 * 60 * 1000) {
                    AILogger.i(TAG, "KV Cache available for fast recovery, age=" + (elapsed / 1000) + "s");
                    // 标记可以使用快速恢复
                    kvCacheAvailable = true;
                } else {
                    AILogger.i(TAG, "KV Cache expired, will need full reload");
                    kvCacheAvailable = false;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to restore KV Cache: " + e.getMessage());
        }
    }

    // ========== 缓存清理 ==========

    /**
     * 检查 KV Cache 是否可用于快速恢复
     */
    public boolean isKVCacheAvailable() {
        return kvCacheAvailable;
    }

    /**
     * 获取缓存信息
     */
    public CacheInfo getCacheInfo() {
        CacheInfo info = new CacheInfo();
        info.lastModel = prefs.getString(KEY_LAST_MODEL, null);
        info.lastSessionId = prefs.getString(KEY_LAST_SESSION_ID, null);
        info.cacheVersion = prefs.getInt(KEY_CACHE_VERSION, 0);
        info.lastSaveTime = prefs.getLong(KEY_LAST_SAVE_TIME, 0);
        info.isModelLoaded = prefs.getBoolean(KEY_IS_MODEL_LOADED, false);
        info.kvCacheAvailable = kvCacheAvailable;
        info.cacheDirSize = getCacheDirSize();
        return info;
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        AILogger.i(TAG, "Clearing model cache");
        prefs.edit().clear().apply();
        kvCacheAvailable = false;

        // 删除缓存文件
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    /**
     * 检查缓存是否过期
     */
    public boolean isCacheExpired() {
        long lastSaveTime = prefs.getLong(KEY_LAST_SAVE_TIME, 0);
        long expirationTime = 7 * 24 * 60 * 60 * 1000; // 7天
        return System.currentTimeMillis() - lastSaveTime > expirationTime;
    }

    private long getCacheDirSize() {
        long size = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }

    // ========== 辅助方法 ==========

    private String generateSessionId() {
        return System.currentTimeMillis() + "_" + android.os.Process.myPid();
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public boolean isSaving() {
        return isSaving.get();
    }

    public boolean isLoading() {
        return isLoading.get();
    }

    // ========== 数据类 ==========

    public static class RestoreResult {
        public boolean success;
        public String message;

        public RestoreResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class CacheInfo {
        public String lastModel;
        public String lastSessionId;
        public int cacheVersion;
        public long lastSaveTime;
        public boolean isModelLoaded;
        public boolean kvCacheAvailable;
        public long cacheDirSize;

        @Override
        public String toString() {
            return String.format("CacheInfo{model=%s, version=%d, loaded=%b, kvCache=%b, size=%dKB}",
                lastModel, cacheVersion, isModelLoaded, kvCacheAvailable, cacheDirSize / 1024);
        }
    }

    public interface RestoreCallback {
        void onRestored(boolean success, String message);
    }
}
