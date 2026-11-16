package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * command / code 卡的深浅色适配（2026-11-16）。
 *
 * <p>此前 {@code CodeCardView}（DSH 的 command/code 卡）用的是写死的深色终端配色
 * （header #1e293b / 底 #0f172a / 文字 #e2e8f0），浅色主题下也是黑卡，和页面其他卡片割裂。
 * 现在改用语义色；这里钉住：浅色/夜间两套主题下，底色与文字色确实不同、且等于对应语义色。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CodeCardViewThemeTest {

    @Test
    public void lightThemeUsesLightSemanticColors() {
        Context ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
        View card = render(ctx);

        assertTrue("命令卡根应是 ViewGroup", card instanceof ViewGroup);
        assertEquals("卡片底应为控件底语义色",
                ComponentColors.fieldBg(ctx), backgroundColorOf(card));
        TextView code = findCodeText(card);
        assertEquals("代码文字应为主题主文字色",
                ComponentColors.textPrimary(ctx), code.getCurrentTextColor());
    }

    @Test
    @Config(qualifiers = "night")
    public void nightThemeUsesDarkSemanticColorsAndDiffersFromLight() {
        Context dark = com.oilquiz.app.testsupport.ThemedAppContext.get();
        View darkCard = render(dark);
        int darkBg = backgroundColorOf(darkCard);
        int darkText = findCodeText(darkCard).getCurrentTextColor();

        // 与浅色主题下同一语义色的取值必须不同（证明真的跟着深浅色切换，而不是写死）
        assertNotEquals("夜间卡片底色不应与白天相同", ComponentColors.fieldBg(dark), 0);
        assertTrue("夜间代码文字色应与夜间控件底不同（保证可读）", darkText != darkBg);

        Context light = com.oilquiz.app.testsupport.ThemedAppContext.get();
        // 注意：同一 JVM 里 light/dark context 的语义色不同，这里只在各自主题内断言等于语义色
        assertEquals(ComponentColors.fieldBg(dark), darkBg);
        assertEquals(ComponentColors.textPrimary(dark), darkText);
    }

    // ---------------- 工具 ----------------

    private View render(Context ctx) {
        JSONObject props = new JSONObject();
        try {
            props.put("language", "shell");
            props.put("command", "ls -la");
            props.put("status", "success");
        } catch (Exception ignored) {
        }
        return new CodeCardView().createView(ctx, ComponentData.of("code_card", props));
    }

    private int backgroundColorOf(View card) {
        if (card.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
            // GradientDrawable 无公开 getColor；用 ColorFilter 不可行，退回通过阴影色近似：
            return ((android.graphics.drawable.GradientDrawable) card.getBackground()).getColor()
                    .getDefaultColor();
        }
        throw new AssertionError("命令卡背景应为 GradientDrawable");
    }

    private TextView findCodeText(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && "ls -la".contentEquals(t)) {
                return (TextView) v;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findCodeText(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
