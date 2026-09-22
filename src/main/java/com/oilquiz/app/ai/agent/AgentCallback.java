package com.oilquiz.app.ai.agent;

import com.oilquiz.app.ai.agent.online.OnlineExecutionStep;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;

import java.util.List;
import java.util.Map;

/**
 * Agent 统一回调接口（独立于具体引擎实现）。
 *
 * 本地引擎（{@link UnifiedAgentEngine}）和在线引擎（OnlineAgentEngine）
 * 共用此接口，UI 层无需感知引擎差异。
 *
 * 工具结果统一使用 {@link OnlineToolResult} 类型，
 * 本地引擎通过 {@link OnlineToolResult#fromAgentServiceResult} 转换。
 */
public interface AgentCallback {

    /** 正文 token 流式回调 */
    void onToken(String token);

    /** 思考链 token 流式回调（reasoning_content） */
    void onThinkingToken(String token);

    /** 思考链结束 */
    void onThinkingEnd();

    /** 工具调用开始（toolCallId 用于 UI 层精确匹配卡片，避免并行/乱序时更新错位） */
    void onToolCallStart(String toolCallId, String toolName, String args);

    /** 工具调用完成（使用统一的 OnlineToolResult 类型） */
    void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result);

    /** 执行步骤更新 */
    void onStepUpdate(String step, String detail);

    /** 生成完成 */
    void onComplete(String fullText);

    /** 错误 */
    void onError(String error);

    // ========== 可选回调（默认空实现） ==========

    /** 在线模型执行步骤变化（OnlineExecutionStep） */
    default void onExecutionStep(OnlineExecutionStep step, String detail) {}

    /** 思考过程输出（整段，非流式） */
    default void onThinking(String thought) {}

    /** 思考阶段变化 */
    default void onThinkingStage(String stage) {}

    /**
     * 工具声明化卡片意图（dsh presentCall/presentResult 对齐，2026-09-23）。
     * 引擎在 onToolCallStart/onToolCallComplete 前调用；card 为 null 表示无声明，UI 走通用呈现。
     */
    default void onToolPresent(String toolName, Map<String, Object> card) {}
}
