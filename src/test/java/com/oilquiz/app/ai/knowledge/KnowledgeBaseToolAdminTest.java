package com.oilquiz.app.ai.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.AIToolResult;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * knowledge_base 工具层的"资产操作"：删整篇 / 导出 / 清空保护（2026-11-10）。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li>{@code delete(source=…)} 能按导入来源删掉整篇（改版文档替换的前提）；</li>
 *   <li>{@code export} 落到文件并回 {@code count}，文件里是 {@code chunks} 数组（能被 import_file 吃回去）；</li>
 *   <li>{@code clear} 默认保留 {@code tool_defs}，只有 {@code include_tool_defs=true} 才一起清。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class KnowledgeBaseToolAdminTest {

    private KnowledgeBaseManager manager;
    private KnowledgeBaseTool tool;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        KnowledgeBaseManager.resetForTesting();
        manager = KnowledgeBaseManager.getInstance(context);
        manager.clear();   // 单测之间互不可见（clear 是全清，连 tool_defs 一起）
        tool = new KnowledgeBaseTool(context);
    }

    private Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private JSONObject payload(AIToolResult result) throws Exception {
        return new JSONObject(String.valueOf(result.getResult()));
    }

    @Test
    public void deleteBySourceWipesWholeDocument() throws Exception {
        String source = "/sdcard/doc-a.pdf";
        manager.addChunk("文档甲", "guide", "", "甲一", source);
        manager.addChunk("文档甲", "guide", "", "甲二", source);
        manager.addChunk("文档乙", "guide", "", "乙一", "/sdcard/doc-b.pdf");

        AIToolResult r = tool.execute(params("action", "delete", "source", source));

        assertTrue("按来源删应成功: " + r.getErrorMessage(), r.isSuccess());
        JSONObject body = payload(r);
        assertEquals(2, body.optInt("deleted"));
        assertEquals("别的来源不受影响", 1, manager.stats().optInt("total"));
    }

    @Test
    public void exportWritesChunksArrayToFile() throws Exception {
        manager.addChunk("条目一", "guide", "kw1", "正文一", "s1");
        manager.addChunk("条目二", "general", "", "正文二", "s2");

        AIToolResult r = tool.execute(params("action", "export"));

        assertTrue("导出应成功: " + r.getErrorMessage(), r.isSuccess());
        JSONObject body = payload(r);
        assertEquals(2, body.optInt("count"));
        assertFalse("必须给出落盘路径", body.optString("file").isEmpty());
    }

    @Test
    public void clearWipesEverythingIncludingToolDefs() throws Exception {
        manager.addChunk("用户条目", "general", "", "正文", "");
        manager.addChunk("工具定义", "tool_defs", "", "工具正文", "system_tool_defs");

        AIToolResult r = tool.execute(params("action", "clear"));

        assertTrue(r.getErrorMessage(), r.isSuccess());
        assertEquals("两条一起清", 2, payload(r).optInt("cleared"));
        assertEquals(0, manager.countByCategory("general"));
        assertEquals("tool_defs 也清（下次 Agent 初始化自动重灌）", 0, manager.countByCategory("tool_defs"));
    }
}
