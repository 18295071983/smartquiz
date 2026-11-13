package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程级连接管理器（2026-11-12）。
 *
 * <p>为什么必须有它：之前连接由 Activity 自己持有（连接页一个、聊天页一个、前台服务再一个），
 * 页面一切走/旋转就断，后台又各开一条流；网络一抖就各自重连，游标还互相覆盖。
 * 现在**整个 App 只有这一条通道**：</p>
 *
 * <ul>
 *   <li>生命周期由前台 Service 持有（{@link RemoteSyncService}），页面只订阅/退订；</li>
 *   <li>会话身份先和电脑对齐（{@link RemoteDshTool#syncSessionIdentity}），全 App 一个 id；</li>
 *   <li>传输游标（transport_seq）与渲染游标（last_seq）分开，后台收事件不会把页面该看的补拉"吃掉"；</li>
 *   <li>断线、心跳、ack、指数退避都在 {@link RemoteChannel}，这里只负责"唯一起点 + 扇出"。</li>
 * </ul>
 */
public final class RemoteConnection {

    private static final String TAG = "RemoteConnection";

    /** 页面订阅者（回调在主线程） */
    public interface Listener {
        default void onState(Map<String, Object> state) {
        }

        default void onView(Map<String, Object> view) {
        }

        default void onEvent(JSONObject event) {
        }

        default void onPulse(long serverTs, int seq) {
        }

        default void onConnection(boolean online, String detail) {
        }
    }

    private static volatile RemoteConnection INSTANCE;

    public static RemoteConnection get(Context c) {
        RemoteConnection local = INSTANCE;
        if (local == null) {
            synchronized (RemoteConnection.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new RemoteConnection(c.getApplicationContext());
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean started;
    /**
     * 是否已经有线程在"解析身份 + 开通道"。
     *
     * <p>为什么需要：Activity 的 onResume 与前台服务会几乎同时调 {@link #start()}，
     * 而通道对象要等身份对齐完才赋值。没有这个闸门时，两次调用都看到 {@code channel == null}
     * 就各自开一条流——真机实测同一 sid 在 4ms 内开了两条 SSE，事件被回放两遍
     * （日志里 seq 3220–3228 各出现两次）。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean opening =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String sid = "";
    private volatile Map<String, Object> lastState;
    private volatile boolean online;
    /** 服务端心跳里带的 seq（电脑端已产生到哪一条）；-1 = 还没收到 */
    private volatile int serverSeq = -1;
    /** 手机上已收到/已应用到的最大 seq；-1 = 还没收到事件 */
    private volatile int appliedSeq = -1;
    private RemoteChannel channel;
    /**
     * 本次进程内还没做过全量重放。首次连接时置 false 并用 since=0 重建时间线，
     * 之后的重连仍按传输游标续传（见 resolveAndOpen 的缺口修复注释）。
     */
    private boolean firstConnectSinceLaunch = true;

    private RemoteConnection(Context app) {
        this.app = app;
    }

    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isOnline() {
        return online;
    }

    public String sessionId() {
        return sid;
    }

    public Map<String, Object> state() {
        return lastState;
    }

    /** 电脑端已产生到的 seq（心跳/状态下发时更新）；-1 = 未知。 */
    public int serverSeq() {
        return serverSeq;
    }

    /** 手机已应用到的 seq；-1 = 还没收到事件。 */
    public int appliedSeq() {
        return appliedSeq;
    }

    /** 落后多少条（服务端领先客户端的事件数）；未知时返回 -1。 */
    public int behindCount() {
        int s = serverSeq;
        int a = appliedSeq;
        if (s < 0) return -1;
        return Math.max(0, s - Math.max(0, a));
    }

    /** 幂等启动：解析身份 → 开唯一通道 */
    public void start() {
        if (started) {
            // ZOMBIE-FIX(2026-11-13)：started 是"服务应该连着"，不等于"通道还活着"。
            // 前台服务被系统重启、或通道因异常退出后，channel 会是 null / 已 closed，
            // 而老实现直接 return —— 于是界面显示"已连接"、实际永远收不到事件。
            // 这里补一次存活检查：死了就重开。
            RemoteChannel ch = channel;
            if (ch != null && !ch.isClosed()) return;
        }
        // OPEN-GATE(2026-11-13)：并发调用只放一个线程去开，避免同一 sid 起两条 SSE
        if (!opening.compareAndSet(false, true)) return;
        started = true;
        startNetworkWatch();
        new Thread(this::resolveAndOpen, "remote-connection").start();
    }

    // ---- 网络切换即重开（NETWORK-FIX，2026-11-13）----
    // Wi-Fi ↔ 蜂窝、换 AP、隧道重建时，旧 TCP 不会立刻报错，只会在读超时后才发现；
    // 这段时间手机"看着在线"却收不到任何事件。系统既然会告诉我们网络变了，就主动重连。

    private android.net.ConnectivityManager.NetworkCallback netCallback;
    /** 注册回调会立刻回调一次 onAvailable，别把它当成"网络切换"再连一遍 */
    private volatile boolean netCallbackPrimed = false;

    private final Runnable netReconnect = () -> {
        if (!started) return;
        if (!opening.compareAndSet(false, true)) return;   // 已有线程在开，别并发出第二条
        closeChannel();
        new Thread(this::resolveAndOpen, "remote-connection-net").start();
    };

    private void scheduleReconnect() {
        if (!started) return;
        main.removeCallbacks(netReconnect);
        main.postDelayed(netReconnect, 1500);   // 抖动去重：切换过程中只重连一次
    }

    private void startNetworkWatch() {
        if (netCallback != null) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    if (!netCallbackPrimed) { netCallbackPrimed = true; return; }
                    scheduleReconnect();
                }

                @Override
                public void onLost(android.net.Network network) {
                    scheduleReconnect();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Throwable t) {
            // 没权限 / 旧系统：退化为读超时兜底（RemoteDshTool 侧 35s）
            netCallback = null;
        }
    }

    private void stopNetworkWatch() {
        if (netCallback == null) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Throwable ignored) {
            // 已注销/无权限
        }
        netCallback = null;
        netCallbackPrimed = false;
        main.removeCallbacks(netReconnect);
    }

    /** 停止并释放通道（用户断开/服务销毁时） */
    public void stop() {
        started = false;
        online = false;
        stopNetworkWatch();
        closeChannel();
    }

    private void resolveAndOpen() {
        try {
            resolveAndOpenInner();
        } finally {
            // 无论成功/早退（未配置、已断开、对齐失败），都要放开闸门，否则永远开不了下一条
            opening.set(false);
        }
    }

    private void resolveAndOpenInner() {
        if (!RemoteDshTool.isConfigured(app)) {
            deliverConnection(false, "还没配置电脑地址/令牌");
            return;
        }
        // 用户点过「断开」就不要因为页面 onResume 又偷偷连回去
        if (!RemoteDshTool.isConnected(app)) {
            deliverConnection(false, "已断开（在「远程连接」里点连接可恢复）");
            return;
        }
        String id;
        try {
            id = RemoteDshTool.syncSessionIdentity(app);
            // 直连会话单独对齐（@direct），失败不影响跟随通道
            try {
                RemoteDshTool.syncDirectIdentity(app);
            } catch (Exception ignored) {
                // 直连是附加信息；连不上时 UI 显示"首次直连时建立"
            }
        } catch (Exception e) {
            deliverConnection(false, "对齐会话失败：" + e.getMessage());
            // 网络刚恢复/隧道还没起来：稍后自己重试，不劳页面重新进
            main.postDelayed(() -> {
                if (started) new Thread(this::resolveAndOpen, "remote-connection-retry").start();
            }, 5000);
            return;
        }
        if (id == null || id.isEmpty()) {
            deliverConnection(false, "电脑端还没有绑定会话");
            return;
        }
        int cursor = RemoteDshTool.getTransportSeq(app, id);
        // 没有传输游标 = 首次连接：从"现在"开始（-2），不要把整条历史重放进页面
        if (cursor < 0) cursor = -2;
        // ★ 本地增量（2026-11-12）：连上就按**缓存的传输游标**续传，不做全量重放。
        //   持久化的 cursor 就是本地缓存的水位：服务端按 seq 只补它之后的事件。
        //   只有服务端明确告知"缺口过大"（发 snapshot）时才重建，见 RemoteChannel.onSnapshot。
        if (firstConnectSinceLaunch) {
            firstConnectSinceLaunch = false;
        }
        open(id, cursor);
    }

    /**
     * 页面落屏确认：把游标推进到已渲染的 seq，并落盘供下次启动续传。
     *
     * @param seq 已被页面消费（落屏）的最大 seq
     */
    public void confirmApplied(int seq) {
        RemoteChannel ch = channel;
        if (ch != null) ch.confirmApplied(seq);
        if (sid != null && !sid.isEmpty()) {
            try {
                RemoteDshTool.setTransportSeq(app, sid, seq);
            } catch (Throwable ignored) {
                // 落盘失败不影响本次会话
            }
        }
    }

    /**
     * 反向指令轮询（电脑端 → 手机，2026-11-12）。
     *
     * <p>插件侧新增了 {@code /cmd}（入队）、{@code /cmd/poll}（取用）、{@code /cmd/ack}（回执）。
     * 这里每 3 秒拉一次，按白名单执行纯 UI / 轻状态动作，执行完回执。
     * 电脑端只能让手机"弹点东西"——不碰文件、不跑命令、不改权限。</p>
     */
    private volatile boolean cmdPollerRunning = false;
    private Thread cmdPollerThread;

    private void startCommandPoller() {
        if (cmdPollerRunning) return;
        cmdPollerRunning = true;
        cmdPollerThread = new Thread(() -> {
            while (cmdPollerRunning && started) {
                try {
                    // 已有契约：RemoteDshTool.pollCommands 返回 JSONArray（/session action=cmd_poll）
                    org.json.JSONArray cmds = RemoteDshTool.pollCommands(app);
                    if (cmds != null) {
                        for (int i = 0; i < cmds.length(); i += 1) {
                            org.json.JSONObject c = cmds.optJSONObject(i);
                            if (c == null) continue;
                            Map<String, Object> cmd = new java.util.HashMap<>();
                            cmd.put("id", c.optString("id", ""));
                            cmd.put("tool", c.optString("tool", ""));
                            org.json.JSONObject a = c.optJSONObject("args");
                            if (a != null) cmd.put("args", jsonToMap(a));
                            execCommand(cmd);
                        }
                    }
                } catch (Throwable ignored) {
                    // 单轮失败不退出轮询
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "dsh-cmd-poller");
        cmdPollerThread.setDaemon(true);
        cmdPollerThread.start();
    }

    /** 执行一条反向指令（都在主线程做 UI）。 */
    @SuppressWarnings("unchecked")
    private void execCommand(Map<String, Object> cmd) {
        if (cmd == null) return;
        final String id = String.valueOf(cmd.get("id") == null ? "" : cmd.get("id"));
        final String tool = String.valueOf(cmd.get("tool") == null ? "" : cmd.get("tool"));
        final Map<String, Object> args = cmd.get("args") instanceof Map
                ? (Map<String, Object>) cmd.get("args") : new java.util.HashMap<>();
        final String title = str(args.get("title"));
        final String text = str(args.get("text") != null ? args.get("text") : args.get("content"));
        main.post(() -> {
            String result;
            try {
                switch (tool) {
                    case "toast":
                        android.widget.Toast.makeText(app, text.isEmpty() ? title : text,
                                android.widget.Toast.LENGTH_LONG).show();
                        result = "toast shown";
                        break;
                    case "dialog":
                    case "ui_component": {
                        android.app.AlertDialog.Builder b =
                                new android.app.AlertDialog.Builder(app, android.R.style.Theme_Material_Light_Dialog_Alert);
                        if (!title.isEmpty()) b.setTitle(title);
                        if (!text.isEmpty()) b.setMessage(text);
                        b.setPositiveButton("知道了", null);
                        b.show();
                        result = "dialog shown";
                        break;
                    }
                    case "notification":
                    case "reminder":
                    default: {
                        showNotification(title.isEmpty() ? "来自电脑端" : title, text);
                        result = "notification shown";
                        break;
                    }
                }
                try {
                    RemoteDshTool.ackCommand(app, id, true, result);
                } catch (Exception ignored) {
                    // 回执失败：指令 10 分钟后自然过期
                }
            } catch (Throwable t) {
                try {
                    RemoteDshTool.ackCommand(app, id, false, String.valueOf(t));
                } catch (Exception ignored) {
                    // 同上
                }
            }
        });
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 容错取整：Map 里的数字可能是 Integer/Long/String。 */
    private static int intOf(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** JSONObject → Map（反向指令 args 用）。 */
    private static Map<String, Object> jsonToMap(org.json.JSONObject o) {
        Map<String, Object> m = new java.util.HashMap<>();
        if (o == null) return m;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, o.opt(k));
        }
        return m;
    }

    /** 通知：反向通道里"人不在手机旁"的场景用得上。 */
    private void showNotification(String title, String content) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "dsh_cmd";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        ch, "电脑端消息", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
            }
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.O
                    ? new android.app.Notification.Builder(app, ch)
                    : new android.app.Notification.Builder(app);
            b.setContentTitle(title)
                    .setContentText(content == null ? "" : content)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0xFFFFFF), b.build());
        } catch (Throwable ignored) {
            // 没通知权限就静默失败，回执照常
        }
    }

    private void open(String sessionId, int since) {
        closeChannel();
        sid = sessionId;
        // 反向指令统一由 AIChatActivity 的 executeRemoteCommand 处理（它走完整 AIToolManager
        // 管线，ui_component 等能真正生效）。这里不再起第二套轮询，避免两边抢指令。
        Log.i(TAG, "[DSH] open sid=" + sessionId + " since=" + since);
        channel = new RemoteChannel(app, sessionId, since, new RemoteChannel.Listener() {
            @Override
            public void onState(Map<String, Object> state) {
                lastState = state;
                // 服务端权威游标：首连时把 applied 对齐到 last_seq（否则状态栏会显示 0/N 的假落后）
                if (state != null) {
                    int ls = intOf(state.get("last_seq"), -1);
                    if (ls >= 0) {
                        if (ls > serverSeq) serverSeq = ls;
                        if (appliedSeq < 0) appliedSeq = ls;
                    }
                }
                // 服务端是唯一权威：id/标题以它为准（本地旧键会被镜像覆盖）
                if (state != null) {
                    Object id = state.get("session_id");
                    Object title = state.get("title");
                    if (id != null && !String.valueOf(id).isEmpty()) {
                        RemoteDshTool.setCanonicalSession(app, String.valueOf(id),
                                title == null ? "" : String.valueOf(title));
                    }
                }
                deliver(l -> l.onState(state));
            }

            @Override
            public void onView(Map<String, Object> view) {
                deliver(l -> l.onView(view));
            }

            @Override
            public void onEvent(JSONObject event) {
                int seq = event == null ? -1 : event.optInt("seq", -1);
                if (seq > 0) {
                    if (seq > appliedSeq) appliedSeq = seq;
                    // 传输游标：只给下一次重连用，不推进页面渲染游标
                    RemoteDshTool.setTransportSeq(app, sessionId, seq);
                }
                deliver(l -> l.onEvent(event));
            }

            @Override
            public void onPulse(long serverTs, int seq) {
                online = true;
                if (seq >= 0 && seq > serverSeq) serverSeq = seq;
                deliver(l -> l.onPulse(serverTs, seq));
            }

            @Override
            public void onConnection(boolean up, String detail) {
                online = up;
                Log.i(TAG, "[DSH] connection=" + up + " " + detail);
                deliver(l -> l.onConnection(up, detail));
            }
        });
        channel.start();
    }

    private void closeChannel() {
        RemoteChannel c = channel;
        channel = null;
        if (c != null) c.close();
    }

    private void deliver(java.util.function.Consumer<Listener> action) {
        main.post(() -> {
            for (Listener l : listeners) {
                try {
                    action.accept(l);
                } catch (Throwable t) {
                    Log.w(TAG, "listener failed: " + t.getMessage());
                }
            }
        });
    }

    private void deliverConnection(boolean up, String detail) {
        online = up;
        deliver(l -> l.onConnection(up, detail));
    }
}
