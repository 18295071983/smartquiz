package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批卡"能放进甲板"的回归（2026-11-13）。
 *
 * <p>甲板卡片复用同一套 part 渲染器，所以这里直接验证渲染器契约：
 * pending 时出现「允许/拒绝」按钮，点击经 {@link PartHost#onApprovalDecision} 回到宿主；
 * 已决时按钮收起、显示结果文案。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ApprovalPartRendererDeckTest {

    private static final class FakeHost implements PartHost {
        ChatMessage owner;
        String lastPartId;
        String lastCallId;
        Boolean lastApproved;

        @Override
        public Context context() {
            return ApplicationProvider.getApplicationContext();
        }

        @Override
        public int availableWidth() {
            return 900;
        }

        @Override
        public ChatMessage message() {
            return owner;
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
            lastPartId = partId;
            lastCallId = toolCallId;
            lastApproved = approved;
        }

        @Override
        public void invalidateSize() {
        }
    }

    private static void collectButtons(View v, List<Button> out) {
        if (v instanceof Button) {
            out.add((Button) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) collectButtons(g.getChildAt(i), out);
        }
    }

    private static Button byText(View root, String text) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        for (Button b : buttons) {
            if (text.contentEquals(b.getText())) return b;
        }
        return null;
    }

    private static AssistantTurn.Part pendingPart() {
        return AssistantTurn.Part.approval("approval:ap-1", "call-1", "write_file",
                "需要写入 build.gradle", AssistantTurn.Part.APPROVAL_PENDING, 1000L);
    }

    @Test
    public void pendingCardShowsButtonsAndSendsDecision() {
        ApprovalPartRenderer renderer = new ApprovalPartRenderer();
        FakeHost host = new FakeHost();
        host.owner = ChatMessage.createAIMessage("T1-A1", "", 1000L, null, 0, 0);

        View view = renderer.createView(host, null);
        assertNotNull(view);
        renderer.bindView(view, pendingPart(), host);

        Button allow = byText(view, "允许");
        Button deny = byText(view, "拒绝");
        assertNotNull("pending 必须有允许按钮", allow);
        assertNotNull("pending 必须有拒绝按钮", deny);
        assertTrue("pending 时按钮可见", allow.getVisibility() == View.VISIBLE);

        allow.performClick();
        assertEquals("approval:ap-1", host.lastPartId);
        assertEquals("call-1", host.lastCallId);
        assertEquals(Boolean.TRUE, host.lastApproved);

        deny.performClick();
        assertEquals("拒绝要走同一个回调", Boolean.FALSE, host.lastApproved);
    }

    @Test
    public void decidedCardHidesButtonsAndShowsResult() {
        ApprovalPartRenderer renderer = new ApprovalPartRenderer();
        FakeHost host = new FakeHost();
        host.owner = ChatMessage.createAIMessage("T1-A2", "", 1000L, null, 0, 0);

        AssistantTurn.Part decided = AssistantTurn.Part.approval("approval:ap-2", "call-2",
                "write_file", "", AssistantTurn.Part.APPROVAL_APPROVED, 1000L);
        View view = renderer.createView(host, null);
        renderer.bindView(view, decided, host);

        Button allow = byText(view, "允许");
        assertNotNull(allow);
        assertEquals("已决时按钮应收起", View.GONE,
                ((View) allow.getParent()).getVisibility());

        List<String> texts = new ArrayList<>();
        collectTexts(view, texts);
        assertTrue("应显示已允许文案：" + texts, texts.toString().contains("已允许"));
    }

    private static void collectTexts(View v, List<String> out) {
        if (v instanceof TextView) {
            out.add(String.valueOf(((TextView) v).getText()));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) collectTexts(g.getChildAt(i), out);
        }
    }
}
