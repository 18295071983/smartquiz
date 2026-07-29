package com.oilquiz.app.ai.agent.software.model;

/**
 * TaskResult - 任务执行结果
 */
public class TaskResult {
    private final String result;
    private final boolean success;
    private final String error;
    
    private TaskResult(String result, boolean success, String error) {
        this.result = result;
        this.success = success;
        this.error = error;
    }
    
    public static TaskResult success(String result) {
        return new TaskResult(result, true, null);
    }
    
    public static TaskResult error(String error) {
        return new TaskResult(null, false, error);
    }
    
    public String getResult() { return result; }
    public boolean isSuccess() { return success; }
    public boolean isError() { return !success; }
    public String getError() { return error; }
    
    @Override
    public String toString() {
        if (success) {
            return "TaskResult{success, result='" + result + "'}";
        } else {
            return "TaskResult{error='" + error + "'}";
        }
    }
}
