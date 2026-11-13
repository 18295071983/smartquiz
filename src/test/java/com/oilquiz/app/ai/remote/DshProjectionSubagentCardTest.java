package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * B15 durable 的投影侧（2026-11-13）：子代理轨迹 → {@code subagent_card} 组件。
 *
 * <p>关键点：① 组件带 {@code source=dsh}（动作回传按来源路由）；② steps 原样进 props；
 * ③ props 能持久化往返（组件随消息落盘，重进会话仍是同一张卡）。</p>
 */
public class DshProjectionSubagentCardTest {

    /** 简单 id 源：与 Activity 的 dshComponentId 一样返回稳定非空 id */
    private static final DshProjection.IdSource IDS = new DshProjection.IdSource() {
        @Override
        public String idFor(String key) {
            return "T1-" + key;
        }
    };

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

    private static JSONObject toolActivity(String childId, String callId, String tool, String args) {
        JSONObject d = new JSONObject();
        try {
            d.put("child_id", childId);
            d.put("label", "子代理A");
            d.put("kind", "tool");
            d.put("callId", callId);
            d.put("tool", tool);
            d.put("args", args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static JSONObject resultActivity(String childId, String callId, boolean error, String text) {
        JSONObject d = new JSONObject();
        try {
            d.put("child_id", childId);
            d.put("label", "子代理A");
            d.put("kind", "tool-result");
            d.put("toolCallId", callId);
            d.put("error", error);
            d.put("text", text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static ChatMessage project() {
        DshTimeline t = new DshTimeline();
        t.reduce(event("subagent/activity", 1, toolActivity("c1", "call-1", "read_file", "/a.txt")));
        t.reduce(event("subagent/activity", 2, resultActivity("c1", "call-1", false, "12 lines")));
        t.reduce(event("subagent/activity", 3, toolActivity("c1", "call-2", "grep", "TODO")));
        ChatMessage m = ChatMessage.createAIMessage("T1-A1", "", 1000L, null, 0, 0);
        DshProjection.project(m, t, IDS, 1000L);
        return m;
    }

    private static ComponentData find(ChatMessage m, String type) {
        if (m.components == null) return null;
        for (ComponentData c : m.components) {
            if (c != null && type.equals(c.type)) return c;
        }
        return null;
    }

    @Test
    public void projectsOneCardPerChildWithSteps() {
        ComponentData card = find(project(), "subagent_card");
        assertNotNull("应投影出 subagent_card", card);
        assertEquals("来源必须是 dsh", "dsh", card.source);
        assertEquals("子代理A", card.props.optString("label"));
        assertNotNull("id 必须非空（适配器按 id 定位）", card.id);
        assertTrue("id 应带回合前缀：" + card.id, card.id.startsWith("T1-"));

        JSONArray steps = card.props.optJSONArray("steps");
        assertNotNull(steps);
        assertEquals(2, steps.length());
        JSONObject first = steps.optJSONObject(0);
        assertEquals("read_file", first.optString("tool"));
        assertEquals("success", first.optString("status"));
        assertEquals("12 lines", first.optString("result"));
        assertEquals("grep", steps.optJSONObject(1).optString("tool"));
        assertEquals("running", steps.optJSONObject(1).optString("status"));
    }

    @Test
    public void cardSurvivesPersistableJsonRoundTrip() {
        ComponentData card = find(project(), "subagent_card");
        assertNotNull(card);
        String json = card.toPersistableJson();
        assertNotNull(json);

        ComponentData back = ComponentData.fromPersistableJson(json);
        assertNotNull(back);
        assertEquals("subagent_card", back.type);
        assertEquals("dsh", back.source);
        assertEquals(2, back.props.optJSONArray("steps").length());
        assertEquals("read_file", back.props.optJSONArray("steps")
                .optJSONObject(0).optString("tool"));
    }

    @Test
    public void noSubagentsNoCard() {
        DshTimeline t = new DshTimeline();
        ChatMessage m = ChatMessage.createAIMessage("T1-A2", "", 1000L, null, 0, 0);
        DshProjection.project(m, t, IDS, 1000L);
        assertEquals("没有子代理时不应多出空卡", null, find(m, "subagent_card"));
    }
}
