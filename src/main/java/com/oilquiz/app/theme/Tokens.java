package com.oilquiz.app.theme;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * 语义 token（2026-11-16，UI 基础设施第二层）。
 *
 * <p><b>为什么需要</b>：{@code dimens.xml} 里原本只有绝对值 —— {@code space_4}、{@code space_8}、
 * {@code radius_12}、{@code text_14}。新代码要么从里面挑一个"看着差不多"的，要么直接写
 * {@code dp(12)} 字面量，全项目累计 1153 处。结果是每个页面的间距/圆角/字号各写各的，
 * 想统一调比例（或适配大字号）无从下手。</p>
 *
 * <p><b>这三组档位是什么</b>：</p>
 * <ul>
 *   <li><b>间距 space</b>（6 档）：{@code xs 4 / sm 8 / md 12 / lg 16 / xl 24 / xxl 32}（dp）。
 *       语义：xs=紧凑内边距，sm=行内间距，md=常规，lg=区块内，xl=区块间，xxl=页面留白。</li>
 *   <li><b>圆角 radius</b>（5 档）：{@code sm 8 / md 12 / lg 16 / xl 24 / pill 999}（dp）。
 *       语义：sm=标签/徽章，md=卡片，lg=面板，xl=大卡，pill=胶囊按钮。</li>
 *   <li><b>字号 font</b>（5 档）：{@code caption 12 / body 15 / subtitle 17 / title 20 / headline 24}（sp）。
 *       语义：caption=辅助说明，body=正文，subtitle=小标题，title=区块标题，headline=页面标题。</li>
 * </ul>
 *
 * <p><b>怎么用</b>：新代码取尺寸/圆角/字号一律走这里，不再写字面量：</p>
 * <pre>
 * view.setPadding(Tokens.spaceLg(ctx), Tokens.spaceMd(ctx), ...);
 * background.setCornerRadius(Tokens.radiusMd(ctx));
 * Tokens.applyText(title, Tokens.TITLE, TokenWeight.SEMIBOLD);
 * </pre>
 *
 * <p><b>为什么是 Java 静态方法而不是 Compose / 主题属性</b>：项目主体是 Java + XML 布局，
 * token 要能被现有代码直接调用；等 Compose 试点时这层可以照搬成 CompositionLocal。
 * 数值本身放在 {@code dimens.xml}（{@code space_*} / {@code radius_*} / {@code font_*}），
 * 这样 XML 布局也能引用同一份档位。</p>
 */
public final class Tokens {

    private Tokens() {
    }

    // ---------------- 档位常量 ----------------

    /** 间距档位（顺序与 {@link #space(Context, int)} 对应） */
    public static final int SPACE_HAIRLINE = 0;
    public static final int SPACE_XS = 1;
    public static final int SPACE_SM = 2;
    public static final int SPACE_MD = 3;
    public static final int SPACE_LG = 4;
    public static final int SPACE_XL = 5;
    public static final int SPACE_XXL = 6;
    public static final int SPACE_2XL = 7;
    public static final int SPACE_3XL = 8;

    /** 圆角档位 */
    public static final int RADIUS_SM = 0;
    public static final int RADIUS_MD = 1;
    public static final int RADIUS_LG = 2;
    public static final int RADIUS_XL = 3;
    public static final int RADIUS_PILL = 4;

    /** 字号档位 */
    public static final int TEXT_MICRO = 0;
    public static final int TEXT_CAPTION = 1;
    public static final int TEXT_FOOTNOTE = 2;
    public static final int TEXT_LABEL = 3;
    public static final int TEXT_BODY = 4;
    public static final int TEXT_BODY_SM = 5;
    public static final int TEXT_BODY_LG = 6;
    public static final int TEXT_SUBTITLE = 7;
    public static final int TEXT_TITLE = 8;
    public static final int TEXT_HEADLINE = 9;

    /** 字重（与字号正交：同一档字号可以配不同字重） */
    public enum Weight {
        REGULAR, MEDIUM, SEMIBOLD, BOLD
    }

    // ---------------- 取像素值 ----------------

    /** 间距（dp → px）。越界自动夹到两端，避免调用方传错就崩。 */
    public static int space(Context context, int tier) {
        return context.getResources().getDimensionPixelSize(dimenRes(spaceRes(tier)));
    }

    public static int spaceHairline(Context c) { return space(c, SPACE_HAIRLINE); }
    public static int spaceXs(Context c) { return space(c, SPACE_XS); }
    public static int spaceSm(Context c) { return space(c, SPACE_SM); }
    public static int spaceMd(Context c) { return space(c, SPACE_MD); }
    public static int spaceLg(Context c) { return space(c, SPACE_LG); }
    public static int spaceXl(Context c) { return space(c, SPACE_XL); }
    public static int spaceXxl(Context c) { return space(c, SPACE_XXL); }
    public static int space2xl(Context c) { return space(c, SPACE_2XL); }
    public static int space3xl(Context c) { return space(c, SPACE_3XL); }

