package com.oilquiz.app.ai.chat;

import android.os.Handler;
import android.os.Looper;

public class StreamingUpdateManager {
    private static final String TAG = "StreamingUpdateManager";
    
    private static final long DEFAULT_MIN_UPDATE_INTERVAL_MS = 50;
    private static final long DEFAULT_FORCE_UPDATE_INTERVAL_MS = 200;
    private static final int DEFAULT_MIN_TOKENS_BEFORE_UPDATE = 3;
    
    private final long minUpdateIntervalMs;
    private final long forceUpdateIntervalMs;
    private final int minTokensBeforeUpdate;
    
    private final Handler mainHandler;
    private final UpdateCallback callback;
    
    private final StringBuilder accumulatedTokens = new StringBuilder();
    private volatile boolean updateScheduled = false;
    private long lastUpdateTimeMs = 0;
    private int tokensSinceLastUpdate = 0;
    private int totalTokensGenerated = 0;
    private long startTimeMs = 0;
    private final Object lock = new Object();
    
    private final Runnable flushRunnable = this::flushUpdates;
    
    public interface UpdateCallback {
        void onUpdate(String accumulatedContent, int totalTokensSinceLastUpdate);
        default void onStatsUpdate(StreamingStats stats) {}
    }
    
    public static class StreamingStats {
        public final int totalTokens;
        public final int tokensInBatch;
        public final long elapsedMs;
        public final float tokensPerSecond;
        public final long timeSinceLastUpdateMs;
        
        public StreamingStats(int totalTokens, int tokensInBatch, long elapsedMs, float tokensPerSecond, long timeSinceLastUpdateMs) {
            this.totalTokens = totalTokens;
            this.tokensInBatch = tokensInBatch;
            this.elapsedMs = elapsedMs;
            this.tokensPerSecond = tokensPerSecond;
            this.timeSinceLastUpdateMs = timeSinceLastUpdateMs;
        }
        
        @Override
        public String toString() {
            return String.format("Tokens: %d, Speed: %.2f t/s, Elapsed: %.1fs", 
                totalTokens, tokensPerSecond, elapsedMs / 1000.0f);
        }
    }
    
    public StreamingUpdateManager(UpdateCallback callback) {
        this(callback, DEFAULT_MIN_UPDATE_INTERVAL_MS, DEFAULT_FORCE_UPDATE_INTERVAL_MS, DEFAULT_MIN_TOKENS_BEFORE_UPDATE);
    }
    
    public StreamingUpdateManager(UpdateCallback callback, 
                                  long minUpdateIntervalMs,
                                  long forceUpdateIntervalMs,
                                  int minTokensBeforeUpdate) {
        this.callback = callback;
        this.minUpdateIntervalMs = minUpdateIntervalMs;
        this.forceUpdateIntervalMs = forceUpdateIntervalMs;
        this.minTokensBeforeUpdate = minTokensBeforeUpdate;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.startTimeMs = System.currentTimeMillis();
    }
    
    public void addToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        
        synchronized (lock) {
            accumulatedTokens.append(token);
            tokensSinceLastUpdate++;
            totalTokensGenerated++;
            
            long now = System.currentTimeMillis();
            long timeSinceLastUpdate = now - lastUpdateTimeMs;
            
            boolean shouldUpdateNow = tokensSinceLastUpdate >= minTokensBeforeUpdate 
                    && timeSinceLastUpdate >= minUpdateIntervalMs;
            
            boolean shouldForceUpdate = timeSinceLastUpdate >= forceUpdateIntervalMs;
            
            if (shouldUpdateNow || shouldForceUpdate) {
                flushUpdatesLocked();
            } else if (!updateScheduled) {
                long delay = forceUpdateIntervalMs - timeSinceLastUpdate;
                if (delay > 0) {
                    mainHandler.postDelayed(flushRunnable, delay);
                    updateScheduled = true;
                } else {
                    flushUpdatesLocked();
                }
            }
        }
    }
    
    public void flush() {
        synchronized (lock) {
            flushUpdatesLocked();
        }
    }
    
    private void flushUpdatesLocked() {
        if (updateScheduled) {
            mainHandler.removeCallbacks(flushRunnable);
            updateScheduled = false;
        }
        
        if (accumulatedTokens.length() > 0 || tokensSinceLastUpdate > 0) {
            final String content = accumulatedTokens.toString();
            final int tokenCount = tokensSinceLastUpdate;
            final long elapsedMs = System.currentTimeMillis() - startTimeMs;
            final float tokensPerSecond = elapsedMs > 0 ? (totalTokensGenerated * 1000.0f) / elapsedMs : 0;
            final long timeSinceLastUpdate = lastUpdateTimeMs > 0 ? System.currentTimeMillis() - lastUpdateTimeMs : 0;
            final StreamingStats stats = new StreamingStats(totalTokensGenerated, tokenCount, elapsedMs, tokensPerSecond, timeSinceLastUpdate);
            
            accumulatedTokens.setLength(0);
            tokensSinceLastUpdate = 0;
            lastUpdateTimeMs = System.currentTimeMillis();
            
            if (callback != null) {
                mainHandler.post(() -> {
                    callback.onUpdate(content, tokenCount);
                    callback.onStatsUpdate(stats);
                });
            }
        }
    }
    
    private void flushUpdates() {
        synchronized (lock) {
            updateScheduled = false;
            flushUpdatesLocked();
        }
    }
    
    public void reset() {
        synchronized (lock) {
            if (updateScheduled) {
                mainHandler.removeCallbacks(flushRunnable);
                updateScheduled = false;
            }
            accumulatedTokens.setLength(0);
            tokensSinceLastUpdate = 0;
            totalTokensGenerated = 0;
            lastUpdateTimeMs = 0;
            startTimeMs = System.currentTimeMillis();
        }
    }
    
    public int getPendingTokenCount() {
        synchronized (lock) {
            return tokensSinceLastUpdate;
        }
    }
    
    public int getTotalTokensGenerated() {
        synchronized (lock) {
            return totalTokensGenerated;
        }
    }
    
    public long getTimeSinceLastUpdateMs() {
        synchronized (lock) {
            return lastUpdateTimeMs > 0 ? System.currentTimeMillis() - lastUpdateTimeMs : -1;
        }
    }
    
    public long getElapsedTimeMs() {
        return System.currentTimeMillis() - startTimeMs;
    }
    
    public float getTokensPerSecond() {
        long elapsed = getElapsedTimeMs();
        return elapsed > 0 ? (totalTokensGenerated * 1000.0f) / elapsed : 0;
    }
    
    public StreamingStats getCurrentStats() {
        synchronized (lock) {
            long elapsedMs = System.currentTimeMillis() - startTimeMs;
            float tokensPerSecond = elapsedMs > 0 ? (totalTokensGenerated * 1000.0f) / elapsedMs : 0;
            long timeSinceLastUpdate = lastUpdateTimeMs > 0 ? System.currentTimeMillis() - lastUpdateTimeMs : 0;
            return new StreamingStats(totalTokensGenerated, tokensSinceLastUpdate, elapsedMs, tokensPerSecond, timeSinceLastUpdate);
        }
    }
}
