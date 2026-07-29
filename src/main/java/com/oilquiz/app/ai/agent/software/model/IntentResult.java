package com.oilquiz.app.ai.agent.software.model;

/**
 * IntentResult - 意图识别结果
 */
public class IntentResult {
    public final String type;           // 意图类型
    public final double confidence;     // 置信度
    public final String entity;         // 关键实体
    public final String originalMessage; // 原始消息
    
    public IntentResult(String type, double confidence, String entity, String originalMessage) {
        this.type = type;
        this.confidence = confidence;
        this.entity = entity;
        this.originalMessage = originalMessage;
    }
    
    /**
     * 是否需要工具
     */
    public boolean needsTool() {
        return type != null && !type.equals("CHAT") && !type.equals("UNKNOWN");
    }
    
    @Override
    public String toString() {
        return "IntentResult{type='" + type + "', confidence=" + confidence + 
               ", entity='" + entity + "'}";
    }
}
