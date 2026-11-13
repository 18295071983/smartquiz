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
 * P2（2026-11-13）：官方载荷里的动态版式 → {@code layout} 组件。
 *
 * <p>通道是 {@code tool/result.meta}（工具通过 {@code output.presentationMeta} 派生）：
 * 插件不能发自定义持久事件，但能随工具结果带 meta —— 于是"不发版换界面"是合规的。
 * 渲染交给已注册的 {@code LayoutCardView}，未知图元/坏节点由渲染层兜底。</p>
 */
public class DshProjectionLayoutTest {

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

    private static ChatMessage project(JSONObject meta) {
        DshTimeline timeline = new DshTimeline();
        JSONObject call = new JSONObject();
        try {
            call.put("callId", "c-9");
            call.put("name", "ask_form");
            call.put("arguments", "{}");
            timeline.reduce(event("tool/call", 1, call));

            JSONObject res = new JSONObject();
            JSONObject msg = new JSONObject();
            msg.put("toolCallId", "c-9");
            msg.put("isError", false);
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", "ok"));
            msg.put("content", content);
            res.put("message", msg);
            if (meta != null) res.put("meta", meta);
            timeline.reduce(event("tool/result", 2, res));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ChatMessage m = ChatMessage.createAIMessage("T1-A1", "", 1000L, null, 0, 0);
        DshProjection.project(m, timeline, DshProjection.IdSource.NONE, 1000L);
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
    public void metaLayoutBecomesLayoutComponent() {
        JSONObject layout = new JSONObject();
        JSONObject meta = new JSONObject();
        try {
            JSONArray children = new JSONArray();
            children.put(new JSONObject().put("type", "text").put("text", "请选择"));
            JSONArray actions = new JSONArray();
            actions.put(new JSONObject().put("id", "ok").put("label", "确定"));
            layout.put("type", "column");
            layout.put("children", children);
            layout.put("actions", actions);
            meta.put("layout", layout);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        ChatMessage m = project(meta);
        ComponentData layoutComp = find(m, "layout");
        assertNotNull("meta.layout 应产出 layout 组件", layoutComp);
        assertEquals("来源必须是 dsh，动作才会回传电脑端", "dsh", layoutComp.source);
        assertNotNull(layoutComp.props);
        assertEquals("column", layoutComp.props.optString("type"));
        assertNotNull("children 必须原样透传", layoutComp.props.optJSONArray("children"));
        assertNotNull("actions 必须原样透传（点击回传靠它）", layoutComp.props.optJSONArray("actions"));
        assertNotNull("工具卡本身也应保留", find(m, "tool_call"));
    }

    @Test
    public void noLayoutMetaProducesNoComponent() {
        ChatMessage m = project(null);
        assertNull(find(m, "layout"));
    }

    @Test
    public void emptyLayoutObjectIgnored() {
        JSONObject meta = new JSONObject();
        try {
            meta.put("layout", new JSONObject());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertNull("空 layout 不应产生空组件", find(project(meta), "layout"));
    }

    @Test
    public void nonLayoutMetaStillKeptOnToolComponent() {
        JSONObject meta = new JSONObject();
        try {
            meta.put("diffs", new JSONArray());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ChatMessage m = project(meta);
        ComponentData tool = find(m, "tool_call");
        assertNotNull(tool);
        assertTrue("非 layout 的 meta 仍应留在工具卡 props 里",
                tool.props.optJSONObject("meta") != null);
        assertNull(find(m, "layout"));
    }
}
