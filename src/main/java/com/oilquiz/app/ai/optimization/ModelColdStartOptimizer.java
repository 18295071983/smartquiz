package com.oilquiz.app.ai.optimization;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModelColdStartOptimizer - 模型冷启动优化器
 * 
 * 功能：
 * 1. 模型元数据缓存 - 避免重复解析模型头信息
 * 2. 内存映射优化 - 使用 mmap 加速模型加载
 * 3. 分层预加载 - 增量加载模型层
 * 4. 后台预热 - 空闲时预加载常用模型
 * 5. 启动加速 - 记录加载历史，优化下次启动
 * 
 * 原理：
 * - 第一次加载：完整加载并记录元数据和加载参数
 * - 后续加载：使用缓存的元数据，优化 mmap 和线程配置
 * - 预加载：在后台提前将模型文件读入系统缓存
 */
public class ModelColdStartOptimizer {
    private static final String TAG = "ModelColdStartOptimizer";
    private static final String PREFS_NAME = "model_cold_start_prefs";
    private static final String KEY_LAST_MODEL = "last_model";
    private static final String KEY_LOAD_COUNT = "load_count_%s";
    private static final String KEY_LAST_LOAD_TIME = "last_load_time_%s";
    private static final String KEY_BEST_LOAD_TIME = "best_load_time_%s";
    private static final String KEY_AVG_LOAD_TIME = "avg_load_time_%s";
    private static final String KEY_PRELOADED = "preloaded_%s";
    
    // 元数据持久化键
    private static final String KEY_META_PATH = "meta_path_%s";
    private static final String KEY_META_SIZE = "meta_size_%s";
    private static final String KEY_META_LOAD_TIME = "meta_load_time_%s";
    private static final String KEY_META_THREADS = "meta_threads_%s";
    private static final String KEY_META_GPU_LAYERS = "meta_gpu_layers_%s";
    private static final String KEY_META_CONTEXT = "meta_context_%s";
    private static final String KEY_META_TIMESTAMP = "meta_timestamp_%s";
    
    // 预加载配置
    private static final int MAX_PRELOAD_THREADS = 2;
    private static final long PRELOAD_CHUNK_SIZE = 64 * 1024 * 1024; // 64MB per chunk
    private static final long MAX_PRELOAD_TIME_MS = 30000; // 30 seconds max
    
    // 缓存配置
    private static final long CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000; // 7 days
    
    private final SharedPreferences prefs;
    private final ExecutorService preloadExecutor;
    private final ConcurrentHashMap<String, ModelMetadata> metadataCache;
    private final AtomicBoolean isPreloading;
    
    /**
     * 模型元数据
     */
    public static class ModelMetadata {
        public final String modelPath;
        public final long fileSize;
        public final long loadTimeMs;
        public final int optimalThreads;
        public final int optimalGpuLayers;
        public final int optimalContextSize;
        public final long timestamp;
        
        public ModelMetadata(String modelPath, long fileSize, long loadTimeMs,
                           int optimalThreads, int optimalGpuLayers, int optimalContextSize) {
            this.modelPath = modelPath;
            this.fileSize = fileSize;
            this.loadTimeMs = loadTimeMs;
            this.optimalThreads = optimalThreads;
            this.optimalGpuLayers = optimalGpuLayers;
            this.optimalContextSize = optimalContextSize;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return "ModelMetadata{" +
                    "size=" + (fileSize / 1024 / 1024) + "MB" +
                    ", loadTime=" + loadTimeMs + "ms" +
                    ", threads=" + optimalThreads +
                    ", gpuLayers=" + optimalGpuLayers +
                    ", context=" + optimalContextSize +
                    '}';
        }
    }
    
    /**
     * 加载统计信息
     */
    public static class LoadStats {
        public final int loadCount;
        public final long lastLoadTimeMs;
        public final long bestLoadTimeMs;
        public final double avgLoadTimeMs;
        public final boolean isCached;
        
        public LoadStats(int loadCount, long lastLoadTimeMs, long bestLoadTimeMs, 
                        double avgLoadTimeMs, boolean isCached) {
            this.loadCount = loadCount;
            this.lastLoadTimeMs = lastLoadTimeMs;
            this.bestLoadTimeMs = bestLoadTimeMs;
            this.avgLoadTimeMs = avgLoadTimeMs;
            this.isCached = isCached;
        }
        
