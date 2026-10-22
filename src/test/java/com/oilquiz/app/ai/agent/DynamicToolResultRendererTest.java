package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /**
     * 2026-10-23 事故回归：别名表把 {@code output} 当路径键收进 ARTIFACT 后，
     * "正文"被归成产物文件——卡片渲染正常、path 却是一段文本，点开就是"文件不存在"。
     * 产物引用只认键名本身就是路径/链接的键。
     */
    @Test
    public void textShapedItemDoesNotBecomeFileCard() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "导入结果");
        item.put("output", "已保存 3 条记录，跳过 0 条");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("files", Arrays.asList(item));

        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "custom_import", AIToolResult.success(r));
        for (ComponentData c : cards) {
            // 只查"会被点开的链接字段"——info_card 里把正文当值显示是合理的，链接字段里出现就是 bug
            String links = c.props.optString("path") + "|" + c.props.optString("url")
                    + "|" + c.props.optJSONArray("images") + "|" + c.props.optJSONArray("files");
            assertFalse("正文被当产物路径生成了卡片: " + c.type + " " + links,
                    links.contains("已保存"));
        }
    }

    /** 收紧别名表不能把真产物一起砍掉：file / file_path / url 三种写法仍要归类 */
    @Test
    public void realArtifactKeysStillBecomeCards() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("file_path", "/tmp/x.png");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("files", Arrays.asList(item));
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "custom_chart", AIToolResult.success(r));
        assertEquals(1, cards.size());
        assertEquals("image_grid", cards.get(0).type);
        assertTrue(cards.get(0).props.toString().contains("/tmp/x.png"));
    }
}
