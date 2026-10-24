package com.html2apk.wrapper.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播相关的本地存储：频道源、收藏、上次播放。
 *
 * <p>存储边界刻意做得很小：</p>
 * <ul>
 *   <li><b>频道源</b>只由 {@code saveLiveSource} 显式写入，打开一个临时 m3u 不会自动落盘；</li>
 *   <li><b>收藏</b>只由 {@code toggleLiveFavorite} 显式切换；</li>
 *   <li><b>上次播放</b>由播放器自动记录（用于"继续观看"），可用 {@code clearLiveHistory} 清掉。</li>
 * </ul>
 *
 * <p>SharedPreferences 的读改写都在 synchronized 内完成，桥线程与播放器线程不会互相覆盖。</p>
 */
public final class LiveStore {

    private static final String PREFS = "live_tv_store";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_LAST = "last";
    private static final int MAX_SOURCES = 50;
    private static final int MAX_FAVORITES = 200;

    public static final class Source {
        public final String id;
        public final String name;
        public final String url;
        public final int channelCount;
        public final long updatedAt;

        Source(String id, String name, String url, int channelCount, long updatedAt) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.channelCount = Math.max(0, channelCount);
            this.updatedAt = updatedAt;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("url", url);
                o.put("channelCount", channelCount);
                o.put("updatedAt", updatedAt);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private final SharedPreferences prefs;

    public LiveStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ==================== 频道源 ====================

    public synchronized List<Source> loadSources() {
        List<Source> out = new ArrayList<>();
        String raw = prefs.getString(KEY_SOURCES, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && out.size() < MAX_SOURCES; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "").trim();
                if (url.isEmpty()) continue;
                String name = o.optString("name", "");
                out.add(new Source(
                        o.optString("id", LiveChannel.sha1Short(url)),
                        name.isEmpty() ? deriveSourceName(url) : name,
                        url,
                        o.optInt("channelCount", 0),
                        o.optLong("updatedAt", 0)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 显式保存/更新一个频道源。按 URL 去重；同名不同 URL 视为两个源。
     */
    public synchronized Source saveSource(String name, String url, int channelCount) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return null;
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = deriveSourceName(u);
        if (n.length() > 40) n = n.substring(0, 40);

        List<Source> list = loadSources();
        String id = LiveChannel.sha1Short(u);
        Source found = null;
        for (Source s : list) {
            if (s.id.equals(id) || s.url.equals(u)) {
                found = s;
                break;
            }
        }
        if (found != null) {
            list.remove(found);
            // 只改名/重复保存时保留上次解析到的频道数，避免被 0 覆盖
            if (channelCount <= 0) channelCount = found.channelCount;
        }
        Source created = new Source(id, n, u, channelCount, System.currentTimeMillis());
        list.add(0, created);
        while (list.size() > MAX_SOURCES) list.remove(list.size() - 1);
        writeSources(list);
        return created;
    }

    public synchronized boolean updateSourceCount(String url, int channelCount) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return false;
        List<Source> list = loadSources();
        for (int i = 0; i < list.size(); i++) {
            Source s = list.get(i);
            if (!s.url.equals(u)) continue;
            list.set(i, new Source(s.id, s.name, s.url, channelCount, System.currentTimeMillis()));
            writeSources(list);
            return true;
        }
        return false;
    }

    public synchronized boolean deleteSource(String id) {
        if (id == null || id.isEmpty()) return false;
        List<Source> list = loadSources();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                writeSources(list);
                return true;
            }
        }
        return false;
    }

    private void writeSources(List<Source> list) {
        JSONArray arr = new JSONArray();
        for (Source s : list) arr.put(s.toJson());
        prefs.edit().putString(KEY_SOURCES, arr.toString()).apply();
    }

    // ==================== 收藏 ====================

    public synchronized List<LiveChannel> loadFavorites() {
        List<LiveChannel> out = new ArrayList<>();
        String raw = prefs.getString(KEY_FAVORITES, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && out.size() < MAX_FAVORITES; i++) {
                LiveChannel ch = LiveChannel.fromJson(arr.optJSONObject(i));
                if (ch != null) out.add(ch);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 切换收藏状态。
     *
     * @return true=已收藏，false=已取消
     */
    public synchronized boolean toggleFavorite(LiveChannel channel) {
        if (channel == null) return false;
        List<LiveChannel> list = loadFavorites();
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(channel.id)) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) {
            list.add(0, channel);
            while (list.size() > MAX_FAVORITES) list.remove(list.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (LiveChannel c : list) arr.put(c.toJson());
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply();
        return !removed;
    }

    // ==================== 上次播放 ====================

    public synchronized void setLastChannel(LiveChannel channel, String sourceName, int index) {
        if (channel == null || !channel.isValid()) return;
        JSONObject o = new JSONObject();
        try {
            o.put("channel", channel.toJson());
            o.put("source", sourceName == null ? "" : sourceName);
            o.put("index", Math.max(0, index));
            o.put("at", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_LAST, o.toString()).apply();
    }

    public synchronized JSONObject getLastChannel() {
        String raw = prefs.getString(KEY_LAST, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return o.optJSONObject("channel") == null ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void clearLastChannel() {
        prefs.edit().remove(KEY_LAST).apply();
    }

    private static String deriveSourceName(String url) {
        String s = url == null ? "" : url.trim();
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.lastIndexOf('/');
        if (cut >= 0 && cut + 1 < s.length()) s = s.substring(cut + 1);
        cut = s.lastIndexOf('.');
        if (cut > 0) s = s.substring(0, cut);
        if (s.isEmpty()) return "直播源";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
