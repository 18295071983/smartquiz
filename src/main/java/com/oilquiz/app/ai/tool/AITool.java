package com.oilquiz.app.ai.tool;

import java.util.Map;

/**
 * AI工具接口，定义了工具的基本方法
 */
public interface AITool {
    /**
     * 获取工具名称
     */
    String getName();
    
    /**
     * 获取工具描述
     */
    String getDescription();
    
    /**
     * 执行工具
     * @param parameters 执行参数
     * @return 执行结果
     */
    AIToolResult execute(Map<String, Object> parameters);
    
    /**
     * 获取工具参数描述
     * @return 参数描述
     */
    Map<String, String> getParameterDescriptions();

    // ==================== dsh 对齐：工具声明化（2026-09-23） ====================

    /**
     * 可选：输出规范声明（JSON Schema 形态，供发现/文档/校验使用）。
     * 返回 null 表示未声明（缺省走通用文本输出）。
     */
    default Map<String, Object> getOutputSchema() {
        return null;
    }

    /**
     * 可选：调用前的 pending 卡片意图（纯函数、可重放，只依赖 args）。
     * 返回 null 表示走通用呈现（工具名 + 参数）。
     * 引擎在 onToolCall 前调用，结果经 onToolPresent 传给 UI 侧。
     */
    default Map<String, Object> presentCall(Map<String, Object> args) {
        return null;
    }

    /**
     * 可选：完成后的结果卡片意图（纯函数、可重放，只依赖 args 与 result）。
     * 返回 null 表示走通用呈现（结果文本）。
     * 引擎在 onToolResult 前调用，结果经 onToolPresent 传给 UI 侧。
     */
    default Map<String, Object> presentResult(Map<String, Object> args, AIToolResult result) {
        return null;
    }
}
