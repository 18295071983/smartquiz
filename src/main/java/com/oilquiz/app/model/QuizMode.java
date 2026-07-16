package com.oilquiz.app.model;

/**
 * 测验模式枚举
 */
public enum QuizMode {
    CHAPTER("chapter", "章节练习", "按章节分类练习，适合系统学习"),
    RANDOM("random", "随机练习", "随机抽取题目，适合考前冲刺"),
    SPECIAL("special", "专项练习", "针对错题或收藏题练习"),
    EXAM("exam", "模拟考试", "计时考试，模拟真实考试环境"),
    REVIEW("review", "复习模式", "回顾已做过的题目"),
    WEAK("weak", "薄弱点练习", "针对错题和易错题练习");

    private final String code;
    private final String displayName;
    private final String description;

    QuizMode(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static QuizMode fromCode(String code) {
        for (QuizMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return CHAPTER; // 默认章节练习
    }
}