        @Override
        public String toString() {
            return String.format("LoadStats{count=%d, last=%dms, best=%dms, avg=%.0fms, cached=%b}",
                    loadCount, lastLoadTimeMs, bestLoadTimeMs, avgLoadTimeMs, isCached);
        }
    }
    
    public ModelColdStartOptimizer(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.preloadExecutor = Executors.newFixedThreadPool(MAX_PRELOAD_THREADS, r -> {
            Thread t = new Thread(r, "ModelPreload");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.metadataCache = new ConcurrentHashMap<>();
        this.isPreloading = new AtomicBoolean(false);
        
        // 启动时从 SharedPreferences 加载元数据缓存
        loadMetadataFromPrefs();
    }
    
    // ========== 模型加载优化 ==========
    
    /**
     * 优化模型加载参数
     * 根据历史加载记录和模型特性，返回最优的加载参数
     * 
     * @param modelPath 模型路径
     * @param requestedContextSize 请求的上下文大小
     * @return 优化后的加载参数
     */
    public LoadParams getOptimizedLoadParams(String modelPath, int requestedContextSize) {
        LoadParams params = new LoadParams();
        params.modelPath = modelPath;
        
        // 获取模型文件信息
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            AILogger.e(TAG, "Model file not found: " + modelPath);
            return params;
        }
        
        long fileSize = modelFile.length();
        String modelKey = getModelKey(modelPath);
        
        // 获取历史加载统计
        LoadStats stats = getLoadStats(modelKey);
        
        // 根据历史数据优化参数
        if (stats.loadCount > 0) {
            AILogger.i(TAG, "Found load history for model: " + modelKey);
            AILogger.i(TAG, "Stats: " + stats);
            
            // 使用历史最优线程数
            params.useOptimalThreads = true;
            params.contextSize = Math.min(requestedContextSize, 16384);
            
            // 根据历史加载时间判断是否需要预加载
            if (stats.avgLoadTimeMs > 5000) {
                AILogger.i(TAG, "Model historically slow to load, enabling pre-warming");
                params.enablePreWarming = true;
            }
        } else {
            AILogger.i(TAG, "No load history for model: " + modelKey);
            params.contextSize = requestedContextSize;
        }
        
        // 根据文件大小优化
        if (fileSize > 2L * 1024 * 1024 * 1024) {
            // 大模型（>2GB）：使用 mmap 和更多线程
            AILogger.i(TAG, "Large model detected, enabling mmap optimization");
            params.useMmap = true;
            params.enableChunkedPreload = true;
        } else if (fileSize > 500 * 1024 * 1024) {
            // 中等模型（500MB-2GB）：使用 mmap
            params.useMmap = true;
        }
        
        // 检查是否已预加载
        if (isModelPreloaded(modelPath)) {
            AILogger.i(TAG, "Model already preloaded, load should be fast");
            params.preloaded = true;
        }
        
        AILogger.i(TAG, "Optimized params: " + params);
        return params;
    }
    
    /**
     * 记录模型加载完成
     * 更新加载统计和元数据缓存
     * 
     * @param modelPath 模型路径
     * @param loadTimeMs 加载时间（毫秒）
     * @param success 是否成功
     */
    public void recordModelLoad(String modelPath, long loadTimeMs, boolean success) {
        String modelKey = getModelKey(modelPath);
        
        // 更新加载次数
        int loadCount = prefs.getInt(String.format(KEY_LOAD_COUNT, modelKey), 0) + 1;
        prefs.edit()
                .putInt(String.format(KEY_LOAD_COUNT, modelKey), loadCount)
                .putLong(String.format(KEY_LAST_LOAD_TIME, modelKey), loadTimeMs)
                .putString(KEY_LAST_MODEL, modelPath)
                .apply();
        
        // 更新最优加载时间
        long bestLoadTime = prefs.getLong(String.format(KEY_BEST_LOAD_TIME, modelKey), Long.MAX_VALUE);
        if (loadTimeMs < bestLoadTime) {
            prefs.edit()
                    .putLong(String.format(KEY_BEST_LOAD_TIME, modelKey), loadTimeMs)
                    .apply();
            AILogger.i(TAG, "New best load time: " + loadTimeMs + "ms");
        }
        
        // 更新平均加载时间（指数移动平均）
        float avgLoadTime = prefs.getFloat(String.format(KEY_AVG_LOAD_TIME, modelKey), 0);
        if (loadCount == 1) {
            avgLoadTime = loadTimeMs;
        } else {
            avgLoadTime = (float) (avgLoadTime * 0.8 + loadTimeMs * 0.2);
        }
        prefs.edit()
                .putFloat(String.format(KEY_AVG_LOAD_TIME, modelKey), avgLoadTime)
                .apply();
        
        // 保存模型元数据
        if (success) {
            saveModelMetadata(modelPath, loadTimeMs);
        }
        
        AILogger.i(TAG, "Recorded load: " + modelPath + " (" + loadTimeMs + "ms, count=" + loadCount + ")");
    }
    
    /**
     * 获取加载统计
     */
    public LoadStats getLoadStats(String modelKey) {
        int loadCount = prefs.getInt(String.format(KEY_LOAD_COUNT, modelKey), 0);
        long lastLoadTime = prefs.getLong(String.format(KEY_LAST_LOAD_TIME, modelKey), 0);
        long bestLoadTime = prefs.getLong(String.format(KEY_BEST_LOAD_TIME, modelKey), 0);
        float avgLoadTime = prefs.getFloat(String.format(KEY_AVG_LOAD_TIME, modelKey), 0);
        boolean isCached = metadataCache.containsKey(modelKey);
        
        return new LoadStats(loadCount, lastLoadTime, bestLoadTime, avgLoadTime, isCached);
    }
    
    // ========== 模型预加载 ==========
    
    /**
     * 预加载模型文件到系统缓存
     * 这不会加载模型到内存，只是让系统缓存文件内容
     * 
     * @param modelPath 模型路径
     * @param callback 预加载回调
     */
    public void preloadModelFile(String modelPath, PreloadCallback callback) {
        if (isPreloading.compareAndSet(false, true)) {
            preloadExecutor.execute(() -> doPreloadModelFile(modelPath, callback));
        } else {
            if (callback != null) {
                callback.onPreloadComplete(false, "Already preloading");
            }
        }
    }
    
    private void doPreloadModelFile(String modelPath, PreloadCallback callback) {
        long startTime = System.currentTimeMillis();
        
        try {
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                notifyPreloadComplete(callback, false, "Model file not found");
                return;
            }
            
            long fileSize = modelFile.length();
            AILogger.i(TAG, "Preloading model file: " + modelFile.getName() + " (" + formatSize(fileSize) + ")");
            
            // 分块预读取文件到系统缓存
            int numChunks = (int) Math.ceil((double) fileSize / PRELOAD_CHUNK_SIZE);
            long chunkSize = Math.min(PRELOAD_CHUNK_SIZE, fileSize);
            
            try (RandomAccessFile raf = new RandomAccessFile(modelPath, "r");
                 FileChannel channel = raf.getChannel()) {
                
                byte[] buffer = new byte[8192]; // 8KB buffer for sequential read
                
                for (int i = 0; i < numChunks; i++) {
                    long startPos = i * chunkSize;
                    long endPos = Math.min((i + 1) * chunkSize, fileSize);
                    long mapSize = endPos - startPos;
                    
                    AILogger.d(TAG, "Preloading chunk " + (i + 1) + "/" + numChunks);
                    
                    // 使用内存映射读取文件块
                    MappedByteBuffer mappedBuffer = channel.map(
                            FileChannel.MapMode.READ_ONLY, startPos, mapSize);
                    
                    // 顺序读取，触发系统缓存
                    while (mappedBuffer.hasRemaining()) {
                        int toRead = Math.min(buffer.length, mappedBuffer.remaining());
                        mappedBuffer.get(buffer, 0, toRead);
                    }
                    
                    // 清理映射缓冲区
                    mappedBuffer.clear();
                    
                    // 计算进度
                    int progress = (int) (((i + 1) * 100) / numChunks);
                    notifyPreloadProgress(callback, progress);
                    
                    // 检查超时
                    if (System.currentTimeMillis() - startTime > MAX_PRELOAD_TIME_MS) {
                        AILogger.w(TAG, "Preload timeout, stopping");
                        break;
                    }
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            AILogger.i(TAG, "Model file preloaded in " + elapsed + "ms");
            
            // 标记模型已预加载
            markModelPreloaded(modelPath);
            
            notifyPreloadComplete(callback, true, null);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error preloading model: " + e.getMessage(), e);
            notifyPreloadComplete(callback, false, e.getMessage());
        } finally {
            isPreloading.set(false);
        }
    }
    
    /**
     * 预热模型加载
     * 使用小上下文预加载模型，然后释放，让文件在系统缓存中
     * 
     * 注意：此方法会检查模型是否正在使用，避免冲突
     * 
     * @param modelPath 模型路径
     * @param callback 预热回调
     */
    public void warmUpModel(String modelPath, WarmUpCallback callback) {
        AILogger.i(TAG, "Warming up model: " + modelPath);

        preloadExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();

            try {
                // 使用 synchronized 确保线程安全，避免与其他操作冲突
                synchronized (LlamaHelper.class) {
                    // 检查模型是否正在使用
                    if (LlamaHelper.isModelInitialized()) {
                        AILogger.w(TAG, "Model is currently in use, skipping warm-up");
                        if (callback != null) {
                            callback.onWarmUpComplete(false, 0);
                        }
                        return;
                    }

                    // 使用最小上下文预加载
                    int warmUpContextSize = 2048;
                    int warmUpThreads = 2;

                    AILogger.i(TAG, "Warm-up loading with context=" + warmUpContextSize + ", threads=" + warmUpThreads);

                    int result = LlamaHelper.initModel(modelPath, warmUpContextSize, warmUpThreads);

                    if (result == 0) {
                        // 预热成功，立即释放
                        LlamaHelper.release();

                        long elapsed = System.currentTimeMillis() - startTime;
                        AILogger.i(TAG, "Model warm-up completed in " + elapsed + "ms");

                        // 标记模型已预加载
                        markModelPreloaded(modelPath);

                        if (callback != null) {
                            callback.onWarmUpComplete(true, elapsed);
                        }
                    } else {
                        AILogger.e(TAG, "Model warm-up failed: " + result);
                        if (callback != null) {
                            callback.onWarmUpComplete(false, 0);
                        }
                    }
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Error during warm-up: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onWarmUpComplete(false, 0);
                }
            }
        });
    }
    
