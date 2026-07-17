package com.oilquiz.app.ai.agent.software.model;

import java.util.HashMap;
import java.util.Map;

/**
 * ExecutionResult - 执行结果
 */
public class ExecutionResult {
    private final Map<String, TaskResult> taskResults;
    private String error;
    
    public ExecutionResult() {
        this.taskResults = new HashMap<>();
    }
    
    public void addTaskResult(String taskId, TaskResult result) {
        taskResults.put(taskId, result);
    }
    
    public TaskResult getTaskResult(String taskId) {
        return taskResults.get(taskId);
    }
    
    public Map<String, TaskResult> getTaskResults() {
        return taskResults;
    }
    
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    
    public boolean hasError() { return error != null && !error.isEmpty(); }
    
    /**
     * 获取所有成功的结果
     */
    public Map<String, TaskResult> getSuccessfulResults() {
        Map<String, TaskResult> successResults = new HashMap<>();
        for (Map.Entry<String, TaskResult> entry : taskResults.entrySet()) {
            if (entry.getValue().isSuccess()) {
                successResults.put(entry.getKey(), entry.getValue());
            }
        }
        return successResults;
    }
}
