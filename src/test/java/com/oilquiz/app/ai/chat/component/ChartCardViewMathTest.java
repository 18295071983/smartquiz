package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 图表扩型后的纯数据口径（2026-10-23）：堆叠上限、散点边界、点回退与刻度文案。
 * 画布本身只能在真机看，这里保证"数算对了"——坐标上限算错会直接导致柱子冲出画布。
 */
public class ChartCardViewMathTest {

    private static ChartCardView.Series series(String name, float... values) {
        ChartCardView.Series s = new ChartCardView.Series();
        s.name = name;
        for (float v : values) s.values.add(v);
        return s;
    }

    @Test
    public void maxValueIgnoresNullAndNonPositive() {
        assertEquals(9f, ChartCardView.maxValue(Arrays.asList(
                series("a", 3, 9), series("b", -2, 4))), 1e-6);
        assertEquals(0f, ChartCardView.maxValue(Arrays.asList(series("a", 0, 0))), 1e-6);
    }

    @Test
    public void stackedTotalIsPerCategorySum() {
        // 分类 0：3+4=7；分类 1：2+6=8 → 上限取 8
        assertEquals(8f, ChartCardView.maxStackedTotal(Arrays.asList(
                series("a", 3, 2), series("b", 4, 6))), 1e-6);
    }

    @Test
    public void scatterBoundsCoverAllSeries() {
        ChartCardView.Series s1 = new ChartCardView.Series();
        s1.points.add(new float[]{1, 5});
        s1.points.add(new float[]{3, 9});
        ChartCardView.Series s2 = new ChartCardView.Series();
        s2.points.add(new float[]{2, 1});
        float[] b = ChartCardView.scatterBounds(Arrays.asList(s1, s2));
        assertNotNull(b);
        assertEquals(1f, b[0], 1e-6);
        assertEquals(3f, b[1], 1e-6);
        assertEquals(1f, b[2], 1e-6);
        assertEquals(9f, b[3], 1e-6);
    }

    @Test
    public void valuesWithoutPointsUseIndexAsX() {
        List<float[]> pts = ChartCardView.pointsOf(series("a", 7, 8));
        assertEquals(2, pts.size());
        assertEquals(1f, pts.get(0)[0], 1e-6);
        assertEquals(7f, pts.get(0)[1], 1e-6);
        assertEquals(2f, pts.get(1)[0], 1e-6);
    }

    @Test
    public void scatterBoundsNullWithoutData() {
        assertNull(ChartCardView.scatterBounds(Arrays.asList(series("a"))));
    }

    @Test
    public void axisLabelsUseIntegerWhenClose() {
        assertEquals("8", ChartCardView.fmt(8f));
        assertEquals("7.5", ChartCardView.fmt(7.5f));
        assertEquals("3.1", ChartCardView.fmt(3.14f));
    }

    @Test
    public void allNewChartTypesPassCanRender() throws Exception {
        ChartCardView view = new ChartCardView();
        for (String type : new String[]{"bar", "line", "area", "stacked_bar", "pie", "radar"}) {
            JSONObject props = new JSONObject();
            props.put("chartType", type);
            props.put("categories", new org.json.JSONArray().put("A").put("B").put("C"));
            props.put("series", new org.json.JSONArray().put(
                    new JSONObject().put("name", "s").put("data",
                            new org.json.JSONArray().put(1).put(2).put(3))));
            assertTrue("canRender 失败: " + type,
                    view.canRender(ComponentData.of("chart", props)));
        }
        // scatter 只需 series（points 形式）
        JSONObject scatter = new JSONObject();
        scatter.put("chartType", "scatter");
        scatter.put("series", new org.json.JSONArray().put(new JSONObject()
                .put("name", "pts").put("points", new org.json.JSONArray()
                        .put(new org.json.JSONArray().put(1).put(2)))));
        assertTrue(view.canRender(ComponentData.of("chart", scatter)));
    }
}
