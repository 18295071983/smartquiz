package com.oilquiz.app.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
/**
 * AI 初始化进度环
 *
 * 视觉元素：
 * 1. 底部灰色轨道环
 * 2. 紫→青渐变进度弧（发光 + SweepGradient）
 * 3. 头部圆点光晕
 * 4. 中央大号百分比文字
 * 内置平滑过渡（DecelerateInterpolator），动画进度自动补间。
 */
public class GradientRingProgress extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float progress = 0f;        // 0-100
    private float displayedProgress = 0f;
    private int ringColorStart;
    private int ringColorEnd = ThemeColors.get(R.color.hc_ff06b6d4);
    private ValueAnimator animator;

    public GradientRingProgress(Context context) {
        super(context);
        init();
    }

    public GradientRingProgress(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(dp(10));

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(dp(10));
        trackPaint.setColor(ThemeColors.get(R.color.hc_28ffffff));

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);

        subPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setColor(ThemeColors.get(R.color.hc_b4ffffff));

        headPaint.setStyle(Paint.Style.FILL);
        headGlowPaint.setStyle(Paint.Style.FILL);
    }

    /** 平滑设置进度（0-100） */
    public void setProgress(float value) {        float target = Math.max(0f, Math.min(100f, value));
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(displayedProgress, target);
        animator.setDuration(400);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            displayedProgress = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
        progress = target;
    }

    /** 获取当前目标进度（0-100） */
    public float getCurrentProgress() {
        return progress;
    }

    public void setRingColors(int start, int end) {
        ringColorStart = start;
        ringColorEnd = end;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - dp(10) - dp(4);
        float stroke = dp(10);

        // 轨道
        canvas.drawCircle(cx, cy, r, trackPaint);

        // 渐变进度弧
        float sweep = 360f * displayedProgress / 100f;
        if (sweep > 1f) {
            arcPaint.setStrokeWidth(stroke);
            arcPaint.setShader(new SweepGradient(cx, cy,
                    new int[]{ringColorStart, ringColorEnd, ringColorStart},
                    new float[]{0f, 0.5f, 1f}));
            arcRect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint);
            arcPaint.setShader(null);
        }

        // 头部圆点 + 光晕
        if (sweep > 2f) {
            double rad = Math.toRadians(-90 + sweep);
            float hx = (float) (cx + r * Math.cos(rad));
            float hy = (float) (cy + r * Math.sin(rad));
            headGlowPaint.setColor((ringColorStart & 0x00FFFFFF) | 0x5A000000);
            canvas.drawCircle(hx, hy, dp(12), headGlowPaint);
            headPaint.setColor(ThemeColors.get(R.color.hc_ff06b6d4));
            canvas.drawCircle(hx, hy, dp(5), headPaint);
        }

        // 中央百分比
        textPaint.setTextSize(dp(46));
        canvas.drawText(Math.round(displayedProgress) + "%", cx, cy + dp(6), textPaint);
        subPaint.setTextSize(dp(13));
        canvas.drawText("AI 初始化", cx, cy + dp(32), subPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
