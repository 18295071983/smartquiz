package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 分钟级降水趋势图：渐变面积图显示未来2小时每5分钟的降水量。
 *
 * 设计要点：
 * - 平滑贝塞尔曲线 + 渐变填充（类似苹果天气）
 * - 面积颜色随最大降水强度变化：无降水(淡灰) → 小雨(浅蓝) → 中雨(蓝) → 大雨(深蓝) → 暴雨(紫)
 * - 固定步长，24个点总宽超过屏幕，支持横向滚动
 * - "现在"标记：虚线 + 顶部胶囊徽标
 * - 时间轴每15分钟一个标签
 * - 轻量水平网格线辅助读图
 */
public class MinutelyPrecipChartView extends View {

    public static class MinutelyData {
        public String time;       // "14:30"
        public float precip;      // 降水量 mm
        public String type;       // "rain" / "snow"

        public MinutelyData(String time, float precip, String type) {
            this.time = time;
            this.precip = precip;
            this.type = type;
        }
    }

    private final List<MinutelyData> dataList = new ArrayList<>();
    private final float density;

    // 布局参数
    private static final float STEP_DP = 16f;        // 每个数据点水平步长
    private static final float SIDE_PADDING_DP = 16f;
    private static final int DEFAULT_COUNT = 24;      // 2h / 5min

