package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 秘塔联网搜索的**模型可见面一致性**不变量（2026-11-02）。
 *
 * <p>背景：这个能力的参数口径在仓库里散落成八处文案（注入用的 {@code ToolDefinition}、
 * 工具注解、native FC 的 {@code AgentService} 清单、本地 FC 提示词、在线提示词、使用指南、
 * 工作区速查表（两份拷贝）、引导流资产）。上一轮就是因为它没对齐出事故的：schema 里写着
 * "支持翻页（page×size≤1000）"，而真机实测带 {@code page}（连 page=0）服务端直接判
 * {@code errCode:1000 请求参数错误}，旧解析器又不看 errCode，于是"参数错误"被读成"搜到 0 条"，
 * 模型反复重搜。</p>
 *
 * <p>所以这里锁三件事：① 六个面都不许再出现 {@code page}；② scope 必须写 {@code scholar}
 * （学术键不是 paper），并且 {@code limit} 要说清 1..50 且没有翻页；③ 请求体里确实不发 page。
 * 实测契约全文见 docs/metaso-network-search-contract-2026-11-02.md。</p>
 */
public class MetasoSearchContractTest {

    private static final String TOOL_SRC =
            "src/main/java/com/oilquiz/app/ai/tool/NetworkSearchTool.java";
    private static final String MGR_SRC =
            "src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java";
    private static final String SERVICE_SRC =
            "src/main/java/com/oilquiz/app/ai/service/AgentService.java";
    private static final String ENGINE_SRC =
            "src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java";
    private static final String ONLINE_PROMPT_SRC =
            "src/main/java/com/oilquiz/app/ai/agent/online/OnlinePromptBuilder.java";
    private static final String GUIDE_SRC =
            "src/main/java/com/oilquiz/app/ai/tool/AIToolUsageGuide.java";
    private static final String WORKSPACE_SRC =
            "src/main/java/com/oilquiz/app/ai/agent/online/AgentWorkspace.java";
    private static final String GUIDE_FLOW_SRC =
            "src/main/assets/tool_guide_flow.json";

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** 取源码里从 marker 起、到下一个 ".build();" / "));" 为止的一段（按工具切块，避免全局 grep 误判）。 */
    private static String slice(String src, String marker, String terminator, String label) {
        int at = src.indexOf(marker);
        assertTrue(label + "：找不到锚点 " + marker, at >= 0);
        int end = src.indexOf(terminator, at);
        return end < 0 ? src.substring(at) : src.substring(at, end);
    }

    /** ① 注入用的权威 ToolDefinition（本地 tools schema 与 tool_registry(get) 都读它）。 */
    @Test
    public void authoritativeToolDefinitionMatchesVerifiedContract() throws Exception {
        String def = slice(read(MGR_SRC), "ToolDefinition.builder(\"network_search\"", ".build();",
                "AIToolManager.network_search");

        assertFalse("ToolDefinition 不许再声明 page：真机带 page 一律 errCode 1000",
                def.contains("addParameter(\"page\""));
        assertTrue("scope 必须写学术键 scholar（不是 paper）", def.contains("scholar"));
        assertTrue("limit 要说清上限 50", def.contains("50"));
        assertTrue("limit 要说清没有翻页", def.contains("翻页"));
        assertTrue("要 include_summary（默认带摘要，关掉模型只能编）", def.contains("include_summary"));
        assertTrue("read_url 要能按字符续读", def.contains("start_char") && def.contains("max_chars"));

        String reader = slice(read(MGR_SRC), "ToolDefinition.builder(\"webpage_reader\"", ".build();",
                "AIToolManager.webpage_reader");
        assertTrue("webpage_reader 也要暴露分段续读参数",
                reader.contains("start_char") && reader.contains("max_chars"));
    }

    /** ② 工具注解（注册表 list/search 的来源）。 */
    @Test
    public void annotationMatchesVerifiedContract() throws Exception {
        String src = read(TOOL_SRC);
        String ann = slice(src, "@Tool(", "public class NetworkSearchTool", "NetworkSearchTool 注解块");

        assertFalse("注解里不能留 page 参数（模型看得见就会去传）",
                ann.contains("@Param(name = \"page\""));
        assertTrue(ann.contains("scholar"));
        assertTrue("limit 描述要说清 1..50 与不支持翻页", ann.contains("1..50") && ann.contains("翻页"));
        assertTrue("ask/search 的选法要写进 action 描述（默认 ask、要来源才 search）",
                ann.contains("默认用 ask") && ann.contains("一批来源"));
        assertTrue("read_url 要提前说明服务端可能回错误码", ann.contains("服务错误码"));
    }

