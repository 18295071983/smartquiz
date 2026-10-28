package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

/**
 * 流式（生成中）消息是否也交给 parts 渲染（P6 流式接管，2026-10-24）。
 *
 * <p>它是 {@link PartsRendererFlag} 的<b>子开关</b>：{@code parts_renderer=false} 时整条链路都不接管，
 * 本开关自然无效；{@code parts_renderer=true}（父开关出厂档已于 2026-10-24 P4 翻成开）而本开关为 false 时，
 * 维持改造前的现状——只有已完成的消息走 parts，生成中的仍走 legacy，落定那一刻才会切换长相。</p>
 *
 * <p>为什么要单独一个开关：流式接管换了正文的顺序真值（按 {@code contentRoundBounds} 切片而不是按
 * {@code roundSnapshots} 重建），并且要把 legacy 的思考行/组件容器在生成期间也让路，
 * 这两件事都是"每 tick 重绑"引出的风险，出问题时必须能单独退回，而不必连整条 parts 一起关。</p>
 *
 * <p>沿用项目既有口径（{@code ai_chat_prefs}、lower_snake_case 键、读值包 try/catch 兜底默认），
 * 与 {@link PartsRendererFlag} / {@link com.oilquiz.app.ai.chat.component.HtmlSubmitFlag} 同写法。
 * 产品里没有本开关的设置页入口（与 {@code parts_renderer} 一样只能改 prefs）。</p>
 */
public final class PartsStreamingFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "parts_streaming";

    /**
     * 出厂默认档。打开是用户在 2026-10-24 的明确要求（"你修好就打开"），
     * 前提是流式接管的单测与回归都过；退回办法就是把该键显式写成 false。
     */
    static final boolean DEFAULT_ENABLED = true;

    private PartsStreamingFlag() {
    }

    /** 读取开关；context 为空或读失败时按默认档返回。 */
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
}
