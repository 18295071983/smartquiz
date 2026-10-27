package com.oilquiz.app.ai.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 流式请求的<b>瞬时错误重试口径</b>（纯函数，可单测）。
 *
 * <p>业界口径（OpenAI Python/JS SDK README「Retries」）：连接错误、408/409/429/5xx 默认重试 2 次、
 * 短指数退避；<b>流式只能在"还没吐出任何内容"时重发</b>——SSE 一旦开始，服务商不支持从中间续传，
 * 客户端重试只会把开头那段再吐一遍。Anthropic SDK 同理（重试整条请求 + 退避）。
 *
 * <p>因此本类只回答两个问题：这条错误算不算瞬时、第 N 次重试该等多久。
 * <b>"有没有吐过字"由调用方判断</b>（引擎侧跟踪），本类不掺和。</p>
 */
public final class StreamRetryPolicy {

    /** 最多重试次数：与 OpenAI SDK 默认一致（2 次） */
    public static final int MAX_RETRIES = 2;

    /** 退避基数与上限 */
    private static final long BASE_DELAY_MS = 500L;
    private static final long MAX_DELAY_MS = 4_000L;

    private StreamRetryPolicy() {
    }

    /**
     * 是否属于"值得重发一次"的瞬时错误。
     *
     * <p>刻意保守：只认连接层抖动、超时、429/5xx 与明显的空流结束；401/403/404/400 这类
     * 配置或请求错误重试只会再失败一次、还多等几秒，必须原样报给用户。</p>
     */
    public static boolean isTransient(String errorText) {
        if (errorText == null) return false;
        String e = errorText.toLowerCase(java.util.Locale.ROOT);
        if (e.trim().isEmpty()) return false;

        // 明确不该重试：鉴权/参数/不存在（先判，避免 "http 400" 命中 5xx 之外的模糊片段）
        if (e.contains("http 400") || e.contains("http 401") || e.contains("http 403")
                || e.contains("http 404") || e.contains("http 422")
                || e.contains("unauthorized") || e.contains("invalid api key")
                || e.contains("模型不存在") || e.contains("参数错误")) {
            return false;
        }

        // 连接层抖动（真机见过的第一条：Software caused connection abort）
        if (e.contains("connection abort") || e.contains("connection reset")
                || e.contains("broken pipe") || e.contains("software caused")
                || e.contains("unexpected end of stream") || e.contains("stream closed")
                || e.contains("premature eof") || e.contains("unexpected eof")
                || e.contains("socket") || e.contains("connect timed out")
                || e.contains("connection timed out") || e.contains("handshake")) {
            return true;
        }
        // 超时（含 idle 超时文案）
        if (e.contains("timeout") || e.contains("timed out") || e.contains("超时")) {
            return true;
        }
        // 限流与服务端抖动
        if (e.contains("http 408") || e.contains("http 409") || e.contains("http 429")
                || e.contains("http 500") || e.contains("http 502")
                || e.contains("http 503") || e.contains("http 504")
                || e.contains("overloaded") || e.contains("rate limit")
                || e.contains("too many requests")) {
            return true;
        }
        return false;
    }

    /**
     * 第 {@code retryIndex} 次重试前的等待（从 1 开始）：500ms、1000ms，加 0~250ms 抖动，封顶 4s。
     * 抖动是为了避免多路并发同时重发再撞一次限流。
     */
    public static long backoffMs(int retryIndex) {
        int idx = Math.max(1, retryIndex);
        long base = BASE_DELAY_MS * (1L << Math.min(idx - 1, 3)); // 500, 1000, 2000, 4000
        long jitter = ThreadLocalRandom.current().nextLong(0, 250);
        return Math.min(MAX_DELAY_MS, base + jitter);
    }
}
