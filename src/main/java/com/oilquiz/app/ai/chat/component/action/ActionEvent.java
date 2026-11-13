package com.oilquiz.app.ai.chat.component.action;

import org.json.JSONObject;

/**
 * 一次组件动作（2026-11-13，M4 ActionSink）。
 *
 * <p>卡片上的按钮被点击后，真正需要的只有三样：<b>哪个组件</b>、<b>什么值</b>、
 * <b>哪个动作</b>（动态卡常带 {@code actions[].id}）。至于送到电脑端、本机引擎还是页面跳转，
 * 由 {@link ActionRouter} 按 sink 顺序决定 —— 渲染器不再关心传输。</p>
 */
public final class ActionEvent {

    /** 组件 id（必填） */
    public final String componentId;
    /** 语义值：如 approve / reject / 用户填写的值 */
    public final String value;
    /** 动作自身 id（可空；动态卡 actions[].id） */
    public final String actionId;
    /** 来源通道：chat_ui / layout / mcp …（用于审计与后置分流） */
    public final String channel;
    /** 事件时间（毫秒） */
    public final long time;

    private ActionEvent(String componentId, String value, String actionId, String channel, long time) {
        this.componentId = componentId == null ? "" : componentId;
        this.value = value == null ? "" : value;
        this.actionId = actionId == null ? "" : actionId;
        this.channel = channel == null ? "" : channel;
        this.time = time;
    }

    public static ActionEvent of(String componentId, String value) {
        return new ActionEvent(componentId, value, "", "chat_ui", System.currentTimeMillis());
    }

    public static ActionEvent of(String componentId, String value, String actionId, String channel) {
        return new ActionEvent(componentId, value, actionId, channel, System.currentTimeMillis());
    }

    /**
     * 从卡片/动态卡的 action JSON 解析。
     *
     * <p>兼容三种既有写法：{@code {id,value}}、{@code {component_id,act}}、
     * 以及动态卡的 {@code {id,action,label}}（此时 value 取 action 或 label）。</p>
     */
    public static ActionEvent fromPayload(JSONObject action) {
        if (action == null) return null;
        String componentId = firstNonEmpty(action, "component_id", "componentId", "id");
        if (componentId.isEmpty()) return null;
        String value = firstNonEmpty(action, "value", "act", "action", "label");
        String actionId = firstNonEmpty(action, "action_id", "actionId");
        String channel = action.optString("channel", "chat_ui");
        return new ActionEvent(componentId, value, actionId, channel, System.currentTimeMillis());
    }

    /** 标准载荷（回传电脑端/日志用；字段名与 /session component_action 一致） */
    public JSONObject toPayload() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("component_id", componentId);
            obj.put("act", value);
            if (!actionId.isEmpty()) obj.put("action_id", actionId);
            if (!channel.isEmpty()) obj.put("channel", channel);
            obj.put("at", time);
        } catch (Exception ignored) {
            // JSONObject.put 只在 key 为 null 时抛；这里 key 都是常量，不会发生
        }
        return obj;
    }

    @Override
    public String toString() {
        return "ActionEvent{cid=" + componentId + ", value=" + value
                + (actionId.isEmpty() ? "" : ", action=" + actionId)
                + (channel.isEmpty() ? "" : ", channel=" + channel) + "}";
    }

    private static String firstNonEmpty(JSONObject obj, String... keys) {
        for (String k : keys) {
            String v = obj.optString(k, "");
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }
}
