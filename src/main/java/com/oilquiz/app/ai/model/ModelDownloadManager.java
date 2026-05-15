package com.oilquiz.app.ai.model;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ModelDownloadManager {
    private static final String TAG = "ModelDownloadManager";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 300000;

    private static volatile ModelDownloadManager INSTANCE;
    private final Context context;
    private final Map<String, DownloadTask> downloadTasks = new ConcurrentHashMap<>();
    private final Map<String, DownloadProgress> downloadProgress = new ConcurrentHashMap<>();
    private final AtomicInteger activeDownloads = new AtomicInteger(0);
    private final int maxConcurrentDownloads = 2;
    private ExecutorService executor;
    private DownloadCallback globalCallback;

    private ModelDownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(maxConcurrentDownloads);
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

    public void setGlobalCallback(DownloadCallback callback) {
        this.globalCallback = callback;
    }

    public String download(DownloadRequest request, DownloadCallback callback) {
        String taskId = request.modelId;

        if (activeDownloads.get() >= maxConcurrentDownloads) {
            Log.w(TAG, "Max concurrent downloads reached, queuing: " + taskId);
        }

        DownloadTask task = new DownloadTask(taskId, request, callback != null ? callback : globalCallback);
        downloadTasks.put(taskId, task);

        executor.execute(task);
        activeDownloads.incrementAndGet();

        return taskId;
    }

    public void pause(String modelId) {
        DownloadTask task = downloadTasks.get(modelId);
        if (task != null) {
            task.pause();
            DownloadProgress progress = downloadProgress.get(modelId);
            if (progress != null) {
                progress.state = DownloadState.PAUSED;
            }
            if (globalCallback != null) {
                globalCallback.onPaused(modelId);
            }
        }
    }

    public void cancel(String modelId) {
        DownloadTask task = downloadTasks.get(modelId);
        if (task != null) {
            task.cancel();
            downloadTasks.remove(modelId);
            downloadProgress.remove(modelId);
            if (globalCallback != null) {
                globalCallback.onCancelled(modelId);
            }
        }
    }

    public void cancelAll() {
        for (String taskId : downloadTasks.keySet()) {
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

    public boolean isModelDownloaded(String modelPath) {
        File file = new File(modelPath);
        return file.exists() && file.length() > 0;
    }

    public int getActiveDownloadCount() {
        return activeDownloads.get();
    }

    public int getQueuedDownloadCount() {
        return downloadTasks.size() - activeDownloads.get();
    }

    public void cleanup() {
        executor.shutdown();
    }

    private class DownloadTask implements Runnable {
        private final String taskId;
        private final DownloadRequest request;
        private final DownloadCallback callback;
        private volatile boolean isPaused = false;
        private volatile boolean isCancelled = false;

        DownloadTask(String taskId, DownloadRequest request, DownloadCallback callback) {
            this.taskId = taskId;
            this.request = request;
            this.callback = callback;
        }

        void pause() {
            isPaused = true;
        }

        void cancel() {
            isCancelled = true;
        }

        @Override
        public void run() {
            DownloadProgress progress = new DownloadProgress(taskId);
            progress.totalBytes = request.expectedSize;
            progress.state = DownloadState.CONNECTING;
            downloadProgress.put(taskId, progress);

            Log.i(TAG, "Starting download: " + taskId + " from " + request.modelUrl);

            int attempt = 0;
            Exception lastError = null;

            while (attempt < MAX_RETRY_ATTEMPTS) {
                try {
                    String result = downloadFile(request, progress);

                    if (result != null) {
                        if (request.checksum != null) {
                            if (!verifyChecksum(result, request.checksum)) {
                                new File(result).delete();
                                throw new IOException("Checksum verification failed");
                            }
                        }

                        progress.state = DownloadState.COMPLETED;
                        Log.i(TAG, "Download completed: " + taskId);

                        if (callback != null) {
                            callback.onComplete(taskId, result);
                        }
                        if (globalCallback != null) {
                            globalCallback.onComplete(taskId, result);
                        }
                        return;
                    } else {
                        throw new IOException("Download returned null");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    lastError = e;
                    attempt++;
                    Log.w(TAG, "Download attempt " + attempt + " failed: " + taskId, e);

                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        try {
                            Thread.sleep(1000L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            progress.state = DownloadState.FAILED;
            progress.errorMessage = lastError != null ? lastError.getMessage() : "Download failed";

            if (callback != null) {
                callback.onError(taskId, progress.errorMessage);
            }
            if (globalCallback != null) {
                globalCallback.onError(taskId, progress.errorMessage);
            }
        }

        private String downloadFile(DownloadRequest request, DownloadProgress progress) throws Exception {
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            File outputFile = null;

            try {
                URL url = new URL(request.modelUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept-Encoding", "identity");

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP error: " + responseCode);
                }

                long totalBytes = request.expectedSize > 0 ? request.expectedSize : connection.getContentLengthLong();
                progress.totalBytes = totalBytes;
                progress.state = DownloadState.DOWNLOADING;

                outputFile = new File(request.modelPath);
                outputFile.getParentFile().mkdirs();

                inputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                outputStream = new FileOutputStream(outputFile);

                byte[] buffer = new byte[BUFFER_SIZE];
                long totalBytesRead = 0;
                long lastUpdateTime = System.currentTimeMillis();
                long bytesSinceLastUpdate = 0;

                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (isCancelled) {
                        outputFile.delete();
                        throw new InterruptedException("Download cancelled");
                    }

                    while (isPaused) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            throw e;
                        }
                    }

                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    bytesSinceLastUpdate += bytesRead;

                    progress.downloadedBytes = totalBytesRead;

                    long currentTime = System.currentTimeMillis();
                    long elapsedSinceLastUpdate = currentTime - lastUpdateTime;

                    if (elapsedSinceLastUpdate >= 500) {
                        long speed = (bytesSinceLastUpdate * 1000L) / elapsedSinceLastUpdate;
                        progress.speedBps = speed;
                        progress.lastUpdateTime = currentTime;

                        bytesSinceLastUpdate = 0;
                        lastUpdateTime = currentTime;

                        long downloadedMB = totalBytesRead / (1024 * 1024);
                        long totalMB = totalBytes > 0 ? totalBytes / (1024 * 1024) : 0;

                        if (callback != null) {
                            callback.onProgress(taskId, progress.getProgressPercent(), downloadedMB, totalMB);
                        }
                    }
                }

                outputStream.flush();
                return outputFile.getAbsolutePath();

            } catch (Exception e) {
                if (outputFile != null) {
                    outputFile.delete();
                }
                throw e;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                    }
                }
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                    }
                }
                activeDownloads.decrementAndGet();
                downloadTasks.remove(taskId);
            }
        }

        private boolean verifyChecksum(String filePath, String expectedChecksum) {
            try {
                File file = new File(filePath);
                String checksum = calculateSHA256(file);
                boolean isValid = checksum.equalsIgnoreCase(expectedChecksum);
                if (!isValid) {
                    Log.w(TAG, "Checksum mismatch: expected=" + expectedChecksum + ", actual=" + checksum);
                }
                return isValid;
            } catch (Exception e) {
                Log.e(TAG, "Failed to verify checksum", e);
                return false;
            }
        }

        private String calculateSHA256(File file) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            try {
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            } finally {
                fis.close();
            }

            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        }
    }

    public interface DownloadCallback {
        void onProgress(String modelId, int progress, long downloadedMB, long totalMB);
        void onComplete(String modelId, String filePath);
        void onError(String modelId, String error);
        void onPaused(String modelId);
        void onCancelled(String modelId);
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

    public static class DownloadRequest {
        public final String modelId;
        public final String modelUrl;
        public final String modelPath;
        public final long expectedSize;
        public final String checksum;
        public final DownloadPriority priority;

        public DownloadRequest(String modelId, String modelUrl, String modelPath, long expectedSize, String checksum) {
            this(modelId, modelUrl, modelPath, expectedSize, checksum, DownloadPriority.NORMAL);
        }

        public DownloadRequest(String modelId, String modelUrl, String modelPath, long expectedSize, String checksum, DownloadPriority priority) {
            this.modelId = modelId;
            this.modelUrl = modelUrl;
            this.modelPath = modelPath;
            this.expectedSize = expectedSize;
            this.checksum = checksum;
            this.priority = priority;
        }
    }

    public enum DownloadPriority {
        LOW, NORMAL, HIGH
    }

    public static class ModelDownloadConfig {
        public boolean allowBackgroundDownload = true;
        public long downloadTimeoutSeconds = 3600;
        public int retryAttempts = 3;
        public boolean verifyChecksum = true;
        public int maxConcurrentDownloads = 2;

        public static ModelDownloadConfig DEFAULT = new ModelDownloadConfig();
    }
}