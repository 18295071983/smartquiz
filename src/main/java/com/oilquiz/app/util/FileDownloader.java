package com.oilquiz.app.util;

import android.util.Log;

import com.oilquiz.app.infra.Network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 文件下载工具类
 * 用于下载网络文件到本地
 */
public class FileDownloader {
    private static final String TAG = "FileDownloader";
    
    private static final int DEFAULT_TIMEOUT = 30;
    private static final int BUFFER_SIZE = 8192;
    
    private OkHttpClient client;
    
    /**
     * 下载进度监听器
     */
    public interface DownloadProgressListener {
        void onProgress(long downloaded, long total);
        void onComplete(File file);
        void onError(Exception e);
    }
    
    /**
     * 构造函数
     */
    public FileDownloader() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 构造函数（使用自定义OkHttpClient）
     * @param client OkHttpClient实例
     */
    public FileDownloader(OkHttpClient client) {
        this.client = client;
    }
    
    /**
     * 同步下载文件
     * @param url 下载URL
     * @param destFile 目标文件
     * @return 是否下载成功
     */
    public boolean downloadSync(String url, File destFile) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "下载失败，HTTP状态码: " + response.code());
                return false;
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                Log.e(TAG, "响应体为空");
                return false;
            }
            
            return saveResponseBodyToFile(body, destFile, null);
        } catch (IOException e) {
            Log.e(TAG, "同步下载失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 异步下载文件（带进度）
     * @param url 下载URL
     * @param destFile 目标文件
     * @param listener 进度监听器
     */
    public void downloadAsync(String url, File destFile, DownloadProgressListener listener) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "异步下载失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    IOException e = new IOException("下载失败，HTTP状态码: " + response.code());
                    Log.e(TAG, e.getMessage(), e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                    return;
                }
                
                ResponseBody body = response.body();
                if (body == null) {
                    IOException e = new IOException("响应体为空");
                    Log.e(TAG, e.getMessage(), e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                    return;
                }
                
                boolean success = saveResponseBodyToFile(body, destFile, listener);
                if (success && listener != null) {
                    listener.onComplete(destFile);
                }
            }
        });
    }
    
    /**
     * 将响应体保存到文件
     * @param body 响应体
     * @param destFile 目标文件
     * @param listener 进度监听器
     * @return 是否成功
     */
    private boolean saveResponseBodyToFile(ResponseBody body, File destFile, DownloadProgressListener listener) {
        long totalBytes = body.contentLength();
        long downloadedBytes = 0;
        
        // 确保父目录存在
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        try (InputStream inputStream = body.byteStream();
             FileOutputStream outputStream = new FileOutputStream(destFile)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;
                
                if (listener != null) {
                    listener.onProgress(downloadedBytes, totalBytes);
                }
            }
            
            outputStream.flush();
            Log.i(TAG, "文件下载成功: " + destFile.getAbsolutePath() + ", 大小: " + downloadedBytes + " bytes");
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "保存文件失败: " + e.getMessage(), e);
            // 下载失败时删除部分文件
            if (destFile.exists()) {
                destFile.delete();
            }
            if (listener != null) {
                listener.onError(e);
            }
            return false;
        }
    }
    
    /**
     * 下载文件到指定目录（使用默认文件名）
     * @param url 下载URL
     * @param destDir 目标目录
     * @param listener 进度监听器
     */
    public void downloadToDirectory(String url, File destDir, DownloadProgressListener listener) {
        String fileName = getFileNameFromUrl(url);
        File destFile = new File(destDir, fileName);
        downloadAsync(url, destFile, listener);
    }
    
    /**
     * 从URL中提取文件名
     * @param url URL
     * @return 文件名
     */
    private String getFileNameFromUrl(String url) {
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        // 移除查询参数
        int queryIndex = fileName.indexOf('?');
        if (queryIndex != -1) {
            fileName = fileName.substring(0, queryIndex);
        }
        // 如果文件名为空，使用默认名称
        if (fileName.isEmpty()) {
            fileName = "downloaded_file";
        }
        return fileName;
    }
    
    /**
     * 取消所有下载
     */
    public void cancelAll() {
        client.dispatcher().cancelAll();
    }
    
    /**
     * 检查文件是否已存在且大小匹配
     * @param url 下载URL
     * @param destFile 目标文件
     * @return 是否可以跳过下载
     */
    public boolean canSkipDownload(String url, File destFile) {
        if (!destFile.exists()) {
            return false;
        }
        
        // 简单检查：如果文件已存在，直接返回true
        // 在实际应用中，可能需要检查文件大小、MD5等
        return true;
    }
    
    /**
     * 格式化文件大小
     * @param bytes 字节数
     * @return 格式化后的字符串
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
    
    /**
     * 计算下载百分比
     * @param downloaded 已下载字节数
     * @param total 总字节数
     * @return 百分比
     */
    public static int calculateProgressPercent(long downloaded, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) ((downloaded * 100) / total);
    }
}
