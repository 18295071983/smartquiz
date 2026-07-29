package com.oilquiz.app.ai.agent.software.thinking.model;

public enum ThinkingType {
    QUESTION,
    SUGGESTION,
    PERSPECTIVE,
    REFLECTION,
    SUMMARY,
    CLARIFICATION,
    ANSWER;

    public String getDisplayName() {
        switch (this) {
            case QUESTION: return "引导问题";
            case SUGGESTION: return "思考建议";
            case PERSPECTIVE: return "新视角";
            case REFLECTION: return "反思";
            case SUMMARY: return "总结";
            case CLARIFICATION: return "澄清";
            case ANSWER: return "回答";
            default: return "思考";
        }
    }
}
