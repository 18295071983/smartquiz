package com.oilquiz.app.ai.remote;

import java.util.Random;

/**
 * WebSocket 连接周期重试策略（2026-11-15，§17 WS-RETRY）。
 *
 * <p>为什么要有这个类：手机端以前在 {@code RemoteChannel.run()} 开头只试 <b>3 次</b> WS，
 * 失败就永久退回 SSE，<b>事后不再升级</b>。插件重载 / 网络抖动把连接踢掉之后，
 * 手机再也回不到 WS（真机：插件重载后 {@code ws_clients} 从 2 变 0，只有重启 App 才恢复）。</p>
 *
 * <p>现在的语义是<b>周期重试、永不放弃</b>：1s → 2s → 4s → 8s → 16s → 30s 封顶，
 * 每次带 ±20% 抖动（避免断网恢复后所有设备同一秒砸向服务端）。抖动后的实际等待
 * 最大约 36s，仍在验收口径「≤40s 内回到 WS」之内。</p>
 *
 * <p>纯 JVM：不依赖 Android / OkHttp，可直接跑单元测试。</p>
 */
public final class WsRetryPolicy {

    /** 首次重试的基准延迟：1s */
    public static final long BASE_DELAY_MS = 1000L;
    /** 基准延迟封顶：30s（抖动后实际最大约 36s） */
    public static final long MAX_DELAY_MS = 30000L;
    /** 抖动幅度：±20% */
    public static final double JITTER_RATIO = 0.2d;

    private final long baseMs;
    private final long maxMs;
    private final double jitterRatio;
    /** 0 = 不设总时长上限（默认，周期重试永不放弃）；>0 = 本轮断线重试窗口 */
    private final long retryWindowMs;
    private final Random random;

    /** 连续失败次数（0 = 还没失败 / 刚 reset） */
    private int failures;

    public WsRetryPolicy() {
        this(BASE_DELAY_MS, MAX_DELAY_MS, JITTER_RATIO, 0L, new Random());
    }

    /** 测试/特殊链路用：可关抖动、指定随机源。 */
    public WsRetryPolicy(long baseMs, long maxMs, double jitterRatio, Random random) {
        this(baseMs, maxMs, jitterRatio, 0L, random);
    }

    /**
     * @param retryWindowMs 本轮断线最多重试多久；0 = 不限（产品默认）
     */
    public WsRetryPolicy(long baseMs, long maxMs, double jitterRatio, long retryWindowMs, Random random) {
        this.baseMs = Math.max(1L, baseMs);
        this.maxMs = Math.max(this.baseMs, maxMs);
        this.jitterRatio = Math.max(0d, Math.min(1d, jitterRatio));
        this.retryWindowMs = Math.max(0L, retryWindowMs);
        this.random = random == null ? new Random() : random;
    }

    /**
     * 第 {@code attempt} 次重试的<b>基准</b>延迟（不含抖动）：1s→2s→4s→8s→16s→30s→30s…
     *
     * @param attempt 从 1 开始（≤0 按 1 处理）
     */
    public long baseDelayMs(int attempt) {
        int n = Math.max(1, attempt);
        long delay = baseMs;
        for (int i = 1; i < n && delay < maxMs; i += 1) {
            delay = delay > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : delay * 2;
            if (delay > maxMs) delay = maxMs;
        }
        return Math.min(maxMs, delay);
    }

    /**
     * 第 {@code attempt} 次重试的实际等待（基准 ± jitter，最小 1ms）。
     * 默认 ±20%：1s 那次在 800–1200ms，30s 封顶那次在 24000–36000ms。
     */
    public long nextDelayMs(int attempt) {
        long base = baseDelayMs(attempt);
        if (jitterRatio <= 0d) return base;
        double offset = (random.nextDouble() * 2d - 1d) * jitterRatio;
        long delay = Math.round(base * (1d + offset));
        return Math.max(1L, delay);
    }

    /** 记一次失败，返回本次重试序号（从 1 开始）。 */
    public synchronized int noteFailure() {
        failures += 1;
        return failures;
    }

    /** 按内部失败次数算下一次等待（不推进计数）。 */
    public synchronized long nextDelayMs() {
        return nextDelayMs(failures);
    }

    /** 当前连续失败次数。 */
    public synchronized int failures() {
        return failures;
    }

    /** 连接成功 / 主动停止后清零：下一条断线仍从 1s 起。 */
    public synchronized void reset() {
        failures = 0;
    }

    /**
     * 本轮 WS 不可用已经持续 {@code outageMs}，是否还应继续重试。
     *
     * <p>产品默认<b>永远返回 true</b>（{@code retryWindowMs=0}）—— 上次就是「试 3 次就放弃」
     * 才坏掉的。带窗口的构造仅供测试/未来限流护栏使用。</p>
     */
    public boolean shouldRetry(long outageMs) {
        if (retryWindowMs <= 0L) return true;
        return outageMs >= 0L && outageMs < retryWindowMs;
    }

    /** 基准延迟封顶值（抖动前）。 */
    public long maxDelayMs() {
        return maxMs;
    }
}
