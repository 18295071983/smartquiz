package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 放大档宿主的副本纪律（2026-10-24）。
 *
 * <p>唯一必须钉住的事实：宿主专用键只能长在副本上。气泡里那份 {@code ComponentData.props}
 * 是活的实例、且整棵会随会话落盘（{@code ChatHistoryManager} 的 ComponentDataAdapter），
 * 一旦被 put 进 {@code fillHeight}，历史恢复后每张卡都自带放大档、还会被 registry
 * 当 style/actions 误读。</p>
 */
public class ComponentFocusHostTest {

    private static ComponentData liveCard() {
        JSONObject p = new JSONObject();
        try {
            p.put("title", "投票页");
            p.put("url", "file:///storage/emulated/0/Download/OilQuiz/agent_workspace/files/vote.html");
            p.put("maxHeight", 620);
            p.put("actions", new JSONArray().put(new JSONObject()
                    .put("label", "提交").put("action", "callback")
                    .put("component_id", "chat_1_1")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ComponentData d = ComponentData.of("html", p);
        d.id = "T9-C2";
        d.internalTemplate = true;
        return d;
    }

    @Test
    public void focusCloneAddsHostKeyWithoutTouchingLiveProps() {
        ComponentData src = liveCard();

        ComponentData clone = ComponentFocusHost.focusClone(src);

        assertNotSame("props 必须是新实例", src.props, clone.props);
        assertTrue("副本带放大档标记", clone.props.optBoolean("fillHeight", false));
        assertFalse("气泡那份 props 绝不能被污染（会跟着落盘）",
                src.props.has("fillHeight"));
        assertEquals("原有字段照搬", "投票页", clone.props.optString("title"));
        assertEquals(620, clone.props.optInt("maxHeight"));
        assertTrue("actions 要带过去，否则全屏里 submit 取不到 component_id",
                clone.props.optJSONArray("actions").length() == 1);

        // 副本上改值不许回写原件（真深拷，不是共享内部 map）
        try {
            clone.props.put("title", "改过的标题");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("投票页", src.props.optString("title"));
    }

    @Test
    public void focusCloneCarriesIdAndTrustMarker() {
        ComponentData src = liveCard();
        ComponentData clone = ComponentFocusHost.focusClone(src);

        assertEquals("T9-C2", clone.id);
        assertEquals("html", clone.type);
        assertTrue("internalTemplate 丢了会掉沙箱档，mermaid/katex 渲不出",
                clone.internalTemplate);
    }

    @Test
    public void focusCloneSurvivesMissingProps() {
        assertNull(ComponentFocusHost.focusClone(null));

        ComponentData bare = new ComponentData("html", null);
        ComponentData clone = ComponentFocusHost.focusClone(bare);
        assertEquals("html", clone.type);
        assertTrue(clone.props.optBoolean("fillHeight", false));
    }

    @Test
    public void hostKeyIsOnlyReadableFromClone() {
        ComponentData src = liveCard();
        ComponentData clone = ComponentFocusHost.focusClone(src);

        assertFalse(src.props.optBoolean(ComponentFocusHost.PROP_FILL_HEIGHT, false));
        assertTrue(clone.props.optBoolean(ComponentFocusHost.PROP_FILL_HEIGHT, false));
    }
}
