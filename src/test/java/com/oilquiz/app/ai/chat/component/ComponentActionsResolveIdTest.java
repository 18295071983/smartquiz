package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@link ComponentActions#resolveComponentId} 的取址口径（html 卡 submit 设计的第一步：纯抽取）。
 *
 * <p>这套逻辑原先只写在 {@code LayoutCardView} 的渲染里（取第一个非空
 * {@code props.actions[].component_id}）。抽成公共方法后 layout 卡与 html 卡共用，
 * 本测试钉住的正是"共用口径不许漂"：</p>
 * <ul>
 *   <li>回传地址是 {@code actions[].component_id}，<b>不是</b> {@code ComponentData.id}
 *       （后者是 COMPONENT 子 id，喂进 {@code notifyResult} 找不到回调、会静默失败）；</li>
 *   <li>没有 {@code actions} 的卡不是交互组件，返回 null（{@code submit} 必须 no-op）。</li>
 * </ul>
 */
public class ComponentActionsResolveIdTest {

    @Test
    public void returnsFirstNonEmptyActionComponentId() {
        JSONObject props = propsWithActions(
                action("component_id", ""),       // 空 id 跳过
                action("component_id", "chat_1"), // 命中
                action("component_id", "chat_2"));// 后面的不再取
        assertEquals("chat_1", ComponentActions.resolveComponentId(props));
    }

    @Test
    public void nullWhenNoActionsOrAllEmpty() {
        assertNull(ComponentActions.resolveComponentId(new JSONObject()));
        assertNull(ComponentActions.resolveComponentId(obj("actions", new JSONArray())));
        assertNull(ComponentActions.resolveComponentId(propsWithActions(
                action("component_id", ""), action("component_id", null))));
    }

    @Test
    public void nullForMissingOrMalformedInput() {
        assertNull(ComponentActions.resolveComponentId((JSONObject) null));
        assertNull(ComponentActions.resolveComponentId((ComponentData) null));
        // actions 不是数组（模型给坏数据）：不抛，按"无地址"处理
        assertNull(ComponentActions.resolveComponentId(obj("actions", "chat_1")));
    }

    /** ComponentData.id 不参与取址：即使有子 id，没有 actions 也拿不到回传地址。 */
    @Test
    public void componentDataIdIsNotTheRoundTripAddress() {
        ComponentData d = ComponentData.of("html", new JSONObject());
        d.id = "T1-C1";
        assertNull(ComponentActions.resolveComponentId(d));

        ComponentData interactive = ComponentData.of("html",
                propsWithActions(action("component_id", "chat_9")));
        interactive.id = "T1-C1";
        assertEquals("chat_9", ComponentActions.resolveComponentId(interactive));
    }

    @Test
    public void componentDataOverloadDelegatesToProps() {
        ComponentData d = ComponentData.of("layout",
                propsWithActions(action("label", "提交", "component_id", "chat_7")));
        assertEquals("chat_7", ComponentActions.resolveComponentId(d));
    }

    // ---- helpers ----

    private static JSONObject action(Object... kv) {
        return obj(kv);
    }

    private static JSONObject propsWithActions(JSONObject... actions) {
        JSONArray arr = new JSONArray();
        for (JSONObject a : actions) {
            arr.put(a);
        }
        return obj("actions", arr);
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                if (kv[i + 1] == null) {
                    o.put(String.valueOf(kv[i]), JSONObject.NULL);
                } else {
                    o.put(String.valueOf(kv[i]), kv[i + 1]);
                }
            } catch (org.json.JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return o;
    }
}
