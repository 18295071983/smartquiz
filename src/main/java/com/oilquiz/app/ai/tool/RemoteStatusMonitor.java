package com.oilquiz.app.ai.tool;

import android.content.Context;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电脑端 {@code /status} + {@code /v2/usage} 的轮询缓存（2026-11-13）。
 *
 * <p>状态栏需要"电脑忙不忙、有没有待审批、上下文用了多少"，这些只在 HTTP 接口里，
 * 而 SSE 只推会话事件。这里做三件事：<b>节流</b>（默认 15s 一次）、<b>单飞</b>
 * （同一时刻只有一个请求在飞）、<b>合并</b>（同一轮里再取一次 usage，把 token/上下文/模型
 * 并进同一份快照，失败保留上次快照——一次网络抖动不该把状态条清空）。</p>
 *
 * <p>回调在后台线程，调用方负责切主线程。</p>
 */
public final class RemoteStatusMonitor {

    private static volatile RemoteStatusSnapshot cache;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static volatile long lastAttemptAt;

    private RemoteStatusMonitor() {
    }

    /** 最近一次成功快照；从未拉过或已清空时为 null。 */
    public static RemoteStatusSnapshot get() {
        return cache;
    }

    /** 兼容旧调用：不带会话 id，只拉 /status。 */
    public static void refreshAsync(Context c, long minIntervalMs, Runnable onDone) {
        refreshAsync(c, "", minIntervalMs, onDone);
    }

    /**
     * 拉一次 {@code /status}（+ 可选 {@code /v2/usage}），节流 + 单飞。
     *
     * @param sessionId     要取 token/上下文的会话（空则只拉 status）
     * @param minIntervalMs 距上次尝试至少多久才真的发请求
     * @param onDone        结束回调（后台线程；无论成功失败都会调）
     */
    public static void refreshAsync(Context c, String sessionId, long minIntervalMs, Runnable onDone) {
        if (c == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < Math.max(1000L, minIntervalMs)) return;
        if (!inFlight.compareAndSet(false, true)) return;
        lastAttemptAt = now;
        final Context app = c.getApplicationContext();
        final String sid = sessionId == null ? "" : sessionId.trim();
        Thread t = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                Map<String, Object> m = new RemoteSdk(app).status();
                long rtt = System.currentTimeMillis() - t0;
                RemoteStatusSnapshot snap = RemoteStatusSnapshot.parse(m, System.currentTimeMillis(), rtt);
                // C6：插件在 /status 里报了 app_release 就比对一次（只提示，不自动装）
                try {
                    Object rel = (m instanceof java.util.Map) ? ((java.util.Map<?, ?>) m).get("app_release") : null;
                    if (rel instanceof java.util.Map) {
                        RemoteAppUpdate.checkAndPrompt(c, new org.json.JSONObject((java.util.Map<?, ?>) rel));
                    }
                } catch (Throwable ignored) { /* 更新检查失败不影响状态刷新 */ }
                if (!sid.isEmpty()) {
                    try {
                        // 插件 v8.1+ 才有 /v2/usage；旧插件 404 时抛异常，保持 snap 原样
                        Map<String, Object> u = new RemoteSdk(app).usage(sid);
                        if (u != null) {
                            int code = 0;
                            Object c0 = u.get("_http_code");
                            if (c0 instanceof Number) code = ((Number) c0).intValue();
                            if (code < 400) snap = snap.withUsage(u, rtt);
                        }
                    } catch (Throwable ignored) {
                        // 忽略：旧插件没有该接口，状态条少两行而已
                    }
                }
                cache = snap;
            } catch (Throwable ignored) {
                // 保留上次快照：失败不清空
            } finally {
                inFlight.set(false);
                if (onDone != null) {
                    try {
                        onDone.run();
                    } catch (Throwable ignored) {
                        // 回调失败不影响后续轮询
                    }
                }
            }
        }, "remote-status");
        t.setDaemon(true);
        t.start();
    }

    /** 断开/清配置时调用：别让状态栏继续显示上一台电脑的忙碌状态。 */
    public static void clear() {
        cache = null;
        lastAttemptAt = 0L;
    }
}
