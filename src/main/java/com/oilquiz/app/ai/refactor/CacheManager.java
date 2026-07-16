package com.oilquiz.app.ai.refactor;

import android.content.Context;
import android.content.SharedPreferences;
import com.oilquiz.app.util.AILogger;

import java.util.LinkedHashMap;
import java.util.Map;

public class CacheManager {
    private static final String TAG = "CacheManager";
    private static final String PREFS_NAME = "ai_cache";
    private static final int DEFAULT_CACHE_SIZE = 100;
    private static final long DEFAULT_EXPIRY_MS = 12 * 60 * 60 * 1000L;

    private final Context context;
    private final SharedPreferences prefs;
    private final LRUCache<String, CacheEntry> memoryCache;

    public CacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.memoryCache = new LRUCache<>(DEFAULT_CACHE_SIZE);
    }

    public String getCachedResponse(String prompt) {
        String key = String.valueOf(prompt.hashCode());
        CacheEntry entry = memoryCache.get(key);
        if (entry != null && !entry.isExpired()) return entry.value;

        String cached = prefs.getString(key, null);
        if (cached != null) {
            long timestamp = prefs.getLong(key + "_timestamp", 0);
            if (System.currentTimeMillis() - timestamp < DEFAULT_EXPIRY_MS) {
                memoryCache.put(key, new CacheEntry(cached, timestamp));
                return cached;
            } else {
                prefs.edit().remove(key).remove(key + "_timestamp").apply();
            }
        }
        return null;
    }

    public void cacheResponse(String prompt, String response) {
        String key = String.valueOf(prompt.hashCode());
        long timestamp = System.currentTimeMillis();
        memoryCache.put(key, new CacheEntry(response, timestamp));
        prefs.edit().putString(key, response).putLong(key + "_timestamp", timestamp).apply();
    }

    public void clearAllCache() {
        memoryCache.clear();
        prefs.edit().clear().apply();
    }

    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxSize;
        public int hits = 0;
        public int misses = 0;

        public LRUCache(int maxSize) {
            super(maxSize, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    }

    private static class CacheEntry {
        public final String value;
        public final long timestamp;

        public CacheEntry(String value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > DEFAULT_EXPIRY_MS;
        }
    }
}
