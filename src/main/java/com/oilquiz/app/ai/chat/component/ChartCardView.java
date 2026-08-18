package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 图表卡片组件：柱状图 / 折线图 / 饼图。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "chartType": "bar" | "line" | "pie",
 *   "title": "图表标题",
 *   "categories": ["1月","2月","3月"],
 *   "series": [{"name":"系列名","data":[120,200,150]}]
 * }
 * </pre>
 */
public class ChartCardView implements ChatComponent {

    @Override
    public String getType() {
        return "chart";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        String chartType = data.props.optString("chartType", "");
        if (chartType.isEmpty()) return false;
        JSONArray series = data.props.optJSONArray("series");
        return series != null && series.length() > 0;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String chartType = p.optString("chartType", "bar");
        String title = p.optString("title", "");
        JSONArray categories = p.optJSONArray("categories");
        JSONArray series = p.optJSONArray("series");

        // 解析系列数据
        List<Series> seriesList = new ArrayList<>();
        for (int i = 0; i < series.length(); i++) {
            JSONObject s = series.optJSONObject(i);
            if (s == null) continue;
            Series sd = new Series();
            sd.name = s.optString("name", "系列" + (i + 1));
            sd.color = ComponentColors.chartColor(context, i);
            JSONArray dataArr = s.optJSONArray("data");
            if (dataArr != null) {
                for (int j = 0; j < dataArr.length(); j++) {
                    sd.values.add((float) dataArr.optDouble(j, 0));
                }
            }
            seriesList.add(sd);
        }
        if (seriesList.isEmpty()) return null;

        // 分类标签
        List<String> cats = new ArrayList<>();
        if (categories != null) {
            for (int i = 0; i < categories.length(); i++) {
                cats.add(categories.optString(i, ""));
            }
        }

        // 组装卡片
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));
        card.setClickable(false);

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        ChartView chartView = new ChartView(context);
        chartView.setChartData(chartType, cats, seriesList);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                "pie".equals(chartType) ? dp(context, 190) : dp(context, 170));
        card.addView(chartView, lp);

        return card;
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /** 系列数据 */
    private static class Series {
        String name;
        int color;
        List<Float> values = new ArrayList<>();
    }

    /** 图表绘制 View */
    private static class ChartView extends View {

        private String chartType = "bar";
        private List<String> categories = new ArrayList<>();
        private List<Series> seriesList = new ArrayList<>();

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChartView(Context context) {
            super(context);
            textPaint.setTextSize(sp(10));
            textPaint.setColor(ComponentColors.textSecondary(context));
        }

        void setChartData(String type, List<String> cats, List<Series> series) {
            this.chartType = type;
            this.categories = cats;
            this.seriesList = series;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (seriesList.isEmpty()) return;
            if ("pie".equals(chartType)) {
                drawPie(canvas);
            } else {
                drawAxisChart(canvas);
            }
        }

        /** 柱状图 / 折线图（共用坐标轴逻辑） */
        private void drawAxisChart(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int padLeft = dp(8);
            int padRight = dp(8);
            int padTop = dp(6);
            int padBottom = dp(18);   // X 轴标签区
            int chartW = w - padLeft - padRight;
            int chartH = h - padTop - padBottom;

            // 数据最大值（含 0 兜底；负值按 0 处理）
            float maxVal = 0;
            int pointCount = 0;
            for (Series s : seriesList) {
                for (Float v : s.values) {
                    if (v != null && v > maxVal) maxVal = v;
                }
                pointCount = Math.max(pointCount, s.values.size());
            }
            if (maxVal <= 0) maxVal = 1;
            maxVal = (float) (maxVal * 1.1);

            // Y 轴网格线（4 条）
            textPaint.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i <= 4; i++) {
                float ratio = i / 4f;
                float y = padTop + chartH * (1 - ratio);
                paint.setColor(ComponentColors.border(getContext()));
                paint.setStrokeWidth(dp(1));
                canvas.drawLine(padLeft, y, w - padRight, y, paint);
                String label = String.valueOf(Math.round(maxVal * ratio));
                canvas.drawText(label, dp(2), y - dp(3), textPaint);
            }

            int n = Math.max(pointCount, 1);
            float slotW = chartW / (float) n;

            if ("line".equals(chartType)) {
                drawLines(canvas, padLeft, padTop, chartH, slotW, maxVal, n);
            } else {
                drawBars(canvas, padLeft, padTop, chartH, slotW, maxVal, n);
            }

            // X 轴分类标签
            if (categories.size() == n) {
                textPaint.setTextAlign(Paint.Align.CENTER);
                for (int i = 0; i < n; i++) {
                    float cx = padLeft + slotW * i + slotW / 2f;
                    String cat = categories.get(i);
                    canvas.drawText(cat, cx, h - dp(4), textPaint);
                }
            }
        }

        private void drawBars(Canvas canvas, int padLeft, int padTop, int chartH, float slotW, float maxVal, int n) {
            int seriesCount = seriesList.size();
            float groupW = slotW * 0.7f;
            float barW = groupW / seriesCount;
            for (int s = 0; s < seriesList.size(); s++) {
                Series series = seriesList.get(s);
                paint.setColor(series.color);
                for (int i = 0; i < series.values.size() && i < n; i++) {
                    float raw = series.values.get(i);
                    float v = Math.max(0f, raw);   // 负值按 0 绘制
                    float barH = (v / maxVal) * chartH;
                    float left = padLeft + slotW * i + (slotW - groupW) / 2f + s * barW;
                    float top = padTop + chartH - barH;
                    RectF r = new RectF(left, top, left + barW - dp(2), padTop + chartH);
                    canvas.drawRoundRect(r, dp(2), dp(2), paint);
                }
            }
        }

        private void drawLines(Canvas canvas, int padLeft, int padTop, int chartH, float slotW, float maxVal, int n) {
            for (Series series : seriesList) {
                Path path = new Path();
                paint.setColor(series.color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                boolean first = true;
                for (int i = 0; i < series.values.size() && i < n; i++) {
                    float v = series.values.get(i);
                    float x = padLeft + slotW * i + slotW / 2f;
                    float y = padTop + chartH - (v / maxVal) * chartH;
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                }
                canvas.drawPath(path, paint);

                // 数据点
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < series.values.size() && i < n; i++) {
                    float v = series.values.get(i);
                    float x = padLeft + slotW * i + slotW / 2f;
                    float y = padTop + chartH - (v / maxVal) * chartH;
                    canvas.drawCircle(x, y, dp(3), paint);
                }
                paint.setStyle(Paint.Style.STROKE);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        /** 饼图（第一个系列） + 图例 */
        private void drawPie(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            Series series = seriesList.get(0);

            float total = 0;
            for (Float v : series.values) {
                if (v != null && v > 0) total += v;
            }
            if (total <= 0) return;

            int legendH = dp(46);
            int diameter = Math.min(w - dp(20), h - legendH);
            float cx = w / 2f;
            float cy = (h - legendH) / 2f;
            float radius = diameter / 2f - dp(6);
            RectF rect = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);

            float startAngle = -90f;
            for (int i = 0; i < series.values.size(); i++) {
                float v = series.values.get(i);
                if (v <= 0) continue;
                float sweep = v / total * 360f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ComponentColors.chartColor(getContext(), i));
                canvas.drawArc(rect, startAngle, sweep - 0.5f, true, paint);
                startAngle += sweep;
            }

            // 图例（底部）
            float legendY = h - legendH + dp(18);
            float x = dp(10);
            textPaint.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i < series.values.size(); i++) {
                String label = (categories.size() > i && !categories.get(i).isEmpty())
                        ? categories.get(i) : "第" + (i + 1) + "项";
                float labelW = textPaint.measureText(label) + dp(24);
                if (x + labelW > w) {
                    x = dp(10);
                    legendY += dp(16);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ComponentColors.chartColor(getContext(), i));
                canvas.drawCircle(x + dp(4), legendY - dp(4), dp(5), paint);
                canvas.drawText(label, x + dp(14), legendY, textPaint);
                x += labelW;
            }
        }

        private int dp(float value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    getResources().getDisplayMetrics());
        }

        private float sp(float value) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                    getResources().getDisplayMetrics());
        }
    }
}
