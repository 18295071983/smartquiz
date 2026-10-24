package com.cjhtmldemo.apk.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 直播频道模型。
 *
 * <p>频道来源可以是 M3U/M3U8 频道列表，也可以由 HTML 直接构造。字段与常见 IPTV
 * 清单保持一一对应：{@code name/group/logo/url/headers}；{@code id} 由名称+地址
 * 稳定生成，用于收藏、上次播放和播放器内部识别。</p>
 */
public final class LiveChannel {

    public final String id;
    public final String name;
    public final String group;
    public final String logo;
    public final String url;
    /** HTTP 请求头（User-Agent / Referer 等），空表表示用壳的默认值 */
    public final Map<String, String> headers;

    public LiveChannel(String id, String name, String group, String logo, String url,
                       Map<String, String> headers) {
        this.url = url == null ? "" : url.trim();
        this.name = safeName(name, this.url);
        this.group = group == null ? "" : group.trim();
        this.logo = logo == null ? "" : logo.trim();
        this.id = (id == null || id.trim().isEmpty())
                ? stableId(this.name, this.url)
                : id.trim();

        Map<String, String> copy = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e == null) continue;
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || k.trim().isEmpty() || v == null) continue;
                copy.put(k.trim(), v);
                if (copy.size() >= 24) break; // 防御异常清单塞入超长头
            }
        }
        this.headers = Collections.unmodifiableMap(copy);
    }

    public boolean isValid() {
        return !url.isEmpty();
    }

    /** 原生播放器当前只接 HTTP(S)；其它协议保留在模型里，播放时明确报错 */
    public boolean isHttp() {
        String u = url.toLowerCase(Locale.US);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("group", group);
            o.put("logo", logo);
            o.put("url", url);
            if (!headers.isEmpty()) {
                JSONObject h = new JSONObject();
                for (Map.Entry<String, String> e : headers.entrySet()) h.put(e.getKey(), e.getValue());
                o.put("headers", h);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static LiveChannel fromJson(JSONObject o) {
        if (o == null) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject h = o.optJSONObject("headers");
        if (h != null) {
            Iterator<String> keys = h.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = h.optString(k, null);
                if (k != null && v != null) headers.put(k, v);
            }
        }
        String url = o.optString("url", "").trim();
        if (url.isEmpty()) return null;
        return new LiveChannel(
                o.optString("id", null),
                o.optString("name", ""),
                o.optString("group", ""),
                o.optString("logo", ""),
                url,
                headers);
    }

    public static String stableId(String name, String url) {
        return sha1Short((name == null ? "" : name) + "\n" + (url == null ? "" : url));
    }

    public static String sha1Short(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < d.length; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((text == null ? "" : text).hashCode());
        }
    }

    private static String safeName(String name, String url) {
        String n = name == null ? "" : name.trim();
        if (!n.isEmpty()) return n;
        String path = url == null ? "" : url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) path = path.substring(slash + 1);
        int dot = path.lastIndexOf('.');
        if (dot > 0) path = path.substring(0, dot);
        return path.isEmpty() ? "频道" : path;
    }
}
