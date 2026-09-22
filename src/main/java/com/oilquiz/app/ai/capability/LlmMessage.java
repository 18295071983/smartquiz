package com.oilquiz.app.ai.capability;

/**
 * LLM 消息 —— 能力缝模型层的不可变消息。
 */
public final class LlmMessage {

    /** 角色常量 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    public final String role;
    public final String content;

    private LlmMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(ROLE_USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(ROLE_ASSISTANT, content);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(ROLE_SYSTEM, content);
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
