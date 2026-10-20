package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

/**
 * 聊天页改版开关（Track A 的灰度闸门）。
 *
 * <p>{@code chat_ui_v2} 自 2026-10-20 起默认<b>开</b>：v2 已是实际默认界面，开关留作
 * 合并完成前的退路（出问题能当场切回旧版，不必回滚代码）。开着时影响聊天页外壳与消息渲染。
 *
 * <p>开关位置：聊天页左侧工具抽屉的「聊天页：新版 / 旧版」（{@link ChatUiSetting}），
 * 选中后立即重建页面。旧版的入口在「⋯」面板里、从旧版切不到新版，已移除。
 */
public final class ChatUiFlags {

    private static final String PREFS = "chat_ui_prefs";
    private static final String KEY_UI_V2 = "chat_ui_v2";

    private ChatUiFlags() {
    }

    /** 新版聊天页是否启用（默认开；合并完成后本类整体删除） */
    public static boolean isUiV2Enabled(Context context) {
        if (context == null) return false; // 没有 Context 就取不到 pref，不要凭默认值把界面改成新版
        return prefs(context).getBoolean(KEY_UI_V2, true);
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
