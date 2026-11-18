package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

/**
 * 思考手风琴开关（2026-11-18；当日改为<b>默认关</b>）。
 *
 * <p><b>为什么默认关</b>：把 reasoning 和 tool_group 混进同一个 thinking part 后，真机出现
 * "一组里既有思考又有工具、折叠故障/多个事件被折在一起"的问题——混合类型的折叠体在
 * 流式重绑时整块重建，用户收起/展开的语义也不清楚。按事件类型分开折叠（思考一个、
 * 工具组一个）每个折叠只含一种内容，状态互不干扰，是更稳的结构。</p>
 *
 * <p>开：渲染前把相邻 reasoning / tool_group 合并成一个 {@code thinking} part（实验结构，
 * 仅留作对照）；关（出厂）：按类型分开，分别走 {@link ReasoningPartRenderer} /
 * {@link ToolGroupPartRenderer}。</p>
 *
 * <p>沿用项目既有 SharedPreferences 口径（{@code ai_chat_prefs}、lower_snake_case key、
 * 读值包 try/catch 兜底默认），与 {@link PartsRendererFlag} 一致。</p>
 */
public final class ThinkingGroupFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "thinking_group";

    /** 出厂档：关——按事件类型分开折叠（混合折叠只留作对照）。 */
    static final boolean DEFAULT_ENABLED = false;

    private ThinkingGroupFlag() {
    }

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

    public static void setEnabled(Context context, boolean value) {
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_KEY, value).apply();
        } catch (Throwable t) {
            com.oilquiz.app.util.AILogger.w("ThinkingGroupFlag",
                    "思考手风琴开关没落盘（下次启动回默认档）: " + t);
        }
    }
}
