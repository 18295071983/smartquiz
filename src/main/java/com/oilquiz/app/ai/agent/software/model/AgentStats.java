package com.oilquiz.app.ai.agent.software.model;

/**
 * AgentStats - Agent 统计信息
 */
public class AgentStats {
    public int totalTokens;
    public long totalTimeMs;
    public int toolCallCount;
    public int thinkingSteps;
    
    public AgentStats(int totalTokens, long totalTimeMs, int toolCallCount, int thinkingSteps) {
        this.totalTokens = totalTokens;
        this.totalTimeMs = totalTimeMs;
        this.toolCallCount = toolCallCount;
        this.thinkingSteps = thinkingSteps;
    }
    
    public float getTokensPerSecond() {
        return totalTimeMs > 0 ? (totalTokens * 1000.0f) / totalTimeMs : 0;
    }
    
    public String toSummary() {
        return String.format("生成完成: %d tokens, %.1fs, %.1f t/s, %d 次工具调用",
            totalTokens, totalTimeMs / 1000.0f, getTokensPerSecond(), toolCallCount);
    }
}
