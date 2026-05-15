package com.oilquiz.app.viewmodel;

import android.content.Context;
import android.os.AsyncTask;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.manager.BackupManager;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class BackupViewModel extends ViewModel {

    private BackupManager backupManager;

    public BackupViewModel() {
        backupManager = new BackupManager();
    }

    // 备份回调接口
    public interface BackupCallback {
        void onSuccess(File backupFile);
        void onFailure(String error);
        default void onProgress(int progress) {} // 默认空实现，可选重写
    }

    // 恢复回调接口
    public interface RestoreCallback {
        void onSuccess();
        void onFailure(String error);
        default void onProgress(int progress) {} // 默认空实现，可选重写
    }

    // 获取备份文件列表回调接口
    public interface GetBackupFilesCallback {
        void onSuccess(List<File> backupFiles);
        void onFailure(String error);
    }

    // 删除备份文件回调接口
    public interface DeleteBackupFileCallback {
        void onSuccess();
        void onFailure(String error);
    }

    // 清理旧备份回调接口
    public interface CleanupBackupsCallback {
        void onSuccess(int deletedCount);
        void onFailure(String error);
    }

    // 备份数据库
    public void backupDatabase(Context context, BackupCallback callback) {
        new AsyncTask<Void, Integer, File>() {
            private String error;

            @Override
            protected File doInBackground(Void... voids) {
                try {
                    // 发布开始进度
                    publishProgress(0);
                    
                    // 执行备份
                    File backupFile = backupManager.backupDatabase(context);
                    
                    // 发布完成进度
                    publishProgress(100);
                    
                    return backupFile;
                } catch (IOException e) {
                    error = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                if (callback != null) {
                    callback.onProgress(values[0]);
                }
            }

            @Override
            protected void onPostExecute(File backupFile) {
                if (callback != null) {
                    if (backupFile != null) {
                        callback.onSuccess(backupFile);
                    } else {
                        callback.onFailure(error != null ? error : "备份失败");
                    }
                }
            }
        }.execute();
    }

    // 恢复数据库
    public void restoreDatabase(Context context, File backupFile, RestoreCallback callback) {
        new AsyncTask<Void, Integer, Boolean>() {
            private String error;

            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    // 发布开始进度
                    publishProgress(0);
                    
                    // 执行恢复
                    boolean success = backupManager.restoreDatabase(context, backupFile);
                    
                    // 发布完成进度
                    publishProgress(100);
                    
                    return success;
                } catch (IOException e) {
                    error = e.getMessage();
                    return false;
                }
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                if (callback != null) {
                    callback.onProgress(values[0]);
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess();
                    } else {
                        callback.onFailure(error != null ? error : "恢复失败");
                    }
                }
            }
        }.execute();
    }

    // 获取备份文件列表
    public void getBackupFiles(GetBackupFilesCallback callback) {
        new AsyncTask<Void, Void, List<File>>() {
            private String error;

            @Override
            protected List<File> doInBackground(Void... voids) {
                try {
                    return backupManager.getBackupFiles();
                } catch (Exception e) {
                    error = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(List<File> backupFiles) {
                if (callback != null) {
                    if (backupFiles != null) {
                        callback.onSuccess(backupFiles);
                    } else {
                        callback.onFailure(error != null ? error : "获取备份文件列表失败");
                    }
                }
            }
        }.execute();
    }

    // 删除备份文件
    public void deleteBackupFile(File backupFile, DeleteBackupFileCallback callback) {
        new AsyncTask<Void, Void, Boolean>() {
            private String error;

            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    return backupManager.deleteBackupFile(backupFile);
                } catch (Exception e) {
                    error = e.getMessage();
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (callback != null) {
                    if (success) {
                        callback.onSuccess();
                    } else {
                        callback.onFailure(error != null ? error : "删除备份文件失败");
                    }
                }
            }
        }.execute();
    }

    // 清理旧备份
    public void cleanupOldBackups(int keepCount, CleanupBackupsCallback callback) {
        new AsyncTask<Void, Void, Integer>() {
            private String error;

            @Override
            protected Integer doInBackground(Void... voids) {
                try {
                    List<File> beforeFiles = backupManager.getBackupFiles();
                    int beforeCount = beforeFiles.size();
                    
                    backupManager.cleanupOldBackups(keepCount);
                    
                    List<File> afterFiles = backupManager.getBackupFiles();
                    int afterCount = afterFiles.size();
                    
                    return beforeCount - afterCount;
                } catch (Exception e) {
                    error = e.getMessage();
                    return -1;
                }
            }

            @Override
            protected void onPostExecute(Integer deletedCount) {
                if (callback != null) {
                    if (deletedCount >= 0) {
                        callback.onSuccess(deletedCount);
                    } else {
                        callback.onFailure(error != null ? error : "清理旧备份失败");
                    }
                }
            }
        }.execute();
    }
}
