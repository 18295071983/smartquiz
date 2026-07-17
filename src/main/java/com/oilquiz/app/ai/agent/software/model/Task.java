package com.oilquiz.app.ai.agent.software.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Task - 任务定义
 */
public class Task {
    private final String id;
    private String description;
    private String intentType;
    private String entity;
    private String toolName;
    private Map<String, Object> parameters;
    private boolean needsTool;
    private boolean isCritical;
    
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
    
    @Override
    public String toString() {
        return "Task{id='" + id + "', description='" + description + "', tool='" + toolName + "'}";
    }
}
