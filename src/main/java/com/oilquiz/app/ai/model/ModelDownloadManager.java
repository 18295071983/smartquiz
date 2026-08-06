package com.oilquiz.app.ai.model;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型下载管理器
 * 支持两种下载方式：
 * 1. 自定义下载器 - 断点续传、暂停/恢复、速度显示
 * 2. 系统 DownloadManager - 后台下载、通知栏进度、无需额外依赖
 */
public class ModelDownloadManager {
    private static final String TAG = "ModelDownloadManager";
    private static final int BUFFER_SIZE = 65536; // 64KB
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 300000;

    private static volatile ModelDownloadManager INSTANCE;
    private final Context context;
    private final Map<String, DownloadTask> downloadTasks = new ConcurrentHashMap<>();
    private final Map<String, DownloadProgress> downloadProgress = new ConcurrentHashMap<>();
    private final Map<String, Long> systemDownloadIds = new ConcurrentHashMap<>(); // modelId -> system download id
    private final Map<String, SystemDownloadTarget> systemDownloadTargets = new ConcurrentHashMap<>(); // modelId -> target info
    private final AtomicInteger activeDownloads = new AtomicInteger(0);
    private final int maxConcurrentDownloads = 2;
    private ExecutorService executor;
    private DownloadCallback globalCallback;
    private android.os.Handler progressHandler;

    private MirrorSource currentMirrorSource = MirrorSource.HF_MIRROR;
    private boolean useDomesticMirror = true;
    private DownloadMethod currentDownloadMethod = DownloadMethod.CUSTOM; // 默认使用自定义下载器

    public enum MirrorSource {
        HUGGINGFACE("huggingface.co", "https://huggingface.co"),
        HF_MIRROR("hf-mirror.com", "https://hf-mirror.com"),
        HF_CN("hf-mirror.com", "https://hf-mirror.com"),
        MODELSCOPE("modelscope.cn", "https://www.modelscope.cn"),
        GITEE("gitee.com", "https://gitee.com"),
        CUSTOM("custom", null);

        public final String domain;
        public final String baseUrl;

        MirrorSource(String domain, String baseUrl) {
            this.domain = domain;
            this.baseUrl = baseUrl;
        }
    }

    private static final Map<String, String> MIRROR_URL_MAPPINGS = new HashMap<>();
    static {
        MIRROR_URL_MAPPINGS.put("huggingface.co", "hf-mirror.com");
        MIRROR_URL_MAPPINGS.put("cdn-lfs.huggingface.co", "hf-mirror.com");
        MIRROR_URL_MAPPINGS.put("huggingface.co:443", "hf-mirror.com");
    }

