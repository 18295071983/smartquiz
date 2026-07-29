package com.oilquiz.app.ai.agent.software.thinking.model;

public enum ThinkingStyle {
    SOCRATIC,
    MULTI_PERSPECTIVE,
    REFLECTIVE,
    CREATIVE,
    STRUCTURED;

    public String getDisplayName() {
        switch (this) {
            case SOCRATIC: return "苏格拉底提问";
            case MULTI_PERSPECTIVE: return "多角度分析";
            case REFLECTIVE: return "反思式";
            case CREATIVE: return "创造性";
            case STRUCTURED: return "结构化";
            default: return "思考";
        }
    }
}
