package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 图表别名的 props 形状归一（2026-10-21 真机抓到的 bug）。
 *
 * <p>现象：模型按工具说明调 {@code ui_component(create, pie_chart, data:[{name,value}])}，
 * 出来的却不是饼图，而是一张"收起原始 JSON"的文本卡；模型随后还回答"饼图已经在上面了"。
 * 根因是 {@code pie_chart/bar_chart/line_chart/sparkline} 这些名字被 canonical 归一到
 * {@link ChartCardView}（原生实现赢名字），但**参数形状没跟着归一**——本卡只读
 * {@code chartType}+{@code series}，于是 {@code canRender=false}，
 * {@code ComponentRegistry.render} 静默落 {@link DynamicCardView}。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ChartAliasShapeTest {

    /** 真机那条：pie_chart + data:[{name,value}]，无 chartType、无 series。 */
    @Test
    public void pieChartWithDataPairsRenders() {
        ComponentData d = ComponentData.of("pie_chart", obj(
                "title", "本周天气构成",
                "data", arr(obj("name", "晴", "value", 3), obj("name", "少云", "value", 2),
                        obj("name", "小雨", "value", 1))));
        assertTrue("pie_chart(data) 必须能渲成图，否则掉 JSON 兜底",
                new ChartCardView().canRender(d));
    }

    /** 别名自带图型：不传 chartType 也要知道是饼图。 */
    @Test
    public void aliasNameImpliesChartType() throws Exception {
        JSONObject n = ChartCardView.normalize("pie_chart", obj("data", arr(1, 2, 3)));
        assertEquals("pie", n.optString("chartType"));
        assertEquals("bar", ChartCardView.normalize("bar_chart", obj("data", arr(1))).optString("chartType"));
        assertEquals("line", ChartCardView.normalize("line_chart", obj("data", arr(1))).optString("chartType"));
    }

    /** 纯数值数组（sparkline/单系列柱线）也要能成一条 series。 */
    @Test
    public void numericDataArrayBecomesSeries() throws Exception {
        JSONObject n = ChartCardView.normalize("bar_chart", obj("data", arr(4, 7, 2)));
        JSONArray series = n.optJSONArray("series");
        assertNotNull(series);
        assertEquals(1, series.length());
        assertEquals(3, series.optJSONObject(0).optJSONArray("data").length());
    }

    /** 显式 series 优先，且 canonical chart 的老口径不许回归。 */
    @Test
    public void explicitSeriesStillWorks() {
        ComponentData d = ComponentData.of("chart", obj(
                "chartType", "line",
                "categories", arr("10-21", "10-22"),
                "series", arr(obj("name", "温度", "data", arr(12, 17)))));
        assertTrue(new ChartCardView().canRender(d));
    }

    /** 两种形状都没有：仍然判不能渲（不能把空卡当成功）。 */
    @Test
    public void noShapeAtAllStillRejected() {
        assertFalse(new ChartCardView().canRender(ComponentData.of("chart", obj("title", "只有标题"))));
        assertFalse(new ChartCardView().canRender(ComponentData.of("pie_chart", new JSONObject())));
        assertFalse(new ChartCardView().canRender(null));
    }

    /** 归一必须返回副本——props 会被持久化，原地写回等于改用户数据。 */
    @Test
    public void normalizeDoesNotMutateSourceProps() throws Exception {
        JSONObject props = obj("data", arr(obj("name", "晴", "value", 3)));
        ChartCardView.normalize("pie_chart", props);
        assertFalse("不该往原 props 里塞 chartType", props.has("chartType"));
        assertFalse("不该往原 props 里塞 series", props.has("series"));
    }

    /** 端到端：走真注册表，出的必须是含 ChartView 的图卡，而不是 JSON 兜底文本。 */
    @Test
    public void registryRendersChartNotJsonFallback() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ComponentData d = ComponentData.of("pie_chart", obj(
                "title", "本周天气构成",
                "data", arr(obj("name", "晴", "value", 3), obj("name", "小雨", "value", 1))));
        View v = ComponentRegistry.getInstance().render(ctx, d);
        assertNotNull(v);
        assertTrue("渲染结果里没有 ChartView，说明仍掉进兜底", containsChartView(v));
    }

    // ---- helpers ----

    private static boolean containsChartView(View v) {
        if (v.getClass().getName().contains("ChartCardView$ChartView")) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (containsChartView(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            } catch (org.json.JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return o;
    }

    private static JSONArray arr(Object... v) {
        JSONArray a = new JSONArray();
        for (Object o : v) {
            a.put(o);
        }
        return a;
    }
}
