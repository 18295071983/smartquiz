package com.oilquiz.app.ai.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 前台同步服务（2026-11-12 重构）：**持有唯一连接**，不再自己轮询。
 *
 * <p>职责：把 {@link RemoteConnection} 的生命周期固定在前台服务上（切后台/锁屏不断），
 * 把状态/最新回复刷到通知；页面只订阅 {@link RemoteConnection}，不再各自开流。</p>
 *
 * <p>旧版这里自己 `while(running) subscribeEvents(...)`，和聊天页的 3 秒补拉、连接页的 5 秒探测
 * 互抢游标 —— 这就是"网络一抖就乱"的根源。现在传输游标（transport_seq）由连接管理器独占，
 * 页面渲染游标（last_seq）由页面自己推进，互不覆盖。</p>
 */
public class RemoteSyncService extends Service {

    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_SINCE = "since";
    private static final String CHANNEL_ID = "remote_dsh_sync";
    private static final int NOTIF_ID = 0x0D5A;

    /** 最近一条 assistant 文本（通知展示用） */
    public static volatile String lastText = "";

    private RemoteConnection.Listener listener;
    private String title = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String target = intent != null ? intent.getStringExtra(EXTRA_TARGET) : null;
        if (target != null && !target.isEmpty()) {
            // 调方给的 target 只是"提示"：立刻收敛到唯一身份（服务端仍会对齐一次）
            RemoteDshTool.setCanonicalSession(this, target, RemoteDshTool.getSyncTitle(this));
        }
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification("正在连接电脑…"));

        final RemoteConnection conn = RemoteConnection.get(this);
        if (listener == null) {
            listener = new RemoteConnection.Listener() {
                @Override
                public void onConnection(boolean online, String detail) {
                    update(buildNotification(online
                            ? "已连接" + (title.isEmpty() ? "" : " · " + title)
                            : "重连中" + (detail == null || detail.isEmpty() ? "" : " · " + detail)));
                }

                @Override
                public void onState(java.util.Map<String, Object> state) {
                    if (state == null) return;
                    Object t = state.get("title");
                    if (t != null && !String.valueOf(t).isEmpty()) title = String.valueOf(t);
                    update(buildNotification("已连接" + (title.isEmpty() ? "" : " · " + title)));
                }

                @Override
                public void onEvent(JSONObject event) {
                    if (event == null) return;
                    if (!"assistant/message".equals(event.optString("type"))) return;
                    String text = textOf(event.optJSONObject("data"));
                    if (text.isEmpty()) return;
                    lastText = text;
                    update(buildNotification(text.length() > 80 ? text.substring(0, 80) + "…" : text));
                }
            };
        }
        conn.addListener(listener);
        conn.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (listener != null) {
            RemoteConnection.get(this).removeListener(listener);
            listener = null;
        }
        RemoteConnection.get(this).stop();
        super.onDestroy();
    }

    private static String textOf(JSONObject data) {
        if (data == null) return "";
        JSONObject message = data.optJSONObject("message");
        JSONArray parts = message == null ? null : message.optJSONArray("content");
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i += 1) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null || "reasoning".equals(p.optString("type"))) continue;
            sb.append(p.optString("text"));
        }
        return sb.toString().trim();
    }

    private void update(Notification n) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (Throwable ignored) {
            // 通知失败不影响通道
        }
    }

    private void ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "远程同步",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("手机与电脑 DSH 的连接通道（后台保持）");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, com.oilquiz.app.ui.activity.AIChatActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setContentTitle("电脑端 · 远程连接")
                .setContentText(text == null ? "" : text)
                .setSmallIcon(com.oilquiz.app.R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }
}
