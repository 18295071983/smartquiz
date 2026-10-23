package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 播放列表卡的条目解析（2026-10-23）。渲染逻辑要真机看，这里钉住"哪些行该收、收哪些字段、
 * 地址别名、上限"——多音频产物会直接把工具结果塞进来，形状并不总是规整。
 */
public class PlaylistCardViewTest {

    @Test
    public void canRenderRequiresNonEmptyItems() {
        assertTrue(new PlaylistCardView().canRender(ComponentData.of("playlist",
                obj("items", arr(obj("name", "a.mp3", "url", "/tmp/a.mp3"))))));
        assertFalse("空数组不该接这张卡",
                new PlaylistCardView().canRender(ComponentData.of("playlist", obj("items", arr()))));
        assertFalse(new PlaylistCardView().canRender(ComponentData.of("playlist", obj("title", "x"))));
    }

    @Test
    public void parseAcceptsUrlAliasesAndSkipsRowsWithoutAddress() {
        JSONArray items = arr(
                obj("name", "a", "url", "/tmp/a.mp3"),
                obj("name", "b", "path", "/tmp/b.mp3"),
                obj("name", "c", "src", "/tmp/c.mp3"),
                obj("name", "没有地址"),
                obj("title", "只有标题没地址"));
        java.util.List<PlaylistCardView.Row> rows = PlaylistCardView.parseItems(items);
        assertEquals(3, rows.size());
        assertEquals("a", rows.get(0).name);
        assertEquals("/tmp/b.mp3", rows.get(1).url);
        assertEquals("c", rows.get(2).name);
    }

    @Test
    public void parseKeepsArtistAndSizeAndCapsAt50() {
        JSONArray items = new JSONArray();
        for (int i = 0; i < 60; i++) {
            items.put(obj("name", "n" + i, "url", "/tmp/" + i + ".mp3",
                    "artist", "x", "size", "1 MB"));
        }
        java.util.List<PlaylistCardView.Row> rows = PlaylistCardView.parseItems(items);
        assertEquals(50, rows.size());
        assertEquals("x", rows.get(0).artist);
        assertEquals("1 MB", rows.get(0).size);
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
        for (Object o : v) a.put(o);
        return a;
    }
}
