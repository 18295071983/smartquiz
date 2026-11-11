package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史导入必须与实时同步"同源"。
 *
 * <p>旧导入器只认四类事件，导入出来的电脑会话在手机上<b>没有工具结果、没有待办卡</b>，
 * 与实时看到的是两套东西。现在两条路径都用 {@code DshTimeline + DshProjection}，
 * 这个测试守住：事件流一次性回放得到的消息，形状（正文/思考/卡片/交错顺序）与实时一致。
 */
public class DshHistoryImporterTest {

    @Test
    public void importedTurnMatchesLiveShape() {
        List<JSONObject> events = new ArrayList<>();
        events.add(user(1, "你好"));
        events.add(assistant(2, 1, 1, "先看看目录", "需要列目录"));
        events.add(toolCall(3, 1, 1, "call_1", "pwsh", "{\"command\":\"ls\"}"));
        events.add(toolResult(4, 1, 1, "call_1", "a.txt", false));
        events.add(assistant(5, 1, 2, "完成", null));
        events.add(todo(6, 1, 2));
        events.add(envelope("turn/end", 7, located(1, 2)));

        List<ChatMessage> messages = DshHistoryImporter.buildMessages(events);

        assertEquals(2, messages.size());
        assertEquals(ChatMessage.MessageType.USER, messages.get(0).type);
        ChatMessage ai = messages.get(1);
        assertEquals(ChatMessage.MessageType.AI, ai.type);
        assertEquals("先看看目录\n\n完成", ai.content);
        assertEquals("需要列目录", ai.thinkingContent);
        assertEquals("两步两条快照", 2, ai.roundSnapshots.size());
        assertEquals(2, ai.components.size());
        assertEquals("tool_call", ai.components.get(0).type);
        assertEquals("success", ai.components.get(0).props.optString("status"));
        assertEquals("a.txt", ai.components.get(0).props.optString("result"));
        assertEquals("todo_card", ai.components.get(1).type);

        AssistantTurn turn = AssistantTurn.fromMessageForParts(ai);
        List<String> types = new ArrayList<>();
        for (AssistantTurn.Part p : turn.parts) types.add(p.type);
        assertEquals(java.util.Arrays.asList("reasoning", "text", "tool_group", "text", "data"), types);
    }

    @Test
    public void approvalEventsBecomeTimelineParts() {
        List<JSONObject> events = new ArrayList<>();
        events.add(user(1, "改一下配置"));
        events.add(assistant(2, 1, 1, "需要写文件", null));
        events.add(approval(3, "ap_1", "write_file", "覆盖 config.json？"));
        events.add(envelope("approval_gone", 4, null));

        List<ChatMessage> messages = DshHistoryImporter.buildMessages(events);

        assertEquals(2, messages.size());
        ChatMessage ai = messages.get(1);
        assertEquals(1, ai.approvals.size());
        assertEquals("write_file", ai.approvals.get(0).permission);
        assertEquals("覆盖 config.json？", ai.approvals.get(0).prompt);
        assertEquals("电脑端已处理 → 导入时标过期，而不是永远挂 pending",
                "expired", ai.approvals.get(0).status);

        AssistantTurn turn = AssistantTurn.fromMessageForParts(ai);
        boolean hasApprovalPart = false;
        for (AssistantTurn.Part p : turn.parts) {
            if (AssistantTurn.Part.TYPE_APPROVAL.equals(p.type)) hasApprovalPart = true;
        }
        assertTrue("审批要在时间线里能查到", hasApprovalPart);
    }

    @Test
    public void userMessageFlushesPreviousTurnIntoOneAiMessage() {
        List<JSONObject> events = new ArrayList<>();
        events.add(user(1, "第一问"));
        events.add(assistant(2, 1, 1, "第一答", null));
        events.add(user(3, "第二问"));
        events.add(assistant(4, 2, 1, "第二答", null));

        List<ChatMessage> messages = DshHistoryImporter.buildMessages(events);

        assertEquals(4, messages.size());
        assertEquals("第一问", messages.get(0).content);
        assertEquals("第一答", messages.get(1).content);
        assertEquals("第二问", messages.get(2).content);
        assertEquals("第二答", messages.get(3).content);
    }

