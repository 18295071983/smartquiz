package com.oilquiz.app.ai.tool;

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
