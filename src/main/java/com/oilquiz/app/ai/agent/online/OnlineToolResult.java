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

    /**
     * 面向 UI 与会话记录的<b>唯一取文口径</b>：成功取 {@code result}，失败取 {@code error}，
     * 连 {@code error} 都没给时给一条可辨识的占位——永不返回 {@code null}，也永不把
     * "空 result"（看着像成功）当失败文案。
     *
     * <p>背景（2026-10-25 真机会话实测）：{@link #failure} 把原因写进 {@code error} 而
     * {@code result} 恒为 {@code null}，但 UI/持久化两处都只取 {@code result.result}，
     * 于是失败的工具卡与会话组件里<b>没有任何失败原因</b>——人只能去翻 ai_logs 按时间戳反查，
     * 历史恢复后也看不到。</p>
     *
     * <p>更正（同日复核）：这里原先写的是"模型侧不受影响，所以缺陷只在人这一侧"，<b>不成立</b>。
     * 引擎回填 tool 消息时拼的是 {@code "工具执行失败: " + error}（见
     * {@code OnlineAgentEngine}），{@code error} 为 {@code null} 时模型读到的是字面量
     * {@code "工具执行失败: null"}——同样拿不到原因。模型侧走 {@link #modelText()}。</p>
     */
    public String displayText() {
        if (success) {
            return result != null ? result : "";
        }
        if (error != null && !error.trim().isEmpty()) {
            return error;
        }
        // 失败却连 error 都没给：留一条可辨识的占位，绝不再给出"空 result"这种看着像成功的样子
        return (result != null && !result.trim().isEmpty())
                ? result : "工具执行失败（引擎未给出原因）";
    }

    /**
     * 回填给模型的 {@code tool} 消息正文口径：失败时带明确的"工具执行失败"标记（模型要靠这个
     * 信号决定换工具还是重试，不能只读到一段像是普通文本的错误串），且<b>永不输出 {@code null}
     * 字面量</b>。"没给原因"的兜底文案与 {@link #displayText()} 共用一处定义。
     */
    public String modelText() {
        if (success) {
            return result != null ? result : "";
        }
        if (error != null && !error.trim().isEmpty()) {
            return "工具执行失败: " + error;
        }
        return displayText();
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
