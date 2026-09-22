package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.chat.component.ComponentData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI工具执行结果
 */
public class AIToolResult {
    private final boolean success;
    private final Object result;
    private final String errorMessage;
    private final Map<String, Object> additionalInfo;
    /** 可选：结构化 UI 组件数据（工具侧桥接，对话界面按类型渲染） */
    private ComponentData component;
    
    /**
     * 创建成功结果
     * @param result 结果数据
     * @param additionalInfo 附加信息
     */
    public AIToolResult(Object result, Map<String, Object> additionalInfo) {
        this.success = true;
        this.result = result;
        this.errorMessage = null;
        this.additionalInfo = additionalInfo;
    }
    
    /**
     * 创建失败结果
     * @param errorMessage 错误信息
     * @param additionalInfo 附加信息
     */
    public AIToolResult(String errorMessage, Map<String, Object> additionalInfo) {
        this.success = false;
        this.result = null;
        this.errorMessage = errorMessage;
        this.additionalInfo = additionalInfo;
    }
    
    /**
     * 创建结果（支持成功/失败标记）
     */
    public AIToolResult(Object result, Map<String, Object> additionalInfo, boolean success) {
        this.success = success;
        this.result = success ? result : null;
        this.errorMessage = success ? null : (result instanceof String ? (String) result : null);
        this.additionalInfo = additionalInfo;
    }
    
    /**
     * 创建成功结果（静态工厂方法）
     */
    public static AIToolResult success(Object result) {
        return new AIToolResult(result, null);
    }
    
    /**
     * 创建成功结果带附加信息
     */
    public static AIToolResult success(Object result, Map<String, Object> additionalInfo) {
        return new AIToolResult(result, additionalInfo);
    }
    
    /**
     * 创建失败结果（静态工厂方法）
     */
    public static AIToolResult fail(String errorMessage) {
        return new AIToolResult(errorMessage, null);
    }
    
    /**
     * 创建失败结果带附加信息
     */
    public static AIToolResult fail(String errorMessage, Map<String, Object> additionalInfo) {
        return new AIToolResult(errorMessage, additionalInfo);
    }
    
    /**
     * 是否执行成功
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * 获取执行结果
     */
    public Object getResult() {
        return result;
    }
    
    /**
     * 获取错误信息
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * 获取附加信息
     */
    public Map<String, Object> getAdditionalInfo() {
        return additionalInfo;
    }

    /**
     * 设置结构化 UI 组件数据（工具侧桥接）。
     */
    public AIToolResult withComponent(ComponentData component) {
        this.component = component;
        return this;
    }

    /**
     * 获取结构化 UI 组件数据，无则返回 null。
     */
    public ComponentData getComponent() {
        return component;
    }

    // ==================== dsh 对齐：结果契约扩展（2026-09-23） ====================

    /** 私有展示载荷：仅供 UI 呈现使用，绝不进入模型可见文本。缺省 null。 */
    private Map<String, Object> meta;

    /** 结果附带额外上下文：注入后续请求（不进本结果文本）。缺省 null。 */
    private List<String> additionalContexts;

    /**
     * 设置私有展示载荷（UI 专用，不进模型上下文）。
     */
    public AIToolResult withMeta(Map<String, Object> meta) {
        this.meta = meta;
        return this;
    }

    /**
     * 获取私有展示载荷，无则返回 null。
     */
    public Map<String, Object> getMeta() {
        return meta;
    }

    /**
     * 追加一条额外上下文（注入后续请求，不进本结果文本）。
     */
    public AIToolResult withAdditionalContext(String context) {
        if (context == null || context.isEmpty()) return this;
        if (additionalContexts == null) additionalContexts = new ArrayList<>();
        additionalContexts.add(context);
        return this;
    }

    /**
     * 获取额外上下文列表，无则返回 null。
     */
    public List<String> getAdditionalContexts() {
        return additionalContexts;
    }
}
