package com.oilquiz.app.ai.chat.component;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 播放器请求头解析（P1）：props.headers 对象 + userAgent/referer/cookie 简写。
 *
 * <p>只收普通 HTTP 头；名字按 token 白名单校验，值拒绝 CR/LF，避免从模型产物里
 * 注入 header。UA/Referer/Cookie 是最常见的防盗链三件套，不在这里做签名/证书。</p>
 */
final class PlaybackHeaderParser {

    private PlaybackHeaderParser() {
    }

    static Map<String, String> parse(JSONObject p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (p == null) return out;
        JSONObject h = p.optJSONObject("headers");
        if (h != null) {
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String name = it.next();
                add(out, name, h.optString(name, ""));
            }
        }
        add(out, "User-Agent", firstNonEmpty(p, "userAgent", "user_agent", "ua"));
        add(out, "Referer", firstNonEmpty(p, "referer", "referrer"));
        add(out, "Cookie", p.optString("cookie", ""));
        return out;
    }

    private static String firstNonEmpty(JSONObject p, String... keys) {
        for (String key : keys) {
            String v = p.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static void add(Map<String, String> out, String name, String value) {
        if (name == null || value == null) return;
        String n = name.trim();
        String v = value.trim();
        if (n.isEmpty() || v.isEmpty() || v.length() > 8192) return;
        if (!n.matches("[A-Za-z0-9-]+")) return;
        if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) return;
        out.put(n, v);
    }
}
