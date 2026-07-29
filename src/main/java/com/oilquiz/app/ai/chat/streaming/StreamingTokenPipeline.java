package com.oilquiz.app.ai.chat.streaming;

import android.util.Log;

/**
 * 流式 Token 管道：接收原始 token，分离 thinking/content，调度批量 UI 更新。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class StreamingTokenPipeline {

    private static final String TAG = "StreamingTokenPipeline";

    public interface TokenListener {
        void onContentToken(String token);
        void onThinkingToken(String token);
        void onToolCall(String toolCallData);
        void onGenerationComplete(String fullContent);
    }

    private final TokenListener listener;
    private final StringBuilder contentBuffer = new StringBuilder();
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private boolean isInThinking = false;
    private boolean isInTag = false;
    private final StringBuilder tagBuffer = new StringBuilder();

    public StreamingTokenPipeline(TokenListener listener) {
        this.listener = listener;
    }

    /**
     * 处理单个 token
     */
    public void processToken(String token) {
        if (token == null || token.isEmpty()) return;

        // Check for tool call
        if (token.contains("[TOOL_CALL]")) {
            if (listener != null) listener.onToolCall(token);
            return;
        }

        // Check for thinking tags
        if (token.contains("<think>")) {
            isInThinking = true;
            // Handle inline <think>...content...</think>
            int thinkStart = token.indexOf("<think>");
            String before = token.substring(0, thinkStart);
            if (!before.isEmpty()) {
                contentBuffer.append(before);
                if (listener != null) listener.onContentToken(before);
            }
            String afterThink = token.substring(thinkStart + 8);
            if (afterThink.contains("</think>")) {
                int thinkEnd = afterThink.indexOf("</think>");
                thinkingBuffer.append(afterThink.substring(0, thinkEnd));
                if (listener != null) listener.onThinkingToken(afterThink.substring(0, thinkEnd));
                isInThinking = false;
                String remaining = afterThink.substring(thinkEnd + 9);
                if (!remaining.isEmpty()) {
                    contentBuffer.append(remaining);
                    if (listener != null) listener.onContentToken(remaining);
                }
            } else {
                thinkingBuffer.append(afterThink);
                if (listener != null) listener.onThinkingToken(afterThink);
            }
            return;
        }

        if (token.contains("</think>")) {
            int thinkEnd = token.indexOf("</think>");
            String thinkContent = token.substring(0, thinkEnd);
            if (!thinkContent.isEmpty()) {
                thinkingBuffer.append(thinkContent);
                if (listener != null) listener.onThinkingToken(thinkContent);
            }
            isInThinking = false;
            String remaining = token.substring(thinkEnd + 9);
            if (!remaining.isEmpty()) {
                contentBuffer.append(remaining);
                if (listener != null) listener.onContentToken(remaining);
            }
            return;
        }

        if (isInThinking) {
            thinkingBuffer.append(token);
            if (listener != null) listener.onThinkingToken(token);
        } else {
            contentBuffer.append(token);
            if (listener != null) listener.onContentToken(token);
        }
    }

    /**
     * 通知生成完成
     */
    public void complete() {
        if (listener != null) {
            listener.onGenerationComplete(contentBuffer.toString());
        }
    }

    /**
     * 获取当前内容
     */
    public String getContent() {
        return contentBuffer.toString();
    }

    /**
     * 获取当前思考内容
     */
    public String getThinkingContent() {
        return thinkingBuffer.toString();
    }

    /**
     * 重置管道状态
     */
    public void reset() {
        contentBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        isInThinking = false;
        isInTag = false;
        tagBuffer.setLength(0);
    }

    public boolean isInThinking() {
        return isInThinking;
    }
}
