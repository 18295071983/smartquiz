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
    /** 开机自启入口标记（见 RemoteBootReceiver）：只用于日志/诊断 */
    public static final String ACTION_BOOT_START = "com.oilquiz.app.REMOTE_BOOT_START";
    /** 页面自己处理了审批：让服务把常驻通知上的审批绑定撤掉，避免"通知 + 页内对话框"同时出现 */
    public static final String ACTION_CLEAR_APPROVAL = "com.oilquiz.app.REMOTE_CLEAR_APPROVAL";

    /** 页面处理审批时调用：撤掉连接通知上的审批绑定（不杀服务） */
    public static void clearApprovalBinding(Context context) {
        if (context == null) return;
        try {
            Intent it = new Intent(context, RemoteSyncService.class);
            it.setAction(ACTION_CLEAR_APPROVAL);
            androidx.core.content.ContextCompat.startForegroundService(context, it);
        } catch (Throwable ignored) {
            // 服务没起也无所谓：本来就没有绑定
        }
    }

    /**
     * 拉起常驻连接服务（幂等）。
     *
     * <p>给 Activity / Application / 开机广播共用：只要配对过就应该有这条连接，
     * 后台和锁屏的审批通知全靠它。以前启动条件里还要求"已连接"，调用时机又很早，
     * 结果服务从来没起来过（用户反馈"连接通知能看到，其他通知看不到"）。</p>
     */
    public static void ensureStarted(Context context) {
        if (context == null) return;
        try {
            // 插件总开关关掉时不再拉起常驻连接（2026-11-18；配对信息保留，打开开关后自动恢复）
            if (!RemoteDshTool.isPluginEnabled(context)) return;
            if (!RemoteDshTool.isConfigured(context)) return;
            Intent svc = new Intent(context, RemoteSyncService.class);
            svc.setAction(ACTION_BOOT_START);
            String target = RemoteDshTool.getSyncTarget(context);
            if (target != null && !target.isEmpty()) {
                svc.putExtra(EXTRA_TARGET, target);
                svc.putExtra(EXTRA_SINCE, RemoteDshTool.getLastSeq(context, target));
            }
            androidx.core.content.ContextCompat.startForegroundService(context, svc);
        } catch (Throwable ignored) {
            // 前台服务起不来不影响主流程
        }
    }
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
    /** 待审批绑定在常驻通知上（空 = 没有待审批） */
    private String pendingApprovalId = "";
    private String pendingApprovalText = "";

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
        if (intent != null && ACTION_CLEAR_APPROVAL.equals(intent.getAction())) {
            // 页内已经处理/展示审批：撤掉通知上的绑定，避免同一件事两处同时显示
            pendingApprovalId = "";
            pendingApprovalText = "";
            refreshConnectionNotification();
            return START_STICKY;
        }
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
                    if ("approval".equals(type)) {
                        // 两条通知分工（2026-11-13，修正"绑到保活通知"的错误）：
                        //   3418 = 前台服务保活通知：安静、不响不弹，只显示连接状态
                        //   3419 = 审批提醒：IMPORTANCE_HIGH，会弹 heads-up，带允许/拒绝/详情
                        // 之前把审批绑到 3418，等于让它继承"不响不弹"，所以用户完全看不到。
                        JSONObject req = event.optJSONObject("request");
                        String tool = req == null ? "" : req.optString("toolName",
                                req.optString("tool", req.optString("name", "")));
                        pendingApprovalId = event.optString("id", "");
                        pendingApprovalText = (tool == null || tool.isEmpty() ? "有一条审批待处理" : "请求权限：" + tool);
                        String brief = requestBrief(event);
                        if (brief != null && !brief.isEmpty() && !brief.contains(String.valueOf(tool))) {
                            pendingApprovalText += " · " + brief;
                        }
                        android.util.Log.i("DshSvc", "[approval] 发独立审批通知 id=" + pendingApprovalId
                                + " text=" + pendingApprovalText);
                        // 保活通知保持"连接状态"文案（不要挂审批：前台通知不响不弹）
                        refreshConnectionNotification();
                        notifyApproval(event.optString("id", ""), tool, brief);
                        return;
                    }
                    if ("question".equals(type)) {
                        if (!com.oilquiz.app.ui.activity.AIChatActivity.isForegroundForAlerts()) {
                            notifyAlert(NOTIF_ID_APPROVAL, "电脑端有问题待回答", requestBrief(event));
                        }
                        return;
                    }
                    if ("approval_gone".equals(type) || "question_gone".equals(type)) {
                        if ("approval_gone".equals(type)) {
                            pendingApprovalId = "";
                            pendingApprovalText = "";
                            android.util.Log.i("DshSvc", "[approval] 结束，恢复连接通知");
                            refreshConnectionNotification();
                        }
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
    /**
     * 审批通知归前台服务（2026-11-13）。
     *
     * <p>以前审批提醒只在 Activity 里发，退到后台就既没有通知也无法回传。现在由服务持有：
     * 通道事件一到就发（带「允许/拒绝」两颗按钮），点击走 {@link RemoteCmdActionReceiver}
     * 直接回传，不依赖页面存活。</p>
     */
    private void notifyApproval(String id, String tool, String detail) {
        if (id == null || id.isEmpty()) return;
        try {
            ensureAlertChannel();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ALERT_CHANNEL_ID)
                    : new Notification.Builder(this);
            Intent open = new Intent(this, com.oilquiz.app.ui.activity.AIChatActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, NOTIF_ID_APPROVAL, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text = (tool == null || tool.isEmpty() ? "有一条审批待处理" : "请求权限：" + tool)
                    + (detail == null || detail.isEmpty() ? "" : " · " + detail);
            b.setContentTitle("待你处理 · 电脑端请求审批")
                    .setContentText(text.length() > 120 ? text.substring(0, 120) + "…" : text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(com.oilquiz.app.R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    // 每条审批都要响/弹一次：以前 true 会把后续审批静音，等于没提醒
                    .setOnlyAlertOnce(false)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setDefaults(Notification.DEFAULT_ALL);
            b.addAction(new Notification.Action.Builder(null, "允许",
                    approvalAction(id, "allow", 1)).build());
            b.addAction(new Notification.Action.Builder(null, "拒绝",
                    approvalAction(id, "deny", 2)).build());
            b.addAction(new Notification.Action.Builder(null, "详情", pi).build());
            nm.notify(NOTIF_ID_APPROVAL, b.build());
            android.util.Log.i("DshSvc", "[approval] 审批提醒已发（high 渠道）");
        } catch (Throwable ignored) {
            // 没通知权限：静默，通道照常
        }
    }

    /** 审批按钮：广播到 RemoteCmdActionReceiver，后台也能点 */
    private PendingIntent approvalAction(String id, String decision, int req) {
        Intent it = new Intent(this, RemoteCmdActionReceiver.class);
        it.setAction(RemoteCmdActionReceiver.ACTION);
        it.putExtra(RemoteCmdActionReceiver.EXTRA_APPROVAL_ID, id);
        it.putExtra(RemoteCmdActionReceiver.EXTRA_APPROVAL_DECISION, decision);
        it.putExtra(RemoteCmdActionReceiver.EXTRA_NOTIF_ID, NOTIF_ID_APPROVAL);
        return PendingIntent.getBroadcast(this, NOTIF_ID_APPROVAL + req, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 独立审批通知 + 自定义视图（2026-11-13）：对齐 Qoder 那种"折叠状态就能看到按钮"。
     *
     * <p>为什么不用前台服务通知：实测 FGS 通知挂自定义视图会被 MIUI 序列化成 0 bytes，
     * 整条通知消失。普通通知（非 ongoing、独立 id）走自定义视图是正常的。</p>
     */
    private void notifyApprovalCard(String id, String text) {
        if (id == null || id.isEmpty()) return;
        try {
            ensureAlertChannel();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ALERT_CHANNEL_ID)
                    : new Notification.Builder(this);
            Intent open = new Intent(this, com.oilquiz.app.ui.activity.AIChatActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, NOTIF_ID_APPROVAL, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(com.oilquiz.app.R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setCategory(Notification.CATEGORY_MESSAGE);
            android.widget.RemoteViews rv = new android.widget.RemoteViews(
                    getPackageName(), com.oilquiz.app.R.layout.notification_approval);
            // 单行布局：标题里带权限名，按钮右对齐（折叠态高度受限，多行会被裁）
            rv.setTextViewText(com.oilquiz.app.R.id.approval_title,
                    text == null || text.isEmpty() ? "待你处理 · 电脑端请求审批"
                            : (text.indexOf("请求权限") >= 0 ? text : "请求权限：" + text));
            rv.setOnClickPendingIntent(com.oilquiz.app.R.id.approval_allow,
                    approvalAction(id, "allow", 1));
            rv.setOnClickPendingIntent(com.oilquiz.app.R.id.approval_deny,
                    approvalAction(id, "deny", 2));
            b.setCustomContentView(rv);
            b.setCustomBigContentView(rv);
            // 系统 action 作为锁屏/无障碍的兜底（MIUI 折叠态不显示它们，但展开还在）
            b.addAction(new Notification.Action.Builder(null, "允许",
                    approvalAction(id, "allow", 1)).build());
            b.addAction(new Notification.Action.Builder(null, "拒绝",
                    approvalAction(id, "deny", 2)).build());
            nm.notify(NOTIF_ID_APPROVAL, b.build());
            android.util.Log.i("DshSvc", "[approval] 自定义视图通知已发 id=" + id);
        } catch (Throwable t) {
            android.util.Log.w("DshSvc", "[approval] 自定义视图通知失败: " + t);
        }
    }

    private void notifyAlert(int id, String title, String text) {        try {
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
        NotificationChannel existing = nm.getNotificationChannel(ALERT_CHANNEL_ID);
        // 必须是 IMPORTANCE_HIGH：DEFAULT 不会弹 heads-up，用户锁屏/后台根本注意不到
        // （2026-11-13：审批被绑到保活通知后彻底没提醒，就是因为前台通知不响不弹）。
        if (existing != null) {
            if (existing.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                existing.setImportance(NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(existing);
            }
            return;
        }
        NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL_ID, "电脑端提醒",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("电脑端等待审批、后台任务失败等需要你处理的提醒");
        ch.enableVibration(true);
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
        b.setContentTitle(pendingApprovalId.isEmpty() ? "电脑端 · 远程连接" : "待你处理 · 电脑端请求审批")
                .setContentText(pendingApprovalId.isEmpty()
                        ? (text == null ? "" : text)
                        : (pendingApprovalText == null ? "" : pendingApprovalText))
                .setSmallIcon(com.oilquiz.app.R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(false);
        if (!pendingApprovalId.isEmpty()) {
            // 展开通知显示完整信息；系统 action 在 MIUI 上是"展开那一行"后才出现
            // （试过自定义 RemoteViews：MIUI 把它序列化成 0 bytes，通知直接不显示，已回退）。
            String detail = pendingApprovalText == null ? "" : pendingApprovalText;
            b.setStyle(new Notification.BigTextStyle().bigText(detail));
            b.setSubText("审批 ID " + shortId(pendingApprovalId));
            b.addAction(new Notification.Action.Builder(null, "允许",
                    approvalAction(pendingApprovalId, "allow", 1)).build());
            b.addAction(new Notification.Action.Builder(null, "拒绝",
                    approvalAction(pendingApprovalId, "deny", 2)).build());
            // 详情：打开对话页看完整上下文（这条是唯一会拉起页面的入口）
            b.addAction(new Notification.Action.Builder(null, "详情", pi).build());
        }
        return b.build();
    }

    /** id 太长，通知副标题只显示前 8 位，够对照日志 */
    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }

    /** 审批状态变了就重画常驻通知（有审批挂按钮，没有就恢复连接状态文案） */
    private void refreshConnectionNotification() {
        String text = pendingApprovalId.isEmpty()
                ? ("已连接" + (title.isEmpty() ? "" : " · " + title))
                : pendingApprovalText;
        update(buildNotification(text));
    }
}
