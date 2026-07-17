package com.oilquiz.app.ai.agent.software.model;

/**
 * ComplexityLevel - 任务复杂度级别
 */
public enum ComplexityLevel {
    SIMPLE("简单", "单步任务，可直接回答"),
    MEDIUM("中等", "需要1-2个工具调用"),
    COMPLEX("复杂", "需要多个工具调用或复杂推理");
    
    public final String displayName;
    public final String description;
    
    ComplexityLevel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}
