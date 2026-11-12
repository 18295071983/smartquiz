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
        while (!closed) {
            final boolean[] gotAnything = {false};
            try {
                RemoteDshTool.subscribeEvents(app, sid, lastSeq, 300, true, 5000, (String) null,
                        new RemoteDshTool.DshEventListener() {
                            @Override
                            public void onReady(int seq) {
                                if (closed) return;
                                if (seq > lastSeq) lastSeq = seq;
                                listener.onConnection(true, "已连接");
                            }

                            @Override
                            public void onSnapshot(int seq, JSONArray turns) {
                                // 缺口过大时服务端给的是快照；通道层只同步游标，
                                // 具体怎么补历史由页面决定（时间线已有增量，不在这里重建）。
                                if (seq > lastSeq) lastSeq = seq;
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
                                int seq = event.optInt("seq", -1);
                                if (seq > lastSeq) lastSeq = seq;
                                listener.onEvent(event);
                                maybeAck();
                            }

                            @Override
                            public void onEnd() {
                                if (closed) return;
                                // 服务端 5 分钟超时是正常的；subscribeEvents 会自己重连，
                                // 这里只把状态告诉界面。
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
            if (!gotAnything[0]) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(30000, backoff * 2);
            } else {
                backoff = 1000;
            }
        }
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
