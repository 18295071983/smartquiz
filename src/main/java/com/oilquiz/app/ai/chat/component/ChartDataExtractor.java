package com.oilquiz.app.ai.chat.component;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表格结果 → 图表卡数据（2026-10-23）。
 *
 * <p>为什么需要它：图表卡以前只有 {@code python_chart} 一个生产者，而且那个工具出的是 PNG，
 * 原生图表卡几乎没人喂。实际上 {@code excel_tool} / {@code database} 的结果天然是
 * "表头 + 行"，只要有一列可当分类、另有数值列，就能直接画图——这是"多几个生产者"的落点。</p>
 *
 * <p>自动出图刻意保守，宁可不出也不出错图：
 * <ul>
 *   <li>行数 2..30（超过就不自动画，避免 100 根柱子/折线糊成一团）；</li>
 *   <li>分类列取第一列<b>非数值</b>列；数值列要求非空单元格里 ≥80% 能解析成数字，且至少两个不同值；</li>
 *   <li>最多取 4 个数值列做系列；</li>
 *   <li>分类值像日期/月份/季度/时间 → 折线，否则柱状；</li>
 *   <li>识别不出分类列或数值列时返回 null（调用方保持原表格卡，不硬凑）。</li>
 * </ul>
 */
public final class ChartDataExtractor {

    static final int MIN_ROWS = 2;
    static final int MAX_ROWS = 30;
    static final int MAX_SERIES = 4;

    private ChartDataExtractor() {
    }

    /** headers: ["月份","销售额"]，rows: [["1月",120],["2月",200]] */
    public static JSONObject fromTable(JSONArray headers, JSONArray rows) {
        if (headers == null || rows == null) return null;
        int nCols = headers.length();
        int nRows = rows.length();
        if (nCols < 2 || nRows < MIN_ROWS || nRows > MAX_ROWS) return null;

        List<String> names = new ArrayList<>();
        List<List<String>> cells = new ArrayList<>();
        for (int j = 0; j < nCols; j++) {
            names.add(headers.optString(j, "").trim());
            cells.add(new ArrayList<>());
        }
        for (int i = 0; i < nRows; i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null) continue;
            for (int j = 0; j < nCols; j++) {
                String v = row.optString(j, "").trim();
                cells.get(j).add(v);
            }
        }

        int labelIndex = -1;
        for (int j = 0; j < nCols; j++) {
            if (!isNumericColumn(cells.get(j))) {
                labelIndex = j;
                break;
            }
        }
        if (labelIndex < 0) return null;

        List<Integer> numericCols = new ArrayList<>();
        for (int j = 0; j < nCols && numericCols.size() < MAX_SERIES; j++) {
            if (j == labelIndex) continue;
            if (isNumericColumn(cells.get(j))) numericCols.add(j);
        }
        if (numericCols.isEmpty()) return null;

        try {
            JSONArray categories = new JSONArray();
            for (int i = 0; i < nRows; i++) {
                String v = cells.get(labelIndex).get(i);
                categories.put(v.isEmpty() ? "第 " + (i + 1) + " 项" : v);
            }
            JSONArray series = new JSONArray();
            StringBuilder seriesNames = new StringBuilder();
            for (int col : numericCols) {
                JSONArray data = new JSONArray();
                for (int i = 0; i < nRows; i++) {
                    Double v = parseNumber(cells.get(col).get(i));
                    data.put(v == null ? 0d : v);
                }
                String name = names.get(col);
                if (name.isEmpty()) name = "数值" + (series.length() + 1);
                JSONObject s = new JSONObject().put("name", name).put("data", data);
                series.put(s);
                if (seriesNames.length() > 0) seriesNames.append("/");
                seriesNames.append(name);
            }
            String labelName = names.get(labelIndex);
            if (labelName.isEmpty()) labelName = "分类";

            JSONObject out = new JSONObject();
            out.put("chartType", isTemporal(cells.get(labelIndex)) ? "line" : "bar");
            out.put("categories", categories);
            out.put("series", series);
            out.put("title", "按" + labelName + "的" + seriesNames);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** List 版：rows 可以是 Map（按表头取值）或 List；供 database/excel 的原始结果直接喂 */
    public static JSONObject fromTable(List<?> headers, List<?> rows) {
        if (headers == null || rows == null) return null;
        try {
            JSONArray h = new JSONArray();
            for (Object o : headers) h.put(o == null ? "" : String.valueOf(o));
            JSONArray r = new JSONArray();
            for (Object row : rows) {
                JSONArray arr = new JSONArray();
                if (row instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) row;
                    for (Object o : headers) {
                        Object v = map.get(o);
                        arr.put(v == null ? "" : String.valueOf(v));
                    }
                } else if (row instanceof List) {
                    for (Object v : (List<?>) row) arr.put(v == null ? "" : String.valueOf(v));
                } else {
                    continue;
                }
                r.put(arr);
            }
            return fromTable(h, r);
        } catch (Exception e) {
            return null;
        }
    }

    /** 非空单元格里 ≥80% 是数字，且至少两个不同的值（常量列不出图） */
    static boolean isNumericColumn(List<String> cells) {
        if (cells == null) return false;
        int nonEmpty = 0, numeric = 0;
        Set<Double> distinct = new HashSet<>();
        for (String c : cells) {
            if (c == null || c.isEmpty()) continue;
            nonEmpty++;
            Double v = parseNumber(c);
            if (v != null) {
                numeric++;
                distinct.add(v);
            }
        }
        if (nonEmpty < MIN_ROWS) return false;
        if (distinct.size() < 2) return false;
        return numeric * 5 >= nonEmpty * 4;   // ≥80%，避免浮点除法
    }

    /** 数字解析：容忍千分位、百分号、货币符号与首尾空白 */
    static Double parseNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = s.replace(",", "").replace("，", "")
                .replace("%", "").replace("％", "")
                .replace("¥", "").replace("￥", "").replace("$", "");
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 分类值像时间轴（日期/月份/季度/年份/时刻）→ 折线更合适 */
    static boolean isTemporal(List<String> cells) {
        if (cells == null) return false;
        int checked = 0, hits = 0;
        for (String c : cells) {
            if (c == null || c.isEmpty()) continue;
            checked++;
            String s = c.toLowerCase(Locale.US);
            boolean hit = s.matches(".*\\d{4}[-/].*")
                    || s.matches(".*\\d{1,2}[-/]\\d{1,2}.*")
                    || s.contains("月") || s.contains("日") || s.contains("年")
                    || s.contains("季度") || s.contains("周") || s.matches(".*\\d{1,2}:\\d{2}.*");
            if (hit) hits++;
            if (checked >= 5) break;
        }
        return checked > 0 && hits * 2 >= checked;
    }
}
