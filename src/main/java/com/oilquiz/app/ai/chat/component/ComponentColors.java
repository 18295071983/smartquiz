package com.oilquiz.app.ai.chat.component;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.oilquiz.app.R;

import com.oilquiz.app.theme.ThemeColors;
/**
 * 组件色板：统一从 colors.xml 读取，避免代码中硬编码颜色。
 */
public final class ComponentColors {

    private ComponentColors() {
    }

    /** 主文字色 */
    public static int textPrimary(Context context) {
        return ContextCompat.getColor(context, R.color.component_text_primary);
    }

    /** 次要文字色 */
    public static int textSecondary(Context context) {
        return ContextCompat.getColor(context, R.color.component_text_secondary);
    }

    /** 弱化文字色 */
    public static int textTertiary(Context context) {
        return ContextCompat.getColor(context, R.color.component_text_tertiary);
    }

    /** 强调色（按钮/链接） */
    public static int accent(Context context) {
        return ContextCompat.getColor(context, R.color.component_accent);
    }

    /** 成功色 */
    public static int success(Context context) {
        return ContextCompat.getColor(context, R.color.component_success);
    }

    /** 警告色 */
    public static int warning(Context context) {
        return ContextCompat.getColor(context, R.color.component_warning);
    }

    /** 错误色 */
    public static int error(Context context) {
        return ContextCompat.getColor(context, R.color.component_error);
    }

    /** 卡片背景色 */
    public static int background(Context context) {
        return ContextCompat.getColor(context, R.color.component_bg);
    }

    /** 卡片边框色 */
    public static int border(Context context) {
        return ContextCompat.getColor(context, R.color.component_border);
    }

    /** 表单字段背景（日期/时间选择器输入底） */
    public static int fieldBg(Context context) {
        return ContextCompat.getColor(context, R.color.component_field_bg);
    }

    /** 评分未选中星色 */
    public static int ratingEmpty(Context context) {
        return ContextCompat.getColor(context, R.color.component_rating_empty);
    }

    /** 图片加载占位色 */
    public static int imagePlaceholder(Context context) {
        return ContextCompat.getColor(context, R.color.component_image_placeholder);
    }

    /** 图片加载失败底色 */
    public static int imageError(Context context) {
        return ContextCompat.getColor(context, R.color.component_image_error);
    }

    /** 强调色叠加底色（如按钮/表头高亮，10% 透明度） */
    public static int accentOverlay(Context context) {
        int accent = accent(context);
        return (accent & 0x00FFFFFF) | 0x1A000000;
    }

    /** 图表系列色（循环取色） */
    public static int chartColor(Context context, int index) {
        int[] chartColors = {
                R.color.chart_1, R.color.chart_2, R.color.chart_3, R.color.chart_4,
                R.color.chart_5, R.color.chart_6, R.color.chart_7, R.color.chart_8
        };
        return ContextCompat.getColor(context, chartColors[index % chartColors.length]);
    }
}
