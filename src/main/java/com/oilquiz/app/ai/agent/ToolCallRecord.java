package com.oilquiz.app.ai.agent;

import java.util.Map;

public class ToolCallRecord {
    private String toolName;
    private Map<String, Object> params;
    private String result;
    private long timestamp;
    private boolean success;
    private String errorMessage;

    public ToolCallRecord() {
        this.timestamp = System.currentTimeMillis();
    }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}