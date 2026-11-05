package com.oilquiz.app.browser;

import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 地址栏输入 → 可直接交给 WebView 的 URL。
 *
 * <p>纯 Java（不依赖 Android），便于单元测试。口径：</p>
 * <ol>
 *   <li>带 http/https/file/content/about/data/blob scheme：原样（仅做非 ASCII 编码），
 *       {@code javascript:} 直接拒绝（地址栏不应能执行脚本）。</li>
 *   <li>无 scheme 但像主机（含点、localhost、IPv4/IPv6、带端口）→ 补协议；
 *       本机/内网补 http（本地服务通常没有证书），公网补 https。</li>
 *   <li>其余（含中文、空格）→ 走搜索引擎。含 CJK 的输入不当作域名，
 *       避免「报告.md」被解析成主机名。</li>
 * </ol>
 */
public final class BrowserUrlNormalizer {

    /** 内置起始页（assets 下的本地页面，不联网） */
    public static final String HOME_URL = "file:///android_asset/browser_home.html";

    /** 搜索入口：百度（国内环境可用性优先） */
    public static final String SEARCH_PREFIX = "https://www.baidu.com/s?wd=";

    private static final String HTTPS = "https://";
    private static final String HTTP = "http://";

    private BrowserUrlNormalizer() {
    }

    /**
     * 解析地址栏输入。
     *
     * @return 可加载的 URL；空输入、被拒绝的 scheme 或无法解析时返回 null
     */
    public static String toLoadableUrl(String raw) {
        if (raw == null) return null;
        String text = raw.trim().replace("\r", "").replace("\n", "");
        if (text.isEmpty()) return null;

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")) return null;

        int colon = text.indexOf(':');
        if (colon > 0) {
            String scheme = text.substring(0, colon);
            if (isValidScheme(scheme)) {
                switch (scheme.toLowerCase(Locale.ROOT)) {
                    case "http":
                    case "https":
                        return encodeNonAscii(text);
                    case "file":
                    case "content":
                    case "about":
                    case "data":
                    case "blob":
                        return text;
                    default:
                        // tel/mailto/intent/自定义协议：地址栏不当网址处理，退回搜索
                        break;
                }
            }
        }

        if (looksLikeHost(text)) {
            return (preferPlainHttp(text) ? HTTP : HTTPS) + encodeNonAscii(text);
        }
        return SEARCH_PREFIX + urlEncode(text);
    }

    /** 是否是可在内置浏览器里正常展示的网页地址 */
    public static boolean isWebUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    public static boolean isHttps(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    public static boolean isHome(String url) {
        return HOME_URL.equals(url);
    }

    /** 地址栏展示用文本：起始页显示空串，其余去掉 http(s):// 前缀 */
    public static String displayUrl(String url) {
        if (url == null || url.isEmpty() || isHome(url)) return "";
        if (url.startsWith("data:") || url.startsWith("blob:")) return "";
        String display = url;
        if (display.startsWith("https://")) {
            display = display.substring(8);
        } else if (display.startsWith("http://")) {
            display = display.substring(7);
        }
        if (display.endsWith("/") && display.indexOf('/') == display.length() - 1) {
            display = display.substring(0, display.length() - 1);
        }
        return display;
    }

    /** 提取 host；解析失败返回空串 */
    public static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = URI.create(url).getHost();
            if (host != null && !host.isEmpty()) return host;
        } catch (Throwable ignored) {
        }
        String u = url;
        int scheme = u.indexOf("://");
        if (scheme >= 0) u = u.substring(scheme + 3);
        int slash = u.indexOf('/');
        if (slash >= 0) u = u.substring(0, slash);
        int at = u.lastIndexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        return u;
    }

    // ==================== 内部判定 ====================

    private static boolean isValidScheme(String scheme) {
        if (scheme == null || scheme.isEmpty()) return false;
        if (!Character.isLetter(scheme.charAt(0))) return false;
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') return false;
        }
        return true;
    }

    /** 无 scheme 的输入是否像主机（而不是搜索词） */
    static boolean looksLikeHost(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.indexOf(' ') >= 0 || text.indexOf('\t') >= 0) return false;

        String authority = text;
        int cut = authority.length();
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        authority = authority.substring(0, cut);
        if (authority.isEmpty()) return false;
        // 只对主机部分做中文判断：路径/查询里的中文不应让整条地址退回搜索
        if (containsCjk(authority)) return false;

        if (authority.startsWith("[")) {
            return authority.indexOf(']') > 1;
        }

        String host = authority;
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && isAllDigits(authority.substring(colon + 1))) {
            host = authority.substring(0, colon);
        }
        if (host.equalsIgnoreCase("localhost")) return true;
        if (isIpv4(host)) return true;
        if (host.indexOf('.') < 0) return false;

        String tld = host.substring(host.lastIndexOf('.') + 1);
        if (tld.length() < 2) return false;
        // 主机名不允许中文标点/空白（CJK 已在上面排除）
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /**
     * 无 scheme 的裸地址是否应补 http：本机、局域网、任意 IP 都按 http 试，
     * 公网域名按 https。IP 站点大多没有证书，补 https 会直接握手失败。
     */
    static boolean preferPlainHttp(String text) {
        String authority = authorityOf(text);
        String host = authority;
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && isAllDigits(authority.substring(colon + 1))) {
            host = authority.substring(0, colon);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("[::1]") || host.endsWith(".local")) {
            return true;
        }
        if (isIpv4(host)) {
            return true;
        }
        return false;
    }

    private static String authorityOf(String text) {
        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        return text.substring(0, cut);
    }

    static boolean isIpv4(String host) {
        if (host == null || host.isEmpty()) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !isAllDigits(p)) return false;
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(s.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    private static String urlEncode(String text) {
        try {
            return URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return text;
        }
    }

    // ==================== 非 ASCII 编码 ====================

    /**
     * 域名走 IDN，路径/查询/片段逐字符百分号编码；已编码的 %XX 原样保留。
     */
    static String encodeNonAscii(String url) {
        if (url == null || url.isEmpty()) return url;
        String prefix = "";
        String rest = url;
        int scheme = url.indexOf("://");
        if (scheme > 0) {
            prefix = url.substring(0, scheme + 3);
            rest = url.substring(scheme + 3);
        }

        int authorityEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = rest.substring(0, authorityEnd);
        String tail = rest.substring(authorityEnd);

        String userInfo = "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userInfo = authority.substring(0, at + 1);
            authority = authority.substring(at + 1);
        }

        String host = authority;
        String port = "";
        if (!authority.startsWith("[")) {
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && isAllDigits(authority.substring(colon + 1))) {
                host = authority.substring(0, colon);
                port = authority.substring(colon);
            }
        }

        String encodedHost = host;
        if (!host.startsWith("[")) {
            try {
                encodedHost = IDN.toASCII(host);
            } catch (Throwable ignored) {
                encodedHost = host;
            }
        }
        return prefix + userInfo + encodedHost + port + encodePath(tail);
    }

    private static String encodePath(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (cp < 128) {
                char c = (char) cp;
                if (c == ' ') {
                    sb.append("%20");
                } else if (c == '%') {
                    if (i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                        sb.append(c);
                    } else {
                        sb.append("%25");
                    }
                } else if (isUriSafe(c)) {
                    sb.append(c);
                } else {
                    sb.append('%').append(String.format(Locale.ROOT, "%02X", cp));
                }
            } else {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
                }
            }
            i += charCount;
        }
        return sb.toString();
    }

    private static boolean isUriSafe(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
        return "-._~:/?#[]@!$&'()*+,;=".indexOf(c) >= 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
