package com.oilquiz.app.ai.sessionlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话回放纯逻辑测试：事件流 → 消息 / 工具调用 / 统计。
 */
public class SessionReplayTest {

    private static final String SID = "s1";

    private static SessionEvent ev(long seq, SessionEventType type, String payload) {
        return new SessionEvent(seq, SID, type, payload, 1000L + seq * 10L, 0L);
    }

    private static String str(JSONObject o) {
        return o == null ? null : o.toString();
    }

    @Test
    public void empty_events_yield_open_status() {
        SessionReplay.ReplayResult r = SessionReplay.replay(new ArrayList<>());
        assertTrue(r.messages.isEmpty());
        assertTrue(r.toolCalls.isEmpty());
        assertEquals(SessionReplay.Status.OPEN, r.stats.status);
        assertEquals(0, r.stats.turnCount);
    }

    @Test
    public void null_events_are_safe() {
        SessionReplay.ReplayResult r = SessionReplay.replay(null);
        assertTrue(r.messages.isEmpty());
        assertEquals(SessionReplay.Status.OPEN, r.stats.status);
    }

    @Test
    public void full_turn_is_reconstructed() {
        List<SessionEvent> events = new ArrayList<>();
        events.add(ev(1, SessionEventType.SESSION_STARTED, null));
        events.add(ev(2, SessionEventType.TURN_STARTED, null));
        events.add(ev(3, SessionEventType.USER_MESSAGE,
                str(SessionEventPayload.userMessage("你好"))));
        events.add(ev(4, SessionEventType.THINKING_MESSAGE,
                str(SessionEventPayload.thinking("让我想想"))));
        events.add(ev(5, SessionEventType.THINKING_ENDED, null));
        events.add(ev(6, SessionEventType.TOOL_CALL_STARTED,
                str(SessionEventPayload.toolCallStarted("t1", "get_weather", "{\"city\":\"银川\"}"))));
        events.add(ev(7, SessionEventType.TOOL_CALL_COMPLETED,
                str(SessionEventPayload.toolCallCompleted("t1", "get_weather", true, "晴 25°C"))));
        events.add(ev(8, SessionEventType.ASSISTANT_MESSAGE,
                str(SessionEventPayload.assistantMessage("今天银川晴"))));
        events.add(ev(9, SessionEventType.TURN_COMPLETED, null));

        SessionReplay.ReplayResult r = SessionReplay.replay(events);

        // 消息：user / thinking / assistant 三句
        assertEquals(3, r.messages.size());
        assertEquals("user", r.messages.get(0).role);
        assertEquals("你好", r.messages.get(0).content);
        assertEquals("thinking", r.messages.get(1).role);
        assertEquals("让我想想", r.messages.get(1).content);
        assertEquals("assistant", r.messages.get(2).role);
        assertEquals("今天银川晴", r.messages.get(2).content);

        // 工具调用配对（start → complete 通过 toolCallId 合并）
        assertEquals(1, r.toolCalls.size());
        SessionReplay.ReplayToolCall tool = r.toolCalls.get(0);
        assertEquals("t1", tool.toolCallId);
        assertEquals("get_weather", tool.name);
        assertEquals("{\"city\":\"银川\"}", tool.args);
        assertTrue(tool.success);
        assertEquals("晴 25°C", tool.result);

        // 统计
        assertEquals(1, r.stats.turnCount);
        assertEquals(1, r.stats.toolCallCount);
        assertEquals(1, r.stats.assistantMessageCount);
        assertEquals(1, r.stats.userMessageCount);
        assertEquals(1, r.stats.thinkingCount);
        assertEquals(SessionReplay.Status.COMPLETED, r.stats.status);
    }

    @Test
    public void failed_turn_yields_failed_status() {
        List<SessionEvent> events = new ArrayList<>();
        events.add(ev(1, SessionEventType.TURN_STARTED, null));
        events.add(ev(2, SessionEventType.TURN_FAILED,
                str(SessionEventPayload.error("超时"))));
        SessionReplay.ReplayResult r = SessionReplay.replay(events);
        assertEquals(SessionReplay.Status.FAILED, r.stats.status);
    }

    @Test
    public void cancelled_turn_yields_cancelled_status() {
        List<SessionEvent> events = new ArrayList<>();
        events.add(ev(1, SessionEventType.TURN_STARTED, null));
        events.add(ev(2, SessionEventType.TURN_CANCELLED, null));
        SessionReplay.ReplayResult r = SessionReplay.replay(events);
        assertEquals(SessionReplay.Status.CANCELLED, r.stats.status);
    }

    @Test
    public void tool_start_without_complete_does_not_count_as_tool_call() {
        List<SessionEvent> events = new ArrayList<>();
        events.add(ev(1, SessionEventType.TOOL_CALL_STARTED,
                str(SessionEventPayload.toolCallStarted("t9", "x", "{}"))));
        SessionReplay.ReplayResult r = SessionReplay.replay(events);
        assertEquals(0, r.stats.toolCallCount);
        assertTrue(r.toolCalls.isEmpty());
    }

    @Test
    public void replay_to_text_renders_messages() {
        List<SessionEvent> events = new ArrayList<>();
        events.add(ev(1, SessionEventType.USER_MESSAGE,
                str(SessionEventPayload.userMessage("问"))));
        events.add(ev(2, SessionEventType.ASSISTANT_MESSAGE,
                str(SessionEventPayload.assistantMessage("答"))));
        SessionReplay.ReplayResult r = SessionReplay.replay(events);
        String text = r.toText();
        assertTrue(text.contains("user: 问"));
        assertTrue(text.contains("assistant: 答"));
    }

    @Test
    public void malformed_payload_does_not_crash_replay() {
        // 载荷不可读时：事件本身保留（按类型计数），内容降级为空串，绝不影响回放主流程
        List<SessionEvent> events = new ArrayList<>();
        events.add(ev(1, SessionEventType.USER_MESSAGE, "{not json"));
        SessionReplay.ReplayResult r = SessionReplay.replay(events);
        assertEquals(1, r.stats.userMessageCount);
        assertEquals("", r.messages.get(0).content);
    }
}
