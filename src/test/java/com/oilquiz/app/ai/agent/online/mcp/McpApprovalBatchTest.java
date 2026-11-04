package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量审批（管理页"批量设置"）的选取与计划。
 *
 * <p>这批断言盯的是两个容易悄悄发生的问题：
 * <ul>
 *   <li>预选范围算错——收紧时把纯查询也勾上，用户会连着一路点"自动允许"止损；</li>
 *   <li>把"已经是这一档"的工具算进改动——toast 报的数量和配置里真的变了的东西对不上。</li>
 * </ul>
 */
public class McpApprovalBatchTest {

    private static McpApprovalBatch.Item item(String name, String effective, String explicit, String basis) {
        return new McpApprovalBatch.Item(name, effective, explicit, basis);
    }

    private static List<McpApprovalBatch.Item> mixed() {
        List<McpApprovalBatch.Item> items = new ArrayList<>();
        items.add(item("maps_geo", McpConstants.APPROVAL_AUTO, "",
                "无注解·未命中写操作关键词"));
        items.add(item("maps_weather", McpConstants.APPROVAL_AUTO, "",
                "服务商声明只读"));
        items.add(item("delete_key", McpConstants.APPROVAL_ASK, "",
                "无注解·名称/描述像写操作（delete）"));
        items.add(item("send_message", McpConstants.APPROVAL_ASK, "",
                "服务商声明可破坏"));
        items.add(item("create_ticket", McpConstants.APPROVAL_ASK, "",
                "无注解·名称/描述像写操作（create）"));
        return items;
    }

    @Test
    public void writeLikePresetCatchesBothAnnotationAndHeuristic() {
        List<McpApprovalBatch.Item> items = mixed();
        List<McpApprovalBatch.Change> plan =
                McpApprovalBatch.plan(items, McpApprovalBatch.Preset.WRITE_LIKE, McpConstants.APPROVAL_ASK);
        assertEquals("写操作那一档应该 3 个: " + plan, 3, plan.size());
        for (McpApprovalBatch.Change c : plan) {
            assertFalse("纯查询不能进收紧计划：" + c.toolName,
                    c.toolName.equals("maps_geo") || c.toolName.equals("maps_weather"));
        }
    }

    @Test
    public void readOnlyPresetIsTheComplement() {
        List<McpApprovalBatch.Change> plan = McpApprovalBatch.plan(mixed(),
                McpApprovalBatch.Preset.READ_ONLY, McpConstants.APPROVAL_AUTO);
        assertEquals(2, plan.size());
        for (McpApprovalBatch.Change c : plan) {
            assertTrue("只该动只读那一半: " + c.toolName,
                    c.toolName.equals("maps_geo") || c.toolName.equals("maps_weather"));
        }
    }

    @Test
    public void alreadyExplicitToolsAreNotRewritten() {
        List<McpApprovalBatch.Item> items = new ArrayList<>();
        // 已经显式设为"每次询问"的写操作工具：再批量设一次同一档，计划里不该有它
        items.add(item("delete_key", McpConstants.APPROVAL_ASK, McpConstants.APPROVAL_ASK,
                "无注解·名称/描述像写操作（delete）"));
        List<McpApprovalBatch.Change> plan = McpApprovalBatch.plan(items,
                McpApprovalBatch.Preset.WRITE_LIKE, McpConstants.APPROVAL_ASK);
        assertEquals("已经是这一档的工具不该被重写第二遍", 0, plan.size());

        // 但换成"禁止"就要写——同一批工具换目标是真改动
        assertEquals(1, McpApprovalBatch.plan(items,
                McpApprovalBatch.Preset.WRITE_LIKE, McpConstants.APPROVAL_DENY).size());
    }

    @Test
    public void resetOnlyTouchesToolsWithExplicitPolicy() {
        List<McpApprovalBatch.Item> items = new ArrayList<>();
        items.add(item("a", McpConstants.APPROVAL_AUTO, McpConstants.APPROVAL_AUTO, "你在管理页设定过"));
        items.add(item("b", McpConstants.APPROVAL_AUTO, "", "无注解·未命中写操作关键词"));
        List<McpApprovalBatch.Change> plan = McpApprovalBatch.plan(items, McpApprovalBatch.Preset.ALL, "");
        assertEquals("恢复默认只该动有显式设定的那一个", 1, plan.size());
        assertEquals("a", plan.get(0).toolName);
        assertEquals(McpConstants.APPROVAL_AUTO, plan.get(0).from);
        assertEquals("", plan.get(0).to);
    }