    @Test
    public void emptyAssistantShellIsDropped() {
        List<JSONObject> events = new ArrayList<>();
        events.add(user(1, "你好"));
        // turn/start 只推进阶段文案，不产正文/思考/卡片 → 不能留下一条空气泡
        events.add(envelope("turn/start", 2, located(1, 1)));

        List<ChatMessage> messages = DshHistoryImporter.buildMessages(events);

        assertEquals("只有用户消息时不能留一条空气泡", 1, messages.size());
        assertEquals(ChatMessage.MessageType.USER, messages.get(0).type);
    }

    @Test
    public void snapshotTurnsFallBackToMessages() {
        // 超大会话服务端会退化成 snapshot（只有角色+文本）；合成事件走的是同一套
        // user/message、assistant/message 入口，这里验证合成形状能被正常消费。
        JSONArray content = new JSONArray().put(payload("type", "text", "text", "摘要正文"));
        JSONObject data = payload("message", payload("content", content));
        List<JSONObject> synthetic = new ArrayList<>();
        synthetic.add(envelope("assistant/message", -1, data));

        List<ChatMessage> messages = DshHistoryImporter.buildMessages(synthetic);

        assertEquals(1, messages.size());
        assertEquals("摘要正文", messages.get(0).content);
    }

    // ===== 构造 =====

    private static JSONObject envelope(String type, int seq, JSONObject data) {
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", type);
            ev.put("seq", seq);
            ev.put("ts", 1000L + seq);
            if (data != null) ev.put("data", data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return ev;
    }

    private static JSONObject located(int turn, int step) {
        return payload("turn", turn, "step", step);
    }

    private static JSONObject user(int seq, String text) {
        JSONArray content = new JSONArray().put(payload("type", "text", "text", text));
        return envelope("user/message", seq, payload("content", content));
    }

    private static JSONObject assistant(int seq, int turn, int step, String text, String thinking) {
        JSONObject data = located(turn, step);
        JSONArray content = new JSONArray();
        if (thinking != null) content.put(payload("type", "reasoning", "text", thinking));
        if (text != null) content.put(payload("type", "text", "text", text));
        return envelope("assistant/message", seq, merge(data, payload("message", payload("content", content))));
    }

    private static JSONObject toolCall(int seq, int turn, int step, String callId, String name, String args) {
        return envelope("tool/call", seq, merge(located(turn, step),
                payload("callId", callId, "name", name, "arguments", args)));
    }

    private static JSONObject toolResult(int seq, int turn, int step, String callId, String text,
                                         boolean error) {
        JSONArray content = new JSONArray().put(payload("type", "text", "text", text));
        return envelope("tool/result", seq, merge(located(turn, step),
                payload("toolCallId", callId, "isError", error, "message", payload("content", content))));
    }

    private static JSONObject todo(int seq, int turn, int step) {
        return envelope("todo/write", seq, merge(located(turn, step),
                payload("todos", new JSONArray().put(payload("content", "写测试", "status", "pending")))));
    }

    /** approval 的 request 在信封顶层（不在 data 里），与插件 /events 一致 */
    private static JSONObject approval(int seq, String id, String tool, String summary) {
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", "approval");
            ev.put("seq", seq);
            ev.put("id", id);
            ev.put("request", payload("tool", tool, "summary", summary));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return ev;
    }

    private static JSONObject merge(JSONObject a, JSONObject b) {
        try {
            for (java.util.Iterator<String> it = b.keys(); it.hasNext(); ) {
                String k = it.next();
                a.put(k, b.get(k));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return a;
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
