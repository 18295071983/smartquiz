# -*- coding: utf-8 -*-
"""普通对话（AIChatViewModel）改用 chatJson 统一协议：
- startLocalInference 从 aiService.chatSend 改为 LlamaHelper.chatJson
- 新增 buildChatJsonRequest（无工具 tool_choice=none、enable_thinking=true）
- 新增 handleThinkingToken（reasoning 事件 -> 思考区折叠显示）"""
import io

p = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\chat\viewmodel\AIChatViewModel.java"
s = io.open(p, encoding="utf-8").read()

anchor_start = "    private void startLocalInference(String message) {"
anchor_end = "    // ========== 流式处理 =========="

i0 = s.find(anchor_start)
i1 = s.find(anchor_end, i0)
assert i0 >= 0 and i1 > i0, "anchors not found: %d/%d" % (i0, i1)

new_block = '''    private void startLocalInference(String message) {
        try {
        executor.execute(() -> {
            try {
                int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 1024;
                // 普通对话改用 chatJson 统一协议（与 Agent 同源）：
                // C++ 侧完成模板格式化 + 思考剥离 + reasoning 事件广播 + 干净正文 complete；
                // Java 侧 token->正文、reasoning->思考区（折叠显示）、complete->终态。
                String requestJson = buildChatJsonRequest(message, maxTokens);
                if (requestJson == null) {
                    handleGenerationError("构建推理请求失败");
                    return;
                }
                LlamaHelper.chatJson(requestJson, new LlamaHelper.JsonCallback() {
                    @Override
                    public void onJson(String json) {
                        try {
                            org.json.JSONObject event = new org.json.JSONObject(json);
                            String type = event.optString("type", "");
                            switch (type) {
                                case "token": {
                                    String token = event.optString("content", "");
                                    if (!token.isEmpty()) handleStreamingToken(token);
                                    break;
                                }
                                case "reasoning": {
                                    String reasoning = event.optString("content", "");
                                    if (!reasoning.isEmpty()) handleThinkingToken(reasoning);
                                    break;
                                }
                                case "complete": {
                                    String content = event.optString("content", "");
                                    completeGeneration(content);
                                    break;
                                }
                                case "error": {
                                    String err = event.optString("message", "未知错误");
                                    handleGenerationError(err);
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
                });
            } catch (Exception e) {
                AILogger.e(TAG, "Local inference failed", e);
                handleGenerationError("推理失败: " + e.getMessage());
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.e(TAG, "Executor rejected local inference task", e);
            handleGenerationError("线程池已关闭，请重启应用");
        }
    }

    /**
     * 构造普通对话的 chatJson 请求：无工具（tool_choice=none）、启用思考（enable_thinking=true，
     * 思考经 reasoning 事件折叠显示）。历史取 chatMessages 中 USER/AI 消息最近 20 条 + 当前消息。
     */
    private String buildChatJsonRequest(String message, int maxTokens) {
        try {
            org.json.JSONObject req = new org.json.JSONObject();
            req.put("action", "chat");
            org.json.JSONArray msgs = new org.json.JSONArray();
            int historyEnd = currentStreamingMessageIndex >= 0 ? currentStreamingMessageIndex : chatMessages.size();
            List<ChatMessage> snapshot = chatMessages.toImmutableList();
            int start = Math.max(0, historyEnd - 20);
            for (int i = start; i < historyEnd; i++) {
                ChatMessage m = snapshot.get(i);
                if (m.type == ChatMessage.MessageType.USER || m.type == ChatMessage.MessageType.AI) {
                    org.json.JSONObject msg = new org.json.JSONObject();
                    msg.put("role", m.getRole());
                    msg.put("content", m.content != null ? m.content : "");
                    msgs.put(msg);
                }
            }
            org.json.JSONObject cur = new org.json.JSONObject();
            cur.put("role", "user");
            cur.put("content", message);
            msgs.put(cur);
            req.put("messages", msgs);
            req.put("enable_thinking", true);
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
     * chatJson reasoning 事件：思考内容实时写入思考区（UI 折叠显示）。
     * 与旧 chatSend 的 [THINK_BEGIN]/[THINK_END] 标记协议不同——chatJson 走 reasoning 事件直接追加。
     */
    private void handleThinkingToken(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) return;
        if (!isInThinking) {
            isInThinking = true;
            if (currentThinkingContent == null) {
                currentThinkingContent = new StringBuilder();
            }
        }
        currentThinkingContent.append(reasoning);
        mainHandler.post(this::updateStreamingMessage);
    }

'''

s = s[:i0] + new_block + s[i1:]
io.open(p, "w", encoding="utf-8").write(s)
print("OK patched AIChatViewModel startLocalInference -> chatJson")
