package com.oilquiz.app.ai.agent.online;

/**
 * 在线模型 Agent 执行步骤定义。
 * 独立于本地 agent 的 ReAct/CoT/Plan/Direct 模式，
 * 专为在线模型原生 function calling 流程设计。
 */
public enum OnlineExecutionStep {

    THINKING("思考中", "模型推理中（reasoning_content 流式）"),
    TOOL_CALLING("工具调用", "检测到 tool_calls，准备执行"),
    TOOL_EXECUTING("执行工具", "工具执行中"),
    RESPONDING("生成回答", "最终回答生成中（content 流式）"),
    COMPLETED("完成", "执行完成");

    public final String displayName;
    public final String description;

    OnlineExecutionStep(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
