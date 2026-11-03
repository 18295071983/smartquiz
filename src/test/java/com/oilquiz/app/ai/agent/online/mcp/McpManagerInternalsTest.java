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

    @Test
    public void approvalDefaultsFollowAnnotations() throws Exception {
        assertEquals(McpConstants.APPROVAL_AUTO, McpManager.inferApproval(
                new JSONObject().put("readOnlyHint", true)));
        assertEquals(McpConstants.APPROVAL_ASK, McpManager.inferApproval(
                new JSONObject().put("destructiveHint", true)));
        assertEquals(McpConstants.APPROVAL_ASK, McpManager.inferApproval(
                new JSONObject().put("readOnlyHint", false)));
        assertEquals("没有注解不能假设只读", McpConstants.APPROVAL_ASK,
                McpManager.inferApproval(new JSONObject()));
    }
}