    /** 圆角（dp → px）。 */
    public static int radius(Context context, int tier) {
        return context.getResources().getDimensionPixelSize(dimenRes(radiusRes(tier)));
    }

    public static int radiusSm(Context c) { return radius(c, RADIUS_SM); }
    public static int radiusMd(Context c) { return radius(c, RADIUS_MD); }
    public static int radiusLg(Context c) { return radius(c, RADIUS_LG); }
    public static int radiusXl(Context c) { return radius(c, RADIUS_XL); }

    /** 胶囊圆角：给按钮/标签用，取一个"足够大"的值（不超过实际高度一半时由 View 自己裁）。 */
    public static int radiusPill(Context c) { return radius(c, RADIUS_PILL); }

    /** 字号（sp → px，已按系统字体缩放）。setTextSize 请用 {@link #applyText} 或本方法配合 COMPLEX_UNIT_PX。 */
    public static float textSizePx(Context context, int tier) {
        return context.getResources().getDimension(dimenRes(fontRes(tier)));
    }

    /** 字号（sp 原值，供 setTextSize(COMPLEX_UNIT_SP, …) 或 Compose 使用）。 */
    public static float textSizeSp(Context context, int tier) {
        return textSizePx(context, tier) / context.getResources().getDisplayMetrics().scaledDensity;
    }

    public static Typeface typeface(Weight weight) {
        switch (weight == null ? Weight.REGULAR : weight) {
            case MEDIUM:
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case SEMIBOLD:
                return Typeface.create("sans-serif-medium", Typeface.BOLD);
            case BOLD:
                return Typeface.DEFAULT_BOLD;
            case REGULAR:
            default:
                return Typeface.DEFAULT;
        }
    }

    /** 一次把字号/字重/颜色打到 TextView 上（颜色为 0 表示不改）。 */
    public static void applyText(TextView view, int tier, Weight weight, int color) {
        if (view == null) return;
        Context c = view.getContext();
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx(c, tier));
        view.setTypeface(typeface(weight));
        if (color != 0) view.setTextColor(color);
    }

    /** 只改字号与字重，颜色保持（最常见用法）。 */
    public static void applyText(TextView view, int tier, Weight weight) {
        applyText(view, tier, weight, 0);
    }

    // ---------------- 资源映射 ----------------

    private static int dimenRes(int resId) {
        return resId;
    }

    private static int spaceRes(int tier) {
        switch (tier) {
            case SPACE_HAIRLINE: return com.oilquiz.app.R.dimen.space_hairline;
            case SPACE_XS: return com.oilquiz.app.R.dimen.space_xs;
            case SPACE_SM: return com.oilquiz.app.R.dimen.space_sm;
            case SPACE_MD: return com.oilquiz.app.R.dimen.space_md;
            case SPACE_LG: return com.oilquiz.app.R.dimen.space_lg;
            case SPACE_XL: return com.oilquiz.app.R.dimen.space_xl;
            case SPACE_XXL: return com.oilquiz.app.R.dimen.space_xxl;
            case SPACE_2XL: return com.oilquiz.app.R.dimen.space_2xl;
            case SPACE_3XL:
            default: return com.oilquiz.app.R.dimen.space_3xl;
        }
    }

    private static int radiusRes(int tier) {
        switch (tier) {
            case RADIUS_SM: return com.oilquiz.app.R.dimen.radius_sm;
            case RADIUS_MD: return com.oilquiz.app.R.dimen.radius_md;
            case RADIUS_LG: return com.oilquiz.app.R.dimen.radius_lg;
            case RADIUS_XL: return com.oilquiz.app.R.dimen.radius_xl;
            case RADIUS_PILL:
            default: return com.oilquiz.app.R.dimen.radius_pill;
        }
    }

    private static int fontRes(int tier) {
        switch (tier) {
            case TEXT_MICRO: return com.oilquiz.app.R.dimen.font_micro;
            case TEXT_CAPTION: return com.oilquiz.app.R.dimen.font_caption;
            case TEXT_FOOTNOTE: return com.oilquiz.app.R.dimen.font_footnote;
            case TEXT_LABEL: return com.oilquiz.app.R.dimen.font_label;
            case TEXT_BODY: return com.oilquiz.app.R.dimen.font_body;
            case TEXT_BODY_SM: return com.oilquiz.app.R.dimen.font_body_sm;
            case TEXT_BODY_LG: return com.oilquiz.app.R.dimen.font_body_lg;
            case TEXT_SUBTITLE: return com.oilquiz.app.R.dimen.font_subtitle;
            case TEXT_TITLE: return com.oilquiz.app.R.dimen.font_title;
            case TEXT_HEADLINE:
            default: return com.oilquiz.app.R.dimen.font_headline;
        }
    }
}
