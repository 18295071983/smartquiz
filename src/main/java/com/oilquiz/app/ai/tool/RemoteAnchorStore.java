package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 锚点存储（A3，2026-11-14）。
 *
 * <p>服务端在 {@code hello} 帧里下发 {@code anchor:{local_session_id, remote_session_id, acked_seq}}，
 * 手机把它记下来。之后：</p>
 * <ul>
 *   <li>收到事件时按锚点**查或建**本地会话（不是每次新建）；</li>
 *   <li>点「新建对话」时调 {@code POST /v2/anchor action=create}，用返回的 id 作为新锚点；</li>
 *   <li>清历史时清掉本地锚点并上报 {@code anchor:null}，让服务端重签。</li>
 * </ul>
 *
 * <p>每台电脑会话一条锚点（按 remote_session_id 区分），互不参合。</p>
 */
public final class RemoteAnchorStore {

    private static final String PREFS = "remote_anchors";
    private static final String KEY_CURRENT = "current";      // 当前跟随的锚点 JSON
    private static final String KEY_MAP = "by_remote";        // remote_session_id → 锚点 JSON

    private RemoteAnchorStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前跟随的锚点；没有返回 null */
    public static JSONObject current(Context c) {
        try {
            String s = prefs(c).getString(KEY_CURRENT, "");
            return s == null || s.isEmpty() ? null : new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前锚点里的本地会话 id（没有返回空串） */
    public static String currentLocalSessionId(Context c) {
        JSONObject a = current(c);
        return a == null ? "" : a.optString("local_session_id", "");
    }

    /** 记下服务端下发的锚点（hello 帧），并按 remote_session_id 归档 */
    public static void save(Context c, JSONObject anchor) {
        if (c == null || anchor == null) return;
        String remote = anchor.optString("remote_session_id", "");
        if (remote.isEmpty()) return;
        try {
            prefs(c).edit()
                    .putString(KEY_CURRENT, anchor.toString())
                    .putString(KEY_MAP + "." + remote, anchor.toString())
                    .apply();
        } catch (Throwable ignored) { /* 存不下不影响本次使用 */ }
    }

    /**
     * A3-接（2026-11-14）：某条电脑会话在**本机**对应的会话 id。
     *
     * <p>这是"查"那一步：有锚点就用锚点里的 {@code local_session_id}（文件在就复用、
     * 不在就用同一个 id 建），没有才让调用方随机新建。没有锚点时返回空串。</p>
     */
    public static String localSessionIdForRemote(Context c, String remoteSessionId) {
        JSONObject a = byRemote(c, remoteSessionId);
        return a == null ? "" : a.optString("local_session_id", "");
    }

    /** 按电脑会话 id 取锚点 */
    public static JSONObject byRemote(Context c, String remoteSessionId) {
        if (remoteSessionId == null || remoteSessionId.isEmpty()) return null;
        try {
            String s = prefs(c).getString(KEY_MAP + "." + remoteSessionId, "");
            return s == null || s.isEmpty() ? null : new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 清掉锚点（清历史时调）：只清本地这半账，服务端会重签 */
    public static void clear(Context c) {
        if (c == null) return;
        try {
            SharedPreferences p = prefs(c);
            SharedPreferences.Editor e = p.edit();
            for (String k : p.getAll().keySet()) {
                if (k != null && (k.equals(KEY_CURRENT) || k.startsWith(KEY_MAP))) e.remove(k);
            }
            e.apply();
        } catch (Throwable ignored) { /* 忽略 */ }
    }

    /**
     * 请求服务端新建一条锚点（手机点「新建对话」时调）。
     *
     * @return 新锚点 JSON；失败返回 null
     */
    public static JSONObject createRemote(Context c, String deviceId) {
        if (c == null) return null;
        try {
            RemoteDshTool tool = new RemoteDshTool(c);
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("device_id", deviceId == null ? "" : deviceId);
            body.put("action", "create");
            java.util.Map<String, Object> resp = tool.httpJson(tool.getBaseUrl() + "/v2/anchor", "POST", body, 20);
            if (!Boolean.TRUE.equals(resp.get("ok"))) return null;
            Object anchor = resp.get("anchor");
            if (!(anchor instanceof java.util.Map)) return null;
            JSONObject a = new JSONObject((java.util.Map<?, ?>) anchor);
            save(c, a);
            return a;
        } catch (Throwable t) {
            return null;
        }
    }
}
