package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

/**
 * parts 渲染器总开关（parts 架构升级 P2，2026-10-21；出厂档由 P4 于 2026-10-24 翻成开）。
 *
 * <p>沿用项目既有 SharedPreferences 口径（{@code ai_chat_prefs}、lower_snake_case key、
 * 读值包 try/catch 兜底默认），对齐 {@code LayoutCanvasConstraints.enabled} 与
 * {@link com.oilquiz.app.ai.chat.component.HtmlSubmitFlag} 的写法。</p>
 *
 * <p><b>默认 true（P4，2026-10-24 用户指令"出厂仍是 false，也打开"）</b>：P0–P3j 的接管链路、
 * 历史恢复归一（§11.9）与流式接管（§11.10）都已落地并跑过全量回归，出厂即生效。
 * 代价要说清：产品里<b>没有</b>本开关的设置页入口，所以关掉只能显式把 prefs 键写成 false，
 * 老用户没有可点开的退路；legacy 那条渲染路径（{@code contentRoundBounds} 组装 +
 * {@code bindComponents}）仍然完整保留在代码里，就是这条退路本身，P5 之前不许删。</p>
 */
public final class PartsRendererFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "parts_renderer";

    /** 出厂档：开。显式写 false 才回 legacy（无设置页入口，只能改 prefs）。 */
    static final boolean DEFAULT_ENABLED = true;

    private PartsRendererFlag() {
    }

    /** 读取开关；context 为空或读失败时按默认档（开）返回。 */
    public static boolean enabled(Context context) {
        if (context == null) {
            return DEFAULT_ENABLED;
        }
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY, DEFAULT_ENABLED);
        } catch (Throwable t) {
            return DEFAULT_ENABLED;
        }
    }

    /** 写入开关，供设置页/调试入口显式开启（固化落盘需显式选择）。 */
    public static void setEnabled(Context context, boolean value) {
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_KEY, value).apply();
        } catch (Throwable ignored) {
        }
    }
}