    @Test
    public void unknownPolicyFallsBackToAsk() {
        List<McpApprovalBatch.Item> items = new ArrayList<>();
        items.add(item("a", McpConstants.APPROVAL_AUTO, "", "无注解·未命中写操作关键词"));
        List<McpApprovalBatch.Change> plan = McpApprovalBatch.plan(items,
                McpApprovalBatch.Preset.ALL, "自动放行");   // 配置被改坏/手滑的未知值
        assertEquals(1, plan.size());
        assertEquals("未知值必须按最安全的询问落，不能当自动", McpConstants.APPROVAL_ASK, plan.get(0).to);
    }

    @Test
    public void currentPolicyPresetsUseEffectiveNotExplicit() {
        List<McpApprovalBatch.Item> items = new ArrayList<>();
        items.add(item("inferred_ask", McpConstants.APPROVAL_ASK, "",
                "无注解·名称/描述像写操作（drop）"));
        items.add(item("explicit_auto", McpConstants.APPROVAL_AUTO, McpConstants.APPROVAL_AUTO,
                "你在管理页设定过"));
        assertEquals(1, McpApprovalBatch.plan(items, McpApprovalBatch.Preset.CURRENT_ASK,
                McpConstants.APPROVAL_ASK).size());
        assertEquals("explicit_auto 生效档是 auto，不该被 CURRENT_AUTO 之外…", 1,
                McpApprovalBatch.plan(items, McpApprovalBatch.Preset.CURRENT_AUTO,
                        McpConstants.APPROVAL_DENY).size());
    }

    @Test
    public void describeCountsMatchThePlan() {
        List<McpApprovalBatch.Change> plan =
                McpApprovalBatch.plan(mixed(), McpApprovalBatch.Preset.WRITE_LIKE, McpConstants.APPROVAL_ASK);
        assertTrue(McpApprovalBatch.describe(plan, 5, McpApprovalBatch.Preset.WRITE_LIKE)
                .contains("将修改 3 个工具"));
        assertTrue(McpApprovalBatch.describe(new ArrayList<>(), 5, McpApprovalBatch.Preset.WRITE_LIKE)
                .startsWith("没有要改的"));
    }

    // ==================== 接线 ====================

    @Test
    public void batchWritesTheConfigExactlyOnce() throws Exception {
        String src = read("McpManager.java");
        int start = src.indexOf("public synchronized int applyApprovalBatch");
        assertTrue("找不到批量写入方法", start > 0);
        String body = src.substring(start, src.indexOf("\n    }\n", start));
        assertEquals("整表 upsert 只能出现一次（每次保存都会重加密全部令牌）",
                1, countOccurrences(body, "store.upsert("));
        assertEquals("审计也只记一条", 1, countOccurrences(body, "AILogger.audit("));
        assertTrue("派生链要在内存里串完", body.contains("next.withToolApproval("));
    }

    @Test
    public void managerPageWiresTheBatchEntryAndReportsCount() throws Exception {
        String page = read7("ui/activity/McpManagerActivity.java");
        assertTrue("策略列表要有批量入口", page.contains("\"批量设置\""));
        assertTrue("批量必须走一次写入的接口，不能循环调单工具设定",
                page.contains("applyApprovalBatch(cfg.id, plan)"));
        assertFalse("本页不该再出现循环 setToolApproval 的批量写法",
                page.contains("for (McpApprovalBatch.Change c : plan) {\n                    McpManager"));
        assertTrue("0 改动不许报成功", page.contains("没有要改的（"));
        assertTrue("改完要刷新列表（否则用户看不到新状态）",
                page.contains("if (applied > 0) refreshList();"));
        assertTrue("范围预选要用同一份判据而不是页面里再写一遍关键词",
                page.contains("McpApprovalBatch.matches(it, preset)"));
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int i = s.indexOf(needle);
        while (i >= 0) {
            n++;
            i = s.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/oilquiz/app/ai/agent/online/mcp", name)), StandardCharsets.UTF_8);
    }

    private static String read7(String rel) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/com/oilquiz/app", rel)),
                StandardCharsets.UTF_8);
    }
}
