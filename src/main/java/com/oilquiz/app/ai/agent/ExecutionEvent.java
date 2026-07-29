package com.oilquiz.app.ai.agent;

import java.util.List;

/**
 * 执行引擎事件 - 引擎向UI传递的事件
 *
 * 设计目的：将模型执行与UI完全解耦
 * 引擎通过此事件通知UI需要用户交互（如补充信息、验证错误），
 * 而不是直接操作Activity显示对话框
 */
public class ExecutionEvent {

    public enum Type {
        // 执行生命周期
        EXECUTION_STARTED,
        EXECUTION_COMPLETED,
        EXECUTION_FAILED,
        EXECUTION_CANCELLED,

        // 执行过程
        STEP_STARTED,
        STEP_COMPLETED,
        TOKEN_GENERATED,
        THINKING_TOKEN,
        THINKING_ENDED,
        INFERENCE_PROGRESS,

        // 工具调用
        TOOL_CALL_STARTED,
        TOOL_CALL_COMPLETED,

        // 需要UI交互
        NEED_USER_INPUT,
        VALIDATION_ERROR,
        EXECUTION_PAUSED,
        EXECUTION_RESUMING,

        // 模式切换
        MODE_SWITCHED
    }

    public final Type type;
    public final String messageId;
    public final long timestamp;

    // 通用数据字段
    public String text;
    public String detail;
    public int stepNumber;
    public int progress;
    public int tokenCount;
    public float tokensPerSecond;
    public boolean success;

    // 工具调用相关
    public String toolName;
    public String toolArgs;
    public String toolResult;

    // 步骤相关
    public String stepType;
    public String stepTitle;
    public String stepContent;

    // 用户输入相关
    public String missingInfo;
    public String context;
    public List<String> suggestions;
    public String paramName;
    public String errorMessage;
    public String correctFormat;
    public List<String> examples;
    public int retryCount;

    private ExecutionEvent(Type type, String messageId) {
        this.type = type;
        this.messageId = messageId;
        this.timestamp = System.currentTimeMillis();
    }

    // ========== 工厂方法 ==========

    public static ExecutionEvent started(String messageId) {
        return new ExecutionEvent(Type.EXECUTION_STARTED, messageId);
    }

    public static ExecutionEvent completed(String messageId, String finalAnswer) {
        ExecutionEvent event = new ExecutionEvent(Type.EXECUTION_COMPLETED, messageId);
        event.text = finalAnswer;
        return event;
    }

    public static ExecutionEvent failed(String messageId, String error) {
        ExecutionEvent event = new ExecutionEvent(Type.EXECUTION_FAILED, messageId);
        event.errorMessage = error;
        return event;
    }

    public static ExecutionEvent cancelled(String messageId) {
        return new ExecutionEvent(Type.EXECUTION_CANCELLED, messageId);
    }

    public static ExecutionEvent token(String messageId, String token) {
        ExecutionEvent event = new ExecutionEvent(Type.TOKEN_GENERATED, messageId);
        event.text = token;
        return event;
    }

    public static ExecutionEvent thinkingToken(String messageId, String token) {
        ExecutionEvent event = new ExecutionEvent(Type.THINKING_TOKEN, messageId);
        event.text = token;
        return event;
    }

    public static ExecutionEvent thinkingEnded(String messageId) {
        return new ExecutionEvent(Type.THINKING_ENDED, messageId);
    }

    public static ExecutionEvent inferenceProgress(String messageId, int tokenCount, float tps) {
        ExecutionEvent event = new ExecutionEvent(Type.INFERENCE_PROGRESS, messageId);
        event.tokenCount = tokenCount;
        event.tokensPerSecond = tps;
        return event;
    }

    public static ExecutionEvent toolCallStarted(String messageId, String toolName, String args) {
        ExecutionEvent event = new ExecutionEvent(Type.TOOL_CALL_STARTED, messageId);
        event.toolName = toolName;
        event.toolArgs = args;
        return event;
    }

    public static ExecutionEvent toolCallCompleted(String messageId, String toolName,
                                                     boolean success, String result) {
        ExecutionEvent event = new ExecutionEvent(Type.TOOL_CALL_COMPLETED, messageId);
        event.toolName = toolName;
        event.success = success;
        event.toolResult = result;
        return event;
    }

    public static ExecutionEvent step(String messageId, int stepNumber, String stepType,
                                       String title, String content, int progress) {
        ExecutionEvent event = new ExecutionEvent(Type.STEP_STARTED, messageId);
        event.stepNumber = stepNumber;
        event.stepType = stepType;
        event.stepTitle = title;
        event.stepContent = content;
        event.progress = progress;
        return event;
    }

    public static ExecutionEvent needUserInput(String messageId, String missingInfo,
                                                String context, List<String> suggestions) {
        ExecutionEvent event = new ExecutionEvent(Type.NEED_USER_INPUT, messageId);
        event.missingInfo = missingInfo;
        event.context = context;
        event.suggestions = suggestions;
        return event;
    }

    public static ExecutionEvent validationError(String messageId, String paramName,
                                                   String errorMsg, String correctFormat,
                                                   List<String> examples, int retryCount) {
        ExecutionEvent event = new ExecutionEvent(Type.VALIDATION_ERROR, messageId);
        event.paramName = paramName;
        event.errorMessage = errorMsg;
        event.correctFormat = correctFormat;
        event.examples = examples;
        event.retryCount = retryCount;
        return event;
    }

    public static ExecutionEvent paused(String messageId, String reason, String currentState) {
        ExecutionEvent event = new ExecutionEvent(Type.EXECUTION_PAUSED, messageId);
        event.detail = reason;
        event.context = currentState;
        return event;
    }

    public static ExecutionEvent modeSwitched(String messageId, String mode) {
        ExecutionEvent event = new ExecutionEvent(Type.MODE_SWITCHED, messageId);
        event.text = mode;
        return event;
    }

    @Override
    public String toString() {
        return "ExecutionEvent{" + type + ", msg=" + messageId + "}";
    }
}