package com.oilquiz.app.ai.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * AI工具抽象基类，实现了AITool接口的默认方法
 * 
 * 提供了工具的基本属性和默认实现，子类只需实现execute方法
 */
public abstract class BaseAITool implements AITool {
    private final String toolName;
    private final String description;

    public BaseAITool(String toolName, String description) {
        this.toolName = toolName;
        this.description = description;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        return new HashMap<>();
    }
    
    public boolean canHandle(String input) {
        return input != null && input.toLowerCase().contains(toolName.toLowerCase());
    }
}