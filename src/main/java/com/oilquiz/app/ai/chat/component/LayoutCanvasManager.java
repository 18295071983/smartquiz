package com.oilquiz.app.ai.chat.component;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 布局画布会话管理器：为「动态页面框架」提供跨工具访问画布的桥梁。
 *
 * Agent 用 ui_component(action=create, component_type=layout_canvas, layout={...})
 * 创建一张常驻画布（弹窗渲染 layout 树），再用 layout_editor 工具对其编辑：
 * - set：整体替换布局（传完整 layout JSON，或 root 节点）
 * - add：向指定容器（path 定位）追加子节点
 * - patch：按键修改/删除节点
 * - get：返回当前布局结构（供 Agent 查看/调试）
 * - rebuild：强制重渲染
 *
 * 每次编辑后由画布宿主（PythonToolManager.showLayoutCanvasDialog 注册的
 * applyLayout 回调）重渲染弹窗内容；输入控件的 key 值在重渲染时回填。
 * 会话在弹窗关闭后移除。
 *
 * 该管理器只保存「注册的会话回调」，不直接持有 View/Activity（用弱引用存回调，
 * 回调内部持有 ComponentRuntime，与现有组件生命周期一致）。
 */
public final class LayoutCanvasManager {

    private static volatile LayoutCanvasManager instance;

    /** 画布会话：component_id → 布局操作回调（由画布弹窗注册） */
    private final ConcurrentHashMap<String, LayoutCanvasSession> sessions =
            new ConcurrentHashMap<>();

    private LayoutCanvasManager() {
    }

    public static LayoutCanvasManager getInstance() {
        if (instance == null) {
            synchronized (LayoutCanvasManager.class) {
                if (instance == null) {
                    instance = new LayoutCanvasManager();
                }
            }
        }
        return instance;
    }

    /** 画布会话：持有当前布局 JSON + 重渲染回调 + 值回填源 */
    public static class LayoutCanvasSession {
        /** 当前布局（可能是 {"root":...} 完整结构或单节点） */
        public volatile JSONObject layout;
        /** 重建弹窗内容（主线程调用）：用 session.layout 重新 render 并回填控件值 */
        public volatile java.util.function.Consumer<LayoutCanvasSession> applyLayout;
        /** 收集当前已渲染的输入控件值（供重建时回填，避免用户输入丢失） */
        public volatile java.util.function.Supplier<java.util.Map<String, Object>> collectCurrentValues;
        /** 初始 props（顶层参数，传给 render） */
        public volatile JSONObject props;

        public JSONObject getLayout() {
            return layout;
        }
    }

    /** 注册画布会话并返回是否成功（component_id 已存在则覆盖）。 */
    public void register(String componentId, LayoutCanvasSession session) {
        if (componentId != null && session != null) {
            sessions.put(componentId, session);
        }
    }

    /** 取画布会话（不存在返回 null） */
    public LayoutCanvasSession get(String componentId) {
        return componentId == null ? null : sessions.get(componentId);
    }

    /** 移除画布会话（弹窗关闭时调用） */
    public void unregister(String componentId) {
        if (componentId != null) {
            sessions.remove(componentId);
        }
    }

    /** 是否有活跃画布（调试用） */
    public boolean hasSession(String componentId) {
        return componentId != null && sessions.containsKey(componentId);
    }

    /** 活跃画布数（调试/管理用） */
    public int size() {
        return sessions.size();
    }
}
