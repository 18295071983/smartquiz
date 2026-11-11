package com.oilquiz.app.ai.tool;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RemoteStream —— 读取电脑端 /v2/stream（SSE）的最小客户端。
 *
 * 为什么单独一个类：事件流是"双端同一时间线"的入口，不能塞进 2000 行的 RemoteDshTool 里。
 * 用法：
 * <pre>
 *   RemoteStream s = new RemoteStream();
 *   s.open(url, token, 5000, listener);   // 后台线程读
 *   ...
 *   s.stop();                             // 页面退出/用户取消
 * </pre>
 * 事件格式：{ v, seq, id, ts, type, data }，与电脑面板、手机页面渲染的是同一份。
 */
public final class RemoteStream {

    private static final String TAG = "RemoteStream";

    public interface Listener {
        /** 收到一条事件（已解析成 Map） */
        void onEvent(Map<String, Object> event);

        /** 流结束：reason = eof | timeout | stopped | error:<msg> */
        void onEnd(String reason);
    }

    private volatile boolean stopped;
    private volatile HttpURLConnection conn;

    /** 主动停止：断开连接并让读线程退出 */
    public void stop() {
        stopped = true;
        HttpURLConnection c = conn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { /* 已断 */ }
        }
    }

    public boolean isStopped() {
        return stopped;
    }

    /**
     * 后台打开事件流。
     *
     * @param url      RemoteSdk#streamUrl 生成的地址
     * @param token    访问令牌（与 /status 同一个）
     * @param waitMs   最长读多久（到时自动结束，避免后台挂死）
     * @param listener 回调（在后台线程触发）
     */
    public void open(final String url, final String token, final long waitMs, final Listener listener) {
        Thread t = new Thread(() -> run(url, token, waitMs, listener), "remote-sse");
        t.setDaemon(true);
        t.start();
    }

    private void run(String url, String token, long waitMs, Listener listener) {
        long deadline = System.currentTimeMillis() + Math.max(1000, waitMs);
        String endReason = "eof";
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(2000, waitMs + 5000)));
            conn.setRequestProperty("Accept", "text/event-stream");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                endReason = "error:HTTP " + code;
                return;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while (!stopped && System.currentTimeMillis() < deadline) {
                    line = r.readLine();
                    if (line == null) { endReason = "eof"; break; }
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            emit(data.toString(), listener);
                            data.setLength(0);
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    }
                    // ": ping" / "event:" / "id:" 这类行直接忽略
                }
                if (System.currentTimeMillis() >= deadline) endReason = "timeout";
                if (stopped) endReason = "stopped";
            }
        } catch (Exception e) {
            endReason = stopped ? "stopped" : ("error:" + e.getMessage());
            Log.w(TAG, "stream end: " + endReason);
        } finally {
            HttpURLConnection c = conn;
            if (c != null) {
                try { c.disconnect(); } catch (Exception ignored) { /* 已断 */ }
            }
            try { listener.onEnd(endReason); } catch (Exception ignored) { /* 回调异常不影响收尾 */ }
        }
    }

    @SuppressWarnings("unchecked")
    private void emit(String json, Listener listener) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, o.opt(k));
            }
            listener.onEvent(map);
        } catch (Exception e) {
            Log.w(TAG, "bad event json: " + e.getMessage());
        }
    }
}
