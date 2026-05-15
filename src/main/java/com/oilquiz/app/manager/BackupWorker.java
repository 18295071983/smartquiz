package com.oilquiz.app.manager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.oilquiz.app.infra.Logging;

import java.io.File;
import java.io.IOException;
import com.google.common.util.concurrent.ListenableFuture;

public class BackupWorker extends Worker {

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Logging.i("开始自动备份数据库");
            BackupManager backupManager = new BackupManager();
            File backupFile = backupManager.backupDatabase(getApplicationContext());
            Logging.i("自动备份完成，备份文件路径：" + backupFile.getAbsolutePath());
            return Result.success();
        } catch (IOException e) {
            Logging.e("自动备份失败", e);
            return Result.failure();
        }
    }
}
