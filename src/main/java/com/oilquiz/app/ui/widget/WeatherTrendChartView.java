package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.oilquiz.app.util.QWeatherIconMapper;

import java.util.ArrayList;
import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
public class WeatherTrendChartView extends View {

    private static final float MIN_HOURLY_ITEM_WIDTH_DP = 60f;
    private static final float MIN_DAILY_ITEM_WIDTH_DP = 90f;

    public static class HourlyData {
        public String time;
        public int temperature;
        public int precipitation;
        public String weatherIcon;
        public int humidity;

        public HourlyData(String time, int temperature, int precipitation, String weatherIcon, int humidity) {
            this.time = time;
            this.temperature = temperature;
            this.precipitation = precipitation;
            this.weatherIcon = weatherIcon;
            this.humidity = humidity;
        }
    }

    public static class DailyData {
        public String dateLabel;
        public int highTemp;
        public int lowTemp;
        public String dayWeatherIcon;
        public String nightWeatherIcon;
        public String windDirection;
        public String windScale;
        public int humidity;

        public DailyData(String dateLabel, int highTemp, int lowTemp,
                         String dayWeatherIcon, String nightWeatherIcon,
                         String windDirection, String windScale, int humidity) {
            this.dateLabel = dateLabel;
            this.highTemp = highTemp;
            this.lowTemp = lowTemp;
            this.dayWeatherIcon = dayWeatherIcon;
            this.nightWeatherIcon = nightWeatherIcon;
            this.windDirection = windDirection;
            this.windScale = windScale;
            this.humidity = humidity;
        }
    }

    private Paint tempLinePaint;
    private Paint tempFillPaint;
    private Paint tempPointPaint;
    private Paint tempTextPaint;
    private Paint gridPaint;
    private Paint axisPaint;
    private Paint precipitationPaint;
    private Paint precipHighPaint;
    private Paint precipitationTextPaint;
    private Paint humidityLinePaint;
    private Paint humidityFillPaint;
    private Paint humidityTextPaint;
    private Paint labelPaint;
    private Paint touchIndicatorPaint;
    private Paint tooltipBgPaint;
    private Paint tooltipTextPaint;
    private Paint iconPaint;
    private Paint dailyRangePaint;
    private Paint dailyHighPaint;
    private Paint dailyLowPaint;
    private Paint dailyHighLinePaint;
    private Paint dailyLowLinePaint;

    private List<HourlyData> hourlyDataList = new ArrayList<>();
    private List<DailyData> dailyDataList = new ArrayList<>();
    private boolean isHourlyMode = true;

    private float chartLeftPadding = 48f;
    private float chartRightPadding = 32f;
    private float chartTopPadding = 32f;
    private float chartBottomPadding = 56f;

    private float chartAreaWidth;
    private float chartAreaHeight;
    private float calculatedMinWidth;

    private int maxTemp = 35;
    private int minTemp = 5;
    private int maxPrecip = 100;

    private int touchIndex = -1;
    private float touchX = -1;
    private float touchY = -1;

    private OnDataTouchListener touchListener;

    public interface OnDataTouchListener {
        void onDataTouched(int index, HourlyData data);
        void onDailyDataTouched(int index, DailyData data);
        void onTouchEnded();
    }

    public WeatherTrendChartView(Context context) {
        super(context);
        init();
    }

    public WeatherTrendChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WeatherTrendChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        tempLinePaint = new Paint();
        tempLinePaint.setColor(ThemeColors.get(R.color.hc_ff64b5f6));
        tempLinePaint.setStrokeWidth(3 * density);
        tempLinePaint.setStyle(Paint.Style.STROKE);
        tempLinePaint.setAntiAlias(true);
        tempLinePaint.setStrokeCap(Paint.Cap.ROUND);

        tempFillPaint = new Paint();
        tempFillPaint.setColor(ThemeColors.get(R.color.hc_3364b5f6));
        tempFillPaint.setStyle(Paint.Style.FILL);
        tempFillPaint.setAntiAlias(true);

        tempPointPaint = new Paint();
        tempPointPaint.setColor(ThemeColors.get(R.color.hc_ff1976d2));
        tempPointPaint.setStyle(Paint.Style.FILL);
        tempPointPaint.setAntiAlias(true);

        tempTextPaint = new Paint();
        tempTextPaint.setColor(ThemeColors.get(R.color.hc_ff1976d2));
        tempTextPaint.setTextSize(12 * density);
        tempTextPaint.setAntiAlias(true);
        tempTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        gridPaint = new Paint();
        gridPaint.setColor(ThemeColors.get(R.color.hc_20cccccc));
        gridPaint.setStrokeWidth(1 * density);
        gridPaint.setAntiAlias(true);
        gridPaint.setStyle(Paint.Style.STROKE);

        axisPaint = new Paint();
        axisPaint.setColor(ThemeColors.get(R.color.hc_40cccccc));
        axisPaint.setStrokeWidth(1 * density);
        axisPaint.setAntiAlias(true);

