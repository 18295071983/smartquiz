package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;

/**
 * 组件设计 token 单一出口（Track B0-2）。
 *
 * <p>此前有两套语言：组件用 {@code component_*} 资源，聊天页用 {@code ?attr/colorSurface}
 * 一类的主题属性，FeedbackBar/AgentStatus 还在代码里写死深色模式的白与蓝——同一屏里
 * 卡片圆角、内边距、标题字重、状态色各画各的，换主题或切深色就露馅。
 *
 * <p>本类把口径收敛成一次取色：<b>先解析当前主题的 attr</b>（跟随换肤 31 档 / 深浅色 /
 * 动态色），解析不到才退回 {@code component_*} 资源兜底。所有卡片只从这里取值，
 * 组件代码里不再出现 {@code 0xFF…} 与 {@code #…}。
 *
 * <p>尺寸口径来自 {@code res/values/dimens.xml} 的 {@code card_shell_*}，
 * 与 {@code demo/ai-chat-redesign.html} 的卡片基线一致（14dp 圆角/内边距、
 * 13sp/600 标题、11sp 辅助文字、footer 最多 2 个动作）。
 */
public final class ComponentTheme {

    private ComponentTheme() {
    }

    // ---------------------------------------------------------------- 颜色

    /** 卡片底色 */
    public static int surface(Context context) {
        return orFallback(ThemeColors.attr(context, R.attr.colorSurface),
                ComponentColors.background(context));
    }

    /** 卡片内的次级底色（代码块、表头、输入底） */
    public static int surfaceVariant(Context context) {
        int fromTheme = ThemeColors.attr(context, R.attr.colorSurfaceVariant);
        if (fromTheme == 0) {
            fromTheme = ThemeColors.attr(context, R.attr.colorCardBackground);
        }
        return orFallback(fromTheme, ComponentColors.fieldBg(context));
    }

    /** 描边色 */
    public static int outline(Context context) {
        int fromTheme = ThemeColors.attr(context, R.attr.colorOutline);
        if (fromTheme == 0) {
            fromTheme = ThemeColors.attr(context, R.attr.colorDivider);
        }
        return orFallback(fromTheme, ComponentColors.border(context));
    }

    public static int textPrimary(Context context) {
        return orFallback(ThemeColors.attr(context, R.attr.colorControlText),
                ComponentColors.textPrimary(context));
    }

    public static int textSecondary(Context context) {
        return orFallback(ThemeColors.attr(context, R.attr.colorControlTextSecondary),
                ComponentColors.textSecondary(context));
    }

    public static int textTertiary(Context context) {
        return orFallback(ThemeColors.attr(context, R.attr.colorControlTextHint),
                ComponentColors.textTertiary(context));
    }

    /** 品牌/强调色（主操作、链接、选中态） */
    public static int brand(Context context) {
        return orFallback(ThemeColors.attr(context, R.attr.colorPrimary),
                ComponentColors.accent(context));
    }

    /** 成功态 */
    public static int success(Context context) {
        return ContextCompat.getColor(context, R.color.component_success);
    }

    /** 警告态 */
    public static int warning(Context context) {
        return ContextCompat.getColor(context, R.color.component_warning);
    }

    /** 危险/失败态 */
    public static int danger(Context context) {
        return ContextCompat.getColor(context, R.color.component_error);
    }

    /** 品牌色低透明度底（选中态背景）；只做透明度，不另起色系 */
    public static int brandOverlay(Context context) {
        return withAlpha(brand(context), 0x1F);
    }

    /** 给颜色叠加 alpha（保留色相，深浅色都由入参色决定） */
    public static int withAlpha(int color, int alpha255) {
        int a = Math.max(0, Math.min(255, alpha255));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    // ---------------------------------------------------------------- 形状与尺寸

    /** 卡片背景：surface + 1px 描边 + 14dp 圆角 */
    public static Drawable cardBackground(Context context) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(surface(context));
        gd.setCornerRadius(radius(context));
        gd.setStroke(borderWidth(context), outline(context));
        return gd;
    }

