package com.oilquiz.app.ui.modal;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * 标准卡片容器（2026-11-16 统一）。
 *
 * <p><b>为什么有它</b>：页面里的"一组内容"容器各写各的 —— 有的用 {@code MaterialCardView}
 * 手调 8/12/16dp 圆角、有的用 {@code GradientDrawable} 写死底色和描边、有的干脆白底 TextView。
 * 这里给一个统一容器：圆角 {@link Tokens#radiusLg}、底色 {@code ?attr/colorSurface}、
 * 描边 {@code ?attr/colorBorder}、内边距 {@link Tokens#spaceLg}，深色/换肤自动跟随。</p>
 *
 * <p>用法：</p>
 * <pre>
 * MaterialCardView card = AppCard.create(this);
 * card.addView(row);
 * container.addView(card);
 * // 或者直接包一层：
 * container.addView(AppCard.wrap(this, row));
 * </pre>
 */
public final class AppCard {

    private AppCard() {
    }

    /** 空的标准卡片（调用方自己 addView 内容）。 */
    public static MaterialCardView create(Context context) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(Tokens.radiusLg(context));
        card.setCardBackgroundColor(ThemeColors.get(context, R.color.surface));
        card.setStrokeColor(ThemeColors.get(context, R.color.border));
        card.setStrokeWidth(Math.max(1, Tokens.spaceHairline(context) / 2));
        card.setCardElevation(0f);
        card.setUseCompatPadding(false);
        int pad = Tokens.spaceLg(context);
        card.setContentPadding(pad, pad, pad, pad);
        return card;
    }

    /** 把一个 View 包进标准卡片（内容自带 padding 时用紧凑模式）。 */
    public static MaterialCardView wrap(Context context, View content) {
        return wrap(context, content, false);
    }

    /**
     * @param compact true 时不额外加内边距（内容自己已经有 padding，例如整行列表项）
     */
    public static MaterialCardView wrap(Context context, View content, boolean compact) {
        MaterialCardView card = create(context);
        if (compact) {
            card.setContentPadding(0, 0, 0, 0);
        }
        if (content != null) {
            if (content.getParent() instanceof ViewGroup) {
                ((ViewGroup) content.getParent()).removeView(content);
            }
            card.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }
}
