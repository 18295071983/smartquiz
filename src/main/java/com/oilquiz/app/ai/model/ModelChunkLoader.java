package com.oilquiz.app.ai.model;

import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型分块加载管理器
 * 
 * 功能：
 * 1. 将大模型文件分块加载，避免内存峰值
 * 2. 支持加载进度回调
 * 3. 支持取消加载
 * 4. 智能内存管理，加载完成后释放中间缓冲区
 * 
 * 分块策略：
 * - 小模型（<500MB）：直接加载，不分块
 * - 中模型（500MB-2GB）：分2块加载
 * - 大模型（>2GB）：分4块加载
 */
public class ModelChunkLoader {
    private static final String TAG = "ModelChunkLoader";
    
    // 分块阈值
    private static final long SMALL_MODEL_THRESHOLD = 500 * 1024 * 1024L;  // 500MB
    private static final long LARGE_MODEL_THRESHOLD = 2L * 1024 * 1024 * 1024L; // 2GB
    
    // 分块数量
    private static final int CHUNKS_SMALL = 1;   // 小模型不分块
    private static final int CHUNKS_MEDIUM = 2;  // 中模型分2块
    private static final int CHUNKS_LARGE = 4;   // 大模型分4块
    
    // 加载配置
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB缓冲区
    private static final long LOAD_TIMEOUT_MS = 120000; // 2分钟超时
    
    private final ExecutorService loadExecutor;
    private final Handler mainHandler;
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    
    public interface ChunkLoadCallback {
        void onProgress(int chunkIndex, int totalChunks, int progressPercent);
        void onChunkLoaded(int chunkIndex, int totalChunks, long bytesLoaded);
        void onComplete(boolean success, String error);
    }
    
    public ModelChunkLoader() {
        this.loadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ModelChunkLoader");
            t.setDaemon(true);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 加载模型（自动选择分块策略）
     */
    public void loadModel(String modelPath, int contextSize, int nThreads, ChunkLoadCallback callback) {
        if (isLoading.compareAndSet(false, true)) {
            isCancelled.set(false);
            loadExecutor.execute(() -> performLoad(modelPath, contextSize, nThreads, callback));
        } else {
            callback.onComplete(false, "Already loading a model");
        }
    }
    
    /**
     * 执行加载
     */
    private void performLoad(String modelPath, int contextSize, int nThreads, ChunkLoadCallback callback) {
        long startTime = System.currentTimeMillis();
        
        try {
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                notifyComplete(callback, false, "Model file not found");
                return;
            }
            
            long fileSize = modelFile.length();
            int numChunks = calculateChunks(fileSize);
            
            AILogger.i(TAG, "Loading model: " + modelFile.getName());
            AILogger.i(TAG, "File size: " + formatFileSize(fileSize));
            AILogger.i(TAG, "Chunks: " + numChunks);
            
            // 分块预读取文件到系统缓存
            if (numChunks > 1) {
                boolean preloadSuccess = preloadChunks(modelPath, numChunks, fileSize, callback);
                if (!preloadSuccess) {
                    notifyComplete(callback, false, "Chunk preload failed");
                    return;
                }
            }
            
            // 检查是否被取消
            if (isCancelled.get()) {
                notifyComplete(callback, false, "Loading cancelled");
                return;
            }
            
            // 调用Native层加载模型
            AILogger.i(TAG, "Initializing model in native layer...");
            int result = LlamaHelper.initModel(modelPath, contextSize, nThreads);
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            if (result == 0) {
                AILogger.i(TAG, "Model loaded successfully in " + elapsed + "ms");
                notifyComplete(callback, true, null);
            } else {
                AILogger.e(TAG, "Model init failed: " + result);
                notifyComplete(callback, false, "Native init failed: " + result);
            }
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error loading model: " + e.getMessage(), e);
            notifyComplete(callback, false, e.getMessage());
        } finally {
            isLoading.set(false);
        }
    }
    
