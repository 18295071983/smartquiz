package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * `tool/call` 的字段容错（2026-11-13）。
 *
 * <p>背景：DSH 内部、插件转发、不同版本对"调用 id"有四种拼写。旧实现只认 {@code callId}，
 * 换一种拼写整张工具卡就消失（`if (callId.isEmpty()) break`）。这里把四种拼写与"真的没有"
 * 两种结局都钉住。</p>
 */
public class DshTimelineToolCallFieldTest {

    private static DshEvent event(String type, int seq, JSONObject data) {
        JSONObject raw = new JSONObject();
        try {
            raw.put("v", 2);
            raw.put("seq", seq);
            raw.put("type", type);
            raw.put("data", data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return DshEvent.from(raw);
    }

    private static JSONObject data(String key, String callId) {
        JSONObject d = new JSONObject();
        try {
            d.put("name", "Bash");
            d.put("arguments", "{}");
            if (key != null) d.put(key, callId);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static boolean reducesToCard(String key) {
        DshTimeline timeline = new DshTimeline();
        DshTimeline.Update u = timeline.reduce(event("tool/call", 1, data(key, "call-1")));
        return u.cardsChanged;
    }

    @Test
    public void acceptsCamelCaseCallId() {
        assertTrue(reducesToCard("callId"));
    }

    @Test
    public void acceptsSnakeCaseCallId() {
        assertTrue(reducesToCard("call_id"));
    }

    @Test
    public void acceptsToolCallId() {
        assertTrue(reducesToCard("toolCallId"));
    }

    @Test
    public void acceptsSnakeCaseToolCallId() {
        assertTrue(reducesToCard("tool_call_id"));
    }

    @Test
    public void missingCallIdProducesNoCard() {
        assertFalse(reducesToCard(null));
    }
}
