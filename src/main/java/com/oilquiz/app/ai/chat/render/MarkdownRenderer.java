package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.text.Spanned;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

/**
 * Markdown 渲染器（基于 Markwon 专业库）
 *
 * 支持：标准 Markdown、代码块、表格、删除线、任务列表、图片加载、自动链接。
 *
 * 使用前需调用 init(context) 初始化 Markwon 实例。
 */
public class MarkdownRenderer {

    private static Markwon markwon;
    private static volatile boolean initialized = false;

    /** 初始化 Markwon 实例（使用 Application Context，只需调用一次） */
    public static void init(Context context) {
        if (initialized && markwon != null) return;
        synchronized (MarkdownRenderer.class) {
            if (initialized && markwon != null) return;
            Context appContext = context.getApplicationContext();

            markwon = Markwon.builder(appContext)
                    .usePlugin(ImagesPlugin.create())
                    .usePlugin(TablePlugin.create(appContext))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(appContext))
                    .usePlugin(LinkifyPlugin.create())
                    .build();
            initialized = true;
        }
    }

    /** 确保 Markwon 已初始化，未初始化时用传入的 context 兜底 */
    private static void ensureInit(Context context) {
        if (!initialized && context != null) {
            init(context);
        }
    }

    /**
     * 渲染 Markdown 文本为 Spanned（供 TextView.setText 使用）
     * 注意：调用方需确保已 init，否则回退到纯文本。
     */
    public static Spanned render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new android.text.SpannableStringBuilder("");
        }
        if (!initialized) {
            return new android.text.SpannableStringBuilder(markdown);
        }
        return markwon.toMarkdown(markdown);
    }

    /**
     * 带上下文的渲染（确保已初始化）
     */
    public static Spanned render(String markdown, Context context) {
        ensureInit(context);
        return render(markdown);
    }

    /**
     * 获取纯文本（用于复制到剪贴板）
     */
    public static String toPlainText(String markdown) {
        if (markdown == null) return "";
        String text = markdown;
        text = text.replaceAll("```[\\s\\S]*?```", "");
        text = text.replaceAll("`([^`]+)`", "$1");
        text = text.replaceAll("^#{1,6}\\s+", "");
        text = text.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "$1");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("\\*(.+?)\\*", "$1");
        text = text.replaceAll("~~(.+?)~~", "$1");
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1");
        text = text.replaceAll("(?m)^>\\s+", "  ");
        return text.trim();
    }
}
