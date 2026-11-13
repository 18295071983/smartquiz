package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * `tool/result.meta` 的投影（2026-11-13，B17）。
 *
 * <p>DSH 官方用它携带"可回放的卡片数据"（diff/read/search/web…），replay 时复现同一张卡。
 * 手机此前完全不读，等于把官方载荷丢了；这里钉住两件事：</p>
 * <ul>
 *   <li>有 meta → 工具组件 props 里带 meta，内容原样；</li>
 *   <li>无 meta → 不塞空的 meta 键（避免落盘里多出无用字段）。</li>
 * </ul>
 */
public class DshProjectionMetaTest {

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

    private static JSONObject callData(String callId) {
        JSONObject d = new JSONObject();
        try {
            d.put("callId", callId);
            d.put("name", "write");
            d.put("arguments", "{}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static JSONObject resultData(String callId, JSONObject meta) {
        JSONObject d = new JSONObject();
        try {
            JSONObject msg = new JSONObject();
            msg.put("toolCallId", callId);
            msg.put("isError", false);
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", "done"));
            msg.put("content", content);
            d.put("message", msg);
            if (meta != null) d.put("meta", meta);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static ComponentData toolComponentOf(ChatMessage msg) {
        assertNotNull("投影后应有 components", msg.components);
        for (ComponentData c : msg.components) {
            if (c != null && "tool_call".equals(c.type)) return c;
        }
        return null;
    }

    private static ChatMessage project(JSONObject meta) {
        DshTimeline timeline = new DshTimeline();
        timeline.reduce(event("tool/call", 1, callData("c-1")));
        timeline.reduce(event("tool/result", 2, resultData("c-1", meta)));
        ChatMessage msg = ChatMessage.createAIMessage("T1-A1", "", 1000L, null, 0, 0);
        DshProjection.project(msg, timeline, DshProjection.IdSource.NONE, 1000L);
        return msg;
    }

    @Test
    public void metaIsProjectedIntoProps() throws Exception {
        JSONObject meta = new JSONObject();
        JSONArray diffs = new JSONArray();
        diffs.put(new JSONObject().put("path", "a.txt").put("oldText", "1").put("newText", "2"));
        meta.put("diffs", diffs);

        ComponentData c = toolComponentOf(project(meta));
        assertNotNull("应投影出 tool_call 组件", c);
        assertNotNull("组件 props 应带 meta", c.props.optJSONObject("meta"));
        JSONArray got = c.props.optJSONObject("meta").optJSONArray("diffs");
        assertNotNull(got);
        assertEquals(1, got.length());
        assertEquals("a.txt", got.optJSONObject(0).optString("path"));
    }

    @Test
    public void absentMetaLeavesNoKey() {
        ComponentData c = toolComponentOf(project(null));
        assertNotNull(c);
        assertNull("没有 meta 时不应塞空键", c.props.optJSONObject("meta"));
        assertEquals("success", c.props.optString("status"));
        assertTrue(c.props.optString("result").length() > 0);
    }
}
