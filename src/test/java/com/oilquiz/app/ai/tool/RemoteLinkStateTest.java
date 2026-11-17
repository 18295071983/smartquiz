package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 远程连接状态机的判定口径（2026-11-17）。
 *
 * <p>这段文案是"电脑到底还活着没有"的唯一出口：到 0 没有心跳必须报异常、停止呼吸；
 * 等待/延迟/异常/断开四种状态不能混在一起说，否则用户只能看到干巴巴一个数字。</p>
 */
public class RemoteLinkStateTest {

    private static final long INTERVAL = 5000L;
    private static final long NOW = 1_000_000L;

    @Test
    public void disconnectedSaysWhenLastBeatWas() {
        RemoteLinkState s = RemoteLinkState.evaluate(false, NOW - 12_000L, INTERVAL, NOW, 2);
        assertEquals(RemoteLinkState.State.DISCONNECTED, s.state);
        assertTrue(s.text.contains("已断开"));
        assertTrue(s.text.contains("12s 前最后心跳"));
        assertFalse(s.breathing);
        assertTrue(s.warn);
    }

    @Test
    public void neverHeardAnyBeatIsWaitingNotHealthy() {
        RemoteLinkState s = RemoteLinkState.evaluate(true, 0L, INTERVAL, NOW, 0);
        assertEquals(RemoteLinkState.State.WAITING, s.state);
        assertTrue(s.text.contains("等待电脑心跳"));
        assertTrue(s.breathing);
        assertFalse(s.warn);
    }

    @Test
    public void onTimeBeatIsHealthyWithCountdown() {
        RemoteLinkState s = RemoteLinkState.evaluate(true, NOW - 1_000L, INTERVAL, NOW, 0);
        assertEquals(RemoteLinkState.State.HEALTHY, s.state);
        assertEquals(4, s.remainingSec);          // 5s 周期 - 已过 1s
        assertTrue(s.text.startsWith("连接正常"));
        assertTrue(s.breathing);
        assertFalse(s.warn);
    }

    @Test
    public void lateBeatIsLaggingStillBreathing() {
        // 迟到 7s：超过 interval+1s，但没到 2 个周期(10s)
        RemoteLinkState s = RemoteLinkState.evaluate(true, NOW - 7_000L, INTERVAL, NOW, 0);
        assertEquals(RemoteLinkState.State.LAGGING, s.state);
        assertTrue(s.text.contains("心跳延迟"));
        assertTrue(s.breathing);
        assertTrue(s.warn);
    }

    @Test
    public void missedTwoWindowsIsAbnormalAndStopsBreathing() {
        RemoteLinkState s = RemoteLinkState.evaluate(true, NOW - 12_000L, INTERVAL, NOW, 3);
        assertEquals(RemoteLinkState.State.ABNORMAL, s.state);
        assertTrue(s.text.contains("连接异常"));
        assertTrue(s.text.contains("12s 没有心跳"));
        assertTrue(s.text.contains("已重连 3 次"));
        assertFalse("异常时不能再假装活着", s.breathing);
        assertTrue(s.warn);
    }

    @Test
    public void exactlyOneIntervalLateStillCountsAsHealthyBoundary() {
        // 迟到正好 5s：仍算正常（留 1s 抖动余量），倒计时到 0
        RemoteLinkState s = RemoteLinkState.evaluate(true, NOW - 5_000L, INTERVAL, NOW, 0);
        assertEquals(RemoteLinkState.State.HEALTHY, s.state);
        assertEquals(0, s.remainingSec);
    }
}
