package com.oilquiz.app.ai.chat.render;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
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
                    RequestBuilder<Drawable> builder = Glide.with(appContext)
                            .asDrawable()
                            .load(drawable.getDestination());
                    // 尺寸策略抽到独立方法（原本内联在这个匿名类里，魔数与调用点耦合）
                    ImageSize size = drawable.hasKnownDimensions() ? drawable.getImageSize() : null;
                    int[] target = chatImageDecodeSize(size, maxImageWidth, maxImageHeight);
                    if (target == null) {
                        // 已指定 px 尺寸且在限内：原样返回 builder，不覆盖解码尺寸
                        // （此处**不加** centerInside —— 与原实现逐分支一致）
                        return builder;
                    }
                    return builder.override(target[0], target[1]).centerInside();
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
                    // 原文预处理改挂官方 processMarkdown 阶段（原来是在 render() 里手工调）。
                    // 这正是 Markwon 为"解析前改写原始 markdown"提供的扩展点：
                    // https://noties.io/Markwon/docs/v4/core/plugins.html#process-markdown
                    // 附带好处：该职责从 render() 的多个重载里收拢到插件一处，
                    // render() 只剩"解析 + 后处理"。
                    .usePlugin(new io.noties.markwon.AbstractMarkwonPlugin() {
                        @NonNull
                        @Override
                        public String processMarkdown(@NonNull String markdown) {
                            return closeUnclosedCodeFence(markdown);
                        }
                    })
                    .build();
            initialized = true;
        }
    }

    /**
     * 聊天图片的解码尺寸策略（从 {@code GlideStore.load} 的匿名闭包里抽出，便于单测）。
     *
     * <p>返回值是调用方要执行的 {@code override(w,h)} 参数；返回 {@code null} 表示
     * **不覆盖**解码尺寸（只做 centerInside 由调用方决定）。逐分支与抽出前一致：</p>
     * <ul>
     *   <li>未指定尺寸（{@code imageSize == null}）→ {@code [maxW, maxH]}；</li>
     *   <li>指定了尺寸但**非 px 单位**（如 {@code 50%}/{@code 2em}）→ **落到最大范围兜底**
     *       {@code [maxW, maxH]}（原来就是在 if 之后 fall through 到这一行，不能丢掉）；</li>
     *   <li>指定了 px 尺寸且在限内 → {@code null}（不放大、不覆盖）；</li>
     *   <li>指定了 px 尺寸且超限 → 按 {@code min(maxW/w, maxH/h)} 等比缩小。</li>
     * </ul>
     *
     * <p>注意：这里约束的是 **Glide 解码尺寸**（省内存、防大图撑爆气泡）。
     * Markwon 的 {@code ImageSizeResolver} 只决定**显示矩形**、不控制解码，
     * 两者不是同一个职责，所以尺寸策略留在这里，而不是搬去 resolver。</p>
     *
     * @return {@code [width, height]}；{@code null} 表示不覆盖解码尺寸
     */
    private static int[] chatImageDecodeSize(ImageSize size, int maxImageWidth, int maxImageHeight) {
        if (size != null && size.width != null && size.height != null) {
            boolean pxUnit = (size.width.unit == null || "px".equals(size.width.unit))
                    && (size.height.unit == null || "px".equals(size.height.unit));
            if (pxUnit) {
                int w = (int) size.width.value;
                int h = (int) size.height.value;
                if (w > 0 && h > 0) {
                    if (w <= maxImageWidth && h <= maxImageHeight) {
                        return null;   // 已指定且在限内：不覆盖
                    }
                    float scale = Math.min((float) maxImageWidth / w, (float) maxImageHeight / h);
                    return new int[]{(int) (w * scale), (int) (h * scale)};
                }
            }
        }
        // 未指定尺寸，或指定了非 px 单位 / 非法值：限制在最大范围内（与抽出前一致）
        return new int[]{maxImageWidth, maxImageHeight};
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
     *
     * <p>原文预处理（流式未闭合代码围栏补闭合）已移到 {@link #init} 注册的
     * {@code processMarkdown} 插件里，走 Markwon 官方扩展点；此处只负责解析与后处理。
     * 注意**不要**在这里再调一次 {@link #closeUnclosedCodeFence} —— 插件阶段已经处理过，
     * 重复调用会把已闭合的围栏再次判定为奇数（补出来的那个 ``` 本身也计入统计）而多补一个。</p>
     */
    public static Spanned render(String markdown, Context context, int availableWidth) {
        if (markdown == null || markdown.isEmpty()) {
            return new android.text.SpannableStringBuilder("");
        }
        ensureInit(context);
        if (!initialized) {
            return new android.text.SpannableStringBuilder(markdown);
        }
        Spanned result = markwon.toMarkdown(markdown);
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
     * 自定义 URLSpan，处理 content:// URI 时添加 FLAG_GRANT_READ_URI_PERMISSION，
     * 处理 http/https URL 时正确编码中文等非 ASCII 字符（IDN 域名 + 路径百分号编码）
     */
    private static class FileSafeUrlSpan extends URLSpan {
        FileSafeUrlSpan(String url) {
            super(url);
        }

        @Override
        public void onClick(View widget) {
            String url = getURL();
            try {
                // 图片 URL/URI → 应用内预览（不依赖系统图片查看器，避免"没有可用打开图片的页面"）
                if (isImageUrl(widget.getContext(), url)) {
                    showImagePreview(widget.getContext(), url);
                    return;
                }
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if ("content".equals(scheme)) {
                    // 文件链接（content://）→ 应用内预览（FileRenderActivity 按类型渲染 md/文本/表格/pdf 等）。
                    // 不能 ACTION_VIEW 交给系统：无 App 能渲染 Markdown 时系统会把 Intent 转给浏览器，
                    // 中文文件名被 punycode 编码成域名发起 DNS 解析必然失败（实测 Bug）。
                    try {
                        Intent intent = new Intent(widget.getContext(),
                                com.oilquiz.app.ui.activity.FileRenderActivity.class);
                        intent.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_URI, uri);
                        if (!(widget.getContext() instanceof android.app.Activity)) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        }
                        widget.getContext().startActivity(intent);
                    } catch (Exception e) {
                        android.util.Log.w("MarkdownRenderer", "App preview failed, fallback ACTION_VIEW: " + e.getMessage());
                        // 兜底：应用内预览失败才交给系统
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        widget.getContext().startActivity(intent);
                    }
                } else if ("http".equals(scheme) || "https".equals(scheme)) {
                    // 正确编码 URL 中的非 ASCII 字符，避免浏览器错误 Punycode 编码
                    String encodedUrl = encodeUrlForBrowser(url);
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(encodedUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    widget.getContext().startActivity(intent);
                } else {
                    // 本地文件分支：file://、绝对路径、工作区相对路径 → 应用内预览
                    // （不能 super.onClick 交给系统：中文文件名会被当域名 punycode 编码 DNS 失败）
                    java.io.File localFile = resolveLocalFile(widget.getContext(), url, uri);
                    if (localFile != null && localFile.exists() && localFile.isFile()) {
                        try {
                            Intent intent = new Intent(widget.getContext(),
                                    com.oilquiz.app.ui.activity.FileRenderActivity.class);
                            intent.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_PATH,
                                    localFile.getAbsolutePath());
                            if (!(widget.getContext() instanceof android.app.Activity)) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            }
                            widget.getContext().startActivity(intent);
                            return;
                        } catch (Exception e) {
                            android.util.Log.w("MarkdownRenderer", "Local file preview failed: " + e.getMessage());
                        }
                    }
                    if (localFile != null && !localFile.exists()) {
                        android.widget.Toast.makeText(widget.getContext(), "文件不存在: " + localFile.getName(),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 非本地文件（无扩展名/非工作区）：退回系统默认
                    super.onClick(widget);
                }
            } catch (ActivityNotFoundException e) {
                android.util.Log.w("MarkdownRenderer", "No activity to handle link: " + url);
            } catch (Exception e) {
                android.util.Log.w("MarkdownRenderer", "Error opening link: " + e.getMessage());
            }
        }
    }

    /**
     * 解析本地文件路径：file:// → 取 path；绝对路径 → 直接使用；
     * 工作区相对路径（如 "报告.md" / "files/报告.md"）→ 用 AgentWorkspace 解析。
     * 返回 null 表示非本地文件（应退回系统默认处理）。
     */
    private static java.io.File resolveLocalFile(Context context, String url, Uri uri) {
        try {
            String path = null;
            if ("file".equals(uri.getScheme())) {
                path = uri.getPath();
            } else if (url != null && url.startsWith("/")) {
                path = url;
            } else if (url != null && !url.contains("://")
                    && (url.contains(".") || url.startsWith("files/") || url.startsWith("tmp/"))) {
                // 相对路径（工作区文件）：AgentWorkspace 解析
                try {
                    com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                            com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
                    java.io.File f = ws.resolveFileToFiles(url);
                    if (f == null || !f.exists()) f = ws.resolveFileToTmp(url);
                    return f;
                } catch (Throwable t) {
                    android.util.Log.w("MarkdownRenderer", "workspace resolve failed: " + t.getMessage());
                    return null;
                }
            }
            if (path == null || path.isEmpty()) return null;
            return new java.io.File(path);
        } catch (Exception e) {
            android.util.Log.w("MarkdownRenderer", "resolveLocalFile failed: " + e.getMessage());
            return null;
        }
    }

    /** 判断 URL/URI 是否为图片：扩展名 + content:// MIME 双重判断 */
    private static boolean isImageUrl(Context context, String url) {
        if (url == null) return false;
        // 去掉查询参数和锚点后判断扩展名
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int h = clean.indexOf('#');
        if (h >= 0) clean = clean.substring(0, h);
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
            return true;
        }
        // content:// 无扩展名时按 MIME 判断（如 content://media/.../images/123）
        if (url.startsWith("content://") && context != null) {
            try {
                String mime = context.getContentResolver().getType(Uri.parse(url));
                return mime != null && mime.startsWith("image/");
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 应用内图片预览（统一全屏流：ImagePreviewUtil——PhotoView 双指缩放，本地系统解码/网络原生下载） */
    private static void showImagePreview(Context context, String url) {
        com.oilquiz.app.ai.chat.component.ImagePreviewUtil.show(context, url);
    }

    /**
     * 正确编码 URL 中的非 ASCII 字符：
     * - 域名部分使用 IDN.toASCII 转换
     * - 路径/查询/片段使用百分号编码
     * 避免浏览器对含中文的 URL 进行错误的 Punycode 编码
     */
    private static String encodeUrlForBrowser(String url) {
        if (url == null || url.isEmpty()) return url;

        try {
            // 检查是否包含非 ASCII 字符，没有则直接返回
            boolean hasNonAscii = false;
            for (int i = 0; i < url.length(); i++) {
                if (url.charAt(i) > 127) {
                    hasNonAscii = true;
                    break;
                }
            }
            if (!hasNonAscii) return url;

            // 分离 scheme+authority 和 path+query+fragment
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return url;

            String scheme = url.substring(0, schemeEnd);
            String rest = url.substring(schemeEnd + 3);

            // 分离 authority（域名）和 path
            int pathStart = rest.indexOf('/');
            String authority;
            String pathAndQuery;
            if (pathStart >= 0) {
                authority = rest.substring(0, pathStart);
                pathAndQuery = rest.substring(pathStart);
            } else {
                // 没有路径，检查是否有 ? 或 #
                int queryStart = rest.indexOf('?');
                int fragStart = rest.indexOf('#');
                int end = -1;
                if (queryStart >= 0 && fragStart >= 0) end = Math.min(queryStart, fragStart);
                else if (queryStart >= 0) end = queryStart;
                else if (fragStart >= 0) end = fragStart;

                if (end >= 0) {
                    authority = rest.substring(0, end);
                    pathAndQuery = rest.substring(end);
                } else {
                    authority = rest;
                    pathAndQuery = "";
                }
            }

            // 去除 authority 中的 userinfo（user:pass@）
            String userInfo = null;
            int atIdx = authority.indexOf('@');
            if (atIdx >= 0) {
                userInfo = authority.substring(0, atIdx);
                authority = authority.substring(atIdx + 1);
            }

            // 分离 host 和 port
            String host = authority;
            String port = "";
            int colonIdx = authority.lastIndexOf(':');
            if (colonIdx >= 0) {
                String possiblePort = authority.substring(colonIdx + 1);
                boolean isPort = true;
                for (int i = 0; i < possiblePort.length(); i++) {
                    if (!Character.isDigit(possiblePort.charAt(i))) {
                        isPort = false;
                        break;
                    }
                }
                if (isPort && !possiblePort.isEmpty()) {
                    host = authority.substring(0, colonIdx);
                    port = ":" + possiblePort;
                }
            }

            // IDN 编码域名
            String encodedHost;
            try {
                encodedHost = java.net.IDN.toASCII(host);
            } catch (Exception e) {
                encodedHost = host;
            }

            // 重建 authority
            String encodedAuthority = "";
            if (userInfo != null) encodedAuthority += userInfo + "@";
            encodedAuthority += encodedHost + port;

            // 编码 pathAndQuery（按 / 和 ? 和 # 分段编码）
            String encodedPathAndQuery = encodePathAndQuery(pathAndQuery);

            return scheme + "://" + encodedAuthority + encodedPathAndQuery;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 编码 URL 的路径和查询部分（保留 / ? & = # 等结构字符）
     */
    private static String encodePathAndQuery(String pathAndQuery) {
        if (pathAndQuery == null || pathAndQuery.isEmpty()) return pathAndQuery;

        StringBuilder sb = new StringBuilder(pathAndQuery.length());
        for (int i = 0; i < pathAndQuery.length(); i++) {
            char c = pathAndQuery.charAt(i);
            if (c > 127) {
                // 非 ASCII 字符进行百分号编码
                try {
                    byte[] bytes = String.valueOf(c).getBytes("UTF-8");
                    for (byte b : bytes) {
                        sb.append(String.format("%%%.2X", b & 0xFF));
                    }
                } catch (Exception e) {
                    sb.append(c);
                }
            } else if (c == ' ') {
                sb.append("%20");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
