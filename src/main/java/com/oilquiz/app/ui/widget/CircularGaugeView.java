package com.oilquiz.app.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.oilquiz.app.R;

public class CircularGaugeView extends View {

    private Paint arcPaint;
    private Paint bgPaint;
    private Paint textPaint;
    private Paint labelPaint;
    private RectF arcRect;

    private float progress = 0;
    private float displayProgress = 0;
    private float maxProgress = 100;
    private int strokeWidth = 12;
    private int arcColor = 0xFF38BDF8;
    private int bgColor = 0x33FFFFFF;
    private int textColor = 0xFFFFFFFF;
    private int labelColor = 0xB3FFFFFF;
    private String text = "";
    private String label = "";
    private float textSize = 32;
    private float labelSize = 14;
    private ValueAnimator animator;

    public CircularGaugeView(Context context) {
        super(context);
        init(null);
    }

    public CircularGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public CircularGaugeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CircularGaugeView);
            progress = a.getFloat(R.styleable.CircularGaugeView_gauge_progress, 0);
            maxProgress = a.getFloat(R.styleable.CircularGaugeView_gauge_max, 100);
            strokeWidth = a.getDimensionPixelSize(R.styleable.CircularGaugeView_gauge_stroke_width, 12);
            arcColor = a.getColor(R.styleable.CircularGaugeView_gauge_color, 0xFF38BDF8);
            bgColor = a.getColor(R.styleable.CircularGaugeView_gauge_bg_color, 0x33FFFFFF);
            textColor = a.getColor(R.styleable.CircularGaugeView_gauge_text_color, 0xFFFFFFFF);
            labelColor = a.getColor(R.styleable.CircularGaugeView_gauge_label_color, 0xB3FFFFFF);
            text = a.getString(R.styleable.CircularGaugeView_gauge_text);
            label = a.getString(R.styleable.CircularGaugeView_gauge_label);
            textSize = a.getDimension(R.styleable.CircularGaugeView_gauge_text_size, 32);
            labelSize = a.getDimension(R.styleable.CircularGaugeView_gauge_label_size, 14);
            a.recycle();
        }

        arcPaint = new Paint();
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidth);
        arcPaint.setColor(arcColor);
        arcPaint.setAntiAlias(true);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        bgPaint = new Paint();
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(strokeWidth);
        bgPaint.setColor(bgColor);
        bgPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(textColor);
        textPaint.setTextSize(textSize);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        labelPaint = new Paint();
        labelPaint.setColor(labelColor);
        labelPaint.setTextSize(labelSize);
        labelPaint.setAntiAlias(true);

        arcRect = new RectF();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int padding = getPaddingLeft() + getPaddingRight();
        int size = Math.min(getWidth() - padding, getHeight() - padding);
        int left = (getWidth() - size) / 2;
        int top = (getHeight() - size) / 2;

        arcRect.set(left + strokeWidth / 2, top + strokeWidth / 2,
                left + size - strokeWidth / 2, top + size - strokeWidth / 2);

        canvas.drawArc(arcRect, 135, 270, false, bgPaint);

        float sweepAngle = (displayProgress / maxProgress) * 270;
        canvas.drawArc(arcRect, 135, Math.max(0, sweepAngle), false, arcPaint);

        float textCenterX = getWidth() / 2;
        float textCenterY = getHeight() / 2 - 6;

        if (text != null && !text.isEmpty()) {
            float textWidth = textPaint.measureText(text);
            canvas.drawText(text, textCenterX - textWidth / 2, textCenterY, textPaint);
        }

        if (label != null && !label.isEmpty()) {
            float labelWidth = labelPaint.measureText(label);
            float labelY = textCenterY + textSize * 0.75f + 8;
            canvas.drawText(label, textCenterX - labelWidth / 2, labelY, labelPaint);
        }
    }

    public void setProgress(float progress) {
        this.progress = Math.min(progress, maxProgress);
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(displayProgress, this.progress);
        animator.setDuration(600);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            displayProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setProgressImmediate(float progress) {
        this.progress = Math.min(progress, maxProgress);
        this.displayProgress = this.progress;
        invalidate();
    }

    public void setMaxProgress(float maxProgress) {
        this.maxProgress = maxProgress;
        invalidate();
    }

    public void setText(String text) {
        this.text = text;
        invalidate();
    }

    public void setLabel(String label) {
        this.label = label;
        invalidate();
    }

    public void setArcColor(int color) {
        this.arcColor = color;
        arcPaint.setColor(color);
        invalidate();
    }
}
