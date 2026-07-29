package com.oilquiz.app.ai.agent.software.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Task - 任务定义
 */
public class Task {
    /** 默认单工具超时（毫秒） */
    public static final int DEFAULT_TIMEOUT_MS = 30_000;
    /** 默认重试次数 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    private final String id;
    private String description;
    private String intentType;
    private String entity;
    private String toolName;
    private Map<String, Object> parameters;
    private boolean needsTool;
    private boolean isCritical;
    /** 依赖的任务 id 列表（这些任务完成后本任务才能执行） */
    private List<String> dependsOn = new ArrayList<>();
    /** 单工具执行超时（毫秒），0 表示使用默认值 */
    private int timeoutMs = 0;
    /** 最大重试次数（含首次执行），0 表示使用默认值 */
    private int maxRetries = 0;

    public Task(String id) {
        this.id = id;
        this.parameters = new HashMap<>();
    }

    // Getters and Setters
    public String getId() { return id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIntentType() { return intentType; }
    public void setIntentType(String intentType) { this.intentType = intentType; }

    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public Map<String, Object> getParameters() { return parameters; }
    public void addParameter(String key, Object value) { parameters.put(key, value); }

    public boolean needsTool() { return needsTool; }
    public void setNeedsTool(boolean needsTool) { this.needsTool = needsTool; }

    public boolean isCritical() { return isCritical; }
    public void setCritical(boolean critical) { isCritical = critical; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>();
    }
    public void addDependency(String taskId) {
        if (taskId != null && !taskId.isEmpty() && !dependsOn.contains(taskId)) {
            dependsOn.add(taskId);
        }
    }

    public int getTimeoutMs() { return timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxRetries() { return maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    @Override
    public String toString() {
        return "Task{id='" + id + "', description='" + description + "', tool='" + toolName
                + "', dependsOn=" + dependsOn + "}";
    }
}
