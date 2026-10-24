package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.AIToolResult;

import org.json.JSONArray;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置卡片的生产者接线（2026-10-24）：agenda ← reminder、diff ← 写文件工具、
 * 网页卡 ← 工作区里的 .html/.htm 产物。
 *
 * <p>钉住的是"工具结果 → 具体卡片类型"这一层，不涉及 Android View：agenda/diff
 * 此前只有渲染端，html 产物此前只会出 file_card，这轮补的都是生产侧。</p>
 */
public class AutoCardProducerTest {

    @Test
    public void reminderAgendaInfoBecomesAgendaCard() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("count", 2);
        result.put("reminders", new ArrayList<>());

        Map<String, Object> info = new HashMap<>();
        info.put("agenda_title", "提醒日程");
        info.put("agenda_items", Arrays.asList(
                agendaItem("2026-10-24", "09:00", "晨会", "ID: r1 · 每日重复", "todo"),
                agendaItem("2026-10-24", "18:30", "交作业", "ID: r2", "todo")));

        List<ComponentData> cards = ToolResultCardBinder.build(
                null, "reminder", AIToolResult.success(result, info), null);

        assertEquals(1, cards.size());
        ComponentData card = cards.get(0);
        assertEquals("agenda", card.type);
        assertEquals("提醒日程", card.props.optString("title"));
        JSONArray items = card.props.optJSONArray("items");
        assertNotNull(items);
        assertEquals(2, items.length());
        assertEquals("2026-10-24", items.optJSONObject(0).optString("date"));
        assertEquals("09:00", items.optJSONObject(0).optString("time"));
        assertEquals("todo", items.optJSONObject(0).optString("status"));
    }

    @Test
    public void reminderWithoutAgendaInfoKeepsListCardFallback() throws Exception {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> reminder = new HashMap<>();
        reminder.put("id", "r1");
        reminder.put("content", "喝水");
        reminder.put("fireAt", "10-24 09:00");
        reminder.put("repeat", "none");
        result.put("reminders", Arrays.asList(reminder));

        List<ComponentData> cards = ToolResultCardBinder.build(
                null, "reminder", AIToolResult.success(result), null);

        assertEquals(1, cards.size());
        assertEquals("list_card", cards.get(0).type);
    }

    @Test
    public void fileGeneratorProducesFileAndDiffCards() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("filePath", "/tmp/report.md");

        Map<String, Object> diff = new HashMap<>();
        diff.put("title", "文件变更 · report.md");
        diff.put("path", "/tmp/report.md");
        diff.put("before", "old line\nsecond");
        diff.put("after", "new line\nsecond");
        Map<String, Object> info = new HashMap<>();
        info.put("diff", diff);

        List<ComponentData> cards = ToolResultCardBinder.build(
                null, "file_generator", AIToolResult.success(result, info), null);

        assertEquals(2, cards.size());
        assertEquals("file_card", cards.get(0).type);
        assertEquals("diff", cards.get(1).type);
        assertEquals("old line\nsecond", cards.get(1).props.optString("before"));
        assertEquals("new line\nsecond", cards.get(1).props.optString("after"));
        assertEquals("/tmp/report.md", cards.get(1).props.optString("path"));
    }

    @Test
    public void pythonFileOpsProducesDiffCardFromAdditionalInfo() throws Exception {
        Map<String, Object> diff = new HashMap<>();
        diff.put("title", "文件变更 · data.csv");
        diff.put("path", "/tmp/data.csv");
        diff.put("before", "a,b\n1,2");
        diff.put("after", "a,b\n1,3");
        Map<String, Object> info = new HashMap<>();
        info.put("diff", diff);

        List<ComponentData> cards = ToolResultCardBinder.build(
                null, "python_file_ops", AIToolResult.success("已写入", info), null);

        assertEquals(1, cards.size());
        assertEquals("diff", cards.get(0).type);
        assertEquals("1,3", cards.get(0).props.optString("after").split("\n")[1]);
    }

    @Test
    public void unchangedContentDoesNotProduceDiffCard() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("filePath", "/tmp/same.md");
        Map<String, Object> diff = new HashMap<>();
        diff.put("before", "same");
        diff.put("after", "same");
        Map<String, Object> info = new HashMap<>();
        info.put("diff", diff);

        List<ComponentData> cards = ToolResultCardBinder.build(
                null, "file_generator", AIToolResult.success(result, info), null);

        assertEquals(1, cards.size());
        assertEquals("file_card", cards.get(0).type);
        assertNull(find(cards, "diff"));
    }

    // ------------------------------------------------------ html 产物 → 网页卡（2026-10-24）

    @Test
    public void workspaceHtmlArtifactBecomesHtmlCard() throws Exception {
        File f = writeTemp("report_", ".html", "<html><body><h3>周报</h3></body></html>");
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("filePath", f.getAbsolutePath());

            List<ComponentData> cards = ToolResultCardBinder.build(
                    null, "file_generator", AIToolResult.success(result), null);

            assertEquals(1, cards.size());
            assertEquals("html", cards.get(0).type);
            // file:// 才能被 HtmlSandbox 判成 LOCAL_FILE（脚本可跑那一档）
            assertEquals("file://" + f.getAbsolutePath(), cards.get(0).props.optString("url"));
            assertEquals("report_", cards.get(0).props.optString("title").substring(0, 7));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void missingHtmlPathKeepsFileCardInsteadOfBlankWebCard() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("filePath", "/data/data/com.oilquiz.app/files/不存在的报告.html");

        List<ComponentData> cards = ToolResultCardBinder.build(
                null, "file_generator", AIToolResult.success(result), null);

        assertEquals(1, cards.size());
        assertEquals("file_card", cards.get(0).type);
    }

    @Test
    public void artifactScanPicksUpHtmlPath() throws Exception {
        File f = writeTemp("page_", ".htm", "<html><body>x</body></html>");
        try {
            List<ComponentData> cards = ToolResultCardBinder.build(null, "python_execute",
                    AIToolResult.success("已生成网页: " + f.getAbsolutePath()), null);
            assertEquals(1, cards.size());
            assertEquals("html", cards.get(0).type);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void dynamicRendererUsesSameHtmlRule() throws Exception {
        File f = writeTemp("dyn_", ".html", "<html><body>y</body></html>");
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("file", f.getAbsolutePath());
            List<ComponentData> cards = DynamicToolResultRenderer.render(
                    "some_dynamic_tool", AIToolResult.success(result));
            assertEquals(1, cards.size());
            assertEquals("html", cards.get(0).type);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static File writeTemp(String prefix, String suffix, String content) throws Exception {
        File f = File.createTempFile(prefix, suffix);
        try (java.io.FileWriter w = new java.io.FileWriter(f)) {
            w.write(content);
        }
        return f;
    }

    private static Map<String, Object> agendaItem(String date, String time, String title,
                                                  String desc, String status) {
        Map<String, Object> item = new HashMap<>();
        item.put("date", date);
        item.put("time", time);
        item.put("title", title);
        item.put("desc", desc);
        item.put("status", status);
        return item;
    }

    private static ComponentData find(List<ComponentData> cards, String type) {
        for (ComponentData c : cards) {
            if (type.equals(c.type)) return c;
        }
        return null;
    }
}
