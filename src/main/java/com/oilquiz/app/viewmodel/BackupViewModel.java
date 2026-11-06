package com.oilquiz.app.viewmodel;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.manager.BackupManager;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 备份相关异步操作（2026-11-06：从已废弃的 AsyncTask 迁到 Executor + 主线程 Handler）。
 * 回调仍在主线程触发，调用方无需改。
 */
public class BackupViewModel extends ViewModel {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "backup-viewmodel");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final BackupManager backupManager;

    public BackupViewModel() {
        backupManager = new BackupManager();
    }

    public interface BackupCallback {
        void onSuccess(File backupFile);
        void onFailure(String error);
        default void onProgress(int progress) {}
    }

    public interface RestoreCallback {
        void onSuccess();
        void onFailure(String error);
        default void onProgress(int progress) {}
    }

    public interface GetBackupFilesCallback {
        void onSuccess(List<File> backupFiles);
        void onFailure(String error);
    }

    public interface DeleteBackupFileCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public interface CleanupBackupsCallback {
        void onSuccess(int deletedCount);
        void onFailure(String error);
    }

    public void backupDatabase(Context context, BackupCallback callback) {
        EXECUTOR.execute(() -> {
            String error = null;
            File backupFile = null;
            try {
                progress(callback, 0);
                backupFile = backupManager.backupDatabase(context);
                progress(callback, 100);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final File result = backupFile;
            final String message = error;
            MAIN.post(() -> {
                if (callback == null) return;
                if (result != null) callback.onSuccess(result);
                else callback.onFailure(message != null ? message : "备份失败");
            });
        });
    }

    public void restoreDatabase(Context context, File backupFile, RestoreCallback callback) {
        EXECUTOR.execute(() -> {
            String error = null;
            boolean success = false;
            try {
                progress(callback, 0);
                success = backupManager.restoreDatabase(context, backupFile);
                progress(callback, 100);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final boolean result = success;
            final String message = error;
            MAIN.post(() -> {
                if (callback == null) return;
                if (result) callback.onSuccess();
                else callback.onFailure(message != null ? message : "恢复失败");
            });
        });
    }

    public void getBackupFiles(GetBackupFilesCallback callback) {
        EXECUTOR.execute(() -> {
            List<File> files = null;
            String error = null;
            try {
                files = backupManager.getBackupFiles();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<File> result = files;
            final String message = error;
            MAIN.post(() -> {
                if (callback == null) return;
                if (result != null) callback.onSuccess(result);
                else callback.onFailure(message != null ? message : "获取备份文件列表失败");
            });
        });
    }

    public void deleteBackupFile(File backupFile, DeleteBackupFileCallback callback) {
        EXECUTOR.execute(() -> {
            boolean success = false;
            String error = null;
            try {
                success = backupManager.deleteBackupFile(backupFile);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final boolean result = success;
            final String message = error;
            MAIN.post(() -> {
                if (callback == null) return;
                if (result) callback.onSuccess();
                else callback.onFailure(message != null ? message : "删除备份文件失败");
            });
        });
    }

    public void cleanupOldBackups(int keepCount, CleanupBackupsCallback callback) {
        EXECUTOR.execute(() -> {
            int deleted = -1;
            String error = null;
            try {
                int before = backupManager.getBackupFiles().size();
                backupManager.cleanupOldBackups(keepCount);
                int after = backupManager.getBackupFiles().size();
                deleted = before - after;
            } catch (Exception e) {
                error = e.getMessage();
            }
            final int result = deleted;
            final String message = error;
            MAIN.post(() -> {
                if (callback == null) return;
                if (result >= 0) callback.onSuccess(result);
                else callback.onFailure(message != null ? message : "清理旧备份失败");
            });
        });
    }

    private static void progress(BackupCallback callback, int value) {
        if (callback != null) MAIN.post(() -> callback.onProgress(value));
    }

    private static void progress(RestoreCallback callback, int value) {
        if (callback != null) MAIN.post(() -> callback.onProgress(value));
    }
}
