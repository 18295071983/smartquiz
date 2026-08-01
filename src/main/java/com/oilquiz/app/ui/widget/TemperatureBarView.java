package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.oilquiz.app.R;

public class TemperatureBarView extends View {

    private Paint barPaint;
    private Paint bgPaint;
    private Paint currentMarkerPaint;
    private RectF barRect;

    private float tempHigh = 35;
    private float tempLow = 0;
    private float tempCurrent = 20;
    private float tempMaxRange = 40;
    private float tempMinRange = -10;
    private int barColor = 0xFFFF6B35;
    private int bgColor = 0x33FFFFFF;

    public TemperatureBarView(Context context) {
        super(context);
        init(null);
    }

    public TemperatureBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public TemperatureBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.TemperatureBarView);
            tempHigh = a.getFloat(R.styleable.TemperatureBarView_temp_high, 35);
            tempLow = a.getFloat(R.styleable.TemperatureBarView_temp_low, 0);
            tempCurrent = a.getFloat(R.styleable.TemperatureBarView_temp_current, 20);
            tempMaxRange = a.getFloat(R.styleable.TemperatureBarView_temp_max_range, 40);
            tempMinRange = a.getFloat(R.styleable.TemperatureBarView_temp_min_range, -10);
            barColor = a.getColor(R.styleable.TemperatureBarView_temp_bar_color, 0xFFFF6B35);
            a.recycle();
        }

        barPaint = new Paint();
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setColor(barColor);
        barPaint.setAntiAlias(true);

        bgPaint = new Paint();
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(bgColor);
        bgPaint.setAntiAlias(true);

        currentMarkerPaint = new Paint();
        currentMarkerPaint.setStyle(Paint.Style.FILL);
        currentMarkerPaint.setColor(0xFFFFFFFF);
        currentMarkerPaint.setAntiAlias(true);

        barRect = new RectF();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int height = getHeight();
        int width = getWidth();
        int barHeight = Math.max(4, height / 3);
        int barTop = (height - barHeight) / 2;

        float range = tempMaxRange - tempMinRange;
        float normalizedHigh = (tempHigh - tempMinRange) / range;
        float normalizedLow = (tempLow - tempMinRange) / range;

        normalizedHigh = Math.min(1, Math.max(0, normalizedHigh));
        normalizedLow = Math.min(1, Math.max(0, normalizedLow));

        float bgLeft = 0;
        float bgRight = width;
        barRect.set(bgLeft, barTop, bgRight, barTop + barHeight);
        canvas.drawRoundRect(barRect, barHeight / 2, barHeight / 2, bgPaint);

        float barLeft = normalizedLow * width;
        float barRight = normalizedHigh * width;
        barRect.set(barLeft, barTop, barRight, barTop + barHeight);
        canvas.drawRoundRect(barRect, barHeight / 2, barHeight / 2, barPaint);

        if (tempCurrent >= tempLow && tempCurrent <= tempHigh) {
            float normalizedCurrent = (tempCurrent - tempMinRange) / range;
            float markerX = normalizedCurrent * width;
            float markerSize = barHeight + 4;
            RectF markerRect = new RectF(
                    markerX - markerSize / 2,
                    barTop - 2,
                    markerX + markerSize / 2,
                    barTop + barHeight + 2);
            canvas.drawRoundRect(markerRect, markerSize / 2, markerSize / 2, currentMarkerPaint);
        }
    }

    public void setTemperatureRange(float low, float high) {
        this.tempLow = low;
        this.tempHigh = high;
        invalidate();
    }

    public void setCurrentTemp(float current) {
        this.tempCurrent = current;
        invalidate();
    }

    public void setRange(float min, float max) {
        this.tempMinRange = min;
        this.tempMaxRange = max;
        invalidate();
    }
}
