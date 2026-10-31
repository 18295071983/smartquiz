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

    /**
     * 单例。此前每个使用方各 new 一份，各自握着独立的内存 LRU —— 任何"作废缓存"的路径
     * 只清得清自己那份，聊天页里的副本照旧命中。引擎变更失效必须打到唯一实例上。
     */
    private static volatile CacheManager INSTANCE;

    private CacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.memoryCache = new LRUCache<>(DEFAULT_CACHE_SIZE);
    }

    public static CacheManager getInstance(Context context) {
        CacheManager local = INSTANCE;
        if (local == null) {
            synchronized (CacheManager.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new CacheManager(context);
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    /**
     * 推理引擎发生变更时作废 Prompt→Response 缓存（切 GPU 后端 / 改 gpu_layers / 开关 NPU）。
     *
     * <p>WHY：缓存 key 只有 prompt，不带引擎与模型身份。同一句话在 hexagon 与 vulkan 下产出不同，
     * 12 小时内会命中上一个引擎（甚至引擎正坏着时）写入的答案，表现就是"切了后端还是错 / 又对了"
     * 这种无法复现的假象。真机 2026-10-31 就出现过切后端后答案形态突变的情况。</p>
     *
     * <p>内存 LRU 与 SharedPreferences 必须一起清：只清 prefs 的话，聊天页仍握着内存副本。</p>
     */
    public static void invalidateEngineCache(Context context, String reason) {
        if (context == null) return;
        try {
            CacheManager manager = getInstance(context);
            int dropped;
            synchronized (manager.memoryCache) {
                dropped = manager.memoryCache.size();
            }
            manager.clearAllCache();
            AILogger.audit(TAG, "引擎变更作废答案缓存: " + reason + "，清除内存条目 " + dropped);
        } catch (Throwable t) {
            AILogger.w(TAG, "引擎变更作废缓存失败: " + t.getMessage());
        }
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

    /**
     * 失效指定 prompt 的缓存（用于重新生成时跳过缓存）
     */
    public void invalidateCache(String prompt) {
        if (prompt == null) return;
        String key = String.valueOf(prompt.hashCode());
        memoryCache.remove(key);
        prefs.edit().remove(key).remove(key + "_timestamp").apply();
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
