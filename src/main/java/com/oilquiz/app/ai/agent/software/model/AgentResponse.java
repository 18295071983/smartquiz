package com.oilquiz.app.ai.agent.software.model;

/**
 * AgentResponse - Agent 响应
 */
public class AgentResponse {
    public String finalAnswer;      // 最终回复
    public ThinkingChain chain;     // 思考链
    public ExecutionResult result;  // 执行结果
    public AgentStats stats;        // 统计信息
    
    public AgentResponse(String finalAnswer, ThinkingChain chain, ExecutionResult result) {
        this.finalAnswer = finalAnswer;
        this.chain = chain;
        this.result = result;
    }
    
    @Override
    public String toString() {
        return "AgentResponse{answer='" + (finalAnswer != null ? finalAnswer.substring(0, Math.min(50, finalAnswer.length())) : "null") + "...'}";
    }
}
