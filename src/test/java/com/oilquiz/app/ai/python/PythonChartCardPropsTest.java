package com.oilquiz.app.ai.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/**
 * python_chart 的 {@code render=card} 数据归一（2026-10-23）。
 * 这个分支不跑 Python，纯数据转换；归一错了会把 PNG 工具变成"出一张空图"。
 */
public class PythonChartCardPropsTest {

    @Test
    public void barWithLabelsAndValues() {
        JSONObject p = PythonChartTool.buildCardProps("bar",
                "{\"labels\":[\"1月\",\"2月\"],\"values\":[120,200]}", "", "销量");
        assertEquals("bar", p.optString("chartType"));
        assertEquals("销量", p.optString("title"));
        assertEquals(2, p.optJSONArray("categories").length());
        assertEquals(200.0, p.optJSONArray("series").optJSONObject(0)
                .optJSONArray("data").optDouble(1), 1e-9);
    }

    @Test
    public void multipleSeriesKeepNames() {
        JSONObject p = PythonChartTool.buildCardProps("line",
                "{\"labels\":[\"A\",\"B\"],\"series\":[{\"name\":\"收入\",\"values\":[1,2]},"
                        + "{\"name\":\"支出\",\"values\":[3,4]}]}", "", "");
        assertEquals(2, p.optJSONArray("series").length());
        assertEquals("支出", p.optJSONArray("series").optJSONObject(1).optString("name"));
    }

    @Test
    public void scatterNeedsPoints() {
        JSONObject ok = PythonChartTool.buildCardProps("scatter",
                "{\"points\":[[1,2],[3,4]]}", "", "散点");
        assertEquals("scatter", ok.optString("chartType"));
        assertEquals(2, ok.optJSONArray("series").optJSONObject(0)
                .optJSONArray("points").length());
        assertNull("散点没有 points 就该报错，不能画空图",
                PythonChartTool.buildCardProps("scatter", "{\"labels\":[\"a\"]}", "", ""));
    }

    @Test
    public void radarNeedsAtLeastThreeAxes() {
        assertNull(PythonChartTool.buildCardProps("radar",
                "{\"labels\":[\"A\",\"B\"],\"values\":[1,2]}", "", ""));
        JSONObject ok = PythonChartTool.buildCardProps("radar",
                "{\"labels\":[\"A\",\"B\",\"C\"],\"values\":[1,2,3]}", "", "");
        assertEquals("radar", ok.optString("chartType"));
    }

    @Test
    public void chartTypeCanOverrideAction() {
        JSONObject p = PythonChartTool.buildCardProps("bar",
                "{\"labels\":[\"A\",\"B\"],\"values\":[1,2]}", "area", "");
        assertEquals("area", p.optString("chartType"));
    }

    @Test
    public void invalidDataReturnsNull() {
        assertNull(PythonChartTool.buildCardProps("bar", "[\"not\",\"an\",\"object\"]", "", ""));
        assertNull(PythonChartTool.buildCardProps("bar", "{\"labels\":[\"A\"]}", "", ""));
    }
}
