package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.content.SharedPreferences;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ResponseCacheManager {
    private static final String TAG = "ResponseCacheManager";
    private static final String PREFS_NAME = "agent_response_cache";
    private static final int MAX_CACHED_SESSIONS = 10;
    private static final long CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L;

    private static volatile ResponseCacheManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final ConcurrentHashMap<String, SessionCache> sessionCaches;
    private final AtomicInteger hitCount = new AtomicInteger(0);
    private final AtomicInteger missCount = new AtomicInteger(0);

    public static ResponseCacheManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ResponseCacheManager.class) {
                if (instance == null) {
                    instance = new ResponseCacheManager(context);
                }
            }
        }
        return instance;
    }

    private ResponseCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.sessionCaches = new ConcurrentHashMap<>();
    }

    public SessionCache getOrCreateSession(String sessionId) {
        return sessionCaches.computeIfAbsent(sessionId, id -> new SessionCache(id));
    }

    public String getCachedResponse(String sessionId, String prompt) {
        SessionCache cache = sessionCaches.get(sessionId);
        if (cache == null) {
            missCount.incrementAndGet();
            return null;
        }
        String cached = cache.getResponse(prompt);
        if (cached != null) {
            hitCount.incrementAndGet();
            AILogger.d(TAG, "Cache hit for session: " + sessionId);
        } else {
            missCount.incrementAndGet();
        }
        return cached;
    }

    public void cacheResponse(String sessionId, String prompt, String response) {
        SessionCache cache = getOrCreateSession(sessionId);
        cache.putResponse(prompt, response);
        if (sessionCaches.size() > MAX_CACHED_SESSIONS) {
            evictOldestSession();
        }
    }

    public void clearSession(String sessionId) {
        sessionCaches.remove(sessionId);
    }

    public void clearAll() {
        sessionCaches.clear();
        prefs.edit().clear().apply();
    }

    private void evictOldestSession() {
        String oldestId = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, SessionCache> entry : sessionCaches.entrySet()) {
            if (entry.getValue().lastAccessTime < oldestTime) {
                oldestTime = entry.getValue().lastAccessTime;
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) {
            sessionCaches.remove(oldestId);
            AILogger.d(TAG, "Evicted oldest cache session: " + oldestId);
        }
    }

    public double getHitRate() {
        int total = hitCount.get() + missCount.get();
        return total > 0 ? (hitCount.get() * 100.0 / total) : 0;
    }

    public static class SessionCache {
        public final String sessionId;
        public final ConcurrentHashMap<String, String> responses;
        public volatile long lastAccessTime;

        public SessionCache(String sessionId) {
            this.sessionId = sessionId;
            this.responses = new ConcurrentHashMap<>();
            this.lastAccessTime = System.currentTimeMillis();
        }

        public String getResponse(String prompt) {
            String key = String.valueOf(prompt.hashCode());
            return responses.get(key);
        }

        public void putResponse(String prompt, String response) {
            String key = String.valueOf(prompt.hashCode());
            responses.put(key, response);
            lastAccessTime = System.currentTimeMillis();
        }
    }
}
