package com.oilquiz.app.ai.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * 屏幕共享前台服务（Android 14 / targetSdk 34 强制要求）：
 * 使用 MediaProjection 前必须运行一个 foregroundServiceType="mediaProjection" 的前台服务，
 * 否则系统在 getMediaProjection 时抛 SecurityException 拒绝。
 *
 * 生命周期：
 * - {@link #start(Context, Intent)}：授权成功后启动服务并创建投影（同进程内后续截屏复用，不再弹窗）
 * - {@link #getProjection()}：ScreenCaptureTool 截屏时取投影
 * - {@link #stop(Context)}：停止投影并退出前台（下次截屏需重新授权）
 * - 进程被杀时服务自动停止，投影随之释放（下次截屏重新授权）
 */
public class MediaProjectionService extends Service {

    private static final String TAG = "MediaProjectionService";
    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 0x5353;

    private static volatile MediaProjection sProjection;

    /** 当前是否有可用的投影（服务运行中） */
    public static boolean isActive() {
        return sProjection != null;
    }

    /** 取当前投影（供截屏使用；可能为 null 表示未授权/已停止） */
    @Nullable
    public static MediaProjection getProjection() {
        return sProjection;
    }

    /** 授权成功后启动前台服务并创建投影 */
    public static void start(Context context, Intent projectionData) {
        try {
            Intent intent = new Intent(context, MediaProjectionService.class);
            intent.putExtra("projection_data", projectionData);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "无法启动屏幕共享前台服务: " + t.getMessage());
        }
    }

    /** 停止投影并退出前台（下次截屏需重新授权） */
    public static void stop(Context context) {
        MediaProjection p = sProjection;
        sProjection = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
        try {
            context.stopService(new Intent(context, MediaProjectionService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        Intent data = intent != null ? intent.getParcelableExtra("projection_data") : null;
        if (data != null) {
            try {
                MediaProjectionManager mpm = (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mpm != null) {
                    sProjection = mpm.getMediaProjection(android.app.Activity.RESULT_OK, data);
                    // Android 14+ 强制：创建 VirtualDisplay 前必须先注册 MediaProjection 回调，
                    // 否则 createVirtualDisplay 抛 IllegalStateException（截屏会反复失败→反复重授权）
                    if (sProjection != null) {
                        sProjection.registerCallback(new MediaProjection.Callback() {
                            @Override
                            public void onStop() {
                                Log.i(TAG, "投影被系统停止（切后台/用户撤销），下次截屏需重新授权");
                                sProjection = null;
                                // 释放常驻截屏通道（VD 绑定已失效投影）
                                try {
                                    ScreenCaptureTool.releasePersistentCapture();
                                } catch (Throwable ignored) {
                                }
                            }
                        }, new android.os.Handler(android.os.Looper.getMainLooper()));
                    }
                    Log.i(TAG, "屏幕共享已授权，投影就绪（同进程内截屏复用，不再弹窗）");
                }
            } catch (Throwable t) {
                Log.e(TAG, "创建投影失败: " + t.getMessage(), t);
                stopSelf();
            }
        }
        // 投影创建失败（无有效授权数据）时自动退出
        if (sProjection == null && (intent == null || data == null)) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        MediaProjection p = sProjection;
        sProjection = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "屏幕共享",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Agent 截屏时使用的屏幕共享前台服务");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("答题宝正在屏幕共享")
                .setContentText("Agent 截屏中，可随时在对话中说「停止截屏」结束")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }
}
