package com.oilquiz.app.ai.model;

import com.oilquiz.app.util.AILogger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 安全 DNS：针对被运营商 DNS 污染的域名（如 hf-mirror.com 被解析到 127.0.0.1），
 * 改用 HTTPS DoH 解析真实 IP 直连下载，绕过系统 DNS 污染；解析结果短时缓存。
 * <p>
 * 背景：用户设备在蜂窝网络下，宁夏运营商 DNS（如 202.100.96.68）把 hf-mirror.com
 * 劫持/污染为 127.0.0.1，导致模型下载"Failed to connect to hf-mirror.com/127.0.0.1:443"。
 * 本类通过阿里/腾讯 DoH 获取真实 IP（实测 160.16.86.14），OkHttp 仍以原域名完成
 * HTTPS 握手（SNI/Host/证书校验不受影响），完全透明。
 */
public class SafeDns implements Dns {

    private static final String TAG = "SafeDns";

    /** 已知可能被运营商 DNS 污染的 HuggingFace 系域名（小写匹配，支持子域） */
    private static final List<String> POLLUTED_HOSTS = Arrays.asList(
            "hf-mirror.com", "huggingface.co", "www.hf-mirror.com", "cdn-lfs.hf-mirror.com"
    );

    /** DoH 服务（国内可达，域名本身不被污染）：主 doh.pub / 备 dns.alidns.com */
    private static final String[] DOH_ENDPOINTS = {
            "https://doh.pub/dns-query?name=%s&type=A",
            "https://dns.alidns.com/resolve?name=%s&type=A"
    };

    /** 缓存 TTL：5 分钟（CDN IP 可能变化，不宜过长） */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final OkHttpClient DOH_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5000, TimeUnit.MILLISECONDS)
            .readTimeout(5000, TimeUnit.MILLISECONDS)
            .build();

    private static final Map<String, CachedEntry> CACHE = new ConcurrentHashMap<>();

    private static final class CachedEntry {
        final List<InetAddress> addresses;
        final long expireAt;

        CachedEntry(List<InetAddress> addresses, long expireAt) {
            this.addresses = addresses;
            this.expireAt = expireAt;
        }
    }

    private static String key(String hostname) {
        return hostname == null ? "" : hostname.toLowerCase(Locale.US);
    }

    private static boolean isPolluted(String hostname) {
        if (hostname == null) return false;
        String h = key(hostname);
        for (String p : POLLUTED_HOSTS) {
            if (h.equals(p) || h.endsWith("." + p)) return true;
        }
        return false;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (!isPolluted(hostname)) {
            return Dns.SYSTEM.lookup(hostname);
        }
        // 命中污染域名：查缓存 → DoH 解析 → fallback 系统 DNS
        CachedEntry cached = CACHE.get(key(hostname));
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.addresses;
        }
        try {
            List<InetAddress> addrs = dohResolve(hostname);
            if (addrs != null && !addrs.isEmpty()) {
                CACHE.put(key(hostname),
                        new CachedEntry(addrs, System.currentTimeMillis() + CACHE_TTL_MS));
                AILogger.i(TAG, "DoH 解析 " + hostname + " → " + addrs);
                return addrs;
            }
        } catch (Exception e) {
            AILogger.w(TAG, "DoH 解析异常 " + hostname + " : " + e.getMessage());
        }
        AILogger.w(TAG, "DoH 解析失败，回退系统 DNS: " + hostname);
        return Dns.SYSTEM.lookup(hostname);
    }

    private static List<InetAddress> dohResolve(String hostname) {
        for (String endpoint : DOH_ENDPOINTS) {
            try {
                String url = String.format(Locale.US, endpoint, hostname);
                Request req = new Request.Builder()
                        .url(url)
                        .header("Accept", "application/dns-json")
                        .build();
                try (Response resp = DOH_CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) continue;
                    String body = resp.body().string();
                    JSONObject obj = new JSONObject(body);
                    if (obj.optInt("Status", -1) != 0) continue;
                    JSONArray answers = obj.optJSONArray("Answer");
                    List<InetAddress> result = new ArrayList<>();
                    if (answers != null) {
                        for (int i = 0; i < answers.length(); i++) {
                            JSONObject a = answers.optJSONObject(i);
                            if (a == null) continue;
                            if (a.optInt("type") == 1) { // A 记录
                                String ip = a.optString("data");
                                if (ip != null && !ip.isEmpty() && !ip.startsWith("127.")) {
                                    try {
                                        result.add(InetAddress.getByName(ip));
                                    } catch (UnknownHostException ignored) {
                                        // 忽略非法 IP
                                    }
                                }
                            }
                        }
                    }
                    if (!result.isEmpty()) return result;
                }
            } catch (Exception e) {
                AILogger.w(TAG, "DoH endpoint 失败 " + endpoint + " : " + e.getMessage());
            }
        }
        return null;
    }
}
