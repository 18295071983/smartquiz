package com.oilquiz.app.ai.chat.component;

import android.content.Context;

/**
 * HTML 卡页面内向 Agent 提交结果的开关（html submit 设计第 2 步，2026-10-21）。
 *
 * <p>沿用项目既有 SharedPreferences 口径（{@code ai_chat_prefs}、lower_snake_case key、
 * 读值包 try/catch 兜底默认），与 {@code PartsRendererFlag} / {@code LayoutCanvasConstraints} 同写法。</p>
 *
 * <p><b>默认 false</b>：关掉时桥的 {@code submit} 拿到的是空回传地址 → 直接 no-op，
 * 其余四个低危方法（showToast/copy/openLink/previewImage）不受影响，现网行为逐字节同今天。
 * 开这条开关的意义是：动的是 legacy 与 parts 共用的生产路径，且 JS↔native 端到端只能真机验，
 * 所以先默认关、真机验通再翻。</p>
 */
public final class HtmlSubmitFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "html_component_submit";

    private HtmlSubmitFlag() {
    }

    /** 读取开关；context 为空或读失败按默认档（关闭）返回。 */
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
