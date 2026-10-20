package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TurnPartsHost 增量协调引擎（parts 架构升级 P2）。
 *
 * <p>这是本次升级的核心保证：证明它做到了"复用同 id / 指纹相同不重绑 / 删除必 releaseView /
 * 支持重排不重建 / 未知类型跳过"，而不是文档 §3 禁止的裸 {@code for(Part) removeAllViews}。
 * 用真实 {@link LinearLayout} + 计数型假 renderer，绕开 Markwon/WebView，纯验算法。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class TurnPartsHostTest {

    private Context ctx;
    private LinearLayout container;
    private FakeRenderer textRenderer;
    private TurnPartsHost host;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        container = new LinearLayout(ctx);
        textRenderer = new FakeRenderer(AssistantTurn.Part.TYPE_TEXT);
        PartRendererRegistry registry = new PartRendererRegistry().register(textRenderer);
        host = new TurnPartsHost(container, registry, new FakeHost(ctx));
    }

    private static AssistantTurn.Part textPart(String id, String text) {
        return AssistantTurn.Part.text(id, text, 1L);
    }

    @Test
    public void rebindingIdenticalPartsDoesNotRecreateOrRebind() {
        List<AssistantTurn.Part> parts = Arrays.asList(
                textPart("a", "正文A"), textPart("b", "正文B"), textPart("c", "正文C"));

        host.bind(parts);
        assertEquals("首次三份各创建一次", 3, host.createCount);
        assertEquals("首次三份各绑定一次", 3, host.bindCount);
        assertEquals(3, container.getChildCount());

        host.bind(parts); // 完全相同
        assertEquals("相同 id 不应重建", 3, host.createCount);
        assertEquals("指纹相同不应重绑", 3, host.bindCount);
        assertEquals(3, container.getChildCount());
    }

    @Test
    public void viewIsReusedForSameIdAcrossBinds() {
        host.bind(Arrays.asList(textPart("a", "x")));
        View first = host.views().get("a");
        host.bind(Arrays.asList(textPart("a", "x")));
        assertSame("同 id 复用同一个 View 实例", first, host.views().get("a"));
    }

    @Test
    public void contentChangeRebindsOnlyTheChangedPart() {
        host.bind(Arrays.asList(textPart("a", "A1"), textPart("b", "B1")));
        int bindsBefore = host.bindCount;

        host.bind(Arrays.asList(textPart("a", "A1"), textPart("b", "B2-changed")));

        assertEquals("内容变化不应触发重建", 2, host.createCount);
        assertEquals("仅 b 指纹变化 → 只多重绑一次", bindsBefore + 1, host.bindCount);
    }

    @Test
    public void removedPartIsReleasedAndRemoved() {
        host.bind(Arrays.asList(textPart("a", "A"), textPart("b", "B"), textPart("c", "C")));
        int releasesBefore = host.releaseCount;
        View bView = host.views().get("b");

        host.bind(Arrays.asList(textPart("a", "A"), textPart("c", "C")));

        assertEquals("消失的 part 释放一次", releasesBefore + 1, host.releaseCount);
        assertFalse(host.views().containsKey("b"));
        assertEquals(2, container.getChildCount());
        assertFalse("被移除的 view 不再在容器里", isInContainer(bView));
        // 其余两个不应重建
        assertEquals(3, host.createCount);
    }

    @Test
    public void reorderMovesViewsWithoutRecreating() {
        host.bind(Arrays.asList(textPart("a", "A"), textPart("b", "B"), textPart("c", "C")));
        View a = host.views().get("a");
        View b = host.views().get("b");
        View c = host.views().get("c");

        host.bind(Arrays.asList(textPart("c", "C"), textPart("a", "A"), textPart("b", "B")));

        assertEquals("重排不应重建任何视图", 3, host.createCount);
        assertSame(c, container.getChildAt(0));
        assertSame(a, container.getChildAt(1));
        assertSame(b, container.getChildAt(2));
    }

    @Test
    public void typeChangeForSameIdReleasesOldAndRebuilds() {
        PartRendererRegistry registry = new PartRendererRegistry()
                .register(new FakeRenderer(AssistantTurn.Part.TYPE_TEXT))
                .register(new FakeRenderer(AssistantTurn.Part.TYPE_REASONING));
        TurnPartsHost h = new TurnPartsHost(new LinearLayout(ctx), registry, new FakeHost(ctx));

        AssistantTurn.Part asText = AssistantTurn.Part.text("id1", "t", 1L);
        h.bind(Arrays.asList(asText));
        View textView = h.views().get("id1");

        AssistantTurn.Part asReasoning = AssistantTurn.Part.reasoning("id1", "t", 1L);
        h.bind(Arrays.asList(asReasoning));

        assertEquals("类型变化释放旧视图一次", 1, h.releaseCount);
        assertEquals("类型变化重建一次", 2, h.createCount);
        assertNotSame(textView, h.views().get("id1"));
    }

    @Test
    public void unknownTypeIsSkippedWithoutCrash() {
        AssistantTurn.Part tool = new AssistantTurn.Part();
        tool.id = "t1";
        tool.type = AssistantTurn.Part.TYPE_TOOL_CALL; // 未注册
        tool.toolCallId = "c1";

        host.bind(Arrays.asList(textPart("a", "A"), tool));

        assertEquals("只渲染已注册类型", 1, container.getChildCount());
        assertEquals(1, host.createCount);
        assertTrue(host.views().containsKey("a"));
        assertFalse(host.views().containsKey("t1"));
    }

    @Test
    public void bindNullClearsAllViewsAndReleases() {
        host.bind(Arrays.asList(textPart("a", "A"), textPart("b", "B")));

        host.bind(null);

        assertEquals(0, container.getChildCount());
        assertEquals(0, host.renderedCount());
        assertEquals(2, host.releaseCount);
    }

    private boolean isInContainer(View v) {
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) == v) return true;
        }
        return false;
    }

    /** 只渲染指定 type，并统计 create/bind/release 的假 renderer。 */
    private static final class FakeRenderer implements PartRenderer {
        private final String type;
        FakeRenderer(String type) {
            this.type = type;
        }
        @Override
        public boolean canRender(AssistantTurn.Part part) {
            return part != null && type.equals(part.type);
        }
        @Override
        public View createView(PartHost host, ViewGroup parent) {
            View v = new View(host.context());
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
        @Override
        public void bindView(View view, AssistantTurn.Part part, PartHost host) {
            view.setTag(part.text); // 记一下内容，证明被绑定
        }
        @Override
        public void releaseView(View view) {
            view.setTag(null);
        }
    }

    private static final class FakeHost implements PartHost {
        private final Context ctx;
        FakeHost(Context ctx) {
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
        public boolean streaming() {
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
        public void renderMarkdown(android.widget.TextView target, String markdown) {
            target.setText(markdown);
        }
        @Override
        public void invalidateSize() {
        }
    }
}
