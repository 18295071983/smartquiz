package com.oilquiz.app.ai.chat.render;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Markdown 正文里链接点击的**唯一**路由器（App 内 + 系统能力都从这里出）。
 *
 * <p>为什么单独成类：这套判断原先长在 {@link MarkdownRenderer} 的私有 URLSpan 里，
 * 正文气泡切到 WebView 渲染（{@code MarkdownWebView}）后，Span 路径不再参与正文，
 * 于是"文件链接→应用内预览、图片→全屏预览、content:// 带读权限、中文 URL 编码、
 * tel:/mailto: 交系统"这些能力在主气泡上**集体失联**（WebView 只认 http/https，
 * 其余 scheme 被 {@code shouldOverrideUrlLoading} 直接吞掉）。现在两条渲染路径
 * 都调这里，行为不会再次分叉。</p>
 *
 * <p>分支口径（与历史实现一致，勿随意改）：</p>
 * <ol>
 *   <li>图片（扩展名或 content:// MIME）→ {@code ImagePreviewUtil} 全屏预览。</li>
 *   <li>{@code content://} → 应用内 {@code FileRenderActivity}；失败才交系统并带
 *       {@code FLAG_GRANT_READ_URI_PERMISSION}。不能直接 ACTION_VIEW：无 App 能渲染
 *       Markdown 时系统会把 Intent 转给浏览器，中文文件名被 punycode 当域名解析必然失败。</li>
 *   <li>{@code http(s)} → App 内 {@code WebViewActivity}（不再跳系统浏览器）。</li>
 *   <li>{@code file://}、绝对路径、工作区相对名 → 解析后应用内预览；不存在给可读提示，
 *       并列出工作区相似文件（模型常缩写文件名）。</li>
 *   <li>其余合法 scheme（tel:/sms:/mailto:/geo:…）→ 交系统；{@code javascript:}/{@code intent:}/
 *       {@code data:} 一律拒绝（正文来自模型输出，不能借一次点击拉起组件或执行代码）。</li>
 * </ol>
 */
public final class MarkdownLinkRouter {

    private static final String TAG = "MarkdownLinkRouter";

    /** 气泡页的 baseUrl 前缀：相对链接会被浏览器补成这个，需还原成工作区相对名再解析 */
    private static final String ASSET_PREFIX = "file:///android_asset/";

    /** 明确拒绝的危险 scheme（正文是模型产出，点击不应变成执行） */
    private static final String[] BLOCKED_SCHEMES = {"javascript", "intent", "data"};

    private MarkdownLinkRouter() { }

    /**
     * 打开正文里的一个链接。所有分支内部自吞异常并给出可读提示，调用方无需再包 try。
     *
     * @param url 原始链接串：http(s)、content://、file://、绝对路径、工作区相对名均可
     */
    public static void open(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        String target = normalizeAssetHref(url);
        try {
            // 1) 图片一律走应用内全屏预览（不依赖系统图片查看器，避免"没有可用打开图片的页面"）
            if (isImageUrl(context, target)) {
                showImagePreview(context, target);
                return;
            }

            String scheme = schemeOf(target);
            if (scheme != null && isBlocked(scheme)) {
                Log.w(TAG, "拒绝的危险 scheme: " + scheme);
                android.widget.Toast.makeText(context, "该链接已被拦截: " + scheme,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uri = Uri.parse(target);
            if ("content".equals(uri.getScheme())) {
                // 2) content:// → 应用内预览
                try {
                    Intent intent = new Intent(context,
                            com.oilquiz.app.ui.activity.FileRenderActivity.class);
                    intent.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_URI, uri);
                    if (!(context instanceof android.app.Activity)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    context.startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "App preview failed, fallback ACTION_VIEW: " + e.getMessage());
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    context.startActivity(intent);
                }
                return;
            }

            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                // 3) 网页 → App 内 WebViewActivity（不跳系统浏览器，保留返回/会话上下文）
                com.oilquiz.app.ai.chat.component.ComponentActions.openLink(context, target);
                return;
            }

            // 4) 本地文件：file://、绝对路径、工作区相对路径
            java.io.File localFile = resolveLocalFile(context, target, uri);
            if (localFile == null && uri.getScheme() == null) {
                // 没有 scheme 又没有解析到工作区文件：它就是模型写的一个裸文件名。
                // 留着往下走 ACTION_VIEW 会把 "报告.md" 当网址（中文文件名被 punycode 当域名，
                // DNS 必挂），所以按"找不到的本地文件"给可读提示。
                localFile = new java.io.File(target);
            }
            if (localFile != null && localFile.exists() && localFile.isFile()) {
                try {
                    Intent intent = new Intent(context,
                            com.oilquiz.app.ui.activity.FileRenderActivity.class);
                    intent.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_PATH,
                            localFile.getAbsolutePath());
                    if (!(context instanceof android.app.Activity)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    context.startActivity(intent);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Local file preview failed: " + e.getMessage());
                }
            }
            if (localFile != null && !localFile.exists()) {
                // 文件名常被模型缩写/丢字 → 给出工作区相似文件，比"文件不存在"有操作性
                java.util.List<java.io.File> similar = searchSimilar(context, target);
                if (!similar.isEmpty()) {
                    StringBuilder sb = new StringBuilder("未找到 ").append(target).append("，工作区有相似文件:\n");
                    for (java.io.File sf : similar) {
                        sb.append("· ").append(sf.getName()).append("\n");
                    }
                    android.widget.Toast.makeText(context, sb.toString().trim(),
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                android.widget.Toast.makeText(context, "文件不存在: " + localFile.getName(),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            // 5) 其余 scheme（tel:/sms:/mailto:/geo:…）交系统；无法处理时明确提示而不是静默
            Intent system = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            system.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(system);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No activity to handle link: " + target);
            android.widget.Toast.makeText(context, "系统里没有能打开该链接的应用",
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "Error opening link: " + e.getMessage());
        }
    }

    /**
     * 把气泡页补全出来的 {@code file:///android_asset/xxx} 还原成原始相对名，
     * 否则工作区解析会拿一个不存在的 android_asset 路径。
     */
    public static String normalizeAssetHref(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.startsWith(ASSET_PREFIX)) {
            String rel = trimmed.substring(ASSET_PREFIX.length());
            try {
                // 浏览器会把中文路径百分号编码，解码后才能拿去工作区里找
                String decoded = Uri.decode(rel);
                if (decoded != null && !decoded.isEmpty()) rel = decoded;
            } catch (Throwable ignored) {
            }
            return rel.isEmpty() ? trimmed : rel;
        }
        return trimmed;
    }

    /** 取 scheme（小写，无 scheme 返回 null） */
    private static String schemeOf(String url) {
        if (url == null) return null;
        int idx = url.indexOf(':');
        if (idx <= 0) return null;
        String s = url.substring(0, idx).toLowerCase(java.util.Locale.ROOT);
        return s.matches("[a-z][a-z0-9+.\\-]*") ? s : null;
    }

    private static boolean isBlocked(String scheme) {
        for (String b : BLOCKED_SCHEMES) {
            if (b.equals(scheme)) return true;
        }
        return false;
    }

    /** 工作区相似文件搜索（AgentWorkspace 才有这能力，失败返回空表不影响主流程） */
    private static java.util.List<java.io.File> searchSimilar(Context context, String url) {
        try {
            return com.oilquiz.app.ai.agent.online.AgentWorkspace
                    .getInstance(context).searchSimilarFiles(url);
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 解析本地文件路径：file:// → 取 path；绝对路径 → 直接使用；
     * 工作区相对路径（如 "报告.md" / "files/报告.md"）→ 用 AgentWorkspace 解析。
     * 返回 null 表示非本地文件（应退回系统默认处理）。
     */
    static java.io.File resolveLocalFile(Context context, String url, Uri uri) {
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
                    Log.w(TAG, "workspace resolve failed: " + t.getMessage());
                    return null;
                }
            }
            if (path == null || path.isEmpty()) return null;
            return new java.io.File(path);
        } catch (Exception e) {
            Log.w(TAG, "resolveLocalFile failed: " + e.getMessage());
            return null;
        }
    }

    /** 判断 URL/URI 是否为图片：扩展名 + content:// MIME 双重判断 */
    static boolean isImageUrl(Context context, String url) {
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
    static void showImagePreview(Context context, String url) {
        com.oilquiz.app.ai.chat.component.ImagePreviewUtil.show(context, url);
    }

    /**
     * 正确编码 URL 中的非 ASCII 字符：
     * - 域名部分使用 IDN.toASCII 转换
     * - 路径/查询/片段使用百分号编码
     * 避免浏览器对含中文的 URL 进行错误的 Punycode 编码
     */
    static String encodeUrlForBrowser(String url) {
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
    static String encodePathAndQuery(String pathAndQuery) {
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
}
