package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.ReasoningShellView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

/**
 * 思考手风琴渲染（2026-11-18）。
 *
 * <p>钉四件事：一个 thinking part → 一个折叠头（不是每段一个）；折叠体内按顺序排
 * "思考段 + 工具行"；生成态自动展开且文案在推进；结束态留一拍后自动收起。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ThinkingPartRendererTest {

    private Context ctx;
    private ThinkingPartRenderer renderer;
    private TestHost host;

    @Before
    public void setUp() {
        // 工具行是 XML（?attr/colorBorder 等），必须用带主题的 Context，裸 Application 会 InflateException
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
        renderer = new ThinkingPartRenderer();
        host = new TestHost(ctx);
    }

    private static AssistantTurn.Part thinking(AssistantTurn.Part... members) {
        AssistantTurn.Part g = new AssistantTurn.Part();
        g.id = members[0].id + ":cot";
        g.type = AssistantTurn.Part.TYPE_THINKING;
        g.group = Arrays.asList(members);
        return g;
    }

    private static AssistantTurn.Part reasoning(String id, String text, long durationMs,
                                                boolean streaming) {
        AssistantTurn.Part p = AssistantTurn.Part.reasoning(id, text, 1L);
        p.durationMs = durationMs;
        if (streaming) {
            p.state = AssistantTurn.Part.STATE_STREAMING;
        }
        return p;
    }

    private static AssistantTurn.Part toolGroup(String id, String... statuses) {
        java.util.List<AssistantTurn.Part> calls = new java.util.ArrayList<>();
        int i = 0;
        for (String s : statuses) {
            calls.add(AssistantTurn.Part.toolCall("t" + i, "c" + i, "search", "{}", s, 2L));
            i++;
        }
        return AssistantTurn.Part.toolGroup(id, calls, 2L);
    }

    private ReasoningShellView bind(AssistantTurn.Part part) {
        ReasoningShellView shell = (ReasoningShellView) renderer.createView(host, new LinearLayout(ctx));
        renderer.bindView(shell, part, host);
        return shell;
    }

    @Test
    public void oneHeaderForReasoningAndTools() {
        ReasoningShellView shell = bind(thinking(
                reasoning("r1", "先读题", 3_200L, false),
                toolGroup("g1", "success", "success")));

        assertNotNull(shell);
        assertTrue("一个 thinking part 只有一个折叠容器", shell instanceof ReasoningShellView);
        assertEquals("结束态文案：深度思考 + 时长（工具数交给副标题）",
                "深度思考 3 秒", shell.label().getText().toString());
        assertEquals("正文列 = 1 段思考 + 2 个工具行", 3, shell.bodyColumn().getChildCount());
        assertTrue(shell.bodyColumn().getChildAt(0) instanceof TextView);
        assertTrue("收起态第二行要说明最后在跑什么：" + shell.summaryText(),
                shell.summaryText().toString().contains("最后："));
        assertTrue("状态必须在最前面（否则一行 ellipsize 后看不到）",
                shell.summaryText().toString().startsWith("已完成"));
    }

    @Test
    public void streamingExpandsAndSaysThinking() {
        ReasoningShellView shell = bind(thinking(
                reasoning("r1", "正在推理", 0L, true),
                toolGroup("g1", "success")));

        assertTrue("生成态文案应含'正在思考'：" + shell.label().getText(),
                shell.label().getText().toString().contains("正在思考"));
        assertEquals("生成态自动展开", View.VISIBLE, shell.bodyHost().getVisibility());
    }

    @Test
    public void runningToolPhraseWinsOverThinking() {
        AssistantTurn.Part r = reasoning("r1", "想完了", 0L, false);
        AssistantTurn.Part g = toolGroup("g1", "running");
        g.statusText = "正在联网搜索";
        ReasoningShellView shell = bind(thinking(r, g));

        assertEquals("工具在跑时头部显示工具短语", "正在联网搜索",
                shell.label().getText().toString());
    }

    @Test
    public void finishedCollapsesAfterABeat() {        ReasoningShellView shell = bind(thinking(
                reasoning("r1", "思考中", 0L, true),
                toolGroup("g1", "running")));
        assertEquals(View.VISIBLE, shell.bodyHost().getVisibility());

        // 用同一视图重绑"全部结束"的同一 part
        ReasoningShellView same = (ReasoningShellView) shell;
        renderer.bindView(same, thinking(
                reasoning("r1", "想完了", 3_000L, false),
                toolGroup("g1", "success")), host);
        assertEquals("刚结束先保持展开（让人看完最后一行）",
                View.VISIBLE, same.bodyHost().getVisibility());

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(900));
        assertEquals("留一拍后自动收起", View.GONE, same.bodyHost().getVisibility());
        assertTrue(same.label().getText().toString().contains("深度思考"));
    }

    /**
     * 流式期间用户点了收起，下一批事件重绑不能把它重新撑开（"不好折叠"的直接原因）。
     */
    @Test
    public void userCollapseSurvivesStreamingRebind() {
        AssistantTurn.Part p = thinking(
                reasoning("r1", "思考中", 0L, true),
                toolGroup("g1", "running"));
        ReasoningShellView shell = bind(p);
        assertEquals("流式默认展开", View.VISIBLE, shell.bodyHost().getVisibility());

        shell.getChildAt(0).performClick(); // 点折叠头收起
        assertEquals(View.GONE, shell.bodyHost().getVisibility());

        renderer.bindView(shell, p, host); // 同一 part 的事件批次又来一轮
        assertEquals("用户收起后，流式重绑不能强行展开", View.GONE,
                shell.bodyHost().getVisibility());
    }

    /** 最小 PartHost：测试只关心视图结构，不触发 markdown/尺寸回调。 */
    private static final class TestHost implements PartHost {
        private final Context ctx;

        TestHost(Context ctx) {
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
