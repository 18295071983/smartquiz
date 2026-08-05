package com.oilquiz.app.ai.agent.software.model;

/**
 * AgentResponse - Agent 响应（单循环架构）
 */
public class AgentResponse {
    public String finalAnswer;      // 最终回复
    public AgentStats stats;        // 统计信息

    public AgentResponse(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    @Override
    public String toString() {
        return "AgentResponse{answer='" + (finalAnswer != null ? finalAnswer.substring(0, Math.min(50, finalAnswer.length())) : "null") + "...'}";
    }
}
