package com.oilquiz.app.ai.chat.mode;

import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.chat.StreamingUpdateManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NormalModeHandler implements ModeHandler {
    private static final String TAG = "NormalMode";
    
    private final AIService aiService;
    private final Set<String> activeMessageIds;
    
    public NormalModeHandler(AIService aiService) {
        this.aiService = aiService;
        this.activeMessageIds = ConcurrentHashMap.newKeySet();
    }
    
    @Override
    public void handleMessage(ChatMessage userMessage, String messageId, ModeHandlerCallback callback) {
        if (userMessage == null || userMessage.content == null) {
            if (callback != null) {
                callback.onError(messageId, "Invalid message");
            }
            return;
        }
        
        activeMessageIds.add(messageId);
        
        ChatMessage initialAiMessage = createInitialAIMessage(messageId, userMessage);
        if (callback != null) {
            callback.onMessageCreated(messageId, initialAiMessage);
        }
        
        StreamingUpdateManager updateManager = new StreamingUpdateManager(
            new StreamingUpdateManager.UpdateCallback() {
                @Override
                public void onUpdate(String accumulatedContent, int totalTokensSinceLastUpdate) {
                    if (callback != null && activeMessageIds.contains(messageId)) {
                        callback.onToken(messageId, accumulatedContent);
                    }
                }
                
                @Override
                public void onStatsUpdate(StreamingUpdateManager.StreamingStats stats) {
                }
            }
        );
        
        try {
            Log.i(TAG, "Starting normal mode generation for message: " + messageId);
            aiService.generateStream(userMessage.content, 512, new AIService.GenerateStreamCallback() {
                private final StringBuilder fullResponse = new StringBuilder();
                private long startTime = System.currentTimeMillis();
                private int tokenCount = 0;
                
                @Override
                public void onToken(String token) {
                    if (!activeMessageIds.contains(messageId)) {
                        Log.i(TAG, "Message cancelled, stopping token delivery: " + messageId);
                        return;
                    }
                    
                    if (token != null) {
                        fullResponse.append(token);
                        tokenCount++;
                        updateManager.addToken(token);
                    }
                }
                
                @Override
                public void onSuccess(String fullText) {
                    updateManager.flush();
                    long elapsed = System.currentTimeMillis() - startTime;
                    float tps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
                    
                    StreamingUpdateManager.StreamingStats stats = 
                        new StreamingUpdateManager.StreamingStats(tokenCount, tokenCount, elapsed, tps, 0);
                    
                    if (callback != null && activeMessageIds.contains(messageId)) {
                        callback.onComplete(messageId, fullText != null ? fullText : fullResponse.toString(), stats);
                    }
                    activeMessageIds.remove(messageId);
                    Log.i(TAG, "Normal mode generation completed: " + messageId + ", tokens: " + tokenCount);
                }
                
                @Override
                public void onError(Exception error) {
                    updateManager.flush();
                    if (callback != null && activeMessageIds.contains(messageId)) {
                        callback.onError(messageId, error != null ? error.getMessage() : "Unknown error");
                    }
                    activeMessageIds.remove(messageId);
                    Log.e(TAG, "Normal mode generation error: " + messageId, error);
                }
            });
        } catch (Exception e) {
            if (callback != null) {
                callback.onError(messageId, e.getMessage());
            }
            activeMessageIds.remove(messageId);
            Log.e(TAG, "Failed to start normal mode generation", e);
        }
    }
    
    @Override
    public void cancel(String messageId) {
        if (messageId != null) {
            activeMessageIds.remove(messageId);
            Log.i(TAG, "Normal mode cancelled: " + messageId);
        }
    }
    
    @Override
    public String getModeName() {
        return "normal";
    }
    
    private ChatMessage createInitialAIMessage(String messageId, ChatMessage userMessage) {
        return new ChatMessage.Builder(ChatMessage.MessageType.AI)
            .id(messageId)
            .parentId(userMessage.id)
            .content("")
            .status(ChatMessage.MessageStatus.GENERATING)
            .build();
    }
}
