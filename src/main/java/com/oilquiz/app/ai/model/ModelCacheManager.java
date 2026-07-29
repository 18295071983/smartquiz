package com.oilquiz.app.ai.model;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ModelCacheManager - 模型内存硬盘缓存转换管理器
 * 
 * 功能：
 * 1. 内存-硬盘数据交换 - 支持模型数据在内存和硬盘之间交换
 * 2. 缓冲区管理 - 防止内存错位和硬盘数据溢出
 * 3. 缓存预热 - 预加载常用模型数据到内存
 * 4. 缓存淘汰 - LRU策略管理缓存空间
 * 5. 缓存验证 - 确保缓存数据完整性
 */
public class ModelCacheManager {

    private static final String TAG = "ModelCacheManager";
    private static final String CACHE_DIR = "model_cache";
    private static final String SWAP_DIR = "model_swap";
    private static final long DEFAULT_MAX_MEMORY_CACHE = 512 * 1024 * 1024; // 512MB
    private static final long DEFAULT_MAX_DISK_CACHE = 2 * 1024 * 1024 * 1024; // 2GB
    private static final int BUFFER_SIZE = 4 * 1024 * 1024; // 4MB buffer

    // 单例
    private static volatile ModelCacheManager INSTANCE;
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;

    // 缓存目录
    private final File cacheDir;
    private final File swapDir;

    // 内存缓存
    private final ConcurrentHashMap<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();
    private final AtomicLong currentMemoryUsage = new AtomicLong(0);
    private long maxMemoryCache = DEFAULT_MAX_MEMORY_CACHE;

    // 磁盘缓存
    private long maxDiskCache = DEFAULT_MAX_DISK_CACHE;
    private final AtomicLong currentDiskUsage = new AtomicLong(0);

    // 状态
    private final AtomicBoolean isSwapping = new AtomicBoolean(false);
    private final AtomicBoolean isPreloading = new AtomicBoolean(false);

    // 回调
    public interface CacheCallback {
        void onProgress(int progress, String message);
        void onComplete(boolean success, String message);
    }

    // 缓存条目
    private static class CacheEntry {
        String key;
        String filePath;
        long size;
        long lastAccessTime;
        int accessCount;
        boolean isDirty;
        ByteBuffer data; // 内存中的数据

        CacheEntry(String key, String filePath, long size) {
            this.key = key;
            this.filePath = filePath;
            this.size = size;
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount = 0;
            this.isDirty = false;
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount++;
        }
    }

    private ModelCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(2);

        this.cacheDir = new File(context.getFilesDir(), CACHE_DIR);
        this.swapDir = new File(context.getFilesDir(), SWAP_DIR);

        if (!cacheDir.exists()) cacheDir.mkdirs();
        if (!swapDir.exists()) swapDir.mkdirs();

