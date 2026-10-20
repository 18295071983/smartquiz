package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

/**
 * parts 渲染器总开关（parts 架构升级 P2，2026-10-21）。
 *
 * <p>沿用项目既有 SharedPreferences 口径（{@code ai_chat_prefs}、lower_snake_case key、
 * 读值包 try/catch 兜底默认），对齐 {@code LayoutCanvasConstraints.enabled} 的写法。</p>
 *
 * <p><b>默认 false</b>：新渲染体系只建基础设施，不改变现网行为。ChatAdapter 接线与
 * "flag=true 与旧 UI 一致"的端到端验证在 P3 落地；P2 阶段 flag=false 即完全走旧路径。</p>
 */
public final class PartsRendererFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "parts_renderer";

    private PartsRendererFlag() {
    }

    /** 读取开关；context 为空或读失败时按默认档（关闭）返回。 */
    public static boolean enabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY, false);
        } catch (Throwable t) {
            return false;
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
