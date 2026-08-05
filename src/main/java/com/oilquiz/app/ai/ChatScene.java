package com.oilquiz.app.ai;

/**
 * 场景常量类 —— 用于对话历史隔离。
 * 不同场景（AI聊天 vs 错题解析 vs 学习辅导 vs 作文批改）的对话历史互不干扰，
 * 从DB层面通过 scene 字段隔离，避免错题解析页出现AI闲聊历史。
 */
public final class ChatScene {
    public static final String AI_CHAT = "ai_chat";
    public static final String QUIZ_EXPLAIN = "quiz_explain";
    public static final String LEARNING_ASSIST = "learning_assist";
    public static final String ESSAY_CORRECT = "essay_correct";
    public static final String OTHERS = "others";

    private ChatScene() {}

    /**
     * 校验 scene 值合法性，非法值回退到 AI_CHAT。
     */
    public static String normalize(String scene) {
        if (scene == null || scene.isEmpty()) return AI_CHAT;
        switch (scene) {
            case AI_CHAT:
            case QUIZ_EXPLAIN:
            case LEARNING_ASSIST:
            case ESSAY_CORRECT:
            case OTHERS:
                return scene;
            default:
                return AI_CHAT;
        }
    }
}
