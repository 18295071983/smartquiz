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
     * 可选：本工具单次执行所需的超时（毫秒）。返回 0 表示用调用方的默认值。
     *
     * 为什么需要（2026-09-27 实测）：调用方（OnlineToolManager）对所有工具套了 30s 默认超时，
     * 但有些工具的耗时本来就不可控——remote_dsh 是在**电脑上真跑任务**（起 dsh agent、跑命令），
     * 实测手机端传 timeout=150 仍在 30s 被杀，且超时后模型只拿到空结果，只能盲目重试。
     * 工具自己最清楚要等多久，因此由工具声明，调用方取"声明值与默认值的较大者"（并受全局上限约束）。
     */
    default long executionTimeoutMs(Map<String, Object> args) {
        return 0L;
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
