package com.oilquiz.app.ai.sessionlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 会话事件载荷构造器测试。
 */
public class SessionEventPayloadTest {

    @Test
    public void user_message_has_role_and_content() {
        JSONObject o = SessionEventPayload.userMessage("题目？");
        assertEquals("user", o.optString("role"));
        assertEquals("题目？", o.optString("content"));
    }

    @Test
    public void null_content_is_coerced_to_empty() {
        JSONObject o = SessionEventPayload.userMessage(null);
        assertEquals("", o.optString("content"));
    }

    @Test
    public void assistant_message_has_role_and_content() {
        JSONObject o = SessionEventPayload.assistantMessage("答案");
        assertEquals("assistant", o.optString("role"));
        assertEquals("答案", o.optString("content"));
    }

    @Test
    public void tool_call_started_carries_id_name_args() {
        JSONObject o = SessionEventPayload.toolCallStarted("t1", "get_weather", "{\"city\":\"x\"}");
        assertEquals("t1", o.optString("toolCallId"));
        assertEquals("get_weather", o.optString("name"));
        assertEquals("{\"city\":\"x\"}", o.optString("args"));
    }

    @Test
    public void tool_call_completed_carries_success_and_result() {
        JSONObject ok = SessionEventPayload.toolCallCompleted("t1", "get_weather", true, "晴");
        assertTrue(ok.optBoolean("success"));
        assertEquals("晴", ok.optString("result"));

        JSONObject fail = SessionEventPayload.toolCallCompleted("t2", "x", false, "网络错误");
        assertFalse(fail.optBoolean("success"));
        assertEquals("网络错误", fail.optString("result"));
    }

    @Test
    public void error_payload_has_error_field() {
        JSONObject o = SessionEventPayload.error("boom");
        assertEquals("boom", o.optString("error"));
    }

    @Test
    public void model_and_end_reason_helpers() {
        assertEquals("deepseek-chat", SessionEventPayload.model("deepseek-chat").optString("model"));
        assertEquals("done", SessionEventPayload.endReason("done").optString("reason"));
    }
}
