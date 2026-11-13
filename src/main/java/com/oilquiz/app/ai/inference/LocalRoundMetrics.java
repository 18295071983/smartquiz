package com.oilquiz.app.ai.inference;

import java.util.Locale;

/**
 * 本地单轮推理的观测账本（LOCAL-ROUND-METRICS，2026-11-13）。
 *
 * <p><b>为什么要它</b>：本地侧此前只有"聚合的 KV 命中率"（{@code AgentKvCache.hitRate()}），
 * 没有"这一轮到底花了多少"——prompt 多大、prefill 多久、decode 多久、多少 token/s。
 * 而多阶段编排、反思 gate、续跑预算全都要在这笔账上做决策；没有单轮账，
 * "反思值不值得多一次小 generate"这类问题只能靠猜（见
 * {@code docs/local-agent-multi-phase-design-2026-10-25.md} §7 的前置条件）。</p>
 *
 * <p><b>为什么不做成采样器</b>：prefill 与 decode 的分界恰好是"第一个流式事件"——
 * 在它之前的时间都是 prompt eval，之后都是 decode。用两次时间戳就能分开，不需要
 * 定时轮询线程，也不依赖 UI 是否可见。</p>
 *
 * <p>本类只做数据与格式化，不读时钟以外的系统状态（纯函数 {@link #format} 可直接单测）；
 * 真正的接线在 {@link InferenceGateway}。</p>
 */
public final class LocalRoundMetrics {

    /** 发起方（gateway 的 owner：AgentLoopEngine / ModelExecutionBridge / …） */
    public final String route;
    /** 开始时刻（毫秒）。非 final：单测需要钉住时间戳来验 prefill/decode 的拆分 */
    public long startedAt;
    /** 第一条流式事件的时刻；0 表示整轮没有任何事件（例如直接失败） */
    public volatile long firstEventAt;
    /** 终态时刻 */
    public volatile long finishedAt;
    /** 事件计数（token/工具增量都算），仅用于区分"空轮"与"有输出" */
    public volatile int events;

    public LocalRoundMetrics(String route) {
        this.route = route == null || route.isEmpty() ? "unknown" : route;
        this.startedAt = System.currentTimeMillis();
    }

    /** 收到一条流式事件；第一次调用即"prefill 结束"。 */
    public void markEvent() {
        if (firstEventAt == 0L) firstEventAt = System.currentTimeMillis();
        events++;
    }

    /** 终态（complete/error/取消）。 */
    public void markFinished() {
        finishedAt = System.currentTimeMillis();
    }

    /**
     * prefill 耗时：开始 → 第一条事件。
     *
     * <p>整轮没有任何事件时退化为"开始 → 结束"（那一整段确实都耗在 prefill / 失败上），
     * 这样账本里不会出现"总耗时 3s 但 prefill 0"的假象。</p>
     */
    public long prefillMs() {
        long end = firstEventAt != 0L ? firstEventAt : (finishedAt != 0L ? finishedAt : System.currentTimeMillis());
        return Math.max(0L, end - startedAt);
    }

    /** decode 耗时：第一条事件 → 终态。没有事件时为 0。 */
    public long decodeMs() {
        if (firstEventAt == 0L) return 0L;
        long end = finishedAt != 0L ? finishedAt : System.currentTimeMillis();
        return Math.max(0L, end - firstEventAt);
    }

    /**
     * 单行格式化（纯函数，可单测）。
     *
     * @param route          发起方
     * @param prefillMs      prefill 毫秒
     * @param decodeMs       decode 毫秒
     * @param promptTokens   本轮 prompt token（native 记账读回；未知传 0）
     * @param generatedTokens 本轮生成 token（同上）
     * @param kvSummary      追加的 KV 摘要（如 {@code kv=PARTIAL matched=512}），可为空
     */
    public static String format(String route, long prefillMs, long decodeMs,
                                int promptTokens, int generatedTokens, String kvSummary) {
        double tps = decodeMs > 0L ? generatedTokens * 1000.0 / decodeMs : 0.0;
        StringBuilder sb = new StringBuilder(96);
        sb.append("ROUND-STATS route=").append(route == null || route.isEmpty() ? "unknown" : route)
                .append(" prompt=").append(promptTokens)
                .append(" gen=").append(generatedTokens)
                .append(" prefill_ms=").append(prefillMs)
                .append(" decode_ms=").append(decodeMs)
                .append(" tps=").append(String.format(Locale.US, "%.1f", tps));
        if (kvSummary != null && !kvSummary.isEmpty()) sb.append(' ').append(kvSummary);
        return sb.toString();
    }

    /** 实例版：读自己的时间戳，格式同上。 */
    public String describe(int promptTokens, int generatedTokens, String kvSummary) {
        return format(route, prefillMs(), decodeMs(), promptTokens, generatedTokens, kvSummary);
    }
}
