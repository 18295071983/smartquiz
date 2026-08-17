package com.oilquiz.app.ai.refactor;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

public class ContextWindowManager {
    private static final String TAG = "ContextWindowManager";
    
    private static volatile ContextWindowManager instance;
    
    private final AIService aiService;
    private final ChatHistoryManager chatHistoryManager;
    private final Context context;
    
    private int maxContextTokens = 16384;
    private float trimThreshold = 0.75f;
    private int minMessagesToKeep = 3;
    
    private ContextWindowManager(Context context) {
        this.context = context.getApplicationContext();
        this.aiService = AIService.getInstance(this.context);
        this.chatHistoryManager = new ChatHistoryManager(this.context);
        loadConfig();
    }
    
    public static synchronized ContextWindowManager getInstance(Context context) {
        if (instance == null) {
            instance = new ContextWindowManager(context);
        }
        return instance;
    }
    
    public static ContextWindowManager getInstance() {
        return instance;
    }
    
    private void loadConfig() {
        try {
            AIConfig config = new AIConfig(context);
            int ctxSize = config.getContextSize();
            if (ctxSize > 0) {
                maxContextTokens = ctxSize;
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to load config, using defaults");
        }
    }
    
    public int getCurrentTokenCount() {
        try {
            if (aiService != null && aiService.isInitialized()) {
                return LlamaHelper.getTokenCount();
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error getting token count", e);
        }
        return 0;
    }
    
    public int getMaxContextTokens() {
        return maxContextTokens;
    }
    
    public float getContextUsagePercent() {
        int current = getCurrentTokenCount();
        if (maxContextTokens <= 0) return 0;
        return (float) current / maxContextTokens;
    }
    
    public boolean shouldTrim() {
        return getContextUsagePercent() >= trimThreshold;
    }
    
    public boolean trimContext(int messagesToKeep) {
        return trimContext(messagesToKeep, false);
    }
    
    public boolean trimContext(int messagesToKeep, boolean generateSummary) {
        try {
            List<ChatMessage> history = chatHistoryManager.loadAIChatHistory();
            if (history == null || history.size() <= messagesToKeep) {
                AILogger.i(TAG, "No need to trim: only " + (history != null ? history.size() : 0) + " messages");
                return false;
            }
            
            int messagesToRemove = history.size() - messagesToKeep;
            AILogger.i(TAG, "Trimming " + messagesToRemove + " messages, keeping " + messagesToKeep);
            
            List<ChatMessage> newHistory = new ArrayList<>();
            if (messagesToKeep > 0) {
                newHistory.addAll(history.subList(history.size() - messagesToKeep, history.size()));
            }
            
            chatHistoryManager.saveAIChatHistory(newHistory);
            
            if (aiService != null) {
                aiService.chatClear();
                AILogger.i(TAG, "Chat context cleared, will be recreated on next chatSend");
            }
            
            AILogger.i(TAG, "Context trimmed successfully. New token count: " + getCurrentTokenCount());
            return true;
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to trim context", e);
            return false;
        }
    }
    
    public boolean autoTrim() {
        if (!shouldTrim()) {
            return false;
        }
        return trimContext(minMessagesToKeep);
    }
    
    public void ensureSpaceForResponse(int expectedResponseTokens) {
        int current = getCurrentTokenCount();
        int available = maxContextTokens - current;
        
        if (available < expectedResponseTokens) {
            int needed = expectedResponseTokens - available;
            AILogger.i(TAG, "Need " + needed + " more tokens, triggering auto-trim");
            autoTrim();
        }
    }
    
    public void setMaxContextTokens(int maxTokens) {
        this.maxContextTokens = maxTokens;
        AILogger.i(TAG, "Max context tokens set to: " + maxTokens);
    }
    
    public void setTrimThreshold(float threshold) {
        this.trimThreshold = Math.max(0.5f, Math.min(0.95f, threshold));
        AILogger.i(TAG, "Trim threshold set to: " + (int)(trimThreshold * 100) + "%");
    }
    
    public void setMinMessagesToKeep(int minMessages) {
        this.minMessagesToKeep = Math.max(1, minMessages);
        AILogger.i(TAG, "Min messages to keep set to: " + minMessages);
    }
    
    public String getStatus() {
        int current = getCurrentTokenCount();
        int max = maxContextTokens;
        float percent = getContextUsagePercent();
        
        return String.format("Context: %d/%d tokens (%.1f%%)", 
            current, max, percent * 100);
    }
}