package com.oilquiz.app.ai.chat.component;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;

/**
 * 组件色板（动作类 / 展示类组件统一取色）。
 *
 * <p><b>2026-11-16 改造</b>：原来每个方法都直接读 {@code @color/component_*} 固定资源，
 * 所以换肤、系统动态色、纯黑（OLED）都不影响组件层 —— 全项目 270 处引用，
 * 表现就是"主色换了、按钮/开关/输入框还是原来的颜色"。</p>
 *
 * <p>现在统一走「主题属性优先，资源兜底」：
 * <ol>
 *   <li>先读主题 attr（{@code colorControlText}、{@code colorPrimary}、{@code colorSuccess}…），
 *       这样 7 个预设色 + 24 色相网格 + 系统动态色 + OLED 都能生效；</li>
 *   <li>主题里没定义该 attr（老页面/自定义主题）时，退回原来的 {@code @color/component_*}，
 *       行为与改造前完全一致，不会因为缺属性变黑块。</li>
 * </ol>
 * 也就是说：这是一次"加了一层主题通路"的改造，默认外观零变化。</p>
 */
public final class ComponentColors {

    private ComponentColors() {
    }

    /** 主题属性优先，取不到再用资源兜底。 */
    private static int themed(Context context, int attrResId, int fallbackColorRes) {
        int c = ThemeColors.attr(context, attrResId);
        if (c != 0) {
            return c;
        }
        return ContextCompat.getColor(context, fallbackColorRes);
    }

    /** 主文字色 */
    public static int textPrimary(Context context) {
        return themed(context, R.attr.colorControlText, R.color.component_text_primary);
    }

    /** 次要文字色 */
    public static int textSecondary(Context context) {
        return themed(context, R.attr.colorControlTextSecondary, R.color.component_text_secondary);
    }

    /** 弱化文字色 */
    public static int textTertiary(Context context) {
        return themed(context, R.attr.colorControlTextTertiary, R.color.component_text_tertiary);
    }

    /** 强调色（按钮/链接） */
    public static int accent(Context context) {
        return themed(context, R.attr.colorPrimary, R.color.component_accent);
    }

    /** 成功色 */
    public static int success(Context context) {
        return themed(context, R.attr.colorSuccess, R.color.component_success);
    }

    /** 警告色 */
    public static int warning(Context context) {
        return themed(context, R.attr.colorWarning, R.color.component_warning);
    }

    /** 错误/危险色 */
    public static int error(Context context) {
        return themed(context, R.attr.colorDanger, R.color.component_error);
    }

    /** 组件底色（卡片/面板） */
    public static int background(Context context) {
        return themed(context, R.attr.colorBackground, R.color.component_bg);
    }

    /** 边框/描边色 */
    public static int border(Context context) {
        return themed(context, R.attr.colorBorder, R.color.component_border);
    }

    /** 输入框/控件底色 */
    public static int fieldBg(Context context) {
        return themed(context, R.attr.colorControlBackground, R.color.component_field_bg);
    }

    /** 评分空星色 */
    public static int ratingEmpty(Context context) {
        return themed(context, R.attr.colorOutline, R.color.component_rating_empty);
    }

    /** 图片占位底色 */
    public static int imagePlaceholder(Context context) {
        return themed(context, R.attr.colorSurfaceVariant, R.color.component_image_placeholder);
    }

    /** 图片加载失败底色 */
    public static int imageError(Context context) {
        return themed(context, R.attr.colorErrorContainer, R.color.component_image_error);
    }

    /** 强调色叠加底色（如按钮/表头高亮，10% 透明度） */
    public static int accentOverlay(Context context) {
        int accent = accent(context);
        return (accent & 0x00FFFFFF) | 0x1A000000;
    }

    // ==================== 层级 / 状态 / 语义容器（2026-11-18 补全）====================
    // 之前组件只有"底色/边框/输入底"三档，做层级只能各自写死 alpha 或借 text 色；
    // 这里把主题的 8 档表面、状态层、语义容器收成同一入口（attr 优先、资源兜底）。

    /** 表面层级：0=最低 … 4=最高（越界自动夹取）。 */
    public static int surfaceContainer(Context context, int level) {
        int attr;
        switch (level) {
            case 0:
                attr = R.attr.colorSurfaceContainerLowest;
                break;
            case 1:
                attr = R.attr.colorSurfaceContainerLow;
                break;
            case 3:
                attr = R.attr.colorSurfaceContainerHigh;
                break;
            case 4:
                attr = R.attr.colorSurfaceContainerHighest;
                break;
            case 2:
            default:
                attr = R.attr.colorSurfaceContainer;
                break;
        }
        return themed(context, attr, R.color.component_surface);
    }

    /** 压低层级用的表面（如禁用的整块区域）。 */
    public static int surfaceDim(Context context) {
        return themed(context, R.attr.colorSurfaceDim, R.color.component_bg);
    }

