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
}
