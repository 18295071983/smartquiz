package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接配置列表（2026-11-14）。
 *
 * <p>以前手机只保存**一份**「地址 + 令牌」，在家用局域网、出门用 Tailscale 就得每次手改，
 * 一不小心把原来那份覆盖掉。现在按名字保存多份，点一下切换。</p>
 *
 * <p>存储：SharedPreferences 里一个 JSON 数组，最多 8 条，按 lastUsed 倒序。</p>
 */
public final class RemoteProfiles {

    private static final String PREFS = "remote_profiles";
    private static final String KEY = "list";
    private static final int MAX = 8;

    /** 一条连接配置 */
    public static final class Profile {
        public final String url;
        public final String token;
        public final String name;
        public final long lastUsed;

        Profile(String url, String token, String name, long lastUsed) {
            this.url = url == null ? "" : url;
            this.token = token == null ? "" : token;
            this.name = name == null ? "" : name;
            this.lastUsed = lastUsed;
        }

        public String label() {
            if (!name.isEmpty()) return name;
            String host = url.replaceFirst("^https?://", "");
            int slash = host.indexOf('/');
            return slash > 0 ? host.substring(0, slash) : host;
        }
    }

    private RemoteProfiles() {
    }

    public static synchronized List<Profile> list(Context c) {
        List<Profile> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i += 1) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                out.add(new Profile(url, o.optString("token", ""), o.optString("name", ""),
                        o.optLong("lastUsed", 0L)));
            }
        } catch (Throwable ignored) { /* 坏了就当空 */ }
        return out;
    }

    /** 保存/更新一条（按 url 去重），并标记为最近使用 */
    public static synchronized void save(Context c, String url, String token, String name) {
        if (c == null || url == null || url.trim().isEmpty()) return;
        String u = url.trim();
        List<Profile> all = list(c);
        JSONArray arr = new JSONArray();
        boolean replaced = false;
        try {
            JSONObject self = new JSONObject();
            self.put("url", u);
            self.put("token", token == null ? "" : token);
            self.put("name", name == null ? "" : name);
            self.put("lastUsed", System.currentTimeMillis());
            arr.put(self);
            int count = 1;
            for (Profile p : all) {
                if (p.url.equalsIgnoreCase(u)) { replaced = true; continue; }
                if (count >= MAX) continue;
                JSONObject o = new JSONObject();
                o.put("url", p.url);
                o.put("token", p.token);
                o.put("name", p.name);
                o.put("lastUsed", p.lastUsed);
                arr.put(o);
                count += 1;
            }
        } catch (Throwable ignored) { /* 忽略 */ }
        try {
            c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) { /* 忽略 */ }
    }

    public static synchronized void remove(Context c, String url) {
        if (c == null || url == null) return;
        JSONArray arr = new JSONArray();
        for (Profile p : list(c)) {
            if (p.url.equalsIgnoreCase(url)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("url", p.url);
                o.put("token", p.token);
                o.put("name", p.name);
                o.put("lastUsed", p.lastUsed);
                arr.put(o);
            } catch (Throwable ignored) { /* 忽略 */ }
        }
        try {
            c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) { /* 忽略 */ }
    }

    /** 除了当前地址以外，最值得推荐的一条（最近用过的） */
    public static Profile otherThan(Context c, String currentUrl) {
        String cur = currentUrl == null ? "" : currentUrl.trim();
        for (Profile p : list(c)) {
            if (!p.url.equalsIgnoreCase(cur)) return p;
        }
        return null;
    }
}
