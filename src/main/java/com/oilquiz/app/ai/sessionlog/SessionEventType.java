package com.oilquiz.app.ai.sessionlog;

/**
 * 会话事件类型 —— append-only 会话事件日志的事件词汇表。
 *
 * <p>借鉴 deepseek-harness 的 turn/step/tool 事件模型：一轮 agent 执行被建模为
 * TURN_STARTED → (USER_MESSAGE / THINKING_MESSAGE / TOOL_CALL_*) → TURN_COMPLETED
 * 的线性事实流。每个事实一旦写入日志就不可变（append-only），
 * 可回放（{@link SessionReplay}）、可统计、可恢复。
 */
public enum SessionEventType {

    /** 会话创建 */
    SESSION_STARTED,

    /** 一轮 agent 执行开始 */
    TURN_STARTED,

    /** 用户输入 */
    USER_MESSAGE,

    /** 助手正文（整段聚合，完成时写入） */
    ASSISTANT_MESSAGE,

    /** 思考链全文（聚合，思考结束时写入） */
    THINKING_MESSAGE,

    /** 思考链结束标记 */
    THINKING_ENDED,

    /** 工具调用开始（携带 toolCallId / 工具名 / 参数 JSON） */
    TOOL_CALL_STARTED,

    /** 工具调用完成（携带是否成功与结果文本） */
    TOOL_CALL_COMPLETED,

    /** 一轮执行正常结束 */
    TURN_COMPLETED,

    /** 一轮执行被取消 */
    TURN_CANCELLED,

    /** 一轮执行出错 */
    TURN_FAILED,

    /** 会话结束 */
    SESSION_ENDED
}
