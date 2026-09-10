package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.text.Spanned;

/**
 * Markdown 渲染器：委托给 {@link MarkdownRenderer}（基于 Markwon + Prism4j）。
 *
 * 支持：标准 Markdown、代码语法高亮（20+ 语言）、表格、删除线、任务列表、
 * 图片加载、自动链接。
 */
public class MarkdownContentRenderer implements ContentRenderer {

    @Override
    public int getPriority() {
        return 10; // 最低优先级，作为兜底
    }

    @Override
    public boolean canRender(String segment) {
        // 可以渲染任何内容（作为 fallback）
        return segment != null && !segment.isEmpty();
    }

    @Override
    public Spanned render(String segment, Context context) {
        return MarkdownRenderer.render(segment, context);
    }

    @Override
    public Spanned render(String segment, Context context, int availableWidth) {
        // 带 context 的三参重载：确保 Markwon 已初始化，并处理流式未闭合代码围栏
        return MarkdownRenderer.render(segment, context, availableWidth);
    }

    @Override
    public String getName() {
        return "Markdown";
    }
}
