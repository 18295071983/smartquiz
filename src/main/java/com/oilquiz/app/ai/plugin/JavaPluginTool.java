package com.oilquiz.app.ai.plugin;

import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolResult;

import java.util.Map;

/**
 * Java 插件工具 —— 声明式工具定义（插件用，注册返回 disposer）。
 *
 * <p>{@link PluginContext#registerJavaTool(JavaPluginTool)} 会把它同时接入
 * AIToolManager（可执行）与 OnlineToolRegistry（模型可见）。
 */
public interface JavaPluginTool {

    /** 工具名（全局唯一） */
    String name();

    /** 工具描述（给模型看） */
    String description();

    /** 工具类别（weather/search/file/code/database/meta…） */
    String category();

    /** 参数描述：参数名 → 参数说明（当前统一 string 类型） */
    Map<String, String> parameters();

    /** 执行逻辑 */
    AIToolResult execute(Map<String, Object> parameters) throws Exception;

    /** 适配为 AITool（默认实现） */
    default AITool asAitool() {
        return new PluginContext.SimpleJavaTool(this);
    }
}
