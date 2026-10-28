package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * text_tools(regex_extract) 的捕获组回归（2026-10-29，真机对话历史实测）。
 *
 * <p>旧实现无条件只取 {@code group(1)}：{@code (\d{4})-(\d{2})-(\d{2})} 只剩年份、
 * {@code "([A-Z]-\d+[A-Z]?)\s*振动([\d.]+)"} 只剩位号——用户的机泵/报警记录是
 * "位号 + 数值"成对提取，配对关系直接被切断。</p>
 */
public class TextToolsRegexGroupsTest {

    private final TextToolsTool tool = new TextToolsTool();

    @Test
    @SuppressWarnings("unchecked")
    public void multipleCaptureGroupsAreAllReturned() {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "regex_extract");
        p.put("text", "泵 P-2101B 振动 2.8，电机 M-1102 振动 1.9");
        p.put("pattern", "([A-Z]-\\d+[A-Z]?)\\s*振动\\s*([\\d.]+)");

        AIToolResult r = tool.execute(p);
        assertTrue(r.isSuccess());
        List<List<String>> groups = (List<List<String>>) r.getAdditionalInfo().get("groups");
        assertNotNull("必须回传 groups，否则配对关系仍然丢。additionalInfo="
                + r.getAdditionalInfo() + " result=" + r.getResult(), groups);
        assertEquals(2, groups.size());
        assertEquals("P-2101B", groups.get(0).get(0));
        assertEquals("2.8", groups.get(0).get(1));
        assertEquals("M-1102", groups.get(1).get(0));
        assertEquals("1.9", groups.get(1).get(1));

        List<String> matches = (List<String>) r.getAdditionalInfo().get("matches");
        assertTrue("不传 group_index 时 matches 是完整匹配", matches.get(0).contains("振动"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void datePatternKeepsAllThreeGroups() {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "regex_extract");
        p.put("text", "记录日期 2026-10-16 与 2026-10-29");
        p.put("pattern", "(\\d{4})-(\\d{2})-(\\d{2})");
        AIToolResult r = tool.execute(p);
        List<List<String>> g = (List<List<String>>) r.getAdditionalInfo().get("groups");
        assertEquals(2, g.size());
        assertEquals(java.util.Arrays.asList("2026", "10", "16"), g.get(0));
        assertEquals(java.util.Arrays.asList("2026", "10", "29"), g.get(1));
    }

    /** group_index 保留旧用法（只要某一组），越界要报得清楚 */
    @Test
    @SuppressWarnings("unchecked")
    public void groupIndexPicksOneGroupAndOutOfRangeIsExplained() {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "regex_extract");
        p.put("text", "2026-10-16");
        p.put("pattern", "(\\d{4})-(\\d{2})-(\\d{2})");
        p.put("group_index", 2);
        List<String> m = (List<String>) tool.execute(p).getAdditionalInfo().get("matches");
        assertEquals(java.util.Collections.singletonList("10"), m);

        Map<String, Object> bad = new HashMap<>(p);
        bad.put("group_index", 9);
        AIToolResult r = tool.execute(bad);
        assertTrue("越界要失败", !r.isSuccess());
        assertTrue("报错要说清范围，别让人猜", r.getErrorMessage().contains("0..3"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void patternWithoutGroupsStillWorks() {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "regex_extract");
        p.put("text", "abc 123 def 456");
        p.put("pattern", "\\d+");
        AIToolResult r = tool.execute(p);
        List<String> m = (List<String>) r.getAdditionalInfo().get("matches");
        assertEquals(java.util.Arrays.asList("123", "456"), m);
        assertTrue("无捕获组时不该塞一个空 groups 进去",
                r.getAdditionalInfo().get("groups") == null);
    }
}
