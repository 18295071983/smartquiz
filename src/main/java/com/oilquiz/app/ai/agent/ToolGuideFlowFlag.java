package com.oilquiz.app.ai.agent;

import android.content.Context;

/**
 * 引导流程改读 {@code tool_guide_flow.json} 的总开关（PoC 档，2026-10-28）。
 *
 * <p>沿用项目既有 SharedPreferences 口径（{@code ai_chat_prefs}、lower_snake_case key、
 * 读值包 try/catch 兜默认档），写法对齐
 * {@link com.oilquiz.app.ai.chat.parts.PartsRendererFlag} 与
 * {@link com.oilquiz.app.ai.chat.component.HtmlSubmitFlag}。</p>
 *
 * <p><b>默认 false（关）</b>：接管路径还没在真机上验过，出厂维持硬编码 builder。
 * 打开后也只有 {@code tool_guide_flow.json} 里 {@code tools} 段列出的工具走配置
 * （PoC 阶段是 {@code ai_weather}/{@code network_search}/{@code database} 三个），
 * 没列到的工具照旧走 builder，所以开关打开不等于全量切换。</p>
 *
 * <p>关这条路的唯一后果是显示层：{@link ToolGuideFlow} 的步骤内容、
 * 下发给工具的参数名与值都不受开关影响（配置本身就是逐字段等价搬过去的，
 * 等价性由 {@code ToolGuideFlowConverterTest} 钉着）。产品里没有本开关的设置页入口，
 * 关掉/打开只能显式把 prefs 键写成 false/true——这是刻意的，PoC 阶段不给它一个显眼的按钮。</p>
 */
public final class ToolGuideFlowFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "guide_config_json";

    /** 出厂档：关。显式写 true 才用配置接管。 */
    static final boolean DEFAULT_ENABLED = false;

    private ToolGuideFlowFlag() {
    }

    /** 读取开关；context 为空或读失败时按默认档（关）返回。 */
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
                    .edit()
                    .putBoolean(PREF_KEY, value)
                    .apply();
        } catch (Throwable t) {
            // 写不进去就维持现状，不抛
        }
    }
}