        precipitationPaint = new Paint();
        precipitationPaint.setColor(ThemeColors.get(R.color.hc_ff90caf9));
        precipitationPaint.setStyle(Paint.Style.FILL);
        precipitationPaint.setAntiAlias(true);

        precipHighPaint = new Paint();
        precipHighPaint.setColor(ThemeColors.get(R.color.hc_ff42a5f5));
        precipHighPaint.setStyle(Paint.Style.FILL);
        precipHighPaint.setAntiAlias(true);

        precipitationTextPaint = new Paint();
        precipitationTextPaint.setColor(ThemeColors.get(R.color.hc_ff64b5f6));
        precipitationTextPaint.setTextSize(10 * density);
        precipitationTextPaint.setAntiAlias(true);

        humidityLinePaint = new Paint();
        humidityLinePaint.setColor(ThemeColors.get(R.color.hc_ffffb74d));
        humidityLinePaint.setStrokeWidth(2 * density);
        humidityLinePaint.setStyle(Paint.Style.STROKE);
        humidityLinePaint.setAntiAlias(true);
        humidityLinePaint.setStrokeCap(Paint.Cap.ROUND);
        humidityLinePaint.setAlpha(180);

        humidityFillPaint = new Paint();
        humidityFillPaint.setColor(ThemeColors.get(R.color.hc_33ffb74d));
        humidityFillPaint.setStyle(Paint.Style.FILL);
        humidityFillPaint.setAntiAlias(true);

        humidityTextPaint = new Paint();
        humidityTextPaint.setColor(ThemeColors.get(R.color.hc_ffffb74d));
        humidityTextPaint.setTextSize(10 * density);
        humidityTextPaint.setAntiAlias(true);

        labelPaint = new Paint();
        labelPaint.setColor(ThemeColors.get(R.color.hc_ff666666));
        labelPaint.setTextSize(11 * density);
        labelPaint.setAntiAlias(true);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        touchIndicatorPaint = new Paint();
        touchIndicatorPaint.setColor(ThemeColors.get(R.color.hc_801976d2));
        touchIndicatorPaint.setStrokeWidth(2 * density);
        touchIndicatorPaint.setStyle(Paint.Style.STROKE);
        touchIndicatorPaint.setAntiAlias(true);

        tooltipBgPaint = new Paint();
        tooltipBgPaint.setColor(ThemeColors.get(R.color.hc_e61976d2));
        tooltipBgPaint.setStyle(Paint.Style.FILL);
        tooltipBgPaint.setAntiAlias(true);

        tooltipTextPaint = new Paint();
        tooltipTextPaint.setColor(Color.WHITE);
        tooltipTextPaint.setTextSize(12 * density);
        tooltipTextPaint.setAntiAlias(true);

        iconPaint = new Paint();
        iconPaint.setColor(Color.BLACK);
        iconPaint.setTextSize(16 * density);
        iconPaint.setAntiAlias(true);

        dailyRangePaint = new Paint();
        dailyRangePaint.setStyle(Paint.Style.FILL);
        dailyRangePaint.setColor(ThemeColors.get(R.color.hc_1564b5f6));

        dailyHighPaint = new Paint();
        dailyHighPaint.setColor(ThemeColors.get(R.color.hc_fff4511e));
        dailyHighPaint.setStyle(Paint.Style.FILL);
        dailyHighPaint.setAntiAlias(true);

        dailyLowPaint = new Paint();
        dailyLowPaint.setColor(ThemeColors.get(R.color.hc_ff42a5f5));
        dailyLowPaint.setStyle(Paint.Style.FILL);
        dailyLowPaint.setAntiAlias(true);

        dailyHighLinePaint = new Paint();
        dailyHighLinePaint.setColor(ThemeColors.get(R.color.hc_fff4511e));
        dailyHighLinePaint.setStrokeWidth(2.5f * density);
        dailyHighLinePaint.setStyle(Paint.Style.STROKE);
        dailyHighLinePaint.setAntiAlias(true);
        dailyHighLinePaint.setStrokeCap(Paint.Cap.ROUND);

        dailyLowLinePaint = new Paint();
        dailyLowLinePaint.setColor(ThemeColors.get(R.color.hc_ff42a5f5));
        dailyLowLinePaint.setStrokeWidth(2.5f * density);
        dailyLowLinePaint.setStyle(Paint.Style.STROKE);
        dailyLowLinePaint.setAntiAlias(true);
        dailyLowLinePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        float density = getResources().getDisplayMetrics().density;