    // ========== 元数据缓存（持久化） ==========
    
    /**
     * 保存模型元数据到缓存和 SharedPreferences
     */
    private void saveModelMetadata(String modelPath, long loadTimeMs) {
        File modelFile = new File(modelPath);
        long fileSize = modelFile.length();
        String modelKey = getModelKey(modelPath);
        
        ModelMetadata metadata = new ModelMetadata(
                modelPath,
                fileSize,
                loadTimeMs,
                LlamaHelper.getThreadCount(),
                LlamaHelper.getGPULayers(),
                LlamaHelper.getContextSize()
        );
        
        // 保存到内存缓存
        metadataCache.put(modelKey, metadata);
        
        // 保存到 SharedPreferences
        prefs.edit()
                .putString(String.format(KEY_META_PATH, modelKey), modelPath)
                .putLong(String.format(KEY_META_SIZE, modelKey), fileSize)
                .putLong(String.format(KEY_META_LOAD_TIME, modelKey), loadTimeMs)
                .putInt(String.format(KEY_META_THREADS, modelKey), metadata.optimalThreads)
                .putInt(String.format(KEY_META_GPU_LAYERS, modelKey), metadata.optimalGpuLayers)
                .putInt(String.format(KEY_META_CONTEXT, modelKey), metadata.optimalContextSize)
                .putLong(String.format(KEY_META_TIMESTAMP, modelKey), metadata.timestamp)
                .apply();
        
        AILogger.i(TAG, "Saved metadata: " + metadata);
    }
    
