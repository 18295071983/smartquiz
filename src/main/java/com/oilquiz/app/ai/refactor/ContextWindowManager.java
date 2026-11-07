package com.oilquiz.app.ai.refactor;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.jni.SystemProbe;
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
            // SYS-PROBE(2026-11-08)：分子用**真实 KV 占用**（llama_memory_seq_pos_max(seq 0)+1），
            // 而不是 s_helperContext 的 total_tokens_in_kv 记账——记账在裁剪/软清/异常路径上
            // 会与真实 KV 脱节（真机上出现过"记账 0、KV 仍占满"导致裁剪不触发）。
            long[] engine = SystemProbe.engine();
            if (engine[SystemProbe.ENGINE_VALID] != 0
                    && engine[SystemProbe.ENGINE_HAS_CTX] != 0) {
                return (int) engine[SystemProbe.ENGINE_KV_TOKENS];
            }
            if (aiService != null && aiService.isInitialized()) {
                return LlamaHelper.getTokenCount();
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error getting token count", e);
        }
        return 0;
    }
    
    public int getMaxContextTokens() {
        // SYS-PROBE(2026-11-08)：优先用**真实 n_ctx**（native 建出来的那个窗口），而不是
        // AIConfig 档位值——后者是构造函数里一次性读的，加载后窗口被内存动态调整过，
        // 它永远不会知道，于是手动 32768 会被它按 12288 提前裁剪。
        int real = LlamaHelper.getContextSize();
        if (real > 0) {
            return real;
        }
        int engineCtx = SystemProbe.engineContextSize();
        if (engineCtx > 0) {
            return engineCtx;
        }
        return maxContextTokens;
    }
    
    public float getContextUsagePercent() {
        int current = getCurrentTokenCount();
        int max = getMaxContextTokens();
        if (max <= 0) return 0;
        return (float) current / max;
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
        int available = getMaxContextTokens() - current;
        
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
        int max = getMaxContextTokens();
        float percent = getContextUsagePercent();
        
        return String.format("Context: %d/%d tokens (%.1f%%)", 
            current, max, percent * 100);
    }
}