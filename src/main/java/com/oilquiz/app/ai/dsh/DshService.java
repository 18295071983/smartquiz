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
            // 不管是"刚启动"还是"已在运行"，只要不是失败，都确保模型切到本机代理
            if (msg != null && !msg.contains("失败")) {
                // 内置就绪 → 自动把 App 模型切到本机代理（否则还连着电脑地址会发不出消息）
                for (int i = 0; i < 20; i++) {
                    String r = com.oilquiz.app.ai.tool.LocalDshManager.ensureBuiltinModel(this);
                    if (r.startsWith("已切换") || r.startsWith("已创建")) {
                        Log.i(TAG, "model: " + r);
                        break;
                    }
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
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
