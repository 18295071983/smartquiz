package com.oilquiz.app.ai.jni;

/**
 * ChatRequest - AI聊天请求数据类
 *
 * 用于批量传递聊天生成所需的所有参数，避免多次JNI调用和字符串转码
 * 解决中文编码问题：通过byte[]传递UTF-8编码的内容
 *
 * @author AI Team
 * @since 2024
 */
public class ChatRequest {

    // 完整提示词（已拼接好system prompt、历史记录、用户输入）
    private byte[] fullPromptUtf8;

    // 对话上下文ID（用于多轮对话管理）
    private String conversationId;

    // 生成参数
    private int maxTokens = 512;
    private float temperature = 0.7f;
    private float topP = 0.9f;
    private int topK = 40;
    private boolean enableThinking = false;

    // 构造函数
    public ChatRequest() {
    }

    public ChatRequest(byte[] fullPromptUtf8) {
        this.fullPromptUtf8 = fullPromptUtf8;
    }

    // Builder模式
    public static Builder builder() {
        return new Builder();
    }

    // Getters and Setters
    public byte[] getFullPromptUtf8() {
        return fullPromptUtf8;
    }

    public void setFullPromptUtf8(byte[] fullPromptUtf8) {
        this.fullPromptUtf8 = fullPromptUtf8;
    }

    /**
     * 设置字符串提示词，自动转为UTF-8 byte[]
     */
    public void setFullPrompt(String prompt) {
        if (prompt != null) {
            this.fullPromptUtf8 = prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public float getTemperature() {
        return temperature;
    }

    public void setTemperature(float temperature) {
        this.temperature = temperature;
    }

    public float getTopP() {
        return topP;
    }

    public void setTopP(float topP) {
        this.topP = topP;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public boolean isEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    /**
     * Builder类
     */
    public static class Builder {
        private ChatRequest request = new ChatRequest();

        public Builder fullPromptUtf8(byte[] fullPromptUtf8) {
            request.fullPromptUtf8 = fullPromptUtf8;
            return this;
        }

        public Builder fullPrompt(String prompt) {
            request.setFullPrompt(prompt);
            return this;
        }

        public Builder conversationId(String conversationId) {
            request.conversationId = conversationId;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            request.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(float temperature) {
            request.temperature = temperature;
            return this;
        }

        public Builder topP(float topP) {
            request.topP = topP;
            return this;
        }

        public Builder topK(int topK) {
            request.topK = topK;
            return this;
        }

        public Builder enableThinking(boolean enableThinking) {
            request.enableThinking = enableThinking;
            return this;
        }

        public ChatRequest build() {
            return request;
        }
    }
}
