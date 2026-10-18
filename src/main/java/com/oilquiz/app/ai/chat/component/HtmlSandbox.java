package com.oilquiz.app.ai.chat.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML 组件沙箱策略（Track B0-1）。
 *
 * <p>问题：html 组件渲染的内容绝大多数来自模型输出（提示词可控），改造前是无条件开 JS +
 * 挂 Java 桥 + 对 file:// 放开跨源文件访问，等于在聊天流里放了一个"带 App 权限的浏览器"：
 * 注入一段 HTML 就能读本地文件、调剪贴板、按任意路径取图并外发。
 *
 * <p>做法：把"内容从哪来"显式建模成四档信任级，能力由信任级决定，而不是由 props 说了算。
 * <pre>
 * INTERNAL_TEMPLATE  应用内置模板（mermaid / katex）。模板我们自己拼、载荷已转义，
 *                    JS 必须开（否则图形/公式渲不出来）；无桥、无文件访问。
 * LOCAL_FILE         Agent 生成在工作区白名单目录内的本地 HTML。JS 开（报表要跑图表脚本）、
 *                    桥只保留 copy/toast/openLink/previewImage 且路径受限；
 *                    放开该文件自身访问，但禁 file 跨源与 universal。
 * REMOTE_PAGE        https 网页（web 组件）。JS 开、无桥、禁文件访问、导航只放行 http(s)。
 * INLINE_UNTRUSTED   模型直接给的 HTML 字符串。JS 关 + 注入 CSP + 无桥 + 禁文件/内容访问 +
 *                    大小上限；链接点击交回应用，不在卡片内导航。
 * </pre>
 *
 * <p>口径依据（Android 官方 WebSettings 文档）：
 * <ul>
 *   <li>addJavascriptInterface 只可用于可信内容——不可信档位一律不挂桥；</li>
 *   <li>API 30 起 setAllowFileAccess / setAllowFileAccessFromFileURLs /
 *       setAllowUniversalAccessFromFileURLs 默认 false，
 *       且 file:///android_asset、file:///android_res 不受 setAllowFileAccess 影响，
 *       所以内置模板里 {@code <script src="js/mermaid.min.js">} 这类相对引用
 *       在关掉文件访问后依然能加载——这正是"禁文件访问但保留本地模板"能共存的原因。</li>
 * </ul>
 *
 * <p>信任判定不接受 props 里的自述字段（如 html 内容里写 "trusted":true）：
 * 内部模板靠 {@link ComponentData#internalTemplate}（transient，不参与持久化，
 * 只有 MermaidMathSplitter 这类应用内代码能置位）；历史会话里的旧数据没有该标记，
 * 靠 {@link #looksLikeInternalTemplate(String)} 的模板指纹兜底——即便模型伪造该指纹，
 * 拿到的也只是"无桥 + 无文件访问 + 只能执行自己页面内脚本"的 JS 环境，
 * 外发通道（connect-src / form / 导航 / 新窗口）都在此关闭。
 */
public final class HtmlSandbox {

    private static final String TAG = "HtmlSandbox";

    /** 不可信内联 HTML 的大小上限：超过则截断渲染并提示（防超大内容拖死 WebView/内存） */
    public static final int MAX_INLINE_HTML_CHARS = 512 * 1024;

    /** 内容信任档位 */
    public enum Trust {
        /** 应用内置模板（mermaid/katex） */
        INTERNAL_TEMPLATE,
        /** 工作区内的本地 HTML 文件 */
        LOCAL_FILE,
        /** https 网页 */
        REMOTE_PAGE,
        /** 模型给的 HTML 字符串（默认档，最严） */
        INLINE_UNTRUSTED
    }

    /** 拦截原因（文案留给渲染层从 strings.xml 取，判定层不产用户可见文本） */
    public enum Block {
        /** file:// 目标不在允许的工作区目录内 */
        LOCAL_OUTSIDE_WORKSPACE,
        /** 明文 http 且不是本机地址 */
        HTTP_NOT_ALLOWED,
        /** javascript:/data:/blob:/content:/intent: 等协议 */
        SCHEME_UNSUPPORTED
    }

    /** 判定结果 */
    public static final class Decision {
        public final Trust trust;
        /** 归一化后的加载目标：file:// / https:// ；内联渲染为 null */
        public final String loadUrl;
        /** 非 null 表示该内容被拒绝加载（本地文件越界、明文 http、危险 scheme 等） */
        public final Block blocked;
        /** 拦截原因的具体信息（如协议名），仅用于日志与占位说明 */
        public final String blockedDetail;
        /** 内联 HTML 超上限被截断（渲染层据此显示截断提示，不在此产文案） */
        public final boolean truncated;
        /** 实际用于渲染的 HTML 字符串（内联档；其它档为 null） */
        public final String html;

        Decision(Trust trust, String loadUrl, String html,
                 Block blocked, String blockedDetail, boolean truncated) {
            this.trust = trust;
            this.loadUrl = loadUrl;
            this.html = html;
            this.blocked = blocked;
            this.blockedDetail = blockedDetail;
            this.truncated = truncated;
        }

        /** 是否被整体拦截（只显示拦截说明卡，不创建 WebView） */
        public boolean isBlocked() {
            return blocked != null;
        }
    }

    private HtmlSandbox() {
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 决定这份 HTML 按哪一档渲染。
     *
     * @param data   组件数据（可能带 {@link ComponentData#internalTemplate} 标记）
     * @param html   从 props 多字段提取出的 HTML 字符串
     * @param rawUrl props.url 原文（可为空）
     */
    public static Decision resolve(Context context, ComponentData data, String html, String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();

        // 1) 内置模板（mermaid/katex）：模板由应用代码拼装，JS 必须可用
        if (data != null && data.internalTemplate) {
            return new Decision(Trust.INTERNAL_TEMPLATE, null, html, null, null, false);
        }

        // 2) 历史会话兼容：旧数据没有 transient 标记，按模板指纹识别
        if (url.isEmpty() && looksLikeInternalTemplate(html)) {
            return new Decision(Trust.INTERNAL_TEMPLATE, null, html, null, null, false);
        }

        // 3) url 加载：只允许 https（+本机白名单）与工作区内的 file://
        if (!url.isEmpty()) {
            String lower = url.toLowerCase(java.util.Locale.US);
            if (lower.startsWith("file://")) {
                File f = new File(filePathFromUrl(url));
                if (isWithinAllowedRoots(context, f)) {
                    return new Decision(Trust.LOCAL_FILE, canonicalFileUrl(f), null, null, null, false);
                }
                return new Decision(Trust.LOCAL_FILE, null, null,
                        Block.LOCAL_OUTSIDE_WORKSPACE, f.getAbsolutePath(), false);
            }
            if (lower.startsWith("https://") || isAllowedHttpOrigin(lower)) {
                return new Decision(Trust.REMOTE_PAGE, url, null, null, null, false);
            }
            if (lower.startsWith("http://")) {
                return new Decision(Trust.REMOTE_PAGE, null, null,
                        Block.HTTP_NOT_ALLOWED, url, false);
            }
            // javascript: / data: / blob: / content: / intent: 等
            return new Decision(Trust.INLINE_UNTRUSTED, null, null,
                    Block.SCHEME_UNSUPPORTED, schemeOf(url), false);
        }

        // 4) 兼容把本地路径塞进 html/content 字段的调用方（web→html 映射的历史写法）
        String asPath = html == null ? "" : html.trim();
        if (looksLikeLocalHtmlPath(asPath)) {
            String path = asPath.startsWith("file://") ? filePathFromUrl(asPath) : asPath;
            File f = new File(path);
            if (isWithinAllowedRoots(context, f) && f.isFile()) {
                return new Decision(Trust.LOCAL_FILE, canonicalFileUrl(f), null, null, null, false);
            }
            return new Decision(Trust.LOCAL_FILE, null, null,
                    Block.LOCAL_OUTSIDE_WORKSPACE, path, false);
        }

        // 5) 默认档：模型给的 HTML 字符串 → 先净化（真解析器剥危险节点），再判长度截断
        String content = sanitizeInline(html);
        boolean truncated = false;
        if (content != null && content.length() > MAX_INLINE_HTML_CHARS) {
            int cut = content.lastIndexOf('>', Math.min(content.length(), MAX_INLINE_HTML_CHARS));
            content = cut > 0 ? content.substring(0, cut + 1) : content.substring(0, MAX_INLINE_HTML_CHARS);
            truncated = true;
            Log.w(TAG, "inline html truncated: " + html.length() + " chars");
        }
        return new Decision(Trust.INLINE_UNTRUSTED, null, content, null, null, truncated);
    }

    /** 上限的 KB 数（渲染层拼截断提示用） */
    public static int maxInlineHtmlKb() {
        return MAX_INLINE_HTML_CHARS / 1024;
    }

    /**
     * 不可信内联 HTML 净化（jsoup 解析后按节点黑名单剥执行面，而不是拿正则糊）。
     *
     * <p>只删"能跑/能跳/能外发"的东西：script/iframe/object/embed 等标签、
     * 所有 {@code on*} 事件属性、{@code javascript:} 类协议、refresh meta。
     * 结构、class 与内联 style 全部保留——模型写的排版仍照它的意思渲染，
     * 少的是执行能力。净化在最严档做，是给"以后有人手滑放开 JS"留的第二道闸。
     */
    public static String sanitizeInline(String html) {
        if (html == null || html.isEmpty()) return html;
        try {
            boolean fullDocument = html.toLowerCase(java.util.Locale.US).contains("<html");
            org.jsoup.nodes.Document doc = fullDocument
                    ? org.jsoup.Jsoup.parse(html)
                    : org.jsoup.Jsoup.parseBodyFragment(html);
            doc.outputSettings().prettyPrint(false)
                    .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
            doc.select("script, iframe, frame, frameset, object, embed, applet, "
                    + "link[rel=import], meta[http-equiv], form[action]").remove();
            for (org.jsoup.nodes.Element el : doc.getAllElements()) {
                // 先收集要剥的属性名再删：jsoup 的 Attributes 是活列表，
                // 遍历中 removeAttr 会抛 ConcurrentModificationException
                java.util.List<String> drop = null;
                for (org.jsoup.nodes.Attribute attr : el.attributes()) {
                    String key = attr.getKey().toLowerCase(java.util.Locale.US);
                    String val = attr.getValue() == null ? "" : attr.getValue().trim();
                    String lowerVal = val.toLowerCase(java.util.Locale.US);
                    boolean dangerousUrl = (key.equals("href") || key.equals("src")
                            || key.equals("xlink:href") || key.equals("formaction")
                            || key.equals("srcdoc"))
                            && (lowerVal.startsWith("javascript:") || lowerVal.startsWith("vbscript:")
                            || lowerVal.startsWith("data:text/html"));
                    if (key.startsWith("on") || dangerousUrl) {
                        if (drop == null) drop = new java.util.ArrayList<>(2);
                        drop.add(attr.getKey());
                    }
                }
                if (drop != null) {
                    for (String k : drop) el.removeAttr(k);
                }
            }
            return fullDocument ? doc.html() : doc.body().html();
        } catch (Throwable t) {
            // 净化失败不能顺手放行原文：宁可只留纯文本
            Log.w(TAG, "inline sanitize failed, fall back to plain text: " + t.getMessage());
            try {
                return org.jsoup.Jsoup.parse(html).text();
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /**
     * 是否长得像我们自己的 mermaid/katex 模板。
     *
     * <p>只用于兼容改造前已持久化的历史消息（那时没有 {@link ComponentData#internalTemplate}）。
     */
    public static boolean looksLikeInternalTemplate(String html) {
        if (html == null || html.isEmpty()) return false;
        String s = html.toLowerCase(java.util.Locale.US);
        boolean mermaid = s.contains("js/mermaid.min.js") && s.contains("class=\"mermaid\"");
        boolean katex = s.contains("js/katex.min.js") && s.contains("css/katex.min.css");
        return mermaid || katex;
    }

    /** 内容是不是一个本地 HTML 文件路径（不含标签、以 .html/.htm 结尾或带 file:// 前缀） */
    private static boolean looksLikeLocalHtmlPath(String s) {
        if (s == null || s.isEmpty() || s.length() > 512) return false;
        if (s.indexOf('<') >= 0) return false;
        String lower = s.toLowerCase(java.util.Locale.US);
        return lower.startsWith("file://")
                || (lower.endsWith(".html") || lower.endsWith(".htm")) && new File(s).isFile();
    }

    private static String schemeOf(String url) {
        int idx = url.indexOf(':');
        return idx > 0 && idx < 16 ? url.substring(0, idx) : "unknown";
    }

    /** 明文 http 仅放行本机地址（本地服务/调试页），其它 http 拦截 */
    private static boolean isAllowedHttpOrigin(String lowerUrl) {
        return lowerUrl.startsWith("http://127.0.0.1")
                || lowerUrl.startsWith("http://localhost");
    }

    // ---------------------------------------------------------------- 文件白名单

    /**
     * 允许作为 LOCAL_FILE 加载的根目录：Agent 工作区（含临时/产物区）+ 应用私有目录。
     *
     * <p>用 canonicalPath 前缀比较，避免 ../ 穿越与软链绕过（比字符串 replace 稳）。
     */
    public static boolean isWithinAllowedRoots(Context context, File target) {
        if (context == null || target == null) return false;
        try {
            String canonicalTarget = target.getCanonicalPath();
            for (File root : allowedRoots(context)) {
                if (root == null) continue;
                String canonicalRoot = root.getCanonicalPath();
                if (!canonicalRoot.endsWith(File.separator)) {
                    canonicalRoot = canonicalRoot + File.separator;
                }
                if (canonicalTarget.startsWith(canonicalRoot)
                        || canonicalTarget.equals(canonicalRoot.substring(0, canonicalRoot.length() - 1))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "allowed-roots check failed: " + t.getMessage());
        }
        return false;
    }

    private static List<File> allowedRoots(Context context) {
        List<File> roots = new ArrayList<>();
        try {
            com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                    com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
            if (ws != null) {
                roots.add(ws.getWorkspaceDir());
                roots.add(ws.getTmpDir());
                roots.add(ws.getFilesDir());
            }
        } catch (Throwable ignored) {
            // 工作区未初始化时仍有下面的私有目录兜底
        }
        try {
            roots.add(context.getFilesDir());
            roots.add(context.getCacheDir());
            File ext = context.getExternalFilesDir(null);
            if (ext != null) roots.add(ext);
        } catch (Throwable ignored) {
        }
        return roots;
    }

    /**
     * file:// URL → 本地路径。
     *
     * <p>只用 JDK 解析（剥 fragment/query + percent 解码），不依赖 android.net.Uri：
     * 路径口径要能在单元测试里跑，也要避免 Uri 对反斜杠/中文名的静默改写。
     */
    public static String filePathFromUrl(String url) {
        if (url == null) return "";
        String s = url.startsWith("file://") ? url.substring("file://".length()) : url;
        int frag = s.indexOf('#');
        if (frag >= 0) s = s.substring(0, frag);
        int query = s.indexOf('?');
        if (query >= 0) s = s.substring(0, query);
        try {
            s = percentDecode(s);
        } catch (Exception ignored) {
            // 非法百分号编码：按原文继续（后面的存在性检查会兜住）
        }
        return s;
    }

    /** 只做 %XX 解码（不用 URLDecoder：它会把 '+' 当空格，文件名里的加号会被改坏） */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()
                    && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write((int) (Character.digit(s.charAt(i + 1), 16) * 16
                        + Character.digit(s.charAt(i + 2), 16)));
                i += 2;
            } else {
                byte[] raw = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(raw, 0, raw.length);
            }
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 规范化成 file:// URL（跟随符号链接后的真实路径，避免用 .. 拼出的越界路径） */
    private static String canonicalFileUrl(File f) {
        try {
            return "file://" + f.getCanonicalPath();
        } catch (Throwable t) {
            return "file://" + f.getAbsolutePath();
        }
    }

    // ---------------------------------------------------------------- WebView 配置

    /**
     * 该档是否放开页面脚本。
     *
     * <p>唯一判定出口：{@link #configure} 用它设 WebSettings，
     * {@link HtmlWebView#isScriptEnabled()} 用它决定是否注入依赖 JS 的能力
     * （自适应高度、图片预览、触摸探测），避免两处口径漂移。
     */
    public static boolean scriptsEnabled(Trust trust) {
        return trust != Trust.INLINE_UNTRUSTED;
    }

    /**
     * 按信任档配置 WebView。原则：能力只给到该档必需的最小集，
     * 且任何一档都不放开 file 跨源/universal 访问（那才是"读任意本地文件"的入口）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static void configure(WebView webView, Trust trust) {
        WebSettings s = webView.getSettings();
        // 先套项目已有的统一加固基线（webview/security/WebViewSecurityConfig），
        // 再按信任档打差异补丁——安全口径只有一处维护，不在聊天组件里另起一套
        com.oilquiz.app.webview.security.WebViewSecurityConfig.configure(s);
        // —— 所有档位共用的收紧项（基线里没覆盖或值需要更严的）——
        s.setJavaScriptEnabled(scriptsEnabled(trust));
        s.setAllowContentAccess(false);
        s.setAllowFileAccess(trust == Trust.LOCAL_FILE);   // 仅本地报表文件需要
        s.setAllowFileAccessFromFileURLs(false);           // API30 起默认 false，显式钉住
        s.setAllowUniversalAccessFromFileURLs(false);      // 同上：禁止 file 页读任意源
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setDomStorageEnabled(trust == Trust.REMOTE_PAGE || trust == Trust.LOCAL_FILE);
        s.setMixedContentMode(trust == Trust.REMOTE_PAGE
                ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                s.setSafeBrowsingEnabled(true);
            } catch (Throwable ignored) {
                // 个别定制 ROM 上 SafeBrowsing 不可用，不影响其它收紧项
            }
        }
        // 缩放/视口体验保持（非安全项，沿用原行为）
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(contentZoomPercent(webView.getContext()));
        Log.i(TAG, "webview configured, trust=" + trust + ", js=" + s.getJavaScriptEnabled());
    }

    /**
     * 按系统字体缩放调 WebView 文字比例（WebView 默认不跟随 fontScale，
     * 1.3× 大字体下卡片正文会比周围原生文字小一圈）。限制在 100–200%。
     */
    private static int contentZoomPercent(Context context) {
        try {
            float fontScale = context.getResources().getConfiguration().fontScale;
            return Math.max(100, Math.min(200, Math.round(100 * fontScale)));
        } catch (Throwable t) {
            return 100;
        }
    }

    /** 该档是否允许挂 Java 桥（只有工作区本地文件档；模型内联内容永远不给） */
    public static boolean allowsBridge(Trust trust) {
        return trust == Trust.LOCAL_FILE;
    }

    /**
     * 不可信内联内容的 CSP。JS 已在该档关闭，这里是纵深防御：
     * 即便将来有人放开 JS 或系统 WebView 有绕过，脚本/外发/表单/嵌套框架仍是关的。
     */
    public static String cspMetaFor(Trust trust) {
        switch (trust) {
            case INLINE_UNTRUSTED:
                return "<meta http-equiv=\"Content-Security-Policy\" content="
                        + "\"default-src 'none'; "
                        + "script-src 'none'; "
                        + "img-src https: data:; "
                        + "style-src 'unsafe-inline' https:; "
                        + "font-src https: data:; "
                        + "media-src https:; "
                        + "connect-src 'none'; "
                        + "object-src 'none'; frame-src 'none'; "
                        + "base-uri 'none'; form-action 'none'\"/>";
            default:
                return null;
        }
    }

    /**
     * 把 CSP meta 注入 HTML 头部。完整文档插到 &lt;head&gt; 之后（无 head 时补一个），
     * 片段则直接前置——两种写法 WebView 都能正确解析。
     */
    public static String injectCsp(String html, Trust trust) {
        String meta = cspMetaFor(trust);
        String content = html == null ? "" : html;
        if (meta == null || content.isEmpty()) return content;
        int headIdx = indexOfIgnoreCase(content, "<head");
        if (headIdx >= 0) {
            int close = content.indexOf('>', headIdx);
            if (close >= 0) {
                return content.substring(0, close + 1) + meta + content.substring(close + 1);
            }
        }
        int htmlIdx = indexOfIgnoreCase(content, "<html");
        if (htmlIdx >= 0) {
            int close = content.indexOf('>', htmlIdx);
            if (close >= 0) {
                return content.substring(0, close + 1) + meta + content.substring(close + 1);
            }
        }
        return meta + content;
    }

    private static int indexOfIgnoreCase(String source, String needle) {
        if (source == null || needle == null) return -1;
        return source.toLowerCase(java.util.Locale.US).indexOf(needle.toLowerCase(java.util.Locale.US));
    }
}
