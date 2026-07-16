package com.oilquiz.app.ai.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具注解，用于声明AI工具元信息
 * 使用此注解可自动提取工具schema，无需手动在AgentService中注册
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    /**
     * 工具名称（如未指定则使用AITool.getName()）
     */
    String value() default "";
    
    /**
     * 工具描述（如未指定则使用AITool.getDescription()）
     */
    String description() default "";
    
    /**
     * 工具分类，用于工具选择和意图匹配
     */
    String category() default "general";
    
    /**
     * 工具别名，支持多个名称映射到同一工具
     */
    String[] aliases() default {};
    
    /**
     * 工具包含的操作列表
     */
    Action[] actions() default {};
    
    /**
     * 通用参数描述
     */
    Param[] params() default {};
}
