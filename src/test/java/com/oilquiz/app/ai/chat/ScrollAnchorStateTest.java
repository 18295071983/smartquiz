package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 滚动锚点补偿逻辑（{@link ScrollAnchorState}）单测（2026-10-25）。
 *
 * <p>钉住“视口上方高度变化要补、数据形态变了不能乱补”的口径：</p>
 * <ol>
 *   <li>没锚点 → 要求重捕；</li>
 *   <li>同一项 top 在容差内不变 → 不补；越界变化 → 返回 dy；</li>
 *   <li>item 数变化（插入/删除/加载更早历史）→ 重捕，不乱补；</li>
 *   <li>同 position 换了消息（稳定键不同）→ 重捕；</li>
 *   <li>补偿后同步锚点，同一变化不会被补第二次。</li>
 * </ol>
 */
public class ScrollAnchorStateTest {

    private static final int TOLERANCE = 1;

    @Test
    public void noAnchorAsksForRecapture() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        assertFalse(s.isValid());
        assertEquals(ScrollAnchorState.RECAPTURE, s.computeDelta(100, 10, "m1"));
    }

    @Test
    public void sameTopNeedsNoRestore() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 120, 20, "m3");
        assertTrue(s.isValid());
        assertEquals(0, s.computeDelta(120, 20, "m3"));
        assertEquals(0, s.computeDelta(121, 20, "m3"));
        assertEquals(0, s.computeDelta(119, 20, "m3"));
    }

    @Test
    public void itemAboveGrewReturnsPositiveDelta() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 120, 20, "m3");
        // 上方内容长高 300px，同一项的 top 被推到 420 → 需要 scrollBy(0, +300) 拉回
        assertEquals(300, s.computeDelta(420, 20, "m3"));
    }

    @Test
    public void itemAboveCollapsedReturnsNegativeDelta() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 520, 20, "m3");
        assertEquals(-300, s.computeDelta(220, 20, "m3"));
    }

    @Test
    public void countChangeAsksForRecapture() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 120, 20, "m3");
        assertEquals(ScrollAnchorState.RECAPTURE, s.computeDelta(700, 21, "m3"));
        assertEquals(ScrollAnchorState.RECAPTURE, s.computeDelta(700, 19, "m3"));
    }

    @Test
    public void keyChangeAsksForRecapture() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 120, 20, "m3");
        assertEquals(ScrollAnchorState.RECAPTURE, s.computeDelta(120, 20, "other-message"));
    }

    @Test
    public void nullKeysAreAccepted() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 120, 20, null);
        assertEquals(0, s.computeDelta(120, 20, null));
        assertEquals(150, s.computeDelta(270, 20, null));
    }

    @Test
    public void applyRestorePreventsDoubleCompensation() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 120, 20, "m3");
        int delta = s.computeDelta(420, 20, "m3");
        assertEquals(300, delta);
        s.applyRestore(delta);
        // 补偿后同一 top 不应再触发（避免下一帧把同一变化补第二遍）
        assertEquals(0, s.computeDelta(420, 20, "m3"));
    }

    @Test
    public void invalidateStopsRestore() {
        ScrollAnchorState s = new ScrollAnchorState(TOLERANCE);
        s.capture(3, 120, 20, "m3");
        s.invalidate();
        assertEquals(ScrollAnchorState.RECAPTURE, s.computeDelta(500, 20, "m3"));
    }

    @Test
    public void toleranceIsInclusiveAndClampedNonNegative() {
        ScrollAnchorState s = new ScrollAnchorState(-5);   // 构造时钳到 0
        s.capture(1, 50, 5, "m1");
        assertEquals(0, s.computeDelta(50, 5, "m1"));
        assertEquals(1, s.computeDelta(51, 5, "m1"));
    }
}
