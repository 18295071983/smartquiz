package com.oilquiz.app.ai.capability;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentSession;
import com.oilquiz.app.ai.agent.online.OnlineExecutionStep;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线 LLM Provider —— 能力缝的在线实现（Service Provider）。
 *
 * <p>包装现有 {@link AgentSession}（在线 Agent 引擎：原生 function calling +
 * reasoning_content + 工具循环）为统一 {@link LlmService}。
 * 工具能力由引擎自带，Consumer 通过流式回调感知工具调用。
 */
public class OnlineLlmProvider implements LlmProvider {

    private static final String TAG = "OnlineLlmProvider";

    public static final String ID = "online";

    private final Context context;

    public OnlineLlmProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "在线 Agent（OpenAI 兼容 API）";
    }

    @Override
    public boolean supports(LlmRequest request) {
        return true;
    }

    @Override
    public LlmService createService() {
        return new OnlineLlmService(context);
    }

    /** 在线服务实例：每次 stream 以一轮 Agent 会话执行 */
    static final class OnlineLlmService implements LlmService {

        private final AgentSession session;
        private final AtomicBoolean busy = new AtomicBoolean(false);

        OnlineLlmService(Context context) {
            this.session = AgentSession.create(context);
        }

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public void stream(LlmRequest request, LlmStreamCallback callback) {
            if (!busy.compareAndSet(false, true)) {
                callback.onError("在线服务忙，请稍后再试");
                return;
            }
            String prompt = request.lastUserMessage();
            if (prompt.isEmpty()) {
                busy.set(false);
                callback.onError("请求中没有用户消息");
                return;
            }
            session.setCallback(new AgentCallback() {
                @Override
                public void onToken(String token) {
                    callback.onToken(token);
                }

                @Override
                public void onThinkingToken(String token) {
                    callback.onThinkingToken(token);
                }

                @Override
                public void onThinkingEnd() {
                    callback.onThinkingEnd();
                }

                @Override
                public void onToolCallStart(String toolCallId, String toolName, String args) {
                    callback.onToolCallStart(toolCallId, toolName, args);
                }

                @Override
                public void onToolCallComplete(String toolCallId, String toolName,
                                               OnlineToolResult result) {
                    callback.onToolCallComplete(toolCallId, toolName,
                            result != null && result.success,
                            result != null ? (result.success ? result.result : result.error) : "");
                }

                @Override
                public void onStepUpdate(String step, String detail) {
                }

                @Override
                public void onComplete(String fullText) {
                    busy.set(false);
                    callback.onComplete(fullText);
                }

                @Override
                public void onError(String error) {
                    busy.set(false);
                    callback.onError(error);
                }

                @Override
                public void onExecutionStep(OnlineExecutionStep step, String detail) {
                }

                @Override
                public void onThinking(String thought) {
                }

                @Override
                public void onThinkingStage(String stage) {
                }
            });
            try {
                session.start(prompt, request.maxTokens, request.enableThinking);
            } catch (Exception e) {
                busy.set(false);
                Log.e(TAG, "start failed", e);
                callback.onError("在线 Agent 启动失败: " + e.getMessage());
            }
        }

        @Override
        public void cancel() {
            session.stop();
            busy.set(false);
        }

        @Override
        public boolean isBusy() {
            return busy.get();
        }
    }
}
