package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 工具活动分类 + 活动总线契约（2026-10-25）：
 * 引擎侧把"正在执行哪个工具"归类发布，UI 按分类选多样化文案（正在生成文件…等）。
 */
public class AgentActivityKindTest {

    @Test
    public void 工具名分类_覆盖搜索读文件下载与各类生成() {
        assertEquals(AgentActivityKind.SEARCH, AgentActivityKind.classify("web_search"));
        assertEquals(AgentActivityKind.SEARCH, AgentActivityKind.classify("news_search"));
        assertEquals(AgentActivityKind.READ_FILE, AgentActivityKind.classify("read_file"));
        // 读文件优先于"pdf/文件生成"：pdf_read 应归读
        assertEquals(AgentActivityKind.READ_FILE, AgentActivityKind.classify("pdf_read"));
        assertEquals(AgentActivityKind.DOWNLOAD, AgentActivityKind.classify("download_file"));
        assertEquals(AgentActivityKind.GENERATE_IMAGE, AgentActivityKind.classify("generate_image"));
        assertEquals(AgentActivityKind.GENERATE_CHART, AgentActivityKind.classify("draw_chart"));
        assertEquals(AgentActivityKind.GENERATE_FILE, AgentActivityKind.classify("write_file"));
        assertEquals(AgentActivityKind.GENERATE_FILE, AgentActivityKind.classify("create_docx"));
        assertEquals(AgentActivityKind.RUN_CODE, AgentActivityKind.classify("python_execute"));
        assertEquals(AgentActivityKind.QUERY_DATA, AgentActivityKind.classify("sql_query"));
        assertEquals(AgentActivityKind.GENERIC, AgentActivityKind.classify("weather"));
        assertEquals(AgentActivityKind.GENERIC, AgentActivityKind.classify(null));
    }

    @Test
    public void 文案多样化_不同分类不同说法() {
        assertEquals("正在联网搜索…", AgentActivityKind.SEARCH.statusText(1_000));
        assertEquals("搜索较久，仍在检索…", AgentActivityKind.SEARCH.statusText(20_000));
        assertEquals("正在读取文件…", AgentActivityKind.READ_FILE.statusText(1_000));
        assertEquals("正在生成文件…", AgentActivityKind.GENERATE_FILE.statusText(1_000));
        assertEquals("文件较大，仍在生成…", AgentActivityKind.GENERATE_FILE.statusText(20_000));
        assertEquals("正在生成图片…", AgentActivityKind.GENERATE_IMAGE.statusText(1_000));
        assertEquals("正在生成图表…", AgentActivityKind.GENERATE_CHART.statusText(1_000));
        assertEquals("正在运行代码…", AgentActivityKind.RUN_CODE.statusText(1_000));
        assertEquals("正在下载文件…", AgentActivityKind.DOWNLOAD.statusText(1_000));
        assertEquals("正在查询数据…", AgentActivityKind.QUERY_DATA.statusText(1_000));
    }

    @Test
    public void 通用工具文案保持四段推进() {
        assertEquals("正在调用工具…", AgentActivityKind.GENERIC.statusText(1_000));
        assertEquals("工具执行中…", AgentActivityKind.GENERIC.statusText(5_000));
        assertEquals("工具耗时较长，仍在执行…", AgentActivityKind.GENERIC.statusText(20_000));
        assertEquals("仍在等待工具结果…", AgentActivityKind.GENERIC.statusText(50_000));
    }

    @Test
    public void 短名可还原_未知回退通用() {
        assertEquals(AgentActivityKind.GENERATE_FILE,
                AgentActivityKind.fromName(AgentActivityKind.GENERATE_FILE.name()));
        assertEquals(AgentActivityKind.GENERIC, AgentActivityKind.fromName("not_a_kind"));
        assertEquals(AgentActivityKind.GENERIC, AgentActivityKind.fromName(null));
    }

    @Test
    public void 活动总线_开始结束与最早快照() {
        AgentActivityBus.clear();
        assertFalse(AgentActivityBus.isRunning());
        assertNull(AgentActivityBus.current());

        AgentActivityBus.started("web_search");
        assertTrue(AgentActivityBus.isRunning());
        AgentActivityBus.Snapshot snap = AgentActivityBus.current();
        assertNotNull(snap);
        assertEquals(AgentActivityKind.SEARCH, snap.kind);
        assertEquals("web_search", snap.toolName);

        AgentActivityBus.started("write_file");
        AgentActivityBus.started("python_execute");
        assertNotNull(AgentActivityBus.current());
        // 最早开始的那个优先（web_search）
        assertEquals(AgentActivityKind.SEARCH, AgentActivityBus.current().kind);

        AgentActivityBus.finished("web_search");
        assertEquals(AgentActivityKind.GENERATE_FILE, AgentActivityBus.current().kind);
        AgentActivityBus.finished("write_file");
        AgentActivityBus.finished("python_execute");
        assertFalse(AgentActivityBus.isRunning());
        AgentActivityBus.clear();
    }

