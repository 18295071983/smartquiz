package com.cjhtmldemo.apk.tv;

import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3U / M3U8 直播源解析器（纯 Java，不依赖 Android API）。
 *
 * <p>覆盖 IPTV 清单里真正会被用到的写法：</p>
 * <ul>
 *   <li>{@code #EXTINF:-1 tvg-id=".." tvg-name=".." tvg-logo=".." group-title="..",频道名}</li>
 *   <li>{@code #EXTGRP:分组}（对后续频道生效，EXTINF 的 group-title 优先级更高）</li>
 *   <li>{@code #EXTVLCOPT:http-user-agent=.. / http-referrer=..} 与
 *       {@code #EXTHTTP:{"User-Agent":".."}}（部分源需要 UA/Referer 才能拉流）</li>
 *   <li>相对地址（按清单 URL 解析）、注释行、空行、BOM、CRLF</li>
 * </ul>
 *
 * <p>如果传入的本身就是一路 HLS（媒体清单/主清单，包含 {@code #EXT-X-*}），
 * 则返回一个"单频道"，地址用清单地址本身——这样 {@code loadLiveChannels}
 * 对直播直链也能工作，而不会把 TS 分片误当成频道。</p>
 */
public final class M3uParser {

    private static final int MAX_CHANNELS = 5000;
    private static final Pattern ATTR = Pattern.compile(
            "([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))");

    public static final class Result {
        public final List<LiveChannel> channels;
        /** true 表示输入是单路 HLS，而不是频道列表 */
        public final boolean singleStream;

        Result(List<LiveChannel> channels, boolean singleStream) {
            this.channels = Collections.unmodifiableList(channels);
            this.singleStream = singleStream;
        }
    }

    private M3uParser() {
    }

    /**
     * @param text         清单文本
     * @param baseUrl      清单地址（用于解析相对流地址；可为空）
     * @param fallbackName 单路模式/无名称频道的兜底名称
     */
    public static Result parse(String text, String baseUrl, String fallbackName) {
        List<LiveChannel> channels = new ArrayList<>();
        if (text == null || text.isEmpty()) return new Result(channels, false);

        String body = text.replace("\uFEFF", "");
        if (looksLikeHlsPlaylist(body)) {
            String streamUrl = baseUrl == null ? "" : baseUrl.trim();
            if (!streamUrl.isEmpty()) {
                String name = isBlank(fallbackName) ? deriveName(streamUrl, 0) : fallbackName.trim();
                channels.add(new LiveChannel(null, name, "", "", streamUrl, null));
                return new Result(channels, true);
            }
        }

        String currentGroup = "";
        Pending pending = new Pending();
        Map<String, LiveChannel> unique = new LinkedHashMap<>();
        String[] lines = body.split("\\r?\\n", -1);

        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#")) {
                String upper = line.toUpperCase(Locale.US);
                if (upper.startsWith("#EXTINF:")) {
                    parseExtInf(line.substring(8), pending);
                } else if (upper.startsWith("#EXTGRP:")) {
                    currentGroup = line.substring(8).trim();
                } else if (upper.startsWith("#EXTVLCOPT:")) {
                    parseVlcOption(line.substring(11), pending.headers);
                } else if (upper.startsWith("#EXTHTTP:")) {
                    parseHeadersJson(line.substring(9), pending.headers);
                } else if (upper.startsWith("#KODIPROP:")) {
                    parseKodiProp(line.substring(10), pending.headers);
                }
                continue;
            }

            // 有 scheme 但不是媒体协议（javascript:/data: 等）直接丢弃；
            // 没有 scheme 的按清单地址解析相对路径。
            if (hasScheme(line) && !isSupportedScheme(line)) {
                pending.clear();
                continue;
            }
            String url = resolve(baseUrl, line);
            if (!isSupportedScheme(url)) {
                pending.clear();
                continue;
            }

            String name = !isBlank(pending.name) ? pending.name
                    : (!isBlank(pending.tvgName) ? pending.tvgName : deriveName(url, channels.size()));
            String group = !isBlank(pending.group) ? pending.group : currentGroup;
            String logo = pending.logo;
            if (!isBlank(logo)) logo = resolve(baseUrl, logo);
            LiveChannel ch = new LiveChannel(null, name, group, logo, url, pending.headers);
            String key = ch.url + "\n" + ch.name + "\n" + ch.group;
            if (!unique.containsKey(key)) {
                unique.put(key, ch);
                if (unique.size() >= MAX_CHANNELS) break;
            }
            pending.clear();
        }

        channels.addAll(unique.values());

        // 频道列表没解析出东西、但本身就是一路 m3u8：按单频道返回，避免"空列表"。
        if (channels.isEmpty() && baseUrl != null
                && (baseUrl.toLowerCase(Locale.US).contains(".m3u8")
                || baseUrl.toLowerCase(Locale.US).contains(".m3u"))) {
            String name = isBlank(fallbackName) ? deriveName(baseUrl, 0) : fallbackName.trim();
            channels.add(new LiveChannel(null, name, "", "", baseUrl.trim(), null));
            return new Result(channels, true);
        }

        return new Result(channels, false);
    }

    /** 单路 HLS 检测：媒体清单或主清单 */
    private static boolean looksLikeHlsPlaylist(String body) {
        return body.contains("#EXT-X-TARGETDURATION")
                || body.contains("#EXT-X-MEDIA-SEQUENCE")
                || body.contains("#EXT-X-PLAYLIST-TYPE")
                || body.contains("#EXT-X-STREAM-INF")
                || body.contains("#EXT-X-ENDLIST");
    }

    private static void parseExtInf(String rest, Pending p) {
        int comma = indexOfUnquotedComma(rest);
        String attrs = comma >= 0 ? rest.substring(0, comma) : rest;
        String title = comma >= 0 ? rest.substring(comma + 1).trim() : "";
        if (!title.isEmpty()) p.name = title;

        Matcher m = ATTR.matcher(attrs);
        while (m.find()) {
            String key = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.US);
            String val = m.group(2) != null ? m.group(2)
                    : (m.group(3) != null ? m.group(3) : m.group(4));
            if (val == null) continue;
            val = val.trim();
            switch (key) {
                case "tvg-name":
                    p.tvgName = val;
                    break;
                case "tvg-logo":
                    p.logo = val;
                    break;
                case "group-title":
                    p.group = val;
                    break;
                case "tvg-id":
                    if (isBlank(p.tvgName)) p.tvgName = val;
                    break;
                case "http-user-agent":
                case "user-agent":
                    p.headers.put("User-Agent", val);
                    break;
                case "http-referrer":
                case "http-referer":
                case "referer":
                case "referrer":
                    p.headers.put("Referer", val);
                    break;
                case "http-origin":
                case "origin":
                    p.headers.put("Origin", val);
                    break;
                default:
                    break;
            }
        }
    }

    private static void parseVlcOption(String opt, Map<String, String> headers) {
        if (opt == null) return;
        String s = opt.trim();
        int eq = s.indexOf('=');
        if (eq <= 0) return;
        String key = s.substring(0, eq).trim().toLowerCase(Locale.US);
        String val = s.substring(eq + 1).trim();
        if (val.isEmpty()) return;
        if (key.contains("user-agent") || key.equals("http-useragent")) {
            headers.put("User-Agent", val);
        } else if (key.contains("referrer") || key.contains("referer")) {
            headers.put("Referer", val);
        } else if (key.contains("origin")) {
            headers.put("Origin", val);
        } else if (key.contains("cookie")) {
            headers.put("Cookie", val);
        }
    }

    private static void parseHeadersJson(String json, Map<String, String> headers) {
        if (json == null || json.length() > 4096) return;
        try {
            JSONObject o = new JSONObject(json.trim());
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, null);
                if (k != null && v != null && !v.isEmpty()) headers.put(k, v);
            }
        } catch (Exception ignored) {
        }
    }

    private static void parseKodiProp(String prop, Map<String, String> headers) {
        if (prop == null) return;
        int eq = prop.indexOf('=');
        if (eq <= 0) return;
        String key = prop.substring(0, eq).trim().toLowerCase(Locale.US);
        String val = prop.substring(eq + 1).trim();
        if (val.isEmpty()) return;
        if (key.contains("stream_headers") || key.contains("manifest_headers")) {
            parseHeadersJson(val, headers);
        } else if (key.contains("user-agent")) {
            headers.put("User-Agent", val);
        }
    }

    private static int indexOfUnquotedComma(String s) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == ',' && !single && !dbl) return i;
        }
        return -1;
    }

    static String resolve(String baseUrl, String ref) {
        String r = ref == null ? "" : ref.trim();
        if (r.isEmpty()) return r;
        if (hasScheme(r)) return r;
        if (baseUrl == null || baseUrl.trim().isEmpty()) return r;
        try {
            return URI.create(baseUrl.trim()).resolve(r).toString();
        } catch (Exception ignored) {
        }
        // URI 解析失败（base 含空格/非常规字符）时做最小拼接
        String b = baseUrl.trim();
        int cut = b.indexOf('?');
        if (cut >= 0) b = b.substring(0, cut);
        cut = b.indexOf('#');
        if (cut >= 0) b = b.substring(0, cut);
        int slash = b.lastIndexOf('/');
        if (slash >= 0) return b.substring(0, slash + 1) + r;
        return r;
    }

    private static boolean hasScheme(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return false;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isSupportedScheme(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        if (u.isEmpty()) return false;
        return u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("file://") || u.startsWith("rtsp://")
                || u.startsWith("rtmp://") || u.startsWith("rtmps://")
                || u.startsWith("udp://") || u.startsWith("rtp://");
    }

    private static String deriveName(String url, int index) {
        String path = url == null ? "" : url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.lastIndexOf('/');
        if (cut >= 0 && cut + 1 < path.length()) path = path.substring(cut + 1);
        cut = path.lastIndexOf('.');
        if (cut > 0) path = path.substring(0, cut);
        if (path.isEmpty()) return "频道 " + (index + 1);
        try {
            return java.net.URLDecoder.decode(path, "UTF-8");
        } catch (Exception e) {
            return path;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static final class Pending {
        String name;
        String tvgName;
        String group;
        String logo;
        final Map<String, String> headers = new LinkedHashMap<>();

        void clear() {
            name = null;
            tvgName = null;
            group = null;
            logo = null;
            headers.clear();
        }
    }
}
