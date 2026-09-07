package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
public class TempRangeBarView extends View {

    private Paint bgPaint;
    private Paint rangePaint;
    private Paint thumbPaint;

    private int lowTemp = 20;
    private int highTemp = 30;
    private int currentTemp = -1;
    private int globalLowTemp = 15;
    private int globalHighTemp = 35;

    public TempRangeBarView(Context context) {
        super(context);
        init();
    }

    public TempRangeBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TempRangeBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        bgPaint = new Paint();
        bgPaint.setColor(ThemeColors.get(R.color.hc_33ffffff));
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setAntiAlias(true);

        rangePaint = new Paint();
        rangePaint.setStyle(Paint.Style.FILL);
        rangePaint.setAntiAlias(true);

        thumbPaint = new Paint();
        thumbPaint.setColor(ThemeColors.get(R.color.hc_ffffffff));
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setAntiAlias(true);
        thumbPaint.setShadowLayer(3 * density, 0, 1 * density, ThemeColors.get(R.color.hc_40000000));
    }

    public void setTempRange(int low, int high, int current, int globalLow, int globalHigh) {
        this.lowTemp = low;
        this.highTemp = high;
        this.currentTemp = current;
        this.globalLowTemp = globalLow;
        this.globalHighTemp = globalHigh;
        invalidate();
    }

    public void setTempRange(int low, int high, int globalLow, int globalHigh) {
        setTempRange(low, high, -1, globalLow, globalHigh);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float density = getResources().getDisplayMetrics().density;
        float barHeight = 6 * density;
        float barTop = (height - barHeight) / 2;
        float barLeft = 0;
        float barRight = width;
        float radius = barHeight / 2;

        RectF bgRect = new RectF(barLeft, barTop, barRight, barTop + barHeight);
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        float globalRange = globalHighTemp - globalLowTemp;
        if (globalRange <= 0) globalRange = 1;

        float lowRatio = (lowTemp - globalLowTemp) / globalRange;
        float highRatio = (highTemp - globalLowTemp) / globalRange;

        float rangeLeft = barLeft + lowRatio * width;
        float rangeRight = barLeft + highRatio * width;

        int coldColor = ThemeColors.get(R.color.hc_ff64b5f6);
        int warmColor = ThemeColors.get(R.color.hc_ffffa726);
        LinearGradient gradient = new LinearGradient(
                rangeLeft, barTop, rangeRight, barTop,
                coldColor, warmColor, Shader.TileMode.CLAMP
        );
        rangePaint.setShader(gradient);

        RectF rangeRect = new RectF(rangeLeft, barTop, rangeRight, barTop + barHeight);
        canvas.drawRoundRect(rangeRect, radius, radius, rangePaint);

        if (currentTemp >= globalLowTemp && currentTemp <= globalHighTemp) {
            float currentRatio = (currentTemp - globalLowTemp) / globalRange;
            float thumbX = barLeft + currentRatio * width;
            float thumbRadius = 5 * density;
            float thumbY = barTop + barHeight / 2;
            canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
        }
    }
}
