package com.oilquiz.app.ai.chat.mode;

import com.oilquiz.app.ai.chat.ChatMessage;

public interface ModeHandler {
    void handleMessage(ChatMessage userMessage, String messageId, ModeHandlerCallback callback);
    void cancel(String messageId);
    String getModeName();
    
    interface ModeHandlerCallback {
        void onMessageCreated(String messageId, ChatMessage initialMessage);
        void onToken(String messageId, String token);
        void onThinkingUpdate(String messageId, Object thinkingData);
        void onComplete(String messageId, String content, Object stats);
        void onError(String messageId, String error);
        void onInferenceProgress(String messageId, int tokenCount, float tokensPerSecond);
    }
}
