package com.oilquiz.app.ai.remote.view;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 效果执行器（C2，2026-11-14）。
 *
 * <p>插件永远不直接操作手机：它只能"建议一串效果"，由这里按白名单校验后执行。
 * 白名单 9 种、一次 ≤4 个、**点按后 3 秒内有效**（迟到的丢弃），与 dsh-Go 的模型一致，
 * 额外加第 9 种 {@code open_file}（打开电脑上的文件，走 /ws/file）。</p>
 *
 * <p>校验与执行分开：{@link #check} 是纯函数（单测用），{@link #execute} 才碰 Context。</p>
 */
public final class EffectExecutor {

    public static final int MAX_EFFECTS = 4;
    public static final long WINDOW_MS = 3000L;

    /** 允许的效果类型 */
    public static final String[] ALLOWED = {
            "insert", "command", "open", "link", "toast", "copy", "refresh", "patch", "open_file",
    };

    /** 真正执行的地方（由 Activity 实现，因为要动 UI/输入框） */
    public interface Host {
        void insertText(String text);

        void runCommand(String name, String args);

        void openPage(String page);

        void openLink(String url);

        void toast(String text);

        void copy(String text);

        void refresh();

        void patch(JSONObject data);

        void openFile(String path);
    }

    private EffectExecutor() {
    }

    /**
     * 校验（纯函数）：返回可执行的效果；不合规的静默丢弃。
     *
     * @param clickAt 用户点按的时间戳（毫秒）
     * @param now     当前时间戳（毫秒）
     */
    public static List<JSONObject> check(JSONArray raw, long clickAt, long now) {
        List<JSONObject> out = new ArrayList<>();
        if (raw == null) return out;
        if (clickAt > 0 && now - clickAt > WINDOW_MS) return out;   // 迟到，全部丢弃
        for (int i = 0; i < raw.length() && out.size() < MAX_EFFECTS; i += 1) {
            JSONObject e = raw.optJSONObject(i);
            if (e == null) continue;
            String type = e.optString("type", "");
            if (!allowed(type)) continue;
            JSONObject norm = normalize(type, e);
            if (norm != null) out.add(norm);
        }
        return out;
    }

    /** 白名单判定（单测直接调） */
    public static boolean allowed(String type) {
        if (type == null) return false;
        for (String t : ALLOWED) if (t.equals(type)) return true;
        return false;
    }

    /**
     * 归一化 + 字段校验；不合规返回 null。
     * 规则照 dsh-Go：insert 1–2000 字、command 名不带 '/'、link 只 https ≤2048、
     * toast ≤40 字、copy 1–8000 字、patch ≤16KiB。
     */
    private static JSONObject normalize(String type, JSONObject e) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            switch (type) {
                case "insert": {
                    String text = e.optString("text", "");
                    if (text.isEmpty() || text.length() > 2000) return null;
                    o.put("text", text);
                    return o;
                }
                case "command": {
                    String name = e.optString("name", "").replaceFirst("^/", "");
                    if (name.isEmpty()) return null;
                    String args = e.optString("args", "");
                    if (args.length() > 500) args = args.substring(0, 500);
                    o.put("name", name);
                    o.put("args", args);
                    return o;
                }
                case "open": {
                    String page = e.optString("page", "");
                    if (page.isEmpty()) return null;
                    o.put("page", page);
                    return o;
                }
                case "link": {
                    String url = e.optString("url", "");
                    if (!url.startsWith("https://") || url.length() > 2048) return null;
                    o.put("url", url);
                    return o;
                }
                case "toast": {
                    String text = e.optString("text", "");
                    if (text.isEmpty()) return null;
                    if (text.length() > 40) text = text.substring(0, 40);
                    o.put("text", text);
                    return o;
                }
                case "copy": {
                    String text = e.optString("text", "");
                    if (text.isEmpty() || text.length() > 8000) return null;
                    o.put("text", text);
                    return o;
                }
                case "refresh":
                    return o;
                case "patch": {
                    JSONObject data = e.optJSONObject("data");
                    if (data == null || data.toString().length() > 16 * 1024) return null;
                    o.put("data", data);
                    return o;
                }
                case "open_file": {
                    String path = e.optString("path", "");
                    if (path.isEmpty()) return null;
                    o.put("path", path);
                    return o;
                }
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 执行（在主线程调用） */
    public static int execute(Context ctx, JSONArray raw, long clickAt, Host host) {
        List<JSONObject> list = check(raw, clickAt, System.currentTimeMillis());
        if (host == null) return 0;
        int done = 0;
        for (JSONObject e : list) {
            try {
                switch (e.optString("type")) {
                    case "insert": host.insertText(e.optString("text")); break;
                    case "command": host.runCommand(e.optString("name"), e.optString("args")); break;
                    case "open": host.openPage(e.optString("page")); break;
                    case "link": host.openLink(e.optString("url")); break;
                    case "toast": host.toast(e.optString("text")); break;
                    case "copy": host.copy(e.optString("text")); break;
                    case "refresh": host.refresh(); break;
                    case "patch": host.patch(e.optJSONObject("data")); break;
                    case "open_file": host.openFile(e.optString("path")); break;
                    default: continue;
                }
                done += 1;
            } catch (Throwable ignored) { /* 单个失败不影响其它 */ }
        }
        return done;
    }

    /** 便捷：直接用系统浏览器打开 https（Host 的默认实现可用） */
    public static void openLinkWithBrowser(Context ctx, String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable ignored) { /* 打不开就静默 */ }
    }
}
