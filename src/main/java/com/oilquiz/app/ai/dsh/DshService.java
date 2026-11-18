package com.oilquiz.app.ai.dsh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * 内置 DSH 的前台服务（2026-11-19）。
 *
 * <p>为什么需要：DSH 跑在 App 内的 proot 子进程里，App 退到后台后系统会回收进程，
 * 表现为"token 打印完、过一会儿 7788 就连不上"。前台服务把 App 保活，DSH 才能常驻。</p>
 */
public class DshService extends Service {

    private static final String TAG = "DshService";
    private static final String CHANNEL = "dsh_runtime";
    private static final int NOTIFY_ID = 0x4453;

    public static void start(Context c) {
        Intent it = new Intent(c, DshService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
        else c.startService(it);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, DshService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("本机 DSH 运行中")
                .setContentText("DeepSeek Harness 内置运行时")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true);
        startForeground(NOTIFY_ID, b.build());
        new Thread(() -> {
            String msg = EmbeddedDsh.start(this);
            Log.i(TAG, "start: " + msg);
            // 2026-11-19：用户要求"只连插件，不要 App 自动建/切模型"。
            // 之前自动创建的「本机 DSH（内置）」模型在这里清掉，避免多出一条配置。
            com.oilquiz.app.ai.tool.LocalDshManager.removeBuiltinModels(this);
        }, "dsh-service-start").start();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "本机 DSH",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        EmbeddedDsh.stop(this);
        super.onDestroy();
    }
}
