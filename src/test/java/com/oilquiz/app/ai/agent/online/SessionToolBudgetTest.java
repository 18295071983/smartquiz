package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话工具集预算（能力优先取向）的行为与接线测试。
 *
 * <p>盯三件事：
 * <ol>
 *   <li><b>已给模型看过的定义不许被收回</b>——旧实现按数量 9 + LRU 淘汰，
 *       淘汰过的工具下一轮"工具不存在"，那是把体积问题换成正确性问题；</li>
 *   <li>上限只当<b>护栏</b>（防异常服务一次推上千工具），不是节流阀——
 *       所以有"下限"断言，防止有人悄悄把它调小回到旧行为；</li>
 *   <li>撞到护栏必须<b>把话说清</b>：模型看到的文本里要写"没并进来"+"还能怎么走"。</li>
 * </ol>
 */
public class SessionToolBudgetTest {

    private static Map<String, Integer> bytes(int each) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < 1000; i++) m.put("t" + i, each);
        return m;
    }

    private static final Set<String> CORE = new LinkedHashSet<>(
            Arrays.asList("ui_component", "workspace", "network_search", "time_date",
                    "tool_registry", "knowledge_base", "douyin_downloader", "mcp_toolbox"));

    // ==================== 取向：能力优先 ====================

    @Test
    public void capsAreGuardrailsNotThrottles() {
        assertTrue("预算被调小＝回到\"模型发现过的工具用着用着就没了\"的旧行为；"
                        + "用户已明确能力优先（2026-11-04），要改必须一起改这里的说明",
                SessionToolBudget.MAX_TOOLSET_BYTES >= 300_000);
        assertTrue("数量硬顶至少要容纳真实服务的完整目录", SessionToolBudget.MAX_TOOLSET_TOOLS >= 400);
    }

    @Test
    public void realSizedToolsetsFitWithoutRefusal() {
        // 内置全量(约 135) + 一个 MCP 服务(15) 这种真实规模必须全数准入，不该走到拒绝分支
        List<String> current = new ArrayList<>();
        Map<String, Integer> per = new HashMap<>();
        for (int i = 0; i < 150; i++) {
            String n = "tool_" + i;
            current.add(n);
            per.put(n, 600);          // 单个 schema ~600 字节，贴近真实
        }
        SessionToolBudget.Verdict v = SessionToolBudget.admit(
                current, CORE, per, "mcp__amap__maps_geo", 900);
        assertTrue("150 个工具之后再并 1 个不该被拒: " + v.reason, v.admitted);
        assertNull(v.modelHint);
        assertEquals(151, v.toolCount);
    }

    // ==================== 只追加、不收回 ====================

    @Test
    public void alreadyAdmittedToolIsNeverRefused() {
        List<String> current = new ArrayList<>();
        Map<String, Integer> per = bytes(400);
        for (int i = 0; i < 900; i++) current.add("t" + i);   // 远超预算的一坨
        SessionToolBudget.Verdict v = SessionToolBudget.admit(
                current, CORE, per, "t5", 400);
        assertTrue("已在集内的工具重取定义时不能因为超预算而被逐出", v.admitted);
    }

    @Test
    public void coreToolsAreExemptFromBothCaps() {
        Map<String, Integer> per = bytes(200_000);
        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 700; i++) huge.add("t" + i);
        SessionToolBudget.Verdict v = SessionToolBudget.admit(huge, CORE, per, "mcp_toolbox", 1200);
        assertTrue("核心集（含 tool_registry/mcp_toolbox 这些发现入口）挤不动：" + v.reason, v.admitted);
    }

    @Test
    public void trimDropsOnlyTheTailAndKeepsCore() {
        Map<String, Integer> per = new HashMap<>();
        List<String> ordered = new ArrayList<>();
        ordered.add("mcp_toolbox");                       // 核心，且在队头
        int each = SessionToolBudget.MAX_TOOLSET_BYTES / 8;
        for (int i = 0; i < 30; i++) {
            String n = "late_" + i;
            ordered.add(n);
            per.put(n, each);
        }
        per.put("mcp_toolbox", each);
        List<String> kept = SessionToolBudget.trimToFit(ordered, CORE, per);
        assertTrue("核心不能被动", kept.contains("mcp_toolbox"));
        assertTrue("队头先到的能力要留下", kept.contains("late_0"));
        assertFalse("被丢的只能是队尾", kept.contains("late_29"));
        assertTrue("留下的体积必须回到预算内",
                SessionToolBudget.totalBytes(kept, per) <= SessionToolBudget.MAX_TOOLSET_BYTES);
        // 丢的必须正好是"最后发现的那几个"——按发现顺序从尾部数
        int keptLate = 0;
        for (String n : kept) if (n.startsWith("late_")) keptLate++;
        for (int i = keptLate; i < 30; i++) {
            assertFalse("第 " + i + " 个（队尾段）应该被丢", kept.contains("late_" + i));
        }
        for (int i = 0; i < keptLate; i++) {
            assertTrue("第 " + i + " 个（队头段）不该被动", kept.contains("late_" + i));
        }
    }

    @Test
    public void trimIsNoopWhenWithinBudget() {
        Map<String, Integer> per = bytes(100);
        List<String> ordered = new ArrayList<>(Arrays.asList("t0", "t1", "t2"));
        assertEquals(ordered, SessionToolBudget.trimToFit(ordered, CORE, per));
    }

    // ==================== 撞护栏时怎么说 ====================

    @Test
    public void refusalCarriesModelVisibleWayOut() {
        Map<String, Integer> per = bytes(SessionToolBudget.MAX_TOOLSET_BYTES);  // 一个工具就吃满
        List<String> current = new ArrayList<>(Collections.singletonList("t0"));
        SessionToolBudget.Verdict v = SessionToolBudget.admit(current, CORE, per, "mcp__big", 1000);
        assertFalse(v.admitted);
        assertNotNull("拒绝必须给模型一句话", v.modelHint);
        assertTrue("要给出路：MCP 可以代执行，不并定义也能用",
                v.modelHint.contains("mcp_toolbox(action=call"));
        assertTrue("要给出路：动态工具可以删掉腾位置", v.modelHint.contains("create_dynamic_tool(action=delete"));
        assertTrue("要说清这个工具没并进来（否则模型会直接调用它）", v.modelHint.contains("没有并入"));
    }

    @Test
    public void countCapAlsoRefusesWithAHint() {
        Map<String, Integer> per = bytes(1);
        List<String> current = new ArrayList<>();
        for (int i = 0; i < SessionToolBudget.MAX_TOOLSET_TOOLS; i++) current.add("t" + i);
        SessionToolBudget.Verdict v = SessionToolBudget.admit(current, CORE, per, "one_more", 1);
        assertFalse(v.admitted);
        assertTrue(v.reason, v.reason.contains("数量到硬顶"));
    }

    @Test
    public void describeReportsBothCounts() {
        Map<String, Integer> per = bytes(1000);
        String s = SessionToolBudget.describe(Arrays.asList("t0", "t1"), per);
        assertTrue(s, s.contains("tools=2 个"));
        assertTrue(s, s.contains("2000/" + SessionToolBudget.MAX_TOOLSET_BYTES));
    }

    // ==================== 接线：引擎真的按这套走 ====================

    @Test
    public void engineNoLongerEvictsByLru() throws Exception {
        String src = read("OnlineAgentEngine.java");
        int start = src.indexOf("private void trimSessionTools(");
        assertTrue("找不到体积收口方法", start > 0);
        String body = src.substring(start, src.indexOf("\n    }\n", start));
        assertFalse("不能再按 LRU 淘汰（toolLastUsed 排序丢最旧）——那会撤掉模型已经看见的定义",
                body.contains("removable.sort(") || body.contains("evicted"));
        assertTrue("必须走 SessionToolBudget 的队尾收口，保持单一口径",
                body.contains("SessionToolBudget.trimToFit("));
    }

    @Test
    public void refusalReachesTheModelNotJustTheLog() throws Exception {
        String src = read("OnlineAgentEngine.java");
        assertTrue("注册被拒的 hint 要拼进工具结果文本",
                src.contains("[工具集上限] ") && src.contains("refusalHint"));
        assertTrue("准入判定必须来自同一个预算类", src.contains("SessionToolBudget.admit("));
    }

    @Test
    public void perRoundToolAccountingIsLogged() throws Exception {
        String src = read("OnlineAgentEngine.java");
        assertTrue("每轮要记 tools 数量/字节/顺序哈希（否则\"发了什么\"只能靠猜）",
                src.contains("tools_count=") && src.contains("tools_sha="));
        assertTrue("命中率要和同一轮的 tools 指纹同条日志出现",
                src.contains("tools=%d个/%d字节 sha=%s"));
        assertTrue("缓存探针要把指纹落到 lastToolsHash", src.contains("lastToolsHash = "));
    }

    @Test
    public void oldCountOnlyCapIsGone() throws Exception {
        String src = read("OnlineAgentEngine.java");
        // 旧口径必须彻底退休（注释里提到它是历史说明，允许）
        assertFalse("MAX_SESSION_TOOLS=9 这个数量口径不能再是代码里的字段/返回值",
                src.contains("int MAX_SESSION_TOOLS") || src.contains("return MAX_SESSION_TOOLS"));
        assertTrue("对外口径改从预算类取（UI 与引擎不能各抄一份）",
                src.contains("return SessionToolBudget.MAX_TOOLSET_TOOLS;")
                        && src.contains("return SessionToolBudget.MAX_TOOLSET_BYTES;"));
    }

    private static String read(String name) throws Exception {
        java.nio.file.Path p = Paths.get("src/main/java/com/oilquiz/app/ai/agent/online", name);
        assertTrue("找不到源文件 " + name + "（" + p + "）", Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
