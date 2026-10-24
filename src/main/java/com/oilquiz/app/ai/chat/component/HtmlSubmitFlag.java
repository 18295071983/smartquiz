package com.oilquiz.app.ai.chat.component;

import android.content.Context;

/**
 * HTML 卡页面内向 Agent 提交结果的开关（html submit 设计第 2 步，2026-10-21 建；**2026-10-24 翻默认**）。
 *
 * <p>沿用项目既有 SharedPreferences 口径（{@code ai_chat_prefs}、lower_snake_case key、
 * 读值包 try/catch 兜底默认），与 {@code PartsRendererFlag} / {@code LayoutCanvasConstraints} 同写法。</p>
 *
 * <p>建的时候默认 false：动的是 legacy 与 parts 共用的生产路径，且 JS↔native 端到端只能真机验，
 * 所以"先默认关、真机验通再翻"。2026-10-24 真机 prefs 里这条已开着跑通端到端（页面按钮 →
 * 本卡结果槽 → {@code get_result}），设计文档§7 的第 3、4 步（翻默认 + 把用法写进模型可见文案）
 * 一并落地，所以出厂默认改成 true。</p>
 *
 * <p><b>这是能力增量，边界要记牢</b>：只有档位是 {@code LOCAL_FILE}（工作区内的本地网页）、
 * 且这张卡创建时带 {@code actions}（否则连可寻址的 component_id 都没有）时，页面 JS 才能
 * {@code Android.submit(payload)} 往<b>自己这张卡</b>的结果槽写一个 ≤16KB 的不透明字符串。
 * id 由 native 绑定、JS 改不了，payload 不解析、不执行、不进任何执行路径。
 * 残余风险照设计文档§4：{@code get_result} 拿到的值多了一个"页面内容"这个来源，模型若据此
 * 执行高影响动作需自己校验——这条在工具输出上今天就成立。</p>
 *
 * <p>回滚：把这条 pref 置 false 即回到"submit 静默 no-op"的今天行为，其余四个低危方法
 * （showToast/copy/openLink/previewImage）不受本开关影响。</p>
 */
public final class HtmlSubmitFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "html_component_submit";

    /** 出厂默认档：开（2026-10-24 真机端到端验通后翻的；关掉即回到逐字节的旧行为） */
    static final boolean DEFAULT_ENABLED = true;

    private HtmlSubmitFlag() {
    }

    /**
     * 读取开关；读不到（Context 为空/异常）时按**默认档**返回，与 {@link PartsRendererFlag}
     * "读失败退回保守值"的口径一致——这里默认档是 true，两种兜底都跟着它走，
     * 免得同一种"读不到"在两处给出相反答案。
     */
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

    /** 写入开关（设置页/调试用）：出厂默认已是"开"，这条现在主要用于把能力**关掉**。 */
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
