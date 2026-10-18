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
     * 分类（canonical 目录用）：content / data / media / task / scene。
     * 默认按 {@link ComponentTypes} 的目录取，取不到算 content。
     */
    default String getCategory() {
        return ComponentTypes.CATEGORY_CONTENT;
    }

    /**
     * 数据版本（B1-2）：渲染器口径发生"旧数据需要不同处理"的变化时递增。
     * 持久化里的 {@code ComponentData} 不带版本，历史数据一律按 1 处理。
     */
    default int getVersion() {
        return 1;
    }

    /**
     * 字段说明（给模型/工具目录用）。默认从 {@link ComponentTypes} 的目录生成，
     * 有专属结构的组件可以覆写。
     */
    default org.json.JSONObject getSchema() {
        org.json.JSONArray catalog = ComponentTypes.catalogJson();
        String canonical = ComponentTypes.resolve(getType());
        for (int i = 0; i < catalog.length(); i++) {
            org.json.JSONObject o = catalog.optJSONObject(i);
            if (o != null && canonical.equals(o.optString("type"))) return o;
        }
        return new org.json.JSONObject();
    }

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

    /**
     * 视图被确定丢弃时的回收钩子（B1-2 预留，B2 收口生命周期）。
     *
     * <p>注意：不要在 detach 里销毁资源——{@code ChatAdapter} 的组件段指纹缓存会在
     * removeAllViews 之后把同一个 View 再 add 回来。真正的销毁走
     * {@link ComponentLifecycle#release(View)}（视图自带 {@link ComponentLifecycle.Releasable}
     * 时优先用它，组件级回收由这里补充）。
     */
    default void onRecycle(View view) {
    }
}
