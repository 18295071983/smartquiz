package com.oilquiz.app.manager;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class AutoBackupManager {

    private static final String PREF_NAME = "auto_backup_preferences";
    private static final String KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled";
    private static final String KEY_BACKUP_INTERVAL = "backup_interval";
    private static final String WORK_NAME = "auto_backup_work";

    public static final long DEFAULT_INTERVAL_HOURS = 24;

    private SharedPreferences preferences;
    private WorkManager workManager;

    public AutoBackupManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        workManager = WorkManager.getInstance(context);
    }

    public boolean isAutoBackupEnabled() {
        return preferences.getBoolean(KEY_AUTO_BACKUP_ENABLED, false);
    }

    public void setAutoBackupEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled);
        editor.apply();

        if (enabled) {
            scheduleAutoBackup(context);
        } else {
            cancelAutoBackup();
        }
    }

    public long getBackupInterval() {
        return preferences.getLong(KEY_BACKUP_INTERVAL, DEFAULT_INTERVAL_HOURS);
    }

    public void setBackupInterval(Context context, long hours) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(KEY_BACKUP_INTERVAL, hours);
        editor.apply();

        if (isAutoBackupEnabled()) {
            scheduleAutoBackup(context);
        }
    }

    public void scheduleAutoBackup(Context context) {
        long intervalHours = getBackupInterval();

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build();

        PeriodicWorkRequest backupRequest = new PeriodicWorkRequest.Builder(BackupWorker.class, intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                backupRequest
        );
    }

    public void cancelAutoBackup() {
        workManager.cancelUniqueWork(WORK_NAME);
    }
}
