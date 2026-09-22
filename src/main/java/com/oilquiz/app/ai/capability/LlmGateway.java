package com.oilquiz.app.ai.capability;

import android.util.Log;

import com.oilquiz.app.ai.sessionlog.AgentEventBridge;
import com.oilquiz.app.ai.sessionlog.SessionEventLog;

/**
 * LLM 网关 —— 能力缝的 Consumer 角色。
 *
 * <p>只依赖 {@link LlmRegistry}（装配）与 {@link LlmService}（契约），
 * 不感知任何 Provider 实现。可选挂接 {@link SessionEventLog}：
 * 请求带 sessionId 时自动把流式回调桥接进会话事件日志（能力缝 × 事件日志联动）。
 */
public class LlmGateway {

    private static final String TAG = "LlmGateway";

    private final LlmRegistry registry;
    private final SessionEventLog eventLog; // 可空

    public LlmGateway(LlmRegistry registry, SessionEventLog eventLog) {
        this.registry = registry;
        this.eventLog = eventLog;
    }

    public LlmRegistry registry() {
        return registry;
    }

    /**
     * 执行一次 LLM 请求：解析 Provider → 创建 Service → 流式调用。
     * providerId 为空时自动选择第一个支持的 Provider。
     */
    public void stream(LlmRequest request, LlmStreamCallback callback) {
        LlmProvider provider = registry.resolve(request.providerId);
        if (provider == null && (request.providerId == null || request.providerId.isEmpty())) {
            provider = registry.resolveFirstSupported(request);
        }
        if (provider == null) {
            callback.onError("没有可用 LLM Provider"
                    + (request.providerId != null ? ": " + request.providerId : ""));
            return;
        }
        if (!provider.supports(request)) {
            callback.onError("Provider " + provider.id() + " 不支持该请求");
            return;
        }
        LlmService service = provider.createService();
        LlmStreamCallback target = callback;
        // 事件日志联动：桥接流式回调 → 会话事件日志（同时透传原回调）
        if (eventLog != null && request.sessionId != null && !request.sessionId.isEmpty()) {
            com.oilquiz.app.ai.agent.AgentCallback bridged =
                    AgentEventBridge.wrap(toAgentCallback(callback), request.sessionId, eventLog);
            target = fromAgentCallback(bridged);
        }
        Log.i(TAG, "stream via provider=" + provider.id()
                + " model=" + request.model + " session=" + request.sessionId);
        try {
            service.stream(request, target);
        } catch (Exception e) {
            Log.e(TAG, "stream failed", e);
            callback.onError("LLM 调用异常: " + e.getMessage());
        }
    }

    /**
     * 把 AgentCallback 形状转回 LlmStreamCallback（桥接器输出 → 服务输入）。
     * 纯透传，不做任何改写。
     */
    private LlmStreamCallback fromAgentCallback(com.oilquiz.app.ai.agent.AgentCallback ac) {
        return new LlmStreamCallback.Adapter() {
            @Override
            public void onToken(String token) {
                ac.onToken(token);
            }

            @Override
            public void onThinkingToken(String token) {
                ac.onThinkingToken(token);
            }

            @Override
            public void onThinkingEnd() {
                ac.onThinkingEnd();
            }

            @Override
            public void onToolCallStart(String toolCallId, String toolName, String args) {
                ac.onToolCallStart(toolCallId, toolName, args);
            }

            @Override
            public void onToolCallComplete(String toolCallId, String toolName,
                                           boolean success, String result) {
                ac.onToolCallComplete(toolCallId, toolName, success
                        ? com.oilquiz.app.ai.agent.online.OnlineToolResult.success(
                                toolCallId, toolName, result, 0L)
                        : com.oilquiz.app.ai.agent.online.OnlineToolResult.failure(
                                toolCallId, toolName, result, 0L));
            }

            @Override
            public void onComplete(String fullText) {
                ac.onComplete(fullText);
            }

            @Override
            public void onError(String error) {
                ac.onError(error);
            }
        };
    }

    /**
     * 把 LlmStreamCallback 转成 AgentCallback 形状（供 AgentEventBridge 复用）。
     * 注意：工具结果以 success + result 文本形式表达。
     */
    private com.oilquiz.app.ai.agent.AgentCallback toAgentCallback(LlmStreamCallback cb) {
        return new com.oilquiz.app.ai.agent.AgentCallback() {
            @Override
            public void onToken(String token) {
                cb.onToken(token);
            }

            @Override
            public void onThinkingToken(String token) {
                cb.onThinkingToken(token);
            }

            @Override
            public void onThinkingEnd() {
                cb.onThinkingEnd();
            }

            @Override
            public void onToolCallStart(String toolCallId, String toolName, String args) {
                cb.onToolCallStart(toolCallId, toolName, args);
            }

            @Override
            public void onToolCallComplete(String toolCallId, String toolName,
                                           com.oilquiz.app.ai.agent.online.OnlineToolResult result) {
                cb.onToolCallComplete(toolCallId, toolName,
                        result != null && result.success,
                        result != null ? (result.success ? result.result : result.error) : "");
            }

            @Override
            public void onStepUpdate(String step, String detail) {
            }

            @Override
            public void onComplete(String fullText) {
                cb.onComplete(fullText);
            }

            @Override
            public void onError(String error) {
                cb.onError(error);
            }
        };
    }
}