    /** ③ native FC 路径的工具清单（第三条独立通道，容易漂）。 */
    @Test
    public void nativeFcToolSchemaMatchesVerifiedContract() throws Exception {
        String src = read(SERVICE_SRC);
        String entry = slice(src, "registerToolSchema(\"network_search\"", "\");", "AgentService.network_search");

        assertFalse("native FC 清单不能出现 page 参数", entry.contains("page("));
        assertTrue(entry.contains("scholar"));
        assertTrue("limit 要写 1..50", entry.contains("1..50"));
        assertTrue("read_url 分段续读要写进来", entry.contains("start_char") && entry.contains("max_chars"));

        String reader = slice(src, "registerToolSchema(\"webpage_reader\"", "\");", "AgentService.webpage_reader");
        assertTrue("webpage_reader 清单也要有分段参数",
                reader.contains("start_char") && reader.contains("max_chars"));
    }

    /** ④ 请求体：真不发 page，且 size 按参考实现传字符串。 */
    @Test
    public void requestBodyDoesNotSendPageAndSendsSizeAsString() throws Exception {
        String src = read(TOOL_SRC);
        String body = slice(src, "private List<Map<String, String>> metasoSearch(",
                "Request request = NetworkUtil", "metasoSearch 请求构造");

        assertFalse("body 不能再 put page（服务端判参数错误）", body.contains("put(\"page\""));
        assertTrue("size 传字符串（与官方示例/社区实现一致）", body.contains("put(\"size\", String.valueOf("));
        assertTrue("includeSummary 必须带", body.contains("put(\"includeSummary\""));
    }

    /** ⑤⑥ 提示词两侧：不许再点名"page"这个已不存在的参数，但要给出唯一出口（调大 limit）。 */
    @Test
    public void promptsTeachNoPagingWithoutNamingADeletedParam() throws Exception {
        String fc = invokeFcPrompt();
        assertTrue("本地 FC 提示词要说清没有翻页", fc.contains("没有翻页"));
        assertTrue("要给出 scope 取值（含学术 scholar）", fc.contains("scholar"));
        assertTrue("要区分 ask 与 search", fc.contains("ask") && fc.contains("结论"));
        assertFalse("不能再写\"别传 page\"：schema 里已没有这个参数，点名它等于让模型改一个看不见的字段",
                fc.contains("别传 page"));

        String online = read(ONLINE_PROMPT_SRC);
        assertTrue("在线侧同口径：没有翻页", online.contains("没有翻页"));
        assertTrue("在线侧同口径：scope 学术键", online.contains("scholar"));

        String guide = read(GUIDE_SRC);
        assertTrue("使用指南同口径：没有翻页", guide.contains("没有翻页"));
        assertTrue("使用指南同口径：scope 学术键", guide.contains("scholar"));
    }

    /** ⑦⑧ 工作区速查表（两份拷贝都要改）与引导流资产（不能带 page 固定参数）。 */
    @Test
    public void workspaceGuideAndGuideFlowAssetStayAligned() throws Exception {
        String ws = read(WORKSPACE_SRC);
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (String line : ws.split("\n")) {
            if (line.contains("network_search   ")) rows.add(line);
        }
        assertTrue("工作区速查表至少有两份拷贝（改一处必改全部）：找到 " + rows.size(), rows.size() >= 2);
        for (int i = 0; i < rows.size(); i++) {
            assertTrue("第 " + (i + 1) + " 份速查表的 network_search 行没跟上口径（缺 scholar）",
                    rows.get(i).contains("scholar"));
            assertFalse("速查表不许再提 page", rows.get(i).contains("page"));
        }

        String flow = new String(Files.readAllBytes(Paths.get(GUIDE_FLOW_SRC)), StandardCharsets.UTF_8);
        assertFalse("引导流不许给 network_search 配 page 固定参数（服务端会判参数错误）",
                flow.contains("\"page\""));
    }

    /**
     * 计费用法（用户口径 2026-11-02）：秘塔**按次**扣积分（一次调用一次，与条数无关），
     * 且 ask 是默认答案通道、它本来就慢。这两件事必须写进模型看得见的每一处，
     * 否则模型的行为是"多问几次、慢了就重试"——那正是白烧积分的路径。
     */
    @Test
    public void surfacesSayAskIsDefaultAndBillingIsPerCall() throws Exception {
        String tool = read(TOOL_SRC);
        String mgr = slice(read(MGR_SRC), "ToolDefinition.builder(\"network_search\"", ".build();",
                "AIToolManager.network_search");
        String svc = slice(read(SERVICE_SRC), "registerToolSchema(\"network_search\"", "\");",
                "AgentService.network_search");
        String guide = read(GUIDE_SRC);
        String online = read(ONLINE_PROMPT_SRC);
        String fc = invokeFcPrompt();

        for (java.util.Map.Entry<String, String> e : java.util.Map.of(
                "工具注解", tool, "ToolDefinition", mgr, "AgentService 清单", svc,
                "使用指南", guide, "在线提示词", online, "本地FC提示词", fc).entrySet()) {
            String text = e.getValue();
            assertTrue(e.getKey() + "：要说清按次计费（与条数无关）",
                    text.contains("按次") && text.contains("无关"));
            assertTrue(e.getKey() + "：要说清 ask 是默认答案通道", text.contains("默认用 ask"));
            assertTrue(e.getKey() + "：要说清 ask 慢不是失败（否则模型会重试再扣一次）",
                    text.contains("慢不是失败") || text.contains("不等于失败"));
        }
    }

