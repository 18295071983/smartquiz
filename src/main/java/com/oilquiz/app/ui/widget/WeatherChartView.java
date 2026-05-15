package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class WeatherChartView extends View {

    private Paint linePaint;
    private Paint pointPaint;
    private Paint fillPaint;
    private Paint textPaint;
    private Paint touchPaint;

    private List<Integer> temperatures = new ArrayList<>();
    private List<String> times = new ArrayList<>();

    private float chartWidth;
    private float chartHeight;
    private float padding = 40;
    private float pointRadius = 6;

    private int maxTemp = 35;
    private int minTemp = -10;

    private int touchIndex = -1;
    private float touchX = -1;
    private float touchY = -1;

    public WeatherChartView(Context context) {
        super(context);
        init();
    }

    public WeatherChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WeatherChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint = new Paint();
        linePaint.setColor(0xFF64B5F6);
        linePaint.setStrokeWidth(3);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setAntiAlias(true);

        pointPaint = new Paint();
        pointPaint.setColor(0xFF1976D2);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        fillPaint = new Paint();
        fillPaint.setColor(0x4064B5F6);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(12 * getResources().getDisplayMetrics().density);
        textPaint.setAntiAlias(true);

        touchPaint = new Paint();
        touchPaint.setColor(0xFF1976D2);
        touchPaint.setStrokeWidth(2);
        touchPaint.setStyle(Paint.Style.STROKE);
        touchPaint.setAntiAlias(true);
    }

    public void setData(List<Integer> temps, List<String> timeLabels) {
        this.temperatures = temps;
        this.times = timeLabels;
        if (!temps.isEmpty()) {
            maxTemp = temps.get(0);
            minTemp = temps.get(0);
            for (int temp : temps) {
                maxTemp = Math.max(maxTemp, temp);
                minTemp = Math.min(minTemp, temp);
            }
            maxTemp += 5;
            minTemp -= 5;
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        chartWidth = w - padding * 2;
        chartHeight = h - padding * 2;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (temperatures.isEmpty()) {
            drawNoDataMessage(canvas);
            return;
        }

        float startX = padding;
        float startY = padding;

        drawGrid(canvas, startX, startY);
        
        if (temperatures.size() >= 2) {
            drawCurve(canvas, startX, startY);
        } else {
            drawSinglePoint(canvas, startX, startY);
        }
        
        drawTouchIndicator(canvas, startX, startY);
    }

    private void drawNoDataMessage(Canvas canvas) {
        String message = "暂无数据";
        float textWidth = textPaint.measureText(message);
        float x = (getWidth() - textWidth) / 2;
        float y = getHeight() / 2;
        textPaint.setColor(0xFF999999);
        canvas.drawText(message, x, y, textPaint);
        textPaint.setColor(Color.BLACK);
    }

    private void drawSinglePoint(Canvas canvas, float startX, float startY) {
        if (temperatures.size() < 1) return;
        
        float x = startX + chartWidth / 2;
        float y = getYForTemp(temperatures.get(0), startY);
        
        canvas.drawCircle(x, y, pointRadius, pointPaint);
        
        String tempText = temperatures.get(0) + "°C";
        float textWidth = textPaint.measureText(tempText);
        canvas.drawText(tempText, x - textWidth / 2, y - pointRadius - 8, textPaint);
    }

    private void drawGrid(Canvas canvas, float startX, float startY) {
        Paint gridPaint = new Paint();
        gridPaint.setColor(0x30CCCCCC);
        gridPaint.setStrokeWidth(1);

        int gridLines = 5;
        float gridInterval = chartHeight / (gridLines - 1);

        for (int i = 0; i < gridLines; i++) {
            float y = startY + gridInterval * i;
            canvas.drawLine(startX, y, startX + chartWidth, y, gridPaint);

            int temp = maxTemp - (int) ((maxTemp - minTemp) * (float) i / (gridLines - 1));
            String tempText = temp + "°";
            float textWidth = textPaint.measureText(tempText);
            canvas.drawText(tempText, startX - textWidth - 10, y + 4, textPaint);
        }

        float pointInterval = chartWidth / (temperatures.size() - 1);
        for (int i = 0; i < temperatures.size(); i++) {
            float x = startX + pointInterval * i;
            canvas.drawLine(x, startY, x, startY + chartHeight, gridPaint);

            String time = times.get(i);
            float textWidth = textPaint.measureText(time);
            canvas.drawText(time, x - textWidth / 2, startY + chartHeight + 25, textPaint);
        }
    }

    private void drawCurve(Canvas canvas, float startX, float startY) {
        if (temperatures.size() < 2) return;

        float pointInterval = chartWidth / (temperatures.size() - 1);

        Path curvePath = new Path();
        Path fillPath = new Path();

        float firstX = startX;
        float firstY = getYForTemp(temperatures.get(0), startY);
        curvePath.moveTo(firstX, firstY);
        fillPath.moveTo(firstX, startY + chartHeight);
        fillPath.lineTo(firstX, firstY);

        for (int i = 1; i < temperatures.size(); i++) {
            float x = startX + pointInterval * i;
            float y = getYForTemp(temperatures.get(i), startY);

            float prevX = startX + pointInterval * (i - 1);
            float prevY = getYForTemp(temperatures.get(i - 1), startY);

            float cpX = (prevX + x) / 2;
            curvePath.quadTo(prevX, prevY, cpX, (prevY + y) / 2);
            fillPath.lineTo(x, y);
        }

        float lastX = startX + chartWidth;
        float lastY = getYForTemp(temperatures.get(temperatures.size() - 1), startY);
        curvePath.lineTo(lastX, lastY);
        fillPath.lineTo(lastX, lastY);
        fillPath.lineTo(lastX, startY + chartHeight);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(curvePath, linePaint);

        for (int i = 0; i < temperatures.size(); i++) {
            float x = startX + pointInterval * i;
            float y = getYForTemp(temperatures.get(i), startY);
            canvas.drawCircle(x, y, pointRadius, pointPaint);
        }
    }

    private void drawTouchIndicator(Canvas canvas, float startX, float startY) {
        if (touchIndex < 0 || touchIndex >= temperatures.size()) return;

        float pointInterval = chartWidth / (temperatures.size() - 1);
        float x = startX + pointInterval * touchIndex;
        float y = getYForTemp(temperatures.get(touchIndex), startY);

        canvas.drawCircle(x, y, pointRadius + 4, touchPaint);

        String tempText = temperatures.get(touchIndex) + "°C";
        String timeText = times.get(touchIndex);

        float textWidth = Math.max(textPaint.measureText(tempText), textPaint.measureText(timeText));
        float bubbleWidth = textWidth + 20;
        float bubbleHeight = 50;

        float bubbleX = Math.max(startX, Math.min(x - bubbleWidth / 2, startX + chartWidth - bubbleWidth));
        float bubbleY = y - bubbleHeight - 10;

        RectF bubbleRect = new RectF(bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight);
        Paint bubblePaint = new Paint();
        bubblePaint.setColor(0xFF1976D2);
        bubblePaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(bubbleRect, 8, 8, bubblePaint);

        textPaint.setColor(Color.WHITE);
        canvas.drawText(timeText, bubbleX + 10, bubbleY + 20, textPaint);
        canvas.drawText(tempText, bubbleX + 10, bubbleY + 40, textPaint);
        textPaint.setColor(Color.BLACK);

        canvas.drawLine(x, y, x, bubbleY + bubbleHeight, touchPaint);
    }

    private float getYForTemp(int temp, float startY) {
        float range = maxTemp - minTemp;
        float normalized = (float) (maxTemp - temp) / range;
        return startY + normalized * chartHeight;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float pointInterval = chartWidth / (temperatures.size() - 1);
        float touchX = event.getX() - padding;

        int newIndex = Math.round(touchX / pointInterval);
        newIndex = Math.max(0, Math.min(newIndex, temperatures.size() - 1));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                touchIndex = newIndex;
                this.touchX = event.getX();
                this.touchY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchIndex = -1;
                invalidate();
                return true;
        }
        return false;
    }

    public interface OnChartTouchListener {
        void onTouch(int index, int temperature, String time);
        void onTouchEnd();
    }

    private OnChartTouchListener onChartTouchListener;

    public void setOnChartTouchListener(OnChartTouchListener listener) {
        this.onChartTouchListener = listener;
    }
}