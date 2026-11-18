package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 断档判定与背压硬线（2026-11-15，§14 事件分流 B/C）。
 *
 * <p>按 dsh-Go 的口径：重同步以<b>连续性</b>为主、数量阈值只当辅助；背压三级是
 * "合并 → 丢弃 → 断"，没有"切拉模式"这一档。这里把每个边界钉住。</p>
 */
public class EventFlowPolicyTest {
    // ===== B：断档 → 快照 =====

    @Test
    public void continuousSeqIsOk() {
        EventFlowPolicy p = new EventFlowPolicy();
        for (int i = 1; i <= 100; i += 1) {
            assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(i));
        }
        assertEquals(100, p.lastSeq());
        assertEquals(0, p.gapCount());
        assertEquals(0, p.resyncCount());
    }

    @Test
    public void duplicateAndOlderSeqAreNotGaps() {
        EventFlowPolicy p = new EventFlowPolicy();
        p.noteSeq(10);
        assertEquals(EventFlowPolicy.Decision.DUPLICATE, p.noteSeq(10));
        assertEquals(EventFlowPolicy.Decision.DUPLICATE, p.noteSeq(7));
        assertEquals(10, p.lastSeq());
        assertEquals(0, p.gapCount());
    }

    @Test
    public void syntheticFramesDoNotTouchWatermark() {
        EventFlowPolicy p = new EventFlowPolicy();
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(0));
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(-1));
        assertEquals(-1, p.lastSeq());
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(5));
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(0));
        assertEquals(5, p.lastSeq());
    }

    @Test
    public void oneSmallJumpIsToleratedButCounted() {
        EventFlowPolicy p = new EventFlowPolicy();
        p.noteSeq(1);
        // 单条被 caps 过滤 / 乱序补发都会造成这种小跳：先照常归约，只计数
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(3));
        assertEquals(1, p.gapCount());
        assertEquals(0, p.resyncCount());
        // 紧接着连续：不连续计数清零
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(4));
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(6));
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(7));
        assertEquals(0, p.resyncCount());
    }

    @Test
    public void twoConsecutiveJumpsMeanRealBreak() {
        EventFlowPolicy p = new EventFlowPolicy();
        p.noteSeq(1);
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(3));       // 第一次小跳
        assertEquals(EventFlowPolicy.Decision.RESYNC, p.noteSeq(5));   // 又一次：真断了
        assertEquals(1, p.resyncCount());
    }

    @Test
    public void bigJumpResyncsImmediately() {
        EventFlowPolicy p = new EventFlowPolicy();
        p.noteSeq(1000);
        assertEquals(EventFlowPolicy.Decision.RESYNC, p.noteSeq(1204)); // 缺 203 条 > 200
        assertEquals(1, p.resyncCount());
    }

    @Test
    public void gapAtThresholdIsNotEnoughAlone() {
        EventFlowPolicy p = new EventFlowPolicy();
        p.noteSeq(1000);
        // 缺 200 条：等于阈值，先当一次小跳（下一跳再连就重建）
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(1201));
    }

    @Test
    public void reorderedBackfillDoesNotTriggerResync() {
        EventFlowPolicy p = new EventFlowPolicy();
        p.noteSeq(1);
        p.noteSeq(5);                       // 小跳
        assertEquals(EventFlowPolicy.Decision.DUPLICATE, p.noteSeq(2));
        assertEquals(EventFlowPolicy.Decision.DUPLICATE, p.noteSeq(3));
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(6));
        assertEquals(0, p.resyncCount());
    }

    @Test
    public void resetAfterSnapshotAlignsWatermark() {
        EventFlowPolicy p = new EventFlowPolicy();
        p.noteSeq(1);
        p.noteSeq(3);
        p.resetAfterSnapshot(900);
        assertEquals(900, p.lastSeq());
        // 重建后第一条紧接着：不能被"上一条没清掉的不连续"再判一次
        assertEquals(EventFlowPolicy.Decision.OK, p.noteSeq(901));
        assertEquals(0, p.resyncCount());
    }

    @Test
    public void stateFrameBehindCheckUsesSameThreshold() {
        EventFlowPolicy p = new EventFlowPolicy();
        assertTrue(p.appliedBehind(1301, 1000));
        assertFalse(p.appliedBehind(1200, 1000));   // 等于阈值不算
        assertFalse(p.appliedBehind(1301, 0));      // 本地未知时不判
    }

    @Test
    public void thresholdsAreConfigurable() {
        EventFlowPolicy p = new EventFlowPolicy(50, 500, 200);
        p.noteSeq(1);
        assertEquals(EventFlowPolicy.Decision.RESYNC, p.noteSeq(53));   // 缺 51 > 50
    }

    // ===== C：背压硬线 =====

    @Test
    public void hardLineOnCumulativeDrops() {
        EventFlowPolicy p = new EventFlowPolicy();
        assertFalse(p.hardLine(499, 0));
        assertTrue(p.hardLine(500, 0));
        assertTrue(p.hardLine(1200, 0));
    }

    @Test
    public void hardLineOnSingleBurst() {
        EventFlowPolicy p = new EventFlowPolicy();
        assertFalse(p.hardLine(0, 199));
        assertTrue(p.hardLine(0, 200));   // 一批就丢 200：别等累计，直接断
    }

    @Test
    public void hardLineRespectsInjectedThresholds() {
        EventFlowPolicy p = new EventFlowPolicy(200, 50, 20);
        assertTrue(p.hardLine(50, 0));
        assertTrue(p.hardLine(0, 20));
        assertEquals(50, p.hardDropLimit());
        assertEquals(20, p.hardDropBurst());
        assertEquals(200, p.snapshotGap());
    }
}
