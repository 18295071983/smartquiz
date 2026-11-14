package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 封口判据（2026-11-15，§19 尾段增量投影）。
 *
 * <p>这是纯 JVM 的规则层：段怎么切、什么时候算封口。它决定了"已完成段重建必须为 0"
 * 的边界 —— 最后一段在流式期间永远是活动段（正文随时可能追加），只有"不再是最后一段"
 * 或"回合结束"才封口。</p>
 */
public class SegmentSealPolicyTest {

    @Test
    public void segmentCountBySteps() {
        assertEquals(0, SegmentSealPolicy.segmentCount(0));
        assertEquals(1, SegmentSealPolicy.segmentCount(1));
        assertEquals(1, SegmentSealPolicy.segmentCount(6));
        assertEquals(2, SegmentSealPolicy.segmentCount(7));
        assertEquals(2, SegmentSealPolicy.segmentCount(12));
        assertEquals(3, SegmentSealPolicy.segmentCount(13));
        assertEquals(9, SegmentSealPolicy.segmentCount(51));
    }

    @Test
    public void segmentRangesAreSixSteps() {
        assertEquals(0, SegmentSealPolicy.fromStep(0));
        assertEquals(6, SegmentSealPolicy.toStep(0));
        assertEquals(6, SegmentSealPolicy.fromStep(1));
        assertEquals(12, SegmentSealPolicy.toStep(1));
        assertEquals(12, SegmentSealPolicy.fromStep(2));
        // 步序号 → 段号
        assertEquals(0, SegmentSealPolicy.segmentIndex(0));
        assertEquals(0, SegmentSealPolicy.segmentIndex(5));
        assertEquals(1, SegmentSealPolicy.segmentIndex(6));
        assertEquals(2, SegmentSealPolicy.segmentIndex(12));
    }

    @Test
    public void lastSegmentIsActiveWhileWriting() {
        // 6 步：只有一段，流式中不封口
        assertFalse(SegmentSealPolicy.isSealed(0, 0, true));
        // 7 步：第 0 段封口，第 1 段活动
        assertTrue(SegmentSealPolicy.isSealed(0, 1, true));
        assertFalse(SegmentSealPolicy.isSealed(1, 1, true));
        // 回合结束：最后一段也封口
        assertTrue(SegmentSealPolicy.isSealed(1, 1, false));
    }

    @Test
    public void negativeSegmentIsSealed() {
        assertTrue(SegmentSealPolicy.isSealed(-1, 0, true));
        assertEquals(-1, SegmentSealPolicy.lastSegmentIndex(0));
    }
}
