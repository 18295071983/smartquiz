package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 图表卡片组件：柱状 / 折线 / 面积 / 堆叠柱 / 饼 / 散点 / 雷达（2026-10-23 扩型）。
 *
 * <p>扩型的原因是"生产者不止 python_chart 一个"：表格类结果（excel_tool / database）现在
 * 会自动挑出"一列标签 + 数值列"生成图表卡，python_chart 也能 render=card 直接给数据。
 * 只要数据是 `categories + series` 形状，这些类型就能画，不必等某个工具出 PNG。</p>
 *
 * <p>数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "chartType": "bar | line | area | stacked_bar | pie | scatter | radar",
 *   "title": "图表标题",
 *   "categories": ["1月","2月","3月"],                  // 饼图时与 series[0].data 一一对应
 *   "series": [
 *     {"name":"系列名","data":[120,200,150]},            // 轴类/饼/雷达
 *     {"name":"散点","points":[[1,2],[2,4]]}             // scatter：数值 x/y（缺省时用 data 的下标做 x）
 *   ]
 * }
 * </pre>
 */
public class ChartCardView implements ChatComponent {

    /** 卡片正文高度（dp）：饼/散点/雷达需要更多空间 */
    static int bodyHeightDp(String chartType) {
        if ("pie".equals(chartType)) return 190;
        if ("scatter".equals(chartType)) return 190;
        if ("radar".equals(chartType)) return 200;
        return 170;
    }

