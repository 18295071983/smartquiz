package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public abstract class BasePreviewEngine implements PreviewEngine {

    protected static final String TAG = "BasePreviewEngine";

    @Override
    public void preview(Context context, File file, PreviewCallback callback) {
        preview(context, file, callback, null);
    }

    @Override
    public void preview(Context context, File file, PreviewCallback callback, PreviewProgressCallback progressCallback) {
        if (!supports(file)) {
            callback.onFailure("文件格式不支持");
            return;
        }

        // 检查文件大小
        if (file.length() > 100 * 1024 * 1024) { // 100MB
            callback.onFailure("文件过大，无法预览");
            return;
        }

        try {
            // 尝试从缓存获取预览
            Bitmap cachedBitmap = getCachedPreview(context, file);
            if (cachedBitmap != null) {
                callback.onSuccess(cachedBitmap);
                return;
            }

            // 生成预览
            Bitmap bitmap = generatePreview(context, file, progressCallback);
            if (bitmap != null) {
                // 缓存预览
                cachePreview(context, file, bitmap);
                callback.onSuccess(bitmap);
            } else {
                callback.onFailure("生成预览失败");
            }
        } catch (Exception e) {
            Log.e(TAG, "生成预览错误", e);
            callback.onFailure("生成预览错误: " + e.getMessage());
        }
    }

    /**
     * 生成预览图片（带进度回调）
     * @param context 上下文
     * @param file 文件
     * @param progressCallback 进度回调
     * @return 预览图片
     * @throws Exception 异常
     */
    protected abstract Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception;

    /**
     * 生成预览图片（兼容旧方法）
     * @param context 上下文
     * @param file 文件
     * @return 预览图片
     * @throws Exception 异常
     */
    protected Bitmap generatePreview(Context context, File file) throws Exception {
        return generatePreview(context, file, null);
    }

    /**
     * 获取缓存的预览
     * @param context 上下文
     * @param file 文件
     * @return 缓存的预览图片
     */
    protected Bitmap getCachedPreview(Context context, File file) {
        try {
            File cacheFile = getCacheFile(context, file);
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "获取缓存预览错误", e);
        }
        return null;
    }

    /**
     * 缓存预览
     * @param context 上下文
     * @param file 文件
     * @param bitmap 预览图片
     */
    protected void cachePreview(Context context, File file, Bitmap bitmap) {
        try {
            File cacheFile = getCacheFile(context, file);
            File cacheDir = cacheFile.getParentFile();
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, fos);
            }
        } catch (Exception e) {
            Log.e(TAG, "缓存预览错误", e);
        }
    }

    /**
     * 获取缓存文件
     * @param context 上下文
     * @param file 文件
     * @return 缓存文件
     * @throws Exception 异常
     */
    protected File getCacheFile(Context context, File file) throws Exception {
        String fileName = getFileHash(file);
        File cacheDir = new File(context.getCacheDir(), "preview_cache");
        return new File(cacheDir, fileName + ".png");
    }

    /**
     * 获取文件哈希值
     * @param file 文件
     * @return 文件哈希值
     * @throws Exception 异常
     */
    protected String getFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 清理缓存
     * @param context 上下文
     */
    public static void clearCache(Context context) {
        try {
            File cacheDir = new File(context.getCacheDir(), "preview_cache");
            if (cacheDir.exists()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "清理缓存错误", e);
        }
    }

    /**
     * 获取缓存大小
     * @param context 上下文
     * @return 缓存大小（字节）
     */
    public static long getCacheSize(Context context) {
        long size = 0;
        try {
            File cacheDir = new File(context.getCacheDir(), "preview_cache");
            if (cacheDir.exists()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        size += file.length();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取缓存大小错误", e);
        }
        return size;
    }

    /**
     * 检查文件扩展名是否匹配
     * @param file 文件
     * @param extensions 扩展名列表
     * @return 是否匹配
     */
    protected boolean hasExtension(File file, String[] extensions) {
        String fileName = file.getName().toLowerCase();
        for (String extension : extensions) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}