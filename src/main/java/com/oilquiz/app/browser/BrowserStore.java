package com.oilquiz.app.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 内置浏览器的历史记录与书签（SharedPreferences + JSON，单文件、小体量）。
 *
 * <p>为什么不用 Room：这两份数据是"最近 N 条"的展示列表，没有关联查询；
 * 用 JSON 一份存储读写都在主线程内完成（<1ms），也避免给数据库再加两张表。
 * 所有写操作都是"整表覆盖"，条目上限见 {@link #MAX_HISTORY}/{@link #MAX_BOOKMARKS}。</p>
 */
public final class BrowserStore {

    private static final String PREFS = "browser_store";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_BOOKMARKS = "bookmarks";

    static final int MAX_HISTORY = 500;
    static final int MAX_BOOKMARKS = 200;

    private static volatile BrowserStore instance;

    private final SharedPreferences prefs;
    private final Object lock = new Object();

    private BrowserStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static BrowserStore get(Context context) {
        if (instance == null) {
            synchronized (BrowserStore.class) {
                if (instance == null) {
                    instance = new BrowserStore(context);
                }
            }
        }
        return instance;
    }

    /** 一条历史/书签。time 为毫秒时间戳。 */
    public static final class Entry {
        public final String url;
        public final String title;
        public final long time;

        public Entry(String url, String title, long time) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.time = time;
        }

        public String displayTitle() {
            if (!title.isEmpty()) return title;
            String host = BrowserUrlNormalizer.hostOf(url);
            return host.isEmpty() ? url : host;
        }
    }

    // ==================== 历史 ====================

    /**
     * 记录一次访问。同一 URL 只保留最新一条（移动/更新到最前）。
     * 只记录 http(s) 页面；起始页、about/data 等不算历史。
     */
    public void recordVisit(String url, String title) {
        if (!BrowserUrlNormalizer.isWebUrl(url)) return;
        String cleanTitle = title == null ? "" : title.trim();
        synchronized (lock) {
            List<Entry> list = read(KEY_HISTORY);
            list.removeIf(e -> url.equals(e.url));
            list.add(0, new Entry(url, cleanTitle, System.currentTimeMillis()));
            trim(list, MAX_HISTORY);
            write(KEY_HISTORY, list);
        }
    }

    /**
     * @param query 为空不过滤；否则在 URL/标题里做不区分大小写的包含匹配
     */
    public List<Entry> getHistory(String query, int limit) {
        synchronized (lock) {
            List<Entry> all = read(KEY_HISTORY);
            List<Entry> out = new ArrayList<>();
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            for (Entry e : all) {
                if (q.isEmpty()
                        || e.url.toLowerCase(Locale.ROOT).contains(q)
                        || e.title.toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(e);
                    if (limit > 0 && out.size() >= limit) break;
                }
            }
            return out;
        }
    }

    public void removeHistory(String url) {
        if (url == null) return;
        synchronized (lock) {
            List<Entry> list = read(KEY_HISTORY);
            list.removeIf(e -> url.equals(e.url));
            write(KEY_HISTORY, list);
        }
    }

    public void clearHistory() {
        synchronized (lock) {
            write(KEY_HISTORY, Collections.emptyList());
        }
    }

    // ==================== 书签 ====================

    public List<Entry> getBookmarks() {
        synchronized (lock) {
            return read(KEY_BOOKMARKS);
        }
    }

    public boolean isBookmarked(String url) {
        if (url == null) return false;
        synchronized (lock) {
            for (Entry e : read(KEY_BOOKMARKS)) {
                if (url.equals(e.url)) return true;
            }
            return false;
        }
    }

    /**
     * 有则删、无则加。
     *
     * @return true 表示本次是"添加"，false 表示"移除"
     */
    public boolean toggleBookmark(String url, String title) {
        if (url == null || url.isEmpty()) return false;
        synchronized (lock) {
            List<Entry> list = read(KEY_BOOKMARKS);
            for (int i = 0; i < list.size(); i++) {
                if (url.equals(list.get(i).url)) {
                    list.remove(i);
                    write(KEY_BOOKMARKS, list);
                    return false;
                }
            }
            list.add(0, new Entry(url, title == null ? "" : title.trim(), System.currentTimeMillis()));
            trim(list, MAX_BOOKMARKS);
            write(KEY_BOOKMARKS, list);
            return true;
        }
    }

    public void removeBookmark(String url) {
        if (url == null) return;
        synchronized (lock) {
            List<Entry> list = read(KEY_BOOKMARKS);
            list.removeIf(e -> url.equals(e.url));
            write(KEY_BOOKMARKS, list);
        }
    }

    // ==================== 序列化 ====================

    private List<Entry> read(String key) {
        List<Entry> list = new ArrayList<>();
        String raw;
        try {
            raw = prefs.getString(key, null);
        } catch (Throwable t) {
            return list;
        }
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("u", "");
                if (url.isEmpty()) continue;
                list.add(new Entry(url, o.optString("t", ""), o.optLong("ts", 0L)));
            }
        } catch (Throwable ignored) {
            // 损坏的旧数据当空表处理，下一次写入会覆盖
        }
        return list;
    }

    private void write(String key, List<Entry> list) {
        JSONArray array = new JSONArray();
        for (Entry e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("u", e.url);
                o.put("t", e.title);
                o.put("ts", e.time);
                array.put(o);
            } catch (Throwable ignored) {
            }
        }
        prefs.edit().putString(key, array.toString()).apply();
    }

    private static void trim(List<Entry> list, int max) {
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }
}
