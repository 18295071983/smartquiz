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
    private volatile String sid = "";
    private volatile Map<String, Object> lastState;
    private volatile boolean online;
    private RemoteChannel channel;

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

    /** 幂等启动：解析身份 → 开唯一通道 */
    public void start() {
        if (started) return;
        started = true;
        new Thread(this::resolveAndOpen, "remote-connection").start();
    }

    /** 停止并释放通道（用户断开/服务销毁时） */
    public void stop() {
        started = false;
        online = false;
        closeChannel();
    }

    private void resolveAndOpen() {
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
        open(id, cursor);
    }

    private void open(String sessionId, int since) {
        closeChannel();
        sid = sessionId;
        Log.i(TAG, "[DSH] open sid=" + sessionId + " since=" + since);
        channel = new RemoteChannel(app, sessionId, since, new RemoteChannel.Listener() {
            @Override
            public void onState(Map<String, Object> state) {
                lastState = state;
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
                    // 传输游标：只给下一次重连用，不推进页面渲染游标
                    RemoteDshTool.setTransportSeq(app, sessionId, seq);
                }
                deliver(l -> l.onEvent(event));
            }

            @Override
            public void onPulse(long serverTs, int seq) {
                online = true;
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
