package com.oilquiz.app.ai.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具依赖注解，用于声明工具执行前的前置条件
 * Agent在执行工具前会自动检查并执行依赖链
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolDependencies {
    Dependency[] value() default {};
}
