package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.text.Spanned;

/**
 * HTML 富文本渲染器：委托给 Markwon 的 HtmlPlugin 处理内嵌 HTML。
 *
 * 支持：基础 HTML 标签（<b>, <i>, <u>, <a>, <img>, <table>, <div> 等）。
 * Markwon 的 HtmlPlugin 会将 HTML 标签转换为对应的 Android Span。
 */
public class HtmlContentRenderer implements ContentRenderer {

    @Override
    public int getPriority() {
        return 70;
    }

    @Override
    public boolean canRender(String segment) {
        return segment != null && !segment.isEmpty();
    }

    @Override
    public Spanned render(String segment, Context context) {
        // 用 Markwon 渲染 HTML（Markwon 原生支持 HTML 内嵌）
        return MarkdownRenderer.render(segment, context);
    }

    @Override
    public String getName() {
        return "HTML";
    }
}
