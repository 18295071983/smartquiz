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
