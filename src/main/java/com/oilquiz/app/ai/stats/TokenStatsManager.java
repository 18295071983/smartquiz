package com.oilquiz.app.ai.stats;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token 统计管理器
 * - 单例模式
 * - 线程安全
 * - 支持多观察者
 * - 区分会话统计和请求统计
 */
public class TokenStatsManager {
    
    private static volatile TokenStatsManager INSTANCE;
    
    private final AtomicInteger sessionPromptTokens = new AtomicInteger(0);
    private final AtomicInteger sessionCompletionTokens = new AtomicInteger(0);
    private final AtomicInteger requestPromptTokens = new AtomicInteger(0);
    private final AtomicInteger requestCompletionTokens = new AtomicInteger(0);
    
    private final CopyOnWriteArraySet<TokenStatsCallback> callbacks = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private TokenStatsManager() {}
    
    public static TokenStatsManager getInstance() {
        if (INSTANCE == null) {
            synchronized (TokenStatsManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TokenStatsManager();
                }
            }
        }
        return INSTANCE;
    }
    
    public void registerCallback(TokenStatsCallback callback) {
        if (callback != null) callbacks.add(callback);
    }
    
    public void unregisterCallback(TokenStatsCallback callback) {
        callbacks.remove(callback);
    }
    
    /**
     * 更新当前请求的统计（流式过程中使用，仅更新 request 统计，不累加 session）
     */
    public void updateRequestStreamingStats(int completionTokens) {
        requestCompletionTokens.set(completionTokens);
        notifyStatsUpdated();
    }

    /**
     * 请求完成时更新最终统计（累加到 session）
     */
    public void updateRequestStats(int promptTokens, int completionTokens) {
        requestPromptTokens.set(promptTokens);
        requestCompletionTokens.set(completionTokens);
        sessionPromptTokens.addAndGet(promptTokens);
        sessionCompletionTokens.addAndGet(completionTokens);
        notifyStatsUpdated();
    }
    
    public void resetSession() {
        sessionPromptTokens.set(0);
        sessionCompletionTokens.set(0);
        requestPromptTokens.set(0);
        requestCompletionTokens.set(0);
        notifyStatsUpdated();
    }
    
    public void destroy() {
        callbacks.clear();
        resetSession();
    }
    
    // Getter
    public int getSessionPromptTokens() { return sessionPromptTokens.get(); }
    public int getSessionCompletionTokens() { return sessionCompletionTokens.get(); }
    public int getSessionTotalTokens() { return sessionPromptTokens.get() + sessionCompletionTokens.get(); }
    public int getRequestPromptTokens() { return requestPromptTokens.get(); }
    public int getRequestCompletionTokens() { return requestCompletionTokens.get(); }
    public int getRequestTotalTokens() { return requestPromptTokens.get() + requestCompletionTokens.get(); }
    
    public TokenStats getCurrentSnapshot() {
        return new TokenStats(
            sessionPromptTokens.get(), sessionCompletionTokens.get(),
            requestPromptTokens.get(), requestCompletionTokens.get()
        );
    }
    
    private void notifyStatsUpdated() {
        if (callbacks.isEmpty()) return;
        mainHandler.post(() -> {
            TokenStats snapshot = getCurrentSnapshot();
            for (TokenStatsCallback callback : callbacks) {
                try {
                    callback.onTokenStatsUpdate(snapshot);
                } catch (Exception e) {
                    android.util.Log.e("TokenStats", "Callback error: " + e.getMessage());
                }
            }
        });
    }
    
    // ========== 内部类 ==========
    
    public static class TokenStats {
        public final int sessionPromptTokens;
        public final int sessionCompletionTokens;
        public final int sessionTotalTokens;
        public final int requestPromptTokens;
        public final int requestCompletionTokens;
        public final int requestTotalTokens;
        
        public TokenStats(int sp, int sc, int rp, int rc) {
            this.sessionPromptTokens = sp;
            this.sessionCompletionTokens = sc;
            this.sessionTotalTokens = sp + sc;
            this.requestPromptTokens = rp;
            this.requestCompletionTokens = rc;
            this.requestTotalTokens = rp + rc;
        }
    }
    
    public interface TokenStatsCallback {
        void onTokenStatsUpdate(TokenStats stats);
    }
}