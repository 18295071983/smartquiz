package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.AgentPanelView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowWebView;

import java.util.Arrays;

/**
 * WebView 组件接线（PoC，2026-11-18）：开关打开时 thinking part 换 WebView 渲染，
 * 且载荷真的注入到了页面的 {@code qzAgent.update}。
 */
@RunWith(RobolectricTestRunner.class)
public class AgentPanelViewWiringTest {

    private Context ctx;
    private ThinkingPartRenderer renderer;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
        renderer = new ThinkingPartRenderer();
        AgentPanelBudget.resetForTest();
        AgentPanelWebFlag.setEnabled(ctx, true);
    }

    @After
    public void tearDown() {
        AgentPanelWebFlag.setEnabled(ctx, false); // 别影响别的用例
        AgentPanelBudget.resetForTest();
    }

    private static AssistantTurn.Part thinking() {
        AssistantTurn.Part r = AssistantTurn.Part.reasoning("r1", "先想想", 1L);
        AssistantTurn.Part call = AssistantTurn.Part.toolCall(
                "t1", "c1", "search", "{\"q\":\"x\"}", "success", 2L);
        AssistantTurn.Part g = new AssistantTurn.Part();
        g.id = "r1:cot";
        g.type = AssistantTurn.Part.TYPE_THINKING;
        g.group = Arrays.asList(r, AssistantTurn.Part.toolGroup(
                "g1", new java.util.ArrayList<>(java.util.Collections.singletonList(call)), 2L));
        return g;
    }

    @Test
    public void flagOnRendersWebPanelInsteadOfNativeShell() {
        AgentPanelView panel = (AgentPanelView) renderer.createView(new Host(ctx), new LinearLayout(ctx));
        assertNotNull("开关打开应出 AgentPanelView", panel);
        assertEquals("只挂一个 WebView（没有原生折叠头）", 1, panel.getChildCount());
    }

    @Test
    public void updateIsInjectedIntoPanelAfterPageReady() {
        AgentPanelView panel = (AgentPanelView) renderer.createView(new Host(ctx), new LinearLayout(ctx));
        renderer.bindView(panel, thinking(), new Host(ctx));

        WebView inner = (WebView) panel.getChildAt(0);
        ShadowWebView shadow = Shadows.shadowOf(inner);
        // 页面未就绪时 update 会被暂存；onPageFinished 后补发
        assertFalse("未就绪前不注入（避免丢帧）", shadow.getLastEvaluatedJavascript() != null
                && shadow.getLastEvaluatedJavascript().contains("qzAgent"));
        shadow.getWebViewClient().onPageFinished(inner, "file:///android_asset/agent_panel/agent_panel.html");

        String js = shadow.getLastEvaluatedJavascript();
        assertNotNull("页面就绪后应把载荷注入页面", js);
        assertTrue("注入入口是 qzAgent.update：" + js, js.contains("qzAgent"));
        assertTrue("载荷里应有 thinking 步：" + js, js.contains("\"kind\":\"thinking\""));
        assertTrue("载荷里应有工具步：" + js, js.contains("\"kind\":\"tool\""));
        assertTrue("载荷里应带 label：" + js, js.contains("深度思考"));
    }

    /** 释放要幂等：renderer 的 releaseView 与组件深度释放两条路都会调，不能重复扣额度。 */
    @Test
    public void releaseIsIdempotentAndReturnsBudget() {
        AgentPanelView panel = (AgentPanelView) renderer.createView(new Host(ctx), new LinearLayout(ctx));
        assertEquals("创建时占一个额度", 1, AgentPanelBudget.live());
        assertFalse(panel.isComponentReleased());
        WebView inner = (WebView) panel.getChildAt(0);
        ShadowWebView webShadow = Shadows.shadowOf(inner);

        panel.release();
        assertTrue(panel.isComponentReleased());
        assertEquals("释放归还额度", 0, AgentPanelBudget.live());
        assertTrue("WebView 必须真的 destroy（否则 Chromium 侧内存不会还）",
                webShadow.wasDestroyCalled());

        panel.release();
        panel.release();
        assertEquals("幂等：重复释放不能把额度压成负数", 0, AgentPanelBudget.live());
    }

    @Test
    public void budgetExhaustedFallsBackToNativeShell() {
        for (int i = 0; i < AgentPanelBudget.MAX_LIVE; i++) {
            assertTrue(AgentPanelBudget.acquire());
        }
        View fallback = renderer.createView(new Host(ctx), new LinearLayout(ctx));
        assertTrue("额度用尽必须退回原生折叠头，而不是硬造 WebView",
                fallback instanceof com.oilquiz.app.ai.chat.ui.ReasoningShellView);
        for (int i = 0; i < AgentPanelBudget.MAX_LIVE; i++) {
            AgentPanelBudget.release();
        }
    }

    private static final class Host implements PartHost {
        private final Context ctx;

        Host(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public Context context() {
            return ctx;
        }

        @Override
        public int availableWidth() {
            return 1080;
        }

        @Override
        public ChatMessage message() {
            return null;
        }

        @Override
        public boolean webMarkdownEnabled() {
            return false;
        }

        @Override
        public boolean dark() {
            return false;
        }

        @Override
        public String markdownThemeVars() {
            return "";
        }

        @Override
        public void renderMarkdown(TextView target, String markdown) {
            target.setText(markdown);
        }

        @Override
        public void onApprovalDecision(String partId, String toolCallId, boolean approved) {
        }

        @Override
        public void invalidateSize() {
        }
    }
}