    /** 抬高/强调用的表面（如浮层底座）。 */
    public static int surfaceBright(Context context) {
        return themed(context, R.attr.colorSurfaceBright, R.color.component_surface);
    }

    /** 强边框（L3 标签/选中态描边，比 border 重一档）。 */
    public static int borderStrong(Context context) {
        return themed(context, R.attr.colorBorderStrong, R.color.component_border_strong);
    }

    /** 弹层遮罩。 */
    public static int scrim(Context context) {
        return themed(context, R.attr.colorScrim, R.color.component_scrim);
    }

    /** 状态层：hover（8%）。 */
    public static int stateHover(Context context) {
        return themed(context, R.attr.colorStateHover, R.color.state_hover);
    }

    /** 状态层：focus（12%）。 */
    public static int stateFocus(Context context) {
        return themed(context, R.attr.colorStateFocus, R.color.state_focus);
    }

    /** 状态层：pressed（12%）。 */
    public static int statePressed(Context context) {
        return themed(context, R.attr.colorStatePressed, R.color.state_pressed);
    }

    /** 状态层：dragged（16%）。 */
    public static int stateDragged(Context context) {
        return themed(context, R.attr.colorStateDragged, R.color.state_dragged);
    }

    /** 禁用态底色。 */
    public static int stateDisabledContainer(Context context) {
        return themed(context, R.attr.colorStateDisabledContainer, R.color.state_disabled_container);
    }

    /** 禁用态文字/图标。 */
    public static int stateDisabledContent(Context context) {
        return themed(context, R.attr.colorStateDisabledContent, R.color.state_disabled_content);
    }

    /** 错误容器 / 其上的文字（提示条、图片兜底共用同一语义）。 */
    public static int errorContainer(Context context) {
        return themed(context, R.attr.colorErrorContainer, R.color.error_container);
    }

    public static int onErrorContainer(Context context) {
        return themed(context, R.attr.colorOnErrorContainer, R.color.on_error_container);
    }

    /** 成功容器 / 其上的文字。 */
    public static int successContainer(Context context) {
        return themed(context, R.attr.colorSuccessContainer, R.color.success_container);
    }

    public static int onSuccessContainer(Context context) {
        return themed(context, R.attr.colorOnSuccessContainer, R.color.component_on_success_container);
    }

    /** 警告容器 / 其上的文字。 */
    public static int warningContainer(Context context) {
        return themed(context, R.attr.colorWarningContainer, R.color.warning_container);
    }

    public static int onWarningContainer(Context context) {
        return themed(context, R.attr.colorOnWarningContainer, R.color.component_on_warning_container);
    }

    /** 信息容器 / 其上的文字。 */
    public static int infoContainer(Context context) {
        return themed(context, R.attr.colorInfoContainer, R.color.info_container);
    }

    public static int onInfoContainer(Context context) {
        return themed(context, R.attr.colorOnInfoContainer, R.color.on_info_container);
    }

    /** 图表：网格线 / 轴线 / 标签。 */
    public static int chartGrid(Context context) {
        return themed(context, R.attr.colorChartGrid, R.color.component_chart_grid);
    }

    public static int chartAxis(Context context) {
        return themed(context, R.attr.colorChartAxis, R.color.component_chart_axis);
    }

    public static int chartLabel(Context context) {
        return themed(context, R.attr.colorChartLabel, R.color.component_chart_label);
    }

    /**
     * 语义化色：good/success/ok → 成功色，warn/warning → 警告色，bad/danger/error → 危险色，
     * 其它/空 → null（调用方保持默认文字色）。
     */
    public static Integer toneColor(Context context, String tone) {
        if (tone == null) return null;
        switch (tone.trim().toLowerCase(java.util.Locale.US)) {
            case "good":
            case "success":
            case "ok":
                return success(context);
            case "warn":
            case "warning":
                return warning(context);
            case "bad":
            case "danger":
            case "error":
            case "fail":
                return error(context);
            case "info":
            case "note":
            case "accent":
            case "brand":
            case "primary":
                return accent(context);
            case "muted":
            case "secondary":
            case "tertiary":
                return textSecondary(context);
            case "text":
                return textPrimary(context);
            default:
                return null;
        }
    }

    /** 图表配色：8 个主题图表色，取不到时回落到旧 chart_* 资源。 */
    public static int chartColor(Context context, int index) {
        int[] chartAttrs = {
                R.attr.colorChart1, R.attr.colorChart2, R.attr.colorChart3, R.attr.colorChart4,
                R.attr.colorChart5, R.attr.colorChart6, R.attr.colorChart7, R.attr.colorChart8
        };
        int[] fallback = {
                R.color.chart_1, R.color.chart_2, R.color.chart_3, R.color.chart_4,
                R.color.chart_5, R.color.chart_6, R.color.chart_7, R.color.chart_8
        };
        int i = Math.abs(index) % chartAttrs.length;
        return themed(context, chartAttrs[i], fallback[i]);
    }
}
