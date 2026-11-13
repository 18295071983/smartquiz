package com.oilquiz.app.ai.tool;

import android.content.Context;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电脑端 {@code /status} 的轮询缓存（2026-11-13）。
 *
 * <p>状态栏需要"电脑忙不忙、有没有待审批"，这些只在 {@code /status} 里，而 SSE 只推会话事件。
 * 这里做两件事：<b>节流</b>（默认 15s 一次，页面刷新再频繁也只发一条）与<b>单飞</b>
 * （同一时刻只有一个请求在飞，失败保留上次快照——一次网络抖动不该把状态条清空）。</p>
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

    /**
     * 拉一次 `/status`（节流 + 单飞）。
     *
     * @param minIntervalMs 距上次尝试至少多久才真的发请求
     * @param onDone        结束回调（后台线程；无论成功失败都会调）
     */
    public static void refreshAsync(Context c, long minIntervalMs, Runnable onDone) {
        if (c == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < Math.max(1000L, minIntervalMs)) return;
        if (!inFlight.compareAndSet(false, true)) return;
        lastAttemptAt = now;
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Map<String, Object> m = new RemoteSdk(app).status();
                cache = RemoteStatusSnapshot.parse(m, System.currentTimeMillis());
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
