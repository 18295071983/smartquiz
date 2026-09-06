# -*- coding: utf-8 -*-
"""ModelExecutionBridge 普通对话（executeSendMessage）改用 chatJson 统一协议：
- chatSend -> LlamaHelper.chatJson（本地维护多轮 history，无状态协议每次全量提交）
- reasoning 事件 -> BridgeCallback.onThinkingUpdate（UI 思考区折叠显示）
- complete -> onGenerationComplete（干净正文）"""
import io

p = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\bridge\ModelExecutionBridge.java"
s = io.open(p, encoding="utf-8").read()

# ---- 1) 替换 executeSendMessage 方法体 ----
old_fn = '''    private void executeSendMessage(ChatCommand command, BridgeCallback callback) {
        if (isGenerating.get()) {
            notifyError(callback, command.messageId, "正在生成中，请稍候");
            return;
        }

        isGenerating.set(true);
        isCancelled = false;
        currentMessageId = command.messageId;
        currentCallback = callback;
        agentToolLoopCount = 0;

        notifyStarted(callback, command.messageId);

        executor.execute(() -> {
            try {
                // 确保模型初始化
                if (!ensureModelInitialized(callback, command.messageId)) return;
                if (isCancelled) return;

                // 确保上下文
                ensureChatContext();
                if (isCancelled) return;

                // 发送到模型
                aiService.chatSend(command.content, command.maxTokens, command.enableThinking,
                    new BridgeTokenCallback(command.messageId, callback));

            } catch (Throwable t) {
                AILogger.e(TAG, "Send message failed", t);
                notifyError(callback, command.messageId,
                    "发送失败: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                resetGeneration();
            }
        });
    }'''

new_fn = '''    private void executeSendMessage(ChatCommand command, BridgeCallback callback) {
        if (isGenerating.get()) {
            notifyError(callback, command.messageId, "正在生成中，请稍候");
            return;
        }

        isGenerating.set(true);
        isCancelled = false;
        currentMessageId = command.messageId;
        currentCallback = callback;
        agentToolLoopCount = 0;

        notifyStarted(callback, command.messageId);

        executor.execute(() -> {
            try {
                // 确保模型初始化
                if (!ensureModelInitialized(callback, command.messageId)) return;
                if (isCancelled) return;

                // 普通对话改用 chatJson 统一协议（与 Agent 同源）：
                // C++ 完成模板格式化 + 思考剥离 + reasoning 事件广播 + 干净正文 complete；
                // Java 侧 token->正文、reasoning->思考区（onThinkingUpdate）、complete->终态。
                String requestJson = buildChatJsonRequest(command.content, command.maxTokens, command.enableThinking);
                if (requestJson == null) {
                    notifyError(callback, command.messageId, "构建推理请求失败");
                    resetGeneration();
                    return;
                }
                LlamaHelper.chatJson(requestJson, new BridgeJsonCallback(command.messageId, callback));

            } catch (Throwable t) {
                AILogger.e(TAG, "Send message failed", t);
                notifyError(callback, command.messageId,
                    "发送失败: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                resetGeneration();
            }
        });
    }

    // ========== chatJson 本地对话历史（无状态协议：每次全量提交，内部维护多轮） ==========
    private static final int CHATJSON_HISTORY_LIMIT = 20;
    private final java.util.List<org.json.JSONObject> chatJsonHistory = new java.util.ArrayList<>();

    private synchronized void appendChatJsonHistory(String role, String content) {
        try {
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("role", role);
            m.put("content", content != null ? content : "");
            chatJsonHistory.add(m);
            while (chatJsonHistory.size() > CHATJSON_HISTORY_LIMIT) {
                chatJsonHistory.remove(0);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "appendChatJsonHistory failed: " + e.getMessage());
        }
    }

    private String buildChatJsonRequest(String message, int maxTokens, boolean enableThinking) {
        try {
            org.json.JSONObject req = new org.json.JSONObject();
            req.put("action", "chat");
            org.json.JSONArray msgs = new org.json.JSONArray();
            synchronized (this) {
                for (org.json.JSONObject m : chatJsonHistory) {
                    msgs.put(m);
                }
            }
            org.json.JSONObject cur = new org.json.JSONObject();
            cur.put("role", "user");
            cur.put("content", message);
            msgs.put(cur);
            appendChatJsonHistory("user", message);
            req.put("messages", msgs);
            req.put("enable_thinking", enableThinking);
            req.put("max_tokens", maxTokens);
            req.put("temperature", 0.6f);
            req.put("top_p", 0.9f);
            req.put("top_k", 40);
            req.put("tool_choice", "none");
            return req.toString();
        } catch (Exception e) {
            AILogger.e(TAG, "buildChatJsonRequest failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * chatJson 普通对话回调：token -> onToken（正文），reasoning -> onThinkingUpdate（思考区），
     * complete -> onGenerationComplete（干净正文并记录 assistant 到本地 history），error -> onGenerationError。
     */
    private class BridgeJsonCallback implements LlamaHelper.JsonCallback {
        private final String messageId;
        private final BridgeCallback callback;
        private final StringBuilder bodyBuf = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private int tokenCount = 0;

        BridgeJsonCallback(String messageId, BridgeCallback callback) {
            this.messageId = messageId;
            this.callback = callback;
        }

        @Override
        public void onJson(String json) {
            if (isCancelled) return;
            try {
                org.json.JSONObject event = new org.json.JSONObject(json);
                String type = event.optString("type", "");
                switch (type) {
                    case "token": {
                        String token = event.optString("content", "");
                        if (!token.isEmpty()) {
                            bodyBuf.append(token);
                            tokenCount++;
                            mainHandler.post(() -> {
                                if (callback != null) callback.onToken(messageId, token);
                            });
                        }
                        break;
                    }
                    case "reasoning": {
                        String reasoning = event.optString("content", "");
                        if (!reasoning.isEmpty()) {
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onThinkingUpdate(messageId, 1, "thinking", "思考", reasoning, 0);
                                }
                            });
                        }
                        break;
                    }
                    case "complete": {
                        String content = event.optString("content", "");
                        final String finalContent = content != null && !content.isEmpty()
                                ? content : bodyBuf.toString();
                        final int finalCount = tokenCount;
                        final long finalElapsed = System.currentTimeMillis() - startTime;
                        final float finalTps = finalElapsed > 0 ? (finalCount * 1000.0f) / finalElapsed : 0;
                        appendChatJsonHistory("assistant", finalContent);
                        mainHandler.post(() -> {
                            if (callback != null) {
                                callback.onGenerationComplete(messageId, finalContent, finalCount, finalElapsed, finalTps);
                            }
                        });
                        resetGeneration();
                        break;
                    }
                    case "error": {
                        String err = event.optString("message", "未知错误");
                        mainHandler.post(() -> {
                            if (callback != null) callback.onGenerationError(messageId, err);
                        });
                        resetGeneration();
                        break;
                    }
                    default:
                        // meta 等事件暂不处理
                        break;
                }
            } catch (Exception e) {
                AILogger.e(TAG, "chatJson event parse error: " + e.getMessage());
            }
        }
    }'''

assert s.count(old_fn) == 1, "executeSendMessage anchor not unique: %d" % s.count(old_fn)
s = s.replace(old_fn, new_fn)
io.open(p, "w", encoding="utf-8").write(s)
print("OK patched ModelExecutionBridge")
