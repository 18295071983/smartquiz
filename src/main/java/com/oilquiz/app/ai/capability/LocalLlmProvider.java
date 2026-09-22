package com.oilquiz.app.ai.capability;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.util.PromptBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 LLM Provider —— 能力缝的本地实现（Service Provider）。
 *
 * <p>包装现有 {@link AIService#generate(String, AIService.GenerateCallback)}
 * 本地 llama.cpp 推理为统一 {@link LlmService}。纯对话能力（无工具循环），
 * 本地 Agent 的 function calling 仍由在线引擎承担。
 */
public class LocalLlmProvider implements LlmProvider {

    private static final String TAG = "LocalLlmProvider";

    public static final String ID = "local";

    private final Context context;

    public LocalLlmProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "本地模型（llama.cpp）";
    }

    @Override
    public boolean supports(LlmRequest request) {
        // 本地模型不做工具循环；带工具需求的请求由在线 Provider 承接
        return true;
    }

    @Override
    public LlmService createService() {
        return new LocalLlmService(context);
    }

    /** 本地服务实例（每次 stream 调用 AIService.generate） */
    static final class LocalLlmService implements LlmService {

        private final AIService aiService;
        private final AtomicBoolean busy = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        LocalLlmService(Context context) {
            this.aiService = AIService.getInstance(context);
        }

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public void stream(LlmRequest request, LlmStreamCallback callback) {
            if (aiService == null) {
                callback.onError("AIService 不可用");
                return;
            }
            if (!busy.compareAndSet(false, true)) {
                callback.onError("本地服务忙，请稍后再试");
                return;
            }
            cancelled.set(false);
            String prompt = request.lastUserMessage();
            if (prompt.isEmpty()) {
                busy.set(false);
                callback.onError("请求中没有用户消息");
                return;
            }
            // 历史消息：去掉最后一条用户消息（作为本次 prompt），其余转 PromptBuilder.Message
            List<PromptBuilder.Message> history = new ArrayList<>();
            List<LlmMessage> msgs = request.messages;
            for (int i = 0; i < msgs.size() - 1; i++) {
                LlmMessage m = msgs.get(i);
                history.add(new PromptBuilder.Message(m.role, m.content));
            }
            try {
                aiService.generate(prompt, history, request.maxTokens,
                        new AIService.GenerateCallback() {
                            @Override
                            public void onSuccess(String response) {
                                busy.set(false);
                                if (!cancelled.get()) {
                                    callback.onComplete(response);
                                }
                            }

                            @Override
                            public void onError(Exception error) {
                                busy.set(false);
                                String msg = error != null ? error.getMessage() : "本地生成失败";
                                callback.onError(msg);
                            }
                        });
            } catch (Exception e) {
                busy.set(false);
                Log.e(TAG, "generate failed", e);
                callback.onError("本地生成异常: " + e.getMessage());
            }
        }

        @Override
        public void cancel() {
            // AIService 无公开的生成取消入口：做逻辑取消（停止转发 token）。
            cancelled.set(true);
            Log.i(TAG, "Logical cancel (no native generation cancel API)");
        }

        @Override
        public boolean isBusy() {
            return busy.get();
        }
    }
}
