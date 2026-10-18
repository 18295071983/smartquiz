package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具结果存储的驻留上限（C1）。
 *
 * <p>这里存的是原始结果对象 + 结果 JSON 全文，且是静态列表：
 * 不限长的话，一次长会话里几十个几百 KB 的工具结果会一直压在内存里不掉。
 * 用例钉住"只保留最近 MAX_KEEP 条、最新一条永远可用、流程内引用不受影响"。
 */
public class ToolResultStoreTest {

    @After
    public void tearDown() {
        ToolResultStore.clear();
    }

    @Test
    public void keepsOnlyRecentExecutions() {
        for (int i = 0; i < ToolResultStore.MAX_KEEP + 12; i++) {
            ToolResultStore.save("tool_" + i, params("i", i), "result-" + i, i);
        }

        assertEquals(ToolResultStore.MAX_KEEP, ToolResultStore.size());
        ToolResultStore.ToolExecution last = ToolResultStore.getLast();
        assertNotNull("最新一条必须在", last);
        assertEquals("tool_" + (ToolResultStore.MAX_KEEP + 11), last.toolName);
    }

    @Test
    public void inFlightFlowReferencesStillResolve() {
        // 聚合流程只引用最近几步：连续保存 5 步后，第 0 步也必须还在
        for (int i = 0; i < 5; i++) {
            ToolResultStore.save("step_tool", params("k", i), "v" + i, i);
        }
        ToolResultStore.ToolExecution first = ToolResultStore.getByIndex(0);
        assertNotNull("流程内的早期步骤不能被裁掉", first);
        assertEquals("v0", String.valueOf(first.result));
    }

    @Test
    public void clearEmptiesStore() {
        ToolResultStore.save("t", params("a", 1), "r", 0);
        assertTrue(ToolResultStore.size() > 0);
        ToolResultStore.clear();
        assertEquals(0, ToolResultStore.size());
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}
