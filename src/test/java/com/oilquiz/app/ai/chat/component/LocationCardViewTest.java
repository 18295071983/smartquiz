package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 位置卡的数据口径（2026-10-23）：坐标解析与 geo: 深链。
 * 真实坑：很多定位结果会把没定位到的值写成 0,0 或只有其中一个坐标——这两种都必须当作
 * "没有有效坐标"，否则会给用户一个指向几内亚湾的"在地图中打开"。
 */
public class LocationCardViewTest {

    @Test
    public void coordsAcceptsNumbersAndStrings() {
        double[] a = LocationCardView.coords(obj("latitude", 31.2304, "longitude", 121.4737));
        assertEquals(31.2304, a[0], 1e-9);
        assertEquals(121.4737, a[1], 1e-9);
        double[] b = LocationCardView.coords(obj("lat", "31.2304", "lng", "121.4737"));
        assertEquals(31.2304, b[0], 1e-9);
        assertEquals(121.4737, b[1], 1e-9);
    }

    @Test
    public void invalidCoordinatesAreRejected() {
        assertNull("0,0 是没定位到的占位值", LocationCardView.coords(obj("latitude", 0, "longitude", 0)));
        assertNull("缺一个坐标", LocationCardView.coords(obj("latitude", 31.2)));
        assertNull("越界", LocationCardView.coords(obj("latitude", 91, "longitude", 0.5)));
        assertNull("非数字", LocationCardView.coords(obj("latitude", "x", "longitude", "y")));
    }

    @Test
    public void geoUriFormatsAndEncodesLabel() {
        assertEquals("geo:31.230400,121.473700",
                LocationCardView.geoUri(31.2304, 121.4737, ""));
        String withLabel = LocationCardView.geoUri(31.2304, 121.4737, "上海市 人民广场");
        assertTrue(withLabel.startsWith("geo:31.230400,121.473700?q=31.230400,121.473700("));
        assertTrue("中文标签必须编码", withLabel.contains("%E4%B8%8A%E6%B5%B7"));
        assertNull(LocationCardView.geoUri(200, 0, "x"));
    }

    @Test
    public void canRenderAcceptsAddressOnly() {
        assertTrue(new LocationCardView().canRender(
                ComponentData.of("location", obj("address", "上海市黄浦区"))));
        assertTrue(new LocationCardView().canRender(
                ComponentData.of("location", obj("latitude", 31.2, "longitude", 121.4))));
        assertFalse(new LocationCardView().canRender(ComponentData.of("location", obj("title", "x"))));
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
}
