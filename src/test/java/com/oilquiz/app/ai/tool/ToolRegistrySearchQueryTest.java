package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 工具发现搜索的口径（2026-11-06 真机回归）：
 * 模型写多关键词（"打开应用 启动 intent 应用操作"）时必须能命中 system_resource，
 * 否则它会判定"没有打开应用的能力"，改去造一张点不动的卡片按钮。
 */
public class ToolRegistrySearchQueryTest {

    @Test
    public void splitsByWhitespaceAndChinesePunctuation() {
        assertEquals(Arrays.asList("打开应用", "启动", "intent", "应用操作"),
                ToolRegistryTool.splitSearchTokens("打开应用 启动，intent、应用操作"));
        assertEquals(Collections.emptyList(),
                ToolRegistryTool.splitSearchTokens("  "));
    }

    @Test
    public void multiKeywordQueryFindsSystemResourceByAnyToken() {
        String desc = "系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话、控制应用、"
                + "执行Shell命令(shell_command)、在Termux里执行命令、读写系统设置等。";
        ToolRegistryTool.SearchScore score = ToolRegistryTool.scoreTool(
                "system_resource", desc,
                Arrays.asList("open_app", "launch_app", "make_call"),
                ToolRegistryTool.splitSearchTokens("打开应用 启动 intent 应用操作"));
        assertTrue("多关键词必须能命中 system_resource", score.score > 0);
        assertTrue(score.hitTokens.contains("打开应用"));
        // 只命中描述，不能冒充"名字命中"
        assertEquals(1, score.tier);
    }

    @Test
    public void singleKeywordKeepsNameAboveDescription() {
        ToolRegistryTool.SearchScore nameHit = ToolRegistryTool.scoreTool(
                "ui_component", "创建UI组件", Collections.emptyList(),
                ToolRegistryTool.splitSearchTokens("ui_component"));
        ToolRegistryTool.SearchScore descHit = ToolRegistryTool.scoreTool(
                "other_tool", "创建 ui_component 卡片", Collections.emptyList(),
                ToolRegistryTool.splitSearchTokens("ui_component"));
        assertEquals(3, nameHit.tier);
        assertTrue(nameHit.score > descHit.score);
        assertEquals(1, descHit.tier);
    }

    @Test
    public void aliasHitIsReported() {
        ToolRegistryTool.SearchScore score = ToolRegistryTool.scoreTool(
                "system_resource", "系统资源", Arrays.asList("open_app", "launch_app"),
                ToolRegistryTool.splitSearchTokens("launch"));
        assertEquals(2, score.tier);
        assertEquals("launch_app", score.alias);
        assertFalse(score.hitTokens.isEmpty());
    }

    @Test
    public void emptyQueryMatchesEverythingLikeBefore() {
        ToolRegistryTool.SearchScore score = ToolRegistryTool.scoreTool(
                "ui_component", "", Collections.emptyList(), Collections.emptyList());
        assertTrue(score.score > 0);
        assertEquals(3, score.tier);
    }
}
