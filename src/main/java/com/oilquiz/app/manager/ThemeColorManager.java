package com.oilquiz.app.manager;

import android.content.Context;

/**
 * 主题色管理器（重构版）：保留旧 API 签名以兼容既有调用（QuizActivity 等），
 * 全部实现委托给 {@link ThemeManager} 统一管理。
 */
public class ThemeColorManager {

    /** 当前主题色资源 id（兼容旧语义；自定义色返回默认紫色） */
    public int getCurrentThemeColor(Context context) {
        return ThemeManager.getThemeColorRes(context);
    }

    /** 当前主题色 ARGB 值（优先自定义色） */
    public int getCurrentThemeColorValue(Context context) {
        return ThemeManager.getThemeColor(context);
    }

    /** 旧语义：按资源 id 设置主题色（兼容旧调用点） */
    public void setThemeColor(Context context, int colorRes) {
        int argb = context.getResources().getColor(colorRes);
        ThemeManager.setThemeColor(context, argb);
    }

    /** 直接按 ARGB 设置主题色（新调用推荐） */
    public void setThemeColorValue(Context context, int argb) {
        ThemeManager.setThemeColor(context, argb);
    }
}
