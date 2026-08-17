package com.oilquiz.app.ai.performance;

/**
 * OptimizationSuggestion - 优化建议数据模型。
 */
public class OptimizationSuggestion {

    public enum Priority {
        HIGH,
        MEDIUM,
        LOW
    }

    public final String id;
    public final String title;
    public final String description;
    public final Priority priority;
    public final String estimatedImprovement;

    public OptimizationSuggestion(String id, String title, String description, Priority priority, String estimatedImprovement) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.estimatedImprovement = estimatedImprovement;
    }
}
