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
    }

    private final Context app;
    private final String sid;
    private final Listener listener;

    private volatile boolean closed;
    private volatile int lastSeq;
    private volatile long lastAckAt;
    /** 长连接连续失败后进入拉取模式（2026-11-14）：免费隧道对长连接不友好 */
    private volatile boolean pollMode;
    private Thread worker;

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

    /** 关通道：置位 + 取消所有进行中的事件流（下一次订阅拿新代） */
    public void close() {
        closed = true;
        RemoteDshTool.cancelEventStreams();
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    private void run() {
        long backoff = 1000;
        int shortStreams = 0;
        while (!closed) {
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
                RemoteDshTool.subscribeEvents(app, sid, lastSeq, 1800, true, 5000, (String) null,
                        new RemoteDshTool.DshEventListener() {
                            @Override
                            public void onReady(int seq) {
                                if (closed) return;
                                // 不再用"服务器当前 seq"覆盖游标：那等于宣布"之前的事件都收过了"。
                                listener.onConnection(true, "已连接");
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
                                if (closed) return;
                                // 服务端到点（默认 600s）或 gone 之后正常收尾：这里只报状态，
                                // 真正的重连接口在 subscribeEvents 内部（带 since 续传）。
                                listener.onConnection(true, "等待重连");
                            }

                            @Override
                            public void onError(String message) {
                                if (closed) return;
                                listener.onConnection(false, message == null ? "连接失败" : message);
                            }
                        });
            } catch (Exception e) {
                listener.onConnection(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            if (closed) break;
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
     * 拉取模式（2026-11-14）：每 1.5s 一次 {@code /v2/events/pull}，游标续传。
     *
     * <p>为什么要有：免费隧道会掐长连接、还可能缓冲 SSE 帧；WebSocket 在公网侧连升级都过不去
     * （实测探针）。"普通短请求 + 游标"是这类链路唯一稳定的形态。跑满 2 分钟后回到 SSE 重试，
     * 隧道恢复了好处（省请求）就能自动回去。</p>
     */
    private void pollLoop() {
        long until = System.currentTimeMillis() + 120_000L;
        int cursor = Math.max(0, lastSeq);
        while (!closed && System.currentTimeMillis() < until) {
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
        // 2 分钟到：回 SSE 试一次（隧道恢复的话这里能自动回到长连接）
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
