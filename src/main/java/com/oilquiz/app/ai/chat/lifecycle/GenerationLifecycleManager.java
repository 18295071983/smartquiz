package com.oilquiz.app.ai.chat.lifecycle;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.StreamingUpdateManager;

import java.util.List;

/**
 * 管理 AI 生成的生命周期：开始、流式更新、完成、错误处理。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class GenerationLifecycleManager {

    private static final String TAG = "GenLifecycleManager";

    public interface Callback {
        void onShowThinkingIndicator();
        void onHideThinkingIndicator();
        void onShowStopButton();
        void onHideStopButton();
        void onUpdateMessageContent(int index, String content);
        void onUpdateMessageThinking(int index, String thinkingContent);
        void onAddAIMessage(ChatMessage message);
        void onAddSystemMessage(String message);
        void onScrollToBottom();
        void onSaveHistoryAsync();
        void onShowToast(String message);
    }

    private final Activity activity;
    private final Handler uiHandler;
    private final Callback callback;

    private volatile boolean isGenerating = false;
    private volatile StringBuilder currentStreamingContent = new StringBuilder();
    private volatile StringBuilder currentThinkingContent = new StringBuilder();
    private volatile boolean isInThinking = false;
    private volatile int currentStreamingMessageIndex = -1;
    private volatile String currentStreamingMessageId = null;
    private int tokenCountSinceLastUpdate = 0;
    private long lastUpdateTime = 0;
    private long totalTokensGenerated = 0;
    private long generationStartTime = 0;
    private StreamingUpdateManager streamingUpdateManager;

    private static final int BATCH_TOKEN_COUNT = 20;
    private static final long BATCH_INTERVAL_MS = 50;

    public GenerationLifecycleManager(Activity activity, Handler uiHandler, Callback callback) {
        this.activity = activity;
        this.uiHandler = uiHandler;
        this.callback = callback;
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    public void beginGeneration(int messageIndex, String messageId) {
        isGenerating = true;
        currentStreamingContent = new StringBuilder();
        currentThinkingContent = new StringBuilder();
        isInThinking = false;
        currentStreamingMessageIndex = messageIndex;
        currentStreamingMessageId = messageId;
        tokenCountSinceLastUpdate = 0;
        lastUpdateTime = System.currentTimeMillis();
        totalTokensGenerated = 0;
        generationStartTime = System.currentTimeMillis();

        streamingUpdateManager = new StreamingUpdateManager(new StreamingUpdateManager.UpdateCallback() {
            @Override
            public void onUpdate(String accumulatedContent, int totalTokensSinceLastUpdate) {
                activity.runOnUiThread(() -> callback.onUpdateMessageContent(currentStreamingMessageIndex, accumulatedContent));
            }
        });

        activity.runOnUiThread(() -> {
            callback.onShowThinkingIndicator();
            callback.onShowStopButton();
        });
    }

    public void handleToken(String token) {
        if (!isGenerating || token == null) return;

        totalTokensGenerated++;
        tokenCountSinceLastUpdate++;

        // Check for thinking tags
        if (token.contains("<think>")) {
            isInThinking = true;
            return;
        }
        if (token.contains("</think>")) {
            isInThinking = false;
            return;
        }

        if (isInThinking) {
            currentThinkingContent.append(token);
        } else {
            currentStreamingContent.append(token);
        }

        // Batch UI updates
        long now = System.currentTimeMillis();
        if (tokenCountSinceLastUpdate >= BATCH_TOKEN_COUNT || (now - lastUpdateTime) >= BATCH_INTERVAL_MS) {
            flushToUI();
            tokenCountSinceLastUpdate = 0;
            lastUpdateTime = now;
        }
    }

    public void completeGeneration(String fullContent, List<ChatMessage> chatHistory) {
        isGenerating = false;

        // Final flush
        if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            msg.content = fullContent != null ? fullContent : currentStreamingContent.toString();
            if (currentThinkingContent.length() > 0) {
                msg.thinkingContent = currentThinkingContent.toString();
            }
            msg.status = ChatMessage.MessageStatus.COMPLETED;
            msg.generationTimeMs = System.currentTimeMillis() - generationStartTime;
        }

        activity.runOnUiThread(() -> {
            callback.onHideThinkingIndicator();
            callback.onHideStopButton();
            callback.onScrollToBottom();
            callback.onSaveHistoryAsync();
        });

        resetState();
    }

    public void handleError(String error, List<ChatMessage> chatHistory) {
        isGenerating = false;

        // Save partial content
        if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            if (currentStreamingContent.length() > 0) {
                msg.content = currentStreamingContent.toString();
                msg.status = ChatMessage.MessageStatus.COMPLETED;
            } else {
                chatHistory.remove(currentStreamingMessageIndex);
            }
        }

        activity.runOnUiThread(() -> {
            callback.onHideThinkingIndicator();
            callback.onHideStopButton();
            if (currentStreamingContent.length() == 0) {
                callback.onAddSystemMessage("生成失败: " + error);
            }
            callback.onSaveHistoryAsync();
        });

        resetState();
    }

    public void cancelGeneration(List<ChatMessage> chatHistory) {
        isGenerating = false;

        if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatHistory.size()) {
            ChatMessage msg = chatHistory.get(currentStreamingMessageIndex);
            if (currentStreamingContent.length() > 0) {
                msg.content = currentStreamingContent.toString() + "\n\n[已停止]";
                msg.status = ChatMessage.MessageStatus.COMPLETED;
            } else {
                chatHistory.remove(currentStreamingMessageIndex);
            }
        }

        activity.runOnUiThread(() -> {
            callback.onHideThinkingIndicator();
            callback.onHideStopButton();
            callback.onSaveHistoryAsync();
        });

        resetState();
    }

    public int getCurrentStreamingMessageIndex() {
        return currentStreamingMessageIndex;
    }

    public String getCurrentStreamingMessageId() {
        return currentStreamingMessageId;
    }

    private void flushToUI() {
        if (currentStreamingMessageIndex < 0) return;
        String content = currentStreamingContent.toString();
        if (!content.isEmpty()) {
            activity.runOnUiThread(() ->
                callback.onUpdateMessageContent(currentStreamingMessageIndex, content)
            );
        }
    }

    private void resetState() {
        currentStreamingContent = new StringBuilder();
        currentThinkingContent = new StringBuilder();
        isInThinking = false;
        currentStreamingMessageIndex = -1;
        currentStreamingMessageId = null;
        tokenCountSinceLastUpdate = 0;
        streamingUpdateManager = null;
    }
}
