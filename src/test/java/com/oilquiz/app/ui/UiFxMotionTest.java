package com.oilquiz.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 动效层（{@link UiFx}）归一后的行为护栏（2026-11-16）。
 *
 * <p>本轮把散在 {@code ChatInputBar}（550ms/0.25）、{@code InferenceProgressView}（1000ms/0.5）、
 * {@code ChatAdapter}（AnimationSet 入场 + 手写错峰）里的手写动画收进 {@code UiFx}。
 * 这里钉住收编后的契约：</p>
 * <ul>
 *   <li>{@code pulse} 幂等（重复调用不叠加动画），{@code stopPulse} 会取消并复位 alpha=1；</li>
 *   <li>{@code staggerIn} 给每个可见子 View 设初始 alpha/位移（错峰入场的前提）；</li>
 *   <li>{@code slideInMessage} 起始位移方向正确（用户从右、AI 从左）且 alpha 从 0 起。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class UiFxMotionTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void pulseIsIdempotentAndStopResetsAlpha() {
        View dot = new View(ctx);
        UiFx.pulse(dot);
        Object tag = dot.getTag(com.oilquiz.app.R.id.tag_breathing);
        assertNotNull("pulse 应把动画挂在 tag 上（用于幂等与停止）", tag);

        UiFx.pulse(dot); // 重复调用不应叠加
        assertEquals("重复 pulse 不应替换/叠加同一个动画", tag, dot.getTag(com.oilquiz.app.R.id.tag_breathing));

        dot.setAlpha(0.4f);
        UiFx.stopPulse(dot);
        assertNull("stopPulse 应清掉 tag", dot.getTag(com.oilquiz.app.R.id.tag_breathing));
        assertEquals("stopPulse 应把 alpha 复位为 1", 1f, dot.getAlpha(), 0.0001f);
    }

    @Test
    public void staggerInSeedsChildrenForStaggeredReveal() {
        LinearLayout host = new LinearLayout(ctx);
        View a = new View(ctx);
        View b = new View(ctx);
        View hidden = new View(ctx);
        hidden.setVisibility(View.GONE);
        host.addView(a);
        host.addView(b);
        host.addView(hidden);

        UiFx.staggerIn(host, 35, 220L);

        assertEquals("可见子 View 应从透明开始", 0f, a.getAlpha(), 0.0001f);
        assertTrue("可见子 View 应有上移起始位移", a.getTranslationY() > 0f);
        assertTrue(b.getTranslationY() > 0f);
        assertEquals("隐藏子 View 不该被动", 1f, hidden.getAlpha(), 0.0001f);

        // 空容器 / null 不应崩
        UiFx.staggerIn(new LinearLayout(ctx), 35, 220L);
        UiFx.staggerIn(null, 35, 220L);
    }

    @Test
    public void slideInMessageStartsFromCorrectSide() {
        View userMsg = new View(ctx);
        UiFx.slideInMessage(userMsg, true);
        assertTrue("用户消息应从右滑入（translationX > 0）", userMsg.getTranslationX() > 0f);
        assertEquals("入场应从透明开始", 0f, userMsg.getAlpha(), 0.0001f);

        View aiMsg = new View(ctx);
        UiFx.slideInMessage(aiMsg, false);
        assertTrue("AI/系统消息应从左滑入（translationX < 0）", aiMsg.getTranslationX() < 0f);

        UiFx.slideInMessage(null, true); // 不应崩
    }
}
