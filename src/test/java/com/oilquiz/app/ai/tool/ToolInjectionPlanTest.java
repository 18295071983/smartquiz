package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 注入集取向（2026-11-04 用户指令）：<b>常驻核心 + 动态工具全量 + MCP 全量，不要内置全量</b>。
 *
 * <p>为什么要有这个类而不是在各处现场拼名单：在线引擎和原生桥此前各自决定"带谁"，
 * 于是同一次对话在两条路径上的能力不一样，而且内置全量（135 个）会被整段摊进每一轮。
 * 取向写在这里一次，两个调用方共用。
 */
public class ToolInjectionPlanTest {

    private static final Set<String> CORE = new LinkedHashSet<>(Arrays.asList(
            "ui_component", "workspace", "network_search", "time_date",
            "tool_registry", "knowledge_base", "douyin_downloader", "mcp_toolbox"));

    @Test
    public void builtInAreExcludedByDefault() {
        List<String> out = ToolInjectionPlan.select(CORE,
                Arrays.asList("my_weather"), Arrays.asList("mcp__amap__maps_geo"),
                Arrays.asList("database", "excel_tool", "ocr_recognize"), false);
        assertFalse("内置工具不能进本轮注入集: " + out, out.contains("database"));
        assertFalse(out.contains("excel_tool"));
        assertTrue(out.contains("my_weather"));
        assertTrue(out.contains("mcp__amap__maps_geo"));
        assertTrue("核心必须在前段（它是每轮都该看见的入口）", out.indexOf("tool_registry") < out.size());
        assertEquals(10, out.size());
    }

    @Test
    public void orderIsCoreThenDynamicThenMcp() {
        List<String> out = ToolInjectionPlan.select(CORE,
                Arrays.asList("z_tool", "a_tool"),
                Arrays.asList("mcp__s__zeta", "mcp__s__alpha"),
                Collections.<String>emptyList(), false);
        int lastCore = out.indexOf("mcp_toolbox");
        int firstDynamic = out.indexOf("a_tool");
        int firstMcp = out.indexOf("mcp__s__alpha");
        assertTrue("核心段必须在最前", lastCore < firstDynamic);
        assertTrue("段内按名排序，且 MCP 段在动态段之后", firstDynamic < firstMcp);
    }

    @Test
    public void dedupAndBlankTolerance() {
        List<String> out = ToolInjectionPlan.select(CORE,
                Arrays.asList("my_tool", " my_tool ", "", null),
                Arrays.asList("mcp__s__one", "mcp_toolbox"),   // 与核心重名
                Arrays.asList("my_tool"), true);
        assertEquals("核心 8 + my_tool + mcp__s__one = 10", 10, out.size());
        assertEquals(1, countOf(out, "my_tool"));
        assertEquals(1, countOf(out, "mcp_toolbox"));
        assertFalse("空白名不能留", out.contains(""));
    }

    @Test
    public void coreSegmentIsStableAcrossRounds() {
        // 分段顺序的代价要说清：新增一个动态工具会把后面段的下标往后推，
        // 所以"前缀稳定"只对核心段成立——这是能力优先换来的，不做二次重排去掩盖它。
        List<String> round1 = ToolInjectionPlan.select(CORE,
                Arrays.asList("aaa_tool"), Arrays.asList("mcp__s__one"), null, false);
        List<String> round2 = ToolInjectionPlan.select(CORE,
                Arrays.asList("aaa_tool", "bbb_tool"), Arrays.asList("mcp__s__one"), null, false);
        assertEquals(new java.util.ArrayList<>(CORE), new java.util.ArrayList<>(round2.subList(0, CORE.size())));
        assertTrue(round2.indexOf("aaa_tool") < round2.indexOf("bbb_tool"));
        assertTrue("MCP 段整体在动态段之后，且段内按名排序",
                round2.indexOf("bbb_tool") < round2.indexOf("mcp__s__one"));
    }

    @Test
    public void builtInIncludedOnlyWhenExplicitlyAsked() {
        List<String> out = ToolInjectionPlan.select(CORE, null, null,
                Arrays.asList("database"), true);
        assertTrue("开关打开时内置才进来", out.contains("database"));
    }

    @Test
    public void describeSeparatesSources() {
        String s = ToolInjectionPlan.describe(CORE, Arrays.asList("a"), Arrays.asList("b"),
                Arrays.asList("c", "d"), false);
        assertTrue(s, s.contains("常驻=8"));
        assertTrue(s, s.contains("动态=1"));
        assertTrue(s, s.contains("MCP=1"));
        assertTrue("不注入内置时要把可用数量也报出来，方便对账", s.contains("不注入(2)"));
    }

    // ==================== 接线 ====================

    @Test
    public void engineInjectsDynamicAndAllMcp() throws Exception {
        String engine = read("ai/agent/online/OnlineAgentEngine.java");
        assertTrue("在线注入集必须走 ToolInjectionPlan 这个唯一口径",
                engine.contains("ToolInjectionPlan.select("));
        assertTrue("动态工具要全量注入",
                engine.contains("getDynamicTools()"));
        assertTrue("MCP 要全量注入（每条先 ensureRegistered 拿可执行的本地名）",
                engine.contains("injectAllMcpTools()") && engine.contains("mgr.ensureRegistered(e)"));
        assertTrue("审批=禁止 的工具不能摆给模型看",
                engine.contains("McpConstants.APPROVAL_DENY"));
        assertTrue("注册不出来的数量要出声，不能静默少注入",
                engine.contains("unresolvable"));
    }

    @Test
    public void namedAssemblyDoesNotSilentlyDropTools() throws Exception {
        String mgr = read("ai/tool/AIToolManager.java");
        assertTrue("按名单装配要有独立入口（否则调用方只能退回内置全量）",
                mgr.contains("public String getOpenAIToolDefinitionsForNames("));
        int start = mgr.indexOf("public String getOpenAIToolDefinitionsForNames(");
        String body = mgr.substring(start, start + 2200);
        assertTrue("装配不出来的名字必须记下来（\"说要注入其实没注入\"最难查）",
                body.contains("missing.add(toolName)") && body.contains("装配不出定义"));
        assertTrue("内置名单要从同一个类取，不能在调用方抄数字",
                mgr.contains("public List<String> getBuiltInToolNames()"));
    }

    @Test
    public void registryDoesNotSilentlySkipNames() throws Exception {
        String reg = read("ai/agent/online/OnlineToolRegistry.java");
        int start = reg.indexOf("public String getToolDefinitionsForNames(");
        assertTrue("找不到按名装配入口", start > 0);
        String body = reg.substring(start, start + 1600);
        assertTrue("未注册/停用不能 continue 掉就算完——要出声",
                body.contains("skipped.add(name +") && body.contains("装配不出定义"));
    }

    private static int countOf(List<String> list, String v) {
        int n = 0;
        for (String s : list) if (v.equals(s)) n++;
        return n;
    }

    private static String read(String rel) throws Exception {
        java.nio.file.Path p = Paths.get("src/main/java/com/oilquiz/app").resolve(rel);
        assertTrue("找不到源文件 " + rel, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
