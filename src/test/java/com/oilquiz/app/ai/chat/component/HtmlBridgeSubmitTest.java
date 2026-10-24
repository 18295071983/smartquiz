package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML 卡页面内提交 → Agent 回传通道（html submit 设计第 2 步）。
 *
 * <p>要钉住的是三条边界，缺一条方案就不成立：</p>
 * <ol>
 *   <li><b>只走既有通道</b>：{@code submit} 必须落到 {@link ComponentActions} 的按 id 回调体系
 *       （和 footer 按钮、layout 卡同一个 {@code get_result} 回收口），不另造第二套；</li>
 *   <li><b>id 由 native 绑定</b>：JS 只能传 payload，不能指定往哪个组件写；非交互卡（没 actions）
 *       或开关关时必须静默 no-op；</li>
 *   <li><b>payload 有上限且不透明</b>：超限拒收，不解析、不执行。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class HtmlBridgeSubmitTest {

    private static final String CID = "chat_test_1";

    private Context ctx;
    private final List<String[]> captured = new ArrayList<>();

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        captured.clear();
        ComponentActions.registerComponentCallback(CID,
                (id, value) -> captured.add(new String[]{id, value}));
    }

    @After
    public void tearDown() {
        ComponentActions.unregisterComponentCallback(CID);
    }

    @Test
    public void submitRoutesToExistingResultChannel() {
        new HtmlCardView.HtmlJsBridge(ctx, CID)
                .submit("{\"action\":\"pick\",\"point\":\"A3\"}");

        assertEquals("应恰好派发一次", 1, captured.size());
        assertEquals(CID, captured.get(0)[0]);
        assertEquals("payload 原样透传，不加工", "{\"action\":\"pick\",\"point\":\"A3\"}", captured.get(0)[1]);
    }

    @Test
    public void plainValuePayloadAlsoPassesThrough() {
        new HtmlCardView.HtmlJsBridge(ctx, CID).submit("like");
        assertEquals(1, captured.size());
        assertEquals("like", captured.get(0)[1]);
    }

    /** 非交互卡（创建时没带 actions）拿不到地址：必须 no-op，不能误投别的组件。 */
    @Test
    public void nullOrEmptyComponentIdIsSilentNoOp() {
        new HtmlCardView.HtmlJsBridge(ctx, null).submit("x");
        new HtmlCardView.HtmlJsBridge(ctx, "").submit("x");
        new HtmlCardView.HtmlJsBridge(ctx).submit("x"); // 旧单参构造：今天的行为
        assertTrue("无地址时不该派发", captured.isEmpty());
    }

    @Test
    public void emptyOrNullPayloadIsIgnored() {
        HtmlCardView.HtmlJsBridge b = new HtmlCardView.HtmlJsBridge(ctx, CID);
        b.submit(null);
        b.submit("");
        assertTrue(captured.isEmpty());
    }

    @Test
    public void oversizedPayloadIsRejectedNotDispatched() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < HtmlCardView.MAX_SUBMIT_CHARS + 1; i++) {
            big.append('a');
        }
        new HtmlCardView.HtmlJsBridge(ctx, CID).submit(big.toString());
        assertTrue("超限 payload 不该进结果槽", captured.isEmpty());

        // 边界：正好等于上限应放行
        new HtmlCardView.HtmlJsBridge(ctx, CID).submit(
                repeat('a', HtmlCardView.MAX_SUBMIT_CHARS));
        assertEquals("等于上限要放行", 1, captured.size());
    }

    /** 桥的挂载档位没被扩大：仍然只有 LOCAL_FILE 有桥。 */
    @Test
    public void bridgeStillOnlyAllowedForLocalFileTier() {
        assertTrue(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.LOCAL_FILE));
        assertFalse(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.INLINE_UNTRUSTED));
        assertFalse(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.INTERNAL_TEMPLATE));
        assertFalse(HtmlSandbox.allowsBridge(HtmlSandbox.Trust.REMOTE_PAGE));
    }

    /**
     * 开关出厂默认是<b>开</b>（2026-10-24 真机端到端验通后翻的；建的时候默认 false 是为了
     * "JS↔native 只能真机验"这段试验期）。翻默认的同时要把"能关掉"钉住——
     * 这是能力增量，出问题时把这条 pref 置 false 就是逐字节的旧行为。
     */
    @Test
    public void submitFlagDefaultsOnAndStillSwitchable() {
        assertTrue("出厂默认必须是开", HtmlSubmitFlag.enabled(ctx));
        // 读不到偏好（Context 为空）也按默认档，不留两种相反的兜底
        assertTrue(HtmlSubmitFlag.enabled(null));

        HtmlSubmitFlag.setEnabled(ctx, false);
        assertFalse("关掉即回到 submit 静默 no-op 的旧行为", HtmlSubmitFlag.enabled(ctx));
        HtmlSubmitFlag.setEnabled(ctx, true);
        assertTrue(HtmlSubmitFlag.enabled(ctx));
    }

    /** 开关开着时，地址从 actions[].component_id 取（与非交互卡区分的依据）。 */
    @Test
    public void interactiveHtmlCardHasRoundTripAddress() {
        ComponentData plain = ComponentData.of("html", obj("html", "<p>hi</p>"));
        assertNull(ComponentActions.resolveComponentId(plain));

        ComponentData interactive = ComponentData.of("html",
                obj("html", "<p>hi</p>", "actions",
                        new JSONArray().put(obj("label", "提交", "component_id", CID))));
        assertEquals(CID, ComponentActions.resolveComponentId(interactive));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            } catch (org.json.JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return o;
    }
}
