package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code ToolResultFields} 的守卫：每张表都必须与它<b>那个读取处迁移前的原清单</b>
 * 逐字一致（含顺序）。
 *
 * <p>期望值是从 {@code git show 3d5687b8^:…/ToolResultInterpreter.java} 一行行抄下来的，
 * 不是设想的。这条测试存在的理由就是上一轮事故：按"概念"把各处清单并成全局表之后，
 * {@code strDeep} 的"第一个命中"语义让每个读取处的接受面被放大到全部工具的键空间，
 * {@code output}（shell/python 的 stdout）被当路径取走，卡片链接指向一段文本。
 * 光看"键集合"抓不到这类回归——**顺序变了就是行为变了**，所以这里比的是数组等值而不是集合包含。</p>
 */
public class ToolResultFieldsTest {

    private static void assertSameList(String site, String[] actual, String... expected) {
        assertEquals(site + " 的清单与迁移前原清单不一致（顺序含 first-hit 语义）",
                Arrays.asList(expected), Arrays.asList(actual));
    }

    /** 一处一条：读取处 → 迁移前那行 strDeep/firstNonEmpty 的字面清单 */
    @Test
    public void everySiteKeepsItsOwnPreMigrationList() {
        assertSameList("networkSearchTemplate.count", ToolResultFields.SEARCH_COUNT,
                "count", "total");
        assertSameList("networkSearchTemplate.err", ToolResultFields.SEARCH_ERROR,
                "error", "message", "msg");
        assertSameList("databaseTemplate.msg", ToolResultFields.DATABASE_MESSAGE,
                "message");
        assertSameList("databaseTemplate.err", ToolResultFields.DATABASE_ERROR,
                "error");
        assertSameList("locationTemplate.err", ToolResultFields.LOCATION_ERROR,
                "error", "message");
        assertSameList("appOperationTemplate.msg", ToolResultFields.APP_OPERATION_MESSAGE,
                "message", "msg", "info");
        assertSameList("appOperationTemplate.count", ToolResultFields.APP_OPERATION_COUNT,
                "count");
        assertSameList("fileReaderTemplate.path", ToolResultFields.PATH_WITH_NAME,
                "file_path", "path", "file", "name");
        assertSameList("fileAnalyzerTemplate.path", ToolResultFields.PATH_WITH_NAME,
                "file_path", "path", "file", "name");
        assertSameList("fileAnalyzerTemplate.type", ToolResultFields.FILE_TYPE,
                "file_type", "type", "format");
        assertSameList("fileAnalyzerTemplate.size", ToolResultFields.ANALYZER_SIZE,
                "size", "file_size", "length");
        assertSameList("fileAnalyzerTemplate.summary", ToolResultFields.ANALYZER_SUMMARY,
                "summary", "analysis_summary", "overview");
        assertSameList("fileGeneratorTemplate.msg", ToolResultFields.FILE_GENERATOR_MESSAGE,
                "message", "msg");
        assertSameList("fileGeneratorTemplate.path", ToolResultFields.FILE_GENERATOR_PATH,
                "file_path", "file_name", "path", "output", "target");
        assertSameList("fileGeneratorTemplate.size", ToolResultFields.FILE_GENERATOR_SIZE,
                "size", "bytes_written", "file_size");
        assertSameList("webpageReaderTemplate.url", ToolResultFields.WEBPAGE_URL,
                "url", "link");
        assertSameList("webpageReaderTemplate.summary", ToolResultFields.WEBPAGE_SUMMARY,
                "summary", "abstract", "overview", "description");
        assertSameList("smartResearchTemplate.summary", ToolResultFields.RESEARCH_SUMMARY,
                "summary", "conclusion", "report", "overview", "result", "answer");
        assertSameList("systemResourceTemplate.msg", ToolResultFields.SYSTEM_RESOURCE_MESSAGE,
                "message", "msg", "info");
        assertSameList("systemResourceTemplate.url", ToolResultFields.SYSTEM_RESOURCE_URL,
                "url", "target");
        assertSameList("systemResourceTemplate.file", ToolResultFields.SYSTEM_RESOURCE_FILE,
                "file", "filePath", "path");
        assertSameList("permissionManagerTemplate.msg", ToolResultFields.PERMISSION_MESSAGE,
                "message", "msg");
        assertSameList("pythonCalculateTemplate.err", ToolResultFields.CALCULATE_ERROR,
                "error", "err", "exception");
        // 动态工具产物归类：历史是 file,path,url；本轮有意多认 file_path,link（见类注释）
        assertSameList("DynamicToolResultRenderer.artifact", ToolResultFields.DYNAMIC_ARTIFACT,
                "file", "file_path", "path", "url", "link");
    }

