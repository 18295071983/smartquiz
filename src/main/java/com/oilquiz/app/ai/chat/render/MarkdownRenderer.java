package com.oilquiz.app.ai.chat.render;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.View;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
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

            // 配置表格主题：清晰边框 + 合适内边距，确保文字在单元格内自动换行
            TableTheme tableTheme = new TableTheme.Builder()
                    .tableCellPadding(8)
                    .tableBorderWidth(1)
                    .tableBorderColor(0xFF999999)
                    .tableHeaderRowBackgroundColor(0x1A000000)  // 表头浅灰背景
                    .tableOddRowBackgroundColor(0x00000000)     // 奇数行透明
                    .tableEvenRowBackgroundColor(0x08000000)    // 偶数行微灰
                    .build();

            markwon = Markwon.builder(appContext)
                    .usePlugin(ImagesPlugin.create())
                    .usePlugin(TablePlugin.create(tableTheme))
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
        Spanned result = markwon.toMarkdown(markdown);
        // 替换 URLSpan 为自定义 Span，支持 content:// URI 点击
        return replaceUrlSpans(result);
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
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if ("content".equals(scheme)) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    widget.getContext().startActivity(intent);
                } else if ("http".equals(scheme) || "https".equals(scheme)) {
                    // 正确编码 URL 中的非 ASCII 字符，避免浏览器错误 Punycode 编码
                    String encodedUrl = encodeUrlForBrowser(url);
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(encodedUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    widget.getContext().startActivity(intent);
                } else {
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