    /**
     * 计算分块数量
     */
    private int calculateChunks(long fileSize) {
        if (fileSize <= SMALL_MODEL_THRESHOLD) {
            return CHUNKS_SMALL;
        } else if (fileSize <= LARGE_MODEL_THRESHOLD) {
            return CHUNKS_MEDIUM;
        } else {
            return CHUNKS_LARGE;
        }
    }
    
    /**
     * 分块预加载文件到系统缓存
     * 这不会加载模型到内存，只是让系统缓存文件内容，加速后续读取
     */
    private boolean preloadChunks(String modelPath, int numChunks, long fileSize, ChunkLoadCallback callback) {
        try (RandomAccessFile raf = new RandomAccessFile(modelPath, "r");
             FileChannel channel = raf.getChannel()) {
            
            long chunkSize = fileSize / numChunks;
            byte[] buffer = new byte[BUFFER_SIZE];
            
            for (int i = 0; i < numChunks; i++) {
                if (isCancelled.get()) {
                    return false;
                }
                
                long startPos = i * chunkSize;
                long endPos = (i == numChunks - 1) ? fileSize : (i + 1) * chunkSize;
                long currentPos = startPos;
                
                AILogger.d(TAG, "Preloading chunk " + (i + 1) + "/" + numChunks);
                
                // 使用内存映射读取文件块
                long mapSize = Math.min(chunkSize, fileSize - startPos);
                if (mapSize > 0) {
                    MappedByteBuffer mappedBuffer = channel.map(
                        FileChannel.MapMode.READ_ONLY, startPos, mapSize);
                    
                    // 顺序读取缓冲区内容，触发系统缓存
                    long bytesRead = 0;
                    while (mappedBuffer.hasRemaining() && !isCancelled.get()) {
                        int remaining = mappedBuffer.remaining();
                        int toRead = Math.min(buffer.length, remaining);
                        mappedBuffer.get(buffer, 0, toRead);
                        bytesRead += toRead;
                        
                        // 计算进度
                        int chunkProgress = (int) ((bytesRead * 100) / mapSize);
                        int overallProgress = (int) (((i * 100) + chunkProgress) / numChunks);
                        
                        notifyProgress(callback, i, numChunks, overallProgress);
                    }
                    
                    // 清理映射缓冲区
                    mappedBuffer.clear();
                    
                    notifyChunkLoaded(callback, i, numChunks, bytesRead);
                }
                
                // 每加载完一块，建议GC释放缓冲区
                if (i < numChunks - 1) {
                    System.gc();
                }
            }
            
            return true;
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error preloading chunks: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 取消加载
     */
    public void cancel() {
        if (isLoading.get()) {
            AILogger.i(TAG, "Cancelling model load");
            isCancelled.set(true);
        }
    }
    
    /**
     * 检查是否正在加载
     */
    public boolean isLoading() {
        return isLoading.get();
    }
    
    /**
     * 通知进度
     */
    private void notifyProgress(ChunkLoadCallback callback, int chunkIndex, int totalChunks, int progress) {
        mainHandler.post(() -> {
            if (callback != null && !isCancelled.get()) {
                callback.onProgress(chunkIndex, totalChunks, progress);
            }
        });
    }
    
    /**
     * 通知块加载完成
     */
    private void notifyChunkLoaded(ChunkLoadCallback callback, int chunkIndex, int totalChunks, long bytesLoaded) {
        mainHandler.post(() -> {
            if (callback != null && !isCancelled.get()) {
                callback.onChunkLoaded(chunkIndex, totalChunks, bytesLoaded);
            }
        });
    }
    
    /**
     * 通知加载完成
     */
    private void notifyComplete(ChunkLoadCallback callback, boolean success, String error) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onComplete(success, error);
            }
        });
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
     * 释放资源
     */
    public void release() {
        cancel();
        if (loadExecutor != null) {
            loadExecutor.shutdown();
        }
    }
}
