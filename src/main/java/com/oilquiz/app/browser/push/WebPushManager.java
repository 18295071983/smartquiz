package com.oilquiz.app.browser.push;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 网页后台提醒管理器（自托管轮询版）。
 *
 * <p>页面提供一个 HTTP 收件箱接口，App 用 WorkManager 每 15~1440 分钟拉一次：
 * 关闭网页后仍会检查，拉到新消息就弹系统通知。它<b>不是</b> W3C Web Push，
 * 不需要 FCM/厂商 SDK，但也不是实时推送；实时场景需要另接 WebSocket/推送服务。</p>
 */
public final class WebPushManager {

    private static final String TAG = "WebPushManager";
    private static final String WORK_NAME = "web_push_poll";
    private static final String WORK_NOW = "web_push_poll_now";
    private static final String PREFS = "web_push_prefs";
    private static final String KEY_DEVICE_ID = "device_id";

    public static final int MIN_INTERVAL_MINUTES = 15;
    public static final int MAX_INTERVAL_MINUTES = 1440;
    public static final int MAX_SUBSCRIPTIONS = 10;
    private static final int MAX_SEEN_IDS = 100;
    private static final int MAX_BODY_PER_POLL = 10;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static volatile WebPushManager instance;

    private final Context context;
    private final WebPushStore store;
    private final OkHttpClient http;
    private final SharedPreferences prefs;
    private final Object mutationLock = new Object();
    private final Object pollLock = new Object();
    private long lastPollNowAt;

    public static final class Parsed {
        public final WebPushSubscription subscription;
        public final String error;

        private Parsed(WebPushSubscription subscription, String error) {
            this.subscription = subscription;
            this.error = error;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final WebPushSubscription subscription;

        private Result(boolean ok, String error, WebPushSubscription subscription) {
            this.ok = ok;
            this.error = error;
            this.subscription = subscription;
        }
    }

    private WebPushManager(Context context) {
        this.context = context.getApplicationContext();
        this.store = new WebPushStore(this.context);
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public static WebPushManager get(Context context) {
        if (instance == null) {
            synchronized (WebPushManager.class) {
                if (instance == null) {
                    instance = new WebPushManager(context);
                }
            }
        }
        return instance;
    }

    // ==================== 配置解析 ====================

    /**
     * 解析页面传来的订阅配置。纯校验，不落盘。
     *
     * @param pageUrl 当前页面地址，用作通知点击的最后兜底
     */
    public static Parsed parseConfig(String configJson, String pageUrl) {
        try {
            JSONObject object = new JSONObject(configJson == null ? "" : configJson);
            String endpoint = object.optString("endpoint", "").trim();
            if (endpoint.isEmpty()) return error("缺少 endpoint");
            if (endpoint.length() > 2048) return error("endpoint 过长");
            Uri uri = Uri.parse(endpoint);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return error("endpoint 只支持 http/https");
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return error("endpoint 缺少主机名");
            }

            String method = object.optString("method", "GET").trim().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"POST".equals(method)) {
                return error("method 只支持 GET/POST");
            }

            String headersJson = "{}";
            JSONObject headers = object.optJSONObject("headers");
            if (headers != null) {
                if (headers.length() > 20) return error("请求头过多");
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headers.optString(key, "");
                    if (key.isEmpty() || key.length() > 64 || key.contains("\r") || key.contains("\n")
                            || value.length() > 1024 || value.contains("\r") || value.contains("\n")) {
                        return error("请求头不合法");
                    }
                }
                headersJson = headers.toString();
                if (headersJson.length() > 4096) return error("请求头过大");
            }

            String body = "";
            Object rawBody = object.opt("body");
            if (rawBody != null) {
                body = rawBody instanceof String ? (String) rawBody : rawBody.toString();
            }
            if (body.length() > 64 * 1024) return error("请求体过大");

