package com.oilquiz.app.ai.python;

import android.content.Context;

/**
 * 画布默认装饰主题解析器（预留接口）。
 *
 * <p>设计目标：动态画布（layout_canvas）的默认设计<strong>跟随项目主题系统动态变化</strong>——
 * 颜色一律取自主题资源（values/colors.xml 与 values-night/colors.xml），不硬编码：
 * - 深浅模式：values-night 覆盖同名资源，getColor 自动随系统深浅切换；
 * - 主题换肤：只要主题资源值变化（如品牌色调整、自定义皮肤），画布默认装饰自动跟随；
 * - 助手显式传 style 时始终优先，本解析器只做"没传样式"时的兜底。
 *
 * <p>扩展点：如需为画布引入独立皮肤/主题体系，替换 {@link #DEFAULT} 实现即可
 * （或在本接口上增加按 componentId / props 解析的默认实现），调用方无需改动。</p>
 */
public interface CanvasThemeResolver {

    /** 画布默认装饰色板（一次解析快照） */
    final class CanvasPalette {
        /** 画布背景色（#RRGGBB） */
        public final String background;
        /** 画布描边（"1 #RRGGBB" 格式，供 layout style.border 直接使用） */
        public final String border;
        /** 空画布骨架·标题文本色 */
        public final String title;
        /** 空画布骨架·副标题文本色 */
        public final String subtitle;
        /** 空画布骨架·引导提示文本色 */
        public final String hint;
        /** 圆角（dp） */
        public final int radius;
        /** 内边距（dp） */
        public final int padding;
        /** 控件间距（dp，注入 layout 顶层 spacing） */
        public final int spacing;

        public CanvasPalette(String background, String border, String title,
                             String subtitle, String hint,
                             int radius, int padding, int spacing) {
            this.background = background;
            this.border = border;
            this.title = title;
            this.subtitle = subtitle;
            this.hint = hint;
            this.radius = radius;
            this.padding = padding;
            this.spacing = spacing;
        }
    }

    /** 解析当前主题下的画布默认色板 */
    CanvasPalette resolve(Context context);

    // ==================== 全局 resolver 注册（预留接口接入点） ====================
    // PythonToolManager 通过 getResolver() 取色，自身零改动。
    // 未来接入独立画布皮肤/主题体系：在 Application 或主题初始化处调用
    //   CanvasThemeResolver.setResolver(自定义皮肤实现);
    // 之后所有动态画布的默认装饰自动走自定义皮肤；不设置则始终走 DEFAULT（主题资源）。

    /** 当前生效的 resolver（null=走默认主题资源实现），volatile 保证跨线程可见 */
    java.util.concurrent.atomic.AtomicReference<CanvasThemeResolver> sResolver =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /** 注册自定义画布皮肤/主题解析器（替换默认主题资源实现）。传 null 恢复默认。 */
    static void setResolver(CanvasThemeResolver resolver) {
        sResolver.set(resolver);
    }

    /** 获取当前生效的画布皮肤/主题解析器（默认：主题资源实现） */
    static CanvasThemeResolver getResolver() {
        CanvasThemeResolver r = sResolver.get();
        return r != null ? r : DEFAULT;
    }

    /**
     * 默认实现：从项目主题资源读取（component_bg / component_border /
     * component_text_primary|secondary|tertiary），
     * 深浅模式由 values-night 同名资源自动覆盖，主题资源变更自动跟随。
     */
    CanvasThemeResolver DEFAULT = new CanvasThemeResolver() {
        @Override
        public CanvasPalette resolve(Context context) {
            String bg = toHex(androidx.core.content.ContextCompat.getColor(context,
                    com.oilquiz.app.R.color.component_bg));
            String border = toHex(androidx.core.content.ContextCompat.getColor(context,
                    com.oilquiz.app.R.color.component_border));
            String title = toHex(androidx.core.content.ContextCompat.getColor(context,
                    com.oilquiz.app.R.color.component_text_primary));
            String subtitle = toHex(androidx.core.content.ContextCompat.getColor(context,
                    com.oilquiz.app.R.color.component_text_secondary));
            String hint = toHex(androidx.core.content.ContextCompat.getColor(context,
                    com.oilquiz.app.R.color.component_text_tertiary));
            return new CanvasPalette(bg, "1 " + border, title, subtitle, hint, 16, 16, 12);
        }

        private String toHex(int color) {
            return String.format("#%06X", 0xFFFFFF & color);
        }
    };
}
