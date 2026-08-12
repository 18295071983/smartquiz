package com.oilquiz.app.ai.chat.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摘要缓存管理器 - 支持磁盘持久化
 */
public class SummaryCacheManager {
    
    private static final String TAG = "SummaryCacheManager";
    private static final String PREFS_NAME = "summary_cache";
    private static final long CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000; // 7天
    
    private static volatile SummaryCacheManager INSTANCE;
    private final Context context;
    private final SharedPreferences prefs;
    private final ConcurrentHashMap<String, CachedSummary> memoryCache;
    
    public static class CachedSummary {
        public String attachmentId;
        public String summary;
        public long timestamp;
        public String modelName; // 记录生成摘要的模型
        
        public CachedSummary() {}
        
        public CachedSummary(String attachmentId, String summary, String modelName) {
            this.attachmentId = attachmentId;
            this.summary = summary;
            this.modelName = modelName;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS;
        }
        
        public JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("attachmentId", attachmentId);
            json.put("summary", summary);
            json.put("timestamp", timestamp);
            json.put("modelName", modelName);
            return json;
        }
        
        public static CachedSummary fromJson(JSONObject json) throws Exception {
            CachedSummary cs = new CachedSummary();
            cs.attachmentId = json.getString("attachmentId");
            cs.summary = json.getString("summary");
            cs.timestamp = json.getLong("timestamp");
            cs.modelName = json.optString("modelName", "unknown");
            return cs;
        }
    }
    
    private SummaryCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.memoryCache = new ConcurrentHashMap<>();
        
        // 启动时从磁盘加载缓存
        loadFromDisk();
    }
    
    public static SummaryCacheManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (SummaryCacheManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SummaryCacheManager(context);
                }
            }
        }
        return INSTANCE;
    }
    
    /**
     * 保存摘要到缓存（内存+磁盘）
     */
    public void saveSummary(String attachmentId, String summary, String modelName) {
        if (attachmentId == null || summary == null) return;
        
        CachedSummary cached = new CachedSummary(attachmentId, summary, modelName);
        memoryCache.put(attachmentId, cached);
        
        // 异步保存到磁盘
        saveToDiskAsync(attachmentId, cached);
    }
    
    /**
     * 从缓存读取摘要
     */
    public String getSummary(String attachmentId) {
        CachedSummary cached = memoryCache.get(attachmentId);
        if (cached != null && !cached.isExpired()) {
            Log.d(TAG, "Cache hit for: " + attachmentId);
            return cached.summary;
        }
        
        // 内存未命中，尝试从磁盘加载
        CachedSummary diskCached = loadFromDisk(attachmentId);
        if (diskCached != null && !diskCached.isExpired()) {
            memoryCache.put(attachmentId, diskCached); // 回填内存
            Log.d(TAG, "Disk cache hit for: " + attachmentId);
            return diskCached.summary;
        }
        
        return null;
    }
    
    /**
     * 清除过期缓存
     */
    public void clearExpiredCache() {
        int cleared = 0;
        for (Map.Entry<String, CachedSummary> entry : memoryCache.entrySet()) {
            if (entry.getValue().isExpired()) {
                memoryCache.remove(entry.getKey());
                removeFromDisk(entry.getKey());
                cleared++;
            }
        }
        Log.i(TAG, "Cleared " + cleared + " expired cache entries");
    }
    
    /**
     * 清空所有缓存
     */
    public void clearAllCache() {
        memoryCache.clear();
        prefs.edit().clear().apply();
        Log.i(TAG, "All cache cleared");
    }
    
    // ========== 私有方法 ==========
    
    private void saveToDiskAsync(String key, CachedSummary summary) {
        new Thread(() -> {
            try {
                String jsonStr = summary.toJson().toString();
                prefs.edit().putString(key, jsonStr).apply();
            } catch (Exception e) {
                Log.e(TAG, "Failed to save cache to disk: " + e.getMessage());
            }
        }).start();
    }
    
    private CachedSummary loadFromDisk(String key) {
        try {
            String jsonStr = prefs.getString(key, null);
            if (jsonStr != null) {
                return CachedSummary.fromJson(new JSONObject(jsonStr));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cache from disk: " + e.getMessage());
        }
        return null;
    }
    
    private void removeFromDisk(String key) {
        prefs.edit().remove(key).apply();
    }
    
    private void loadFromDisk() {
        Map<String, ?> allEntries = prefs.getAll();
        int loaded = 0;
        
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            try {
                String jsonStr = (String) entry.getValue();
                CachedSummary summary = CachedSummary.fromJson(new JSONObject(jsonStr));
                if (!summary.isExpired()) {
                    memoryCache.put(entry.getKey(), summary);
                    loaded++;
                } else {
                    // 删除过期项
                    removeFromDisk(entry.getKey());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse cache entry: " + entry.getKey());
            }
        }
        
        Log.i(TAG, "Loaded " + loaded + " cache entries from disk");
    }
}
