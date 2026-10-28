package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具改名后的历史统计迁移（2026-10-28：python_file_ops → document_parser）。
 *
 * <p>共现键是 {@code "toolA|toolB"} 的字符串。只换一侧会造出 {@code document_parser|ui_component}
 * 与 {@code python_file_ops|ui_component} 两条互不相干的记录，同一个工具在排行里裂成两个——
 * 所以迁移必须换两侧、撞车时相加，且幂等（跑两遍结果一样）。
 */
public class OnlineToolUsageTrackerMigrateTest {

    @Test
    public void renamesBothSidesOfThePair() {
        Map<String, Integer> in = new LinkedHashMap<>();
        in.put("python_file_ops|ui_component", 12);
        in.put("tool_registry|python_file_ops", 5);

        Map<String, Integer> out = OnlineToolUsageTracker.migrateToolNames(in);

        assertEquals(12, (int) out.get("document_parser|ui_component"));
        assertEquals(5, (int) out.get("tool_registry|document_parser"));
        assertTrue("旧键不能残留", out.keySet().stream().noneMatch(k -> k.contains("python_file_ops")));
    }

    @Test
    public void mergesWhenOldAndNewBothExist() {
        Map<String, Integer> in = new LinkedHashMap<>();
        in.put("python_file_ops|knowledge_base", 7);
        in.put("document_parser|knowledge_base", 3);

        Map<String, Integer> out = OnlineToolUsageTracker.migrateToolNames(in);

        assertEquals("撞车要相加，不能覆盖", 10, (int) out.get("document_parser|knowledge_base"));
        assertEquals(1, out.size());
    }

    @Test
    public void leavesUnrelatedToolsAlone() {
        Map<String, Integer> in = new LinkedHashMap<>();
        in.put("ai_weather|ui_component", 160);
        in.put("python_execute|tool_registry", 407);

        Map<String, Integer> out = OnlineToolUsageTracker.migrateToolNames(in);

        assertEquals(in, out);
    }

    @Test
    public void isIdempotent() {
        Map<String, Integer> in = new LinkedHashMap<>();
        in.put("python_file_ops|time_date", 1);

        Map<String, Integer> once = OnlineToolUsageTracker.migrateToolNames(in);
        Map<String, Integer> twice = OnlineToolUsageTracker.migrateToolNames(once);

        assertEquals(once, twice);
    }

    @Test
    public void handlesNullAndEmpty() {
        assertTrue(OnlineToolUsageTracker.migrateToolNames(null).isEmpty());
        assertTrue(OnlineToolUsageTracker.migrateToolNames(new LinkedHashMap<>()).isEmpty());
    }
}
