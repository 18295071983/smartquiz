package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * McpManager 的两个“外部输入出口”不变量：
 * ① 远端工具名是不可信输入，必须变成合法 function 名（否则服务商拒收/截断撞名）；
 * ② 模型传的嵌套 Map/List 必须转成真正的 JSON，不能 String.valueOf 成 "{a=b}"。
 */
public class McpManagerInternalsTest {

    @Test
    public void sanitizeNameKeepsAllowedCharsAndCollapsesIllegalRuns() throws Exception {
        Method m = McpManager.class.getDeclaredMethod("sanitizeName", String.class);
        m.setAccessible(true);
        assertEquals("foo_bar_baz", m.invoke(null, "foo.bar/baz"));
        assertEquals("a--b", m.invoke(null, "__a--b__"));
        assertEquals("", m.invoke(null, "中文"));
        assertEquals("tool_2", m.invoke(null, "tool 2"));
    }

    @Test
    public void nestedArgsBecomeRealJson() throws Exception {
        Method m = McpManager.class.getDeclaredMethod("toJsonValue", Object.class);
        m.setAccessible(true);

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("name", "x");
        nested.put("tags", Arrays.asList("a", "b"));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("deep", 1);
        nested.put("inner", inner);

        Object out = m.invoke(null, nested);
        assertTrue(out instanceof JSONObject);
        JSONObject o = (JSONObject) out;
        assertEquals("x", o.getString("name"));
        JSONArray tags = o.getJSONArray("tags");
        assertEquals(2, tags.length());
        assertEquals(1, o.getJSONObject("inner").getInt("deep"));
    }

    /**
     * 默认审批策略：<b>注解优先，缺注解按名称/描述判断</b>。
     *
     * <p>2026-11-04 改的口径。原来是"没有注解就询问"，看着保守，实际经不起服务商现实：
     * 高德 MCP 的 15 个工具一个 annotations 都不发（当天 tools/list 复核），
     * 于是 {@code maps_weather}、{@code maps_geo} 这种纯查询每次调用都要用户点同意，
     * 真机上一轮对话连点 6 次，最后被迫逐个设成"始终允许"——那种点法既没读风险说明，
     * 也让审批形同虚设。现在只有名字/描述命中写操作词表才问。</p>
     */
    @Test
    public void approvalDefaultsFollowAnnotationsThenHeuristic() throws Exception {
        // 服务商自报的注解永远优先
        assertEquals(McpConstants.APPROVAL_AUTO, McpManager.inferApproval(
                new JSONObject().put("readOnlyHint", true), "anything", ""));
        assertEquals(McpConstants.APPROVAL_ASK, McpManager.inferApproval(
                new JSONObject().put("destructiveHint", true), "anything", ""));
        assertEquals(McpConstants.APPROVAL_ASK, McpManager.inferApproval(
                new JSONObject().put("readOnlyHint", false), "anything", ""));

        // 缺注解：只读形状的查询自动放行
        assertEquals("查询类工具不该再拦", McpConstants.APPROVAL_AUTO,
                McpManager.inferApproval(null, "maps_geo", "把一个地点地址转换成经纬度"));
        assertEquals(McpConstants.APPROVAL_AUTO,
                McpManager.inferApproval(null, "maps_weather", "查询城市天气"));
        assertEquals(McpConstants.APPROVAL_AUTO,
                McpManager.inferApproval(null, "maps_direction_driving", "驾车路径规划"));

        // 缺注解：名字或描述像写操作才问
        assertEquals(McpConstants.APPROVAL_ASK,
                McpManager.inferApproval(null, "delete_record", "查询台账"));
        assertEquals(McpConstants.APPROVAL_ASK,
                McpManager.inferApproval(null, "send_mail", ""));
        assertEquals("描述里的中文动作也要能挡", McpConstants.APPROVAL_ASK,
                McpManager.inferApproval(null, "ledger_tool", "把数据写入台账并更新余额"));
        assertEquals(McpConstants.APPROVAL_ASK,
                McpManager.inferApproval(new JSONObject(), "drop_table", ""));
    }

    /** 判定依据必须能说出来——策略不可解释时，用户只会一路点最宽的那个按钮。 */
    @Test
    public void approvalBasisExplainsWhichRuleFired() throws Exception {
        assertEquals("服务商声明只读", McpManager.approvalBasis(
                new JSONObject().put("readOnlyHint", true), "maps_geo", ""));
        assertEquals("服务商声明可破坏", McpManager.approvalBasis(
                new JSONObject().put("destructiveHint", true), "anything", ""));
        String readOnlyBasis = McpManager.approvalBasis(null, "maps_geo", "地理编码");
        assertTrue(readOnlyBasis + " 应说明是无注解", readOnlyBasis.startsWith("无注解"));
        String writeBasis = McpManager.approvalBasis(null, "delete_record", "");
        assertTrue(writeBasis + " 应点名命中的词", writeBasis.contains("delete"));
    }
}
