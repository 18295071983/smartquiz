package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * UI 呈现节拍器（2026-11-18）：两次投影之间的最小间隔与边界。
 *
 * <p>这几条边界最容易写错：第一帧必须放行、等于间隔算放行、时间回拨不能被压死、
 * 复位后要能立刻投。</p>
 */
public class PresentationPacerTest {

    @Test
    public void firstPresentationIsAllowedImmediately() {
        PresentationPacer p = new PresentationPacer(120);
        assertTrue(p.canPresent(0));
        assertTrue(p.canPresent(999_999));
        assertEquals(0, p.delayUntilNext(0));
    }

    @Test
    public void blocksWithinIntervalAndAllowsAtBoundary() {
        PresentationPacer p = new PresentationPacer(120);
        p.notePresented(1_000);
        assertFalse("119ms 还不到下一次", p.canPresent(1_119));
        assertTrue("等于间隔就算到点（>= 边界）", p.canPresent(1_120));
        assertTrue(p.canPresent(2_000));
    }

    @Test
    public void delayCountsDownAndNeverNegative() {
        PresentationPacer p = new PresentationPacer(120);
        p.notePresented(1_000);
        assertEquals(120, p.delayUntilNext(1_000));
        assertEquals(70, p.delayUntilNext(1_050));
        assertEquals(0, p.delayUntilNext(1_120));
        assertEquals("时间回拨也不能给负数延迟", 0, p.delayUntilNext(900));
    }

    @Test
    public void resetAllowsFirstFrameAgain() {
        PresentationPacer p = new PresentationPacer(120);
        p.notePresented(1_000);
        assertFalse(p.canPresent(1_050));
        p.reset();
        assertTrue("页面重建后第一帧要能立即投", p.canPresent(1_050));
    }

    @Test
    public void zeroIntervalNeverBlocks() {
        PresentationPacer p = new PresentationPacer(0);
        p.notePresented(1_000);
        assertTrue(p.canPresent(1_000));
        assertEquals(0, p.delayUntilNext(1_000));
    }
}
