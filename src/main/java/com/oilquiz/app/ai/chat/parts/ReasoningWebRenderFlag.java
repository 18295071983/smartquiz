package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

/**
 * 思考正文 WebView 渲染开关（2026-10-25，思考正文接入轮内流式）。
 *
 * <p>它回答的是"思考正文（不是回答正文）在流式期间用哪条渲染通路"：</p>
 * <ul>
 *   <li><b>true（出厂档）</b>：{@link ReasoningPartRenderer} 与 text part 同款——正文区换成
 *       {@link com.oilquiz.app.ai.chat.ui.MarkdownWebView}，流式期 {@code setContent(text, true)}
 *       走块级尾巴（与 {@code markdown_stream_tail} 同一套切分器），定稿 {@code setContent(text, false)}
 *       整篇重载。</li>
 *   <li><b>false</b>：退回 2026-10-25 之前的"实时整块刷新"——流式期纯 {@code TextView.setText}，
 *       定稿才交宿主 markdown。</li>
 * </ul>
 *
 * <p>为什么单独一个键：思考正文是折叠行里的第二段（header 只显示状态），WebView 的高度回调、
 * 收起时延迟加载、以及 RecyclerView 复用都只在这一段上生效。真机上若出现高度抖动/空白，
 * 只需写 {@code ai_chat_prefs.reasoning_web_render=false} 单独退回思考正文，
 * 不必连 {@code web_markdown_render} / {@code parts_renderer} 一起关。</p>
 *
 * <p>沿用项目既有口径（{@code ai_chat_prefs}、lower_snake_case key、读值包 try/catch 兜底默认），
 * 与 {@link PartsStreamingFlag} / {@link com.oilquiz.app.ai.chat.render.MarkdownStreamTailFlag}
 * 同写法。产品里没有本开关的设置页入口，只能显式改 prefs。</p>
 */
public final class ReasoningWebRenderFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "reasoning_web_render";

    /** 出厂档：开（思考正文与正文同一条流式渲染通路）。 */
    static final boolean DEFAULT_ENABLED = true;

    private ReasoningWebRenderFlag() {
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

    /** 写入开关，供调试入口/测试显式切换（固化落盘需显式选择）。 */
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
