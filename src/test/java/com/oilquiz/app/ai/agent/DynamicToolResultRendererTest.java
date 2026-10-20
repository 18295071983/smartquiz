package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.AIToolResult;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任意工具结果的动态卡片兜底（2026-10-21）：按结果形状选卡，纯文本不造卡。
 */
public class DynamicToolResultRendererTest {

    @Test
    public void tableShapeBecomesTableCard() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("title", "成绩");
        r.put("headers", Arrays.asList("姓名", "分数"));
        r.put("rows", Arrays.asList(Arrays.asList("甲", 90)));
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "custom_query", AIToolResult.success(r));
        assertEquals(1, cards.size());
        assertEquals("table_card", cards.get(0).type);
    }

    @Test
    public void mediaShapeBecomesNativeVideoCard() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("files", Arrays.asList("/tmp/a.mp4", "/tmp/b.mp3"));
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "custom_media", AIToolResult.success(r));
        assertEquals(2, cards.size());
        assertEquals("video", cards.get(0).type);
        assertEquals("audio", cards.get(1).type);
    }

    @Test
    public void scalarShapeBecomesInfoCard() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("city", "银川");
        r.put("temperature", "-3.3");
        r.put("status", "success");
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "custom_weather", AIToolResult.success(r));
        assertEquals(1, cards.size());
        assertEquals("info_card", cards.get(0).type);
        assertTrue(cards.get(0).props.optJSONArray("items").length() >= 2);
    }

    @Test
    public void plainTextDoesNotCreateCard() {
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "calculator", AIToolResult.success("4"));
        assertTrue(cards.isEmpty());
    }

    @Test
    public void objectListBecomesListCard() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "第一项");
        a.put("description", "说明");
        list.add(a);
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "custom_list", AIToolResult.success(list));
        assertEquals(1, cards.size());
        assertEquals("list_card", cards.get(0).type);
    }
}
