package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 插件下发的视图定义缓存（C5，2026-11-14）。
 *
 * <p>插件在连接建立时推 {@code {type:'view_def', version, views:{名称:组件树}}}；
 * 手机存本地，之后要用时按名字取（命令里写 {@code {view:'名称'}} 即可打开）。
 * 这样"改界面"只在插件里改一份定义，手机不发版。</p>
 *
 * <p>版本号只用于展示/排查，不做严格协商；字段不认识就忽略，不会崩。</p>
 */
public final class RemoteViewDefs {

    private static final String PREFS = "remote_view_defs";
    private static final String KEY_JSON = "views";
    private static final String KEY_VER = "version";

    private static volatile JSONObject cached = null;

    private RemoteViewDefs() {
    }

    /** 应用一帧 view_def；返回 true 表示已处理 */
    public static boolean apply(Context c, JSONObject frame) {
        if (c == null || frame == null) return false;
        JSONObject views = frame.optJSONObject("views");
        if (views == null || views.length() == 0) return false;
        cached = views;
        try {
            SharedPreferences sp = c.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_JSON, views.toString())
                    .putInt(KEY_VER, frame.optInt("version", 0))
                    .apply();
        } catch (Throwable ignored) { /* 存不下不影响本次使用 */ }
        return true;
    }

    /** 按名字取视图（内存没命中就读磁盘缓存） */
    public static JSONObject get(Context c, String name) {
        JSONObject views = cached;
        if (views == null && c != null) {
            try {
                SharedPreferences sp = c.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String json = sp.getString(KEY_JSON, "");
                if (json != null && !json.isEmpty()) {
                    views = new JSONObject(json);
                    cached = views;
                }
            } catch (Throwable ignored) { /* 缓存坏了就当没有 */ }
        }
        if (views == null) return null;
        JSONObject v = views.optJSONObject(name);
        return v != null ? v : views.optJSONObject("default");
    }

    /** 当前缓存的视图名（诊断用） */
    public static String names(Context c) {
        JSONObject views = cached;
        if (views == null && c != null) get(c, "__none__");
        views = cached;
        if (views == null) return "";
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = views.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(it.next());
        }
        return sb.toString();
    }
}