    @Override
    public String getType() {
        return "chart";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null) return false;
        JSONObject n = normalize(data.type, data.props);
        if (n == null) return false;
        String chartType = n.optString("chartType", "");
        if (chartType.isEmpty()) return false;
        JSONArray series = n.optJSONArray("series");
        return series != null && series.length() > 0;
    }

    /**
     * 把"图表别名"的 props 形状归一到本卡真正读的 {@code chartType}/{@code series}/{@code categories}。
     *
     * <p>坑的来路：{@code pie_chart}/{@code bar_chart}/{@code line_chart}/{@code sparkline} 这些名字
     * 被 {@link ComponentTypes} 归一到本卡（原生实现赢名字），但工具说明教它们的参数仍是 layout 原语那套
     * {@code data}（如 {@code pie_chart(data:[{name,value}])}）。名字归一了、参数没归一 →
     * 模型照说明传就 {@code canRender=false} → {@code ComponentRegistry.render} 静默落
     * {@code DynamicCardView}，用户看到的是一张"收起原始 JSON"的文本卡，而模型以为图已经画出来了。</p>
     *
     * <p>只读不改：返回归一后的<b>副本</b>，绝不原地写回 {@code data.props}（它会被持久化/共享）。</p>
     */
    static JSONObject normalize(String requestedType, JSONObject p) {
        if (p == null) return null;
        JSONObject out;
        try {
            out = new JSONObject(p.toString());
        } catch (Exception e) {
            return p;
        }
        String chartType = p.optString("chartType", "");
        if (chartType.isEmpty()) {
            chartType = chartTypeOfTypeName(requestedType);
        }
        if (!chartType.isEmpty() && !out.has("chartType")) {
            putQuiet(out, "chartType", chartType);
        }

        JSONArray series = p.optJSONArray("series");
        boolean seriesUsable = series != null && series.length() > 0;
        JSONArray data = p.optJSONArray("data");
        if (!seriesUsable && data != null && data.length() > 0) {
            JSONArray cats = new JSONArray();
            JSONArray vals = new JSONArray();
            boolean namedPairs = true;
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) {
                    namedPairs = false;
                    break;
                }
                putArr(cats, o.optString("name", o.optString("label", "")));
                putArr(vals, o.optDouble("value", o.optDouble("y", 0d)));
            }
            JSONObject s = new JSONObject();
            putQuiet(s, "name", p.optString("seriesName", "数值"));
            putQuiet(s, "data", namedPairs ? vals : data);
            JSONArray built = new JSONArray();
            putArr(built, s);
            putQuiet(out, "series", built);
            if (namedPairs && !p.has("categories")) {
                putQuiet(out, "categories", cats);
            }
        }
        return out;
    }

    /** 别名本身带着图型时（pie_chart/area_chart/…），据此补 chartType。 */
    private static String chartTypeOfTypeName(String type) {
        if (type == null) return "";
        String t = ComponentTypes.normalize(type);
        if (t.contains("pie")) return "pie";
        if (t.contains("stacked")) return "stacked_bar";
        if (t.contains("area")) return "area";
        if (t.contains("scatter")) return "scatter";
        if (t.contains("radar")) return "radar";
        if (t.contains("bar")) return "bar";
        if (t.contains("line")) return "line";
        return "";
    }

    private static void putQuiet(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    /** Android 的 {@code JSONArray.put(double/Object)} 会抛 JSONException；归一是尽力而为，不因单点失败炸整张卡。 */
    private static void putArr(JSONArray a, Object v) {
        try {
            a.put(v);
        } catch (Exception ignored) {
        }
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = normalize(data.type, data.props != null ? data.props : new JSONObject());
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
            JSONArray pointsArr = s.optJSONArray("points");
            if (pointsArr != null) {
                for (int j = 0; j < pointsArr.length(); j++) {
                    JSONArray pair = pointsArr.optJSONArray(j);
                    if (pair != null && pair.length() >= 2) {
                        sd.points.add(new float[]{
                                (float) pair.optDouble(0, 0),
                                (float) pair.optDouble(1, 0)});
                    }
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

        // 组装卡片（外壳统一走 CardShell，图表只填 body）
        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_stats)
                .setHeader(title, chartTypeLabel(context, chartType));

        int points = 0;
        for (Series s : seriesList) {
            points += s.values.size() + s.points.size();
        }
        if (points == 0) {
            shell.setState(CardShell.State.EMPTY);
            return shell;
        }

        ChartView chartView = new ChartView(context);
        chartView.setChartData(chartType, cats, seriesList);
        chartView.setContentDescription(chartTypeLabel(context, chartType)
                + "：" + seriesList.size() + " 组 / " + points + " 个数据点");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ComponentTheme.dp(context, bodyHeightDp(chartType)));
        shell.addBody(chartView, lp);
        // 图表读不出数（分类多、色弱、TalkBack）时的降级入口：同一份数据渲染成表格
        shell.addAction(context.getString(R.string.component_chart_data_table), false,
                () -> showChartDataDialog(context, cats, seriesList,
                        chartTypeLabel(context, chartType)));
        return shell;
    }

    /**
     * 「查看数据表」：把 series/categories 交给 table_card 渲染后弹窗展示。
     * 复用现成组件而不是另写一套表格，保证与聊天流里的表格卡同一口径。
     */
    private static void showChartDataDialog(Context context, List<String> cats,
                                            List<Series> seriesList, String chartLabel) {
        try {
            org.json.JSONArray headers = new org.json.JSONArray();
            headers.put(cats.isEmpty() ? "项" : "分类");
            for (Series s : seriesList) headers.put(s.name);
            int rows = Math.max(cats.size(), 1);
            for (Series s : seriesList) {
                rows = Math.max(rows, Math.max(s.values.size(), s.points.size()));
            }
            org.json.JSONArray rowsArr = new org.json.JSONArray();
            for (int i = 0; i < rows; i++) {
                org.json.JSONArray row = new org.json.JSONArray();
                row.put(i < cats.size() ? cats.get(i) : "第 " + (i + 1) + " 项");
                for (Series s : seriesList) {
                    if (i < s.values.size()) {
                        row.put(String.valueOf(s.values.get(i)));
                    } else if (i < s.points.size()) {
                        float[] pt = s.points.get(i);
                        row.put(fmt(pt[0]) + ", " + fmt(pt[1]));
                    } else {
                        row.put("");
                    }
                }
                rowsArr.put(row);
            }
            JSONObject props = new JSONObject();
            props.put("headers", headers);
            props.put("rows", rowsArr);
            View table = ComponentRegistry.getInstance().render(context,
                    new ComponentData("table_card", props));
            androidx.appcompat.app.AlertDialog.Builder b =
                    new androidx.appcompat.app.AlertDialog.Builder(context)
                            .setTitle(context.getString(R.string.component_chart_data_title, chartLabel));
            if (table != null) b.setView(table);
            b.setNegativeButton(R.string.close, null).show();
        } catch (Throwable t) {
            android.util.Log.w("ChartCardView", "数据表弹窗失败: " + t.getMessage());
        }
    }

    /** 图表类型中文标签（TalkBack 与 meta 共用） */
    private static String chartTypeLabel(Context context, String chartType) {
        if ("line".equals(chartType)) {
            return context.getString(R.string.card_chart_type_line);
        }
        if ("pie".equals(chartType)) {
            return context.getString(R.string.card_chart_type_pie);
        }
        if ("area".equals(chartType)) {
            return context.getString(R.string.card_chart_type_area);
        }
        if ("stacked_bar".equals(chartType)) {
            return context.getString(R.string.card_chart_type_stacked_bar);
        }
        if ("scatter".equals(chartType)) {
            return context.getString(R.string.card_chart_type_scatter);
        }
        if ("radar".equals(chartType)) {
            return context.getString(R.string.card_chart_type_radar);
        }
        return context.getString(R.string.card_chart_type_bar);
    }

    // ---------------------------------------------------------------- 纯数据口径（可单测）

    /** 单系列最大值（负值按 0） */
    static float maxValue(List<Series> seriesList) {
        float max = 0;
        if (seriesList == null) return 0;
        for (Series s : seriesList) {
            for (Float v : s.values) {
                if (v != null && v > max) max = v;
            }
        }
        return max;
    }

    /** 堆叠柱：按"每类各系列之和"的最大值取坐标上限 */
    static float maxStackedTotal(List<Series> seriesList) {
        if (seriesList == null) return 0;
        int categories = 0;
        for (Series s : seriesList) categories = Math.max(categories, s.values.size());
        float max = 0;
        for (int i = 0; i < categories; i++) {
            float sum = 0;
            for (Series s : seriesList) {
                if (i < s.values.size()) sum += Math.max(0f, s.values.get(i));
            }
            if (sum > max) max = sum;
        }
        return max;
    }

    /** 散点边界 {minX,maxX,minY,maxY}；没有点也没值返回 null */
    static float[] scatterBounds(List<Series> seriesList) {
        if (seriesList == null) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (Series s : seriesList) {
            for (float[] pt : pointsOf(s)) {
                any = true;
                minX = Math.min(minX, pt[0]);
                maxX = Math.max(maxX, pt[0]);
                minY = Math.min(minY, pt[1]);
                maxY = Math.max(maxY, pt[1]);
            }
        }
        return any ? new float[]{minX, maxX, minY, maxY} : null;
    }

    /** 散点用点：显式 points 优先，否则把 data 下标当 x */
    static List<float[]> pointsOf(Series s) {
        if (s == null) return new ArrayList<>();
        if (!s.points.isEmpty()) return s.points;
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < s.values.size(); i++) {
            out.add(new float[]{i + 1, s.values.get(i)});
        }
        return out;
    }

    /** 轴刻度文案：整数不带小数，其余保留一位 */
    static String fmt(float v) {
        float r = Math.round(v);
        if (Math.abs(v - r) < 0.05f) return String.valueOf((int) r);
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 系列数据 */
    static class Series {
        String name;
        int color;
        List<Float> values = new ArrayList<>();
        /** 散点专用：数值 x/y */
        List<float[]> points = new ArrayList<>();
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
            } else if ("scatter".equals(chartType)) {
                drawScatter(canvas);
            } else if ("radar".equals(chartType)) {
                drawRadar(canvas);
            } else {
                drawAxisChart(canvas);
            }
        }

        /** 柱状图 / 折线图 / 面积图 / 堆叠柱（共用坐标轴逻辑） */
        private void drawAxisChart(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int padLeft = dp(8);
            int padRight = dp(8);
            int padTop = dp(6);
            int padBottom = dp(18);   // X 轴标签区
            int chartW = w - padLeft - padRight;
            int chartH = h - padTop - padBottom;

            // 数据最大值：堆叠柱要按"每类总和"取，否则柱会冲出坐标区
            float maxVal = "stacked_bar".equals(chartType)
                    ? maxStackedTotal(seriesList) : maxValue(seriesList);
            int pointCount = 0;
            for (Series s : seriesList) {
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
                drawLines(canvas, padLeft, padTop, chartH, slotW, maxVal, n, false);
            } else if ("area".equals(chartType)) {
                drawLines(canvas, padLeft, padTop, chartH, slotW, maxVal, n, true);
            } else if ("stacked_bar".equals(chartType)) {
                drawStackedBars(canvas, padLeft, padTop, chartH, slotW, maxVal, n);
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

        private void drawStackedBars(Canvas canvas, int padLeft, int padTop, int chartH,
                                     float slotW, float maxVal, int n) {
            float barW = slotW * 0.55f;
            for (int i = 0; i < n; i++) {
                float bottom = padTop + chartH;
                for (Series series : seriesList) {
                    if (i >= series.values.size()) continue;
                    float v = Math.max(0f, series.values.get(i));
                    float barH = (v / maxVal) * chartH;
                    if (barH <= 0) continue;
                    paint.setColor(series.color);
                    paint.setStyle(Paint.Style.FILL);
                    RectF r = new RectF(
                            padLeft + slotW * i + (slotW - barW) / 2f,
                            bottom - barH,
                            padLeft + slotW * i + (slotW + barW) / 2f,
                            bottom);
                    canvas.drawRoundRect(r, dp(2), dp(2), paint);
                    bottom -= barH;
                }
            }
        }

        private void drawLines(Canvas canvas, int padLeft, int padTop, int chartH, float slotW, float maxVal, int n) {
            drawLines(canvas, padLeft, padTop, chartH, slotW, maxVal, n, false);
        }

        private void drawLines(Canvas canvas, int padLeft, int padTop, int chartH, float slotW,
                               float maxVal, int n, boolean fillArea) {
            for (Series series : seriesList) {
                Path path = new Path();
                paint.setColor(series.color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                boolean first = true;
                float firstX = 0, lastX = 0;
                for (int i = 0; i < series.values.size() && i < n; i++) {
                    float v = series.values.get(i);
                    float x = padLeft + slotW * i + slotW / 2f;
                    float y = padTop + chartH - (v / maxVal) * chartH;
                    if (first) {
                        path.moveTo(x, y);
                        firstX = x;
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                    lastX = x;
                }
                // 面积图：先填色到基线，再画线
                if (fillArea && !first) {
                    Path area = new Path(path);
                    area.lineTo(lastX, padTop + chartH);
                    area.lineTo(firstX, padTop + chartH);
                    area.close();
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(withAlpha(series.color, 0x40));
                    canvas.drawPath(area, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(series.color);
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

        /** 散点图：数值 x/y 轴（series 未给 points 时用 data 的下标做 x） */
        private void drawScatter(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int padLeft = dp(34);
            int padRight = dp(10);
            int padTop = dp(8);
            int padBottom = dp(22);
            int chartW = w - padLeft - padRight;
            int chartH = h - padTop - padBottom;
            float[] bounds = scatterBounds(seriesList);
            if (bounds == null || chartW <= 0 || chartH <= 0) return;
            float minX = bounds[0], maxX = bounds[1], minY = bounds[2], maxY = bounds[3];
            if (maxX - minX < 1e-6f) { minX -= 1; maxX += 1; }
            if (maxY - minY < 1e-6f) { minY -= 1; maxY += 1; }
            float xSpan = maxX - minX, ySpan = maxY - minY;

            textPaint.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i <= 4; i++) {
                float ratio = i / 4f;
                float y = padTop + chartH * (1 - ratio);
                paint.setColor(ComponentColors.border(getContext()));
                paint.setStrokeWidth(dp(1));
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(padLeft, y, w - padRight, y, paint);
                canvas.drawText(fmt(minY + ySpan * ratio), dp(2), y - dp(3), textPaint);
            }
            textPaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i <= 4; i++) {
                float ratio = i / 4f;
                float x = padLeft + chartW * ratio;
                canvas.drawText(fmt(minX + xSpan * ratio), x, h - dp(5), textPaint);
            }
            for (Series s : seriesList) {
                paint.setColor(s.color);
                paint.setStyle(Paint.Style.FILL);
                for (float[] pt : pointsOf(s)) {
                    float px = padLeft + (pt[0] - minX) / xSpan * chartW;
                    float py = padTop + chartH - (pt[1] - minY) / ySpan * chartH;
                    canvas.drawCircle(px, py, dp(3.2f), paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
        }

        /** 雷达图：categories 是轴；每个系列一条闭合折线（值按最大值归一） */
        private void drawRadar(Canvas canvas) {
            int n = 0;
            for (Series s : seriesList) {
                n = Math.max(n, Math.max(s.values.size(), s.points.size()));
            }
            n = Math.max(n, categories.size());
            if (n < 3) return;
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) / 2f - dp(26);
            if (radius <= dp(10)) return;
            float maxVal = maxValue(seriesList);
            if (maxVal <= 0) maxVal = 1;

            for (int ring = 1; ring <= 4; ring++) {
                Path p = new Path();
                for (int i = 0; i < n; i++) {
                    double a = -Math.PI / 2 + 2 * Math.PI * i / n;
                    float x = cx + (float) Math.cos(a) * radius * ring / 4f;
                    float y = cy + (float) Math.sin(a) * radius * ring / 4f;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(ComponentColors.border(getContext()));
                canvas.drawPath(p, paint);
            }
            textPaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                double a = -Math.PI / 2 + 2 * Math.PI * i / n;
                float x = cx + (float) Math.cos(a) * radius;
                float y = cy + (float) Math.sin(a) * radius;
                canvas.drawLine(cx, cy, x, y, paint);
                if (i < categories.size() && n <= 12) {
                    canvas.drawText(categories.get(i),
                            cx + (float) Math.cos(a) * (radius + dp(12)),
                            cy + (float) Math.sin(a) * (radius + dp(12)) + dp(3),
                            textPaint);
                }
            }
            for (Series s : seriesList) {
                Path p = new Path();
                for (int i = 0; i < n; i++) {
                    float v = i < s.values.size() ? Math.max(0f, s.values.get(i)) : 0f;
                    double a = -Math.PI / 2 + 2 * Math.PI * i / n;
                    float r = radius * (v / maxVal);
                    float x = cx + (float) Math.cos(a) * r;
                    float y = cy + (float) Math.sin(a) * r;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(s.color, 0x40));
                canvas.drawPath(p, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(s.color);
                canvas.drawPath(p, paint);
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
