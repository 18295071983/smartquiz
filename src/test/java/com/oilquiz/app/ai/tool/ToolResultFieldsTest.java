package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolResultFields} 的守卫测试。
 *
 * <p>核心作用不是测"能不能取到值"，而是钉住三件容易做错的事：
 * ① 合并别名表时<b>一个历史键都不能丢</b> —— 下面把迁移前各处实际写过的键集合抄成
 * "迁移前快照"，逐个要求被<b>那一处现在问的那张表</b>覆盖，将来谁精简表项就会红；
 * ② 边界规则不能破：{@code message} 不许进 {@link ToolResultFields#ERROR}
 * （很多工具的 message 是成功提示，当错误原文渲染会说谎），
 * 计数不许把 rows/words/pages 混成通用 count（会显示错数字）；
 * ③ <b>含义随工具而变的键不许进全局表</b>（{@code output}/{@code target}/
 * {@code name}/{@code result}）。这一条是真机事故换来的：上一轮合并把 {@code output}
 * 当"路径别名"收进 {@link ToolResultFields#PATH} 与 {@link ToolResultFields#ARTIFACT}，
 * 于是 shell 的 UI 摘要多打一行"📄 文件：&lt;整段 stdout&gt;"，动态工具结果被
 * {@code DynamicToolResultRenderer} 归成产物卡、渲染出指向一段文本的 file_card
 * ——用户看到的就是"卡片文件链接全坏了"。要那种降级请在调用处点名。</p>
 */
public class ToolResultFieldsTest {

    /**
     * 迁移前各读取处实际用过的键集合（照抄旧代码，不是设想出来的）。
     * 快照必须按<b>读取处</b>对号入座到"那一处现在问的表"，而不是"全部并进一张 PATH 就算覆盖"
     * ——那正是上次引入事故的合并方式。
     */
    private static final String[][] PATH_BEFORE_READER = {
            // file_reader / file_analyzer：允许退到裸文件名 → PATH_OR_NAME
            {"file_path", "path", "file", "name"},
            // shell_command：只认真正的路径键（output 在这里是 stdout！）→ PATH
            {"file", "filePath", "path"},
            // 文件写入类工具：output/target 在这类结果里就是落盘路径，单独点名保留 → PATH_FILE_OPS
            {"file_path", "file_name", "path", "output", "target"},
    };
    private static final String[] PATH_TABLE_PER_READER =
            {"PATH_OR_NAME", "PATH", "PATH_FILE_OPS"};

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

    /** 按表名取实际表（写死映射，避免测试自己又长出第二份名单） */
    private static String[] table(String name) {
        switch (name) {
            case "PATH": return ToolResultFields.PATH;
            case "PATH_OR_NAME": return ToolResultFields.PATH_OR_NAME;
            case "PATH_FILE_OPS": return ToolResultFields.PATH_FILE_OPS;
            case "URL": return ToolResultFields.URL;
            case "URL_APP_OPS": return ToolResultFields.URL_APP_OPS;
            case "SUMMARY": return ToolResultFields.SUMMARY;
            case "SUMMARY_OR_RESULT": return ToolResultFields.SUMMARY_OR_RESULT;
            case "ARTIFACT": return ToolResultFields.ARTIFACT;
            default: throw new IllegalArgumentException("未知表: " + name);
        }
    }

    private static void assertCovers(String[] table, String[] before) {
        List<String> keys = Arrays.asList(table);
        for (String key : before) {
            assertTrue("别名表缺少历史键 " + key + "（表=" + Arrays.toString(table) + "）",
                    keys.contains(key));
        }
    }

    @Test
    public void everyHistoricalKeySetIsStillCovered() {
        assertEquals("每个路径快照都要指定它该问的表",
                PATH_BEFORE_READER.length, PATH_TABLE_PER_READER.length);
        for (int i = 0; i < PATH_BEFORE_READER.length; i++) {
            assertCovers(table(PATH_TABLE_PER_READER[i]), PATH_BEFORE_READER[i]);
        }
        assertCovers(table("URL"), URL_BEFORE[0]);
        assertCovers(table("URL_APP_OPS"), URL_BEFORE[1]);
        for (String[] before : SIZE_BEFORE) {
            assertCovers(ToolResultFields.SIZE, before);
        }
        for (String[] before : MESSAGE_BEFORE) {
            assertCovers(ToolResultFields.MESSAGE, before);
        }
        assertCovers(table("SUMMARY"), SUMMARY_BEFORE[0]);
        assertCovers(table("SUMMARY"), SUMMARY_BEFORE[1]);
        assertCovers(table("SUMMARY_OR_RESULT"), SUMMARY_BEFORE[2]);
        assertCovers(ToolResultFields.FILE_TYPE, new String[]{"file_type", "type", "format"});
        assertCovers(ToolResultFields.ARTIFACT, new String[]{"file", "path", "url"});
        assertCovers(ToolResultFields.COUNT_TOTAL, new String[]{"count", "total"});
        // 旧的 error 读取处用过四种组合，其中两种是"error 退到 message"，靠 union 组合保持
        assertCovers(ToolResultFields.ERROR, new String[]{"error", "err", "exception"});
        assertCovers(ToolResultFields.union(ToolResultFields.ERROR, ToolResultFields.MESSAGE),
                new String[]{"error", "message", "msg"});
    }

    /**
     * 第四条规则的正反两面：内容键（output/target/name/result）不在任何全局表里，
     * 但确实需要它们的读取处有对应的点名表可用，且点名表不得反过来吞掉核心路径键。
     */
    @Test
    public void contentShapedKeysStayOutOfGlobalTables() {
        String[][] globals = {ToolResultFields.PATH, ToolResultFields.URL,
                ToolResultFields.ARTIFACT, ToolResultFields.SUMMARY};
        for (String contentKey : new String[]{"output", "target", "name", "result"}) {
            for (String[] global : globals) {
                assertFalse(contentKey + " 含义随工具而变，不许进全局表 "
                                + Arrays.toString(global) + "（会被当路径/链接/摘要渲染）",
                        Arrays.asList(global).contains(contentKey));
            }
        }
        // 点名表：谁要用，谁自己负责语义
        assertTrue(Arrays.asList(ToolResultFields.PATH_OR_NAME).contains("name"));
        assertTrue(Arrays.asList(ToolResultFields.PATH_FILE_OPS).contains("output"));
        assertTrue(Arrays.asList(ToolResultFields.URL_APP_OPS).contains("target"));
        assertTrue(Arrays.asList(ToolResultFields.SUMMARY_OR_RESULT).contains("result"));
        // 点名表是把核心表包进去的，不是另起一份手抄名单
        assertTrue(Arrays.asList(ToolResultFields.PATH_OR_NAME).containsAll(
                Arrays.asList(ToolResultFields.PATH)));
        assertTrue(Arrays.asList(ToolResultFields.URL_APP_OPS).containsAll(
                Arrays.asList(ToolResultFields.URL)));
        assertTrue(Arrays.asList(ToolResultFields.SUMMARY_OR_RESULT).containsAll(
                Arrays.asList(ToolResultFields.SUMMARY)));
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
        List<String> path = Arrays.asList(ToolResultFields.PATH_OR_NAME);
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
        String[][] all = {ToolResultFields.PATH, ToolResultFields.PATH_OR_NAME,
                ToolResultFields.PATH_FILE_OPS, ToolResultFields.URL, ToolResultFields.URL_APP_OPS,
                ToolResultFields.ARTIFACT, ToolResultFields.FILE_TYPE, ToolResultFields.SIZE,
                ToolResultFields.MESSAGE, ToolResultFields.ERROR, ToolResultFields.SUMMARY,
                ToolResultFields.SUMMARY_OR_RESULT, ToolResultFields.COUNT_TOTAL};
        for (String[] table : all) {
            Set<String> seen = new HashSet<>(Arrays.asList(table));
            assertEquals("别名表内有重复键: " + Arrays.toString(table), table.length, seen.size());
        }
    }
}
