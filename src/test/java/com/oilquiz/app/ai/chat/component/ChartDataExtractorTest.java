package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表格 → 图表的自动提取（2026-10-23）。规则刻意保守：识别不出就不出图。
 * 这里钉住"该出的出、不该出的不出"，防止每次表格结果都硬凑一张错图。
 */
public class ChartDataExtractorTest {

    @Test
    public void labelPlusNumericColumnBecomesBar() {
        JSONObject chart = ChartDataExtractor.fromTable(
                Arrays.asList("名称", "数量"),
                Arrays.asList(Arrays.asList("苹果", 3), Arrays.asList("香蕉", 5),
                        Arrays.asList("橘子", 2)));
        assertNotNull(chart);
        assertEquals("bar", chart.optString("chartType"));
        assertEquals(3, chart.optJSONArray("categories").length());
        assertEquals("香蕉", chart.optJSONArray("categories").optString(1));
        assertEquals(5.0, chart.optJSONArray("series").optJSONObject(0)
                .optJSONArray("data").optDouble(1), 1e-9);
        assertTrue(chart.optString("title").contains("名称"));
    }

    @Test
    public void temporalLabelBecomesLine() {
        JSONObject chart = ChartDataExtractor.fromTable(
                Arrays.asList("月份", "销售额"),
                Arrays.asList(Arrays.asList("1月", 120), Arrays.asList("2月", 200)));
        assertNotNull(chart);
        assertEquals("line", chart.optString("chartType"));
    }

    @Test
    public void mapRowsAreSupported() {
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("城市", "银川");
        r1.put("温度", "-3.3");
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("城市", "上海");
        r2.put("温度", "21.5");
        JSONObject chart = ChartDataExtractor.fromTable(Arrays.asList("城市", "温度"),
                Arrays.asList(r1, r2));
        assertNotNull(chart);
        assertEquals("bar", chart.optString("chartType"));
        org.json.JSONArray series = chart.optJSONArray("series");
        assertEquals(1, series.length());
        assertEquals(2, series.optJSONObject(0).optJSONArray("data").length());
        assertEquals(21.5, series.optJSONObject(0).optJSONArray("data").optDouble(1), 1e-9);
    }

    @Test
    public void noNumericColumnMeansNoChart() {
        assertNull(ChartDataExtractor.fromTable(Arrays.asList("名称", "备注"),
                Arrays.asList(Arrays.asList("a", "x"), Arrays.asList("b", "y"))));
    }

    @Test
    public void constantColumnMeansNoChart() {
        assertNull("数值列全是 5，画出来没有信息量",
                ChartDataExtractor.fromTable(Arrays.asList("名称", "数量"),
                        Arrays.asList(Arrays.asList("a", 5), Arrays.asList("b", 5))));
    }

    @Test
    public void rowCapAndMinRows() {
        assertNull("单行不画图", ChartDataExtractor.fromTable(Arrays.asList("名称", "数量"),
                Arrays.asList(Arrays.asList("a", 1))));
        java.util.List<java.util.List<Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(Arrays.asList("第" + i + "项", i));
        assertNull("超过 30 行不自动画", ChartDataExtractor.fromTable(
                Arrays.asList("名称", "数量"), many));
    }

    @Test
    public void numberParsingToleratesCommasAndUnits() {
        assertEquals(1234.0, ChartDataExtractor.parseNumber("1,234"), 1e-9);
        // 百分比按"显示的数字"取值（50% → 50），不做 /100：轴刻度要和单元格里看到的一致
        assertEquals(50.0, ChartDataExtractor.parseNumber("50%"), 1e-9);
        assertEquals(88.0, ChartDataExtractor.parseNumber("￥88"), 1e-9);
        assertNull(ChartDataExtractor.parseNumber("n/a"));
        assertNull(ChartDataExtractor.parseNumber(""));
        List<String> cells = Arrays.asList("1,000", "2,000", "");
        assertTrue(ChartDataExtractor.isNumericColumn(cells));
    }
}
