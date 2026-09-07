package com.oilquiz.app.theme;

import com.oilquiz.app.R;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import com.oilquiz.app.SmartQuizApplication;

/**
 * 硬编码色资源化取色工具：读取 hc_* 颜色资源（values + values-night 双套）。
 * 历史硬编码色（Java 中 0xFFxxxxxx / Color.parseColor）统一迁移为资源引用后，
 * 通过本工具获取，使深色模式自动适配。
 */
public final class ThemeColors {

    private ThemeColors() {
    }

    /** 读取颜色资源（自动适配深浅色） */
    public static int get(int resId) {
        return SmartQuizApplication.getAppContext().getColor(resId);
    }

    /** 供无 Application 上下文的静态场景使用 */
    public static int get(Resources res, int resId) {
        return res.getColor(resId);
    }

    /**
     * 从指定 context 的主题读取颜色属性（?attr/colorXxx）。
     * 跟随当前 Activity 已叠加的主题：换肤（31 档位）、深浅色、系统动态色都生效。
     * 属性未定义或解析失败返回 0。
     */
    public static int attr(Context context, int attrResId) {
        if (context == null) {
            return 0;
        }
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                return context.getResources().getColor(tv.resourceId);
            }
        }
        return 0;
    }

    /**
     * 语义色取色总入口：传入 colors.xml 的语义色资源 id，
     * 主题语义色（primary/on_surface/text_* 等）自动改从当前主题 attr 解析
     * （跟随换肤/深浅/动态色），其余颜色（语义状态色、图表色等）原样返回。
     * 供 Java 动态创建控件统一使用，替代 context.getColor()。
     */
    public static int get(Context context, int resId) {
        if (context == null) {
            return 0;
        }
        if (resId == R.color.primary || resId == R.color.primary_color) {
            return attr(context, R.attr.colorPrimary);
        }
        if (resId == R.color.primary_dark) {
            return attr(context, R.attr.colorPrimaryDark);
        }
        if (resId == R.color.primary_light || resId == R.color.primary_container) {
            return attr(context, R.attr.colorPrimaryContainer);
        }
        if (resId == R.color.on_primary) {
            return attr(context, R.attr.colorOnPrimary);
        }
        if (resId == R.color.on_primary_container) {
            return attr(context, R.attr.colorOnPrimaryContainer);
        }
        if (resId == R.color.secondary) {
            return attr(context, R.attr.colorSecondary);
        }
        if (resId == R.color.secondary_container) {
            return attr(context, R.attr.colorSecondaryContainer);
        }
        if (resId == R.color.surface) {
            return attr(context, R.attr.colorSurface);
        }
        if (resId == R.color.surface_variant) {
            return attr(context, R.attr.colorSurfaceVariant);
        }
        if (resId == R.color.on_surface) {
            return attr(context, R.attr.colorOnSurface);
        }
        if (resId == R.color.outline) {
            return attr(context, R.attr.colorOutline);
        }
        if (resId == R.color.card_background) {
            return attr(context, R.attr.colorCardBackground);
        }
        if (resId == R.color.text_primary) {
            return attr(context, R.attr.colorControlText);
        }
        if (resId == R.color.text_secondary) {
            return attr(context, R.attr.colorControlTextSecondary);
        }
        if (resId == R.color.text_tertiary || resId == R.color.text_hint) {
            return attr(context, R.attr.colorControlTextHint);
        }
        if (resId == R.color.background) {
            return attr(context, R.attr.colorBackground);
        }
        if (resId == R.color.on_background) {
            return attr(context, R.attr.colorOnBackground);
        }
        return context.getColor(resId);
    }
}
