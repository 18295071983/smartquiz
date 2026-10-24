package com.oilquiz.app.ai.chat.render;

import android.content.Context;

/**
 * 轮内流式正文的块级尾巴开关（P7，2026-10-25）。
 *
 * <p>它是 {@code web_markdown_render} 的子开关：WebView 正文路径没开时本开关无效，
 * 80ms 节流刷新也不能放开（否则会把 3cab5a48 的“逐 token 全文重排”放回来）。
 * 出厂档 <b>true</b>（2026-10-25 用户显式指令“打开默认 true”）；产品里没有设置页入口，
 * 回退只能显式把 {@code ai_chat_prefs.markdown_stream_tail} 写成 false。</p>
 *
 * <p>沿用项目既有口径（{@code ai_chat_prefs}、lower_snake_case 键、读值包 try/catch
 * 兜底默认），与 {@link com.oilquiz.app.ai.chat.parts.PartsRendererFlag} /
 * {@code HtmlSubmitFlag} 同写法。</p>
 *
 * <p>打开后只改变“生成中的正文怎么上屏”：Java 侧仍用
 * {@link MarkdownStreamSplitter} 切块，{@code MarkdownWebView} 只替换尾巴；定稿仍走整篇
 * {@link MarkdownHtmlConverter#toBodyHtml(String)}。关闭时所有路径与改造前逐字一致。</p>
 */
public final class MarkdownStreamTailFlag {

    static final String PREFS_NAME = "ai_chat_prefs";
    static final String PREF_KEY = "markdown_stream_tail";

    /** 出厂档：开。显式写 false 回退到原轮次批量上屏（无设置页入口，只能改 prefs）。 */
    static final boolean DEFAULT_ENABLED = true;

    private MarkdownStreamTailFlag() {
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