    @Test
    public void 活动快照_带中文别名与正在干什么的目标() {
        AgentActivityBus.clear();
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("file_name", "C:\\temp\\季度报告.docx");
        AgentActivityBus.started("file_generator", params);
        AgentActivityBus.Snapshot snap = AgentActivityBus.current();
        assertNotNull(snap);
        // 中文别名来自 ToolMetaRegistry（已知工具）
        assertEquals("文件生成", snap.displayName);
        // 目标：去掉目录、只留文件名
        assertEquals("季度报告.docx", snap.target);
        AgentActivityBus.clear();

        // 未知工具 → 润色英文名（中文优先/英文兜底，不强行翻译）
        AgentActivityBus.started("foo_bar_tool", null);
        snap = AgentActivityBus.current();
        assertNotNull(snap);
        assertEquals("Foo Bar", snap.displayName);
        assertEquals("", snap.target);
        AgentActivityBus.clear();
    }

    @Test
    public void 状态文案可追加正在干什么() {
        String withTarget = com.oilquiz.app.ai.chat.InferenceStatusPhrases.toolPhrase(
                AgentActivityKind.GENERATE_FILE, 1_000, "季度报告.docx");
        assertEquals("正在生成文件 · 季度报告.docx", withTarget);
        // 没有目标时保持原句（带省略号）
        assertEquals("正在生成文件…", com.oilquiz.app.ai.chat.InferenceStatusPhrases
                .toolPhrase(AgentActivityKind.GENERATE_FILE, 1_000, null));
        assertEquals("正在生成文件…", com.oilquiz.app.ai.chat.InferenceStatusPhrases
                .toolPhrase(AgentActivityKind.GENERATE_FILE, 1_000, "  "));
        // 目标带换行/超长也要能安全用于单行状态
        String dirty = com.oilquiz.app.ai.chat.InferenceStatusPhrases.toolPhrase(
                AgentActivityKind.GENERATE_FILE, 1_000, "季度\n报告" + "x".repeat(60));
        assertFalse(dirty.contains("\n"));
        assertTrue(dirty.length() < 60);
    }

    @Test
    public void 表外工具与空值_兜底不抛异常() {
        // 表外工具：照样记账、照样给通用分类；显示名是润色英文名
        AgentActivityBus.clear();
        AgentActivityBus.started("totally_unknown_tool", null);
        AgentActivityBus.Snapshot snap = AgentActivityBus.current();
        assertNotNull(snap);
        assertEquals(AgentActivityKind.GENERIC, snap.kind);
        assertEquals("Totally Unknown", snap.displayName);
        AgentActivityBus.finished("totally_unknown_tool");
        AgentActivityBus.clear();

        // 空名/空参数/结束未知工具都不允许抛异常
        AgentActivityBus.started(null);
        AgentActivityBus.started("");
        AgentActivityBus.started("x", null);
        AgentActivityBus.finished(null);
        AgentActivityBus.finished("never_started");
        AgentActivityBus.clear();
        assertNull(AgentActivityBus.current());
        assertFalse(AgentActivityBus.isRunning());
    }

    @Test
    public void 目标提取_脏参数也安全() {
        assertEquals("", AgentActivityBus.extractTarget(null));
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("file_name", 123);
        assertEquals("", AgentActivityBus.extractTarget(m));
        m.put("file_name", "a".repeat(200));
        assertEquals("", AgentActivityBus.extractTarget(m));
        m.put("file_name", "C:\\tmp\\季度\n报告.docx");
        String t = AgentActivityBus.extractTarget(m);
        assertFalse(t.contains("\n"));
        assertTrue(t.endsWith(".docx"));
        m.clear();
        m.put("query", "  https://example.com/a?b=1  ");
        String q = AgentActivityBus.extractTarget(m);
        assertFalse(q.startsWith("http"));
        assertTrue(q.length() <= 24);
        // 分类对怪名字也只返回 GENERIC，不抛异常
        assertEquals(AgentActivityKind.GENERIC, AgentActivityKind.classify("@@@"));
        assertEquals(AgentActivityKind.GENERIC, AgentActivityKind.classify(null));
    }
}
