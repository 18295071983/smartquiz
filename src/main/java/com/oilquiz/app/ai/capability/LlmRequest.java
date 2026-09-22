package com.oilquiz.app.ai.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM 请求 —— 能力缝（Service Definition）的请求模型。
 *
 * <p>providerId 为空时由 Consumer（{@link LlmGateway}）自动选择第一个支持的 Provider。
 */
public final class LlmRequest {

    /** 目标服务商 ID；null/空表示自动选择 */
    public final String providerId;
    /** 模型标识（如 deepseek-chat / local-llama-3），Provider 可据此门控 */
    public final String model;
    /** 消息序列 */
    public final List<LlmMessage> messages;
    /** 最大输出 token 数 */
    public final int maxTokens;
    /** 是否开启思考链 */
    public final boolean enableThinking;
    /** 会话 ID（用于事件日志关联，可空） */
    public final String sessionId;

    private LlmRequest(String providerId, String model, List<LlmMessage> messages,
                       int maxTokens, boolean enableThinking, String sessionId) {
        this.providerId = providerId;
        this.model = model;
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.maxTokens = maxTokens;
        this.enableThinking = enableThinking;
        this.sessionId = sessionId;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 便捷：最后一条用户消息文本（取不到返回空串） */
    public String lastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (LlmMessage.ROLE_USER.equals(messages.get(i).role)) {
                return messages.get(i).content;
            }
        }
        return "";
    }

    public static final class Builder {
        private String providerId;
        private String model;
        private final List<LlmMessage> messages = new ArrayList<>();
        private int maxTokens = 2048;
        private boolean enableThinking = false;
        private String sessionId;

        public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder addMessage(LlmMessage message) {
            this.messages.add(message);
            return this;
        }

        public Builder messages(List<LlmMessage> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder enableThinking(boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(providerId, model, messages, maxTokens, enableThinking, sessionId);
        }
    }
}
