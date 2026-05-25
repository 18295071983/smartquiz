package com.oilquiz.app.ai.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 参数注解，用于声明工具参数信息
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PARAMETER})
public @interface Param {
    /**
     * 参数名称
     */
    String name();
    
    /**
     * 参数类型：string, int, float, boolean, list, object
     */
    String type() default "string";
    
    /**
     * 参数描述
     */
    String description() default "";
    
    /**
     * 是否必填
     */
    boolean required() default false;
    
    /**
     * 默认值
     */
    String defaultValue() default "";
    
    /**
     * 可选值列表（对于枚举型参数）
     */
    String[] options() default {};
}