    /**
     * 从 SharedPreferences 加载元数据缓存
     */
    private void loadMetadataFromPrefs() {
        // 获取所有已保存的模型键
        java.util.Map<String, ?> allPrefs = prefs.getAll();
        java.util.Set<String> savedModels = new java.util.HashSet<>();
        
        for (String key : allPrefs.keySet()) {
            if (key.startsWith("meta_path_")) {
                String modelKey = key.substring("meta_path_".length());
                savedModels.add(modelKey);
            }
        }
        
        for (String modelKey : savedModels) {
            String modelPath = prefs.getString(String.format(KEY_META_PATH, modelKey), null);
            if (modelPath == null) continue;
            
            long fileSize = prefs.getLong(String.format(KEY_META_SIZE, modelKey), 0);
            long loadTimeMs = prefs.getLong(String.format(KEY_META_LOAD_TIME, modelKey), 0);
            int threads = prefs.getInt(String.format(KEY_META_THREADS, modelKey), 2);
            int gpuLayers = prefs.getInt(String.format(KEY_META_GPU_LAYERS, modelKey), 0);
            int contextSize = prefs.getInt(String.format(KEY_META_CONTEXT, modelKey), 4096);
            long timestamp = prefs.getLong(String.format(KEY_META_TIMESTAMP, modelKey), 0);
            
            // 检查是否过期
            if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) {
                AILogger.i(TAG, "Metadata expired for model: " + modelKey);
                clearModelMetadata(modelKey);
                continue;
            }
            
            ModelMetadata metadata = new ModelMetadata(
                    modelPath, fileSize, loadTimeMs, threads, gpuLayers, contextSize
            );
            metadataCache.put(modelKey, metadata);
        }
        
