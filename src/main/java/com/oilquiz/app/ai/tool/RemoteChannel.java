package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机端的**唯一连接通道**（2026-11-12 重构）。
 *
 * <p>以前是"客户端轮询"：聊天页每 3 秒打一次 {@code /events} 补拉、连接页每 5 秒
 * {@code /v2/view} + {@code /v2/pulse}、会话选择器每 3 秒拉一次列表；两条通道各自维护
 * {@code since}，还会和"发消息时临时开的 600s 流"抢渲染 —— 这就是"通道有问题、对账对不上"的根源。</p>
 *
 * <p>现在的模型是**一次连接、服务端推送**：</p>
 * <pre>
 *   客户端 GET /events?follow=1&since=&caps=   （一个请求）
 *   服务端立即推 state + view，之后持续推 event / pulse / view / stream/delta /
 *   subagent/activity / schedule/changed …
 *   客户端只做三件事：dispatch、推进 since、定期 ack
 * </pre>
 *
 * <p>断线由本类负责指数退避重连，并带着 {@code since} 续传；不丢事件（服务端按 seq 重放）。</p>
 */
public final class RemoteChannel {

    private static final String TAG = "RemoteChannel";

    public interface Listener {
        /** 服务端下发的权威会话状态（id/标题/是否 live/执行位置/游标） */
        void onState(Map<String, Object> state);

        /** 服务端派生的视图（cards；连接页直接渲染） */
        void onView(Map<String, Object> view);

        /** 业务事件（durable 会话事件 + 合成事件） */
        void onEvent(JSONObject event);

        /** 心跳（服务端时间 + 当前 seq） */
        void onPulse(long serverTs, int seq);

        /** 连接状态变化；detail 用于界面提示 */
        void onConnection(boolean online, String detail);

        /**
         * 服务端 caps 摘要（2026-11-15，§18）：握手回执（WS hello / SSE ready|snapshot）
         * 解析后回调；服务端没带摘要时用本地声明兜底（{@link CapsSummary#local()}）。
         */
        default void onCaps(CapsSummary summary) {
        }
    }

    private final Context app;
    private final String sid;
    private final Listener listener;

    private volatile boolean closed;
    private volatile int lastSeq;
    private volatile long lastAckAt;
    /** 长连接连续失败后进入拉取模式（2026-11-14）：免费隧道对长连接不友好 */
    private volatile boolean pollMode;
    /**
     * UI 通道的 socket（2026-11-14）。必须持有引用：以前每重连一次就新开一条
     * 却不关旧的，`/ws/ui` 上会越堆越多（自检实测 2 条）——审批/slot 推送被重复投递，
     * 手机端表现为重复弹窗。
     */
    private volatile okhttp3.WebSocket uiSocket;
    private Thread worker;

    /**
     * 最近一次握手回执里的 caps 摘要（2026-11-15，§18）。
     *
     * <p>WS hello / SSE ready|snapshot 带 {@code caps}；解析后立即打一行
     * {@code [caps] 声明=… 缺失=…}，并回调 {@link Listener#onCaps}。服务端没带
     * （旧插件）时用 {@link CapsSummary#local()} 兜底，只兜一次，避免每次重连刷屏。</p>
     */
    private volatile CapsSummary lastCapsSummary;
    private volatile boolean localCapsLogged;

    // ---- WS 周期重试（2026-11-15，§17 WS-RETRY）----
    // 以前 run() 开头只试 3 次 WS，失败就永久退回 SSE，事后不再升级（插件重载后
    // ws_clients=0，只有重启 App 才回到 WS）。现在 WS 与 SSE 并行：WS 按退避周期重试、
    // 永不放弃；SSE 在 WS 未连上期间照常兜底，WS 一旦握手成功立刻让位。