    /** footer/头部小徽章背景：圆角矩形，active 时描品牌色 */
    public static Drawable chipBackground(Context context, boolean active) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(context, 10));
        gd.setColor(active ? brandOverlay(context) : android.graphics.Color.TRANSPARENT);
        gd.setStroke(dp(context, 1), active ? brand(context) : outline(context));
        return gd;
    }

    public static float radius(Context context) {
        return context.getResources().getDimension(R.dimen.card_shell_radius);
    }

    public static int borderWidth(Context context) {
        int px = context.getResources().getDimensionPixelSize(R.dimen.card_shell_border);
        return px > 0 ? px : dp(context, 1);
    }

    public static int padding(Context context) {
        return context.getResources().getDimensionPixelSize(R.dimen.card_shell_padding);
    }

    /** header/body/footer 之间的分隔间距 */
    public static int gap(Context context) {
        return context.getResources().getDimensionPixelSize(R.dimen.card_shell_gap);
    }

    /** 内容区默认最大高度，超出走内部滚动/折叠 */
    public static int bodyMaxHeight(Context context) {
        return context.getResources().getDimensionPixelSize(R.dimen.card_shell_body_max_height);
    }

    /** footer 里最多并排显示几个动作，多余的进「更多」 */
    public static final int MAX_INLINE_ACTIONS = 2;

    public static float headerTextSize() {
        return 13f;
    }

    public static float metaTextSize() {
        return 11f;
    }

    public static float bodyTextSize() {
        return 13f;
    }

    public static float actionTextSize() {
        return 12f;
    }

    // ---------------------------------------------------------------- 工具

    public static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /**
     * 主题 attr 解析不到时退回资源兜底。
     *
     * <p>0 在颜色里是透明黑：个别非 Material 主题（AppTheme）解析 ?attr/colorSurface
     * 失败会返回 0，直接用会把卡片染黑，所以 0/透明一律当作"没配"处理。
     */
    /**
     * 解析组件 {@code props.style} 里的颜色写法（B2：只接受语义 token，不接受任意色值）。
     *
     * <p>此前模型可以传 {@code style.background="#000000"} 覆盖卡片底色，深色模式与对比度
     * 直接失控（一张白底黑字的卡在暗色主题里可能整块不可读）。现在只认这些 token：
     * surface / surfaceVariant / outline / brand / success / warning / danger /
     * text / textSecondary / textTertiary / transparent。
     *
     * @return 解析出的颜色；写法不被接受时返回 {@code null}（调用方保持 token 默认值）
     */
    public static Integer styleColor(Context context, String token) {
        if (context == null || token == null) return null;
        String t = token.trim().toLowerCase(java.util.Locale.US).replace("_", "");
        switch (t) {
            case "surface":
            case "surfacevariant":
            case "card":
                return surface(context);
            case "outline":
            case "border":
            case "divider":
                return outline(context);
            case "brand":
            case "primary":
            case "accent":
                return brand(context);
            case "success":
                return success(context);
            case "warning":
                return warning(context);
            case "danger":
            case "error":
                return danger(context);
            case "text":
            case "textprimary":
                return textPrimary(context);
            case "textsecondary":
                return textSecondary(context);
            case "texttertiary":
            case "hint":
                return textTertiary(context);
            case "transparent":
            case "none":
                return android.graphics.Color.TRANSPARENT;
            default:
                // 十六进制/rgb() 等任意色值一律拒绝：宁可保持主题默认底色也不让模型改配色
                return null;
        }
    }

    private static int orFallback(int fromTheme, int fallback) {
        if (fromTheme == 0 || fromTheme == android.graphics.Color.TRANSPARENT) {
            return fallback;
        }
        return fromTheme;
    }
}