        AILogger.i(TAG, "Loaded " + metadataCache.size() + " model metadata entries from cache");
    }
    
    /**
     * 清除模型元数据
     */
    private void clearModelMetadata(String modelKey) {
        metadataCache.remove(modelKey);
        prefs.edit()
                .remove(String.format(KEY_META_PATH, modelKey))
                .remove(String.format(KEY_META_SIZE, modelKey))
                .remove(String.format(KEY_META_LOAD_TIME, modelKey))
                .remove(String.format(KEY_META_THREADS, modelKey))
                .remove(String.format(KEY_META_GPU_LAYERS, modelKey))
                .remove(String.format(KEY_META_CONTEXT, modelKey))
                .remove(String.format(KEY_META_TIMESTAMP, modelKey))
                .apply();
    }
    
    /**
     * 获取缓存的元数据
     */
    public ModelMetadata getCachedMetadata(String modelPath) {
        return metadataCache.get(getModelKey(modelPath));
    }
    
    // ========== 辅助方法 ==========
    
    private String getModelKey(String modelPath) {
        return new File(modelPath).getName();
    }
    
    private boolean isModelPreloaded(String modelPath) {
        String key = String.format(KEY_PRELOADED, getModelKey(modelPath));
        long timestamp = prefs.getLong(key, 0);
        return timestamp > 0 && (System.currentTimeMillis() - timestamp) < CACHE_EXPIRY_MS;
    }
    
    private void markModelPreloaded(String modelPath) {
        String key = String.format(KEY_PRELOADED, getModelKey(modelPath));
        prefs.edit().putLong(key, System.currentTimeMillis()).apply();
    }
    
    private void notifyPreloadProgress(PreloadCallback callback, int progress) {
        if (callback != null) {
            callback.onPreloadProgress(progress);
        }
    }
    
    private void notifyPreloadComplete(PreloadCallback callback, boolean success, String error) {
        if (callback != null) {
            callback.onPreloadComplete(success, error);
        }
    }
    
    private String formatSize(long bytes) {
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
     * 释放资源
     */
    public void release() {
        preloadExecutor.shutdown();
        metadataCache.clear();
    }
    
    // ========== 数据类 ==========
    
    /**
     * 加载参数
     */
    public static class LoadParams {
        public String modelPath;
        public int contextSize = 4096;
        public boolean useOptimalThreads = false;
        public boolean useMmap = false;
        public boolean enableChunkedPreload = false;
        public boolean enablePreWarming = false;
        public boolean preloaded = false;
        
        @Override
        public String toString() {
            return "LoadParams{" +
                    "ctx=" + contextSize +
                    ", optimalThreads=" + useOptimalThreads +
                    ", mmap=" + useMmap +
                    ", chunked=" + enableChunkedPreload +
                    ", warmUp=" + enablePreWarming +
                    ", preloaded=" + preloaded +
                    '}';
        }
    }
    
    /**
     * 预加载回调接口
     */
    public interface PreloadCallback {
        void onPreloadProgress(int progress);
        void onPreloadComplete(boolean success, String error);
    }
    
    /**
     * 预热回调接口
     */
    public interface WarmUpCallback {
        void onWarmUpComplete(boolean success, long elapsedMs);
    }
}
