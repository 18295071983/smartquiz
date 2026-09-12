package com.cjhtmldemo.apk;

/*
 * ForegroundBridgeService —— 壳前台服务（v7）。
 *
 * HTML 通过 AndroidApp.startForeground(title, text) / stopForeground() 控制：
 * - startForegroundService 后立即 startForeground(通知)，满足 Android 8.0+ 5 秒限制
 * - 通知渠道与 MainActivity 共用 "shell_notify"
 * - update() 供 MainActivity 静态更新文案（可选）
 */
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicBoolean;

public class ForegroundBridgeService extends Service {

    private static final String CHANNEL = "shell_notify";
    private static final int NOTIFY_ID = 1999;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    public static boolean isRunning() {
        return running.get();
    }

    /** 更新前台服务通知文案（可被 MainActivity 调用） */
    public static void update(String title, String text) {
        // 通知内容由 onStartCommand 维护；此处仅记录（简单场景无需跨进程刷新）
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running.set(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = "前台服务运行中";
        String text = "";
        if (intent != null) {
            if (intent.getStringExtra("title") != null) title = intent.getStringExtra("title");
            if (intent.getStringExtra("text") != null) text = intent.getStringExtra("text");
        }
        createChannel();
        startForeground(NOTIFY_ID, buildNotification(title, text));
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "壳应用通知", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("HTML 应用通知 / 前台服务常驻");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        running.set(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
