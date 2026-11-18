package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * "还在动"的呼吸点（2026-11-18）：3 颗用于折叠头，1 颗用于任何状态行/卡片头部。
 *
 * <p><b>为什么需要</b>：agent 执行时，气泡里只有一行静止的"正在运行 pwsh / 正在思考…"，
 * 以及一张状态写着"运行中"却不动的卡片。一行不动的字读起来像卡死——尤其旁边工具耗时
 * 还在变的时候（用户原话："agent 执行时没有引入动画"）。聊天类产品的通行做法是给活动行
 * 加一个"活着"的信号：点错峰呼吸。</p>
 *
 * <p><b>为什么用系统时钟而不是 Animator</b>：这些视图会被反复重建——工具卡的
 * {@code bindView} 每 tick 移除并重建卡片、工具组的成员行每次重绑都重排。若相位由
 * "本实例的 Animator 从 0 开始"驱动，每次重建都回到相位 0，看上去就是一张静止的图。
 * 改成 {@code SystemClock.uptimeMillis() % 周期} 后，**任意时刻新建的实例都落在正确相位**，
 * 重建多少次都连续；代价是各实例同相（观感上反而一致）。</p>
 *
 * <p><b>为什么自绘、不用库</b>：这一处要的只是几颗圆点的 alpha/半径相位差，用 Lottie 得先做
 * 一个 json、还得随主题换色（json 里颜色写死），Shimmer/SpinKit 一类库也早已停更。</p>
 *
 * <p>颜色不由本类决定（{@link #setDotColor(int)} 由调用方按主题传入），满足"动效原语只管
 * 形状、不管配色"的口径（见 {@code docs/ui/UI-标准-模态框-弹层-容器.md} §4）。
 * 离屏不画即不自刷新，不存在空转的动画循环。</p>
 */
public final class LiveDotsView extends View {

    /** 一轮呼吸的时长：1.2s 与 UiFx.pulse 的 1.1s 同档，肉眼是同一套节奏。 */
    private static final long CYCLE_MS = 1200L;
    private static final int MAX_DOTS = 3;
    /** 相邻点的相位差：0.16 圈 ≈ 190ms，错峰但不拖成"波浪线"。 */
    private static final float PHASE_STEP = 0.16f;
    private static final float DOT_RADIUS_DP = 2.5f;
    private static final float GAP_DP = 3f;
    private static final float HEIGHT_DP = 14f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 业务是否要求"正在跑"；离屏后回树据此续跑。 */
    private boolean running;
    private int dotCount = MAX_DOTS;

    public LiveDotsView(Context context) {
        this(context, null);
    }

    public LiveDotsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LiveDotsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 点色由调用方按主题给（本类不取色，也不写死任何色值）。 */
    public void setDotColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /** 点数量：3 = 折叠头的"打字中"，1 = 行内状态点（工具行 / 命令卡 / 卡片头）。 */
    public void setDotCount(int count) {
        int n = Math.max(1, Math.min(MAX_DOTS, count));
        if (n == dotCount) {
            return;
        }
        dotCount = n;
        requestLayout();
        invalidate();
    }

    /** 开始呼吸（幂等）。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        if (isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }

    /** 停止呼吸（执行结束 / 折叠头不再 active 时调用）；静止态画成满亮满径。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (running) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.round(dp(dotCount * 2 * DOT_RADIUS_DP + (dotCount - 1) * GAP_DP));
        int height = Math.round(dp(HEIGHT_DP));
        setMeasuredDimension(
                resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean animate = running && android.animation.ValueAnimator.areAnimatorsEnabled();
        float phase = animate ? (SystemClock.uptimeMillis() % CYCLE_MS) / (float) CYCLE_MS : 0f;
        float r = Math.min(dp(DOT_RADIUS_DP), h * 0.5f);
        float gap = dp(GAP_DP);
        float total = dotCount * 2 * r + (dotCount - 1) * gap;
        float cy = h / 2f;
        float startX = (w - total) / 2f + r;
        int baseAlpha = paint.getAlpha();
        for (int i = 0; i < dotCount; i++) {
            // 静止态：满亮满径（是一颗"状态点"，不是动画停帧）；运行时：余弦波起落
            float k = 1f;
            if (animate) {
                float t = (phase + i * PHASE_STEP) % 1f;
                k = (float) (0.5f - 0.5f * Math.cos(2 * Math.PI * t));
            }
            paint.setAlpha(Math.round(baseAlpha * (0.25f + 0.75f * k)));
            canvas.drawCircle(startX + i * (2 * r + gap), cy, r * (0.72f + 0.28f * k), paint);
        }
        paint.setAlpha(baseAlpha);
        if (animate) {
            postInvalidateOnAnimation(); // 自驱一帧：GONE/离屏时不再收到 onDraw，循环自然停
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