    /**
     * 失败判定只看服务端错误码，不看耗时（用户口径："api 有错误码，你根据错误码判断"）。
     * 锁四件事：① 三端共用的取码/翻码；② ask 自己申报长超时且 HTTP 读超时同步放宽；
     * ③ 本地引擎不再用一刀切 15 秒判工具失败；④ 自动降级只允许出现在"端点不存在(404/405)"一处。
     */
    @Test
    public void failureIsJudgedByErrorCodeNotByElapsed() throws Exception {
        String tool = read(TOOL_SRC);
        assertTrue("问答超时不能低于 180 秒（几十秒出答案是常态）",
                tool.contains("ASK_TIMEOUT_MS = 180_000L"));
        assertTrue("ask 的 HTTP 读超时要用申报值，否则底层 30 秒先掐断",
                tool.contains("clientFor(ASK_TIMEOUT_MS)"));
        assertTrue("必须实现 executionTimeoutMs 向调用方申报",
                tool.contains("public long executionTimeoutMs("));
        assertTrue("业务码判定要集中在一处（peekMetasoErrorCode）",
                tool.contains("peekMetasoErrorCode("));
        assertTrue("错误码要翻成下一步动作（1000/4000/5000 已知，未知码不编造含义）",
                tool.contains("case 1000:") && tool.contains("case 4000:") && tool.contains("case 5000:"));
        assertFalse("不许再用\"消息里含 timeout\"这类耗时线索代替错误码定性",
                tool.contains("toLowerCase().contains(\"timeout\")"));
        assertEquals("自动降级补打搜索只允许 1 处（404/405 端点不存在）",
                1, countOf(tool, "AIToolResult fb = askFallbackToSearch("));

        String engine = read(ENGINE_SRC);
        assertTrue("本地引擎必须走 ToolTimeouts（基线与工具自声明取大），不能一刀切 15 秒判失败",
                engine.contains("ToolTimeouts.effectiveMs("));
    }

    /**
     * 申报值要真的能把调用方的等待放宽（光有常量不接线等于没改）。
     * 本地引擎基线是 15 秒，ask 申报 180 秒 —— `ToolTimeouts.effectiveMs` 必须取到 180 秒，
     * 否则 ask 每次都被判"工具超时(15秒)"，模型接着重试，而秘塔按次扣积分。
     */
    @Test
    public void askTimeoutIsDeclaredAndActuallyWinsOverTheCallerBaseline() throws Exception {
        NetworkSearchTool tool = new NetworkSearchTool(null);
        java.util.Map<String, Object> askArgs = new java.util.HashMap<>();
        askArgs.put("action", "ask");
        java.util.Map<String, Object> searchArgs = new java.util.HashMap<>();
        searchArgs.put("action", "search");

        org.junit.Assert.assertEquals(180_000L, tool.executionTimeoutMs(askArgs));
        org.junit.Assert.assertEquals(60_000L, tool.executionTimeoutMs(searchArgs));
        org.junit.Assert.assertEquals(60_000L, tool.executionTimeoutMs(new java.util.HashMap<>()));

        // 与本地 AgentLoopEngine 的基线同口径：取较大者，且不超过全局硬上限
        assertEquals("ask 的等待必须真的放宽到 180 秒",
                180_000, com.oilquiz.app.ai.tool.ToolTimeouts.effectiveMs(tool, askArgs, 15_000));
        assertEquals("search/reader 走 60 秒",
                60_000, com.oilquiz.app.ai.tool.ToolTimeouts.effectiveMs(tool, searchArgs, 15_000));
    }

    private static int countOf(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    /** 引擎提示词的反射调用（与 LocalThinToolsTest 同口径：不靠扫源码文本判行为）。 */
    private static String invokeFcPrompt() throws Exception {
        Method m = com.oilquiz.app.ai.agent.software.engine.AgentLoopEngine.class
                .getDeclaredMethod("buildFcSystemPrompt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, (String) null);
    }
}
