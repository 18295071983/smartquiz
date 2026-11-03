package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.oilquiz.app.ai.agent.online.mcp.McpConstants;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mcp_toolbox 的对外契约（模型看得到的 schema 就是这里）。
 *
 * <p>这层单测只钉“入口形状”，不连网：动作必须齐、参数必须能被模型填、
 * call 的申报超时必须大于普通工具基线。真正的 MCP 协议行为由 McpClient/McpManager
 * 的代码路径负责。</p>
 */
public class McpToolboxToolContractTest {

    @Test
    public void toolAnnotationDeclaresSearchGetCall() {
        Tool tool = McpToolboxTool.class.getAnnotation(Tool.class);
        assertNotNull("mcp_toolbox 缺 @Tool 注解，模型看不到它", tool);
        assertEquals("mcp_toolbox", tool.value());
        assertTrue("描述要说明“内置工具做不了时用”，否则模型不会想到搜外部服务",
                tool.description().contains("内置工具"));

        Map<String, Action> actions = new LinkedHashMap<>();
        for (Action a : tool.actions()) actions.put(a.name(), a);
        assertTrue(actions.containsKey("search"));
        assertTrue(actions.containsKey("get"));
        assertTrue(actions.containsKey("call"));
    }

    @Test
    public void toolAnnotationParamsMatchImplementation() {
        Tool tool = McpToolboxTool.class.getAnnotation(Tool.class);
        assertNotNull(tool);
        Map<String, Param> params = new LinkedHashMap<>();
        for (Param p : tool.params()) params.put(p.name(), p);
        assertTrue("action 必填", params.containsKey("action") && params.get("action").required());
        assertTrue(params.containsKey("keyword"));
        assertTrue(params.containsKey("server"));
        assertTrue(params.containsKey("tool"));
        assertTrue(params.containsKey("args"));
        assertEquals("integer", params.get("limit").type());
    }

    @Test
    public void callDeclaresLongerTimeoutThanSearch() {
        McpToolboxTool tool = new McpToolboxTool(mock(Context.class));
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("action", "search");
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("action", "call");
        assertEquals(15_000L, tool.executionTimeoutMs(search));
        assertTrue("call 要等远端 tools/call，不能按普通工具 30s 处死",
                tool.executionTimeoutMs(call) > 30_000L);
        assertEquals(0L, tool.executionTimeoutMs(null));
    }

    @Test
    public void registeredInAIToolManagerAndOnlineCore() throws Exception {
        Context ctx = mock(Context.class);
        when(ctx.getApplicationContext()).thenReturn(ctx);
        // 不碰全局单例：全量测试里别的用例可能已用“getApplicationContext()=null”的 mock 初始化过它
        java.lang.reflect.Constructor<AIToolManager> ctor =
                AIToolManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        AIToolManager mgr = ctor.newInstance(ctx);

        com.oilquiz.app.ai.tool.openai.ToolDefinition def =
                mgr.getToolDefinition("mcp_toolbox");
        assertNotNull("mcp_toolbox 未注册进 AIToolManager，tool_registry 与本地链路都发现不了", def);
        assertEquals("mcp_toolbox", def.getName());
        assertNotNull(def.getParameters());
        assertTrue(def.getParameters().size() >= 5);
        com.oilquiz.app.ai.tool.openai.ParamDefinition actionParam = null;
        for (com.oilquiz.app.ai.tool.openai.ParamDefinition pd : def.getParameters()) {
            if ("action".equals(pd.getName())) actionParam = pd;
        }
        assertNotNull("模型必须看到 action 参数", actionParam);
        assertNotNull("action 必须有枚举，否则模型会自创动作", actionParam.getEnumValues());
        assertTrue(actionParam.getEnumValues().containsAll(
                java.util.Arrays.asList("search", "get", "call")));
        assertTrue("必须进在线核心集，否则模型不会主动想到搜外部服务",
                com.oilquiz.app.ai.agent.online.OnlineAgentEngine.coreTools().contains("mcp_toolbox"));
    }

    @Test
    public void remoteToolNameBudgetIsEnforced() {
        assertEquals(64, McpConstants.MAX_TOOL_NAME_LENGTH);
    }
}
