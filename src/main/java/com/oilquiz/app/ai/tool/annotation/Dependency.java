package com.oilquiz.app.ai.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具依赖声明
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Dependency {
    /**
     * 依赖的工具名称
     */
    String tool();
    
    /**
     * 依赖的操作（可选）
     */
    String action() default "execute";
    
    /**
     * 依赖条件类型
     * - permission_not_granted: 权限未授予时触发
     * - always: 总是执行前置工具
     * - condition: 根据条件表达式判断
     */
    String condition() default "always";
    
    /**
     * 条件参数，如权限名称等
     */
    String conditionParam() default "";
    
    /**
     * 前置工具的参数JSON模板
     */
    String argsTemplate() default "{}";
    
    /**
     * 依赖失败时是否阻止执行
     */
    boolean blockOnFailure() default true;
    
    /**
     * 依赖描述，用于日志和调试
     */
    String description() default "";
}
