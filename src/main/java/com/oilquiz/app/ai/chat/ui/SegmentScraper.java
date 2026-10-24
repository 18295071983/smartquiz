package com.oilquiz.app.ai.chat.ui;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;

/**
 * "刮刀式滑入"——新内容从左往右刮进来 + 柔和淡入，**只在换段时播一次**。
 *
 * <p>原先这段动画长在 {@link GenerationStatusBar} 私有方法里（顶部生成状态条的思考行）。
 * parts 渲染器接管气泡后，思考段变成消息里的一个 reasoning part，不再经过那条状态条，
 * 于是需要同一个原语——所以把它抽到共用处，而不是复制第二份形状
 * （2026-10-06/07 那两轮修的正是"两份复制各写一遍判断"导致的闪烁与循环重播，
 * 见 {@code GenerationStatusBar.lastThinkLen} 与 {@code AIChatActivity:9268} 的注释）。</p>
 *
 * <p>形状刻意保持原样不"顺手优化"：Alpha 0.3→1 + Translate −0.6→0（自身宽度比例）、
 * 320ms、DecelerateInterpolator。改数值等于改现网观感，要改就单独拍。</p>
 */
public final class SegmentScraper {

    /** 与 GenerationStatusBar 原实现一致的时长/位移口径。 */
    private static final long DURATION_MS = 320L;
    private static final float FROM_X = -0.6f;
    private static final float FROM_ALPHA = 0.3f;

    private SegmentScraper() {
    }

    /**
     * 对目标视图播一次滑入。先 {@code clearAnimation()}：上一次没播完就叠加/重启，
     * alpha 会被打回 0.3、位移打回 −0.6，视觉上就是闪烁——这是这条动画唯一的坑。
     * 系统关闭动画（开发者选项/省电）时直接不播。
     */
    public static void slideIn(View v) {
        if (v == null) {
            return;
        }
        try {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                return;
            }
            v.clearAnimation();
            AnimationSet set = new AnimationSet(true);
            TranslateAnimation ta = new TranslateAnimation(
                    TranslateAnimation.RELATIVE_TO_SELF, FROM_X,
                    TranslateAnimation.RELATIVE_TO_SELF, 0f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0f);
            ta.setDuration(DURATION_MS);
            ta.setInterpolator(new DecelerateInterpolator());
            AlphaAnimation aa = new AlphaAnimation(FROM_ALPHA, 1f);
            aa.setDuration(DURATION_MS);
            set.addAnimation(ta);
            set.addAnimation(aa);
            v.startAnimation(set);
        } catch (Throwable ignored) {
            // 动画失败不影响内容显示
        }
    }

    /**
     * 换段信号 = 文本里的换行数。用"整行"而不是字符数，是因为逐 token 追加只让长度涨、
     * 不该重播；真正开新段（新的一行思考）才滑一次。
     */
    public static int segmentSignal(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 是否算"换了一段"（信号变大才播；变小或相等都不播，避免重置后立刻重播）。 */
    public static boolean isNewSegment(int previousSignal, int nextSignal) {
        return nextSignal > previousSignal;
    }
}
