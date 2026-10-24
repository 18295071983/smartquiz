package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 流式滚动贴底状态机（{@link StickToBottomState}）单测（2026-10-25）。
 *
 * <p>钉住四条产品口径：</p>
 * <ol>
 *   <li>默认贴底；内容增长（distance 变大但没拖拽）不能把 pin 抖掉；</li>
 *   <li>用户一拖拽就 unpin 且立即停止跟随；</li>
 *   <li>拖拽回到距底阈值内 → 重新 pin；拖离超过阈值 → 保持 unpinned；</li>
 *   <li>显式 pin（发消息/回底按钮）与平滑回底期间暂停跟随。</li>
 * </ol>
 */
public class StickToBottomStateTest {

    private static final int THRESHOLD = 32;

    private static StickToBottomState fresh() {
        return new StickToBottomState(THRESHOLD);
    }

    @Test
    public void defaultsToPinnedAndFollowing() {
        StickToBottomState s = fresh();
        assertTrue("默认贴底", s.isPinned());
        assertTrue("默认应跟随", s.shouldFollow());
        assertEquals(THRESHOLD, s.thresholdPx());
    }

    @Test
    public void contentGrowthDoesNotUnpinWithoutDrag() {
        StickToBottomState s = fresh();
        // 程序化长高会让 distance 瞬间变大，但用户没碰屏幕 → pin 不能被抖掉
        s.onDistance(2000);
        assertTrue(s.isPinned());
        assertTrue(s.shouldFollow());
    }

    @Test
    public void userDragUnpinsImmediatelyAndStopsFollowing() {
        StickToBottomState s = fresh();
        s.onDragStart();
        assertFalse("拖拽即 unpin", s.isPinned());
        assertFalse("拖拽中不跟随", s.shouldFollow());
        s.onDistance(500);
        assertFalse("拖离底部后保持 unpinned", s.isPinned());
        assertFalse(s.shouldFollow());
    }

    @Test
    public void dragBackWithinThresholdRepins() {
        StickToBottomState s = fresh();
        s.onDragStart();
        s.onDistance(500);
        assertFalse(s.isPinned());
        s.onDistance(THRESHOLD);
        assertTrue("拖回距底阈值内应重新 pin", s.isPinned());
        assertFalse("手指还没抬起：拖拽中不跟随", s.shouldFollow());
        s.onScrollIdle();
        assertTrue("松手后恢复跟随", s.shouldFollow());
    }

    @Test
    public void distanceBoundaryIsInclusive() {
        StickToBottomState s = fresh();
        s.onDragStart();
        s.onDistance(THRESHOLD + 1);
        assertFalse("阈值外不 pin", s.isPinned());
        s.onDistance(THRESHOLD);
        assertTrue("恰好等于阈值算贴底", s.isPinned());
    }

    @Test
    public void idleAfterFlingUpStaysUnpinned() {
        StickToBottomState s = fresh();
        s.onDragStart();
        s.onDistance(800);
        s.onScrollIdle();
        assertFalse("惯性结束后仍在阅读位置，不能自动恢复跟随", s.isPinned());
        assertFalse(s.shouldFollow());
    }

    /** 用户接管后，程序化高度收缩（思考收起/工具卡插入）把人"变到"底部，不能再拽回。 */
    @Test
    public void contentShrinkDoesNotRepinAfterUserOptOut() {
        StickToBottomState s = fresh();
        s.onDragStart();
        s.onDistance(1200);
        s.onScrollIdle();
        assertFalse(s.isPinned());
        // 思考正文收起 → 内容高度骤减，distance 变成 0，但用户没自己滚回来
        s.onDistance(0);
        assertFalse("程序化收缩不能解除'用户接管'", s.isPinned());
        assertFalse(s.shouldFollow());
    }

    /** 用户自己甩回底部（松手后的惯性）→ 重新 pin，恢复跟随。 */
    @Test
    public void userFlingBackToBottomRepins() {
        StickToBottomState s = fresh();
        s.onDragStart();
        s.onDistance(800);
        s.onScrollIdle();
        assertFalse(s.isPinned());
        s.onSettling();
        s.onDistance(THRESHOLD);
        assertTrue("用户自己滚回底部应重新 pin", s.isPinned());
        assertTrue(s.shouldFollow());
        s.onScrollIdle();
        assertTrue(s.shouldFollow());
    }

    @Test
    public void explicitPinResumesFollowing() {
        StickToBottomState s = fresh();
        s.onDragStart();
        s.onDistance(900);
        s.onScrollIdle();
        s.pin();
        assertTrue(s.isPinned());
        assertTrue(s.shouldFollow());
    }

    @Test
    public void smoothScrollPausesFrameFollowingUntilIdle() {
        StickToBottomState s = fresh();
        s.pinForSmoothScroll();
        assertTrue("回底动画期间仍算 pinned（按钮可隐藏）", s.isPinned());
        assertFalse("平滑动画中不要再叠逐帧跟随", s.shouldFollow());
        s.onScrollIdle();
        assertTrue("动画结束后恢复跟随", s.shouldFollow());
    }

    @Test
    public void dragStartCancelsSmoothScroll() {
        StickToBottomState s = fresh();
        s.pinForSmoothScroll();
        s.onDragStart();
        assertFalse(s.isPinned());
        assertFalse(s.isSmoothScrolling());
        assertFalse(s.shouldFollow());
    }

    @Test
    public void constructorClampsNonPositiveThreshold() {
        StickToBottomState s = new StickToBottomState(0);
        assertEquals(1, s.thresholdPx());
    }
}