    /** 退避策略：1s→2s→4s→8s→16s→30s，±20% 抖动。 */
    private final com.oilquiz.app.ai.remote.WsRetryPolicy wsRetry =
            new com.oilquiz.app.ai.remote.WsRetryPolicy();
    /** WS 已接管事件流：SSE 暂停（不双通道重复投递） */
    private volatile boolean wsActive;
    /** 本轮 WS 不可用的起始时刻（0 = 正常）；只用于 shouldRetry 的时间窗口护栏 */
    private volatile long wsDownSince;
    /** 重连调度走主线程 postDelayed；close() 时整体移除 */
    private android.os.Handler wsRetryHandler;
    /** 当前正在跑的 /ws/events socket：close() 要能立刻掐断，否则连接线程阻塞到读超时 */
    private volatile okhttp3.WebSocket eventSocket;
    /** 同一时刻只跑一个 WS 连接尝试（okhttp 回调异步，连接线程阻塞等 ready） */
    private final java.util.concurrent.atomic.AtomicBoolean wsConnecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** WS 连上 → SSE 停；WS 断开 → SSE 立刻醒（wait/notify，最多 500ms 轮询一次防漏通知） */
    private final Object wsLock = new Object();

    public RemoteChannel(Context c, String sessionId, int since, Listener listener) {
        this.app = c.getApplicationContext();
        this.sid = sessionId == null ? "" : sessionId;
        this.listener = listener;
        // 允许 -2（"从现在开始"）：首次连接不回放历史；收到第一条事件后变成真实 seq
        this.lastSeq = since;
    }

    public String sessionId() {
        return sid;
    }

    /** 已应用到的事件最大序号（= 客户端游标） */
    public int lastSeq() {
        return lastSeq;
    }

    public boolean isClosed() {
        return closed;
    }

    /** 最近一次握手回执的 caps 摘要；还没握手成功时为 null（§18，设置页备用） */
    public CapsSummary lastCapsSummary() {
        return lastCapsSummary;
    }

    /** 解析握手帧里的 caps 块：成功则留档 + 打一行 + 回调上层 */
    private void acceptCaps(org.json.JSONObject frame, String source) {
        if (frame == null) return;
        try {
            CapsSummary s = CapsSummary.parse(frame.opt("caps"), source);
            if (s == null) return;
            lastCapsSummary = s;
            Log.i("DshProbe", s.line());
            listener.onCaps(s);
        } catch (Throwable t) {
            Log.w(TAG, "caps 解析失败: " + t.getMessage());
        }
    }

    /**
     * 服务端没回 caps 摘要（旧插件）时的兜底：用本端声明的名单打一行，缺失显示 {@code ?}。
     * 每次通道生命周期只打一次，避免每条 SSE 重连都刷屏。
     */
    private void acceptLocalCaps() {
        if (localCapsLogged) return;
        localCapsLogged = true;
        CapsSummary local = CapsSummary.local();
        lastCapsSummary = local;
        Log.i("DshProbe", local.line());
        listener.onCaps(local);
    }

