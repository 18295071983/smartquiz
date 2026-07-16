package com.oilquiz.app.ai.tool;

import java.util.Map;

/**
 * AI工具接口，定义了工具的基本方法
 */
public interface AITool {
    /**
     * 获取工具名称
     */
    String getName();
    
    /**
     * 获取工具描述
     */
    String getDescription();
    
    /**
     * 执行工具
     * @param parameters 执行参数
     * @return 执行结果
     */
    AIToolResult execute(Map<String, Object> parameters);
    
    /**
     * 获取工具参数描述
     * @return 参数描述
     */
    Map<String, String> getParameterDescriptions();
}
