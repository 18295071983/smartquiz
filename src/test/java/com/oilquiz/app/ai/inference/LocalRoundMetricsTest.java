package com.oilquiz.app.ai.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 本地单轮账本的纯算术契约（LOCAL-ROUND-METRICS，2026-11-13）。
 *
 * <p>为什么值得单测：这两个时间戳的分界（第一条流式事件）决定了 prefill/decode 的拆分，
 * 而那是判断"慢在 prompt eval 还是慢在生成"的唯一依据。写错不会崩，只会让人对着错误的
 * 结论去优化——所以边界（没有事件、只有终态、decode 为 0）都钉死。</p>
 */
public class LocalRoundMetricsTest {

    @Test
    public void prefillEndsAtFirstEventAndDecodeRunsToFinish() {
        LocalRoundMetrics m = new LocalRoundMetrics("AgentLoopEngine");
        m.startedAt = 1000L;
        m.firstEventAt = 1500L;   // prefill 500ms
        m.finishedAt = 4500L;     // decode 3000ms

        assertEquals(500L, m.prefillMs());
        assertEquals(3000L, m.decodeMs());
    }

    @Test
    public void noEventsAtAllMeansAllTimeIsPrefill() {
        LocalRoundMetrics m = new LocalRoundMetrics("ModelExecutionBridge");
        m.startedAt = 2000L;
        m.finishedAt = 8000L;     // 整轮没有任何事件（例如直接失败）

        assertEquals("没有事件时不能出现『总耗时 6s 但 prefill 0』的假象", 6000L, m.prefillMs());
        assertEquals(0L, m.decodeMs());
    }

    @Test
    public void formatCarriesTokensTimingsAndTps() {
        String line = LocalRoundMetrics.format("AgentLoopEngine", 500, 2000, 4096, 100, "kv=PARTIAL matched=512");

        assertTrue(line.startsWith("ROUND-STATS route=AgentLoopEngine"));
        assertTrue(line.contains("prompt=4096"));
        assertTrue(line.contains("gen=100"));
        assertTrue(line.contains("prefill_ms=500"));
        assertTrue(line.contains("decode_ms=2000"));
        // 100 token / 2s = 50.0 t/s
        assertTrue(line, line.contains("tps=50.0"));
        assertTrue(line.contains("kv=PARTIAL matched=512"));
    }

    @Test
    public void zeroDecodeDoesNotDivideByZero() {
        String line = LocalRoundMetrics.format("bridge", 300, 0, 100, 0, "");
        assertTrue(line, line.contains("tps=0.0"));
        assertTrue("空 KV 摘要不应留下尾部空格", line.endsWith("tps=0.0"));
    }

    @Test
    public void nullRouteFallsBackToUnknown() {
        assertEquals("unknown", new LocalRoundMetrics(null).route);
        assertEquals("unknown", new LocalRoundMetrics("").route);
        assertTrue(LocalRoundMetrics.format(null, 0, 0, 0, 0, null).contains("route=unknown"));
    }
}