        // 初始化磁盘使用量
        calculateDiskUsage();
    }

    public static ModelCacheManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelCacheManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelCacheManager(context);
                }
            }
        }
        return INSTANCE;
    }

    // ========== 内存-硬盘交换 ==========

    /**
     * 将模型数据从内存交换到硬盘
     */
    public boolean swapToDisk(String modelId, CacheCallback callback) {
        if (isSwapping.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doSwapToDisk(modelId, callback);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onComplete(success, success ? "交换到硬盘成功" : "交换到硬盘失败");
                        }
                    });
                } finally {
                    isSwapping.set(false);
                }
            });
            return true;
        }
        return false;
    }

    private boolean doSwapToDisk(String modelId, CacheCallback callback) {
        CacheEntry entry = memoryCache.get(modelId);
        if (entry == null) {
            AILogger.w(TAG, "Model not in memory cache: " + modelId);
            return false;
        }

        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(10, "准备交换数据..."));
        }

        try {
            // 检查磁盘空间
            if (!hasEnoughDiskSpace(entry.size)) {
                AILogger.e(TAG, "Not enough disk space for swap");
                // 尝试清理磁盘缓存
                cleanupDiskCache(entry.size);
                if (!hasEnoughDiskSpace(entry.size)) {
                    return false;
                }
            }

            // 创建交换文件
            File swapFile = new File(swapDir, modelId + ".swap");
            if (callback != null) {
                mainHandler.post(() -> callback.onProgress(30, "写入交换文件..."));
            }

            // 写入数据到磁盘
            writeToFile(swapFile, entry.data, entry.size);

            // 更新缓存条目
            entry.filePath = swapFile.getAbsolutePath();
            entry.isDirty = false;

            // 释放内存
            entry.data = null;
            currentMemoryUsage.addAndGet(-entry.size);

            AILogger.i(TAG, "Swapped model to disk: " + modelId + " (" + entry.size / 1024 / 1024 + "MB)");

            if (callback != null) {
                mainHandler.post(() -> callback.onProgress(100, "交换完成"));
            }

            return true;

        } catch (Exception e) {
            AILogger.e(TAG, "Failed to swap to disk: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 将模型数据从硬盘加载到内存
     */
    public boolean loadFromDisk(String modelId, CacheCallback callback) {
        if (isSwapping.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doLoadFromDisk(modelId, callback);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onComplete(success, success ? "加载到内存成功" : "加载到内存失败");
                        }
                    });
                } finally {
                    isSwapping.set(false);
                }
            });
            return true;
        }
        return false;
    }

    private boolean doLoadFromDisk(String modelId, CacheCallback callback) {
        CacheEntry entry = memoryCache.get(modelId);
        if (entry == null) {
            // 创建新的缓存条目
            File swapFile = new File(swapDir, modelId + ".swap");
            if (!swapFile.exists()) {
                AILogger.w(TAG, "Swap file not found: " + modelId);
                return false;
            }

            entry = new CacheEntry(modelId, swapFile.getAbsolutePath(), swapFile.length());
            memoryCache.put(modelId, entry);
        }

        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(10, "检查内存空间..."));
        }

        // 检查内存空间
        if (!hasEnoughMemory(entry.size)) {
            AILogger.w(TAG, "Not enough memory, evicting old entries");
            // 淘汰旧的缓存
            evictMemoryCache(entry.size);
            if (!hasEnoughMemory(entry.size)) {
                AILogger.e(TAG, "Still not enough memory after eviction");
                return false;
            }
        }

        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(30, "从磁盘加载数据..."));
        }

        try {
            // 从磁盘读取数据
            File swapFile = new File(entry.filePath);
            ByteBuffer data = readFromFile(swapFile, entry.size);

            // 更新缓存条目
            entry.data = data;
            entry.touch();
            currentMemoryUsage.addAndGet(entry.size);

            AILogger.i(TAG, "Loaded model from disk: " + modelId + " (" + entry.size / 1024 / 1024 + "MB)");

            if (callback != null) {
                mainHandler.post(() -> callback.onProgress(100, "加载完成"));
            }

            return true;

        } catch (Exception e) {
            AILogger.e(TAG, "Failed to load from disk: " + e.getMessage(), e);
            return false;
        }
    }

    // ========== 缓冲区管理 ==========

    /**
     * 防止内存错位的缓冲区管理
     */
    private synchronized ByteBuffer createSafeBuffer(long size) {
        // 检查内存对齐
        long alignedSize = (size + 4095) & ~4095L; // 4KB对齐

        try {
            // 分配堆外内存以避免GC影响
            ByteBuffer buffer = ByteBuffer.allocateDirect((int) alignedSize);
            buffer.clear();
            return buffer;
        } catch (OutOfMemoryError e) {
            AILogger.e(TAG, "Failed to allocate buffer: " + e.getMessage());
            // 尝试清理后重试
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return ByteBuffer.allocateDirect((int) alignedSize);
        }
    }

    /**
     * 验证缓冲区完整性
     */
    private boolean validateBuffer(ByteBuffer buffer, long expectedSize) {
        if (buffer == null) return false;
        return buffer.capacity() >= expectedSize;
    }

    // ========== 缓存预热 ==========

    /**
     * 预热模型缓存
     */
    public boolean preloadModel(String modelId, String modelPath, CacheCallback callback) {
        if (isPreloading.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doPreloadModel(modelId, modelPath, callback);
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onComplete(success, success ? "预热成功" : "预热失败");
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

    private boolean doPreloadModel(String modelId, String modelPath, CacheCallback callback) {
        AILogger.i(TAG, "Preloading model: " + modelId);

        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(10, "检查模型文件..."));
        }

        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            AILogger.e(TAG, "Model file not found: " + modelPath);
            return false;
        }

        long fileSize = modelFile.length();

        // 检查是否已经在缓存中
        if (memoryCache.containsKey(modelId)) {
            AILogger.i(TAG, "Model already in cache: " + modelId);
            return true;
        }

        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(30, "分配缓存空间..."));
        }

        // 检查内存空间
        if (!hasEnoughMemory(fileSize)) {
            AILogger.w(TAG, "Not enough memory for preload, evicting old entries");
            evictMemoryCache(fileSize);
        }

        try {
            // 创建缓存条目
            CacheEntry entry = new CacheEntry(modelId, modelPath, fileSize);

            if (callback != null) {
                mainHandler.post(() -> callback.onProgress(50, "加载模型数据..."));
            }

            // 使用内存映射加载
            ByteBuffer data = mmapFile(modelFile);
            entry.data = data;

            // 添加到缓存
            memoryCache.put(modelId, entry);
            currentMemoryUsage.addAndGet(fileSize);

            AILogger.i(TAG, "Model preloaded: " + modelId + " (" + fileSize / 1024 / 1024 + "MB)");

            if (callback != null) {
                mainHandler.post(() -> callback.onProgress(100, "预热完成"));
            }

            return true;

        } catch (Exception e) {
            AILogger.e(TAG, "Failed to preload model: " + e.getMessage(), e);
            return false;
        }
    }

    // ========== 缓存淘汰 ==========

    /**
     * LRU淘汰内存缓存
     */
    private void evictMemoryCache(long requiredSpace) {
        AILogger.i(TAG, "Evicting memory cache, required: " + requiredSpace / 1024 / 1024 + "MB");

        // 按访问时间排序
        memoryCache.entrySet().stream()
            .sorted((a, b) -> Long.compare(a.getValue().lastAccessTime, b.getValue().lastAccessTime))
            .forEach(entry -> {
                if (currentMemoryUsage.get() + requiredSpace > maxMemoryCache) {
                    CacheEntry cacheEntry = entry.getValue();
                    // 交换到磁盘
                    if (cacheEntry.data != null) {
                        swapToDisk(cacheEntry.key, null);
                    }
                    memoryCache.remove(entry.getKey());
                }
            });
    }

    /**
     * 清理磁盘缓存
     */
    private void cleanupDiskCache(long requiredSpace) {
        AILogger.i(TAG, "Cleaning up disk cache, required: " + requiredSpace / 1024 / 1024 + "MB");

        File[] files = swapDir.listFiles();
        if (files == null) return;

        // 按修改时间排序
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        for (File file : files) {
            if (currentDiskUsage.get() + requiredSpace > maxDiskCache) {
                long fileSize = file.length();
                if (file.delete()) {
                    currentDiskUsage.addAndGet(-fileSize);
                    AILogger.d(TAG, "Deleted swap file: " + file.getName());
                }
            }
        }
    }

    // ========== 空间检查 ==========

    private boolean hasEnoughMemory(long required) {
        return currentMemoryUsage.get() + required <= maxMemoryCache;
    }

    private boolean hasEnoughDiskSpace(long required) {
        StatFs stat = new StatFs(cacheDir.getPath());
        long available = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        return available >= required;
    }

    private void calculateDiskUsage() {
        long totalSize = 0;
        File[] files = swapDir.listFiles();
        if (files != null) {
            for (File file : files) {
                totalSize += file.length();
            }
        }
        currentDiskUsage.set(totalSize);
    }

    // ========== 文件操作 ==========

    private ByteBuffer mmapFile(File file) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        FileChannel channel = raf.getChannel();
        MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
        channel.close();
        raf.close();
        return buffer;
    }

    private void writeToFile(File file, ByteBuffer data, long size) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        FileChannel channel = fos.getChannel();

        if (data != null) {
            data.position(0);
            channel.write(data);
        }

        channel.close();
        fos.close();
    }

    private ByteBuffer readFromFile(File file, long size) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        FileChannel channel = fis.getChannel();

        ByteBuffer buffer = createSafeBuffer(size);
        channel.read(buffer);
        buffer.flip();

        channel.close();
        fis.close();

        return buffer;
    }

    // ========== 状态查询 ==========

    public long getMemoryUsage() {
        return currentMemoryUsage.get();
    }

    public long getDiskUsage() {
        return currentDiskUsage.get();
    }

    public int getCacheSize() {
        return memoryCache.size();
    }

    public boolean isSwapping() {
        return isSwapping.get();
    }

    public boolean isPreloading() {
        return isPreloading.get();
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        CacheStats stats = new CacheStats();
        stats.memoryUsageMB = currentMemoryUsage.get() / 1024 / 1024;
        stats.diskUsageMB = currentDiskUsage.get() / 1024 / 1024;
        stats.maxMemoryMB = maxMemoryCache / 1024 / 1024;
        stats.maxDiskMB = maxDiskCache / 1024 / 1024;
        stats.entryCount = memoryCache.size();
        return stats;
    }

    /**
     * 清理所有缓存
     */
    public void clearAll() {
        AILogger.i(TAG, "Clearing all caches");

        // 清理内存缓存
        memoryCache.clear();
        currentMemoryUsage.set(0);

        // 清理磁盘缓存
        File[] files = swapDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        currentDiskUsage.set(0);
    }

    // ========== 数据类 ==========

    public static class CacheStats {
        public long memoryUsageMB;
        public long diskUsageMB;
        public long maxMemoryMB;
        public long maxDiskMB;
        public int entryCount;

        @Override
        public String toString() {
            return String.format("CacheStats{memory=%d/%dMB, disk=%d/%dMB, entries=%d}",
                memoryUsageMB, maxMemoryMB, diskUsageMB, maxDiskMB, entryCount);
        }
    }
}
