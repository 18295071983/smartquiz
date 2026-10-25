package com.oilquiz.app.ai.agent.software.model;

/**
 * AgentResponse - Agent 响应（单循环架构）
 */
public class AgentResponse {
    public String finalAnswer;      // 最终回复
    public AgentStats stats;        // 统计信息

    /**
     * 本轮是否被用户取消（2026-10-25 停止链路修复）。
     *
     * <p>由 {@code AgentLoopEngine} 在取消分支置位；{@code AgentSoftwareLayer} 据此把它
     * 与"正常完成/失败"区分开打日志。注意取消仍会带回**已产出的部分正文**（finalAnswer
     * 可能是半截答案），UI 侧沿用既有的"用户停止"标注，不另造终态。</p>
     */
    public boolean cancelled = false;

    public AgentResponse(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    @Override
    public String toString() {
        return "AgentResponse{answer='" + (finalAnswer != null ? finalAnswer.substring(0, Math.min(50, finalAnswer.length())) : "null") + "...'}";
    }
}
