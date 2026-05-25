package com.oilquiz.app.ai.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作注解，用于声明工具支持的具体操作
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Action {
    /**
     * 操作名称
     */
    String name();
    
    /**
     * 操作描述
     */
    String description() default "";
    
    /**
     * 操作所需的参数
     */
    Param[] params() default {};
}
