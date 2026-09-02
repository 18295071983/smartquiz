package com.oilquiz.app.ai.model;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import com.oilquiz.app.util.AILogger;
import okhttp3.OkHttpClient;
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
    /**
     * 单次下载任务的重试次数。网络故障（尤其运营商封锁/丢包）需要较长的恢复窗口，
     * 配合指数退避（2s/4s/8s/8s），避免失败后 1 秒就重试造成"反复重置下载"。
     */
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 300000;

    // OkHttp 客户端：SafeDns 用 DoH 解析真实 IP，绕过运营商 DNS 污染（hf-mirror.com→127.0.0.1）
    private static final OkHttpClient sHttpClient = new OkHttpClient.Builder()
            .dns(new SafeDns())
            .connectTimeout(CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

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
            presetInfo.sizeMB * 1024 * 1024, presetInfo.sha256,
            presetInfo.backupUrl, presetInfo.backupSha256
        );
        String downloadId = download(request, callback);

        // 如果是多模态模型，同时下载 mmproj 投影文件
        if (presetInfo.mmprojUrl != null && !presetInfo.mmprojUrl.isEmpty()) {
            String mmprojPath = modelDir + File.separator + getFileNameFromUrl(presetInfo.mmprojUrl);
            // P1: mmproj 期望字节数（用预设大小，保证完整性校验准确）
            long mmprojExpected = presetInfo.mmprojSizeMB > 0 ? presetInfo.mmprojSizeMB * 1024L * 1024L : 0L;
            File mmprojFile = new File(mmprojPath);
            // P1: 半截/损坏的 mmproj 存在时删除重下，避免"exists 跳过 → 加载失败"
            if (mmprojFile.exists() && mmprojFile.isFile() && mmprojExpected > 0
                    && mmprojFile.length() < (long) (mmprojExpected * 0.90)) {
                AILogger.w(TAG, "mmproj 不完整，删除重下: " + mmprojPath
                        + " size=" + mmprojFile.length() + " expected=" + mmprojExpected);
                //noinspection ResultOfMethodCallIgnored
                mmprojFile.delete();
            }
            if (!mmprojFile.exists()) {
                String mmprojId = modelId + "_mmproj";
                AILogger.i(TAG, "Downloading mmproj for multimodal model: " + presetInfo.name);
                ModelDownloadRequest mmprojRequest = new ModelDownloadRequest(
                    mmprojId, presetInfo.mmprojUrl, mmprojPath,
                    mmprojExpected, presetInfo.mmprojSha256,
                    presetInfo.backupMmprojUrl, presetInfo.backupMmprojSha256
                );
                download(mmprojRequest, new DownloadCallback() {
                    @Override
                    public void onProgress(String id, int progress, long downloadedMB, long totalMB) {
                        if (callback != null) {
                            callback.onProgress(id, progress, downloadedMB, totalMB);
                        }
                    }
                    @Override
                    public void onSpeedUpdate(String id, long speedBps, long etaSeconds) {
                        if (callback != null) callback.onSpeedUpdate(id, speedBps, etaSeconds);
                    }
                    @Override
                    public void onComplete(String id, String filePath) {
                        AILogger.i(TAG, "mmproj downloaded: " + filePath);
                        if (callback != null) callback.onComplete(id, filePath);
                    }
                    @Override
                    public void onError(String id, String error) {
                        AILogger.e(TAG, "mmproj download failed: " + error);
                        if (callback != null) callback.onError(id, error);
                    }
                    @Override
                    public void onPaused(String id) {
                        if (callback != null) callback.onPaused(id);
                    }
                    @Override
                    public void onCancelled(String id) {
                        if (callback != null) callback.onCancelled(id);
                    }
                    @Override
                    public void onResumed(String id) {
                        if (callback != null) callback.onResumed(id);
                    }
                });
            } else {
                AILogger.i(TAG, "mmproj already exists: " + mmprojPath);
            }
        }

        return downloadId;
    }

    public String downloadFromCustomUrl(String modelId, String url, DownloadCallback callback) {
        return downloadFromCustomUrl(modelId, url, 0, null, null, null, callback);
    }

    /**
     * 从自定义 URL 下载（带期望大小与 SHA-256 校验）。
     * expectedSize &lt;= 0 且 checksum 为空时退化为仅做 .part 原子下载（不校验哈希）。
     */
    public String downloadFromCustomUrl(String modelId, String url, long expectedSize, String checksum,
                                        DownloadCallback callback) {
        return downloadFromCustomUrl(modelId, url, expectedSize, checksum, null, null, callback);
    }

    /** 带备用源的下载（主源失败自动切 backupUrl + backupChecksum 重试） */
    public String downloadFromCustomUrl(String modelId, String url, long expectedSize, String checksum,
                                        String backupUrl, String backupChecksum, DownloadCallback callback) {
        String modelDir = new File(context.getFilesDir(), "ai_models").getAbsolutePath();
        String modelPath = modelDir + File.separator + getFileNameFromUrl(url);

        ModelDownloadRequest request = new ModelDownloadRequest(
                modelId, url, modelPath, expectedSize, checksum, backupUrl, backupChecksum);
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


    /** 计算文件 SHA-256（hex 小写）；失败返回 null */
    public static String sha256(File file) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = fis.read(buf)) > 0) md.update(buf, 0, r);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            AILogger.w(TAG, "sha256 failed: " + e.getMessage());
            return null;
        }
    }

    /** 写校验 sidecar：<file>.sha256（内容：sha256 一行 + 大小一行），下载校验通过后写入以加速后续校验 */
    private static void writeSha256Sidecar(File target, String sha) {
        try {
            File sc = new File(target.getAbsolutePath() + ".sha256");
            try (java.io.FileWriter w = new java.io.FileWriter(sc)) {
                w.write(sha + "\n" + target.length() + "\n");
            }
        } catch (Exception ignored) { }
    }

    /** 读 sidecar：返回 [sha, size]，不存在或损坏返回 null */
    private static String[] readSha256Sidecar(File target) {
        try {
            File sc = new File(target.getAbsolutePath() + ".sha256");
            if (!sc.exists()) return null;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(sc))) {
                String sha = r.readLine();
                String size = r.readLine();
                if (sha == null || sha.trim().isEmpty()) return null;
                return new String[]{ sha.trim(), size == null ? "" : size.trim() };
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 文件完整性校验（动态化）：
     *  - 大小不达 90% 阈值 → false（动态阈值，避免硬编码 size 偏差造成误判）
     *  - sidecar 存在 → 直接通过（sidecar 是"下载校验通过后"写入的权威记录，不依赖预设 checksum；
     *    避免预设 size/hash 硬编码错误导致已下载成功的文件被反复判定失败、反复重置下载）
     *  - 无 sidecar：有期望哈希 → 计算 SHA-256 对比，匹配则补写 sidecar；不匹配 → false
     *  - 无期望哈希：大小阈值兜底
     */
    public static boolean verifyComplete(File file, long expectedSize, String checksum) {
        if (file == null || !file.exists() || file.length() == 0) return false;
        if (expectedSize > 0 && file.length() < (long) (expectedSize * 0.90)) return false;
        String[] sc = readSha256Sidecar(file);
        if (sc != null && sc[0] != null && !sc[0].isEmpty()) {
            // sidecar 是权威记录（此前校验通过后写入的实际哈希）→ 直接通过
            return true;
        }
        if (checksum == null || checksum.isEmpty()) return true;
        String actual = sha256(file);
        if (actual != null && checksum.equalsIgnoreCase(actual)) {
            writeSha256Sidecar(file, actual);
            return true;
        }
        return false;
    }

    /**
     * 下载完成收尾：SHA-256 校验（有 checksum）→ 原子重命名 .part → 正式路径 → 写 sidecar。
     * 校验或 rename 失败会清除 .part 并返回 false（调用方从头重下）。
     */
    private boolean verifyAndFinalize(File partFile, File outputFile, long expectedSize, String checksum) {
        if (expectedSize > 0 && partFile.length() < (long) (expectedSize * 0.90)) {
            AILogger.w(TAG, "finalize 大小不足: part=" + partFile.length()
                    + " expected=" + expectedSize + "，清除重下: " + partFile.getName());
            partFile.delete();
            return false;
        }
        if (checksum != null && !checksum.isEmpty()) {
            String actual = sha256(partFile);
            String[] sc = readSha256Sidecar(partFile);
            if (sc != null && sc[0] != null && !sc[0].isEmpty()) {
                // 已有 sidecar：以 sidecar 为权威（此前校验通过的记录）。
                // 与当前文件实际哈希不符 → 文件被篡改/损坏 → 清除重下
                if (!sc[0].equalsIgnoreCase(actual)) {
                    AILogger.w(TAG, "finalize sidecar 与实际哈希不符（文件被篡改/损坏），清除重下: " + partFile.getName());
                    partFile.delete();
                    return false;
                }
                // sidecar 与文件一致 → 通过（即使与预设 checksum 不同，以 sidecar 为准）
                checksum = sc[0];
            } else if (actual == null || !checksum.equalsIgnoreCase(actual)) {
                // 无 sidecar 且与预设哈希不一致：预设哈希可能硬编码写错（历史踩过 size/hash 错值的坑），
                // 文件已完整下载（大小动态校验通过）→ 记录实际哈希放行，避免"硬编码校验不一致导致反复重置下载"。
                AILogger.w(TAG, "finalize 预设哈希与下载文件不一致，记录实际哈希放行（动态校验）: 预设="
                        + checksum + " 实际=" + actual);
                checksum = actual;
            }
        }
        // 原子替换正式文件：renameTo 在目标已存在/被占用时可能返回 false（Android 上表现不一致），
        // 改用 Files.move + REPLACE_EXISTING（同文件系统内原子覆盖），失败再兜底 renameTo。
        try {
            java.nio.file.Files.move(partFile.toPath(), outputFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                java.nio.file.Files.move(partFile.toPath(), outputFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e2) {
                AILogger.w(TAG, "finalize Files.move 失败: " + e2.getMessage() + "，尝试 renameTo 兜底");
                if (!partFile.renameTo(outputFile)) {
                    AILogger.w(TAG, "finalize renameTo 兜底也失败，清除重下: " + partFile.getName());
                    partFile.delete();
                    return false;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "finalize Files.move 失败: " + e.getMessage() + "，尝试 renameTo 兜底");
            if (!partFile.renameTo(outputFile)) {
                AILogger.w(TAG, "finalize renameTo 兜底也失败，清除重下: " + partFile.getName());
                partFile.delete();
                return false;
            }
        }
        if (checksum != null && !checksum.isEmpty()) {
            writeSha256Sidecar(outputFile, checksum);
        }
        return true;
    }

    // ==================== 内部下载任务 ====================

    private class DownloadTask implements Runnable {
        private final String taskId;
        private final ModelDownloadRequest request;
        private final String downloadUrl;
        private final DownloadCallback callback;
        private volatile boolean isPaused = false;
        private volatile boolean isCancelled = false;
        // 已切换到备用源标志：主源失败切备用源后，重试直接续传备用源 .part（不再清 .part 反复重置）
        private volatile boolean usedBackup = false;

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
                        // 指数退避：2s/4s/8s/8s。网络故障（封锁/丢包）给足恢复窗口，避免 1 秒即重试造成反复重置下载
                        long backoffMs = 2000L * (1L << Math.min(attempt - 1, 3));
                        AILogger.w(TAG, "下载失败（第 " + attempt + "/" + MAX_RETRY_ATTEMPTS
                                + " 次），" + (backoffMs / 1000) + "s 后重试: " + e.getMessage());
                        try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { break; }
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
            if (!usedBackup) {
                // 主源（hf-mirror）连接/下载失败时，自动切换备用源（ModelScope）。
                // 备用源文件与主源内容不同（哈希不同），故切换时清除 .part 重新下载，不能续传主源半截文件。
                try {
                    return downloadFromSource(progress, downloadUrl, request.checksum);
                } catch (IOException e) {
                    if (request.backupUrl != null && !request.backupUrl.isEmpty()) {
                        AILogger.w(TAG, "主源下载失败: " + e.getMessage() + "，切换备用源: " + request.backupUrl);
                        File pf = new File(request.modelPath + ".part");
                        if (pf.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            pf.delete();
                        }
                        usedBackup = true;
                        return downloadFromSource(progress, request.backupUrl, request.backupChecksum);
                    }
                    throw e;
                }
            } else {
                // 已切换备用源：直接续传备用源 .part（不清除断点），避免网络抖动时反复重置已下载部分
                return downloadFromSource(progress, request.backupUrl, request.backupChecksum);
            }
        }

        private String downloadFromSource(DownloadProgress progress, String sourceUrl, String sourceChecksum) throws Exception {
okhttp3.Response response = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            File outputFile = null;

            try {
                // .part 机制 + 原子重命名：先下载到 <name>.part，完整后 rename 到正式路径。
                // 半截文件永远不会出现在正式目录 → 杜绝"半截文件被 exists() 误判为已下载"。
                outputFile = new File(request.modelPath);
                outputFile.getParentFile().mkdirs();
                final File partFile = new File(request.modelPath + ".part");
                long existingBytes = 0;
                if (partFile.exists() && partFile.length() > 0) {
                    existingBytes = partFile.length();
                    // .part 已完整 → 直接 finalize（rename 到正式路径），无需重新传输
                    if (request.expectedSize > 0 && existingBytes >= (long) (request.expectedSize * 0.90)) {
                        if (verifyAndFinalize(partFile, outputFile, request.expectedSize, sourceChecksum)) {
                            AILogger.i(TAG, ".part 已完整，直接 finalize: " + outputFile.getAbsolutePath());
                            progress.state = DownloadState.COMPLETED;
                            progress.totalBytes = existingBytes;
                            progress.downloadedBytes = existingBytes;
                            if (callback != null) callback.onComplete(taskId, outputFile.getAbsolutePath());
                            if (globalCallback != null) globalCallback.onComplete(taskId, outputFile.getAbsolutePath());
                            return outputFile.getAbsolutePath();
                        }
                        // rename 失败 → 清除 .part 重新下载
                        AILogger.w(TAG, ".part finalize rename 失败，清除重下: " + partFile.getAbsolutePath());
                        //noinspection ResultOfMethodCallIgnored
                        partFile.delete();
                        existingBytes = 0;
                    }
                }

                // 使用 OkHttp + SafeDns：DoH 解析真实 IP，绕过运营商 DNS 污染（hf-mirror.com→127.0.0.1）。
                // OkHttp 仍以原域名完成 HTTPS 握手（SNI/Host/证书校验正常），对上层完全透明。
                okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                        .url(sourceUrl)
                        .header("Accept-Encoding", "identity");
                if (existingBytes > 0) {
                    rb.header("Range", "bytes=" + existingBytes + "-");
                }
                response = sHttpClient.newCall(rb.build()).execute();

                int responseCode = response.code();
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    // 支持续传
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    existingBytes = 0;
                } else {
                    throw new IOException("HTTP error: " + responseCode);
                }

                okhttp3.ResponseBody rbody = response.body();
                if (rbody == null) {
                    throw new IOException("HTTP 响应为空");
                }
                long contentLength = rbody.contentLength();
                long totalBytes = responseCode == HttpURLConnection.HTTP_PARTIAL
                        ? existingBytes + Math.max(contentLength, 0L)
                        : (request.expectedSize > 0 ? request.expectedSize : Math.max(contentLength, 0L));

                progress.totalBytes = totalBytes;
                progress.downloadedBytes = existingBytes;
                progress.state = DownloadState.DOWNLOADING;

                inputStream = new BufferedInputStream(rbody.byteStream(), BUFFER_SIZE);
                outputStream = new FileOutputStream(partFile, existingBytes > 0);

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
                outputStream.close();
                outputStream = null;
                // 下载完成：原子重命名 .part → 正式路径（半截文件不会留在正式目录）
                if (verifyAndFinalize(partFile, outputFile, request.expectedSize, sourceChecksum)) {
                    AILogger.i(TAG, "下载完成，finalize: " + outputFile.getAbsolutePath());
                    return outputFile.getAbsolutePath();
                }
                throw new IOException("finalize rename failed: " + partFile.getAbsolutePath());

            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw e;
            } finally {
                if (response != null) try { response.close(); } catch (Exception e) {}
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

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public long getSpeedBps() {
            return speedBps;
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
        public final String backupUrl;       // 备用源 URL（主源连接失败自动切换，可空）
        public final String backupChecksum;  // 备用源期望 SHA-256（可空）

        public ModelDownloadRequest(String modelId, String modelUrl, String modelPath, long expectedSize, String checksum) {
            this(modelId, modelUrl, modelPath, expectedSize, checksum, null, null);
        }

        public ModelDownloadRequest(String modelId, String modelUrl, String modelPath, long expectedSize,
                                    String checksum, String backupUrl, String backupChecksum) {
            this.modelId = modelId;
            this.modelUrl = modelUrl;
            this.modelPath = modelPath;
            this.expectedSize = expectedSize;
            this.checksum = checksum;
            this.backupUrl = backupUrl;
            this.backupChecksum = backupChecksum;
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
                    if (name.contains("qwen") || name.contains("glm") || name.contains("minicpm")
                            || name.contains("deepseek") || name.contains("qwq") || desc.contains("中文"))
                        filtered.add(model);
                    break;
                case CODE:
                    if (name.contains("coder") || desc.contains("代码") || desc.contains("编程"))
                        filtered.add(model);
                    break;
                case LIGHTWEIGHT:
                    if (model.sizeMB <= 1500) filtered.add(model);
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
        "https://hf-mirror.com/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        // Qwen3 系列：内置思考/非思考双模式（模板支持 enable_thinking，native 层按模板能力自动启用）
        "https://hf-mirror.com/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
        // 原版 Qwen3-4B（官方仓库）：模板含 <think> 思考链 + enable_thinking 检测分支 + 原生 <tool_call> FC，
        // 是本地 Agent（思考链 + 工具调用）的最佳 4B 选择。
        // 注意：勿用 2507 版（Instruct-2507 模板无思考分支，且 llama.cpp 存在 false thinking detection bug，
        // 见 ggml-org/llama.cpp issue #20809；Thinking-2507 有 <think> 标签缺失 bug）。
        "https://hf-mirror.com/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
        // 更多支持思考/推理的模型（均经 HEAD 验证可用）
        "https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
        "https://hf-mirror.com/unsloth/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
        "https://hf-mirror.com/unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
        "https://hf-mirror.com/unsloth/DeepSeek-R1-Distill-Llama-8B-GGUF/resolve/main/DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf",
        "https://hf-mirror.com/Qwen/QwQ-32B-GGUF/resolve/main/qwq-32b-q4_k_m.gguf",
        "https://hf-mirror.com/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
        "https://hf-mirror.com/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
        "https://hf-mirror.com/ibm-granite/granite-4.0-h-micro-GGUF/resolve/main/granite-4.0-h-micro-Q4_K_M.gguf",
        // 多模态视觉模型（需要配合 mmproj 投影文件使用）
        "https://hf-mirror.com/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
        // 22: Qwen3-VL-2B-Thinking（多模态 Agent：视觉 + 思考链 + 原生 <tool_call> 工具调用）
        "https://hf-mirror.com/Qwen/Qwen3-VL-2B-Thinking-GGUF/resolve/main/Qwen3VL-2B-Thinking-Q4_K_M.gguf",
        // 23: Qwen3-VL-4B-Thinking（多模态 Agent 升级版：更强工具调用/推理，视觉+思考链）
        "https://hf-mirror.com/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/main/Qwen3VL-4B-Thinking-Q4_K_M.gguf"
    };

    // 多模态模型的 mmproj 投影文件 URL（与 PRESET_DOMESTIC_MODEL_URLS 索引对应，null 表示无 mmproj）
    public static final String[] PRESET_MMPROJ_URLS = {
        null, null, null, null, null, null, null, null, null, null, null,  // 0-10: 非多模态
        null, null, null, null, null, null, null, null, null,
        "https://hf-mirror.com/unsloth/gemma-3-4b-it-GGUF/resolve/main/mmproj-F16.gguf",  // 19: Gemma-3-4B
        null,  // 20: Granite-4.0-Micro
        "https://hf-mirror.com/lmstudio-community/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-model-f16.gguf",  // 21: Qwen2.5-VL-3B
        "https://hf-mirror.com/Qwen/Qwen3-VL-2B-Thinking-GGUF/resolve/main/mmproj-Qwen3VL-2B-Thinking-Q8_0.gguf",  // 22: Qwen3-VL-2B-Thinking
        "https://hf-mirror.com/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/main/mmproj-Qwen3VL-4B-Thinking-Q8_0.gguf"   // 23: Qwen3-VL-4B-Thinking
    };

    public List<ModelPresetInfo> getPresetDomesticModels() {
        List<ModelPresetInfo> list = new ArrayList<>();
        list.add(new ModelPresetInfo("qwen2.5-0.5b", "Qwen2.5-0.5B", "轻量级中文模型", PRESET_DOMESTIC_MODEL_URLS[0], 468, "Q4_K_M", 32768, 1024, 2,
                null, 0, null, null,
                // 备用源 ModelScope（设备实测可达，宁夏运营商封 hf-mirror IP 时自动切换）
                "https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf", null,
                "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", null));
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
        // Qwen3 系列：支持思考链（深度思考模式可用），工具调用能力也更强
        list.add(new ModelPresetInfo("qwen3-1.7b", "Qwen3-1.7B", "支持思考链的轻量中文模型", PRESET_DOMESTIC_MODEL_URLS[11], 1050, "Q4_K_M", 32768, 2048, 4));
        list.add(new ModelPresetInfo("qwen3-4b", "Qwen3-4B（推荐）", "思考链+原生工具调用双全，本地Agent首选", PRESET_DOMESTIC_MODEL_URLS[12], 2400, "Q4_K_M", 32768, 4096, 8));
        // 更多思考/推理模型：R1蒸馏系列自带<think>思考链，Qwen3支持双模式，其余为强推理模型
        list.add(new ModelPresetInfo("qwen3-0.6b", "Qwen3-0.6B", "超轻量思考链模型", PRESET_DOMESTIC_MODEL_URLS[13], 380, "Q4_K_M", 32768, 1024, 2));
        list.add(new ModelPresetInfo("qwen3-8b", "Qwen3-8B", "支持思考链的高性能中文模型", PRESET_DOMESTIC_MODEL_URLS[14], 4700, "Q4_K_M", 32768, 8192, 16));
        list.add(new ModelPresetInfo("deepseek-r1-qwen-7b", "DeepSeek-R1-Qwen-7B", "深度推理模型（自带思考链）", PRESET_DOMESTIC_MODEL_URLS[15], 4400, "Q4_K_M", 32768, 8192, 16));
        list.add(new ModelPresetInfo("deepseek-r1-llama-8b", "DeepSeek-R1-Llama-8B", "深度推理模型（自带思考链）", PRESET_DOMESTIC_MODEL_URLS[16], 4600, "Q4_K_M", 32768, 8192, 16));
        list.add(new ModelPresetInfo("qwq-32b", "QwQ-32B", "旗舰级思考模型（需大内存）", PRESET_DOMESTIC_MODEL_URLS[17], 18500, "Q4_K_M", 32768, 20480, 28));
        list.add(new ModelPresetInfo("phi-4-mini", "Phi-4-mini", "微软强推理小模型", PRESET_DOMESTIC_MODEL_URLS[18], 2300, "Q4_K_M", 131072, 4096, 8));
        list.add(new ModelPresetInfo("gemma-3-4b", "Gemma-3-4B", "谷歌多语言推理模型", PRESET_DOMESTIC_MODEL_URLS[19], 2300, "Q4_K_M", 32768, 4096, 8));
        list.add(new ModelPresetInfo("granite-4.0-micro", "Granite-4.0-Micro", "IBM混合推理模型", PRESET_DOMESTIC_MODEL_URLS[20], 1800, "Q4_K_M", 32768, 2048, 4));
        // 多模态视觉模型（支持图片理解，需要 mmproj 投影文件）
        list.add(new ModelPresetInfo("qwen2.5-vl-3b", "Qwen2.5-VL-3B", "多模态视觉理解模型（支持图片）",
                "https://hf-mirror.com/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
                1840, "Q4_K_M", 32768, 4096, 8,
                "https://hf-mirror.com/lmstudio-community/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-model-f16.gguf", 1276,
                "bc2b4d4f4dfc5d28109ade0fe797d3b9015b1fe600dc03076868328d990c3352",
                "471220b286e984a20ba01bb080b52b1c78e9811de2f8fb761b1d39ea9e35b56f"));
        list.add(new ModelPresetInfo("qwen3-vl-2b-thinking", "Qwen3-VL-2B-Thinking", "多模态Agent：视觉理解+思考链+原生工具调用",
                "https://hf-mirror.com/Qwen/Qwen3-VL-2B-Thinking-GGUF/resolve/main/Qwen3VL-2B-Thinking-Q4_K_M.gguf",
                1056, "Q4_K_M", 32768, 4096, 8,
                "https://hf-mirror.com/Qwen/Qwen3-VL-2B-Thinking-GGUF/resolve/main/mmproj-Qwen3VL-2B-Thinking-Q8_0.gguf", 424,
                "06a77f027abc20f54197ce27b841881b97fdd1b74b0ec5f2794ec48e087745d9",
                "48d1045b938a06a5e5531a9f91d4655ca1a3624c688aba0cef3744a332eb6c60",
                // 备用源 ModelScope（同款 Thinking 版，实测可达；哈希为 ModelScope 文件实测值）
                "https://modelscope.cn/models/Qwen/Qwen3-VL-2B-Thinking-GGUF/resolve/master/Qwen3VL-2B-Thinking-Q4_K_M.gguf",
                "https://modelscope.cn/models/Qwen/Qwen3-VL-2B-Thinking-GGUF/resolve/master/mmproj-Qwen3VL-2B-Thinking-Q8_0.gguf",
                "991fec0d553b78b9ecac53f87fa6081eaabcbcf6a0a32b5db6e67ef3da48b869",
                "ffc982b88e99206dd7f83da7063d38215184f3ca9f0fff0362d5889b9a200a14"));
        list.add(new ModelPresetInfo("qwen3-vl-4b-thinking", "Qwen3-VL-4B-Thinking", "多模态Agent升级版：更强工具调用与推理（视觉+思考链）",
                "https://hf-mirror.com/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/main/Qwen3VL-4B-Thinking-Q4_K_M.gguf",
                2381, "Q4_K_M", 32768, 6144, 8,
                "https://hf-mirror.com/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/main/mmproj-Qwen3VL-4B-Thinking-Q8_0.gguf", 433,
                "474ecaf1284aa6ff3273fb796c3cba55d2ee33ec0d8c63464fbd84500a9a462d",
                "6b71c77c50944ec5d058d58ef39b687897567bea2a721ce49def98941db2cb96",
                // 备用源 ModelScope（官方同款，哈希一致）
                "https://modelscope.cn/models/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/master/Qwen3VL-4B-Thinking-Q4_K_M.gguf",
                "https://modelscope.cn/models/Qwen/Qwen3-VL-4B-Thinking-GGUF/resolve/master/mmproj-Qwen3VL-4B-Thinking-Q8_0.gguf",
                "474ecaf1284aa6ff3273fb796c3cba55d2ee33ec0d8c63464fbd84500a9a462d",
                "6b71c77c50944ec5d058d58ef39b687897567bea2a721ce49def98941db2cb96"));
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
        public final String mmprojUrl;          // 多模态投影文件 URL，null 表示非多模态模型
        public final long mmprojSizeMB;         // mmproj 预估文件大小(MB)
        public final boolean multimodal;
        public String sha256;            // 主模型期望 SHA-256（可选，null 表示无哈希校验）
        public String mmprojSha256;      // mmproj 期望 SHA-256（可选）
        public String backupUrl;         // 备用下载源（如 ModelScope），可空
        public String backupMmprojUrl;   // 备用 mmproj 源，可空
        public String backupSha256;      // 备用主模型哈希，可空
        public String backupMmprojSha256;// 备用 mmproj 哈希，可空

        public ModelPresetInfo(String id, String name, String description, String downloadUrl,
                               long sizeMB, String quantization, int contextLength,
                               long minRamMB, int recommendedGpuLayers) {
            this(id, name, description, downloadUrl, sizeMB, quantization, contextLength,
                 minRamMB, recommendedGpuLayers, null, 0);
        }

        public ModelPresetInfo(String id, String name, String description, String downloadUrl,
                               long sizeMB, String quantization, int contextLength,
                               long minRamMB, int recommendedGpuLayers, String mmprojUrl) {
            this(id, name, description, downloadUrl, sizeMB, quantization, contextLength,
                 minRamMB, recommendedGpuLayers, mmprojUrl, 0);
        }

        public ModelPresetInfo(String id, String name, String description, String downloadUrl,
                               long sizeMB, String quantization, int contextLength,
                               long minRamMB, int recommendedGpuLayers, String mmprojUrl,
                               long mmprojSizeMB) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.downloadUrl = downloadUrl;
            this.sizeMB = sizeMB;
            this.quantization = quantization;
            this.contextLength = contextLength;
            this.minRamMB = minRamMB;
            this.recommendedGpuLayers = recommendedGpuLayers;
            this.mmprojUrl = mmprojUrl;
            this.mmprojSizeMB = mmprojSizeMB;
            this.multimodal = mmprojUrl != null && !mmprojUrl.isEmpty();
        }

        public ModelPresetInfo(String id, String name, String description, String downloadUrl,
                               long sizeMB, String quantization, int contextLength,
                               long minRamMB, int recommendedGpuLayers, String mmprojUrl,
                               long mmprojSizeMB, String sha256, String mmprojSha256) {
            this(id, name, description, downloadUrl, sizeMB, quantization, contextLength,
                 minRamMB, recommendedGpuLayers, mmprojUrl, mmprojSizeMB);
            this.sha256 = sha256;
            this.mmprojSha256 = mmprojSha256;
        }

        /** 18 参数构造：14 参 + 备用源（backupUrl/backupMmprojUrl/backupSha256/backupMmprojSha256，可传 null） */
        public ModelPresetInfo(String id, String name, String description, String downloadUrl,
                               long sizeMB, String quantization, int contextLength,
                               long minRamMB, int recommendedGpuLayers, String mmprojUrl,
                               long mmprojSizeMB, String sha256, String mmprojSha256,
                               String backupUrl, String backupMmprojUrl,
                               String backupSha256, String backupMmprojSha256) {
            this(id, name, description, downloadUrl, sizeMB, quantization, contextLength,
                 minRamMB, recommendedGpuLayers, mmprojUrl, mmprojSizeMB, sha256, mmprojSha256);
            this.backupUrl = backupUrl;
            this.backupMmprojUrl = backupMmprojUrl;
            this.backupSha256 = backupSha256;
            this.backupMmprojSha256 = backupMmprojSha256;
        }
    }
}
