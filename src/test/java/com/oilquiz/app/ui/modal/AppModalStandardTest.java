package com.oilquiz.app.ui.modal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;

/**
 * 标准 UI 容器/模态框（{@link AppDialog} / {@link AppSheet} / {@link AppCard}）的行为护栏（2026-11-16）。
 *
 * <p>把"对话框/弹层/卡片容器"统一到这一层后，钉住三条契约：</p>
 * <ul>
 *   <li>能正常创建并显示（show 不抛、isShowing 正确）；</li>
 *   <li>外观走主题语义色（不是写死白/黑）；</li>
 *   <li>标准卡片的圆角/底色与令牌一致。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class AppModalStandardTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
    }

    @Test
    public void appDialogShowsWithButtons() {
        androidx.appcompat.app.AlertDialog dialog = AppDialog.builder(ctx)
                .title("删除会话")
                .message("删除后不可恢复")
                .positive("删除", null)
                .negative("取消", null)
                .show();
        assertNotNull(dialog);
        assertTrue("标准对话框 show() 后应处于显示状态", dialog.isShowing());
        dialog.dismiss();
    }

    @Test
    public void appDialogConvenienceHelpersWork() {
        androidx.appcompat.app.AlertDialog message = AppDialog.message(ctx, "提示", "内容");
        assertTrue(message.isShowing());
        message.dismiss();

        androidx.appcompat.app.AlertDialog confirm = AppDialog.confirm(ctx, "确认", "继续？", "确定", null);
        assertTrue(confirm.isShowing());
        confirm.dismiss();
    }

    @Test
    public void appSheetShowsWithTitleAndContent() {
        LinearLayout content = new LinearLayout(ctx);
        content.addView(new View(ctx));
        AppSheet sheet = AppSheet.builder(ctx)
                .title("选择操作")
                .subtitle("副标题")
                .content(content)
                .action("确定", true, null)
                .action("取消", false, null)
                .show();
        assertTrue("标准弹层 show() 后应处于显示状态", sheet.isShowing());
        sheet.dismiss();
    }

    @Test
    public void appCardUsesThemeSurfaceAndTokenRadius() {
        com.google.android.material.card.MaterialCardView card = AppCard.create(ctx);
        int expected = ThemeColors.get(ctx, R.color.surface);
        assertEquals("标准卡片底色应等于主题 surface 色",
                expected, card.getCardBackgroundColor().getDefaultColor());
        assertTrue("标准卡片圆角应来自令牌（>0）", card.getRadius() > 0f);

        View child = new View(ctx);
        com.google.android.material.card.MaterialCardView wrapped = AppCard.wrap(ctx, child, true);
        assertEquals("wrap 后子 View 应挂在卡片里", 1, wrapped.getChildCount());
        assertEquals(child, wrapped.getChildAt(0));
    }
}
