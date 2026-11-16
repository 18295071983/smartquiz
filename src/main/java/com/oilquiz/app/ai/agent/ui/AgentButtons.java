package com.oilquiz.app.ai.agent.ui;

import android.content.Context;
import android.content.res.ColorStateList;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * Agent 调试面板的按钮工厂（2026-11-16）。
 *
 * <p>背景：这几个面板早期用 {@code TextView + emoji + 自定义 drawable} 当按钮
 * （"🔄 刷新" / "🗑 清空所有记忆"），既不是真正的按钮元素、emoji 也不能跟主题着色。
 * 现在统一改成 {@link MaterialButton}：矢量图标 + 文字，图标随文字色着色，圆角/内边距用令牌。</p>
 *
 * <p>注意：只用于"动作按钮"；面板里的标题/说明/状态文字仍然保持 TextView（emoji 文案不动）。</p>
 */
final class AgentButtons {

    private AgentButtons() {
    }

    /** 文字按钮（无底色，图标+文字同色）。 */
    static MaterialButton text(Context c, CharSequence text, int iconRes, int textColorRes) {
        return build(c, text, iconRes, textColorRes, 0);
    }

    /** 带底色的动作按钮。 */
    static MaterialButton filled(Context c, CharSequence text, int iconRes,
                                 int textColorRes, int bgColorRes) {
        return build(c, text, iconRes, textColorRes, bgColorRes);
    }

    private static MaterialButton build(Context c, CharSequence text, int iconRes,
                                        int textColorRes, int bgColorRes) {
        MaterialButton b = new MaterialButton(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setIconResource(iconRes);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        b.setIconPadding(Tokens.spaceXs(c));
        int color = ThemeColors.get(c, textColorRes);
        b.setTextColor(color);
        b.setIconTint(ColorStateList.valueOf(color));
        b.setBackgroundTintList(ColorStateList.valueOf(
                bgColorRes == 0 ? 0 : ThemeColors.get(c, bgColorRes)));
        b.setCornerRadius(Tokens.radiusMd(c));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int padH = Tokens.spaceMd(c);
        b.setPadding(padH, Tokens.spaceXs(c), padH, Tokens.spaceXs(c));
        return b;
    }
}
