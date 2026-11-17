package com.oilquiz.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * 统一交互与动效层（2026-11-16）。
 *
 * <p><b>为什么需要</b>：审计发现"点击 / 长按 / 选中 / 涟漪反馈 / 入场动画"全散在各处 ——
 * 28 处长按分布在 8+ 个 Adapter，涟漪只有 15 处，{@code res/anim} 里 10 个动画资源有 8 个零引用。
 * 结果是同类控件的反馈各不相同。这里把这几类能力收敛成一套可复用 API，新页面直接用。</p>
 *
 * <p><b>能力清单</b>：</p>
 * <ul>
 *   <li>{@link #click(View, Runnable)} 点击：自带涟漪 + 400ms 防抖（防连点重复提交）；</li>
 *   <li>{@link #longPress(View, Runnable)} 长按：统一触摸反馈；</li>
 *   <li>{@link #toggleSelection(View, boolean)} 选中/取消：统一的选中态视觉；</li>
 *   <li>{@link #pressFeedback(View)} 按压缩放反馈（轻量、不依赖动画资源）；</li>
 *   <li>入场/状态动画：{@link #messageIn} {@link #messageInFromRight} {@link #welcomeIn}
 *       {@link #thinking} {@link #cursorBlink} {@link #weatherIcon} {@link #buttonState}。</li>
 * </ul>
 */
public final class UiFx {

    /** 点击防抖窗口（毫秒）：400ms 内重复点击直接忽略 */
    private static final long CLICK_DEBOUNCE_MS = 400L;

    private UiFx() {
    }

    // ------------------------------------------------------------------
    // 点击 / 长按 / 选中
    // ------------------------------------------------------------------

    /**
     * 绑定点击：自动补涟漪背景（原本有背景则保留）+ 400ms 防抖。
     * 适合"提交/跳转"类按钮，避免连点重复执行。
     */
    public static void click(View view, Runnable action) {
        if (view == null || action == null) {
            return;
        }
        ensureRipple(view);
        view.setOnClickListener(new View.OnClickListener() {
            private long lastClickAt = 0L;

            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastClickAt < CLICK_DEBOUNCE_MS) {
                    return;
                }
                lastClickAt = now;
                action.run();
            }
        });
    }

    /** 绑定长按：与 {@link #click} 可同时使用（点击走主逻辑，长按走二级操作）。 */
    public static void longPress(View view, Runnable action) {
        if (view == null || action == null) {
            return;
        }
        ensureRipple(view);
        view.setOnLongClickListener(v -> {
            action.run();
            return true;
        });
    }

    /** 选中/取消视觉：选中用主题主色描边 + 浅底，取消恢复。 */
    public static void toggleSelection(View view, boolean selected) {
        if (view == null) {
            return;
        }
        int accent = ThemeColors.get(view.getContext(), R.color.primary);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Tokens.radius(view.getContext(), Tokens.RADIUS_MD));
        if (selected) {
            int fill = (accent & 0x00FFFFFF) | 0x1A000000; // 10% 主色
            bg.setColor(fill);
            bg.setStroke(Math.max(1, Tokens.space(view.getContext(), Tokens.SPACE_HAIRLINE)), accent);
        } else {
            bg.setColor(ThemeColors.get(view.getContext(), R.color.card_background));
            bg.setStroke(1, ThemeColors.get(view.getContext(), R.color.border));
        }
        view.setBackground(bg);
    }

    /** 按压反馈：手指按下轻微缩小，抬起复位（不依赖动画资源，任何 View 可用）。 */
    public static void pressFeedback(View view) {
        if (view == null) {
            return;
        }
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false; // 不消费事件，点击/长按照常派发
        });
    }

    /** 没有背景的控件补一个涟漪背景；已有背景的不动（避免覆盖业务样式）。 */
    public static void ensureRipple(View view) {
        if (view == null || view.getBackground() != null) {
            return;
        }
        android.util.TypedValue tv = new android.util.TypedValue();
        if (view.getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            view.setBackgroundResource(tv.resourceId);
        }
    }

    // ------------------------------------------------------------------
    // 动画（把 res/anim 里闲置的资源统一暴露出来）
    // ------------------------------------------------------------------

    /** 播放一个动画资源（内部做空值/重复保护）。 */
    public static void play(View view, int animResId) {
        play(view, animResId, null);
    }

    /** 播放动画并在结束后回调（可用于入场后清理、状态切换）。 */
    public static void play(View view, int animResId, Runnable onEnd) {
        if (view == null || animResId == 0) {
            return;
        }
        try {
            Animation anim = AnimationUtils.loadAnimation(view.getContext(), animResId);
            if (onEnd != null) {
                anim.setAnimationListener(new Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation a) { }
                    @Override public void onAnimationEnd(Animation a) { onEnd.run(); }
                    @Override public void onAnimationRepeat(Animation a) { }
                });
            }
            view.clearAnimation();
            view.startAnimation(anim);
        } catch (Throwable ignored) {
            // 动画失败不影响功能
        }
    }

    /** 消息入场（底部滑入 + 淡入）：列表新增一条消息时调用。 */
    public static void messageIn(View view) {
        play(view, R.anim.message_enter_bottom);
    }

    /** 消息入场（右侧滑入）：用户自己发出的消息用。 */
    public static void messageInFromRight(View view) {
        play(view, R.anim.message_enter_right);
    }

    /** 流式输出中的打字感（AI 正在生成）。 */
    public static void streamingTyping(View view) {
        play(view, R.anim.message_streaming_typing);
    }

    /** 欢迎卡/首屏卡片入场。 */
    public static void welcomeIn(View view) {
        play(view, R.anim.welcome_card_enter);
    }

    /** "思考中"三点跳动（沿用闲置的 thinking_dot_bounce 资源，与 Lottie 二选一）。 */
    public static void thinking(View view) {
        play(view, R.anim.thinking_dot_bounce);
    }

    /**
     * 状态点"呼吸"（2026-11-16）。
     *
     * <p>用途：连接状态圆点、心跳指示这类"还活着"的信号。不动的圆点看起来像卡死，
     * 尤其与旁边不断刷新的耗时/时间对比时更明显（用户原话："看着像时间卡死"）。</p>
     *
     * <p>实现沿用桌面端 demo 的 {@code .pulse}：1.1s 一次，alpha 1 → 0.3 → 1 循环。
     * 用 tag 防重复启动；{@link #stopBreathing(View)} 负责停（页面 pause / 离线时调）。</p>
     */
    public static void breathing(View view) {
        if (view == null) {
            return;
        }
        Object running = view.getTag(R.id.tag_breathing);
        if (running instanceof android.animation.Animator) {
            return;
        }
        try {
            android.animation.ObjectAnimator a =
                    android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.3f, 1f);
            a.setDuration(1100L);
            a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            view.setTag(R.id.tag_breathing, a);
            a.start();
        } catch (Throwable t) {
            // 动画失败不至于影响界面
        }
    }

    /** 停止呼吸（离线 / 页面不可见时用，避免后台白耗电）。 */
    public static void stopBreathing(View view) {
        if (view == null) {
            return;
        }
        Object running = view.getTag(R.id.tag_breathing);
        if (running instanceof android.animation.Animator) {
            try {
                ((android.animation.Animator) running).cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
            view.setTag(R.id.tag_breathing, null);
        }
        view.setAlpha(1f);
    }

    /** 输入光标闪烁。 */
    public static void cursorBlink(View view) {
        play(view, R.anim.cursor_blink);
    }

    /**
     * 通用"脉冲/呼吸"（语义化命名，等价 {@link #breathing}）。
     *
     * <p>2026-11-16 归一：生成中圆点、推理进度 emoji、连接状态点此前各自手写
     * {@code ObjectAnimator}（550ms/0.25、1000ms/0.5、1100ms/0.3 三套参数），
     * 现在统一走这里——同一套节奏、同一套停法（{@link #stopPulse} 会复位 alpha）。</p>
     */
    public static void pulse(View view) {
        breathing(view);
    }

    /** 停止脉冲并复位 alpha（页面不可见/生成结束时调）。 */
    public static void stopPulse(View view) {
        stopBreathing(view);
    }

    /**
     * 单次"心跳呼吸"（2026-11-17）。
     *
     * <p>与 {@link #breathing(View)} 的区别：那个是无限循环动画，只要颜色是绿的就会一直动，
     * 服务没响应也照样"呼吸"——看着有生命，其实已经卡死（用户原话：要连接到服务，
     * 不要简单只做动画）。这里改成**每收到一次真实心跳才亮一下**：远程态的圆点在每次
     * /status 刷新成功、连接页每次探测成功、WS/SSE 心跳帧到达时各调一次；
     * 服务不响应就没有新的脉冲，灯自然静止（颜色同时由状态逻辑改成灰/黄）。</p>
     *
     * <p>节奏 700ms 单程；同一 view 上新的心跳会接管旧的（tag 记录当前动画），
     * 不会叠加出一堆 Animator。</p>
     */
    public static void pulseOnce(View view) {
        if (view == null) {
            return;
        }
        try {
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
            Object running = view.getTag(R.id.tag_breathing);
            if (running instanceof android.animation.Animator) {
                ((android.animation.Animator) running).cancel();
            }
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(1f, 0.35f, 1f);
            va.setDuration(700L);
            va.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            va.addUpdateListener(a -> view.setAlpha(((Number) a.getAnimatedValue()).floatValue()));
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    view.setAlpha(1f);
                    if (view.getTag(R.id.tag_breathing) == animation) {
                        view.setTag(R.id.tag_breathing, null);
                    }
                }
            });
            view.setTag(R.id.tag_breathing, va);
            va.start();
        } catch (Throwable ignored) {
            // 动画失败不影响状态显示
        }
    }

    /**
     * 内容块错峰入场：给"一条消息里多个 part 卡"用（每个 part 一张卡时，
     * 一起淡入会显得整条消息一起蹦出来）。淡入 + 上移 6dp，逐个延迟 {@code stepMs}（最多 6 档）。
     */
    public static void staggerIn(ViewGroup host, int stepMs, long durationMs) {
        if (host == null) {
            return;
        }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        int count = host.getChildCount();
        if (count <= 0) {
            return;
        }
        float dy = Tokens.spaceSm(host.getContext());
        int i = 0;
        for (int c = 0; c < count; c++) {
            View child = host.getChildAt(c);
            if (child == null || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            child.setAlpha(0f);
            child.setTranslationY(dy);
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(Math.min(i, 6) * stepMs)
                    .setDuration(durationMs)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            i++;
        }
    }

    /**
     * 消息方向性入场：用户消息从右、AI/系统消息从左，8dp 位移 + 淡入，180ms。
     *
     * <p>2026-11-16 归一：{@code ChatAdapter} 里手写的 {@code AnimationSet
     * (TranslateAnimation + AlphaAnimation)} 收到这里，参数与 demo motion 口径一致
     * （短、轻、不抢阅读）；同时按"动画关闭"（开发者选项/省电）直接跳过。</p>
     */
    public static void slideInMessage(View view, boolean fromRight) {
        if (view == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        float offset = Tokens.spaceMd(view.getContext());
        view.setTranslationX(fromRight ? offset : -offset);
        view.setAlpha(0f);
        view.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(180L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /**
     * 天气图标微动效。
     *
     * <p>注意：{@code res/anim/weather_icon_anim.xml} 是 <b>&lt;animation-list&gt;</b>（逐帧动画，
     * 由 3 张 weather_sunny_1/2/3 组成），不是 tween 动画 —— 用 {@link AnimationUtils#loadAnimation}
     * 加载会失败（返回 null），表现就是"天气图标不动"。正确做法是当 Drawable 取出后 start()。
     * 这里的实现两种情况都兼容：AnimationDrawable 走帧动画，其它走 tween。</p>
     */
    public static void weatherIcon(View view) {
        if (view == null) {
            return;
        }
        try {
            android.graphics.drawable.Drawable d = androidx.core.content.res.ResourcesCompat.getDrawable(
                    view.getContext().getResources(), R.anim.weather_icon_anim,
                    view.getContext().getTheme());
            if (d instanceof android.graphics.drawable.AnimationDrawable) {
                view.setBackground(d);
                ((android.graphics.drawable.AnimationDrawable) d).start();
                return;
            }
        } catch (Throwable ignored) {
            // 帧动画加载失败则退回 tween
        }
        play(view, R.anim.weather_icon_anim);
    }

    /**
     * 按钮状态切换动画（按下/抬起的高度变化）。
     *
     * <p>注意：它在 {@code res/animator/} 下，是 StateListAnimator，不能走
     * {@link AnimationUtils#loadAnimation}（那是给 tween anim 的），必须用
     * {@code AnimatorInflater.loadStateListAnimator}。</p>
     */
    public static void buttonState(View view) {
        if (view == null) {
            return;
        }
        try {
            view.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(
                    view.getContext(), R.animator.button_state_list_animator));
        } catch (Throwable ignored) {
            // 状态动画失败不影响点击
        }
    }

    /** 折叠指示器旋转（展开 90° / 收起 0°，180ms）：思考区/工具区 chevron 统一口径。 */
    public static void rotateChevron(View chevron, boolean expanded) {
        if (chevron == null) {
            return;
        }
        chevron.animate()
                .rotation(expanded ? 90f : 0f)
                .setDuration(180L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** 内容淡入（先 VISIBLE 再从 0 → 1）。 */
    public static void fadeIn(View view, long durationMs) {
        if (view == null) {
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(durationMs).start();
    }

    /** 内容淡出（从 1 → 0 后置 GONE），避免"瞬间消失"的突兀。 */
    public static void fadeOutGone(View view, long durationMs) {
        if (view == null) {
            return;
        }
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        view.animate()
                .alpha(0f)
                .setDuration(durationMs)
                .withEndAction(() -> view.setVisibility(View.GONE))
                .start();
    }
    /** 从左侧滑入转场。 */
    public static void slideInLeft(View view) {
        play(view, R.anim.slide_in_left);
    }

    /** 从右侧滑入转场。 */
    public static void slideInRight(View view) {
        play(view, R.anim.slide_in_right);
    }
}
