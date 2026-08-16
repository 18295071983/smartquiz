package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.view.View;

/**
 * 对话 UI 组件插件接口。
 *
 * 实现此接口并注册到 {@link ComponentRegistry}，即可让对话界面按
 * {@link ComponentData#type} 渲染对应的结构化 View——新增组件无需改动
 * ChatAdapter / 渲染管线（插件式扩展）。
 */
public interface ChatComponent {

    /** 组件类型标识（与 ComponentData.type 对应），如 "chart"、"info_card" */
    String getType();

    /**
     * 判断此组件是否能渲染给定数据。
     *
     * @param data 组件数据（props 已解析为 JSONObject）
     * @return true 表示可以渲染
     */
    boolean canRender(ComponentData data);

    /**
     * 创建组件 View。
     *
     * @param context Android Context
     * @param data    组件数据
     * @return 渲染完成的 View；数据非法时可返回空占位 View
     */
    View createView(Context context, ComponentData data);
}
