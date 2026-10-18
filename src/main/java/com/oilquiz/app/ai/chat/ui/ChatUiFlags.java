package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

/**
 * 聊天页改版开关（Track A 的灰度闸门）。
 *
 * <p>{@code chat_ui_v2} 默认<b>关</b>：开着时只影响聊天页外壳（顶栏/技术信息下沉/输入区密度），
 * 消息渲染与推理链路不动。这样新旧两套界面可以并行跑一段，出问题时把开关关掉即可回到原样，
 * 不需要回滚代码。
 *
 * <p>开关位置：聊天页右上「⋯ → 界面」，或 adb：
 * {@code adb shell run-as com.oilquiz.app} 改 shared_prefs 后重进页面。
 */
public final class ChatUiFlags {

    private static final String PREFS = "chat_ui_prefs";
    private static final String KEY_UI_V2 = "chat_ui_v2";

    private ChatUiFlags() {
    }

    /** 新版聊天页外壳是否启用（默认关） */
    public static boolean isUiV2Enabled(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_UI_V2, false);
    }

    public static void setUiV2Enabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_UI_V2, enabled).apply();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