    private ModelDownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(maxConcurrentDownloads);
        this.progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        loadMirrorPreference();
    }

    public static ModelDownloadManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelDownloadManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelDownloadManager(context);
                }
            }
        }
        return INSTANCE;
    }

    private void loadMirrorPreference() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("model_download_prefs", Context.MODE_PRIVATE);
            String savedMirror = prefs.getString("mirror_source", MirrorSource.HF_MIRROR.name());
            currentMirrorSource = MirrorSource.valueOf(savedMirror);
            useDomesticMirror = prefs.getBoolean("use_domestic_mirror", true);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load mirror preference", e);
        }
    }

    private void saveMirrorPreference() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("model_download_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                .putString("mirror_source", currentMirrorSource.name())
                .putBoolean("use_domestic_mirror", useDomesticMirror)
                .apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save mirror preference", e);
        }
    }

    public void setUseDomesticMirror(boolean enabled) {
        this.useDomesticMirror = enabled;
        saveMirrorPreference();
    }

    public boolean isUseDomesticMirror() {
        return useDomesticMirror;
    }

    public void setCurrentMirrorSource(MirrorSource source) {
        this.currentMirrorSource = source;
        saveMirrorPreference();
    }

    public MirrorSource getCurrentMirrorSource() {
        return currentMirrorSource;
    }

    public String convertToDomesticMirror(String originalUrl) {
        if (!useDomesticMirror || originalUrl == null || originalUrl.isEmpty()) {
            return originalUrl;
        }
        try {
            java.net.URI uri = java.net.URI.create(originalUrl);
            String host = uri.getHost();
            if (host == null) return originalUrl;
            String mirrorDomain = MIRROR_URL_MAPPINGS.get(host);
            if (mirrorDomain != null) {
                return originalUrl.replace(host, mirrorDomain);
            }
            if (host.contains("modelscope.cn") || host.contains("hf-mirror.com")) {
                return originalUrl;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to convert URL", e);
        }
        return originalUrl;
    }

    public void setGlobalCallback(DownloadCallback callback) {
        this.globalCallback = callback;
    }

    // ==================== 下载方式选择 ====================

    public enum DownloadMethod {
        CUSTOM("自定义下载器"),      // 自定义下载器，支持暂停/恢复/速度显示
        SYSTEM("系统下载管理器");    // Android 系统 DownloadManager

        public final String displayName;
        DownloadMethod(String displayName) { this.displayName = displayName; }
    }

    public void setDownloadMethod(DownloadMethod method) {
        this.currentDownloadMethod = method;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("model_download_prefs", Context.MODE_PRIVATE);
            prefs.edit().putString("download_method", method.name()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save download method", e);
        }
    }

    public DownloadMethod getDownloadMethod() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("model_download_prefs", Context.MODE_PRIVATE);
            String saved = prefs.getString("download_method", DownloadMethod.CUSTOM.name());
            return DownloadMethod.valueOf(saved);
        } catch (Exception e) {
            return DownloadMethod.CUSTOM;
        }
    }

    public String download(ModelDownloadRequest request, DownloadCallback callback) {
        String modelId = request.modelId;
        String convertedUrl = convertToDomesticMirror(request.modelUrl);

        // 根据设置选择下载方式
        DownloadMethod method = getDownloadMethod();
        if (method == DownloadMethod.SYSTEM) {
            return downloadWithSystemManager(modelId, convertedUrl, request.modelPath, callback);
        }

        // 自定义下载器
        if (activeDownloads.get() >= maxConcurrentDownloads) {
            Log.w(TAG, "Max concurrent downloads reached");
            DownloadCallback cb = callback != null ? callback : globalCallback;
            if (cb != null) cb.onError(modelId, "已达到最大并发下载数");
            return null;
        }

        DownloadTask task = new DownloadTask(modelId, request, convertedUrl, callback != null ? callback : globalCallback);
        downloadTasks.put(modelId, task);
        executor.execute(task);
        activeDownloads.incrementAndGet();

        return modelId;
    }

    public String downloadPresetModel(String modelId, ModelPresetInfo presetInfo, DownloadCallback callback) {
        String modelDir = new File(context.getFilesDir(), "ai_models").getAbsolutePath();
        String modelPath = modelDir + File.separator + getFileNameFromUrl(presetInfo.downloadUrl);

        ModelDownloadRequest request = new ModelDownloadRequest(
            modelId, presetInfo.downloadUrl, modelPath,
            presetInfo.sizeMB * 1024 * 1024, null
        );
        return download(request, callback);
    }

    public String downloadFromCustomUrl(String modelId, String url, DownloadCallback callback) {
        String modelDir = new File(context.getFilesDir(), "ai_models").getAbsolutePath();
        String modelPath = modelDir + File.separator + getFileNameFromUrl(url);

        ModelDownloadRequest request = new ModelDownloadRequest(modelId, url, modelPath, 0, null);
        return download(request, callback);
    }

    private String getFileNameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "model.gguf";
        try {
            String decoded = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
            int lastSlash = decoded.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < decoded.length() - 1) {
                String fileName = decoded.substring(lastSlash + 1);
                int queryIdx = fileName.indexOf('?');
                if (queryIdx > 0) fileName = fileName.substring(0, queryIdx);
                return fileName;
            }
        } catch (Exception ignored) {}
        return "model.gguf";
    }

    // ==================== 系统 DownloadManager 实现 ====================

    private String downloadWithSystemManager(String modelId, String url, String targetPath, DownloadCallback callback) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                if (callback != null) callback.onError(modelId, "系统下载管理器不可用");
                return null;
            }

            // 清理 URL，确保可访问
            String cleanUrl = url.trim();
            if (cleanUrl.contains("?")) {
                // 保留查询参数，但确保 URL 编码正确
                cleanUrl = cleanUrl.replace(" ", "%20");
            }
            
            String fileName = getFileNameFromUrl(cleanUrl);
            Log.i(TAG, "System download URL: " + cleanUrl);
            Log.i(TAG, "System download fileName: " + fileName);
            
            Uri uri = Uri.parse(cleanUrl);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle("答题宝 - " + modelId);
            request.setDescription("正在下载 " + fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            
            // 设置 User-Agent 避免被拒绝
            request.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
            
            // 下载到公共 Downloads 目录（不使用子目录，避免权限问题）
            String destFileName = "smartquiz_" + modelId + "_" + fileName;
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destFileName);
            
            long downloadId = dm.enqueue(request);
            systemDownloadIds.put(modelId, downloadId);
            
            // 保存目标路径和文件名用于后续移动
            systemDownloadTargets.put(modelId, new SystemDownloadTarget(targetPath, destFileName));
            
            // 初始化进度
            DownloadProgress progress = new DownloadProgress(modelId);
            progress.state = DownloadState.DOWNLOADING;
            downloadProgress.put(modelId, progress);
            
            if (callback != null) callback.onProgress(modelId, 0, 0, 0);
            
            // 启动进度轮询
            startSystemDownloadProgressPolling(modelId, downloadId, targetPath, destFileName, callback);
            
            Log.i(TAG, "System download started: " + modelId + ", downloadId: " + downloadId + ", destFile: " + destFileName);
            return modelId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start system download", e);
            if (callback != null) callback.onError(modelId, "启动系统下载失败: " + e.getMessage());
            return null;
        }
    }
    
    private void startSystemDownloadProgressPolling(String modelId, long downloadId, String targetPath, String fileName, DownloadCallback callback) {
        progressHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) return;
                
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                
                try (Cursor cursor = dm.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        
                        int status = cursor.getInt(statusIndex);
                        long bytesDownloaded = cursor.getLong(bytesIndex);
                        long totalBytes = cursor.getLong(totalIndex);
                        
                        DownloadProgress progress = downloadProgress.get(modelId);
                        if (progress == null) {
                            progress = new DownloadProgress(modelId);
                            downloadProgress.put(modelId, progress);
                        }
                        
                        progress.totalBytes = totalBytes;
                        progress.downloadedBytes = bytesDownloaded;
                        
                        int percent = progress.getProgressPercent();
                        long downloadedMB = bytesDownloaded / (1024 * 1024);
                        long totalMB = totalBytes / (1024 * 1024);
                        
                        switch (status) {
                            case DownloadManager.STATUS_RUNNING:
                                progress.state = DownloadState.DOWNLOADING;
                                if (callback != null) {
                                    callback.onProgress(modelId, percent, downloadedMB, totalMB);
                                }
                                if (globalCallback != null) {
                                    globalCallback.onProgress(modelId, percent, downloadedMB, totalMB);
                                }
                                // 继续轮询
                                progressHandler.postDelayed(this, 500);
                                break;
                                
                            case DownloadManager.STATUS_PAUSED:
                                progress.state = DownloadState.PAUSED;
                                if (callback != null) callback.onPaused(modelId);
                                if (globalCallback != null) globalCallback.onPaused(modelId);
                                progressHandler.postDelayed(this, 1000);
                                break;
                                
                            case DownloadManager.STATUS_SUCCESSFUL:
                                progress.state = DownloadState.COMPLETED;
                                // 移动文件到私有目录
                                moveSystemDownloadToPrivateDir(modelId, fileName, targetPath, callback);
                                break;
                                
                            case DownloadManager.STATUS_FAILED:
                                int reason = cursor.getInt(reasonIndex);
                                progress.state = DownloadState.FAILED;
                                progress.errorMessage = "下载失败，错误码: " + reason;
                                if (callback != null) callback.onError(modelId, progress.errorMessage);
                                if (globalCallback != null) globalCallback.onError(modelId, progress.errorMessage);
                                systemDownloadIds.remove(modelId);
                                break;
                        }
                    } else {
                        // 下载记录不存在，可能已被移除
                        systemDownloadIds.remove(modelId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error polling system download progress", e);
                    progressHandler.postDelayed(this, 1000);
                }
            }
        }, 500);
    }
    
    private void moveSystemDownloadToPrivateDir(String modelId, String destFileName, String targetPath, DownloadCallback callback) {
        new Thread(() -> {
            try {
                // 源文件在 Downloads 目录根目录（不使用子目录）
                File sourceFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), destFileName);
                File targetFile = new File(targetPath);
                targetFile.getParentFile().mkdirs();
                
                Log.i(TAG, "Moving system download from: " + sourceFile.getAbsolutePath());
                Log.i(TAG, "To target: " + targetPath);
                
                if (!sourceFile.exists()) {
                    throw new Exception("源文件不存在: " + sourceFile.getAbsolutePath());
                }
                
                // 复制文件
                try (InputStream in = new FileInputStream(sourceFile);
                     OutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                
                // 删除源文件
                sourceFile.delete();
                
                Log.i(TAG, "System download moved to private dir: " + targetPath);
                
                if (callback != null) callback.onComplete(modelId, targetPath);
                if (globalCallback != null) globalCallback.onComplete(modelId, targetPath);
                
                systemDownloadIds.remove(modelId);
                systemDownloadTargets.remove(modelId);
                downloadProgress.remove(modelId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to move system download", e);
                if (callback != null) callback.onError(modelId, "移动文件失败: " + e.getMessage());
                if (globalCallback != null) globalCallback.onError(modelId, "移动文件失败: " + e.getMessage());
            }
        }).start();
    }
    
    // 系统下载目标信息
    private static class SystemDownloadTarget {
        final String targetPath;
        final String destFileName;
        
        SystemDownloadTarget(String targetPath, String destFileName) {
            this.targetPath = targetPath;
            this.destFileName = destFileName;
        }
    }

    public void pause(String modelId) {
        // 检查是否是系统下载
        Long systemId = systemDownloadIds.get(modelId);
        if (systemId != null) {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                // 系统 DownloadManager 不支持直接暂停，需要取消后重新下载
                Log.i(TAG, "System download cannot be paused directly, use cancel instead");
            }
            return;
        }
        
        DownloadTask task = downloadTasks.get(modelId);
        if (task != null) {
            task.pause();
            DownloadProgress progress = downloadProgress.get(modelId);
            if (progress != null) progress.state = DownloadState.PAUSED;
            if (globalCallback != null) globalCallback.onPaused(modelId);
        }
    }

    public void resume(String modelId) {
        // 检查是否是系统下载
        Long systemId = systemDownloadIds.get(modelId);
        if (systemId != null) {
            Log.i(TAG, "System download resume not needed, handled by system");
            return;
        }
        
        DownloadTask task = downloadTasks.get(modelId);
        if (task != null) {
            DownloadProgress progress = downloadProgress.get(modelId);
            if (progress != null && progress.state == DownloadState.PAUSED) {
                task.resume();
                progress.state = DownloadState.DOWNLOADING;
                if (globalCallback != null) globalCallback.onResumed(modelId);
            }
        }
    }

    public void cancel(String modelId) {
        // 检查是否是系统下载
        Long systemId = systemDownloadIds.get(modelId);
        if (systemId != null) {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.remove(systemId);
            }
            systemDownloadIds.remove(modelId);
            systemDownloadTargets.remove(modelId);
            downloadProgress.remove(modelId);
            if (globalCallback != null) globalCallback.onCancelled(modelId);
            return;
        }
        
        DownloadTask task = downloadTasks.get(modelId);
        if (task != null) {
            task.cancel();
            downloadTasks.remove(modelId);
            downloadProgress.remove(modelId);
            if (globalCallback != null) globalCallback.onCancelled(modelId);
        }
    }

    public void cancelAll() {
        // 取消所有系统下载
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) {
            for (Long systemId : systemDownloadIds.values()) {
                dm.remove(systemId);
            }
        }
        systemDownloadIds.clear();
        systemDownloadTargets.clear();
        
        // 取消所有自定义下载
        for (String taskId : new ArrayList<>(downloadTasks.keySet())) {
            cancel(taskId);
        }
    }

    public DownloadProgress getProgress(String modelId) {
        return downloadProgress.get(modelId);
    }

    public boolean isDownloading(String modelId) {
        DownloadProgress progress = downloadProgress.get(modelId);
        return progress != null && progress.state == DownloadState.DOWNLOADING;
    }

    public boolean isPaused(String modelId) {
        DownloadProgress progress = downloadProgress.get(modelId);
        return progress != null && progress.state == DownloadState.PAUSED;
    }

    public boolean isModelDownloaded(String modelPath) {
        File file = new File(modelPath);
        return file.exists() && file.length() > 0;
    }

    public int getActiveDownloadCount() {
        return activeDownloads.get();
    }

    public void cleanup() {
        executor.shutdown();
    }

    // ==================== 内部下载任务 ====================

    private class DownloadTask implements Runnable {
        private final String taskId;
        private final ModelDownloadRequest request;
        private final String downloadUrl;
        private final DownloadCallback callback;
        private volatile boolean isPaused = false;
        private volatile boolean isCancelled = false;

        DownloadTask(String taskId, ModelDownloadRequest request, String downloadUrl, DownloadCallback callback) {
            this.taskId = taskId;
            this.request = request;
            this.downloadUrl = downloadUrl;
            this.callback = callback;
        }

        void pause() { isPaused = true; }
        void resume() { isPaused = false; }
        void cancel() { isCancelled = true; }

        @Override
        public void run() {
            DownloadProgress progress = new DownloadProgress(taskId);
            progress.totalBytes = request.expectedSize;
            progress.state = DownloadState.CONNECTING;
            downloadProgress.put(taskId, progress);

            int attempt = 0;
            Exception lastError = null;

            while (attempt < MAX_RETRY_ATTEMPTS) {
                try {
                    if (attempt > 0) {
                        progress.state = DownloadState.CONNECTING;
                        Log.i(TAG, "Retry attempt " + attempt);
                    }
                    String result = downloadFile(progress);
                    if (result != null) {
                        progress.state = DownloadState.COMPLETED;
                        if (callback != null) callback.onComplete(taskId, result);
                        if (globalCallback != null) globalCallback.onComplete(taskId, result);
                        return;
                    }
                } catch (InterruptedException e) {
                    if (isPaused) {
                        progress.state = DownloadState.PAUSED;
                        return;
                    }
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (isCancelled || isPaused) break;
                    lastError = e;
                    attempt++;
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { break; }
                    }
                }
            }

            if (!isPaused) {
                progress.state = DownloadState.FAILED;
                progress.errorMessage = lastError != null ? lastError.getMessage() : "Download failed";
                if (callback != null) callback.onError(taskId, progress.errorMessage);
                if (globalCallback != null) globalCallback.onError(taskId, progress.errorMessage);
            }
        }

        private String downloadFile(DownloadProgress progress) throws Exception {
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            File outputFile = null;

            try {
                URL url = new URL(downloadUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept-Encoding", "identity");

                // 断点续传
                outputFile = new File(request.modelPath);
                outputFile.getParentFile().mkdirs();
                long existingBytes = 0;
                if (outputFile.exists() && outputFile.length() > 0) {
                    existingBytes = outputFile.length();
                    connection.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    // 支持续传
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    existingBytes = 0;
                } else {
                    throw new IOException("HTTP error: " + responseCode);
                }

                long contentLength = connection.getContentLengthLong();
                long totalBytes = responseCode == HttpURLConnection.HTTP_PARTIAL ? 
                    existingBytes + contentLength : 
                    (request.expectedSize > 0 ? request.expectedSize : contentLength);
                
                progress.totalBytes = totalBytes;
                progress.downloadedBytes = existingBytes;
                progress.state = DownloadState.DOWNLOADING;

                inputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                outputStream = new FileOutputStream(outputFile, existingBytes > 0);

                byte[] buffer = new byte[BUFFER_SIZE];
                long totalBytesRead = existingBytes;
                long lastUpdateTime = System.currentTimeMillis();
                long windowBytes = 0;
                long windowStart = System.currentTimeMillis();

                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (isCancelled) {
                        throw new InterruptedException("Cancelled");
                    }
                    while (isPaused && !isCancelled) {
                        Thread.sleep(200);
                    }
                    if (isCancelled) throw new InterruptedException("Cancelled");

                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    windowBytes += bytesRead;
                    progress.downloadedBytes = totalBytesRead;

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime >= 300) {
                        long windowElapsed = currentTime - windowStart;
                        if (windowElapsed > 0 && windowBytes > 0) {
                            progress.speedBps = (windowBytes * 1000L) / windowElapsed;
                        }
                        progress.lastUpdateTime = currentTime;
                        windowBytes = 0;
                        windowStart = currentTime;
                        lastUpdateTime = currentTime;

                        int percent = progress.getProgressPercent();
                        long downloadedMB = totalBytesRead / (1024 * 1024);
                        long totalMB = totalBytes / (1024 * 1024);
                        long eta = progress.getEstimatedTimeRemainingSeconds();

                        if (callback != null) {
                            callback.onProgress(taskId, percent, downloadedMB, totalMB);
                            callback.onSpeedUpdate(taskId, progress.speedBps, eta);
                        }
                    }
                }

                outputStream.flush();
                return outputFile.getAbsolutePath();

            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw e;
            } finally {
                if (connection != null) connection.disconnect();
                if (inputStream != null) try { inputStream.close(); } catch (IOException e) {}
                if (outputStream != null) try { outputStream.close(); } catch (IOException e) {}
                if (!isPaused && !isCancelled) {
                    activeDownloads.decrementAndGet();
                    downloadTasks.remove(taskId);
                }
            }
        }
    }

    // ==================== 公共接口和数据类 ====================

    public interface DownloadCallback {
        void onProgress(String modelId, int progress, long downloadedMB, long totalMB);
        void onSpeedUpdate(String modelId, long speedBps, long etaSeconds);
        void onComplete(String modelId, String filePath);
        void onError(String modelId, String error);
        void onPaused(String modelId);
        void onCancelled(String modelId);
        void onResumed(String modelId);
    }

    public static class DownloadProgress {
        public final String modelId;
        public long totalBytes;
        public long downloadedBytes;
        public DownloadState state;
        public String errorMessage;
        public long speedBps;
        public long lastUpdateTime;

        DownloadProgress(String modelId) {
            this.modelId = modelId;
            this.state = DownloadState.IDLE;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public int getProgressPercent() {
            return totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
        }

        public long getEstimatedTimeRemainingSeconds() {
            return speedBps > 0 ? (totalBytes - downloadedBytes) / speedBps : -1;
        }
    }

    public enum DownloadState {
        IDLE, CONNECTING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    public static class ModelDownloadRequest {
        public final String modelId;
        public final String modelUrl;
        public final String modelPath;
        public final long expectedSize;
        public final String checksum;

        public ModelDownloadRequest(String modelId, String modelUrl, String modelPath, long expectedSize, String checksum) {
            this.modelId = modelId;
            this.modelUrl = modelUrl;
            this.modelPath = modelPath;
            this.expectedSize = expectedSize;
            this.checksum = checksum;
        }
    }

    public enum DownloadPriority { LOW, NORMAL, HIGH }

    public enum ModelCategory { ALL, CHINESE, CODE, LIGHTWEIGHT, PERFORMANCE }

    public List<ModelPresetInfo> getPresetModelsByCategory(ModelCategory category) {
        List<ModelPresetInfo> allModels = getPresetDomesticModels();
        if (category == ModelCategory.ALL) return allModels;
        List<ModelPresetInfo> filtered = new ArrayList<>();
        for (ModelPresetInfo model : allModels) {
            String name = model.name.toLowerCase();
            String desc = model.description.toLowerCase();
            switch (category) {
                case CHINESE:
                    if (name.contains("qwen") || name.contains("glm") || name.contains("minicpm") || desc.contains("中文"))
                        filtered.add(model);
                    break;
                case CODE:
                    if (name.contains("coder") || desc.contains("代码") || desc.contains("编程"))
                        filtered.add(model);
                    break;
                case LIGHTWEIGHT:
                    if (model.sizeMB < 1000) filtered.add(model);
                    break;
                case PERFORMANCE:
                    if (model.sizeMB >= 2000) filtered.add(model);
                    break;
            }
        }
        return filtered;
    }

    public static final String[] PRESET_DOMESTIC_MODEL_URLS = {
        "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/hugging-quants/Llama-3.2-1B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-1b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
        "https://hf-mirror.com/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
        "https://hf-mirror.com/openbmb/MiniCPM3-4B-GGUF/resolve/main/minicpm3-4b-q4_k_m.gguf",
        "https://hf-mirror.com/zai-org/glm-edge-1.5b-chat-gguf/resolve/main/ggml-model-Q4_K_M.gguf",
        "https://hf-mirror.com/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
    };

    public List<ModelPresetInfo> getPresetDomesticModels() {
        List<ModelPresetInfo> list = new ArrayList<>();
        list.add(new ModelPresetInfo("qwen2.5-0.5b", "Qwen2.5-0.5B", "轻量级中文模型", PRESET_DOMESTIC_MODEL_URLS[0], 350, "Q4_K_M", 32768, 1024, 2));
        list.add(new ModelPresetInfo("qwen2.5-1.5b", "Qwen2.5-1.5B", "平衡性能中文模型", PRESET_DOMESTIC_MODEL_URLS[1], 950, "Q4_K_M", 32768, 2048, 4));
        list.add(new ModelPresetInfo("qwen2.5-3b", "Qwen2.5-3B", "强推理中文模型", PRESET_DOMESTIC_MODEL_URLS[2], 1900, "Q4_K_M", 32768, 4096, 8));
        list.add(new ModelPresetInfo("qwen2.5-coder-1.5b", "Qwen2.5-Coder-1.5B", "代码模型", PRESET_DOMESTIC_MODEL_URLS[3], 950, "Q4_K_M", 32768, 2048, 4));
        list.add(new ModelPresetInfo("llama-3.2-1b", "Llama-3.2-1B", "Meta轻量模型", PRESET_DOMESTIC_MODEL_URLS[4], 750, "Q4_K_M", 8192, 1024, 2));
        list.add(new ModelPresetInfo("llama-3.2-3b", "Llama-3.2-3B", "Meta平衡模型", PRESET_DOMESTIC_MODEL_URLS[5], 1900, "Q4_K_M", 8192, 2048, 4));
        list.add(new ModelPresetInfo("phi-3.5-mini", "Phi-3.5-mini", "微软推理模型", PRESET_DOMESTIC_MODEL_URLS[6], 2200, "Q4_K_M", 32768, 4096, 8));
        list.add(new ModelPresetInfo("phi-3-mini", "Phi-3-mini", "微软4K模型", PRESET_DOMESTIC_MODEL_URLS[7], 2300, "Q4", 4096, 4096, 8));
        list.add(new ModelPresetInfo("minicpm3-4b", "MiniCPM3-4B", "面壁中文模型", PRESET_DOMESTIC_MODEL_URLS[8], 2400, "Q4_K_M", 32768, 4096, 8));
        list.add(new ModelPresetInfo("glm-edge-1.5b", "GLM-Edge-1.5B", "智谱对话模型", PRESET_DOMESTIC_MODEL_URLS[9], 1000, "Q4_K_M", 32768, 2048, 4));
        list.add(new ModelPresetInfo("deepseek-r1-1.5b", "DeepSeek-R1-1.5B", "推理模型", PRESET_DOMESTIC_MODEL_URLS[10], 1100, "Q4_K_M", 32768, 2048, 4));
        return list;
    }

    public static class ModelPresetInfo {
        public final String id;
        public final String name;
        public final String description;
        public final String downloadUrl;
        public final long sizeMB;
        public final String quantization;
        public final int contextLength;
        public final long minRamMB;
        public final int recommendedGpuLayers;

        public ModelPresetInfo(String id, String name, String description, String downloadUrl,
                               long sizeMB, String quantization, int contextLength,
                               long minRamMB, int recommendedGpuLayers) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.downloadUrl = downloadUrl;
            this.sizeMB = sizeMB;
            this.quantization = quantization;
            this.contextLength = contextLength;
            this.minRamMB = minRamMB;
            this.recommendedGpuLayers = recommendedGpuLayers;
        }
    }
}
