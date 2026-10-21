package com.oilquiz.app.ai.chat.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * AssistantTurn parts 模型（2026-10-21）：modelCalls + 有序 parts 为权威，content 为投影。
 */
public class AssistantTurnTest {

    @Test
    public void snapshotsBecomeOrderedParts() {
        ChatMessage msg = ChatMessage.createAIMessage("a1", "final", 1L);
        msg.turnId = "T1";
        msg.roundSnapshots = new ArrayList<>();
        ChatMessage.RoundSnapshot r1 = new ChatMessage.RoundSnapshot(1, "第一轮正文", "轮1思考", 11L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_1"));
        ChatMessage.RoundSnapshot r2 = new ChatMessage.RoundSnapshot(2, "第二轮最终", "", 12L);
        msg.roundSnapshots.add(r1);
        msg.roundSnapshots.add(r2);

        AssistantTurn turn = AssistantTurn.fromMessage(msg);

        assertNotNull(turn);
        assertEquals("T1", turn.turnId);
        assertEquals(2, turn.modelCalls.size());
        assertEquals("第一轮正文", turn.modelCalls.get(0).content);
        assertEquals("call_1", turn.modelCalls.get(0).toolCallIds.get(0));
        // reasoning -> text -> tool_call -> reasoning? 第二轮无 thinking，直接 text
        assertEquals(AssistantTurn.Part.TYPE_REASONING, turn.parts.get(0).type);
        assertEquals(AssistantTurn.Part.TYPE_TEXT, turn.parts.get(1).type);
        assertEquals(AssistantTurn.Part.TYPE_TOOL_CALL, turn.parts.get(2).type);
        assertEquals(AssistantTurn.Part.TYPE_TEXT, turn.parts.get(3).type);
        assertEquals("第一轮正文\n\n第二轮最终", turn.deriveText());
    }

    @Test
    public void partsDeriveContentAndBounds() {
        ChatMessage msg = ChatMessage.createAIMessage("a3", "old", 1L);
        msg.turnId = "T4";
        msg.roundSnapshots = new ArrayList<>();
        ChatMessage.RoundSnapshot r1 = new ChatMessage.RoundSnapshot(1, "第一轮正文", "", 11L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_9"));
        msg.roundSnapshots.add(r1);
        msg.roundSnapshots.add(new ChatMessage.RoundSnapshot(2, "第二轮最终", "", 12L));

        AssistantTurn turn = AssistantTurn.fromMessage(msg);
        turn.applyToMessage(msg);

        assertEquals("第一轮正文\n\n第二轮最终", msg.content);
        assertEquals(1, msg.contentRoundBounds.size());
        assertEquals(Integer.valueOf("第一轮正文".length()), msg.contentRoundBounds.get(0));
        assertEquals("call_9", msg.contentRoundToolIds.get(0));
    }

    @Test
    public void legacyContentFallsBackToSingleCall() {
        ChatMessage msg = ChatMessage.createAIMessage("a2", "老消息", 1L);
        msg.turnId = "T2";
        AssistantTurn turn = AssistantTurn.fromMessage(msg);
        assertEquals(1, turn.modelCalls.size());
        assertEquals("老消息", turn.deriveText());
    }

    @Test
    public void copyIsDeepForCollections() {
        AssistantTurn turn = new AssistantTurn();
        turn.turnId = "T3";
        turn.modelCalls.add(new AssistantTurn.ModelCall("R1", 1, "a", "t", "eos", 1L));
        turn.parts.add(AssistantTurn.Part.text("p1", "a", 1L));
        AssistantTurn copy = turn.copy();
        copy.modelCalls.get(0).content = "b";
        copy.parts.get(0).text = "b";
        assertEquals("a", turn.modelCalls.get(0).content);
        assertEquals("a", turn.parts.get(0).text);
        assertTrue(copy.modelCalls.get(0).toolCallIds.isEmpty());
    }

    // ---- P1: 稳定 id 与 fingerprint() ----------------------------------

    private static ChatMessage twoRoundMsg() {
        ChatMessage msg = ChatMessage.createAIMessage("a1", "final", 1L);
        msg.turnId = "T1";
        msg.roundSnapshots = new ArrayList<>();
        ChatMessage.RoundSnapshot r1 = new ChatMessage.RoundSnapshot(1, "第一轮正文", "轮1思考", 11L);
        r1.toolCallIds = new ArrayList<>(Arrays.asList("call_1", "call_2"));
        msg.roundSnapshots.add(r1);
        msg.roundSnapshots.add(new ChatMessage.RoundSnapshot(2, "第二轮最终", "", 12L));
        return msg;
    }

    private static java.util.List<String> ids(AssistantTurn turn) {
        java.util.List<String> out = new ArrayList<>();
        for (AssistantTurn.Part p : turn.parts) out.add(p.id);
        return out;
    }

    @Test
    public void everyPartGetsNonEmptyUniqueStableId() {
        AssistantTurn turn = AssistantTurn.fromMessage(twoRoundMsg());
        java.util.List<String> ids = ids(turn);
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            assertNotNull(id);
            assertTrue("id 不应为空", !id.trim().isEmpty());
            assertTrue("id 应唯一: " + id, seen.add(id));
        }
    }

    @Test
    public void partIdsAreStableAcrossRebuild() {
        // 同一消息两次构建，part id 必须逐个一致（供渲染层做 keyed diff/复用）
        java.util.List<String> first = ids(AssistantTurn.fromMessage(twoRoundMsg()));
        java.util.List<String> second = ids(AssistantTurn.fromMessage(twoRoundMsg()));
        assertEquals(first, second);
    }

    @Test
    public void toolPartIdKeyedByToolCallId() {
        AssistantTurn turn = AssistantTurn.fromMessage(twoRoundMsg());
        assertTrue(ids(turn).contains("T1-R1:tool:call_1"));
        assertTrue(ids(turn).contains("T1-R1:tool:call_2"));
    }

    @Test
    public void toolIdDoesNotDriftWhenEarlierToolRemoved() {
        // 移除前一个工具不应改变后一个工具的 id（旧的全局 seq 方案会漂移）
        ChatMessage withBoth = twoRoundMsg();
        ChatMessage onlySecond = twoRoundMsg();
        onlySecond.roundSnapshots.get(0).toolCallIds =
                new ArrayList<>(Arrays.asList("call_2"));

        AssistantTurn a = AssistantTurn.fromMessage(withBoth);
        AssistantTurn b = AssistantTurn.fromMessage(onlySecond);

        String idOfCall2InA = "T1-R1:tool:call_2";
        assertTrue(ids(a).contains(idOfCall2InA));
        assertTrue("call_2 的 id 不应随 call_1 移除而变化", ids(b).contains(idOfCall2InA));
    }

    @Test
    public void resultPartIdDerivedFromCallId() {
        ChatMessage msg = twoRoundMsg();
        org.json.JSONObject props = new org.json.JSONObject();
        try {
            props.put("toolCallId", "call_1");
            props.put("toolName", "weather");
            props.put("args", "{\"city\":\"北京\"}");
            props.put("status", "done");
            props.put("result", "晴天 25度");
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
        msg.components = new ArrayList<>();
        msg.components.add(com.oilquiz.app.ai.chat.component.ComponentData.of(
                "tool_call", props));

        AssistantTurn turn = AssistantTurn.fromMessage(msg);

        AssistantTurn.Part call = null;
        AssistantTurn.Part result = null;
        for (AssistantTurn.Part p : turn.parts) {
            if ("T1-R1:tool:call_1".equals(p.id)) call = p;
            if (AssistantTurn.Part.TYPE_TOOL_RESULT.equals(p.type)) result = p;
        }
        assertNotNull("组件应回填到快照工具 part", call);
        assertNotNull("应生成 result part", result);
        assertEquals("T1-R1:tool:call_1:result", result.id);
    }

    @Test
    public void fingerprintIgnoresIdAndTimestamp() {
        AssistantTurn.Part a = AssistantTurn.Part.text("id-a", "你好世界", 1L);
        AssistantTurn.Part b = AssistantTurn.Part.text("id-b", "你好世界", 9999L);
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    public void fingerprintChangesWithContent() {
        String base = AssistantTurn.Part.text("id", "你好", 1L).fingerprint();
        assertNotEquals(base, AssistantTurn.Part.text("id", "你好呀", 1L).fingerprint());
    }

    @Test
    public void fingerprintDistinguishesPartType() {
        assertNotEquals(
                AssistantTurn.Part.text("id", "same", 1L).fingerprint(),
                AssistantTurn.Part.reasoning("id", "same", 1L).fingerprint());
    }

    @Test
    public void fingerprintTreatsNullAsEmpty() {
        AssistantTurn.Part viaFactory = AssistantTurn.Part.text("id", "x", 1L);
        AssistantTurn.Part manual = new AssistantTurn.Part();
        manual.id = "id";
        manual.type = AssistantTurn.Part.TYPE_TEXT;
        manual.text = "x";
        manual.timestamp = 1L;
        // 其余字段留 null，应与工厂（未设置即 null）等价
        assertEquals(viaFactory.fingerprint(), manual.fingerprint());
    }

    @Test
    public void fingerprintChangesWhenToolResultChanges() {
        AssistantTurn.Part call = AssistantTurn.Part.toolCall(
                "t", "call_1", "weather", "{}", "done", 1L);
        String r1 = AssistantTurn.Part.toolResult(call, "晴天", 1L).fingerprint();
        String r2 = AssistantTurn.Part.toolResult(call, "雨天", 1L).fingerprint();
        assertNotEquals(r1, r2);
        // 结果 part 与调用 part 指纹不同（type/result 不同）
        assertNotEquals(call.fingerprint(), r1);
    }

    @Test
    public void fingerprintReflectsStreamingState() {
        // 正文相同、只有流式态不同：done 收尾必须触发重渲（补全 markdown）
        AssistantTurn.Part streaming = AssistantTurn.Part.text("t", "同一句话", 1L);
        streaming.state = AssistantTurn.Part.STATE_STREAMING;
        AssistantTurn.Part done = AssistantTurn.Part.text("t", "同一句话", 1L);
        done.state = AssistantTurn.Part.STATE_DONE;
        assertNotEquals(streaming.fingerprint(), done.fingerprint());
    }

    @Test
    public void isStreamingOnlyTrueForStreamingState() {
        AssistantTurn.Part p = AssistantTurn.Part.text("t", "x", 1L);
        assertFalse("null 默认非流式", p.isStreaming());
        p.state = AssistantTurn.Part.STATE_STREAMING;
        assertTrue(p.isStreaming());
        p.state = AssistantTurn.Part.STATE_DONE;
        assertFalse(p.isStreaming());
    }

    @Test
    public void cancelledStateIsDistinctAndNotStreaming() {
        AssistantTurn.Part done = AssistantTurn.Part.text("t", "半截", 1L);
        done.state = AssistantTurn.Part.STATE_DONE;
        AssistantTurn.Part cancelled = AssistantTurn.Part.text("t", "半截", 1L);
        cancelled.state = AssistantTurn.Part.STATE_CANCELLED;
        assertFalse("cancelled 不是流式", cancelled.isStreaming());
        // 用户中断收尾也应触发重绑（指纹随 state 变）
        assertNotEquals(done.fingerprint(), cancelled.fingerprint());
    }

    @Test
    public void fromMessageForPartsSplitsMermaidIntoDataPart() {
        ChatMessage msg = ChatMessage.createAIMessage("a",
                "前面文字\n```mermaid\ngraph TD; A-->B;\n```\n后面文字", 1L);
        msg.turnId = "T1";

        AssistantTurn t = AssistantTurn.fromMessageForParts(msg, false);

        int data = 0;
        boolean textStillHasFence = false;
        boolean htmlComp = false;
        for (AssistantTurn.Part p : t.parts) {
            if (AssistantTurn.Part.TYPE_DATA.equals(p.type)) {
                data++;
                if (p.data != null && "html".equals(p.data.type)) htmlComp = true;
            }
            if (AssistantTurn.Part.TYPE_TEXT.equals(p.type)
                    && p.text != null && p.text.contains("```mermaid")) {
                textStillHasFence = true;
            }
        }
        assertTrue("mermaid 应切成 html data part", data >= 1);
        assertTrue("data part 是 html 组件", htmlComp);
        assertFalse("残留 text 段不应再含 mermaid 围栏", textStillHasFence);
    }

    private static int countType(AssistantTurn turn, String type) {
        int n = 0;
        for (AssistantTurn.Part p : turn.parts) {
            if (type.equals(p.type)) n++;
        }
        return n;
    }

    @Test
    public void fromMessageEmitsDataPartsFromNonToolComponents() {
        ChatMessage msg = ChatMessage.createAIMessage("a1", "正文", 1L);
        msg.turnId = "T9";
        msg.components = new ArrayList<>();
        JSONObject chartProps = new JSONObject();
        try {
            chartProps.put("title", "销量");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ComponentData chart = ComponentData.of("chart", chartProps);
        chart.id = "cmp1";
        msg.components.add(chart);
        // tool_call 组件应走工具路径，不应变成 data part
        ComponentData toolComp = ComponentData.of("tool_call", new JSONObject());
        toolComp.id = "cmp2";
        msg.components.add(toolComp);

        AssistantTurn turn = AssistantTurn.fromMessage(msg);

        assertEquals("非 tool_call 组件产 1 个 data part", 1, countType(turn, AssistantTurn.Part.TYPE_DATA));
        boolean hasStableId = false;
        for (AssistantTurn.Part p : turn.parts) {
            if ("data:cmp1".equals(p.id)) {
                hasStableId = true;
                assertNotNull("data part 应引用回组件", p.data);
                assertEquals("chart", p.data.type);
            }
        }
        assertTrue(hasStableId);
    }

    @Test
    public void twoDataPartsOfSameTypeHaveDistinctFingerprints() {
        ChatMessage msg = ChatMessage.createAIMessage("a1", "正文", 1L);
        msg.turnId = "T9";
        msg.components = new ArrayList<>();
        ComponentData c1 = ComponentData.of("chart", props("title", "A"));
        c1.id = "x1";
        ComponentData c2 = ComponentData.of("chart", props("title", "B"));
        c2.id = "x2";
        msg.components.add(c1);
        msg.components.add(c2);

        AssistantTurn turn = AssistantTurn.fromMessage(msg);
        assertEquals(2, countType(turn, AssistantTurn.Part.TYPE_DATA));

        AssistantTurn.Part d1 = null;
        AssistantTurn.Part d2 = null;
        for (AssistantTurn.Part p : turn.parts) {
            if ("data:x1".equals(p.id)) d1 = p;
            if ("data:x2".equals(p.id)) d2 = p;
        }
        // 同类型不同内容：指纹必须不同，否则引擎会误判第二个"没变"而跳过绑定
        assertNotEquals(d1.fingerprint(), d2.fingerprint());
    }

    private static JSONObject props(String k, String v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    @Test
    public void fromMessageForPartsSplitsInlineComponentMarkers() {
        ChatMessage msg = ChatMessage.createAIMessage("a",
                "开头说明\n```component:chart\n{\"title\":\"A\"}\n```\n结尾说明", 1L);
        msg.turnId = "T1";

        AssistantTurn split = AssistantTurn.fromMessageForParts(msg);

        int data = 0;
        boolean anyTextHasMarker = false;
        boolean sawHead = false, sawTail = false;
        for (AssistantTurn.Part p : split.parts) {
            if (AssistantTurn.Part.TYPE_DATA.equals(p.type)) {
                data++;
                assertNotNull("内联 data part 引用组件", p.data);
                assertEquals("chart", p.data.type);
            }
            if (AssistantTurn.Part.TYPE_TEXT.equals(p.type)) {
                assertFalse("切出的 text 段不应再含 component 标记",
                        p.text.contains("```component"));
                if (p.text.contains("开头说明")) sawHead = true;
                if (p.text.contains("结尾说明")) sawTail = true;
            }
        }
        assertEquals(1, data);
        assertTrue("头段保留", sawHead);
        assertTrue("尾段保留", sawTail);
        // 两个 text 段 id 必须不同（否则引擎撞 id）
        Set<String> ids = new HashSet<>();
        for (AssistantTurn.Part p : split.parts) ids.add(p.id);
        assertEquals("所有 part id 唯一", split.parts.size(), ids.size());
    }

    @Test
    public void groupToolsCollapsesConsecutiveCallsAndMergesResults() {
        AssistantTurn turn = new AssistantTurn();
        turn.turnId = "T1";
        AssistantTurn.Part c1 = AssistantTurn.Part.toolCall(
                "T1:tool:c1", "c1", "weather", "{\"city\":\"北京\"}", "done", 1L);
        AssistantTurn.Part r1 = AssistantTurn.Part.toolResult(c1, "晴天 25度", 1L);
        AssistantTurn.Part c2 = AssistantTurn.Part.toolCall(
                "T1:tool:c2", "c2", "database", "{}", "done", 2L);
        AssistantTurn.Part r2 = AssistantTurn.Part.toolResult(c2, "10 题", 2L);
        turn.parts.add(AssistantTurn.Part.text("t0", "正文", 1L));
        turn.parts.add(c1);
        turn.parts.add(r1);
        turn.parts.add(c2);
        turn.parts.add(r2);
        turn.parts.add(AssistantTurn.Part.text("t1", "收尾", 3L));

        AssistantTurn.groupTools(turn);

        // 2 个连续工具 → 1 个 tool_group；无独立 tool_result 残留。
        assertEquals(1, countType(turn, AssistantTurn.Part.TYPE_TOOL_GROUP));
        assertEquals("结果已并入，不该有独立 tool_result",
                0, countType(turn, AssistantTurn.Part.TYPE_TOOL_RESULT));
        assertEquals("不该再散着两个 tool_call",
                0, countType(turn, AssistantTurn.Part.TYPE_TOOL_CALL));

        AssistantTurn.Part grp = null;
        for (AssistantTurn.Part p : turn.parts) {
            if (AssistantTurn.Part.TYPE_TOOL_GROUP.equals(p.type)) grp = p;
        }
        assertNotNull(grp);
        assertEquals(2, grp.group.size());
        assertEquals("晴天 25度", grp.group.get(0).result);
        assertEquals("10 题", grp.group.get(1).result);
        // 头尾正文保留，顺序不变：text → group → text
        assertEquals(AssistantTurn.Part.TYPE_TEXT, turn.parts.get(0).type);
        assertEquals(AssistantTurn.Part.TYPE_TEXT, turn.parts.get(2).type);
    }

    @Test
    public void groupToolsKeepsSingleCallStandalone() {
        AssistantTurn turn = new AssistantTurn();
        AssistantTurn.Part c1 = AssistantTurn.Part.toolCall(
                "x:tool:c1", "c1", "weather", "{}", "done", 1L);
        turn.parts.add(AssistantTurn.Part.text("t0", "正文", 1L));
        turn.parts.add(c1);
        turn.parts.add(AssistantTurn.Part.toolResult(c1, "晴", 1L));
        turn.parts.add(AssistantTurn.Part.text("t1", "收尾", 2L));

        AssistantTurn.groupTools(turn);

        // 单个工具不折叠成组，仍是独立 tool_call，且 result 已并入。
        assertEquals(0, countType(turn, AssistantTurn.Part.TYPE_TOOL_GROUP));
        assertEquals(1, countType(turn, AssistantTurn.Part.TYPE_TOOL_CALL));
        assertEquals(0, countType(turn, AssistantTurn.Part.TYPE_TOOL_RESULT));
    }

    @Test
    public void groupToolsFingerprintTracksMemberChanges() {
        AssistantTurn a = new AssistantTurn();
        AssistantTurn b = new AssistantTurn();
        AssistantTurn.Part c1a = AssistantTurn.Part.toolCall("i:c1", "c1", "w", "{}", "done", 1L);
        AssistantTurn.Part c2a = AssistantTurn.Part.toolCall("i:c2", "c2", "d", "{}", "done", 2L);
        a.parts.add(c1a);
        a.parts.add(c2a);
        AssistantTurn.Part c1b = AssistantTurn.Part.toolCall("i:c1", "c1", "w", "{}", "done", 1L);
        AssistantTurn.Part c2b = AssistantTurn.Part.toolCall("i:c2", "c2", "d", "{}", "done", 2L);
        c2b.result = "新结果";
        b.parts.add(c1b);
        b.parts.add(c2b);
        AssistantTurn.groupTools(a);
        AssistantTurn.groupTools(b);

        AssistantTurn.Part ga = null;
        AssistantTurn.Part gb = null;
        for (AssistantTurn.Part p : a.parts) {
            if (AssistantTurn.Part.TYPE_TOOL_GROUP.equals(p.type)) ga = p;
        }
        for (AssistantTurn.Part p : b.parts) {
            if (AssistantTurn.Part.TYPE_TOOL_GROUP.equals(p.type)) gb = p;
        }
        assertNotNull(ga);
        assertNotNull(gb);
        assertNotEquals("成员结果变化应改变组卡指纹", ga.fingerprint(), gb.fingerprint());
    }

    @Test
    public void attachOwnedProductsFoldsProductUnderOwningToolAndRemovesTopLevel() {
        AssistantTurn turn = new AssistantTurn();
        AssistantTurn.Part call = AssistantTurn.Part.toolCall(
                "T1:tool:c1", "c1", "weather", "{}", "done", 1L);
        JSONObject prod = new JSONObject();
        try {
            prod.put("title", "天气图");
            prod.put("producerToolCallId", "c1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ComponentData chart = ComponentData.of("chart", prod);
        chart.id = "cmp1";
        turn.parts.add(call);
        turn.parts.add(AssistantTurn.Part.data("data:cmp1", chart, 1L));
        turn.parts.add(AssistantTurn.Part.text("t1", "正文", 2L));

        AssistantTurn.attachOwnedProducts(turn);

        assertEquals("产物折进工具后顶层不再有 data part",
                0, countType(turn, AssistantTurn.Part.TYPE_DATA));
        assertNotNull("所属工具应挂上产物", call.products);
        assertEquals(1, call.products.size());
        assertEquals("cmp1", call.products.get(0).id);
        // 正文 part 不受影响
        assertEquals(AssistantTurn.Part.TYPE_TEXT, turn.parts.get(1).type);
    }

    @Test
    public void attachOwnedProductsKeepsUnownedAndNameFallback() {
        AssistantTurn turn = new AssistantTurn();
        AssistantTurn.Part call = AssistantTurn.Part.toolCall(
                "T1:tool:c1", "c1", "weather", "{}", "done", 1L);
        // 仅 producerToolName（本地无 id 场景）→ 按名字归属
        JSONObject byName = propsKV("title", "N1", "producerToolName", "weather");
        ComponentData owned = ComponentData.of("chart", byName);
        owned.id = "own";
        // 无 producer → 保持顶层
        ComponentData orphan = ComponentData.of("chart", props("title", "独立"));
        orphan.id = "orph";
        turn.parts.add(call);
        turn.parts.add(AssistantTurn.Part.data("data:own", owned, 1L));
        turn.parts.add(AssistantTurn.Part.data("data:orph", orphan, 1L));

        AssistantTurn.attachOwnedProducts(turn);

        assertEquals(1, countType(turn, AssistantTurn.Part.TYPE_DATA)); // orphan 仍顶层
        assertNotNull(call.products);
        assertEquals(1, call.products.size());
        assertEquals("own", call.products.get(0).id);
    }

    @Test
    public void toolFingerprintChangesWhenOwnedProductChanges() {
        AssistantTurn.Part a = AssistantTurn.Part.toolCall("c1", "c1", "w", "{}", "done", 1L);
        AssistantTurn.Part b = AssistantTurn.Part.toolCall("c1", "c1", "w", "{}", "done", 1L);
        ComponentData p1 = ComponentData.of("chart", props("title", "A"));
        ComponentData p2 = ComponentData.of("chart", props("title", "B"));
        a.products = new ArrayList<>(Arrays.asList(p1));
        b.products = new ArrayList<>(Arrays.asList(p2));
        assertNotEquals("产物内容变化应改变工具卡指纹", a.fingerprint(), b.fingerprint());

        // 无产物 vs 有产物也应不同
        AssistantTurn.Part c = AssistantTurn.Part.toolCall("c1", "c1", "w", "{}", "done", 1L);
        assertNotEquals(c.fingerprint(), a.fingerprint());
    }

    private static JSONObject propsKV(String k1, String v1, String k2, String v2) {
        JSONObject o = new JSONObject();
        try {
            o.put(k1, v1);
            o.put(k2, v2);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    @Test
    public void allBuiltinComponentTypesBecomeRenderableParts() {
        // 逐类内置组件（图表/天气/应用/表格/视频/音频/html/布局树/产物…）都必须成为 data part，
        // 且被默认注册表全覆盖（闸门不丢任一内置类型）。工具类走 tool_call，不单列 data。
        ChatMessage msg = ChatMessage.createAIMessage("a1", "正文", 1L);
        msg.turnId = "T7";
        msg.components = new ArrayList<>();
        String[] types = {
                "chart", "info_card", "table_card", "weather", "app_operation",
                "video", "audio", "html", "layout", "todo_card", "progress", "quiz"
        };
        int i = 0;
        for (String t : types) {
            ComponentData c = ComponentData.of(t, props("title", t));
            c.id = "cmp" + (i++);
            msg.components.add(c);
        }
        // 一个工具组件应走工具路径，不占 data 名额
        ComponentData tool = ComponentData.of("tool_call", props("toolName", "weather"));
        tool.id = "cmp_tool";
        msg.components.add(tool);

        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg, false);

        assertEquals("每个内置组件都应成为 data part", types.length,
                countType(turn, AssistantTurn.Part.TYPE_DATA));
        // 闸门全覆盖：默认注册表能解析所有 part（含刚造的组卡若有），任一内置类型不被丢
        assertTrue("默认注册表应覆盖全部内置 data part",
                com.oilquiz.app.ai.chat.parts.PartRendererRegistry.createDefault()
                        .canRenderAll(turn.parts));
    }

    @Test
    public void fromMessageForPartsDoesNotMutateLiveAssistantTurn() {
        ChatMessage msg = ChatMessage.createAIMessage("a",
                "文\n```component:chart\n{\"title\":\"A\"}\n```", 1L);
        msg.turnId = "T1";
        // 现网 canonical（AIChatActivity 会这么建）：整段含标记的 text part
        msg.assistantTurn = AssistantTurn.fromMessage(msg);
        int livePartsBefore = msg.assistantTurn.parts.size();
        String liveText = msg.assistantTurn.parts.get(0).text;

        AssistantTurn split = AssistantTurn.fromMessageForParts(msg);

        // 现网 turn 不被改动：仍是单个含标记 text part
        assertEquals(livePartsBefore, msg.assistantTurn.parts.size());
        assertTrue(liveText.contains("```component"));
        // 但返回的是展开后的新 turn（>1 part，且不含标记）
        assertTrue(split.parts.size() > livePartsBefore);
    }
}
