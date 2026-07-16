package com.oilquiz.app.util.preview;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 预览数据管理器 - 负责管理预览数据的缓存和处理
 */
public class PreviewDataManager {
    private static final String TAG = "PreviewDataManager";
    private Context context;
    private Map<String, FilePreviewManager.PreviewData> previewDataCache;
    
    public PreviewDataManager(Context context) {
        this.context = context;
        this.previewDataCache = new HashMap<>();
    }
    
    /**
     * 缓存预览数据
     * @param file 文件
     * @param previewData 预览数据
     */
    public void cachePreviewData(File file, FilePreviewManager.PreviewData previewData) {
        if (file == null || previewData == null) {
            return;
        }
        String key = file.getAbsolutePath();
        previewDataCache.put(key, previewData);
        Log.d(TAG, "Cached preview data for file: " + file.getName());
    }
    
    /**
     * 获取缓存的预览数据
     * @param file 文件
     * @return 预览数据，如果没有缓存则返回null
     */
    public FilePreviewManager.PreviewData getCachedPreviewData(File file) {
        if (file == null) {
            return null;
        }
        String key = file.getAbsolutePath();
        return previewDataCache.get(key);
    }
    
    /**
     * 清除预览数据缓存
     * @param file 文件，如果为null则清除所有缓存
     */
    public void clearPreviewDataCache(File file) {
        if (file == null) {
            previewDataCache.clear();
            Log.d(TAG, "Cleared all preview data cache");
        } else {
            String key = file.getAbsolutePath();
            previewDataCache.remove(key);
            Log.d(TAG, "Cleared preview data cache for file: " + file.getName());
        }
    }
    
    /**
     * 获取预览缓存目录
     * @return 预览缓存目录
     */
    public File getPreviewCacheDirectory() {
        File cacheDir = new File(context.getCacheDir(), "preview_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir;
    }
    
    /**
     * 清理过期的预览缓存
     */
    public void cleanupExpiredCache() {
        File cacheDir = getPreviewCacheDirectory();
        if (!cacheDir.exists()) {
            return;
        }
        
        long cutoffTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000; // 24小时前
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.lastModified() < cutoffTime) {
                    file.delete();
                    Log.d(TAG, "Cleaned up expired cache file: " + file.getName());
                }
            }
        }
    }
    
    /**
     * 计算预览缓存大小
     * @return 缓存大小（字节）
     */
    public long getCacheSize() {
        File cacheDir = getPreviewCacheDirectory();
        if (!cacheDir.exists()) {
            return 0;
        }
        return calculateDirectorySize(cacheDir);
    }
    
    /**
     * 计算目录大小
     * @param directory 目录
     * @return 目录大小（字节）
     */
    private long calculateDirectorySize(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += calculateDirectorySize(file);
                }
            }
        }
        return size;
    }
}