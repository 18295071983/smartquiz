package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * WS 周期重试策略（2026-11-15，§17 WS-RETRY）。
 *
 * <p>钉住四件事：退避序列 1s→2s→4s→8s→16s→30s、封顶不再翻倍、±20% 抖动范围、
 * 连接成功后 {@code reset()} 从 1s 重新开始。</p>
 */
public class WsRetryPolicyTest {

    private static WsRetryPolicy exact() {
        // 关抖动，便于断言精确序列
        return new WsRetryPolicy(1000L, 30000L, 0d, new Random(7L));
    }

    @Test
    public void backoffDoublesFromOneSecond() {
        WsRetryPolicy p = exact();
        assertEquals(1000L, p.nextDelayMs(1));
        assertEquals(2000L, p.nextDelayMs(2));
        assertEquals(4000L, p.nextDelayMs(3));
        assertEquals(8000L, p.nextDelayMs(4));
        assertEquals(16000L, p.nextDelayMs(5));
    }

    @Test
    public void delayCapsAtThirtySeconds() {
        WsRetryPolicy p = exact();
        assertEquals(30000L, p.nextDelayMs(6));
        assertEquals(30000L, p.nextDelayMs(7));
        assertEquals(30000L, p.nextDelayMs(100));
        // 非法的 attempt 也按第 1 次处理，不能算出 0/负数
        assertEquals(1000L, p.nextDelayMs(0));
        assertEquals(1000L, p.nextDelayMs(-5));
    }

    @Test
    public void jitterStaysWithinTwentyPercent() {
        WsRetryPolicy p = new WsRetryPolicy(1000L, 30000L, 0.2d, new Random(42L));
        boolean sawDifferent = false;
        long first = p.nextDelayMs(1);
        for (int i = 0; i < 200; i += 1) {
            for (int attempt = 1; attempt <= 8; attempt += 1) {
                long base = p.baseDelayMs(attempt);
                long delay = p.nextDelayMs(attempt);
                assertTrue("第 " + attempt + " 次延迟 " + delay + " 低于 -20%",
                        delay >= Math.round(base * 0.8d));
                assertTrue("第 " + attempt + " 次延迟 " + delay + " 高于 +20%",
                        delay <= Math.round(base * 1.2d));
                if (attempt == 1 && delay != first) sawDifferent = true;
            }
        }
        assertTrue("抖动必须真的产生不同取值（否则是没加抖动）", sawDifferent);
        // 30s 那档抖动后最坏 36s，仍在验收口径 40s 内
        assertEquals(30000L, p.baseDelayMs(8));
    }

    @Test
    public void jitteredCapNeverExceedsThirtySixSeconds() {
        WsRetryPolicy p = new WsRetryPolicy(1000L, 30000L, 0.2d, new Random(2026L));
        for (int i = 0; i < 1000; i += 1) {
            long delay = p.nextDelayMs(42);
            assertTrue(delay >= 24000L);
            assertTrue(delay <= 36000L);
        }
    }

    @Test
    public void resetStartsFromOneSecondAgain() {
        WsRetryPolicy p = exact();
        for (int i = 0; i < 6; i += 1) {
            p.noteFailure();
        }
        assertEquals(6, p.failures());
        assertEquals(30000L, p.nextDelayMs());
        p.reset();
        assertEquals(0, p.failures());
        assertEquals(1000L, p.nextDelayMs());
        assertEquals(1, p.noteFailure());
    }

    @Test
    public void noteFailureDrivesInternalCounter() {
        WsRetryPolicy p = exact();
        assertEquals(1, p.noteFailure());
        assertEquals(1000L, p.nextDelayMs());
        assertEquals(2, p.noteFailure());
        assertEquals(2000L, p.nextDelayMs());
        assertEquals(3, p.noteFailure());
        assertEquals(4000L, p.nextDelayMs());
    }

    @Test
    public void retryNeverGivesUpByDefault() {
        WsRetryPolicy p = exact();
        assertTrue("默认必须永不放弃：1 分钟", p.shouldRetry(60_000L));
        assertTrue("默认必须永不放弃：1 天", p.shouldRetry(24L * 3600 * 1000));
    }

    @Test
    public void boundedWindowIsHonoredWhenConfigured() {
        WsRetryPolicy p = new WsRetryPolicy(1000L, 30000L, 0.2d, 60_000L, new Random(1L));
        assertTrue(p.shouldRetry(0L));
        assertTrue(p.shouldRetry(59_999L));
        assertFalse(p.shouldRetry(60_000L));
    }
}
