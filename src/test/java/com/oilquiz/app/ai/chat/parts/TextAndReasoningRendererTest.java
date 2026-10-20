package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.MarkdownWebView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Text/Reasoning 两个 renderer 的类型分派与视图结构（parts 架构升级 P2）。
 *
 * <p>只验 canRender 与 createView 的视图形状（native→TextView、web→MarkdownWebView、
 * reasoning→折叠行）；实际 markdown 内容由 {@link com.oilquiz.app.ai.chat.render.MarkdownRenderer}
 * 负责、由引擎用假 renderer 覆盖绑定计数，此处不重复触发 Markwon，避免与旧路径耦合的脆弱断言。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class TextAndReasoningRendererTest {

    private static AssistantTurn.Part part(String type, String text) {
        AssistantTurn.Part p = new AssistantTurn.Part();
        p.id = "p-" + type;
        p.type = type;
        p.text = text;
        return p;
    }

    @Test
    public void textRendererDispatchesNativeAndWeb() {
        Context ctx = ApplicationProvider.getApplicationContext();
        TextPartRenderer r = new TextPartRenderer();
        ViewGroup parent = new LinearLayout(ctx);

        assertTrue(r.canRender(part(AssistantTurn.Part.TYPE_TEXT, "t")));
        assertFalse(r.canRender(part(AssistantTurn.Part.TYPE_REASONING, "t")));

        View nativeView = r.createView(new TestHost(ctx, false), parent);
        assertTrue("webMarkdownEnabled=false → TextView", nativeView instanceof TextView);

        View webView = r.createView(new TestHost(ctx, true), parent);
        assertTrue("webMarkdownEnabled=true → MarkdownWebView", webView instanceof MarkdownWebView);

        assertNotNull(nativeView.getLayoutParams());
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, nativeView.getLayoutParams().width);
    }

    @Test
    public void reasoningRendererBuildsCollapsedRow() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ReasoningPartRenderer r = new ReasoningPartRenderer();
        ViewGroup parent = new LinearLayout(ctx);

        assertTrue(r.canRender(part(AssistantTurn.Part.TYPE_REASONING, "t")));
        assertFalse(r.canRender(part(AssistantTurn.Part.TYPE_TEXT, "t")));

        View view = r.createView(new TestHost(ctx, false), parent);
        assertTrue(view instanceof LinearLayout);
        LinearLayout row = (LinearLayout) view;
        assertEquals("header + body 两段", 2, row.getChildCount());
        assertTrue(row.getChildAt(0) instanceof TextView);
        assertTrue(row.getChildAt(1) instanceof TextView);
        assertEquals("思考默认折叠", View.GONE, row.getChildAt(1).getVisibility());
    }

    private static final class TestHost implements PartHost {
        private final Context ctx;
        private final boolean web;
        TestHost(Context ctx, boolean web) {
            this.ctx = ctx;
            this.web = web;
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
            return web;
        }
        @Override
        public void invalidateSize() {
        }
    }
}
