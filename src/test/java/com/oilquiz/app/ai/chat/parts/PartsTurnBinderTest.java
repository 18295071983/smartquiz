package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
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
import java.util.List;

/**
 * PartsTurnBinder 消费端驱动（P3a）：一条消息 → 容器 → 视图，复用同 id、msg 切换清空。
 * 用假 registry（计数 renderer）避开 Markwon/WebView，纯验驱动与复用。
 */
@RunWith(RobolectricTestRunner.class)
public class PartsTurnBinderTest {

    private Context ctx;
    private LinearLayout container;
    private CountingRenderer textRenderer;
    private PartsTurnBinder binder;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        container = new LinearLayout(ctx);
        textRenderer = new CountingRenderer();
        PartRendererRegistry registry = new PartRendererRegistry().register(textRenderer);
        binder = new PartsTurnBinder(container, registry, new SimpleHost(ctx));
    }

    private static ChatMessage msgWithParts(AssistantTurn.Part... parts) {
        ChatMessage msg = ChatMessage.createAIMessage("a", "正文", 1L);
        AssistantTurn turn = new AssistantTurn();
        turn.turnId = "T1";
        List<AssistantTurn.Part> list = new ArrayList<>();
        for (AssistantTurn.Part p : parts) list.add(p);
        turn.parts = list;
        msg.assistantTurn = turn;
        return msg;
    }

    @Test
    public void bindsEachPartOnceAndReusesAcrossRebind() {
        ChatMessage msg = msgWithParts(
                AssistantTurn.Part.text("t1", "第一段", 1L),
                AssistantTurn.Part.text("t2", "第二段", 1L));

        binder.bind(msg);
        assertEquals(2, container.getChildCount());
        assertEquals(2, textRenderer.creates);
        assertEquals(2, binder.renderedCount());

        binder.bind(msg); // 同内容再绑
        assertEquals("复用不重建", 2, textRenderer.creates);
        assertEquals("指纹相同不重绑", 2, textRenderer.binds);
    }

    @Test
    public void switchingMessageClearsPrevious() {
        binder.bind(msgWithParts(AssistantTurn.Part.text("t1", "A", 1L)));
        assertEquals(1, container.getChildCount());

        binder.bind(msgWithParts(
                AssistantTurn.Part.text("t2", "B", 1L),
                AssistantTurn.Part.text("t3", "C", 1L)));
        assertEquals("旧 part 被移除", 2, container.getChildCount());
        assertEquals(1, textRenderer.releases);
    }

    @Test
    public void clearRemovesEverything() {
        binder.bind(msgWithParts(AssistantTurn.Part.text("t1", "A", 1L)));
        binder.clear();
        assertEquals(0, container.getChildCount());
        assertEquals(0, binder.renderedCount());
    }

    /**
     * 端到端接线（2026-11-18）：默认注册表 + 相邻 reasoning/tool_group → 只出一个手风琴视图。
     *
     * <p>这条钉的是 {@link PartsTurnBinder} 里"渲染前合并"的那一步真的生效了——单测
     * {@code AssistantTurn.coalesceThinking} 只证明算法，这条证明接线。</p>
     */
    @Test
    public void reasoningAndToolGroupRenderAsTwoSeparateFoldsByDefault() {
        Context themed = com.oilquiz.app.testsupport.ThemedAppContext.get();
        LinearLayout target = new LinearLayout(themed);
        PartsTurnBinder binderWithDefaults = new PartsTurnBinder(
                target, PartRendererRegistry.createDefault(), new SimpleHost(themed));

        AssistantTurn turn = new AssistantTurn();
        turn.parts = new ArrayList<>();
        turn.parts.add(AssistantTurn.Part.reasoning("r1", "先想想", 1L));
        turn.parts.add(AssistantTurn.Part.toolGroup("g1", new ArrayList<>(java.util.Collections
                .singletonList(AssistantTurn.Part.toolCall(
                        "t1", "c1", "search", "{}", "success", 2L))), 2L));

        binderWithDefaults.bind(turn);

        assertEquals("按事件类型分开折叠：思考一个、工具组一个", 2, target.getChildCount());
        assertTrue("第一个是思考折叠",
                target.getChildAt(0) instanceof com.oilquiz.app.ai.chat.ui.ReasoningShellView);
        assertTrue("第二个是工具组折叠",
                target.getChildAt(1) instanceof android.widget.LinearLayout);
    }

    /** 开关打开时仍保留"混成一个手风琴"的实验结构（对照用，不是默认）。 */
    @Test
    public void mixedThinkingShellOnlyWhenFlagEnabled() {
        Context themed = com.oilquiz.app.testsupport.ThemedAppContext.get();
        ThinkingGroupFlag.setEnabled(themed, true);
        try {
            LinearLayout target = new LinearLayout(themed);
            PartsTurnBinder binderWithDefaults = new PartsTurnBinder(
                    target, PartRendererRegistry.createDefault(), new SimpleHost(themed));
            AssistantTurn turn = new AssistantTurn();
            turn.parts = new ArrayList<>();
            turn.parts.add(AssistantTurn.Part.reasoning("r1", "先想想", 1L));
            turn.parts.add(AssistantTurn.Part.toolGroup("g1", new ArrayList<>(java.util.Collections
                    .singletonList(AssistantTurn.Part.toolCall(
                            "t1", "c1", "search", "{}", "success", 2L))), 2L));

            binderWithDefaults.bind(turn);

            assertEquals("开关打开才合并成一个视图", 1, target.getChildCount());
            assertTrue(target.getChildAt(0)
                    instanceof com.oilquiz.app.ai.chat.ui.ReasoningShellView);
        } finally {
            ThinkingGroupFlag.setEnabled(themed, false);
        }
    }

    /** 计数型假 text renderer。 */
    private static final class CountingRenderer implements PartRenderer {
        int creates, binds, releases;
        @Override
        public boolean canRender(AssistantTurn.Part part) {
            return part != null && AssistantTurn.Part.TYPE_TEXT.equals(part.type);
        }
        @Override
        public View createView(PartHost host, ViewGroup parent) {
            creates++;
            View v = new View(host.context());
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
        @Override
        public void bindView(View view, AssistantTurn.Part part, PartHost host) {
            binds++;
        }
        @Override
        public void releaseView(View view) {
            releases++;
        }
    }

    private static final class SimpleHost implements PartHost {
        private final Context ctx;
        SimpleHost(Context ctx) {
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
        public void renderMarkdown(android.widget.TextView target, String markdown) {
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
