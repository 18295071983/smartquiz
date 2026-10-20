package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批 part 渲染器（对齐 agent 面板的确认块，parts P2.5）。
 * 钉住：pending 显"允许/拒绝"，点击经 {@link PartHost#onApprovalDecision} 回调宿主；
 * 已决态收起按钮。不依赖真实权限系统，只验 renderer↔host 契约。
 */
@RunWith(RobolectricTestRunner.class)
public class ApprovalPartRendererTest {

    private Context ctx;
    private ApprovalPartRenderer renderer;
    private RecordingHost host;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        renderer = new ApprovalPartRenderer();
        host = new RecordingHost(ctx);
    }

    @Test
    public void canRenderOnlyApproval() {
        assertTrue(renderer.canRender(Part.approval("a1", "c1", "存储", "需要读写文件", null, 1L)));
        assertFalse(renderer.canRender(Part.text("t", "正文", 1L)));
    }

    @Test
    public void pendingShowsButtonsAndClickFiresDecision() {
        Part p = Part.approval("a1", "c1", "存储", "需要读写文件", Part.APPROVAL_PENDING, 1L);
        assertTrue(p.isApprovalPending());

        LinearLayout root = (LinearLayout) renderer.createView(host, new LinearLayout(ctx));
        renderer.bindView(root, p, host);

        LinearLayout actions = (LinearLayout) root.getChildAt(1);
        assertEquals("pending 应显示动作行", View.VISIBLE, actions.getVisibility());
        Button deny = (Button) actions.getChildAt(0);
        Button allow = (Button) actions.getChildAt(1);

        allow.performClick();
        deny.performClick();

        assertEquals(2, host.decisions.size());
        assertEquals("a1|c1|true", host.decisions.get(0));
        assertEquals("a1|c1|false", host.decisions.get(1));
    }

    @Test
    public void decidedHidesButtons() {
        Part p = Part.approval("a1", "c1", "存储", "", Part.APPROVAL_APPROVED, 1L);
        assertFalse(p.isApprovalPending());

        LinearLayout root = (LinearLayout) renderer.createView(host, new LinearLayout(ctx));
        renderer.bindView(root, p, host);

        View actions = root.getChildAt(1);
        assertEquals("已决应收起动作行", View.GONE, actions.getVisibility());
        assertTrue(((TextView) root.getChildAt(0)).getText().toString().contains("已允许"));
    }

    @Test
    public void defaultRegistryResolvesApproval() {
        assertTrue(PartRendererRegistry.createDefault()
                .resolve(Part.approval("a1", "c1", "p", "q", null, 1L))
                instanceof ApprovalPartRenderer);
    }

    private static final class RecordingHost implements PartHost {
        final List<String> decisions = new ArrayList<>();
        private final Context ctx;
        RecordingHost(Context ctx) {
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
            decisions.add(partId + "|" + toolCallId + "|" + approved);
        }
        @Override
        public void invalidateSize() {
        }
    }
}
