package com.oilquiz.app.ai.agent.online;

/**
 * 在线模型工具执行结果类型。
 * 独立于 {@link com.oilquiz.app.ai.service.AgentService.ToolResult}，
 * 专为在线模型 Agent 设计。
 */
public class OnlineToolResult {

    public final String toolCallId;
    public final String toolName;
    public final String result;
    public final boolean success;
    public final String error;
    public final long executionTimeMs;

    private OnlineToolResult(String toolCallId, String toolName, String result,
                             boolean success, String error, long executionTimeMs) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.result = result;
        this.success = success;
        this.error = error;
        this.executionTimeMs = executionTimeMs;
    }

    /**
     * 创建成功结果
     */
    public static OnlineToolResult success(String toolCallId, String toolName,
                                           String result, long executionTimeMs) {
        return new OnlineToolResult(toolCallId, toolName, result, true, null, executionTimeMs);
    }

    /**
     * 创建失败结果
     */
    public static OnlineToolResult failure(String toolCallId, String toolName,
                                           String error, long executionTimeMs) {
        return new OnlineToolResult(toolCallId, toolName, null, false, error, executionTimeMs);
    }

    @Override
    public String toString() {
        if (success) {
            return "OnlineToolResult{ok, tool=" + toolName + ", time=" + executionTimeMs + "ms, len=" + (result != null ? result.length() : 0) + "}";
        } else {
            return "OnlineToolResult{fail, tool=" + toolName + ", error=" + error + "}";
        }
    }

    /**
     * 从 AgentService.ToolResult 转换（供本地引擎适配使用）。
     */
    public static OnlineToolResult fromAgentServiceResult(String toolName,
                                                           com.oilquiz.app.ai.service.AgentService.ToolResult result) {
        if (result.success) {
            return success(null, toolName, result.result, result.executionTimeMs);
        } else {
            String err = result.errorMessage != null ? result.errorMessage : result.result;
            return failure(null, toolName, err != null ? err : "未知错误", result.executionTimeMs);
        }
    }
}