    /** 放宽过的唯一一处，必须留下"历史三键相对顺序不变"的证据，否则同类结果归类会变 */
    @Test
    public void dynamicArtifactWideningKeepsHistoricalOrder() {
        java.util.List<String> t = Arrays.asList(ToolResultFields.DYNAMIC_ARTIFACT);
        assertTrue(t.indexOf("file") < t.indexOf("path"));
        assertTrue(t.indexOf("path") < t.indexOf("url"));
        assertFalse("正文键不许进产物表（2026-10-23 卡片链接故障根因）", t.contains("output"));
        assertFalse(t.contains("target"));
    }

    /**
     * 含义随工具而变的键，只许出现在点名允许它的那一处。
     *
     * <p>反向也要成立：{@code FILE_GENERATOR_PATH} 收 {@code output}/{@code target}
     * 是因为写入类工具拿它们存落盘目标；{@code RESEARCH_SUMMARY} 收 {@code result}
     * 是因为那里的 result 就是报告正文。别处借用就是再次放大。</p>
     */
    @Test
    public void contentShapedKeysOnlyWhereThatToolActuallyUsesThem() {
        assertFalse(Arrays.asList(ToolResultFields.SYSTEM_RESOURCE_FILE).contains("output"));
        assertFalse(Arrays.asList(ToolResultFields.PATH_WITH_NAME).contains("output"));
        assertFalse(Arrays.asList(ToolResultFields.WEBPAGE_URL).contains("target"));
        assertFalse(Arrays.asList(ToolResultFields.WEBPAGE_SUMMARY).contains("result"));
        assertFalse(Arrays.asList(ToolResultFields.ANALYZER_SUMMARY).contains("result"));
        assertFalse("目录形状的键不许混进被读文件路径",
                Arrays.asList(ToolResultFields.PATH_WITH_NAME).contains("dir_path"));
        assertFalse("种类计数不许当通用计数（totalQuestions 是题库量）",
                Arrays.asList(ToolResultFields.SEARCH_COUNT).contains("totalQuestions"));
        assertTrue(Arrays.asList(ToolResultFields.FILE_GENERATOR_PATH).contains("output"));
        assertTrue(Arrays.asList(ToolResultFields.RESEARCH_SUMMARY).contains("result"));
        assertTrue(Arrays.asList(ToolResultFields.PATH_WITH_NAME).contains("name"));
    }

    /** 表里出现重复键说明是手抄错位：会让"第一个命中"的语义变得不可预期 */
    @Test
    public void noDuplicateKeysInsideAnyTable() {
        String[][] all = {ToolResultFields.SEARCH_COUNT, ToolResultFields.SEARCH_ERROR,
                ToolResultFields.DATABASE_MESSAGE, ToolResultFields.DATABASE_ERROR,
                ToolResultFields.LOCATION_ERROR, ToolResultFields.APP_OPERATION_MESSAGE,
                ToolResultFields.APP_OPERATION_COUNT, ToolResultFields.PATH_WITH_NAME,
                ToolResultFields.FILE_TYPE, ToolResultFields.ANALYZER_SIZE,
                ToolResultFields.ANALYZER_SUMMARY, ToolResultFields.FILE_GENERATOR_MESSAGE,
                ToolResultFields.FILE_GENERATOR_PATH, ToolResultFields.FILE_GENERATOR_SIZE,
                ToolResultFields.WEBPAGE_URL, ToolResultFields.WEBPAGE_SUMMARY,
                ToolResultFields.RESEARCH_SUMMARY, ToolResultFields.SYSTEM_RESOURCE_MESSAGE,
                ToolResultFields.SYSTEM_RESOURCE_URL, ToolResultFields.SYSTEM_RESOURCE_FILE,
                ToolResultFields.PERMISSION_MESSAGE, ToolResultFields.CALCULATE_ERROR,
                ToolResultFields.DYNAMIC_ARTIFACT};
        for (String[] table : all) {
            Set<String> seen = new HashSet<>(Arrays.asList(table));
            assertEquals("别名表内有重复键: " + Arrays.toString(table), table.length, seen.size());
            for (String key : table) {
                assertTrue("表里不许有空键名: " + Arrays.toString(table),
                        key != null && !key.trim().isEmpty());
            }
        }
    }
}