            int interval = object.optInt("intervalMinutes", MIN_INTERVAL_MINUTES);
            interval = Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, interval));

            String openUrl = object.optString("openUrl", "").trim();
            if (!openUrl.isEmpty() && !isHttpTemplate(openUrl)) {
                return error("openUrl 只支持 http/https");
            }

            String safePageUrl = isOpenablePage(pageUrl) ? pageUrl : "";
            String id = object.optString("id", "").trim();
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                id = "push_" + sha256(originOf(safePageUrl) + "|" + endpoint).substring(0, 12);
            }

            WebPushSubscription subscription = new WebPushSubscription();
            subscription.id = id;
            subscription.endpoint = endpoint;
            subscription.method = method;
            subscription.headersJson = headersJson;
            subscription.body = body;
            subscription.intervalMinutes = interval;
            subscription.openUrl = openUrl;
            subscription.pageUrl = safePageUrl;
            subscription.titleField = field(object, "titleField", "title");
            subscription.bodyField = field(object, "bodyField", "body");
            subscription.idField = field(object, "idField", "id");
            subscription.urlField = field(object, "urlField", "url");
            subscription.createdAt = System.currentTimeMillis();
            return new Parsed(subscription, null);
        } catch (Throwable t) {
            return error("配置不是合法 JSON: " + t.getMessage());
        }
    }

    private static Parsed error(String message) {
        return new Parsed(null, message);
    }

    private static String field(JSONObject object, String key, String fallback) {
        String value = object.optString(key, fallback).trim();
        return value.matches("[A-Za-z0-9_.-]{1,32}") ? value : fallback;
    }

    private static boolean isHttpTemplate(String value) {
        String probe = value
                .replace("{id}", "x")
                .replace("{title}", "x")
                .replace("{url}", "x");
        try {
            Uri uri = Uri.parse(probe);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isOpenablePage(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return uri.getHost() != null && !uri.getHost().isEmpty();
            }
            return "file".equalsIgnoreCase(scheme)
                    && uri.getPath() != null && !uri.getPath().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================== 订阅管理 ====================

    public Result register(WebPushSubscription subscription) {
        if (subscription == null) {
            return new Result(false, "订阅为空", null);
        }
        WebPushSubscription actual;
        synchronized (mutationLock) {
            List<WebPushSubscription> subscriptions = store.load();
            WebPushSubscription sameId = findById(subscriptions, subscription.id);
            String origin = originOf(subscription.pageUrl);
            if (sameId != null && !origin.equals(originOf(sameId.pageUrl))) {
                return new Result(false, "id 已被其他页面占用，请换一个", null);
            }
            WebPushSubscription existing = sameId != null
                    ? sameId : findByEndpoint(subscriptions, subscription.endpoint, origin);
            if (existing != null) {
                subscription.id = existing.id;
                subscription.seenIds = existing.seenIds == null
                        ? new ArrayList<>() : new ArrayList<>(existing.seenIds);
                subscription.lastPollAt = existing.lastPollAt;
                subscription.createdAt = existing.createdAt;
                subscriptions.remove(existing);
                subscriptions.add(subscription);
                actual = subscription;
            } else {
                if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
                    return new Result(false, "最多保留 " + MAX_SUBSCRIPTIONS + " 个后台提醒", null);
                }
                actual = subscription;
                subscriptions.add(actual);
            }
            // 请求头里通常带 Authorization/Bearer，落盘前用 Keystore 加密
            actual.headersJson = com.oilquiz.app.ai.util.ApiKeyCipher.encrypt(actual.headersJson);
            store.save(subscriptions);
        }
        scheduleWork();
        pollNow();
        return new Result(true, null, actual);
    }

    public boolean unregister(String idOrEndpoint) {
        return unregister(idOrEndpoint, null);
    }

    /** 只允许移除当前页面来源自己的订阅，避免一个站点删掉别的站点的后台提醒 */
    public boolean unregister(String idOrEndpoint, String pageUrl) {
        if (idOrEndpoint == null || idOrEndpoint.isEmpty()) return false;
        String origin = originOf(pageUrl);
        synchronized (mutationLock) {
            List<WebPushSubscription> subscriptions = store.load();
            boolean removed = subscriptions.removeIf(s ->
                    (idOrEndpoint.equals(s.id) || idOrEndpoint.equals(s.endpoint))
                            && (pageUrl == null || origin.equals(originOf(s.pageUrl))));
            if (removed) store.save(subscriptions);
            scheduleWork();
            return removed;
        }
    }

    public List<WebPushSubscription> list() {
        return store.load();
    }

    /** 只返回当前页面来源自己的订阅 */
    public List<WebPushSubscription> listForPage(String pageUrl) {
        String origin = originOf(pageUrl);
        List<WebPushSubscription> out = new ArrayList<>();
        for (WebPushSubscription subscription : store.load()) {
            if (origin.equals(originOf(subscription.pageUrl))) out.add(subscription);
        }
        return out;
    }

    public boolean hasSubscription(String endpoint, String pageUrl) {
        if (endpoint == null) return false;
        String origin = originOf(pageUrl);
        for (WebPushSubscription subscription : store.load()) {
            if (endpoint.equals(subscription.endpoint)
                    && origin.equals(originOf(subscription.pageUrl))) {
                return true;
            }
        }
        return false;
    }

    /** 稳定设备标识，供页面作为订阅身份带给自己的服务器（非 Android ID） */
    public String deviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    private static WebPushSubscription findById(List<WebPushSubscription> subscriptions, String id) {
        if (id == null) return null;
        for (WebPushSubscription subscription : subscriptions) {
            if (id.equals(subscription.id)) return subscription;
        }
        return null;
    }

    private static WebPushSubscription findByEndpoint(List<WebPushSubscription> subscriptions,
                                                      String endpoint, String origin) {
        if (endpoint == null) return null;
        for (WebPushSubscription subscription : subscriptions) {
            if (endpoint.equals(subscription.endpoint)
                    && origin.equals(originOf(subscription.pageUrl))) {
                return subscription;
            }
        }
        return null;
    }

    /** 页面来源（scheme://host:port）；file:// 统一成 "file://" */
    static String originOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null) return "";
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return scheme.toLowerCase(Locale.ROOT) + "://";
            int port = uri.getPort();
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                    + (port > 0 ? ":" + port : "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ==================== 调度 ====================

    public void scheduleWork() {
        List<WebPushSubscription> subscriptions = store.load();
        try {
            if (subscriptions.isEmpty()) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
                return;
            }
            int interval = MAX_INTERVAL_MINUTES;
            for (WebPushSubscription subscription : subscriptions) {
                interval = Math.min(interval, subscription.intervalMinutes);
            }
            interval = Math.max(MIN_INTERVAL_MINUTES, interval);

            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest request =
                    new PeriodicWorkRequest.Builder(WebPushWorker.class, interval, TimeUnit.MINUTES)
                            .setConstraints(constraints)
                            .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request);
            Log.i(TAG, "后台提醒轮询已调度，间隔 " + interval + " 分钟，共 "
                    + subscriptions.size() + " 个订阅");
        } catch (Throwable t) {
            Log.w(TAG, "调度后台提醒失败: " + t.getMessage());
        }
    }

    /** 立即检查一次（注册后先跑一轮，不等 15 分钟） */
    public void pollNow() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            // 页面可以频繁调用；30 秒内只排队一次，避免把收件箱打爆
            if (now - lastPollNowAt < 30_000L) return;
            lastPollNowAt = now;
        }
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WebPushWorker.class)
                    .setConstraints(constraints)
                    .setInputData(new Data.Builder().putBoolean("force", true).build())
                    .build();
            WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NOW, ExistingWorkPolicy.REPLACE, request);
        } catch (Throwable t) {
            Log.w(TAG, "立即检查调度失败: " + t.getMessage());
        }
    }

    // ==================== 轮询 ====================

    public void pollAll(boolean force) {
        synchronized (pollLock) {
            List<WebPushSubscription> subscriptions = store.load();
            if (subscriptions.isEmpty()) return;
            long now = System.currentTimeMillis();
            boolean polledAny = false;
            for (WebPushSubscription subscription : subscriptions) {
                if (!force && subscription.lastPollAt > 0
                        && now - subscription.lastPollAt < subscription.intervalMinutes * 60_000L) {
                    continue;
                }
                try {
                    pollOne(subscription);
                } catch (Throwable t) {
                    Log.w(TAG, "轮询失败[" + subscription.id + "]: " + t.getMessage());
                }
                subscription.lastPollAt = System.currentTimeMillis();
                polledAny = true;
            }
            if (!polledAny) return;

            // 轮询期间用户可能新增/删除了订阅，只把运行结果合并回当前表，避免整表覆盖
            synchronized (mutationLock) {
                List<WebPushSubscription> current = store.load();
                for (WebPushSubscription polled : subscriptions) {
                    WebPushSubscription target = findById(current, polled.id);
                    if (target != null) {
                        target.lastPollAt = polled.lastPollAt;
                        target.seenIds = polled.seenIds == null
                                ? new ArrayList<>() : new ArrayList<>(polled.seenIds);
                    }
                }
                store.save(current);
            }
        }
    }

    private void pollOne(WebPushSubscription subscription) throws IOException {
        if (subscription == null || subscription.endpoint == null) return;

        Request.Builder builder = new Request.Builder()
                .url(subscription.endpoint)
                .header("User-Agent", "SmartQuiz-WebPush/1.0")
                .header("Accept", "application/json");

        String contentType = "application/json; charset=utf-8";
        String method = subscription.method == null ? "GET" : subscription.method;
        try {
            String storedHeaders = subscription.headersJson == null ? "{}" : subscription.headersJson;
            storedHeaders = com.oilquiz.app.ai.util.ApiKeyCipher.decrypt(storedHeaders);
            JSONObject headers = new JSONObject(storedHeaders);
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, "");
                if ("content-type".equalsIgnoreCase(key) && "POST".equalsIgnoreCase(method)) {
                    contentType = value;
                    continue;
                }
                builder.header(key, value);
            }
        } catch (Throwable ignored) {
        }

        if ("POST".equalsIgnoreCase(method)) {
            MediaType type = MediaType.parse(contentType);
            if (type == null) type = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(
                    subscription.body == null ? "" : subscription.body, type);
            builder.post(body);
        } else {
            builder.get();
        }

        try (Response response = http.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            String text = response.peekBody(MAX_RESPONSE_BYTES).string();
            List<WebPushMessageParser.Message> messages =
                    WebPushMessageParser.parse(text, subscription);
            if (messages.isEmpty()) return;
            if (!WebPushNotifier.canNotify(context)) {
                Log.w(TAG, "通知权限未授予，本轮不标记消息已读");
                return;
            }
            int shown = 0;
            for (WebPushMessageParser.Message message : messages) {
                if (message.id == null || message.id.isEmpty()) continue;
                if (subscription.seenIds == null) subscription.seenIds = new ArrayList<>();
                if (subscription.seenIds.contains(message.id)) continue;
                WebPushNotifier.show(context, subscription, message);
                subscription.seenIds.add(message.id);
                while (subscription.seenIds.size() > MAX_SEEN_IDS) {
                    subscription.seenIds.remove(0);
                }
                shown++;
                if (shown >= MAX_BODY_PER_POLL) break;
            }
        }
    }

    // ==================== JSON 输出（给页面回调） ====================

    public static String resultJson(Result result) {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", result != null && result.ok);
            if (result == null || !result.ok) {
                out.put("error", result == null ? "未知错误" : result.error);
            } else if (result.subscription != null) {
                out.put("id", result.subscription.id);
                out.put("intervalMinutes", result.subscription.intervalMinutes);
            }
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"结果序列化失败\"}";
        }
    }

    public static String listJson(List<WebPushSubscription> subscriptions) {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            JSONArray array = new JSONArray();
            for (WebPushSubscription subscription : subscriptions) {
                JSONObject item = new JSONObject();
                item.put("id", subscription.id);
                item.put("endpoint", subscription.endpoint);
                item.put("intervalMinutes", subscription.intervalMinutes);
                item.put("lastPollAt", subscription.lastPollAt);
                array.put(item);
            }
            out.put("subscriptions", array);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"列表序列化失败\"}";
        }
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString((text == null ? "" : text).hashCode());
        }
    }
}
