package com.oilquiz.app.ai.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 远程同步前台服务：让"电脑端 → 手机"的消息在 App 切后台/锁屏时也能继续收。
 *
 * <p>职责很窄：常驻订阅插件的事件流（断线自动重连，带 {@code last_seq} 游标），
 * 把 seq 落盘、把最新一条 assistant 文本刷新到通知上。渲染仍在聊天页完成——
 * 回到页面时 Activity 会用同一个游标补拉，两边靠 seq 去重，不会重复。</p>
 */
public class RemoteSyncService extends Service {

    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_SINCE = "since";
    private static final String CHANNEL_ID = "remote_dsh_sync";
    private static final int NOTIF_ID = 0x0D5A;

    /** 最近一条 assistant 文本（通知与"回页面补拉"都用它兜底） */
    public static volatile String lastText = "";

    private volatile boolean running = false;
    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String target = intent != null ? intent.getStringExtra(EXTRA_TARGET) : null;
        int since = intent != null ? intent.getIntExtra(EXTRA_SINCE, -1) : -1;
        if (target == null || target.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification("正在连接电脑…"));
        if (!running) {
            running = true;
            worker = new Thread(() -> loop(target, since), "remote-dsh-sync");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop(String target, int since) {
        final java.util.concurrent.atomic.AtomicInteger seqBox =
                new java.util.concurrent.atomic.AtomicInteger(Math.max(0, since));
        while (running) {
            final int start = seqBox.get();
            try {
                RemoteDshTool.subscribeEvents(this, target, start, 300, true,
                        new RemoteDshTool.DshEventListener() {
                            @Override public void onReady(int s) { /* 已连接 */ }

                            @Override public void onSnapshot(int s, JSONArray turns) {
                                if (s > seqBox.get()) {
                                    seqBox.set(s);
                                    RemoteDshTool.setLastSeq(RemoteSyncService.this, target, s);
                                }
                            }

                            @Override public void onEvent(JSONObject ev) {
                                int s = ev.optInt("seq", -1);
                                if (s > seqBox.get()) {
                                    seqBox.set(s);
                                    RemoteDshTool.setLastSeq(RemoteSyncService.this, target, s);
                                }
                                if (!"assistant/message".equals(ev.optString("type"))) return;
                                String text = extractText(ev);
                                if (text.isEmpty()) return;
                                lastText = text;
                                notifyText(snippet(text));
                            }

                            @Override public void onEnd() { /* 由循环重连 */ }

                            @Override public void onError(String message) { /* 下一轮重试 */ }
                        });
            } catch (Exception ignored) {
                // 网络抖动：等一会重连
            }
            if (!running) break;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                break;
            }
        }
        stopForeground(true);
        stopSelf();
    }

    /** 从 assistant/message 事件里取正文（跳过 reasoning part） */
    private static String extractText(JSONObject ev) {
        try {
            JSONObject data = ev.optJSONObject("data");
            JSONObject msg = data == null ? null : data.optJSONObject("message");
            JSONArray parts = msg == null ? null : msg.optJSONArray("content");
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i += 1) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                if ("reasoning".equals(p.optString("type")) || "thinking".equals(p.optString("type"))) continue;
                sb.append(p.optString("text"));
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String snippet(String text) {
        String t = text.replace('\n', ' ').trim();
        return t.length() > 60 ? t.substring(t.length() - 60) : t;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "电脑同步", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("电脑端对话同步到手机");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, com.oilquiz.app.ui.activity.AIChatActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("电脑端 · 同步中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    private void notifyText(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {
            // 通知失败不影响同步
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
