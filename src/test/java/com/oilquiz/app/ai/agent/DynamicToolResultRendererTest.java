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

    // ---- 目录 ≠ 文件（2026-10-24 真机：workspace(action=path) 把目录挂成了文件卡）----

    /** 真存在的目录：出浏览卡（带条目与目录 path），绝不能出 file_card。 */
    @Test
    public void directoryPathBecomesBrowseCardNotFileCard() throws Exception {
        java.io.File dir = makeDirWithEntries();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", dir.getAbsolutePath());
        r.put("message", "工作区路径: " + dir.getAbsolutePath());
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "workspace", AIToolResult.success(r));

        assertEquals("目录只该出一张卡: " + types(cards), 1, cards.size());
        assertEquals("file_list", cards.get(0).type);
        assertEquals(dir.getAbsolutePath(), cards.get(0).props.optString("path"));
        org.json.JSONArray rows = cards.get(0).props.optJSONArray("files");
        assertEquals(3, rows.length());
        assertEquals("目录行要排在文件前", "dir", rows.getJSONObject(0).optString("type"));
        assertEquals("子目录", rows.getJSONObject(0).optString("name"));
        for (int i = 1; i < rows.length(); i++) {
            assertEquals("file", rows.getJSONObject(i).optString("type"));
        }
        assertTrue("标题要带目录名，否则用户不知道在看哪个目录",
                cards.get(0).props.optString("title").contains(dir.getName()));
    }

    /** 真存在的单个文件仍走 file_card（别把修目录改成"什么都不挂卡"）。 */
    @Test
    public void existingSingleFileStillBecomesFileCard() throws Exception {
        java.io.File dir = makeDirWithEntries();
        String file = new java.io.File(dir, "报告.md").getAbsolutePath();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", file);
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "file_generator", AIToolResult.success(r));

        assertTrue("应仍出文件卡: " + types(cards), containsType(cards, "file_card"));
        assertFalse(containsType(cards, "file_list"));
    }

    /** 不存在的路径（模型缩写/幻觉）维持原形状：file_card 至少能看到文件名、点开有提示。 */
    @Test
    public void missingPathKeepsFileCardShape() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", "/data/never/exists/agent_workspace");
        List<ComponentData> cards = DynamicToolResultRenderer.render(
                "workspace", AIToolResult.success(r));

        assertTrue("幻觉路径不该被当目录展开: " + types(cards), containsType(cards, "file_card"));
        assertFalse(containsType(cards, "file_list"));
    }

    private static java.io.File makeDirWithEntries() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("ws-dir-shape").toFile();
        dir.deleteOnExit();
        assertTrue(new java.io.File(dir, "报告.md").createNewFile());
        assertTrue(new java.io.File(dir, "a.txt").createNewFile());
        assertTrue(new java.io.File(dir, "子目录").mkdirs());
        return dir;
    }

    private static List<String> types(List<ComponentData> cards) {
        List<String> out = new ArrayList<>();
        for (ComponentData c : cards) out.add(c.type);
        return out;
    }

    private static boolean containsType(List<ComponentData> cards, String type) {
        return types(cards).contains(type);
    }
}