        int dataCount = isHourlyMode ? hourlyDataList.size() : dailyDataList.size();
        if (dataCount < 2) {
            calculatedMinWidth = 0;
            setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight());
            return;
        }

        float minItemWidth = isHourlyMode ? MIN_HOURLY_ITEM_WIDTH_DP * density
                : MIN_DAILY_ITEM_WIDTH_DP * density;
        calculatedMinWidth = chartLeftPadding + (dataCount - 1) * minItemWidth + chartRightPadding;

        int minWidth = (int) Math.max(calculatedMinWidth, getSuggestedMinimumWidth());
        setMeasuredDimension(minWidth, getMeasuredHeight());
    }

    public void setHourlyData(List<HourlyData> dataList) {
        this.hourlyDataList = dataList != null ? dataList : new ArrayList<>();
        this.isHourlyMode = true;
        calculateHourlyRange();
        requestLayout();
        invalidate();
    }

    public void setDailyData(List<DailyData> dataList) {
        this.dailyDataList = dataList != null ? dataList : new ArrayList<>();
        this.isHourlyMode = false;
        calculateDailyRange();
        requestLayout();
        invalidate();
    }

    private float getEffectivePointSpacing() {
        int dataCount = isHourlyMode ? hourlyDataList.size() : dailyDataList.size();
        if (dataCount < 2) return 0;

        float density = getResources().getDisplayMetrics().density;
        float minItemWidth = isHourlyMode ? MIN_HOURLY_ITEM_WIDTH_DP * density
                : MIN_DAILY_ITEM_WIDTH_DP * density;

        float totalWidth = chartAreaWidth;
        float spacing = totalWidth / (dataCount - 1);

        if (calculatedMinWidth > 0 && spacing < minItemWidth) {
            return minItemWidth;
        }
        return spacing;
    }

    private float getChartAreaWidthForDraw() {
        float density = getResources().getDisplayMetrics().density;
        int dataCount = isHourlyMode ? hourlyDataList.size() : dailyDataList.size();
        if (dataCount < 2) return chartAreaWidth;

        float spacing = getEffectivePointSpacing();
        return spacing * (dataCount - 1);
    }

    private void calculateHourlyRange() {
        if (hourlyDataList.isEmpty()) return;
        maxTemp = hourlyDataList.get(0).temperature;
        minTemp = hourlyDataList.get(0).temperature;
        for (HourlyData d : hourlyDataList) {
            maxTemp = Math.max(maxTemp, d.temperature);
            minTemp = Math.min(minTemp, d.temperature);
            maxPrecip = Math.max(maxPrecip, d.precipitation);
        }
        maxTemp += 3;
        minTemp -= 3;
        maxTemp = Math.min(maxTemp, 50);
        minTemp = Math.max(minTemp, -20);
        maxPrecip = Math.max(maxPrecip, 10);
    }

    private void calculateDailyRange() {
        if (dailyDataList.isEmpty()) return;
        maxTemp = dailyDataList.get(0).highTemp;
        minTemp = dailyDataList.get(0).lowTemp;
        for (DailyData d : dailyDataList) {
            maxTemp = Math.max(maxTemp, d.highTemp);
            minTemp = Math.min(minTemp, d.lowTemp);
        }
        maxTemp += 2;
        minTemp -= 2;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        chartAreaWidth = w - chartLeftPadding - chartRightPadding;
        chartAreaHeight = h - chartTopPadding - chartBottomPadding;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (isHourlyMode) {
            drawHourlyChart(canvas);
        } else {
            drawDailyChart(canvas);
        }
    }

    private void drawHourlyChart(Canvas canvas) {
        if (hourlyDataList.isEmpty()) {
            drawNoDataMessage(canvas);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float left = chartLeftPadding;
        float top = chartTopPadding;
        float right = left + getChartAreaWidthForDraw();
        float bottom = top + chartAreaHeight;

        drawTemperatureGrid(canvas, left, top, right, bottom);

        int dataCount = hourlyDataList.size();
        if (dataCount < 2) return;

        float pointSpacing = getEffectivePointSpacing();
        float barMaxHeight = chartAreaHeight * 0.25f;
        float barBottom = bottom;

        drawPrecipitationBars(canvas, left, pointSpacing, barMaxHeight, barBottom);

        drawTemperatureCurve(canvas, left, top, right, bottom, pointSpacing);

        drawHumidityCurve(canvas, left, top, right, bottom, pointSpacing);

        drawWeatherIcons(canvas, left, bottom, pointSpacing);

        drawTimeLabels(canvas, left, bottom, pointSpacing);

        drawTouchIndicator(canvas, left, top, bottom, pointSpacing);
    }

    private void drawHumidityCurve(Canvas canvas, float left, float top,
                                   float right, float bottom, float pointSpacing) {
        if (hourlyDataList.size() < 2) return;

        float density = getResources().getDisplayMetrics().density;
        float smoothFactor = 0.2f;

        float[] points = new float[hourlyDataList.size() * 2];
        for (int i = 0; i < hourlyDataList.size(); i++) {
            float x = left + pointSpacing * i;
            int humidity = hourlyDataList.get(i).humidity;
            float y = getYForHumidity(humidity, top, bottom);
            points[i * 2] = x;
            points[i * 2 + 1] = y;
        }

        Path curvePath = new Path();
        Path fillPath = new Path();

        curvePath.moveTo(points[0], points[1]);
        fillPath.moveTo(points[0], bottom);
        fillPath.lineTo(points[0], points[1]);

        for (int i = 0; i < points.length - 2; i += 2) {
            float p0x = i >= 2 ? points[i - 2] : points[i];
            float p0y = i >= 2 ? points[i - 1] : points[i + 1];

            float p1x = points[i];
            float p1y = points[i + 1];

            float p2x = points[i + 2];
            float p2y = points[i + 3];

            float p3x = i + 4 < points.length ? points[i + 4] : p2x;
            float p3y = i + 4 < points.length ? points[i + 5] : p2y;

            float cp1x = p1x + (p2x - p0x) * smoothFactor;
            float cp1y = p1y + (p2y - p0y) * smoothFactor;
            float cp2x = p2x - (p3x - p1x) * smoothFactor;
            float cp2y = p2y - (p3y - p1y) * smoothFactor;

            curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
            fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
        }

        float lastX = points[points.length - 2];
        float lastY = points[points.length - 1];
        fillPath.lineTo(lastX, lastY);
        fillPath.lineTo(lastX, bottom);
        fillPath.close();

        canvas.drawPath(fillPath, humidityFillPaint);
        canvas.drawPath(curvePath, humidityLinePaint);

        for (int i = 0; i < hourlyDataList.size(); i++) {
            float x = left + pointSpacing * i;
            int humidity = hourlyDataList.get(i).humidity;
            if (humidity > 0) {
                float y = getYForHumidity(humidity, top, bottom);
                canvas.drawCircle(x, y, 3 * density, humidityLinePaint);
            }
        }
    }

    private float getYForHumidity(int humidity, float top, float bottom) {
        if (humidity <= 0) return bottom;
        if (humidity >= 100) return top;
        float normalized = (float) humidity / 100f;
        return bottom - normalized * (bottom - top) * 0.8f;
    }

    private void drawWeatherIcons(Canvas canvas, float left, float bottom, float pointSpacing) {
        float density = getResources().getDisplayMetrics().density;
        int iconInterval = Math.max(1, hourlyDataList.size() / 8);
        float iconSize = 18 * density;

        for (int i = 0; i < hourlyDataList.size(); i += iconInterval) {
            HourlyData data = hourlyDataList.get(i);
            if (data.weatherIcon == null || data.weatherIcon.isEmpty()) continue;

            float x = left + pointSpacing * i;
            float iconY = bottom + 36 * density;

            Drawable iconDrawable = QWeatherIconMapper.getIconDrawable(data.weatherIcon, getContext(), (int) iconSize);
            if (iconDrawable != null) {
                int halfIcon = (int) (iconSize / 2);
                iconDrawable.setBounds((int) x - halfIcon, (int) iconY - halfIcon, (int) x + halfIcon, (int) iconY + halfIcon);
                iconDrawable.draw(canvas);
            }
        }
    }

    private void drawTemperatureGrid(Canvas canvas, float left, float top, float right, float bottom) {
        int gridLines = 5;
        float gridInterval = (bottom - top) / (gridLines - 1);

        for (int i = 0; i < gridLines; i++) {
            float y = top + gridInterval * i;
            canvas.drawLine(left, y, right, y, gridPaint);

            int temp = maxTemp - (int) ((maxTemp - minTemp) * (float) i / (gridLines - 1));
            String tempText = temp + "°";
            tempTextPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(tempText, left - 8, y + 4, tempTextPaint);
        }

        canvas.drawLine(left, top, left, bottom, axisPaint);
        canvas.drawLine(left, bottom, right, bottom, axisPaint);
    }

    private void drawPrecipitationBars(Canvas canvas, float left, float pointSpacing,
                                       float barMaxHeight, float barBottom) {
        for (int i = 0; i < hourlyDataList.size(); i++) {
            HourlyData data = hourlyDataList.get(i);
            if (data.precipitation <= 0) continue;

            float x = left + pointSpacing * i;
            float barHeight = (data.precipitation / 100f) * barMaxHeight;
            float barWidth = Math.min(pointSpacing * 0.5f, 16f);

            RectF barRect = new RectF(
                    x - barWidth / 2,
                    barBottom - barHeight,
                    x + barWidth / 2,
                    barBottom
            );

            if (data.precipitation >= 70) {
                canvas.drawRect(barRect, precipHighPaint);
            } else {
                canvas.drawRect(barRect, precipitationPaint);
            }

            if (data.precipitation >= 30) {
                String precipText = data.precipitation + "%";
                precipitationTextPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(precipText, x, barBottom - barHeight - 4, precipitationTextPaint);
            }
        }
    }

    private void drawTemperatureCurve(Canvas canvas, float left, float top,
                                      float right, float bottom, float pointSpacing) {
        if (hourlyDataList.size() < 2) return;

        float density = getResources().getDisplayMetrics().density;
        float smoothFactor = 0.2f;

        float[] points = new float[hourlyDataList.size() * 2];
        for (int i = 0; i < hourlyDataList.size(); i++) {
            float x = left + pointSpacing * i;
            float y = getYForTemp(hourlyDataList.get(i).temperature, top, bottom);
            points[i * 2] = x;
            points[i * 2 + 1] = y;
        }

        Path curvePath = new Path();
        Path fillPath = new Path();

        curvePath.moveTo(points[0], points[1]);
        fillPath.moveTo(points[0], bottom);
        fillPath.lineTo(points[0], points[1]);

        for (int i = 0; i < points.length - 2; i += 2) {
            float p0x = i >= 2 ? points[i - 2] : points[i];
            float p0y = i >= 2 ? points[i - 1] : points[i + 1];

            float p1x = points[i];
            float p1y = points[i + 1];

            float p2x = points[i + 2];
            float p2y = points[i + 3];

            float p3x = i + 4 < points.length ? points[i + 4] : p2x;
            float p3y = i + 4 < points.length ? points[i + 5] : p2y;

            float cp1x = p1x + (p2x - p0x) * smoothFactor;
            float cp1y = p1y + (p2y - p0y) * smoothFactor;
            float cp2x = p2x - (p3x - p1x) * smoothFactor;
            float cp2y = p2y - (p3y - p1y) * smoothFactor;

            curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
            fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
        }

        float lastX = points[points.length - 2];
        float lastY = points[points.length - 1];
        fillPath.lineTo(lastX, lastY);
        fillPath.lineTo(lastX, bottom);
        fillPath.close();

        canvas.drawPath(fillPath, tempFillPaint);
        canvas.drawPath(curvePath, tempLinePaint);

        int tempLabelInterval = Math.max(1, hourlyDataList.size() / 8);
        for (int i = 0; i < hourlyDataList.size(); i++) {
            float x = left + pointSpacing * i;
            float y = getYForTemp(hourlyDataList.get(i).temperature, top, bottom);
            canvas.drawCircle(x, y, 4 * density, tempPointPaint);

            if (i % tempLabelInterval == 0 || i == hourlyDataList.size() - 1) {
                String tempText = hourlyDataList.get(i).temperature + "°";
                tempTextPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(tempText, x, y - 10, tempTextPaint);
            }
        }
    }

    private void drawTimeLabels(Canvas canvas, float left, float bottom, float pointSpacing) {
        int labelInterval = Math.max(1, hourlyDataList.size() / 8);

        for (int i = 0; i < hourlyDataList.size(); i += labelInterval) {
            HourlyData data = hourlyDataList.get(i);
            float x = left + pointSpacing * i;

            String label = data.time;
            if (label.length() >= 11) {
                label = label.substring(11, 16);
            } else if (label.length() >= 6) {
                label = label.substring(label.length() - 5);
            }
            label = label.replace("T", " ");

            labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, x, bottom + 20, labelPaint);
        }
    }

    private void drawTouchIndicator(Canvas canvas, float left, float top,
                                     float bottom, float pointSpacing) {
        if (touchIndex < 0 || touchIndex >= hourlyDataList.size()) return;

        float x = left + pointSpacing * touchIndex;
        float y = getYForTemp(hourlyDataList.get(touchIndex).temperature, top, bottom);

        canvas.drawLine(x, top, x, bottom, touchIndicatorPaint);
        canvas.drawCircle(x, y, 8, touchIndicatorPaint);

        drawTooltip(canvas, x, y);
    }

    private void drawTooltip(Canvas canvas, float x, float y) {
        HourlyData data = hourlyDataList.get(touchIndex);

        float density = getResources().getDisplayMetrics().density;
        float padding = 8 * density;
        float lineHeight = 15 * density;
        float iconSize = 20 * density;

        String timeText = formatTime(data.time);
        String tempText = data.temperature + "°C";
        String precipText = data.precipitation > 0 ? "降水 " + data.precipitation + "%" : "无降水";
        String humidityText = "湿度 " + data.humidity + "%";
        String weatherDesc = QWeatherIconMapper.getWeatherTextDesc(data.weatherIcon);

        float maxWidth = 0;
        maxWidth = Math.max(maxWidth, tooltipTextPaint.measureText(timeText));
        maxWidth = Math.max(maxWidth, tooltipTextPaint.measureText(tempText));
        maxWidth = Math.max(maxWidth, tooltipTextPaint.measureText(precipText));
        maxWidth = Math.max(maxWidth, tooltipTextPaint.measureText(humidityText));
        if (weatherDesc != null) {
            maxWidth = Math.max(maxWidth, tooltipTextPaint.measureText(weatherDesc));
        }

        float tooltipWidth = maxWidth + padding * 2 + iconSize + 4 * density;
        int lineCount = weatherDesc != null && !weatherDesc.isEmpty() ? 5 : 4;
        float tooltipHeight = lineCount * lineHeight + padding * 2;

        float tooltipX = x - tooltipWidth / 2;
        float maxRight = chartLeftPadding + getChartAreaWidthForDraw() - chartRightPadding;
        if (tooltipX < chartLeftPadding) tooltipX = chartLeftPadding;
        if (tooltipX + tooltipWidth > maxRight)
            tooltipX = maxRight - tooltipWidth;

        float tooltipY = y - tooltipHeight - 16;
        if (tooltipY < chartTopPadding) tooltipY = y + 16;

        RectF tooltipRect = new RectF(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight);
        canvas.drawRoundRect(tooltipRect, 8 * density, 8 * density, tooltipBgPaint);

        float textX = tooltipX + padding + iconSize + 4 * density;
        float textY = tooltipY + padding + lineHeight * 0.8f;
        tooltipTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(timeText, textX, textY, tooltipTextPaint);
        textY += lineHeight;
        canvas.drawText(tempText, textX, textY, tooltipTextPaint);
        textY += lineHeight;
        canvas.drawText(precipText, textX, textY, tooltipTextPaint);
        textY += lineHeight;
        canvas.drawText(humidityText, textX, textY, tooltipTextPaint);
        if (weatherDesc != null && !weatherDesc.isEmpty()) {
            textY += lineHeight;
            canvas.drawText(weatherDesc, textX, textY, tooltipTextPaint);
        }

        if (data.weatherIcon != null && !data.weatherIcon.isEmpty()) {
            Drawable iconDrawable = QWeatherIconMapper.getIconDrawable(data.weatherIcon, getContext(), (int) iconSize);
            if (iconDrawable != null) {
                int halfIcon = (int) (iconSize / 2);
                float iconCenterY = tooltipY + padding + lineHeight * 0.8f;
                iconDrawable.setBounds((int) (tooltipX + padding), (int) (iconCenterY - halfIcon),
                        (int) (tooltipX + padding + iconSize), (int) (iconCenterY + halfIcon));
                iconDrawable.draw(canvas);
            }
        }
    }

    private String formatTime(String time) {
        if (time == null || time.isEmpty()) return "";
        if (time.length() >= 16) {
            return time.substring(5, 16).replace("T", " ");
        } else if (time.length() >= 11) {
            return time.substring(5).replace("T", " ");
        }
        return time;
    }

    private void drawDailyChart(Canvas canvas) {
        if (dailyDataList.isEmpty()) {
            drawNoDataMessage(canvas);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float left = chartLeftPadding;
        float top = chartTopPadding;
        float right = left + getChartAreaWidthForDraw();
        float bottom = top + chartAreaHeight;

        drawTemperatureGrid(canvas, left, top, right, bottom);

        int dataCount = dailyDataList.size();
        if (dataCount < 1) return;

        float itemSpacing = getEffectivePointSpacing();
        float itemCenterX;

        float pointRadius = 7 * density;

        float highY, lowY;

        float[] highPoints = new float[dataCount * 2];
        float[] lowPoints = new float[dataCount * 2];

        for (int i = 0; i < dataCount; i++) {
            DailyData data = dailyDataList.get(i);
            itemCenterX = left + itemSpacing * i + itemSpacing / 2;

            highY = getYForTemp(data.highTemp, top, bottom);
            lowY = getYForTemp(data.lowTemp, top, bottom);

            highPoints[i * 2] = itemCenterX;
            highPoints[i * 2 + 1] = highY;
            lowPoints[i * 2] = itemCenterX;
            lowPoints[i * 2 + 1] = lowY;

            float lineLeft = itemCenterX - itemSpacing * 0.25f;
            float lineRight = itemCenterX + itemSpacing * 0.25f;

            canvas.drawRect(lineLeft, highY, lineRight, lowY, dailyRangePaint);

            canvas.drawCircle(itemCenterX, highY, pointRadius, dailyHighPaint);
            canvas.drawCircle(itemCenterX, lowY, pointRadius, dailyLowPaint);

            tempTextPaint.setColor(ThemeColors.get(R.color.hc_fff4511e));
            tempTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(data.highTemp + "°", itemCenterX, highY - pointRadius - 8, tempTextPaint);

            tempTextPaint.setColor(ThemeColors.get(R.color.hc_ff42a5f5));
            canvas.drawText(data.lowTemp + "°", itemCenterX, lowY + pointRadius + 14, tempTextPaint);

            float dayIconSize = 16 * density;
            if (data.dayWeatherIcon != null && !data.dayWeatherIcon.isEmpty()) {
                Drawable dayIcon = QWeatherIconMapper.getIconDrawable(data.dayWeatherIcon, getContext(), (int) dayIconSize);
                if (dayIcon != null) {
                    float iconY = highY - pointRadius - 10 - dayIconSize;
                    if (iconY < top) iconY = highY + pointRadius + 10;
                    int halfIcon = (int) (dayIconSize / 2);
                    dayIcon.setBounds((int) (itemCenterX - halfIcon), (int) (iconY - halfIcon),
                            (int) (itemCenterX + halfIcon), (int) (iconY + halfIcon));
                    dayIcon.draw(canvas);
                }
            }

            float nightIconSize = 14 * density;
            if (data.nightWeatherIcon != null && !data.nightWeatherIcon.isEmpty() &&
                !data.nightWeatherIcon.equals(data.dayWeatherIcon)) {
                Drawable nightIcon = QWeatherIconMapper.getIconDrawable(data.nightWeatherIcon, getContext(), (int) nightIconSize);
                if (nightIcon != null) {
                    float iconY = lowY + pointRadius + 10 + nightIconSize;
                    if (iconY > bottom) iconY = lowY - pointRadius - 10;
                    int halfIcon = (int) (nightIconSize / 2);
                    nightIcon.setBounds((int) (itemCenterX - halfIcon), (int) (iconY - halfIcon),
                            (int) (itemCenterX + halfIcon), (int) (iconY + halfIcon));
                    nightIcon.draw(canvas);
                }
            }
        }

        float smoothFactor = 0.2f;

        Path highLinePath = new Path();
        Path rangeFillPath = new Path();
        highLinePath.moveTo(highPoints[0], highPoints[1]);
        rangeFillPath.moveTo(highPoints[0], highPoints[1]);

        for (int i = 0; i < highPoints.length - 2; i += 2) {
            float p0x = i >= 2 ? highPoints[i - 2] : highPoints[i];
            float p0y = i >= 2 ? highPoints[i - 1] : highPoints[i + 1];

            float p1x = highPoints[i];
            float p1y = highPoints[i + 1];

            float p2x = highPoints[i + 2];
            float p2y = highPoints[i + 3];

            float p3x = i + 4 < highPoints.length ? highPoints[i + 4] : p2x;
            float p3y = i + 4 < highPoints.length ? highPoints[i + 5] : p2y;

            float cp1x = p1x + (p2x - p0x) * smoothFactor;
            float cp1y = p1y + (p2y - p0y) * smoothFactor;
            float cp2x = p2x - (p3x - p1x) * smoothFactor;
            float cp2y = p2y - (p3y - p1y) * smoothFactor;

            highLinePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
        }

        float[] tempLowPoints = new float[lowPoints.length];
        for (int i = 0; i < lowPoints.length; i++) {
            tempLowPoints[i] = lowPoints[i];
        }

        rangeFillPath.lineTo(tempLowPoints[0], tempLowPoints[1]);

        for (int i = 0; i < tempLowPoints.length - 2; i += 2) {
            float p0x = i >= 2 ? tempLowPoints[i - 2] : tempLowPoints[i];
            float p0y = i >= 2 ? tempLowPoints[i - 1] : tempLowPoints[i + 1];

            float p1x = tempLowPoints[i];
            float p1y = tempLowPoints[i + 1];

            float p2x = tempLowPoints[i + 2];
            float p2y = tempLowPoints[i + 3];

            float p3x = i + 4 < tempLowPoints.length ? tempLowPoints[i + 4] : p2x;
            float p3y = i + 4 < tempLowPoints.length ? tempLowPoints[i + 5] : p2y;

            float cp1x = p1x + (p2x - p0x) * smoothFactor;
            float cp1y = p1y + (p2y - p0y) * smoothFactor;
            float cp2x = p2x - (p3x - p1x) * smoothFactor;
            float cp2y = p2y - (p3y - p1y) * smoothFactor;

            rangeFillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
        }

        Path lowLinePath = new Path();
        lowLinePath.moveTo(lowPoints[0], lowPoints[1]);
        for (int i = 0; i < lowPoints.length - 2; i += 2) {
            float p0x = i >= 2 ? lowPoints[i - 2] : lowPoints[i];
            float p0y = i >= 2 ? lowPoints[i - 1] : lowPoints[i + 1];

            float p1x = lowPoints[i];
            float p1y = lowPoints[i + 1];

            float p2x = lowPoints[i + 2];
            float p2y = lowPoints[i + 3];

            float p3x = i + 4 < lowPoints.length ? lowPoints[i + 4] : p2x;
            float p3y = i + 4 < lowPoints.length ? lowPoints[i + 5] : p2y;

            float cp1x = p1x + (p2x - p0x) * smoothFactor;
            float cp1y = p1y + (p2y - p0y) * smoothFactor;
            float cp2x = p2x - (p3x - p1x) * smoothFactor;
            float cp2y = p2y - (p3y - p1y) * smoothFactor;

            lowLinePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
        }

        rangeFillPath.close();

        canvas.drawPath(rangeFillPath, dailyRangePaint);
        canvas.drawPath(highLinePath, dailyHighLinePaint);
        canvas.drawPath(lowLinePath, dailyLowLinePaint);

        for (int i = 0; i < dataCount; i++) {
            DailyData data = dailyDataList.get(i);
            itemCenterX = left + itemSpacing * i + itemSpacing / 2;

            String dayLabel = data.dateLabel;
            if (dayLabel.length() > 10) {
                dayLabel = dayLabel.substring(5);
            }
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setColor(ThemeColors.get(R.color.hc_ff666666));
            canvas.drawText(dayLabel, itemCenterX, bottom + 20, labelPaint);

            if (i == 0 || i == dataCount - 1) {
                String shortLabel = dayLabel.length() > 5 ? dayLabel.substring(dayLabel.length() - 5) : dayLabel;
                canvas.drawText(shortLabel, itemCenterX, bottom + 38, labelPaint);
            }
        }

        if (touchIndex >= 0 && touchIndex < dailyDataList.size()) {
            itemCenterX = left + itemSpacing * touchIndex + itemSpacing / 2;
            highY = getYForTemp(dailyDataList.get(touchIndex).highTemp, top, bottom);
            lowY = getYForTemp(dailyDataList.get(touchIndex).lowTemp, top, bottom);

            canvas.drawLine(itemCenterX, top, itemCenterX, bottom, touchIndicatorPaint);

            DailyData data = dailyDataList.get(touchIndex);
            String tooltipText = String.format("%s\n高: %d° 低: %d°\n%s %s\n湿度: %d%%",
                    data.dateLabel, data.highTemp, data.lowTemp,
                    data.windDirection != null ? data.windDirection : "",
                    data.windScale != null ? data.windScale : "",
                    data.humidity);

            drawDailyTooltip(canvas, itemCenterX, highY, tooltipText);
        }
    }

    private void drawDailyTooltip(Canvas canvas, float x, float y, String text) {
        float density = getResources().getDisplayMetrics().density;
        float padding = 8 * density;
        float lineHeight = 14 * density;
        float iconSize = 18 * density;

        String[] lines = text.split("\n");
        float maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, tooltipTextPaint.measureText(line));
        }

        float tooltipWidth = maxWidth + padding * 2 + iconSize + 4 * density;
        float tooltipHeight = lines.length * lineHeight + padding * 2;

        float tooltipX = x - tooltipWidth / 2;
        float maxRight = chartLeftPadding + getChartAreaWidthForDraw() - chartRightPadding;
        if (tooltipX < chartLeftPadding) tooltipX = chartLeftPadding;
        if (tooltipX + tooltipWidth > maxRight)
            tooltipX = maxRight - tooltipWidth;

        float tooltipY = y - tooltipHeight - 16;
        if (tooltipY < chartTopPadding) tooltipY = y + 16;

        RectF tooltipRect = new RectF(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight);
        canvas.drawRoundRect(tooltipRect, 8 * density, 8 * density, tooltipBgPaint);

        float textX = tooltipX + padding + iconSize + 4 * density;
        float textY = tooltipY + padding + lineHeight * 0.8f;
        tooltipTextPaint.setTextAlign(Paint.Align.LEFT);
        for (String line : lines) {
            canvas.drawText(line, textX, textY, tooltipTextPaint);
            textY += lineHeight;
        }

        if (touchIndex >= 0 && touchIndex < dailyDataList.size()) {
            DailyData data = dailyDataList.get(touchIndex);
            if (data.dayWeatherIcon != null && !data.dayWeatherIcon.isEmpty()) {
                Drawable iconDrawable = QWeatherIconMapper.getIconDrawable(data.dayWeatherIcon, getContext(), (int) iconSize);
                if (iconDrawable != null) {
                    int halfIcon = (int) (iconSize / 2);
                    float iconCenterY = tooltipY + padding + lineHeight * 0.8f;
                    iconDrawable.setBounds((int) (tooltipX + padding), (int) (iconCenterY - halfIcon),
                            (int) (tooltipX + padding + iconSize), (int) (iconCenterY + halfIcon));
                    iconDrawable.draw(canvas);
                }
            }
        }
    }

    private void drawNoDataMessage(Canvas canvas) {
        String message = isHourlyMode ? "暂无逐小时数据" : "暂无每日预报数据";
        float textWidth = labelPaint.measureText(message);
        float x = (getWidth() - textWidth) / 2;
        float y = getHeight() / 2;
        labelPaint.setColor(ThemeColors.get(R.color.hc_ff999999));
        canvas.drawText(message, x, y, labelPaint);
        labelPaint.setColor(ThemeColors.get(R.color.hc_ff666666));
    }

    private float getYForTemp(int temp, float top, float bottom) {
        float range = maxTemp - minTemp;
        if (range <= 0) return (top + bottom) / 2;
        float normalized = (float) (maxTemp - temp) / range;
        return top + normalized * (bottom - top);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        if (isHourlyMode && hourlyDataList.isEmpty()) return false;
        if (!isHourlyMode && dailyDataList.isEmpty()) return false;

        float left = chartLeftPadding;
        float bottom = chartTopPadding + chartAreaHeight;

        int dataCount = isHourlyMode ? hourlyDataList.size() : dailyDataList.size();
        if (dataCount < 2) return false;

        float pointSpacing = getEffectivePointSpacing();
        float touchX = event.getX() + getScrollX() - left;

        int newIndex = Math.round(touchX / pointSpacing);
        newIndex = Math.max(0, Math.min(newIndex, dataCount - 1));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                touchIndex = newIndex;
                this.touchX = event.getX();
                this.touchY = event.getY();
                invalidate();

                if (touchListener != null) {
                    if (isHourlyMode) {
                        touchListener.onDataTouched(newIndex, hourlyDataList.get(newIndex));
                    } else {
                        touchListener.onDailyDataTouched(newIndex, dailyDataList.get(newIndex));
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchIndex = -1;
                invalidate();
                if (touchListener != null) {
                    touchListener.onTouchEnded();
                }
                return true;
        }
        return false;
    }

    public void setOnDataTouchListener(OnDataTouchListener listener) {
        this.touchListener = listener;
    }

    public List<HourlyData> getHourlyDataList() {
        return hourlyDataList;
    }

    public List<DailyData> getDailyDataList() {
        return dailyDataList;
    }

    public boolean isHourlyMode() {
        return isHourlyMode;
    }

    public int getTouchIndex() {
        return touchIndex;
    }
}