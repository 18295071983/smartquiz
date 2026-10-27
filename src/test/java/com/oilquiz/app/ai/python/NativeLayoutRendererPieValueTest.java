package com.oilquiz.app.ai.python;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 饼图取值兜底（真机崩溃回归）。
 *
 * <p>2026-10-27 18:16 的崩溃：模型给的 {@code pie_chart.data} 里混了非对象元素，
 * {@code optJSONObject(i)} 返回 null，旧代码直接 {@code .optDouble(...)} → NPE 抛在 onDraw 里，
 * 整个聊天页崩溃。绘制层不能假设模型给的形状一定合规，这里钉住三种形态都不崩。</p>
 */
public class NativeLayoutRendererPieValueTest {

    @Test
    public void objectArrayUsesValueField() throws Exception {
        JSONArray a = new JSONArray("[{\"value\":5},{\"value\":3}]");
        assertEquals(5f, NativeLayoutRenderer.pieValue(a, 0), 1e-6);
        assertEquals(3f, NativeLayoutRenderer.pieValue(a, 1), 1e-6);
    }

    @Test
    public void numericArrayIsAccepted() throws Exception {
        JSONArray a = new JSONArray("[5,3,1]");
        assertEquals(5f, NativeLayoutRenderer.pieValue(a, 0), 1e-6);
        assertEquals(1f, NativeLayoutRenderer.pieValue(a, 2), 1e-6);
    }

    @Test
    public void nullOrMixedElementsNeverThrow() throws Exception {
        JSONArray a = new JSONArray("[null,{\"value\":2},\"x\"]");
        assertEquals(0f, NativeLayoutRenderer.pieValue(a, 0), 1e-6);
        assertEquals(2f, NativeLayoutRenderer.pieValue(a, 1), 1e-6);
        assertEquals(0f, NativeLayoutRenderer.pieValue(a, 2), 1e-6);
        assertEquals(0f, NativeLayoutRenderer.pieValue(null, 0), 1e-6);
    }
}
