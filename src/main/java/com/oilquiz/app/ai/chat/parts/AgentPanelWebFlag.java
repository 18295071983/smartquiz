package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

/**
 * 执行活动区的 WebView 组件开关（PoC，2026-11-18）。
 *
 * <p>开：思考手风琴改用内嵌 WebView 渲染（{@code assets/agent_panel/agent_panel.html}），
 * 用来验证"用 web 组件承载 agent 执行区"的可行性与性能；关：走原生
 * {@link ThinkingPartRenderer}（默认）。</p>
 *
 * <p>出厂关：PoC 阶段只给调试入口与显式开关用，不影响现网。</p>
 */
public final class AgentPanelWebFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "agent_panel_web";
    static final boolean DEFAULT_ENABLED = false;

    private AgentPanelWebFlag() {
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
            com.oilquiz.app.util.AILogger.w("AgentPanelWebFlag", "开关没落盘: " + t);
        }
    }
}
