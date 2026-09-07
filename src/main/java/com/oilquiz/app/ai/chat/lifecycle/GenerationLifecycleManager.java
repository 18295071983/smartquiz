package com.oilquiz.app.ai.chat.lifecycle;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.StreamingUpdateManager;
import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;

import java.util.ArrayList;
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

    /** 模板思考标签：来自 chat template（LlamaHelper.getThinkingTags()），不硬编码 */
    private ThinkingTagConfig tagConfig = ThinkingTagConfig.empty();
    /** 跨 token 标签边界缓冲：标签可能被 tokenizer 拆开，凑齐后再判定，避免漏检 */
    private final StringBuilder tagLookahead = new StringBuilder();

    /** 注入模板思考标签（不硬编码）。调用方从 LlamaHelper.getThinkingTags() 获取后注入。 */
    public void setThinkingTags(ThinkingTagConfig config) {
        this.tagConfig = config != null ? config : ThinkingTagConfig.empty();
    }

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
        tagLookahead.setLength(0);
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

        // 跨 token 标签边界缓冲，避免标签被 tokenizer 拆开漏检
        tagLookahead.append(token);
        String buf = tagLookahead.toString();
        if (possibleTagPrefix(buf) != null) {
            return;   // 标签尚未到齐，保留缓冲等待下一个 token
        }
        tagLookahead.setLength(0);

        // 思考标签来自 chat template（tagConfig），不硬编码 <think>/</think>。
        // 修复旧实现缺陷：标签所在 token 里标签之后的正文不再被整段丢弃。
        if (tagConfig.isAvailable()) {
            String start = tagConfig.getStartTag();
            if (buf.contains(start)) {
                isInThinking = true;
                int s = buf.indexOf(start);
                String rest = buf.substring(s + start.length());
                String end = firstEndTagIn(rest);
                if (end != null) {
                    int e = rest.indexOf(end);
                    currentThinkingContent.append(rest.substring(0, e));
                    isInThinking = false;
                    String after = rest.substring(e + end.length());
                    if (!after.isEmpty()) currentStreamingContent.append(after);
                } else {
                    currentThinkingContent.append(rest);
                }
                flushToUI();
                return;
            }
            String end = firstEndTagIn(buf);
            if (end != null) {
                int e = buf.indexOf(end);
                currentThinkingContent.append(buf.substring(0, e));
                isInThinking = false;
                String after = buf.substring(e + end.length());
                if (!after.isEmpty()) currentStreamingContent.append(after);
                flushToUI();
                return;
            }
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

    private String firstEndTagIn(String text) {
        if (!tagConfig.isAvailable()) return null;
        String first = null;
        int firstPos = Integer.MAX_VALUE;
        for (String tag : tagConfig.getEndTags()) {
            if (tag.isEmpty()) continue;
            int p = text.indexOf(tag);
            if (p >= 0 && p < firstPos) {
                firstPos = p;
                first = tag;
            }
        }
        return first;
    }

    /**
     * 若 text 尾部是某个思考标签（开始/结束）的前缀，返回该后缀，表示标签尚未到齐、需等待
     * 后续 token 凑齐；否则返回 null。用于解决标签跨 token 漏检。
     */
    private String possibleTagPrefix(String text) {
        if (!tagConfig.isAvailable()) return null;
        List<String> tags = new ArrayList<>();
        tags.add(tagConfig.getStartTag());
        tags.addAll(tagConfig.getEndTags());
        int n = text.length();
        int maxSuffix = 0;
        for (String tag : tags) {
            if (!tag.isEmpty()) maxSuffix = Math.max(maxSuffix, tag.length() - 1);
        }
        for (int len = Math.min(maxSuffix, n); len >= 1; len--) {
            String suffix = text.substring(n - len);
            for (String tag : tags) {
                if (!tag.isEmpty() && tag.startsWith(suffix)) {
                    return suffix;
                }
            }
        }
        return null;
    }

    /**
     * 专门处理在线模型独立的 reasoning_content 流（通过 onThinkingToken 回调）。
     * 与 handleToken 中解析 <think> 标签的逻辑独立，两种场景共存。
     */
    public void handleThinkingToken(String token) {
        if (!isGenerating || token == null || token.isEmpty()) return;

        totalTokensGenerated++;
        currentThinkingContent.append(token);
        tokenCountSinceLastUpdate++;

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
        // 思考链有内容时，也要通过回调更新 Adapter
        String thinking = currentThinkingContent.toString();
        if (!thinking.isEmpty()) {
            activity.runOnUiThread(() ->
                callback.onUpdateMessageThinking(currentStreamingMessageIndex, thinking)
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
