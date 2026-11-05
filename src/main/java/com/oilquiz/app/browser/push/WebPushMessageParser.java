package com.oilquiz.app.browser.push;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 收件箱响应 → 通知消息。
 *
 * <p>兼容三种形状：顶层数组、{@code {"messages":[...]}}（也认 notifications/data/items），
 * 以及单个对象。字段名可由订阅配置覆盖，未配置时用 title/body/id/url 并做常见别名兜底。</p>
 */
public final class WebPushMessageParser {

    public static final class Message {
        public final String id;
        public final String title;
        public final String body;
        public final String url;

        Message(String id, String title, String body, String url) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.url = url;
        }
    }

    private WebPushMessageParser() {
    }

    public static List<Message> parse(String json, WebPushSubscription subscription) {
        List<Message> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty() || subscription == null) return out;
        try {
            Object root = new JSONTokener(json).nextValue();
            JSONArray array = null;
            if (root instanceof JSONArray) {
                array = (JSONArray) root;
            } else if (root instanceof JSONObject) {
                JSONObject object = (JSONObject) root;
                for (String key : new String[]{"messages", "notifications", "data", "items"}) {
                    JSONArray nested = object.optJSONArray(key);
                    if (nested != null) {
                        array = nested;
                        break;
                    }
                }
                if (array == null) {
                    JSONObject data = object.optJSONObject("data");
                    if (data != null && (data.has("title") || data.has("body"))) {
                        object = data;
                    }
                    array = new JSONArray();
                    array.put(object);
                }
            }
            if (array == null) return out;

            int limit = Math.min(array.length(), 50);
            for (int i = 0; i < limit; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String title = pick(item, subscription.titleField, "title", "subject");
                String body = pick(item, subscription.bodyField, "body", "message", "content", "text");
                String url = pick(item, subscription.urlField, "url", "link");
                String id = pick(item, subscription.idField, "id", "_id", "msgId", "messageId");
                if (title.isEmpty() && body.isEmpty()) continue;
                title = trim(title, 120);
                body = trim(body, 1000);
                url = trim(url, 2048);
                if (id.isEmpty()) {
                    String stamp = pick(item, "timestamp", "time", "createdAt", "ts");
                    id = hash(title + "\u0001" + body + "\u0001" + url + "\u0001" + stamp);
                } else {
                    id = trim(id, 128);
                }
                out.add(new Message(id, title, body, url));
            }
        } catch (Throwable ignored) {
            // 非 JSON/结构不符：本轮当作没有消息
        }
        return out;
    }

    private static String pick(JSONObject object, String configured, String... fallbacks) {
        if (configured != null && !configured.isEmpty()) {
            String value = object.optString(configured, "");
            if (!value.trim().isEmpty()) return value.trim();
        }
        for (String key : fallbacks) {
            String value = object.optString(key, "");
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return sb.substring(0, 32);
        } catch (Throwable t) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
