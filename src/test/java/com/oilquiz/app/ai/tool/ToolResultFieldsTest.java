package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ToolResultFields} 的守卫测试。
 *
 * <p>核心作用不是测"能不能取到值"，而是钉住两件容易做错的事：
 * ① 合并别名表时<b>一个历史键都不能丢</b> —— 下面把迁移前各处实际写过的键集合抄成
 * "迁移前快照"，逐个要求被新表覆盖，将来谁精简表项就会红；
 * ② 边界规则不能破：{@code message} 不许进 {@link ToolResultFields#ERROR}
 * （很多工具的 message 是成功提示，当错误原文渲染会说谎），
 * 计数不许把 rows/words/pages 混成通用 count（会显示错数字）。
 */
public class ToolResultFieldsTest {

    /** 迁移前各读取处实际用过的键集合（照抄旧代码，不是设想出来的） */
    private static final String[][] PATH_BEFORE = {
            {"file_path", "path", "file", "name"},
            {"file", "filePath", "path"},
            {"file_path", "file_name", "path", "output", "target"},
    };
    private static final String[][] URL_BEFORE = {
            {"url", "link"},
            {"url", "target"},
    };
    private static final String[][] SIZE_BEFORE = {
            {"size", "file_size", "length"},
            {"size", "bytes_written", "file_size"},
    };
    private static final String[][] MESSAGE_BEFORE = {
            {"message", "msg"},
            {"message", "msg", "info"},
            {"message"},
    };
    private static final String[][] SUMMARY_BEFORE = {
            {"summary", "analysis_summary", "overview"},
            {"summary", "abstract", "overview", "description"},
            {"summary", "conclusion", "report", "overview", "result", "answer"},
    };

    private static void assertCovers(String[] table, String[] before) {
        List<String> keys = Arrays.asList(table);
        for (String key : before) {
            assertTrue("别名表缺少历史键 " + key + "（表=" + Arrays.toString(table) + "）",
                    keys.contains(key));
        }
    }

    @Test
    public void everyHistoricalKeySetIsStillCovered() {
        for (String[] before : PATH_BEFORE) {
            assertCovers(ToolResultFields.PATH, before);
        }
        for (String[] before : URL_BEFORE) {
            assertCovers(ToolResultFields.URL, before);
        }
        for (String[] before : SIZE_BEFORE) {
            assertCovers(ToolResultFields.SIZE, before);
        }
        for (String[] before : MESSAGE_BEFORE) {
            assertCovers(ToolResultFields.MESSAGE, before);
        }
        for (String[] before : SUMMARY_BEFORE) {
            assertCovers(ToolResultFields.SUMMARY, before);
        }
        assertCovers(ToolResultFields.FILE_TYPE, new String[]{"file_type", "type", "format"});
        assertCovers(ToolResultFields.ARTIFACT, new String[]{"file", "path", "url"});
        assertCovers(ToolResultFields.COUNT_TOTAL, new String[]{"count", "total"});
        // 旧的 error 读取处用过四种组合，其中两种是"error 退到 message"，靠 union 组合保持
        assertCovers(ToolResultFields.ERROR, new String[]{"error", "err", "exception"});
        assertCovers(ToolResultFields.union(ToolResultFields.ERROR, ToolResultFields.MESSAGE),
                new String[]{"error", "message", "msg"});
    }

    @Test
    public void errorMessageAndCountBoundariesHold() {
        List<String> error = Arrays.asList(ToolResultFields.ERROR);
        assertFalse("message 不许进 ERROR：它常是成功提示，当错误渲染会说谎", error.contains("message"));
        assertFalse(error.contains("msg"));
        assertFalse(error.contains("info"));
        List<String> count = Arrays.asList(ToolResultFields.COUNT_TOTAL);
        for (String kind : new String[]{"rows", "words", "pages", "lines", "sources", "matches"}) {
            assertFalse("不同种类的量不能混进通用 count: " + kind, count.contains(kind));
        }
        // source 在网页提取里是"来源站点名"，不是链接；收进 URL 会把站点名当 URL 渲染
        assertFalse("source 不属于 URL 别名表",
                Arrays.asList(ToolResultFields.URL).contains("source"));
        assertEquals("状态词只该是 status 本身", 1, ToolResultFields.STATUS.length);
        assertEquals("status", ToolResultFields.STATUS[0]);
    }

    @Test
    public void precedencePutsAuthoritativePathKeysBeforeDisplayNames() {
        List<String> path = Arrays.asList(ToolResultFields.PATH);
        assertTrue(path.indexOf("file_path") < path.indexOf("name"));
        assertTrue(path.indexOf("path") < path.indexOf("name"));
        assertTrue(path.indexOf("file") < path.indexOf("name"));
        // 旧站点里 file 优先于 path（ARTIFACT），迁移后相对顺序要保持，否则同类结果归类会变
        List<String> artifact = Arrays.asList(ToolResultFields.ARTIFACT);
        assertTrue(artifact.indexOf("file") < artifact.indexOf("path"));
        assertTrue(artifact.indexOf("path") < artifact.indexOf("url"));
    }

    @Test
    public void unionKeepsOrderAndDeduplicates() {
        String[] merged = ToolResultFields.union(
                new String[]{"a", "b"}, new String[]{"b", "c"}, null, new String[]{});
        assertEquals(Arrays.asList("a", "b", "c"), Arrays.asList(merged));
        assertEquals(0, ToolResultFields.union(new String[0][]).length);
    }

    /** 表里出现重复键说明是手抄错位：会让"第一个命中"的语义变得不可预期 */
    @Test
    public void noDuplicateKeysInsideAnyTable() {
        String[][] all = {ToolResultFields.PATH, ToolResultFields.URL, ToolResultFields.ARTIFACT,
                ToolResultFields.FILE_TYPE, ToolResultFields.SIZE, ToolResultFields.MESSAGE,
                ToolResultFields.ERROR, ToolResultFields.SUMMARY, ToolResultFields.COUNT_TOTAL};
        for (String[] table : all) {
            Set<String> seen = new HashSet<>(Arrays.asList(table));
            assertEquals("别名表内有重复键: " + Arrays.toString(table), table.length, seen.size());
        }
    }
}
