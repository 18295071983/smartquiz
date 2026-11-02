package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 按次计费与"工具发现通道"的引擎侧不变量（2026-11-02）。
 *
 * <p>用户口径：秘塔<b>按次</b>扣积分（一次 3 分，与条数无关），ask 慢是常态、不要把慢当失败。
 * 这两条在执行引擎里的落地方式是"**不要自动替用户多花一次钱**"：
 * ① 超时/异常不自动重试（三处执行路径都问 {@link ToolSideEffects}，别再各写一份判据）；
 * ② 经 {@code tool_registry(call)} 代执行时，等待时长要继承被代执行工具的申报，
 *    否则 ask 的 180 秒在发现通道上退化成基线 15 秒 —— 一样是"把慢判成失败"。</p>
 */
public class MetasoBillingAndDiscoveryTest {

    /** 取源码里从 marker 起、到 terminator 为止的一段（按工具切块，避免全局 grep 误判）。 */
    private static String slice(String src, String marker, String terminator, String label) {
        int at = src.indexOf(marker);
        assertTrue(label + "：找不到锚点 " + marker, at >= 0);
        int end = src.indexOf(terminator, at);
        return end < 0 ? src.substring(at) : src.substring(at, end);
    }

    /** 反射调本地 FC 提示词构造器：判"模型实际收到什么"，不靠扫源码文本。 */
    private static String invokeFcPrompt() throws Exception {
        java.lang.reflect.Method m = com.oilquiz.app.ai.agent.software.engine.AgentLoopEngine.class
                .getDeclaredMethod("buildFcSystemPrompt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, (String) null);
    }

    /** 失败结果可能带 Throwable 前缀，取纯文本再判。 */
    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** 计费用语：按次付费工具可缓存（缓存才是省钱），但不自动重试。 */
    @Test
    public void billedPerCallToolsAreNeverAutoRetried() {
        assertTrue("network_search 按次计费", ToolSideEffects.isBilledPerCall("network_search"));
        assertTrue("smart_research 内部也是秘塔调用，同样按次", ToolSideEffects.isBilledPerCall("smart_research"));
        assertFalse("免费只读工具仍可重试", ToolSideEffects.isBilledPerCall("ai_weather"));

        assertFalse("按次付费超时不许自动重跑", ToolSideEffects.mayRetryOnTimeout("network_search"));
        assertFalse(ToolSideEffects.mayRetryOnTimeout("smart_research"));
        assertTrue("只读且免费的可以重试", ToolSideEffects.mayRetryOnTimeout("ai_weather"));
        assertFalse("有副作用的本来就不行", ToolSideEffects.mayRetryOnTimeout("database"));
        assertTrue("network_search 仍是只读（可以缓存，缓存是在省钱）",
                ToolSideEffects.isReadOnlyIdempotent("network_search"));
    }

    /** 三条执行路径都要问同一个判据，不许再各写一份（写死工具名或"一律重试"都会漂）。 */
    @Test
    public void allThreeExecutionPathsConsultTheBilledJudge() throws Exception {
        String online = read("src/main/java/com/oilquiz/app/ai/agent/online/OnlineToolManager.java");
        assertTrue("在线 Agent 的超时分支必须查 isBilledPerCall（原来 MAX_RETRY=2 会连扣三次）",
                online.contains("ToolSideEffects.isBilledPerCall(toolName)"));

        String service = read("src/main/java/com/oilquiz/app/ai/service/AgentService.java");
        assertTrue("对话页 native FC 同样", service.contains("ToolSideEffects.isBilledPerCall(realToolName)"));

        String local = read("src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java");
        assertTrue("本地 Agent 的同参重跑改用统一判据，不再写死单个工具名",
                local.contains("ToolSideEffects.mayRetryOnTimeout(toolName)"));
        assertFalse("本地不该再有写死名字的旧判据",
                local.contains("!\"network_search\".equals(toolName)"));
    }

    /** 本地引擎要真的按申报值等（15 秒一刀切就是"把慢当失败"的那条路径）。 */
    @Test
    public void localEngineWaitsByDeclaredTimeout() throws Exception {
        String local = read("src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java");
        assertTrue("等待时长走 ToolTimeouts（基线与申报取大）",
                local.contains("ToolTimeouts.effectiveMs("));
        assertTrue("并行批次的截止也按各调用取最大值", local.contains("toolWaitMs(tc0.toolName"));
    }

    /** 发现通道：tool_registry(call) 继承被代执行工具的申报超时。 */
    @Test
    public void registryCallInheritsInnerToolTimeout() throws Exception {
        String reg = read("src/main/java/com/oilquiz/app/ai/tool/ToolRegistryTool.java");
        assertTrue("ToolRegistryTool 必须自己申报超时",
                reg.contains("public long executionTimeoutMs("));
        assertTrue("call 档要取内层工具的申报值", reg.contains("inner.executionTimeoutMs(innerArgs)"));
        assertTrue("args 两种写法都要能解析（Map 与 JSON 字符串）", reg.contains("parseCallArgs("));
    }

    /**
     * 发现通道的描述不再裁剪（用户口径：api 返回的数据不要截断）。
     * 以前 list/search/get 分别切 40/60/160/400 字，正好把"默认用 ask"、"按次计费"切成半句，
     * 模型在发现通道里读到残句只能靠猜。
     */
    @Test
    public void registryNeverClipsDescriptions() throws Exception {
        String reg = read("src/main/java/com/oilquiz/app/ai/tool/ToolRegistryTool.java");
        assertFalse("发现通道不该再按字数裁剪描述", reg.contains("clipAtSentence("));
        assertFalse("不该再用 substring 截描述",
                reg.contains("desc.substring(0,") || reg.contains("s.substring(0,")
                        || reg.contains("tdesc.substring(0,"));
        // 注释里可以留历史记录，但不能再有这个**写入**（标记本身就是裁过的证据）
        assertFalse("description_truncated 标记应随裁剪一起下线",
                reg.contains("put(\"description_truncated\""));
        assertTrue("要留下不裁的口径说明", reg.contains("NO-CLIP"));
    }

    /**
     * 引擎侧同样不许裁 API 数据（用户口径：返回的数据条数由模型自己在 limit 里设）。
     * 条数由 limit（1..50）决定，不是由引擎代砍；超预算只许说明，不许静默截断。
     */
    @Test
    public void engineNeverClipsToolResultData() throws Exception {
        String engine = read("src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java");
        assertFalse("不该再按结构裁剪工具结果（数组前 N 条 / 单字段 400 字）",
                engine.contains("shrinkForModel("));
        assertFalse("裁剪用的常量应随之下线",
                engine.contains("MAX_RESULT_ARRAY_ITEMS")
                        || engine.contains("MAX_RESULT_STRING_CHARS"));
        // 只锁"把 API 数据拦腰截"的那两处：总长兜底 substring 与字符串字段 clipString。
        // （read_file 的分页合并另说：它带 start_line 续读指引，是显式可续读，不是静默截断。）
        assertFalse("不许再用总长兜底截工具结果",
                engine.contains("out.substring(0, MAX_TOOL_RESULT_LENGTH)"));
        assertFalse("不许再把结果字符串 clip 到总长上限",
                engine.contains("clipString((String) data, MAX_TOOL_RESULT_LENGTH)"));
        assertTrue("结果很大时只标注 size_chars，不动数据", engine.contains("size_chars"));
        assertTrue("装不下要指名是谁撑爆的（超了就说）",
                engine.contains("biggestToolResultChars") && engine.contains("OVERSIZE-SAY"));
    }

    /** 第三处独立文案（AgentToolView / AIToolCreatorTool 会读出来当"工具说明"）不能还停在旧契约。 */
    @Test
    public void toolParameterDescriptionMapMatchesCurrentContract() {
        NetworkSearchTool tool = new NetworkSearchTool(null);
        Map<String, String> d = tool.getParameterDescriptions();

        assertFalse("get_weather 这档早就删了（天气工具叫 ai_weather），不能再出现在说明表里",
                d.get("action").contains("get_weather"));
        assertFalse("location 参数不在本工具里", d.containsKey("location"));
        for (String key : new String[]{"question", "scope", "include_summary", "start_char", "max_chars"}) {
            assertTrue("参数说明表缺 " + key + "（下发 schema 里有它，两处口径必须一致）", d.containsKey(key));
        }
        assertTrue("action 说明要把 ask 摆成默认", d.get("action").contains("默认"));
        assertTrue("limit 说明要带按次计费口径", d.get("limit").contains("按次"));
        assertTrue("scope 要写学术键 scholar", d.get("scope").contains("scholar"));
        assertFalse("limit 不能教模型翻页", d.get("limit").contains("page"));
    }

    /**
     * 手机实测（用户让模型"把每个 action 都试一遍"）抓到：{@code smart_read}/{@code summarize}
     * 把上一步 search 的 {@code results} 原样贴回来却被判"缺 results"。
     * 根因是解析只认 Java List。{@link NetworkSearchTool#parseResultsArg} 现在三种写法都收。
     */
    @Test
    public void resultsParameterAcceptsAllThreeShapesTheModelActuallySends() throws Exception {
        java.util.List<java.util.Map<String, Object>> asList = new java.util.ArrayList<>();
        java.util.Map<String, Object> one = new java.util.HashMap<>();
        one.put("title", "A");
        one.put("url", "https://a");
        one.put("snippet", "s");
        one.put("date", 2026);          // 非字符串值也不能把整条摘要打爆（旧裸强转 + (String) 取值会 CCE）
        asList.add(one);

        assertEquals("真数组", 1, NetworkSearchTool.parseResultsArg(asList).size());
        assertEquals("数组的 JSON 字符串", 1,
                NetworkSearchTool.parseResultsArg("[{\"title\":\"A\",\"url\":\"https://a\"}]").size());
        assertEquals("整段搜索返回（外层带 results）", 1,
                NetworkSearchTool.parseResultsArg("{\"query\":\"q\",\"count\":1,\"results\":[{\"title\":\"A\"}]}").size());
        assertEquals("org.json 数组", 2,
                NetworkSearchTool.parseResultsArg(
                        new org.json.JSONArray("[{\"title\":\"A\"},{\"title\":\"B\"}]")).size());
        assertTrue("垃圾输入不抛异常，按空处理（由调用方报缺参）",
                NetworkSearchTool.parseResultsArg("not json at all").isEmpty());
        assertTrue("null 按空处理", NetworkSearchTool.parseResultsArg(null).isEmpty());
        // 数字值要能转成文本，不能因为 (String) 强转失败而让整条结果报错
        assertEquals("2026", String.valueOf(NetworkSearchTool.parseResultsArg(asList).get(0).get("date")));
    }

    /**
     * 缺参文案不许再指错路：实测 {@code summarize} 被要求补 query，而它真正要的是 results/url
     * —— 模型照做就永远失败（用户那轮"四连失败"）。
     */
    @Test
    public void missingParameterHintsNameTheRealRequiredKey() throws Exception {
        java.lang.reflect.Method m = NetworkSearchTool.class.getDeclaredMethod(
                "missingParam", String.class, Map.class);
        m.setAccessible(true);

        String sum = text(((AIToolResult) m.invoke(null, "summarize", new java.util.HashMap<String, Object>())).getErrorMessage());
        assertTrue("summarize 要说真实必填项 results（并允许 url），不能再让它补 query",
                sum.contains("results") && sum.contains("url"));
        assertFalse("不能再把 summarize 指向 query 作为必填", sum.contains("summarize 需要 query"));

        String sr = text(((AIToolResult) m.invoke(null, "smart_read", new java.util.HashMap<String, Object>())).getErrorMessage());
        assertTrue("smart_read 要说明 results 也可以是 JSON 字符串", sr.contains("results") && sr.contains("JSON"));

        String ask = text(((AIToolResult) m.invoke(null, "ask", new java.util.HashMap<String, Object>())).getErrorMessage());
        assertTrue("ask 要 question", ask.contains("question"));

        String ru = text(((AIToolResult) m.invoke(null, "read_url", new java.util.HashMap<String, Object>())).getErrorMessage());
        assertTrue("read_url 要 url 且提醒别写进 query", ru.contains("url") && ru.contains("query"));

        // 本地拦下的缺参一次请求都没发出去 → 必须说明不扣额度，否则模型不敢改正重试
        for (String a : new String[]{"summarize", "smart_read", "ask", "read_url", "search"}) {
            String t = text(((AIToolResult) m.invoke(null, a, new java.util.HashMap<String, Object>())).getErrorMessage());
            assertTrue(a + " 的缺参文案要写明没发请求、不扣额度", t.contains("不扣额度"));
        }
    }

    /** 扣额度边界要写在模型看得见的每一处（否则它会怕花钱而不敢用本地免费动作，或白烧 read_url）。 */
    @Test
    public void billingBoundaryIsStatedOnEveryModelFacingSurface() throws Exception {
        String tool = read("src/main/java/com/oilquiz/app/ai/tool/NetworkSearchTool.java");
        String mgr = slice(read("src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java"),
                "ToolDefinition.builder(\"network_search\"", ".build();", "AIToolManager.network_search");
        String svc = slice(read("src/main/java/com/oilquiz/app/ai/service/AgentService.java"),
                "registerToolSchema(\"network_search\"", "\");", "AgentService.network_search");
        String guide = read("src/main/java/com/oilquiz/app/ai/tool/AIToolUsageGuide.java");
        String online = read("src/main/java/com/oilquiz/app/ai/agent/online/OnlinePromptBuilder.java");
        String fc = invokeFcPrompt();

        for (java.util.Map.Entry<String, String> e : java.util.Map.of(
                "注解+参数表", tool, "ToolDefinition", mgr, "AgentService 清单", svc,
                "使用指南", guide, "在线提示词", online, "本地FC提示词", fc).entrySet()) {
            assertTrue(e.getKey() + "：要区分哪些 action 扣额度",
                    e.getValue().contains("不扣额度") || e.getValue().contains("不花钱"));
            assertTrue(e.getKey() + "：要列出本地免费的抓网页类 action",
                    e.getValue().contains("get_webpage") || e.getValue().contains("抓网页"));
        }
        assertTrue("search_and_read 要说清只搜索那一步扣费",
                tool.contains("只搜索那一步扣") || tool.contains("不再额外扣"));
        assertTrue("extract_info 不能再冒充语义抽取",
                tool.contains("不是语义问答") || tool.contains("元数据"));
    }

    /**
     * 对话页 native FC 这条路径（本地模型走它）以前把工具结果裁成 400 字符（天气类 1500），
     * 列表还只给前 10 条 —— 台账/表格类数据进去 400 字就只剩表头，模型没见过后面的行只能编；
     * 秘塔按次买回来的第 11 条也永远进不了它眼里。现在与本地 Agent 引擎同口径：不裁，
     * 超预算由 ChatContextBuilder 挤更早的历史兜底。
     */
    @Test
    public void nativeFcPathNoLongerTruncatesToolResults() throws Exception {
        String svc = read("src/main/java/com/oilquiz/app/ai/service/AgentService.java");
        assertFalse("不许再按 400/1500 截字符串", svc.contains("TOOL_CONTENT_PREVIEW_LENGTH")
                || svc.contains("WEATHER_CONTENT_PREVIEW_LENGTH"));
        assertFalse("不许再出现'内容过长，已截断'", svc.contains("内容过长，已截断"));
        assertFalse("列表不许只给前 10 条", svc.contains("Math.min(list.size(), 10)"));
        assertFalse("不许再写'... 还有 N'这种丢条目的尾巴", svc.contains("... 还有 "));
        assertTrue("要留下不裁的口径说明", svc.contains("NO-CUT(2026-11-02)"));
        // 同一文件里那族"工具结果智能摘要"是死代码（summarizeToolResult 全仓零调用），
        // 行为比活的 400 截断更狠（按行过滤 + 超 1000 字 break），已整族删除。
        for (String dead : new String[]{"summarizeToolResult", "formatSearchResult",
                "formatWeatherResult", "formatDatabaseResult", "smartTruncate",
                "TOOL_RESULT_MAX_LENGTH", "TOOL_RESULT_SUMMARY_THRESHOLD"}) {
            assertFalse("死代码族应已整体删除: " + dead, svc.contains(dead));
        }
    }

    /** 申报值本身（回归锁：ask 一定要比 search 长得多）。 */
    @Test
    public void askDeclaresMuchLongerTimeoutThanSearch() {
        NetworkSearchTool tool = new NetworkSearchTool(null);
        Map<String, Object> ask = new HashMap<>();
        ask.put("action", "ask");
        Map<String, Object> search = new HashMap<>();
        search.put("action", "search");
        long askMs = tool.executionTimeoutMs(ask);
        long searchMs = tool.executionTimeoutMs(search);
        assertTrue("ask 至少要有 120 秒（实测几十秒起步）", askMs >= 120_000L);
        assertTrue("ask 要比 search 宽得多", askMs >= searchMs * 2);
    }
}