    /** 开一个后台线程持流；重复调用无副作用 */
    public void start() {
        if (worker != null) return;
        worker = new Thread(this::run, "remote-channel");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 页面落屏确认：只有确认过的事件才算"送达"，游标从这里推进。
     *
     * <p>见 {@code onEvent} 里的说明：把游标从"派发即推进"改成"落屏后推进"，
     * 断线/切后台期间没落屏的事件下次重连会被重放，而不是被静默跳过。</p>
     *
     * @param seq 已落屏的最大 seq（小于等于当前游标时忽略）
     */
    public void confirmApplied(int seq) {
        if (seq > lastSeq) lastSeq = seq;
    }

    /**
     * 快照重建后对齐游标（2026-11-15，§14B）：快照水位之前的状态已经整体重建，
     * 传输游标与拉取游标都直接跳到水位，避免把旧事件再拉一遍。
     */
    public void resetCursorTo(int seq) {
        if (seq < 0) return;
        lastSeq = seq;
    }

    /** 关通道：置位 + 取消所有进行中的事件流（下一次订阅拿新代） */
    public void close() {
        closed = true;
        wsActive = false;
        wakeSse();
        android.os.Handler h = wsRetryHandler;
        if (h != null) h.removeCallbacksAndMessages(null);
        // WS 连接线程可能阻塞在 closedLatch.await() 上：掐掉 socket 才有 onFailure。
        try {
            okhttp3.WebSocket es = eventSocket;
            eventSocket = null;
            if (es != null) es.cancel();
        } catch (Throwable ignored) { /* 已断 */ }
        RemoteDshTool.cancelEventStreams();
        // 只翻代号不够：读线程可能还阻塞在 readLine 上（最多 35s 才超时）。
        // 主动掐掉连接，重连/硬重置才能立刻生效（§14C）。
        RemoteDshTool.closeEventStreams();
        try {
            okhttp3.WebSocket ui = uiSocket;
            uiSocket = null;
            if (ui != null) ui.cancel();
        } catch (Throwable ignored) { /* 已断 */ }
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    private void run() {
        // WS 与 SSE 并行（2026-11-15，§17 WS-RETRY）：
        // 以前这里先试 3 次 WS 才轮到 SSE，失败即永久放弃；现在 WS 由独立监管线程
        // 按退避周期一直重试，SSE 主循环在 WS 未连上时照常兜底（事件不断），
        // WS 一旦握手成功立刻停 SSE、由 WS 接管。
        startWsSupervisor();
        runSseLoop();
    }

    /**
     * WS 连接监管（§17）：连接失败/断开都按 {@link com.oilquiz.app.ai.remote.WsRetryPolicy}
     * 周期重试，**永不放弃**；每次失败后都确保 SSE 兜底已经恢复。
     *
     * <p>为什么用主线程 {@code postDelayed} 而不是在连接线程里 sleep：连接线程要阻塞等
     * {@code ready}（最多 8s）并随后一直持有长连接，退避调度必须独立出来，页面销毁时
     * {@link #close()} 也能一次性把待触发的重试全部移除。</p>
     */
    private void startWsSupervisor() {
        if (wsRetryHandler == null) {
            wsRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        scheduleWsAttempt(0L);
    }

    private void scheduleWsAttempt(long delayMs) {
        android.os.Handler h = wsRetryHandler;
        if (closed || h == null) return;
        h.removeCallbacks(wsAttempt);
        h.postDelayed(wsAttempt, Math.max(0L, delayMs));
    }

    /** 一次 WS 连接尝试：成功则一直持有到断开，失败则排下一次退避重试。 */
    private final Runnable wsAttempt = () -> {
        if (closed || !wsConnecting.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            boolean established = false;
            try {
                established = runSocketOnce();
            } catch (Throwable e) {
                Log.w(TAG, "WS 连接异常: " + e.getMessage());
            } finally {
                wsConnecting.set(false);
            }
            if (closed) return;
            // 握手成功过又断：退避清零 —— 下一次重连仍从 1s 起（§17）。
            if (established) wsRetry.reset();
            // 断线期间 SSE 必须立刻恢复兜底；wsActive 置 false 后 runSseLoop 会被唤醒。
            wsActive = false;
            wakeSse();
            if (wsDownSince == 0L) wsDownSince = System.currentTimeMillis();
            if (established) {
                Log.i("DshProbe", "[ws] 已断开，SSE 兜底恢复");
            }
            int attempt = wsRetry.noteFailure();
            long delay = wsRetry.nextDelayMs();
            Log.i("DshProbe", "[ws] 重试第 " + attempt + " 次，延迟 " + delay + "ms"
                    + (established ? "（连接断开）" : "（握手失败）"));
            if (!wsRetry.shouldRetry(System.currentTimeMillis() - wsDownSince)) {
                Log.w("DshProbe", "[ws] 重试窗口用尽，停止重试（SSE 兜底继续）");
                return;
            }
            scheduleWsAttempt(delay);
        }, "remote-ws-connect");
        t.setDaemon(true);
        t.start();
    };

    /** SSE 让位后等待 WS 断开（最多 500ms 轮询一次，防 notify 丢失）。 */
    private void awaitWsDrop() {
        synchronized (wsLock) {
            while (!closed && wsActive) {
                try {
                    wsLock.wait(500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void wakeSse() {
        synchronized (wsLock) {
            wsLock.notifyAll();
        }
    }

    /** SSE 兜底主循环：WS 未接管时跑；WS 连上时被 stopSseFallback() 掐断并让位。 */
    private void runSseLoop() {
        long backoff = 1000;
        int shortStreams = 0;
        while (!closed) {
            if (wsActive) {
                awaitWsDrop();
                continue;
            }
            if (pollMode) {
                pollLoop();
                continue;
            }
            final long startedAt = System.currentTimeMillis();
            final boolean[] gotAnything = {false};
            try {
                // STREAM-TTL(2026-11-14)：以前这里传 300 —— 服务端到点就"正常收尾"，
                // 于是**每 5 分钟**必然断一次、重连一次（空窗 ~1s，隧道再抖一下就变成
                // 用户看到的"SSE 流不稳定"）。跟随流本来就不该按时间断：
                // 存活由服务端 10s 一条 pulse + 客户端 35s 读超时判定，所以这里放到
                // 30 分钟（= subscribeEvents 自己的 deadline），只在真死链时才重连。
                // 背压（§14C）：队列积压过线时页面会先请求快照、再调 close() 重连，
                // 新连接从快照水位续传，不再把积压的推送灌进来。
                RemoteDshTool.subscribeEvents(app, sid, lastSeq, 1800, true, 5000, (String) null,
                        new RemoteDshTool.DshEventListener() {
                            @Override
                            public void onReady(int seq) {
                                if (closed) return;
                                // 旧插件不带 caps：兜底打一行本端声明（缺失=?）
                                if (lastCapsSummary == null) acceptLocalCaps();
                                // 不再用"服务器当前 seq"覆盖游标：那等于宣布"之前的事件都收过了"。
                                listener.onConnection(true, "已连接");
                            }

                            @Override
                            public void onHello(org.json.JSONObject frame) {
                                // §18：SSE 的 ready / snapshot 带 caps 摘要
                                acceptCaps(frame, CapsSummary.SOURCE_SSE);
                            }

                            @Override
                            public void onSnapshot(int seq, JSONArray turns) {
                                // 服务端说"缺口过大，给你一份快照"：这是唯一需要放弃
                                // 本地增量、整段重建的信号。通道层把它转成一条内部事件，
                                // 由页面丢掉本地时间线并按快照重建。
                                JSONObject snapshot = new JSONObject();
                                try {
                                    snapshot.put("type", "__force_resync");
                                    snapshot.put("seq", -1);
                                    snapshot.put("data", new JSONObject().put("server_seq", seq));
                                } catch (org.json.JSONException ignored) {
                                    return;
                                }
                                listener.onEvent(snapshot);
                            }

                            @Override
                            public void onEvent(JSONObject event) {
                                if (closed || event == null) return;
                                gotAnything[0] = true;
                                String type = event.optString("type", "");
                                if ("state".equals(type)) {
                                    listener.onState(jsonObjectToMap(event.optJSONObject("data")));
                                    return;
                                }
                                if ("view".equals(type)) {
                                    listener.onView(jsonObjectToMap(event.optJSONObject("data")));
                                    return;
                                }
                                if ("pulse".equals(type)) {
                                    listener.onPulse(event.optLong("ts", 0L), event.optInt("seq", lastSeq));
                                    return;
                                }
                                // ★ 2026-11-12：这里**不再**随手推进游标。
                                //   以前是"派发即推进"，页面不在前台/事件被 per-turn 游标丢弃时，
                                //   事件在传输层已经算送达，于是重连永远不会再补 —— 手机侧工具卡
                                //   停在"运行中"、正文缺段就是这么来的。
                                //   现在只有页面真正落屏后回调 confirmApplied(seq) 才提交游标；
                                //   没人确认就不推进，下次重连照原样重放（重放幂等）。
                                listener.onEvent(event);
                                maybeAck();
                            }

                            @Override
                            public void onPulse(long serverTs, int seq) {
                                if (closed) return;
                                // 心跳也算"这条流还活着"：断线判定与退避重置都据此。
                                gotAnything[0] = true;
                                listener.onPulse(serverTs, seq);
                            }

                            @Override
                            public void onEnd() {
                                if (closed || wsActive) return;
                                // 服务端到点（默认 600s）或 gone 之后正常收尾：这里只报状态，
                                // 真正的重连接口在 subscribeEvents 内部（带 since 续传）。
                                listener.onConnection(true, "等待重连");
                            }

                            @Override
                            public void onError(String message) {
                                if (closed || wsActive) return;
                                listener.onConnection(false, message == null ? "连接失败" : message);
                            }
                        });
            } catch (Exception e) {
                if (!closed && !wsActive) {
                    listener.onConnection(false,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
            if (closed) break;
            if (wsActive) continue;   // WS 已接管：这次 SSE 退出不算失败，别重开双通道
            // subscribeEvents 自己会重试 8 次；走到这里说明它整个返回了（30 分钟或彻底失败）。
            boolean shortLived = System.currentTimeMillis() - startedAt < 60_000L;
            if (!gotAnything[0]) {
                // 短命且一条都没收到 = 这条路（多半是隧道）在掐长连接。
                // 连续 3 次就切到"拉取模式"：普通短请求 + 游标，免费隧道也扛得住。
                if (shortLived) shortStreams += 1; else shortStreams = 0;
                if (shortStreams >= 3) {
                    shortStreams = 0;
                    pollMode = true;
                    listener.onConnection(true, "长连接不稳，已切到拉取模式");
                    continue;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(30000, backoff * 2);
            } else {
                backoff = 1000;
                shortStreams = 0;
            }
        }
    }

    /**
     * 跑一次 WebSocket 通道，直到断线。
     *
     * <p>WS 只推"连上之后"的事件，所以拿到 {@code ready{seq}} 后先用 {@code /v2/events/pull}
     * 把本地游标追到服务端 seq，再靠 WS 增量 —— 这样 WS 只要保证"实时"，补历史仍然只有
     * 一条被测过的代码路径。</p>
     *
     * <p>§17：握手成功即<b>接管</b>——置 {@link #wsActive} 并停掉 SSE 兜底（避免双通道
     * 重复投递）；方法返回时把 event/ui 两条 socket 都关干净，由监管线程决定退避重连。</p>
     *
     * @return true = 曾经握手成功（断线后从 1s 起重连）；false = 这次没连上（继续按策略重试）
     */
    private boolean runSocketOnce() {
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch closedLatch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean gotReady = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger head = new java.util.concurrent.atomic.AtomicInteger(-1);

        okhttp3.WebSocket socket = null;
        try {
            socket = RemoteDshTool.openLiveSocket(app, sid, new RemoteDshTool.DshSocketListener() {
                @Override
                public void onReady(int seq) {
                    gotReady.set(true);
                    head.set(seq);
                    // §18：旧插件/极端情况下 hello 没带 caps，用本端声明兜底（只打一次）
                    if (lastCapsSummary == null) acceptLocalCaps();
                    ready.countDown();
                }

                @Override
                public void onHello(org.json.JSONObject frame) {
                    // §18：WS hello 带服务端 caps 摘要
                    acceptCaps(frame, CapsSummary.SOURCE_WS_HELLO);
                }

                @Override
                public void onEvent(JSONObject event) {
                    if (closed || event == null) return;
                    listener.onEvent(event);
                    maybeAck();
                }

                @Override
                public void onPulse(long serverTs, int seq) {
                    if (!closed) listener.onPulse(serverTs, seq);
                }

                @Override
                public void onClosed(String reason) {
                    Log.i("DshProbe", "[ws] 连接结束：" + reason);
                    ready.countDown();
                    closedLatch.countDown();
                }
            }, "/ws/events");
            if (closed) return false;
            eventSocket = socket;
            // UI 端点单独一条 socket（cmd / slot / 审批 / 提问）：文件传输或事件洪水不会堵住它。
            // 开之前先把上一条关掉——否则每次重连都多堆一条，推送被重复投递。
            closeUiSocket();
            uiSocket = RemoteDshTool.openLiveSocket(app, sid, new RemoteDshTool.DshSocketListener() {
                @Override public void onReady(int seq) { }
                @Override public void onEvent(JSONObject event) {
                    if (!closed && event != null) listener.onEvent(event);
                }
                @Override public void onPulse(long serverTs, int seq) { }
                @Override public void onClosed(String reason) { }
            }, "/ws/ui");

            boolean established;
            try {
                established = ready.await(8, java.util.concurrent.TimeUnit.SECONDS) && gotReady.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!established || closed) return false;

            // 握手成功：WS 接管事件流，SSE 兜底立刻让位（§17 WS/SSE 互斥语义）。
            wsActive = true;
            wsDownSince = 0L;
            Log.i("DshProbe", "[ws] 已连接，停 SSE");
            listener.onConnection(true, "WS 已连接");
            stopSseFallback();
            scheduleSseSweep();
            catchUpViaPull(head.get());
            try {
                closedLatch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "WS 连接失败: " + t.getMessage());
            return false;
        } finally {
            try {
                if (socket != null) socket.cancel();
            } catch (Throwable ignored) { /* 已断 */ }
            eventSocket = null;
            closeUiSocket();
        }
    }

    /** 停掉 SSE 兜底：翻代 + 掐断正在读的 follow 流（readLine 不用等 35s 读超时）。 */
    private void stopSseFallback() {
        RemoteDshTool.cancelEventStreams();
        RemoteDshTool.closeFollowStreams();
        wakeSse();
    }

    /**
     * 补一次清扫（§17）：WS 置位 {@code wsActive} 与 SSE 线程「检查完标志、正要开流」之间
     * 有一个微秒级窗口，晚开的那条流不会看到旧的代。1.5s 后再扫一次，把竞态窗口关掉；
     * 只动 follow 流，导入/补拉不受影响。
     */
    private void scheduleSseSweep() {
        android.os.Handler h = wsRetryHandler;
        if (h == null) return;
        h.postDelayed(() -> {
            if (!closed && wsActive) stopSseFallback();
        }, 1500L);
    }

    private void closeUiSocket() {
        try {
            okhttp3.WebSocket ui = uiSocket;
            uiSocket = null;
            if (ui != null) ui.cancel();
        } catch (Throwable ignored) { /* 已断 */ }
    }

    /** 把本地游标追到服务端 head（WS 不管历史，只补这一段缺口） */
    private void catchUpViaPull(int head) {
        if (head < 0) return;
        if (lastSeq < 0) {          // -2 = "从现在开始"，不补历史
            lastSeq = head;
            return;
        }
        int guard = 0;
        while (!closed && lastSeq < head && guard < 20) {
            guard += 1;
            try {
                int next = RemoteDshTool.pullEvents(app, sid, Math.max(0, lastSeq), 200,
                        new RemoteDshTool.DshEventListener() {
                            @Override public void onReady(int seq) { }
                            @Override public void onEvent(JSONObject event) {
                                if (!closed && event != null) listener.onEvent(event);
                            }
                            @Override public void onEnd() { }
                            @Override public void onError(String message) { }
                        });
                if (next <= lastSeq) break;
                lastSeq = next;
                maybeAck();
            } catch (Exception e) {
                break;   // 追不上也无妨：事件幂等，后续 WS 增量会补
            }
        }
    }

    /**
     * 拉取模式（2026-11-14）：每 1.5s 一次 {@code /v2/events/pull}，游标续传。
     *
     * <p>为什么要有：免费隧道会掐长连接、还可能缓冲 SSE 帧；WebSocket 在公网侧连升级都过不去
     * （实测探针）。"普通短请求 + 游标"是这类链路唯一稳定的形态。跑满 2 分钟后回到 SSE 重试，
     * 隧道恢复了好处（省请求）就能自动回去。</p>
     */
    private void pollLoop() {
        long until = System.currentTimeMillis() + 120_000L;
        int cursor = Math.max(0, lastSeq);
        while (!closed && !wsActive && System.currentTimeMillis() < until) {
            try {
                int next = RemoteDshTool.pullEvents(app, sid, cursor, 200,
                        new RemoteDshTool.DshEventListener() {
                            @Override public void onReady(int seq) { }
                            @Override public void onEvent(JSONObject event) {
                                if (closed || event == null) return;
                                listener.onEvent(event);
                            }
                            @Override public void onEnd() { }
                            @Override public void onError(String message) { }
                        });
                if (next > cursor) {
                    cursor = next;
                    if (cursor > lastSeq) lastSeq = cursor;
                    maybeAck();
                }
            } catch (Exception e) {
                if (closed) break;
                listener.onConnection(false, e.getMessage() == null ? "拉取失败" : e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // 2 分钟到（或被 WS 接管）：回 SSE 试一次（隧道恢复的话这里能自动回到长连接）
        pollMode = false;
    }

    /** 定期回执：服务端按 acked_seq 维护水位线，断线重连时两边口径一致 */
    private void maybeAck() {
        long now = System.currentTimeMillis();
        if (lastSeq < 0 || now - lastAckAt < 5000) return;
        lastAckAt = now;
        final int seq = lastSeq;
        new Thread(() -> {
            try {
                new RemoteSdk(app).ack(sid, seq);
            } catch (Exception e) {
                Log.w(TAG, "ack failed: " + e.getMessage());
            }
        }, "remote-ack").start();
    }

    // ---------------- JSON → Map ----------------

    static Map<String, Object> jsonObjectToMap(JSONObject o) {
        if (o == null) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, jsonToJava(o.opt(k)));
        }
        return out;
    }

    static Object jsonToJava(Object v) {
        if (v instanceof JSONObject) {
            return jsonObjectToMap((JSONObject) v);
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i += 1) list.add(jsonToJava(a.opt(i)));
            return list;
        }
        return v;
    }
}
