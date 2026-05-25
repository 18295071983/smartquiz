package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.oilquiz.app.util.AILogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ModelTransferManager {
    private static final String TAG = "ModelTransferManager";
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int DEFAULT_PORT = 8888;
    private static final int SOCKET_TIMEOUT = 5000;

    private static volatile ModelTransferManager INSTANCE;
    private final Context context;
    private final ExecutorService executor;

    private final ConcurrentHashMap<String, TransferProgress> transferProgress = new ConcurrentHashMap<>();
    private TransferCallback globalCallback;

    private ServerSocket serverSocket;
    private AtomicBoolean isServerRunning = new AtomicBoolean(false);
    private int serverPort = DEFAULT_PORT;

    public enum TransferMode {
        WIFI_DIRECT,
        HOTSPOT,
        BLUETOOTH,
        LOCAL_COPY,
        SERVER_CLIENT
    }

    public enum TransferDirection {
        SEND,
        RECEIVE
    }

    public enum TransferState {
        IDLE,
        CONNECTING,
        TRANSFERRING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private ModelTransferManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newCachedThreadPool();
    }

    public static ModelTransferManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelTransferManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelTransferManager(context);
                }
            }
        }
        return INSTANCE;
    }

    public void setGlobalCallback(TransferCallback callback) {
        this.globalCallback = callback;
    }

    public String shareModelViaFileProvider(String modelPath, String title) {
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            AILogger.e(TAG, "Model file not found: " + modelPath);
            return null;
        }

        try {
            String authority = context.getPackageName() + ".fileprovider";
            Uri uri;
            try {
                uri = FileProvider.getUriForFile(context, authority, modelFile);
            } catch (Exception e) {
                AILogger.e(TAG, "FileProvider failed, using alternative method", e);
                return shareModelViaCopyToDownload(modelFile, title);
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/octet-stream");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title != null ? title : "AI Model");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "分享AI模型文件: " + modelFile.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(shareIntent, "分享模型");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);

            AILogger.i(TAG, "Model shared via FileProvider: " + modelFile.getName());
            return modelFile.getName();
        } catch (Exception e) {
            AILogger.e(TAG, "Error sharing model via FileProvider", e);
            return shareModelViaCopyToDownload(new File(modelPath), title);
        }
    }

    private String shareModelViaCopyToDownload(File modelFile, String title) {
        try {
            File downloadsDir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            } else {
                downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            }

            if (downloadsDir == null) {
                downloadsDir = context.getFilesDir();
            }

            File destFile = new File(downloadsDir, modelFile.getName());
            if (destFile.exists()) {
                destFile.delete();
            }

            copyFile(modelFile, destFile, null);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/octet-stream");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(destFile));
            } else {
                String authority = context.getPackageName() + ".fileprovider";
                try {
                    Uri uri = FileProvider.getUriForFile(context, authority, destFile);
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    AILogger.w(TAG, "FileProvider not available, using URI without permissions");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(destFile));
                }
            }
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title != null ? title : "AI Model");
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(shareIntent, "分享模型");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);

            AILogger.i(TAG, "Model shared via downloads: " + destFile.getAbsolutePath());
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            AILogger.e(TAG, "Error sharing model via downloads", e);
            return null;
        }
    }

    public String copyModelToExternalStorage(String modelPath, TransferCallback callback) {
        String taskId = "copy_" + System.currentTimeMillis();
        File sourceFile = new File(modelPath);

        if (!sourceFile.exists()) {
            if (callback != null) {
                callback.onError(taskId, "源文件不存在: " + modelPath);
            }
            return null;
        }

        File externalDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        } else {
            externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        }

        if (externalDir == null) {
            externalDir = context.getFilesDir();
        }

        File destFile = new File(externalDir, sourceFile.getName());
        TransferProgress progress = new TransferProgress(taskId, TransferDirection.SEND);
        progress.totalBytes = sourceFile.length();
        progress.state = TransferState.TRANSFERRING;
        transferProgress.put(taskId, progress);

        executor.execute(() -> {
            try {
                boolean success = copyFile(sourceFile, destFile, new TransferProgressListener() {
                    @Override
                    public void onProgress(long transferred, long total) {
                        progress.transferredBytes = transferred;
                        if (callback != null) {
                            callback.onProgress(taskId, progress.getProgressPercent(), 
                                transferred / (1024 * 1024), total / (1024 * 1024));
                        }
                        if (globalCallback != null) {
                            globalCallback.onProgress(taskId, progress.getProgressPercent(),
                                transferred / (1024 * 1024), total / (1024 * 1024));
                        }
                    }
                });

                if (success) {
                    progress.state = TransferState.COMPLETED;
                    AILogger.i(TAG, "Model copied to: " + destFile.getAbsolutePath());
                    if (callback != null) {
                        callback.onComplete(taskId, destFile.getAbsolutePath());
                    }
                    if (globalCallback != null) {
                        globalCallback.onComplete(taskId, destFile.getAbsolutePath());
                    }
                } else {
                    progress.state = TransferState.FAILED;
                    String error = "文件复制失败";
                    if (callback != null) {
                        callback.onError(taskId, error);
                    }
                    if (globalCallback != null) {
                        globalCallback.onError(taskId, error);
                    }
                }
            } catch (Exception e) {
                progress.state = TransferState.FAILED;
                progress.errorMessage = e.getMessage();
                AILogger.e(TAG, "Error copying model", e);
                if (callback != null) {
                    callback.onError(taskId, e.getMessage());
                }
                if (globalCallback != null) {
                    globalCallback.onError(taskId, e.getMessage());
                }
            }
        });

        return taskId;
    }

    public String exportModelForBackup(String modelName, TransferCallback callback) {
        ModelManager modelManager = new ModelManager(context);
        String modelPath = modelManager.getModelPath(modelName);
        if (modelPath == null) {
            if (callback != null) {
                callback.onError(null, "未找到模型: " + modelName);
            }
            return null;
        }
        return copyModelToExternalStorage(modelPath, callback);
    }

    public boolean startServer(int port) {
        if (isServerRunning.get()) {
            AILogger.w(TAG, "Server is already running");
            return false;
        }

        this.serverPort = port;
        isServerRunning.set(true);

        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(serverPort);
                serverSocket.setSoTimeout(0);
                AILogger.i(TAG, "Model transfer server started on port " + serverPort);

                while (isServerRunning.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        AILogger.i(TAG, "Client connected: " + clientSocket.getInetAddress());
                        handleClientConnection(clientSocket);
                    } catch (IOException e) {
                        if (isServerRunning.get()) {
                            AILogger.e(TAG, "Error accepting client connection", e);
                        }
                    }
                }
            } catch (IOException e) {
                AILogger.e(TAG, "Error starting server", e);
                isServerRunning.set(false);
            }
        });

        return true;
    }

    public void stopServer() {
        isServerRunning.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                AILogger.e(TAG, "Error closing server socket", e);
            }
        }
    }

    public boolean isServerRunning() {
        return isServerRunning.get();
    }

    public int getServerPort() {
        return serverPort;
    }

    private void handleClientConnection(Socket clientSocket) {
        executor.execute(() -> {
            try {
                clientSocket.setSoTimeout(SOCKET_TIMEOUT);
                InputStream in = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream();

                byte[] headerBuf = new byte[8];
                int read = in.read(headerBuf);
                if (read < 8) {
                    throw new IOException("Invalid header");
                }

                long fileSize = bytesToLong(headerBuf);
                AILogger.i(TAG, "Receiving file, size: " + fileSize + " bytes");

                File modelDir = new File(context.getFilesDir(), "ai_models");
                if (!modelDir.exists()) {
                    modelDir.mkdirs();
                }

                String receivedFileName = "received_model_" + System.currentTimeMillis() + ".gguf";
                File tempFile = new File(modelDir, receivedFileName + ".tmp");
                File finalFile = new File(modelDir, receivedFileName);

                String taskId = "receive_" + System.currentTimeMillis();
                TransferProgress progress = new TransferProgress(taskId, TransferDirection.RECEIVE);
                progress.totalBytes = fileSize;
                progress.state = TransferState.TRANSFERRING;
                transferProgress.put(taskId, progress);

                AtomicLong transferred = new AtomicLong(0);

                try (BufferedInputStream bis = new BufferedInputStream(in);
                     BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tempFile))) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    long lastUpdate = System.currentTimeMillis();

                    while ((bytesRead = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, bytesRead);
                        long currentTransferred = transferred.addAndGet(bytesRead);
                        progress.transferredBytes = currentTransferred;

                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 500) {
                            lastUpdate = now;
                            if (globalCallback != null) {
                                globalCallback.onProgress(taskId, progress.getProgressPercent(),
                                    currentTransferred / (1024 * 1024), fileSize / (1024 * 1024));
                            }
                        }
                    }
                    bos.flush();
                }

                tempFile.renameTo(finalFile);
                progress.state = TransferState.COMPLETED;

                AILogger.i(TAG, "File received successfully: " + finalFile.getAbsolutePath());

                if (globalCallback != null) {
                    globalCallback.onComplete(taskId, finalFile.getAbsolutePath());
                }

                out.write(1);
                out.flush();

            } catch (Exception e) {
                AILogger.e(TAG, "Error handling client connection", e);
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    AILogger.e(TAG, "Error closing client socket", e);
                }
            }
        });
    }

    public String sendModelToServer(String modelPath, String host, int port, TransferCallback callback) {
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            if (callback != null) {
                callback.onError(null, "模型文件不存在: " + modelPath);
            }
            return null;
        }

        String taskId = "send_" + System.currentTimeMillis();
        TransferProgress progress = new TransferProgress(taskId, TransferDirection.SEND);
        progress.totalBytes = modelFile.length();
        progress.state = TransferState.CONNECTING;
        transferProgress.put(taskId, progress);

        executor.execute(() -> {
            Socket socket = null;
            try {
                AILogger.i(TAG, "Connecting to " + host + ":" + port);
                socket = new Socket(host, port);
                socket.setSoTimeout(30000);

                progress.state = TransferState.TRANSFERRING;

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                out.write(longToBytes(modelFile.length()));
                out.flush();

                AtomicLong transferred = new AtomicLong(0);
                long lastUpdate = System.currentTimeMillis();

                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(modelFile));
                     BufferedOutputStream bos = new BufferedOutputStream(out)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;

                    while ((bytesRead = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, bytesRead);
                        long currentTransferred = transferred.addAndGet(bytesRead);
                        progress.transferredBytes = currentTransferred;

                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 500) {
                            lastUpdate = now;
                            if (callback != null) {
                                callback.onProgress(taskId, progress.getProgressPercent(),
                                    currentTransferred / (1024 * 1024), progress.totalBytes / (1024 * 1024));
                            }
                            if (globalCallback != null) {
                                globalCallback.onProgress(taskId, progress.getProgressPercent(),
                                    currentTransferred / (1024 * 1024), progress.totalBytes / (1024 * 1024));
                            }
                        }
                    }
                    bos.flush();
                }

                int ack = in.read();
                if (ack == 1) {
                    progress.state = TransferState.COMPLETED;
                    AILogger.i(TAG, "Model sent successfully");
                    if (callback != null) {
                        callback.onComplete(taskId, modelPath);
                    }
                    if (globalCallback != null) {
                        globalCallback.onComplete(taskId, modelPath);
                    }
                } else {
                    progress.state = TransferState.FAILED;
                    String error = "接收方未确认";
                    if (callback != null) {
                        callback.onError(taskId, error);
                    }
                    if (globalCallback != null) {
                        globalCallback.onError(taskId, error);
                    }
                }

            } catch (Exception e) {
                progress.state = TransferState.FAILED;
                progress.errorMessage = e.getMessage();
                AILogger.e(TAG, "Error sending model", e);
                if (callback != null) {
                    callback.onError(taskId, e.getMessage());
                }
                if (globalCallback != null) {
                    globalCallback.onError(taskId, e.getMessage());
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        AILogger.e(TAG, "Error closing socket", e);
                    }
                }
            }
        });

        return taskId;
    }

    public String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && !sAddr.isEmpty()) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            AILogger.e(TAG, "Error getting local IP", e);
        }
        return "127.0.0.1";
    }

    private boolean copyFile(File source, File dest, TransferProgressListener listener) throws IOException {
        if (source == null || !source.exists()) {
            return false;
        }

        File parentDir = dest.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        long totalBytes = source.length();
        long transferred = 0;
        long lastUpdate = System.currentTimeMillis();

        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                transferred += bytesRead;

                if (listener != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 500) {
                        lastUpdate = now;
                        listener.onProgress(transferred, totalBytes);
                    }
                }
            }
            fos.flush();

            if (listener != null) {
                listener.onProgress(totalBytes, totalBytes);
            }

            return true;
        }
    }

    private long bytesToLong(byte[] bytes) {
        return ((long) bytes[0] << 56) |
               ((long) (bytes[1] & 0xFF) << 48) |
               ((long) (bytes[2] & 0xFF) << 40) |
               ((long) (bytes[3] & 0xFF) << 32) |
               ((long) (bytes[4] & 0xFF) << 24) |
               ((long) (bytes[5] & 0xFF) << 16) |
               ((long) (bytes[6] & 0xFF) << 8) |
               ((long) bytes[7] & 0xFF);
    }

    private byte[] longToBytes(long value) {
        return new byte[]{
            (byte) (value >> 56),
            (byte) (value >> 48),
            (byte) (value >> 40),
            (byte) (value >> 32),
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }

    public TransferProgress getProgress(String taskId) {
        return transferProgress.get(taskId);
    }

    public void cancelTransfer(String taskId) {
        TransferProgress progress = transferProgress.get(taskId);
        if (progress != null) {
            progress.state = TransferState.CANCELLED;
        }
        if (globalCallback != null) {
            globalCallback.onCancelled(taskId);
        }
    }

    public void cleanup() {
        stopServer();
        executor.shutdown();
    }

    public interface TransferCallback {
        void onProgress(String taskId, int progress, long transferredMB, long totalMB);
        void onComplete(String taskId, String filePath);
        void onError(String taskId, String error);
        void onCancelled(String taskId);
    }

    private interface TransferProgressListener {
        void onProgress(long transferred, long total);
    }

    public static class TransferProgress {
        public final String taskId;
        public final TransferDirection direction;
        public long totalBytes;
        public long transferredBytes;
        public TransferState state;
        public String errorMessage;
        public long lastUpdateTime;

        public TransferProgress(String taskId, TransferDirection direction) {
            this.taskId = taskId;
            this.direction = direction;
            this.state = TransferState.IDLE;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public int getProgressPercent() {
            return totalBytes > 0 ? (int) ((transferredBytes * 100) / totalBytes) : 0;
        }
    }
}