    // 降水强度阈值（mm）
    private static final float LIGHT_RAIN = 0.1f;
    private static final float MODERATE_RAIN = 1.5f;
    private static final float HEAVY_RAIN = 3.0f;
    private static final float STORM_RAIN = 6.0f;
    private static final float MAX_SCALE = 10.0f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noDataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MinutelyPrecipChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        init();
    }

    public MinutelyPrecipChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        init();
    }

    public MinutelyPrecipChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setAntiAlias(true);

        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);

        timePaint.setColor(0x99FFFFFF);
        timePaint.setTextSize(10 * density);
        timePaint.setTextAlign(Paint.Align.CENTER);

        nowLinePaint.setColor(0xFFFFFFFF);
        nowLinePaint.setStrokeWidth(1f * density);
        nowLinePaint.setAlpha(140);
        nowLinePaint.setStyle(Paint.Style.STROKE);
        nowLinePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{4 * density, 3 * density}, 0));

        nowBadgePaint.setColor(0xFFFFFFFF);
        nowBadgePaint.setAntiAlias(true);

        nowBadgeTextPaint.setColor(0xFF1976D2);
        nowBadgeTextPaint.setTextSize(10 * density);
        nowBadgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        nowBadgeTextPaint.setTextAlign(Paint.Align.CENTER);

        gridPaint.setColor(0x14FFFFFF);
        gridPaint.setStrokeWidth(1f * density);

        baselinePaint.setColor(0x33FFFFFF);
        baselinePaint.setStrokeWidth(1f * density);

        endLabelPaint.setColor(0x80FFFFFF);
        endLabelPaint.setTextSize(10 * density);
        endLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        endLabelPaint.setTextAlign(Paint.Align.CENTER);

        noDataPaint.setColor(0x66FFFFFF);
        noDataPaint.setTextSize(12 * density);
        noDataPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(List<MinutelyData> data) {
        dataList.clear();
        if (data != null) {
            dataList.addAll(data);
        }
        requestLayout();
        invalidate();
    }

    public boolean hasData() {
        return !dataList.isEmpty();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (120 * density);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        if (mode == MeasureSpec.AT_MOST || mode == MeasureSpec.UNSPECIFIED) {
            height = desiredHeight;
        }

        int count = Math.max(dataList.size(), DEFAULT_COUNT);
        float contentWidth = SIDE_PADDING_DP * density * 2 + (count - 1) * STEP_DP * density;
        int width = (int) Math.max(contentWidth, getSuggestedMinimumWidth());
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (dataList.isEmpty()) return;

        int w = getWidth();
        int h = getHeight();
        float padding = SIDE_PADDING_DP * density;
        float timeAxisH = 18 * density;
        float topBadgeH = 18 * density;
        float chartTop = topBadgeH + 4 * density;
        float chartBottom = h - timeAxisH;
        float chartHeight = chartBottom - chartTop;

        if (chartHeight <= 0) return;

        int count = dataList.size();
        float step = STEP_DP * density;

        // 计算最大降水量
        float maxPrecip = 0;
        for (MinutelyData d : dataList) {
            if (d.precip > maxPrecip) maxPrecip = d.precip;
        }
        float scaleMax = Math.max(maxPrecip, MAX_SCALE * 0.3f);

        // 判断是否有降水
        boolean hasAnyPrecip = maxPrecip >= LIGHT_RAIN;
        int areaColor = getAreaColor(maxPrecip, dataList.get(0).type);

        // 绘制水平网格线（3条：25%/50%/75%）
        for (int i = 1; i <= 3; i++) {
            float y = chartBottom - chartHeight * (i / 4f);
            canvas.drawLine(padding, y, w - padding, y, gridPaint);
        }

        // 计算每个数据点的坐标
        float[] xs = new float[count];
        float[] ys = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i] = padding + i * step;
            ys[i] = chartBottom - (dataList.get(i).precip / scaleMax) * chartHeight;
        }

        if (hasAnyPrecip) {
            // 构建平滑曲线路径
            Path curvePath = buildSmoothCurve(xs, ys);

            // 构建填充区域路径（曲线 + 底部封闭）
            Path fillPath = new Path(curvePath);
            fillPath.lineTo(xs[count - 1], chartBottom);
            fillPath.lineTo(xs[0], chartBottom);
            fillPath.close();

            // 渐变填充：顶部颜色 → 底部透明
            LinearGradient fillShader = new LinearGradient(
                    0, chartTop, 0, chartBottom,
                    areaColor, adjustAlpha(areaColor, 0.05f),
                    Shader.TileMode.CLAMP);
            fillPaint.setShader(fillShader);
            fillPaint.setAntiAlias(true);
            canvas.drawPath(fillPath, fillPaint);
            fillPaint.setShader(null);

            // 绘制顶部曲线线条
            linePaint.setColor(adjustAlpha(areaColor, 0.95f));
            canvas.drawPath(curvePath, linePaint);

            // 绘制关键点：当前点（第一点）和峰值点
            pointPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(xs[0], ys[0], 4 * density, pointPaint);
            pointPaint.setColor(adjustAlpha(areaColor, 1.0f));
            canvas.drawCircle(xs[0], ys[0], 2.5f * density, pointPaint);

            // 峰值点标记
            int peakIdx = 0;
            for (int i = 1; i < count; i++) {
                if (dataList.get(i).precip > dataList.get(peakIdx).precip) peakIdx = i;
            }
            if (peakIdx != 0 && dataList.get(peakIdx).precip >= MODERATE_RAIN) {
                pointPaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(xs[peakIdx], ys[peakIdx], 3.5f * density, pointPaint);
                pointPaint.setColor(adjustAlpha(areaColor, 1.0f));
                canvas.drawCircle(xs[peakIdx], ys[peakIdx], 2 * density, pointPaint);
            }
        } else {
            // 无降水：绘制底部基线
            canvas.drawLine(padding, chartBottom - 1 * density,
                    w - padding, chartBottom - 1 * density, baselinePaint);
        }

        // 绘制"现在"标记
        float nowX = xs[0];
        drawNowMarker(canvas, nowX, chartTop - topBadgeH, chartBottom);

        // 绘制时间轴标签（每15分钟 = 3个点）
        int labelInterval = 3;
        float timeY = h - 4 * density;
        for (int i = 0; i < count; i += labelInterval) {
            String time = dataList.get(i).time;
            if (time != null && !time.isEmpty()) {
                canvas.drawText(time, xs[i], timeY, timePaint);
            }
        }

        // 末尾标注"2h后"
        canvas.drawText("2h后", xs[count - 1], timeY, endLabelPaint);
    }

    /**
     * 构建平滑贝塞尔曲线（Catmull-Rom 转 Bézier）
     */
    private Path buildSmoothCurve(float[] xs, float[] ys) {
        Path path = new Path();
        int n = xs.length;
        if (n == 0) return path;
        path.moveTo(xs[0], ys[0]);
        if (n == 1) return path;

        float smoothFactor = 0.2f;
        for (int i = 0; i < n - 1; i++) {
            float p0x = i >= 1 ? xs[i - 1] : xs[i];
            float p0y = i >= 1 ? ys[i - 1] : ys[i];
            float p1x = xs[i];
            float p1y = ys[i];
            float p2x = xs[i + 1];
            float p2y = ys[i + 1];
            float p3x = (i + 2 < n) ? xs[i + 2] : p2x;
            float p3y = (i + 2 < n) ? ys[i + 2] : p2y;

            float cp1x = p1x + (p2x - p0x) * smoothFactor;
            float cp1y = p1y + (p2y - p0y) * smoothFactor;
            float cp2x = p2x - (p3x - p1x) * smoothFactor;
            float cp2y = p2y - (p3y - p1y) * smoothFactor;

            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y);
        }
        return path;
    }

    /**
     * 绘制"现在"标记：顶部胶囊徽标 + 垂直虚线
     */
    private void drawNowMarker(Canvas canvas, float centerX, float top, float bottom) {
        Path dashPath = new Path();
        dashPath.moveTo(centerX, top + 14 * density);
        dashPath.lineTo(centerX, bottom);
        canvas.drawPath(dashPath, nowLinePaint);

        String text = "现在";
        float textWidth = nowBadgeTextPaint.measureText(text);
        float pillW = textWidth + 12 * density;
        float pillH = 16 * density;
        float pillLeft = centerX - pillW / 2f;
        float pillTop = top;
        RectF pillRect = new RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH);
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, nowBadgePaint);

        Paint.FontMetrics fm = nowBadgeTextPaint.getFontMetrics();
        float textY = pillTop + pillH / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, centerX, textY, nowBadgeTextPaint);
    }

    /**
     * 根据最大降水强度返回面积图主色
     */
    private int getAreaColor(float maxPrecip, String type) {
        boolean isSnow = "snow".equals(type);
        if (isSnow) {
            if (maxPrecip < LIGHT_RAIN) return 0x40E0F7FF;
            if (maxPrecip < MODERATE_RAIN) return 0xFFB3E5FC;
            if (maxPrecip < HEAVY_RAIN) return 0xFF90CAF9;
            return 0xFF64B5F6;
        }
        // rain
        if (maxPrecip < LIGHT_RAIN) return 0x4090CAF9;
        if (maxPrecip < MODERATE_RAIN) return 0xFF64B5F6;   // 浅蓝
        if (maxPrecip < HEAVY_RAIN) return 0xFF1976D2;       // 蓝
        if (maxPrecip < STORM_RAIN) return 0xFF1565C0;       // 深蓝
        return 0xFF7B1FA2;                                    // 紫色(暴雨)
    }

    private int adjustAlpha(int color, float alpha) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return Color.argb((int) (Color.alpha(color) * alpha), r, g, b);
    }
}
