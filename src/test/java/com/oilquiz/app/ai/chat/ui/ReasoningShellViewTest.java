package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 思考块容器（2026-11-18）：结构、展开/收起、流式呼吸点。
 *
 * <p>为什么单独钉：思考段此前没有自己的容器（裸 TextView 当折叠头 + 裸正文），
 * 展开/收起只能整块 {@code setVisibility}，箭头、底色、悬停反馈都无处可挂。
 * 这里锁住"容器存在且真的管着正文可见性与呼吸点"，避免以后又退回裸视图。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ReasoningShellViewTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static ReasoningShellView newShell(Context ctx) {
        return new ReasoningShellView(ctx);
    }

    @Test
    public void shellOwnsHeaderAndBodyContainer() {
        ReasoningShellView shell = newShell(ctx());
        assertEquals("折叠头 + 正文容器两段", 2, shell.getChildCount());
        assertTrue("折叠头是整行容器", shell.getChildAt(0) instanceof LinearLayout);
        assertTrue("正文容器是 FrameLayout（左竖线底 + clip 高度动画）",
                shell.bodyHost() instanceof android.widget.FrameLayout);
        assertEquals("默认收起", View.GONE, shell.bodyHost().getVisibility());
        assertFalse(shell.isExpanded());
    }

    /**
     * 折叠头至少按设计稿 32dp（`chat_v2_think_height`）。
     *
     * <p>不钉"恰好 32"：Robolectric 的字体度量是假的，12.5sp 单行会量出 ~41px；
     * 真机上 32dp 最小高度生效、文字更高时按文字高。这里钉"最小高度生效 + 撑满宽度"，
     * 足够抓住"布局塌成 0 / 权重没生效"这类回归。</p>
     */
    @Test
    public void headerLaysOutAtLeastDesignHeight() {
        ReasoningShellView shell = newShell(ctx());
        int width = 1080;
        shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        shell.layout(0, 0, width, shell.getMeasuredHeight());

        int expected = ctx().getResources().getDimensionPixelSize(
                com.oilquiz.app.R.dimen.chat_v2_think_height);
        ViewGroup header = (ViewGroup) shell.getChildAt(0);
        assertTrue("折叠头至少 32dp，实际=" + header.getMeasuredHeight(),
                header.getMeasuredHeight() >= expected);
        assertTrue("折叠头宽度撑满", header.getMeasuredWidth() > 0);
    }

    @Test
    public void setBodyViewReplacesOnlyChild() {
        ReasoningShellView shell = newShell(ctx());
        TextView a = new TextView(ctx());
        TextView b = new TextView(ctx());
        shell.setBodyView(a);
        shell.setBodyView(b);

        assertEquals("重复塞正文只保留最新一份", 1, shell.bodyHost().getChildCount());
        assertSame(b, shell.bodyHost().getChildAt(0));
    }

    @Test
    public void expandAndCollapseDriveBodyHostVisibility() {
        ReasoningShellView shell = newShell(ctx());
        shell.setBodyView(new TextView(ctx()));

        shell.setExpanded(true, false); // 无动画路径（绑定/系统关动画时走这条）
        assertEquals(View.VISIBLE, shell.bodyHost().getVisibility());
        assertTrue(shell.isExpanded());

        shell.setExpanded(false, false);
        assertEquals(View.GONE, shell.bodyHost().getVisibility());
        assertFalse(shell.isExpanded());
    }

    @Test
    public void streamingTogglesLiveDots() {
        ReasoningShellView shell = newShell(ctx());
        View dots = shell.dotsView();
        assertEquals("非流式不挂常驻呼吸点", View.GONE, dots.getVisibility());

        shell.setStreaming(true);
        assertEquals(View.VISIBLE, dots.getVisibility());

        shell.setStreaming(false);
        assertEquals("定稿后收起呼吸点", View.GONE, dots.getVisibility());
    }

    /** 收起态副标题：只在"收起 + 已结束 + 有内容"时出现，展开/流式都不占位。 */
    @Test
    public void summaryVisibleOnlyWhenCollapsedAndFinished() {
        ReasoningShellView shell = newShell(ctx());
        shell.setSummary("最后：pwsh · Get-ChildItem…（已完成）");
        assertTrue("默认收起 → 显示", shell.isSummaryVisible());

        shell.setExpanded(true, false);
        assertFalse("展开后正文就在眼前，摘要收起", shell.isSummaryVisible());

        shell.setExpanded(false, false);
        assertTrue("重新收起 → 再出现", shell.isSummaryVisible());

        shell.setStreaming(true);
        assertTrue("思考中收起时也要能看见想到哪一步（ThinkingPreview 口径）",
                shell.isSummaryVisible());

        shell.setStreaming(false);
        shell.setSummary("");
        assertFalse("没有摘要内容就不占位", shell.isSummaryVisible());
    }

    /**
     * 带高度动画的展开（真机走的就是这条）：测量 → 动画 → 结束后高度必须回到 WRAP_CONTENT，
     * 否则内容后来再长（WebView 增量渲染）会被钉死在展开那一刻的高度。
     */
    @Test
    public void animatedExpandEndsWithWrapContentHeight() {
        ReasoningShellView shell = newShell(ctx());
        TextView body = new TextView(ctx());
        body.setText("一段足够长的思考正文，用来量出正文的自然高度，验证展开动画的起止口径。");
        shell.setBodyView(body);

        int width = 1080;
        shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        shell.layout(0, 0, width, shell.getMeasuredHeight());

        shell.setExpanded(true, true);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(600));

        assertEquals("动画结束仍在屏上", View.VISIBLE, shell.bodyHost().getVisibility());
        assertEquals("动画结束必须放开固定高度", android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                shell.bodyHost().getLayoutParams().height);
    }

    @Test
    public void headerClickIsDelegatedToWholeRow() {
        ReasoningShellView shell = newShell(ctx());
        final int[] clicks = {0};
        shell.setOnHeaderClickListener(v -> clicks[0]++);
        assertTrue("点箭头/图标也走整行", shell.getChildAt(0).performClick());
        assertEquals(1, clicks[0]);
    }
}
