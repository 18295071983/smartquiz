package com.oilquiz.app.ai.sessionlog;

import android.util.Log;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.online.OnlineExecutionStep;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 事件桥接器 —— 把现有 {@link AgentCallback} 事件流映射为会话事件日志。
 *
 * <p>零侵入接入：不修改引擎，只需在设置 UI 回调时用
 * {@link #wrap(AgentCallback, String, SessionEventLog)} 包一层。
 * 桥接器内部维护一轮执行的状态机：
 *
 * <pre>
 * 首个回调 → TURN_STARTED
 *   用户/助手/思考/工具事件 → 对应事件类型（payload 见 SessionEventPayload）
 * onComplete  → ASSISTANT_MESSAGE + TURN_COMPLETED
 * onError     → TURN_FAILED
 * </pre>
 */
public class AgentEventBridge implements AgentCallback {

    private static final String TAG = "AgentEventBridge";

    private final AgentCallback delegate;
    private final String sessionId;
    private final SessionEventLog log;

    // 一轮执行状态
    private boolean turnOpen = false;
    private long turnStartTs = 0L;
    private final StringBuilder assistantBuffer = new StringBuilder();
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private final Map<String, Long> toolStartTs = new HashMap<>();

    private AgentEventBridge(AgentCallback delegate, String sessionId, SessionEventLog log) {
        this.delegate = delegate;
        this.sessionId = sessionId;
        this.log = log;
    }

    /** 包装一个回调：UI 回调 + 事件日志双写（delegate 可为 null，仅记日志）。 */
    public static AgentCallback wrap(AgentCallback delegate, String sessionId,
                                     SessionEventLog log) {
        return new AgentEventBridge(delegate, sessionId, log);
    }

    // ==================== 回调实现 ====================

    @Override
    public void onToken(String token) {
        ensureTurnOpen();
        if (token != null) {
            assistantBuffer.append(token);
        }
        if (delegate != null) {
            delegate.onToken(token);
        }
    }

    @Override
    public void onThinkingToken(String token) {
        ensureTurnOpen();
        if (token != null) {
            thinkingBuffer.append(token);
        }
        if (delegate != null) {
            delegate.onThinkingToken(token);
        }
    }

    @Override
    public void onThinkingEnd() {
        flushThinking();
        if (delegate != null) {
            delegate.onThinkingEnd();
        }
    }

    @Override
    public void onToolCallStart(String toolCallId, String toolName, String args) {
        ensureTurnOpen();
        log.append(sessionId, SessionEventType.TOOL_CALL_STARTED,
                SessionEventPayload.toolCallStarted(toolCallId, toolName, args));
        toolStartTs.put(toolCallId == null ? "" : toolCallId, System.currentTimeMillis());
        if (delegate != null) {
            delegate.onToolCallStart(toolCallId, toolName, args);
        }
    }

    @Override
    public void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result) {
        ensureTurnOpen();
        String id = toolCallId == null ? "" : toolCallId;
        boolean success = result != null && result.success;
        String output = result != null
                ? (success ? result.result : result.error)
                : "null result";
        long duration = 0L;
        Long start = toolStartTs.remove(id);
        if (start != null) {
            duration = System.currentTimeMillis() - start;
        }
        log.append(sessionId, SessionEventType.TOOL_CALL_COMPLETED,
                SessionEventPayload.toolCallCompleted(id, toolName, success, output), duration);
        if (delegate != null) {
            delegate.onToolCallComplete(toolCallId, toolName, result);
        }
    }

    @Override
    public void onStepUpdate(String step, String detail) {
        if (delegate != null) {
            delegate.onStepUpdate(step, detail);
        }
    }

    @Override
    public void onComplete(String fullText) {
        ensureTurnOpen();
        flushThinking();
        String content = fullText != null ? fullText : assistantBuffer.toString();
        log.append(sessionId, SessionEventType.ASSISTANT_MESSAGE,
                SessionEventPayload.assistantMessage(content));
        closeTurn(SessionEventType.TURN_COMPLETED);
        if (delegate != null) {
            delegate.onComplete(fullText);
        }
    }

    @Override
    public void onError(String error) {
        ensureTurnOpen();
        flushThinking();
        log.append(sessionId, SessionEventType.TURN_FAILED,
                SessionEventPayload.error(error));
        closeTurn(SessionEventType.TURN_FAILED);
        if (delegate != null) {
            delegate.onError(error);
        }
    }

    // ==================== 可选回调透传 ====================

    @Override
    public void onExecutionStep(OnlineExecutionStep step, String detail) {
        if (delegate != null) {
            delegate.onExecutionStep(step, detail);
        }
    }

    @Override
    public void onThinking(String thought) {
        if (delegate != null) {
            delegate.onThinking(thought);
        }
    }

    @Override
    public void onThinkingStage(String stage) {
        if (delegate != null) {
            delegate.onThinkingStage(stage);
        }
    }

    // ==================== 内部 ====================

    private void ensureTurnOpen() {
        if (!turnOpen) {
            turnOpen = true;
            turnStartTs = System.currentTimeMillis();
            log.append(sessionId, SessionEventType.TURN_STARTED, null);
        }
    }

    private void flushThinking() {
        if (thinkingBuffer.length() > 0) {
            log.append(sessionId, SessionEventType.THINKING_MESSAGE,
                    SessionEventPayload.thinking(thinkingBuffer.toString()));
            log.append(sessionId, SessionEventType.THINKING_ENDED, null);
            thinkingBuffer.setLength(0);
        }
    }

    private void closeTurn(SessionEventType terminalType) {
        if (!turnOpen) {
            return;
        }
        long duration = System.currentTimeMillis() - turnStartTs;
        log.append(sessionId, terminalType, null, duration);
        turnOpen = false;
        assistantBuffer.setLength(0);
    }

    @Override
    public String toString() {
        return "AgentEventBridge{session=" + sessionId + ", turnOpen=" + turnOpen + "}";
    }
}
