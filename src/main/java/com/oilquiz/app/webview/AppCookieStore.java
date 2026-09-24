package com.oilquiz.app.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.infra.AppLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用级 Cookie 持久化仓库。
 *
 * 背景：WebView 的 CookieManager 是进程内单例、登录态只存在于 WebView 自己的存储里，
 * 其他通道（python_web_reader 的 requests、原生 OkHttp）拿不到。
 * 本类把 WebView 登录后种下的 Cookie 快照到 SharedPreferences，
 * 供 web_render / python_web_reader / Network(OkHttp) 复用，实现"一次登录、处处可用"。
 *
 * 存储结构（按"主域名"分组，记录整体快照）：
 *   cookie.<host>  → "k=v; k2=v2; ..."（含 HttpOnly，CookieManager.getCookie 可读出）
 *   ts.<host>      → 最近一次捕获时间戳
 *
 * 读取匹配规则：目标 URL 的 host 先精确匹配，再逐级向父域名匹配
 * （如 www.example.com → www.example.com / example.com），父域 Cookie 作为兜底。
 */
public class AppCookieStore {

    private static final String TAG = "AppCookieStore";
    private static final String PREFS_NAME = "app_cookie_store";
    private static final String KEY_PREFIX = "cookie.";
    private static final String TS_PREFIX = "ts.";

    private static volatile AppCookieStore instance;

    private final SharedPreferences prefs;

    private AppCookieStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static AppCookieStore getInstance() {
        if (instance == null) {
            synchronized (AppCookieStore.class) {
                if (instance == null) {
                    instance = new AppCookieStore(SmartQuizApplication.getAppContext());
                }
            }
        }
        return instance;
    }

    /**
     * 从 WebView 的 CookieManager 捕获指定 URL 的 Cookie 并持久化。
     * 在页面加载完成后调用（主线程即可，内部直接读 CookieManager）。
     *
     * @return 捕获到的 cookie 对数（0 表示没有 cookie）
     */
    public synchronized int captureFromWebView(String url) {
        if (url == null || url.trim().isEmpty()) return 0;
        String host = normalizeHost(url);
        if (host == null) return 0;
        try {
            String raw = CookieManager.getInstance().getCookie(url);
            Map<String, String> pairs = parsePairs(raw);
            if (pairs.isEmpty()) return 0;
            String snapshot = joinPairs(pairs);
            prefs.edit()
                    .putString(KEY_PREFIX + host, snapshot)
                    .putLong(TS_PREFIX + host, System.currentTimeMillis())
                    .apply();
            AppLogger.d(TAG, "已捕获 Cookie[" + host + "] 共 " + pairs.size() + " 个");
            return pairs.size();
        } catch (Throwable t) {
            AppLogger.e(TAG, "捕获 Cookie 失败: " + t.getMessage());
            return 0;
        }
    }

    /**
     * 获取目标 URL 可用的 Cookie 请求头值（"k=v; k2=v2"），无匹配返回 null。
     * 精确 host 优先，父域名兜底；同名 key 精确 host 覆盖父域。
     */
    public synchronized String getCookieHeader(String url) {
        String host = normalizeHost(url);
        if (host == null) return null;
        List<String> candidates = hostCandidates(host);
        if (candidates.isEmpty()) return null;

        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        for (String cand : candidates) {
            String snapshot = prefs.getString(KEY_PREFIX + cand, null);
            if (snapshot == null || snapshot.isEmpty()) continue;
            Map<String, String> pairs = parsePairs(snapshot);
            for (Map.Entry<String, String> e : pairs.entrySet()) {
                if (!merged.containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        return merged.isEmpty() ? null : joinPairs(merged);
    }

    /**
     * 把已持久化的 Cookie 注入 WebView 的 CookieManager（目标 URL 加载前调用）。
     * 使 web_render 等新开的 WebView 能带着登录态访问页面。
     */
    public synchronized void injectToWebView(String url) {
        String host = normalizeHost(url);
        if (host == null) return;
        List<String> candidates = hostCandidates(host);
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            for (String cand : candidates) {
                String snapshot = prefs.getString(KEY_PREFIX + cand, null);
                if (snapshot == null || snapshot.isEmpty()) continue;
                for (Map.Entry<String, String> e : parsePairs(snapshot).entrySet()) {
                    String cookie = e.getKey() + "=" + e.getValue() + "; Path=/";
                    if (!cand.equals(host)) {
                        cookie += "; Domain=" + cand;
                    }
                    try {
                        cm.setCookie(url, cookie);
                    } catch (Throwable ignored) {
                    }
                }
            }
            try {
                cm.flush();
            } catch (Throwable ignored) {
            }
            AppLogger.d(TAG, "已向 WebView 注入 Cookie: " + url);
        } catch (Throwable t) {
            AppLogger.e(TAG, "注入 Cookie 失败: " + t.getMessage());
        }
    }

    /** 目标 URL 是否已有登录态 Cookie */
    public synchronized boolean hasLogin(String url) {
        return getCookieHeader(url) != null;
    }

    /** 已存储的域名列表（用于日志/展示） */
    public synchronized List<String> getDomains() {
        List<String> hosts = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_PREFIX) && e.getValue() != null) {
                String s = String.valueOf(e.getValue());
                if (!s.isEmpty()) hosts.add(e.getKey().substring(KEY_PREFIX.length()));
            }
        }
        return hosts;
    }

    /** 清空全部 Cookie 快照 */
    public synchronized void clear() {
        prefs.edit().clear().apply();
        AppLogger.d(TAG, "已清空 Cookie 快照");
    }

    // ===== 内部工具 =====

    /** 从完整 URL 提取小写 host（去 scheme/port），无法解析返回 null */
    private static String normalizeHost(String url) {
        if (url == null) return null;
        String u = url.trim();
        int scheme = u.indexOf("://");
        if (scheme >= 0) {
            u = u.substring(scheme + 3);
        }
        int slash = u.indexOf('/');
        if (slash >= 0) u = u.substring(0, slash);
        int at = u.lastIndexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        int colon = u.indexOf(':');
        if (colon >= 0) u = u.substring(0, colon);
        u = u.trim().toLowerCase(Locale.ROOT);
        if (u.isEmpty()) return null;
        // 去掉可能的 []（IPv6 等，简单处理）
        if (u.startsWith("[")) {
            int close = u.indexOf(']');
            if (close > 0) u = u.substring(0, close + 1);
        }
        return u;
    }

    /** host 候选：自身 + 逐级父域名（保留至少两级标签） */
    private static List<String> hostCandidates(String host) {
        List<String> list = new ArrayList<>();
        String[] labels = host.split("\\.");
        if (labels.length <= 2) {
            list.add(host);
            return list;
        }
        for (int i = 0; i < labels.length - 1; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < labels.length; j++) {
                if (j > i) sb.append('.');
                sb.append(labels[j]);
            }
            list.add(sb.toString());
        }
        return list;
    }

    /** "k=v; k2=v2" → 有序 Map（去重，保留 HttpOnly） */
    private static Map<String, String> parsePairs(String cookieLine) {
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        if (cookieLine == null || cookieLine.isEmpty()) return pairs;
        String[] parts = cookieLine.split(";");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).trim();
            String value = p.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            pairs.put(name, value);
        }
        return pairs;
    }

    private static String joinPairs(Map<String, String> pairs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
