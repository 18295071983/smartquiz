package com.oilquiz.app.ai.agent.software.model;

import java.util.Map;

/**
 * Action - 执行动作
 */
public class Action {
    
    public enum ActionType {
        TOOL_CALL,    // 调用工具
        CONTINUE,     // 继续分析
        FINISH,       // 完成任务
        RETRY         // 重试
    }
    
    private final ActionType type;
    private final String description;
    private final Map<String, Object> parameters;
    
    public Action(ActionType type, String description, Map<String, Object> parameters) {
        this.type = type;
        this.description = description;
        this.parameters = parameters;
    }
    
    public ActionType getType() { return type; }
    public String getDescription() { return description; }
    public Map<String, Object> getParameters() { return parameters; }
}
