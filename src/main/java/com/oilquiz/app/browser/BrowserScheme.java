package com.oilquiz.app.browser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 未知/自定义 scheme 的**判定层**（纯 Java，不依赖 Android，便于单测）。
 *
 * <p>网页里的「打开 App」按钮几乎都走自定义协议（{@code weixin://}、{@code alipays://}、
 * {@code tbopen://}、{@code intent://…}、{@code magnet:?} 等）。WebView 内核只认
 * http/https/file/data/blob 这些，其余直接报 {@code ERR_UNKNOWN_URL_SCHEME(-2)}，
 * 而 -2 一旦被丢进通用错误页，用户看到的就是"页面加载失败/请检查网络连接"——
 * 明明系统浏览器能识别、手机里也装着对应 App，却像网络坏了。这一层只回答两个问题：
 * 「这条 URL 是什么性质的协议」和「该给用户哪几条出路」，真正的 startActivity/弹框
 * 留给 {@link BrowserSchemeHandler}。</p>
 *
 * <p>判定口径（与 {@link BrowserUrlNormalizer} 的地址栏归一互为补充）：</p>
 * <ol>
 *   <li>{@code http/https} → {@link Kind#WEB}，应用内加载。</li>
 *   <li>{@code file/content/about/data/blob} → {@link Kind#IN_PAGE}，应用内或另有专门处理。</li>
 *   <li>{@code javascript} → {@link Kind#SCRIPT}，地址栏不许执行脚本。</li>
 *   <li>{@code intent://} → {@link Kind#INTENT}，是 Android 显式 intent 串，要解析而不是当协议。</li>
 *   <li>其余带协议的（{@code tel/mailto/sms/geo/ftp/market/} 及各种 App 私有协议）
 *       → {@link Kind#SYSTEM_APP}，交系统解析。</li>
 *   <li>看着像协议其实不是（{@code localhost:8080}、{@code 127.0.0.1:3000/x}、含中文的搜索词）
 *       → {@link Kind#NOT_A_SCHEME}，退回地址栏正常归一，别把端口当协议。</li>
 * </ol>
 */
public final class BrowserScheme {

    public enum Kind {
        /** http/https：内置浏览器直接加载 */
        WEB,
        /** file/content/about/data/blob：应用内（blob/data 另有下载与渲染链路） */
        IN_PAGE,
        /** javascript:：地址栏拒绝执行 */
        SCRIPT,
        /** intent://…#Intent;…end：需要解析的显式 intent */
        INTENT,
        /** 其它协议：tel/mailto/sms/geo/ftp/market 及 App 私有协议，交系统解析 */
        SYSTEM_APP,
        /** 不是协议：host:port 或搜索词，交回地址栏归一 */
        NOT_A_SCHEME
    }

    // ==================== 处置选项 key（标签在资源里，判定层只出 key） ====================

    public static final String OPT_SYSTEM_APP = "system_app";
    public static final String OPT_WEB_FALLBACK = "web_fallback";
    public static final String OPT_SYSTEM_BROWSER = "system_browser";
    public static final String OPT_COPY = "copy";

    /** 未知协议处理策略 */
    public static final String POLICY_ASK = "ask";
    public static final String POLICY_SYSTEM_APP = "system";
    public static final String POLICY_SYSTEM_BROWSER = "browser";
    private static final Set<String> POLICIES =
            new HashSet<>(Arrays.asList(POLICY_ASK, POLICY_SYSTEM_APP, POLICY_SYSTEM_BROWSER));

    /**
     * 不带 {@code ://} 也算协议的常见名字。
     *
     * <p>为什么需要白名单：按 RFC 3986，{@code localhost:8080} 的 {@code localhost} 是合法
     * scheme 字面量，照字符判会把开发机地址当成协议转交出去。只认「字符合法 <b>且</b> 后面紧跟
     * {@code //}」或「在这张表里」，其余带冒号的输入留给 {@link BrowserUrlNormalizer}。</p>
     */
    private static final Set<String> BARE_SCHEMES = new HashSet<>(Arrays.asList(
            "tel", "mailto", "sms", "smsto", "mms", "geo", "intent", "callto", "skype",
            "ftp", "ftps", "sftp", "file", "content", "about", "data", "blob", "javascript",
            "market", "magnet", "ed2k", "thunder", "flashget", "svn", "svn+ssh", "git",
            "weixin", "wx", "alipay", "alipays", "taobao", "openapp", "tmall", "tbopen",
            "snssdk1128", "dingtalk", "weibo", "sinaweibo", "mqq", "qq", "kwai", "eleme",
            "dianping", "baiduboxapp", "baiduboxlite", "imeituan", "itms-apps", "vscode",
            "jetbrains", "slack", "telegram", "tg", "whatsapp", "zalo", "line", "viber"));

    /** 这些协议转交给通讯/地图类 App 即可，"用系统浏览器打开"对它们没意义 */
    private static final Set<String> NO_BROWSER_HINT = new HashSet<>(Arrays.asList(
            "tel", "callto", "skype", "sms", "smsto", "mms", "mailto", "geo"));

    private BrowserScheme() {
    }

    // ==================== 判定 ====================

    /**
     * 取 scheme（小写）；不是协议返回空串。
     */
    public static String schemeOf(String url) {
        if (url == null) return "";
        String text = url.trim();
        int colon = text.indexOf(':');
        if (colon <= 0) return "";
        String scheme = text.substring(0, colon);
        if (!isValidScheme(scheme)) return "";
        String lower = scheme.toLowerCase(Locale.ROOT);
        String rest = text.substring(colon + 1);
        if (rest.startsWith("//")) return lower;
        return BARE_SCHEMES.contains(lower) ? lower : "";
    }

    /** 这条 URL 属于哪一类协议 */
    public static Kind classify(String url) {
        String scheme = schemeOf(url);
        if (scheme.isEmpty()) {
            // 带冒号但不在白名单：可能仍是真协议（如厂商私有 myapp://），"://" 才是硬证据
            String text = url == null ? "" : url.trim();
            int colon = text.indexOf(':');
            if (colon > 0 && text.substring(colon + 1).startsWith("//")
                    && isValidScheme(text.substring(0, colon))) {
                return Kind.SYSTEM_APP;
            }
            return Kind.NOT_A_SCHEME;
        }
        switch (scheme) {
            case "http":
            case "https":
                return Kind.WEB;
            case "file":
            case "content":
            case "about":
            case "data":
            case "blob":
                return Kind.IN_PAGE;
            case "javascript":
                return Kind.SCRIPT;
            case "intent":
                return Kind.INTENT;
            default:
                return Kind.SYSTEM_APP;
        }
    }

    /** 是否需要转交出浏览器（{@code SYSTEM_APP} / {@code INTENT}） */
    public static boolean needsHandoff(String url) {
        Kind k = classify(url);
        return k == Kind.SYSTEM_APP || k == Kind.INTENT;
    }

    /** 通讯/地图类协议：转交后要弹的是系统自己的拨号/写信界面，不需要用户再确认一次 */
    public static boolean isCommunicationScheme(String url) {
        return NO_BROWSER_HINT.contains(schemeOf(url));
    }

    /** 这些协议转交后不必再给"用系统浏览器打开"这条出路（浏览器帮不上拨号/写信） */
    public static boolean hideBrowserHint(String url) {
        return isCommunicationScheme(url);
    }

    // ==================== 网页回退地址 ====================

    /**
     * 从协议链接里找**可直接应用内加载的网页回退地址**。
     *
     * <p>三类来源，按可靠性排序：①{@code intent://…#Intent;S.browser_fallback_url=…;end}
     * （官方写法，值本身还是编码过的）；②国内 App 拉起链接把 H5 地址塞在
     * {@code url/u/link/target/h5Url/backUrl/redirect} 等参数里；③整串里出现百分号编码的
     * {@code https%3A%2F%2F…}（双重编码的站点也有，解一层再扫一次）。
     * 只认 http/https，其余（含 {@code javascript:}）一律不当回退地址，避免转交过程里
     * 把脚本串喂给 WebView。</p>
     */
    public static String webFallback(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        String fromIntent = decode(valueAfterMarker(url, "browser_fallback_url"));
        if (isHttp(fromIntent)) return fromIntent;
        String scheme = schemeOf(url);
        if ("intent".equals(scheme)) {
            // intent 串没有回退地址就别再拿它的 query 猜了：里面的参数是给 App 看的
            return null;
        }
        int mark = url.indexOf('?');
        if (mark >= 0) {
            String query = url.substring(mark + 1);
            String[] keys = {"url", "u", "link", "target", "h5url", "h5Url", "backurl", "backUrl",
                    "returnurl", "returnUrl", "redirect", "redirect_url", "fallback", "goto"};
            for (String key : keys) {
                String v = queryParam(query, key);
                if (isHttp(v)) return v;
            }
        }
        String encoded = firstEncodedHttp(url);
        if (encoded != null) return encoded;
        return firstEncodedHttp(decode(url));
    }

    private static final java.util.regex.Pattern ENCODED_HTTP =
            java.util.regex.Pattern.compile("https?%3A%2F%2F[^&;'\"\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 扫百分号编码的 {@code https%3A%2F%2F…}；没有返回 null */
    private static String firstEncodedHttp(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = ENCODED_HTTP.matcher(text);
        if (!m.find()) return null;
        String decoded = decode(m.group());
        return isHttp(decoded) ? decoded : null;
    }

    // ==================== 处置选项 ====================

    /**
     * 给用户列出的出路（按推荐顺序）。纯逻辑，标签由 {@link BrowserSchemeHandler} 从资源取。
     *
     * @param appResolved 系统里是否有能处理该协议的应用（由调用方问 PackageManager）
     */
    public static List<String> optionsFor(String url, Kind kind, boolean appResolved, String fallbackUrl) {
        List<String> options = new ArrayList<>();
        if (appResolved) options.add(OPT_SYSTEM_APP);
        if (isHttp(fallbackUrl)) options.add(OPT_WEB_FALLBACK);
        if (!hideBrowserHint(url) && kind != Kind.SCRIPT) options.add(OPT_SYSTEM_BROWSER);
        options.add(OPT_COPY);
        return options;
    }

    /** 策略归一：非法/空值一律回到"每次询问"，别把没配过的设备变成自动跳转 */
    public static String normalizePolicy(String policy) {
        if (policy == null) return POLICY_ASK;
        String p = policy.trim().toLowerCase(Locale.ROOT);
        return POLICIES.contains(p) ? p : POLICY_ASK;
    }

    public static boolean isHttp(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    // ==================== 内部 ====================

    private static boolean isValidScheme(String scheme) {
        if (scheme == null || scheme.isEmpty()) return false;
        if (!Character.isLetter(scheme.charAt(0))) return false;
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') return false;
        }
        return true;
    }

    /**
     * 取 intent URI 里的 {@code S.browser_fallback_url=} 段。
     *
     * <p>按规范，{@code #Intent;} 之后各段以 {@code ;} 分隔、值必须编码，所以整串按 {@code ;} 切
     * 是安全的；不能"截到下一个分号后再把 'end' 替换掉"——回退地址里本来就可能有 "end" 子串
     * （recommend/blend 之类），那会把 URL 改坏。</p>
     */
    private static String valueAfterMarker(String url, String key) {
        for (String token : url.split(";")) {
            int eq = token.indexOf('=');
            if (eq <= 0) continue;
            String name = token.substring(0, eq).trim();
            if (name.equalsIgnoreCase(key) || name.equalsIgnoreCase("S." + key)) {
                String value = token.substring(eq + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String queryParam(String query, String key) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (pair.substring(0, eq).equalsIgnoreCase(key)) {
                return decode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    /**
     * 百分号解码。
     *
     * <p>不用 {@code URLDecoder}：它按表单口径把 {@code +} 当空格，会把回退地址里
     * {@code ?q=a+b} 这类真实取值改坏。协议链接里的加号该原样保留。</p>
     */
    private static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) return value;
        StringBuilder sb = new StringBuilder(value.length());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()
                    && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                bytes.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 3;
                continue;
            }
            // 遇到字面量：先把攒下来的字节整段按 UTF-8 解（一个汉字是 3 个 %XX，
            // 逐个 flush 会把多字节序列切碎成乱码）
            if (bytes.size() > 0) {
                sb.append(utf8(bytes));
                bytes.reset();
            }
            sb.append(c);
            i++;
        }
        if (bytes.size() > 0) sb.append(utf8(bytes));
        return sb.toString();
    }

    private static String utf8(java.io.ByteArrayOutputStream bytes) {
        try {
            return new String(bytes.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return bytes.toString();
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
