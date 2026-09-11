package com.oilquiz.app.ai.spi;

/**
 * 字符串资源提供者（SPI，解耦 Context.getString）。
 *
 * 逻辑组件不再持有 Context，统一通过本接口取文案；
 * 宿主实现映射到自己的资源系统（Android R.string / 云端文案 / 多语言）。
 */
public interface StringProvider {

    /** 取字符串（resId 为宿主资源 ID；无资源系统时可实现为常量表映射） */
    String get(int resId);

    /** 取字符串并格式化（占位符同 String.format） */
    String get(int resId, Object... args);
}
