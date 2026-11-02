package com.oilquiz.app.ai.chat;

/**
 * "这一次提问要联网"的显式信号（2026-11-02）。
 *
 * <p>来源：对话页工具抽屉里的「🔍 搜索」chip。它以前只会弹一个参数表单、直接执行工具，
 * 和模型是两条平行的路。现在点一下就表示"这条问题请联网"，由模型结合结果回答；
 * 想手填参数仍然长按它。</p>
 *
 * <p>为什么要单开一个信号类而不改 webSearch 开关：`webSearch` 是**服务商能力**
 * （`config.supportsWebSearch`，来自 providers.json），不是用户意图。用户点一次 chip
 * 只该影响**这一条消息**：在线侧用它绕过"实时词意图闸"去预搜索（服务商官方能搜时仍走官方，
 * 不白烧秘塔额度），本地引擎用它在本轮用户消息尾部加一句"先用 network_search 查"。
 * 两个执行路径各自消费一次。</p>
 *
 * <p>带 {@link #TTL_MS} 过期时间：信号只对紧接着的那次发送有效，避免"点了 chip 但那条消息
 * 没发出去"把标记留到十分钟后的下一轮，悄悄多烧一次搜索。读取用 {@link #consume()}（取走即清）。</p>
 */
public final class LiveSearchHint {

    /** 信号有效期：从点击到真正发请求一般不到几秒；给到 20 秒容忍排队/二次确认。 */
    static final long TTL_MS = 20_000L;

    private static final java.util.concurrent.atomic.AtomicLong REQUESTED_AT =
            new java.util.concurrent.atomic.AtomicLong(0L);

    private LiveSearchHint() {
    }

    /** chip 点击时置位：这一次提问要联网。 */
    public static void request() {
        REQUESTED_AT.set(System.currentTimeMillis());
    }

    /** 是否存在未过期的信号（不清除），供只想 peek 的地方判断。 */
    public static boolean isPending() {
        long at = REQUESTED_AT.get();
        return at > 0 && System.currentTimeMillis() - at <= TTL_MS;
    }

    /**
     * 取走信号：命中且未过期返回 true，并清零（只生效一次）；过期或没设过返回 false。
     * 两条执行路径（在线预搜索 / 本地引擎）谁先跑谁消费，不会重复生效。
     */
    public static boolean consume() {
        long at = REQUESTED_AT.getAndSet(0L);
        return at > 0 && System.currentTimeMillis() - at <= TTL_MS;
    }

    /** 测试/复位用：清空信号。 */
    public static void clear() {
        REQUESTED_AT.set(0L);
    }
}
