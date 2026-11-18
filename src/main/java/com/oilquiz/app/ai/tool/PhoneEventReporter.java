package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手机本地事件回传（2026-11-18，P1）——{@code phone.event.*}。
 *
 * <p>把手机端的事实（工具调用轨迹 / agent 状态 / 错误摘要）批量 POST 给电脑插件，
 * 电脑面板可见，也用于远端优化。约束：</p>
 * <ul>
 *   <li>只在插件开关打开 + 已配对时发送；关掉立刻停（不缓存报复）；</li>
 *   <li>只发结构化摘要：工具名/成败/耗时/错误前 120 字；**不含**聊天正文、文件内容、剪贴板；</li>
 *   <li>队列上限 50 条、每秒最多一批；发送失败直接丢弃（诊断数据不值得重试堆积）。</li>
 * </ul>
 */
public final class PhoneEventReporter {

    private static final String TAG = "PhoneEventReporter";
    private static final int MAX_QUEUE = 50;
    private static final int MAX_BATCH = 20;
    private static final long MIN_INTERVAL_MS = 1500;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<JSONObject> QUEUE = new ArrayDeque<>();
    private static Context appContext;
    private static long lastFlushAt;
    private static volatile boolean flushing;
    /** 错误去重：where|message → 上次上报时间（60s 内同一条只报一次）。 */
    private static final Map<String, Long> ERROR_SEEN = new LinkedHashMap<>();

    private PhoneEventReporter() {
    }

    public static void init(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    // ==================== 生产者 ====================

    public static void tool(String name, boolean ok, long elapsedMs, String error) {
        try {
            JSONObject d = new JSONObject();
            d.put("name", name == null ? "" : name);
            d.put("ok", ok);
            d.put("ms", Math.max(0, elapsedMs));
            if (error != null && !error.isEmpty()) d.put("error", cut(error, 120));
            enqueue("tool", d);
        } catch (Throwable ignored) {
        }
    }

    public static void agent(String state, int round) {
        try {
            JSONObject d = new JSONObject();
            d.put("state", state == null ? "" : state);
            d.put("round", Math.max(0, round));
            enqueue("agent", d);
        } catch (Throwable ignored) {
        }
    }

    public static void error(String where, String message) {
        try {
            String key = String.valueOf(where) + "|" + String.valueOf(message);
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                Long last = ERROR_SEEN.get(key);
                if (last != null && now - last < 60_000L) return;
                ERROR_SEEN.put(key, now);
                if (ERROR_SEEN.size() > 200) {
                    Iterator<Map.Entry<String, Long>> it = ERROR_SEEN.entrySet().iterator();
                    while (it.hasNext()) {
                        if (now - it.next().getValue() > 60_000L) it.remove();
                    }
                }
            }
            JSONObject d = new JSONObject();
            d.put("where", where == null ? "" : where);
            d.put("message", cut(message, 200));
            enqueue("error", d);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 队列 / 发送 ====================

    private static void enqueue(String type, JSONObject data) {
        Context ctx = appContext;
        if (ctx == null) return;
        if (!RemoteDshTool.isPluginEnabled(ctx)) return;
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", type);
            ev.put("ts", System.currentTimeMillis());
            ev.put("data", data);
            synchronized (LOCK) {
                if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
                QUEUE.addLast(ev);
            }
            flushAsync();
        } catch (Throwable ignored) {
        }
    }

    private static void flushAsync() {
        long now = System.currentTimeMillis();
        if (flushing || now - lastFlushAt < MIN_INTERVAL_MS) return;
        lastFlushAt = now;
        flushing = true;
        Thread t = new Thread(() -> {
            try {
                flushOnce();
            } finally {
                flushing = false;
            }
        }, "phone-event-flush");
        t.setDaemon(true);
        t.start();
    }

    private static void flushOnce() {
        Context ctx = appContext;
        if (ctx == null) return;
        if (!RemoteDshTool.isPluginEnabled(ctx)) {
            synchronized (LOCK) {
                QUEUE.clear();
            }
            return;
        }
        JSONArray batch = new JSONArray();
        synchronized (LOCK) {
            int n = 0;
            while (!QUEUE.isEmpty() && n < MAX_BATCH) {
                batch.put(QUEUE.pollFirst());
                n++;
            }
        }
        if (batch.length() == 0) return;
        try {
            if (!post(ctx, batch)) {
                // 诊断数据不值得重试；失败就丢，避免离线时越堆越多
                Log.d(TAG, "事件上报失败，丢弃 " + batch.length() + " 条");
            }
        } catch (Throwable t) {
            Log.d(TAG, "事件上报异常: " + t.getMessage());
        }
    }

    private static boolean post(Context ctx, JSONArray events) {
        String base = RemoteDshTool.configValue(ctx, "base_url");
        if (base == null || base.trim().isEmpty()) return false;
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String token = RemoteDshTool.configValue(ctx, "token");
        if (token == null || token.trim().isEmpty()) {
            token = RemoteDshTool.configValue(ctx, "main_token");
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("action", "phone_event");
            body.put("events", events);
            body.put("platform", "android");
            body.put("app_version", appVersion(ctx));
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            // 与 RemoteDshTool.postSessionRaw 同一路径（/session，不是 /v2/session；
            // 后者是 prompt 通道，会把 phone_event 当对话去执行 agent）
            conn = (HttpURLConnection) new URL(b + "/session").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String appVersion(Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ');
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }
}
