package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ScaleXSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.request.target.Target;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.image.AsyncDrawable;
import io.noties.markwon.image.ImageSize;
import io.noties.markwon.image.glide.GlideImagesPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
/**
 * Markdown 渲染器（基于 Markwon 专业库）
 *
 * 支持：标准 Markdown、代码块、表格、删除线、任务列表、图片加载、自动链接。
 * 表格单元格长文本自动换行。
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

            // 图片最大显示尺寸：宽度 = 屏幕宽 88%，高度 = 屏幕高 60%（防止大图撑爆气泡）
            DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
            final int maxImageWidth = (int) (dm.widthPixels * 0.88f);
            final int maxImageHeight = (int) (dm.heightPixels * 0.6f);

            // 自定义 Glide 加载：解码时限制图片尺寸，避免大图超出聊天气泡
            GlideImagesPlugin.GlideStore glideStore = new GlideImagesPlugin.GlideStore() {
                @Override
                public RequestBuilder<Drawable> load(@NonNull AsyncDrawable drawable) {
                    // 旧 Markwon 路径同样要解析裸文件名/相对路径：Glide 不认相对路径，
                    // 解析到实体文件就传 File，否则原样传（http/content 由 Glide 自己处理）
                    Object imageModel = drawable.getDestination();
                    try {
                        java.io.File local = com.oilquiz.app.ai.chat.component.MediaPathResolver
                                .resolveToFile(appContext, String.valueOf(imageModel));
                        if (local != null) imageModel = local;
                    } catch (Throwable ignored) {
                    }
                    RequestBuilder<Drawable> builder = Glide.with(appContext)
                            .asDrawable()
                            .load(imageModel);
                    // Markdown 里已指定尺寸（![](... =WxH)）→ 超限则按比例缩放
                    if (drawable.hasKnownDimensions()) {
                        ImageSize size = drawable.getImageSize();
                        boolean pxUnit = (size.width.unit == null || "px".equals(size.width.unit))
                                && (size.height.unit == null || "px".equals(size.height.unit));
                        if (pxUnit) {
                            int w = (int) size.width.value;
                            int h = (int) size.height.value;
                            if (w > 0 && h > 0) {
                                if (w <= maxImageWidth && h <= maxImageHeight) {
                                    return builder;
                                }
                                float scale = Math.min((float) maxImageWidth / w, (float) maxImageHeight / h);
                                return builder.override((int) (w * scale), (int) (h * scale));
                            }
                        }
                    }
                    // 未指定尺寸 → 限制在最大范围内，保持宽高比
                    return builder.override(maxImageWidth, maxImageHeight).centerInside();
                }

                @Override
                public void cancel(@NonNull Target<?> target) {
                    Glide.with(appContext).clear(target);
                }
            };

            // 配置表格主题：支持自动换行 + 清晰边框 + 合适内边距
            TableTheme tableTheme = new TableTheme.Builder()
                    .tableCellPadding(8)
                    .tableBorderWidth(1)
                    .tableBorderColor(ThemeColors.get(R.color.hc_ff999999))
                    .tableHeaderRowBackgroundColor(ThemeColors.get(R.color.hc_1a000000))  // 表头浅灰背景
                    .tableOddRowBackgroundColor(ThemeColors.get(R.color.hc_00000000))     // 奇数行透明
                    .tableEvenRowBackgroundColor(ThemeColors.get(R.color.hc_08000000))    // 偶数行微灰
                    .build();

            // Prism4j 代码语法高亮（内部组件：markwon-syntax-highlight + prism4j-bundler 已引入）
            // 语法包由 AiPrismBundle（@PrismBundle）声明，bundler 生成同包 GrammarLocatorDef
            final io.noties.prism4j.Prism4j prism4j = new io.noties.prism4j.Prism4j(
                    new com.oilquiz.app.ai.chat.render.GrammarLocatorDef());
            // 深色代码块主题（与 CodeCardView 风格一致，深浅模式均可读）
            final io.noties.markwon.syntax.Prism4jTheme prismTheme =
                    io.noties.markwon.syntax.Prism4jThemeDarkula.create();

            markwon = Markwon.builder(appContext)
                    .usePlugin(GlideImagesPlugin.create(glideStore))
                    .usePlugin(HtmlPlugin.create())
                    .usePlugin(TablePlugin.create(tableTheme))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(appContext))
                    .usePlugin(LinkifyPlugin.create())
                    .usePlugin(io.noties.markwon.syntax.SyntaxHighlightPlugin.create(prism4j, prismTheme))
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
     * @param markdown Markdown内容
     * @param availableWidth 实际可用宽度（像素），当前保留用于兼容调用方
     */
    public static Spanned render(String markdown, int availableWidth) {
        return render(markdown, (Context) null, availableWidth);
    }

    /**
     * 渲染 Markdown 文本为 Spanned（供 TextView.setText 使用）
     * 注意：调用方需确保已 init，否则回退到纯文本。
     */
    public static Spanned render(String markdown) {
        return render(markdown, (Context) null, 0);
    }

    /**
     * 带上下文的渲染（确保已初始化）
     */
    public static Spanned render(String markdown, Context context) {
        return render(markdown, context, 0);
    }

    /**
     * 带上下文 + 可用宽度的渲染（确保已初始化）。
     * 流式生成期间自动闭合未完成的代码围栏（``` 奇数个时补 ```），
     * 避免 Markwon 把围栏之后的正文整段当作代码块渲染（生成中样式错乱/闪烁）。
     */
    public static Spanned render(String markdown, Context context, int availableWidth) {
        if (markdown == null || markdown.isEmpty()) {
            return new android.text.SpannableStringBuilder("");
        }
        ensureInit(context);
        if (!initialized) {
            return new android.text.SpannableStringBuilder(markdown);
        }
        String safeMarkdown = closeUnclosedCodeFence(markdown);
        Spanned result = markwon.toMarkdown(safeMarkdown);
        // 替换 URLSpan 为自定义 Span，支持 content:// URI 点击
        return replaceUrlSpans(result);
    }

    /**
     * 检测未闭合的代码围栏：行首 ``` 出现奇数次时，在末尾补一个 ``` 临时闭合。
     * 流式生成中（代码块开头已输出、结束 ``` 尚未到达）Markwon 会把围栏之后
     * 的所有内容（含已生成的正文）整体解析为代码块，表现为生成中整段变等宽/深色、
     * 生成完成瞬间才恢复——补闭合后尾部（进行中的代码内容）以代码块样式渲染，
     * 前面的正文保持正常排版，且渲染稳定不闪烁。
     */
    private static String closeUnclosedCodeFence(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        int fenceCount = 0;
        for (String line : markdown.split("\n", -1)) {
            String t = line.trim();
            // 仅行首 ``` 计入围栏（含 ```java 等语言标记；行内反引号不参与）
            if (t.startsWith("```")) {
                fenceCount++;
            }
        }
        return (fenceCount & 1) == 1 ? markdown + "\n```" : markdown;
    }

    /**
     * 将 Spanned 中的 URLSpan 替换为自定义的 FileSafeUrlSpan，
     * 使 content:// URI 点击时携带 FLAG_GRANT_READ_URI_PERMISSION
     */
    private static Spanned replaceUrlSpans(Spanned spanned) {
        if (!(spanned instanceof Spannable)) {
            // 复制为可编辑的 Spannable
            Spannable spannable = new android.text.SpannableStringBuilder(spanned);
            doReplaceUrlSpans(spannable);
            return spannable;
        }
        Spannable spannable = (Spannable) spanned;
        doReplaceUrlSpans(spannable);
        return spannable;
    }

    private static void doReplaceUrlSpans(Spannable spannable) {
        URLSpan[] urlSpans = spannable.getSpans(0, spannable.length(), URLSpan.class);
        for (URLSpan span : urlSpans) {
            int start = spannable.getSpanStart(span);
            int end = spannable.getSpanEnd(span);
            int flags = spannable.getSpanFlags(span);
            spannable.removeSpan(span);
            spannable.setSpan(new FileSafeUrlSpan(span.getURL()), start, end, flags);
        }
    }

    /**
     * 自定义 URLSpan：链接行为全部交给 {@link MarkdownLinkRouter}（与 WebView 主气泡共用
     * 同一条路由，避免"原生路径能点、WebView 路径点了没反应"这种分叉）。
     */
    private static class FileSafeUrlSpan extends URLSpan {
        FileSafeUrlSpan(String url) {
            super(url);
        }

        @Override
        public void onClick(View widget) {
            MarkdownLinkRouter.open(widget.getContext(), getURL());
        }
    }

    /**
     * 获取纯文本（用于复制到剪贴板）
     */
    public static String toPlainText(String markdown) {
        if (markdown == null) return "";
        String text = markdown;
        text = text.replaceAll("```[\\s\\S]*?```", "");
        text = text.replaceAll("`([^`]+)`", "$1");
        // (?m) 多行模式：清理每行开头的标题标记（旧实现缺 MULTILINE，只处理第一行）
        text = text.replaceAll("(?m)^#{1,6}\\s+", "");
        text = text.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "$1");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("\\*(.+?)\\*", "$1");
        text = text.replaceAll("~~(.+?)~~", "$1");
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1");
        text = text.replaceAll("(?m)^>\\s+", "  ");
        // 任务列表/列表标记清理
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        text = text.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        return text.trim();
    }
}
