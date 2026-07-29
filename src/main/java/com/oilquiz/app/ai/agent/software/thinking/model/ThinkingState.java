package com.oilquiz.app.ai.agent.software.thinking.model;

public enum ThinkingState {
    EXPLORING,
    ANALYZING,
    REFLECTING,
    SYNTHESIZING,
    DECIDING;

    public String getDisplayName() {
        switch (this) {
            case EXPLORING: return "探索";
            case ANALYZING: return "分析";
            case REFLECTING: return "反思";
            case SYNTHESIZING: return "综合";
            case DECIDING: return "决策";
            default: return "思考";
        }
    }
}
