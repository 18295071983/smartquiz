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
    /** 告警通知通道：IMPORTANCE_DEFAULT 才会响/弹（常驻的同步通知是 LOW，只安静显示） */
    private static final String ALERT_CHANNEL_ID = "remote_dsh_alert";
    private static final int NOTIF_ID = 0x0D5A;
    private static final int NOTIF_ID_APPROVAL = 0x0D5B;
    private static final int NOTIF_ID_JOB = 0x0D5C;

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
        startReverseCommandPoller();

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
                    String type = event.optString("type", "");

                    // ALERT(2026-11-13)：需要人处理的事单独弹一条通知。
                    // 前台那条常驻通知只显示"已连接/最后一句"，既不响也不弹；用户锁屏时
                    // 根本不知道电脑正在等他审批。审批/提问 → 提醒；审批被处理 → 收回。
                    if ("approval".equals(type) || "question".equals(type)) {
                        if (!com.oilquiz.app.ui.activity.AIChatActivity.isForegroundForAlerts()) {
                            notifyAlert(NOTIF_ID_APPROVAL,
                                    "approval".equals(type) ? "电脑端等待审批" : "电脑端有问题待回答",
                                    requestBrief(event));
                        }
                        return;
                    }
                    if ("approval_gone".equals(type) || "question_gone".equals(type)) {
                        cancelAlert(NOTIF_ID_APPROVAL);
                        return;
                    }
                    if ("job/settled".equals(type)) {
                        JSONObject d = event.optJSONObject("data");
                        boolean failed = d != null && (d.optBoolean("failed", false)
                                || "failed".equals(d.optString("status", "")));
                        if (failed && !com.oilquiz.app.ui.activity.AIChatActivity.isForegroundForAlerts()) {
                            notifyAlert(NOTIF_ID_JOB, "后台任务失败",
                                    d == null ? "" : d.optString("label", ""));
                        }
                        return;
                    }

                    if (!"assistant/message".equals(type)) return;
                    String text = textOf(event.optJSONObject("data"));
                    if (text.isEmpty()) return;
                    lastText = text;
                    update(buildNotification(text.length() > 80 ? text.substring(0, 80) + "…" : text));
                }
            };
        }
        conn.addListener(listener);
        conn.start();
        // 补一次当前状态：连接是单例，若服务在连接**已建立之后**才挂监听，
        // 那条 online 回调永远不会再来，通知就永远停在启动时的「正在连接电脑…」。
        try {
            if (RemoteDshTool.isConnected(this)) {
                update(buildNotification("已连接" + (title.isEmpty() ? "" : " · " + title)));
            }
        } catch (Throwable ignored) {
            // 状态读取失败保持原文案
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        cmdPolling = false;
        if (listener != null) {
            RemoteConnection.get(this).removeListener(listener);
            listener = null;
        }
        RemoteConnection.get(this).stop();
        super.onDestroy();
    }

    // ===== 反向指令轮询（电脑端 → 手机，2026-11-12 下沉到服务）=====
    //
    // 以前执行器在 AIChatActivity 里：App 切后台或页面销毁就收不到指令，
    // "人不在手机旁"这个场景等于不成立。这里在服务层再起一条，
    // 保证前台服务活着就能收。
    //
    // 与 Activity 侧执行器的分工：
    //   · 聊天页在前台时由 Activity 执行（完整 AIToolManager 管线，ui_component 能真弹卡）
    //   · 否则由本服务执行（toast / 通知；dialog 与 ui_component 退化为通知）
    // pollCommands 取走即标 delivering，两边不会重复执行同一条。
    private volatile boolean cmdPolling = false;
    private Thread cmdThread;

    private void startReverseCommandPoller() {
        if (cmdPolling) return;
        cmdPolling = true;
        cmdThread = new Thread(() -> {
            while (cmdPolling) {
                try {
                    JSONArray cmds = RemoteDshTool.pollCommands(this);
                    if (cmds != null) {
                        for (int i = 0; i < cmds.length(); i += 1) {
                            JSONObject c = cmds.optJSONObject(i);
                            if (c == null) continue;
                            String id = c.optString("id", "");
                            String tool = c.optString("tool", "");
                            JSONObject a = c.optJSONObject("args");
                            java.util.Map<String, Object> args = new java.util.HashMap<>();
                            if (a != null) {
                                java.util.Iterator<String> it = a.keys();
                                while (it.hasNext()) {
                                    String k = it.next();
                                    args.put(k, a.opt(k));
                                }
                            }
                            String result;
                            boolean ok = true;
                            try {
                                result = RemoteCommandExecutor.execute(this, tool, args, id);
                            } catch (Throwable t) {
                                ok = false;
                                result = String.valueOf(t);
                            }
                            try {
                                RemoteDshTool.ackCommand(this, id, ok, result);
                            } catch (Exception ignored) {
                                // 回执失败：10 分钟后自然过期
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 单轮失败不退出
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "dsh-cmd-svc");
        cmdThread.setDaemon(true);
        cmdThread.start();
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

    /**
     * 弹一条"需要人处理"的通知（审批 / 任务失败）。
     *
     * <p>与前台常驻通知分开：常驻那条是 IMPORTANCE_LOW（安静），这条是 DEFAULT（会响/弹）。
     * 没有通知权限时静默失败——连接本身不受影响。</p>
     */
    private void notifyAlert(int id, String title, String text) {
        try {
            ensureAlertChannel();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, ALERT_CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            Intent open = new Intent(this, com.oilquiz.app.ui.activity.AIChatActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setContentTitle(title)
                    .setContentText(text == null ? "" : text)
                    .setSmallIcon(com.oilquiz.app.R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(id, b.build());
        } catch (Throwable ignored) {
            // 没通知权限：静默失败，不影响通道
        }
    }

    /** 审批/提问已在电脑端被处理时收回通知（先点先赢，另一端不该还挂着）。 */
    private void cancelAlert(int id) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(id);
        } catch (Throwable ignored) {
            // 取消失败无害
        }
    }

    private void ensureAlertChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL_ID, "电脑端提醒",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("电脑端等待审批、后台任务失败等需要你处理的提醒");
        nm.createNotificationChannel(ch);
    }

    /**
     * 从审批/提问请求里取一句可读描述。
     *
     * <p>字段随 DSH 版本而变（toolName / title / description / questions[]…），
     * 全部按可选处理，取不到就空串——通知没有正文也比不弹好。</p>
     */
    private static String requestBrief(JSONObject event) {
        JSONObject req = event == null ? null : event.optJSONObject("request");
        if (req == null) return "";
        String[] keys = {"toolName", "title", "description", "question", "prompt", "reason", "summary"};
        for (String k : keys) {
            String v = req.optString(k, "").trim();
            if (!v.isEmpty()) return v.length() > 60 ? v.substring(0, 60) + "…" : v;
        }
        JSONArray qs = req.optJSONArray("questions");
        if (qs != null && qs.length() > 0) {
            JSONObject q = qs.optJSONObject(0);
            if (q != null) {
                String v = q.optString("question", q.optString("title", "")).trim();
                if (!v.isEmpty()) return v.length() > 60 ? v.substring(0, 60) + "…" : v;
            }
        }
        return "";
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
