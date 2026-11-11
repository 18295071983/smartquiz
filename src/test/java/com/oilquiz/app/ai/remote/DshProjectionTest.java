package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 投影层：时间线 → {@code roundSnapshots / components}，并验证它真的能被 App 的
 * parts 渲染器按"思考 → 正文 → 工具卡"的顺序装配出来。
 *
 * <p>这是旧实现最致命的地方：它写的是 {@code assistantTurn.parts}，而渲染真值是
 * {@code roundSnapshots + components}，所以手机上的多步回合永远只有一大段思考 +
 * 一大段正文 + 末尾一堆卡。这里用 {@link AssistantTurn#fromMessageForParts}（渲染层
 * 实际调用的入口）反过来证明交错顺序。</p>
 */
public class DshProjectionTest {

    /** 稳定但带前缀的发号器，模拟 Activity 侧的 ChatIdDispatcher */
    private static final DshProjection.IdSource IDS = new DshProjection.IdSource() {
        private final Map<String, String> issued = new HashMap<>();
        private int next = 1;

        @Override
        public String idFor(String key) {
            return issued.computeIfAbsent(key, k -> "T1-C" + (next++));
        }
    };

    @Test
    public void projectsStepsIntoSnapshotsAndComponents() {
        ChatMessage msg = ChatMessage.createAIMessage("m1", "", 500L);
        msg.turnId = "T1";

        DshProjection.project(msg, timeline(), IDS, 600L);

        assertEquals("正文一\n\n正文二", msg.content);
        assertEquals("思考一\n\n思考二", msg.thinkingContent);
        assertEquals("两步各一条快照", 2, msg.roundSnapshots.size());
        assertEquals(1, msg.roundSnapshots.get(0).toolCallIds.size());
        assertEquals("call_1", msg.roundSnapshots.get(0).toolCallIds.get(0));

        assertNotNull(msg.components);
        assertEquals(2, msg.components.size()); // 工具卡 + 待办卡
        ComponentData tool = msg.components.get(0);
        assertEquals("tool_call", tool.type);
        assertEquals("call_1", tool.props.optString("toolCallId"));
        assertEquals("pwsh", tool.props.optString("toolName"));
        assertEquals("success", tool.props.optString("status"));
        assertEquals("a.txt", tool.props.optString("result"));
        assertEquals("todo_card", msg.components.get(1).type);
        assertNull("不再维护第四份渲染模型", msg.assistantTurn);
    }

    @Test
    public void renderedParts_interleaveReasoningTextAndToolCards() {
        ChatMessage msg = ChatMessage.createAIMessage("m1", "", 500L);
        msg.turnId = "T1";

        DshProjection.project(msg, timeline(), IDS, 600L);
        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg);
        List<AssistantTurn.Part> parts = turn.parts;

        // 顺序：思考一 → 正文一 → 工具组 → 思考二 → 正文二 → 待办卡
        assertEquals(6, parts.size());
        assertEquals(AssistantTurn.Part.TYPE_REASONING, parts.get(0).type);
        assertEquals("思考一", parts.get(0).text);
        assertEquals(AssistantTurn.Part.TYPE_TEXT, parts.get(1).type);
        assertEquals("正文一", parts.get(1).text);
        assertEquals(AssistantTurn.Part.TYPE_TOOL_GROUP, parts.get(2).type);
        assertEquals(1, parts.get(2).group.size());
        assertEquals("success", parts.get(2).group.get(0).status);
        assertEquals("a.txt", parts.get(2).group.get(0).result);
        assertEquals("第二步的思考必须排在第一步工具之后",
                AssistantTurn.Part.TYPE_REASONING, parts.get(3).type);
        assertEquals("正文二", parts.get(4).text);
        assertEquals(AssistantTurn.Part.TYPE_DATA, parts.get(5).type);
        assertEquals("todo_card", parts.get(5).data.type);
    }

    @Test
    public void cardRoundPositionInterleavesWithSteps() {
        DshTimeline tl = new DshTimeline();
        tl.reduce(DshEvent.from(event("assistant/message", 1, located(1, 1),
                messagePayload("第一步", null))));
        tl.reduce(DshEvent.from(event("todo/write", 2, located(1, 1),
                payload("todos", new JSONArray().put(payload("content", "第一步的待办", "status", "pending"))))));
        tl.reduce(DshEvent.from(event("assistant/message", 3, located(1, 2),
                messagePayload("第二步", null))));

        ChatMessage msg = ChatMessage.createAIMessage("m4", "", 500L);
        msg.turnId = "T1";
        DshProjection.project(msg, tl, IDS, 600L);

        AssistantTurn turn = AssistantTurn.fromMessageForParts(msg);
        List<AssistantTurn.Part> parts = turn.parts;
        // 卡片插在自己那一步之后，而不是整条消息末尾
        assertEquals(3, parts.size());
        assertEquals("第一步", parts.get(0).text);
        assertEquals(AssistantTurn.Part.TYPE_DATA, parts.get(1).type);
        assertEquals("第一步的待办",
                parts.get(1).data.props.optJSONArray("items").optJSONObject(0).optString("text"));
        assertEquals("第二步", parts.get(2).text);
    }

    @Test
    public void projectionIsIdempotent() {
        DshTimeline tl = timeline();
        ChatMessage msg = ChatMessage.createAIMessage("m1", "", 500L);
        msg.turnId = "T1";

        DshProjection.project(msg, tl, IDS, 600L);
        String content = msg.content;
        int snapshots = msg.roundSnapshots.size();
        String firstToolId = msg.components.get(0).id;

        DshProjection.project(msg, tl, IDS, 700L);

        assertEquals(content, msg.content);
        assertEquals(snapshots, msg.roundSnapshots.size());
        assertEquals("补拉重复投影不能换组件 id", firstToolId, msg.components.get(0).id);
    }

    @Test
    public void toolOnlyStepStillBecomesSnapshot() {
        DshTimeline tl = new DshTimeline();
        tl.reduce(DshEvent.from(event("tool/call", 1, located(1, 1),
                payload("callId", "c9", "name", "read_file", "arguments", "{}"))));

        ChatMessage msg = ChatMessage.createAIMessage("m2", "", 1L);
        DshProjection.project(msg, tl, IDS, 2L);

        assertEquals("纯工具步也要有快照，否则工具被塞到末尾", 1, msg.roundSnapshots.size());
        assertEquals("", msg.content);
        assertEquals(1, msg.components.size());
        assertEquals("running", msg.components.get(0).props.optString("status"));
    }

    @Test
    public void emptyTimelineClearsMessage() {
        ChatMessage msg = ChatMessage.createAIMessage("m3", "旧正文", 1L);
        msg.roundSnapshots = new java.util.ArrayList<>();
        msg.components = new java.util.ArrayList<>();

        DshProjection.project(msg, new DshTimeline(), IDS, 2L);

        assertEquals("", msg.content);
        assertNull(msg.thinkingContent);
        assertTrue(msg.roundSnapshots.isEmpty());
        assertTrue(msg.components.isEmpty());
    }

    // ===== 构造一条两步时间线：步骤1 = 思考+正文+工具，步骤2 = 思考+正文，另有一张待办卡 =====

    private static DshTimeline timeline() {
        DshTimeline tl = new DshTimeline();
        tl.reduce(DshEvent.from(event("assistant/message", 1, located(1, 1),
                messagePayload("正文一", "思考一"))));
        tl.reduce(DshEvent.from(event("tool/call", 2, located(1, 1),
                payload("callId", "call_1", "name", "pwsh", "arguments", "{\"command\":\"ls\"}"))));
        tl.reduce(DshEvent.from(event("tool/result", 3, located(1, 1),
                payload("toolCallId", "call_1", "isError", false),
                messagePayload("a.txt", null))));
        tl.reduce(DshEvent.from(event("assistant/message", 4, located(1, 2),
                messagePayload("正文二", "思考二"))));
        tl.reduce(DshEvent.from(event("todo/write", 5, located(1, 2),
                payload("todos", new JSONArray().put(payload("content", "写测试", "status", "pending"))))));
        return tl;
    }

    // ===== JSON 构造 =====

    private static JSONObject located(int turn, int step) {
        return payload("turn", turn, "step", step);
    }

    private static JSONObject messagePayload(String text, String thinking) {
        JSONArray content = new JSONArray();
        if (thinking != null) content.put(payload("type", "reasoning", "text", thinking));
        if (text != null) content.put(payload("type", "text", "text", text));
        return payload("message", payload("content", content));
    }

    /** 事件 = {type, seq, data: located + 各 merge 片段} */
    private static JSONObject event(String type, int seq, JSONObject located, JSONObject... merges) {
        JSONObject data = new JSONObject();
        try {
            merge(data, located);
            for (JSONObject m : merges) merge(data, m);
            JSONObject ev = new JSONObject();
            ev.put("type", type);
            ev.put("seq", seq);
            ev.put("data", data);
            return ev;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void merge(JSONObject target, JSONObject src) throws Exception {
        if (src == null) return;
        for (java.util.Iterator<String> it = src.keys(); it.hasNext(); ) {
            String k = it.next();
            target.put(k, src.get(k));
        }
    }

    private static JSONObject payload(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            try {
                o.put(String.valueOf(pairs[i]), pairs[i + 1]);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return o;
    }
}
