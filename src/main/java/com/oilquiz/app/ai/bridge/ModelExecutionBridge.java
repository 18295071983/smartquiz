package com.oilquiz.app.ai.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型执行桥接器 - UI与模型之间的唯一通道
 *
 * 设计原则：
 * 1. UI不直接调用AIService/LlamaHelper/AgentService，全部通过此桥接器
 * 2. 所有模型操作在后台线程执行，回调在主线程返回
 * 3. native崩溃被try-catch隔离，不传播到UI
 * 4. 支持取消操作
 *
 * 架构：
 *   AIChatActivity (UI)
 *       ↓ ChatCommand
 *   ModelExecutionBridge (桥接层)
 *       ↓ 调用
 *   AIService / LlamaHelper / AgentService (模型层)
 *       ↓ JNI
 *   本地模型
 *       ↓ 回调
 *   ModelExecutionBridge
 *       ↓ BridgeCallback (主线程)
 *   AIChatActivity (UI)
 */
public class ModelExecutionBridge {

    private static final String TAG = "ModelBridge";

    private static volatile ModelExecutionBridge instance;

    private final Context appContext;
    private final AIService aiService;
    private final AgentService agentService;
    private final AIConfig aiConfig;

    // 后台执行线程
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "model-bridge");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, throwable) ->
            Log.e(TAG, "Uncaught exception in bridge thread", throwable));
        return t;
    });

    // 主线程Handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 当前生成状态
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private volatile String currentMessageId;
    private volatile BridgeCallback currentCallback;
    private volatile boolean isCancelled = false;

    // Agent工具调用循环计数
    private int agentToolLoopCount = 0;

    private ModelExecutionBridge(Context context, AIService aiService,
                                  AgentService agentService, AIConfig aiConfig) {
        this.appContext = context.getApplicationContext();
        this.aiService = aiService;
        this.agentService = agentService;
        this.aiConfig = aiConfig;
    }

    public static ModelExecutionBridge getInstance(Context context, AIService aiService,
                                                     AgentService agentService, AIConfig aiConfig) {
        if (instance == null) {
            synchronized (ModelExecutionBridge.class) {
                if (instance == null) {
                    instance = new ModelExecutionBridge(context, aiService, agentService, aiConfig);
                }
            }
        }
        return instance;
    }

    public static ModelExecutionBridge getInstance() {
        return instance;
    }

    /**
     * 执行命令 - UI层唯一入口
     */
    public void execute(ChatCommand command, BridgeCallback callback) {
        if (command == null) return;

        switch (command.type) {
            case SEND_MESSAGE:
                executeSendMessage(command, callback);
                break;
            case SEND_MESSAGE_AGENT:
                executeSendMessageAgent(command, callback);
                break;
            case STOP_GENERATION:
                executeStopGeneration(callback);
                break;
            case CLEAR_CONTEXT:
                executeClearContext(callback);
                break;
            case INIT_CONTEXT:
                executeInitContext(command, callback);
                break;
            case INIT_MODEL:
                executeInitModel(callback);
                break;
            case RELOAD_MODEL:
                executeReloadModel(callback);
                break;
            case GET_MODEL_INFO:
                executeGetModelInfo(callback);
                break;
            case GET_TOKEN_COUNT:
                executeGetTokenCount(command, callback);
                break;
            case HANDLE_MEMORY_PRESSURE:
                executeHandleMemoryPressure(command, callback);
                break;
            case CHECK_NATIVE_STATE:
                executeCheckNativeState(callback);
                break;
        }
    }

    // ========== 消息生成 ==========

    private void executeSendMessage(ChatCommand command, BridgeCallback callback) {
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

    /**
     * 从外部（UI 会话历史）重建 chatJson 历史，作为普通对话上下文的统一真相源。
     * 本地 Agent 同源（每次从 UI 历史重建），普通对话与 Agent 来回切换时上下文自然连续，
     * 不再需要增量同步桥接。
     * 调用时机：每次普通对话发送前。entries 为 {role, content} 对，不含当前待发送消息
     * （buildChatJsonRequest 会追加当前 user 消息）。
     */
    public synchronized void rebuildChatJsonHistoryFromExternal(java.util.List<String[]> entries) {
        chatJsonHistory.clear();
        if (entries != null) {
            for (String[] e : entries) {
                if (e == null || e.length < 2) continue;
                String role = e[0];
                String content = e[1];
                if (role == null || role.isEmpty() || content == null || content.isEmpty()) continue;
                try {
                    org.json.JSONObject m = new org.json.JSONObject();
                    m.put("role", role);
                    m.put("content", content);
                    chatJsonHistory.add(m);
                } catch (Exception ignored) {
                }
            }
            while (chatJsonHistory.size() > CHATJSON_HISTORY_LIMIT) {
                chatJsonHistory.remove(0);
            }
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
     * 构建 Agent 模式的 chatJson 请求（原生工具链路）：
     * - 与 buildChatJsonRequest 同构，但携带 tools（OpenAI 格式）并 tool_choice=auto
     * - 首轮（userMessage 非空）：历史 + 当前用户消息
     * - 续轮（userMessage 为 null）：仅历史（工具结果轮，模型基于工具结果继续）
     * C++ 层按模型模板注入工具定义并负责解析输出（common_chat_parse）。
     */
    private String buildAgentChatJsonRequest(String userMessage, int maxTokens) {
        try {
            org.json.JSONObject req = new org.json.JSONObject();
            req.put("action", "chat");
            org.json.JSONArray msgs = new org.json.JSONArray();
            synchronized (this) {
                for (org.json.JSONObject m : chatJsonHistory) {
                    msgs.put(m);
                }
            }
            if (userMessage != null && !userMessage.isEmpty()) {
                org.json.JSONObject cur = new org.json.JSONObject();
                cur.put("role", "user");
                cur.put("content", userMessage);
                msgs.put(cur);
                appendChatJsonHistory("user", userMessage);
            }
            if (msgs.length() == 0) {
                AILogger.e(TAG, "buildAgentChatJsonRequest: empty messages");
                return null;
            }
            req.put("messages", msgs);
            req.put("enable_thinking", false);
            req.put("max_tokens", maxTokens);
            req.put("temperature", 0.6f);
            req.put("top_p", 0.9f);
            req.put("top_k", 40);
            req.put("tool_choice", "auto");
            if (agentService != null) {
                String toolsJson = agentService.getAgentOpenAIToolDefinitions();
                if (toolsJson != null && !toolsJson.isEmpty() && !"[]".equals(toolsJson)) {
                    req.put("tools", new org.json.JSONArray(toolsJson));
                }
            }
            return req.toString();
        } catch (Exception e) {
            AILogger.e(TAG, "buildAgentChatJsonRequest failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 记录 assistant 的工具调用消息到 chatJson 历史（OpenAI 兼容格式，C++ oaicompat 解析）。
     * content 为该轮正文（可能为空——模型只输出工具调用时）。
     */
    private synchronized void appendChatJsonHistoryToolCalls(
            List<com.oilquiz.app.ai.service.OnlineInferenceService.ToolCallInfo> toolCalls, String content) {
        try {
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("role", "assistant");
            m.put("content", content != null ? content : "");
            org.json.JSONArray tcs = new org.json.JSONArray();
            for (com.oilquiz.app.ai.service.OnlineInferenceService.ToolCallInfo tc : toolCalls) {
                org.json.JSONObject tcObj = new org.json.JSONObject();
                tcObj.put("id", tc.id != null && !tc.id.isEmpty() ? tc.id : "call_" + System.nanoTime());
                tcObj.put("type", "function");
                org.json.JSONObject fn = new org.json.JSONObject();
                fn.put("name", tc.name != null ? tc.name : "");
                fn.put("arguments", tc.arguments != null ? tc.arguments : "{}");
                tcObj.put("function", fn);
                tcs.put(tcObj);
            }
            m.put("tool_calls", tcs);
            chatJsonHistory.add(m);
            trimChatJsonHistory();
        } catch (Exception e) {
            AILogger.w(TAG, "appendChatJsonHistoryToolCalls failed: " + e.getMessage());
        }
    }

    /**
     * 记录工具执行结果到 chatJson 历史（role=tool 消息，C++ 模板层渲染 <tool_response> 等）。
     */
    private synchronized void appendChatJsonHistoryToolResult(String toolCallId, String toolName, String content) {
        try {
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("role", "tool");
            m.put("tool_call_id", toolCallId);
            if (toolName != null) m.put("name", toolName);
            m.put("content", content != null ? content : "");
            chatJsonHistory.add(m);
            trimChatJsonHistory();
        } catch (Exception e) {
            AILogger.w(TAG, "appendChatJsonHistoryToolResult failed: " + e.getMessage());
        }
    }

    private void trimChatJsonHistory() {
        while (chatJsonHistory.size() > CHATJSON_HISTORY_LIMIT) {
            chatJsonHistory.remove(0);
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
        /** 本轮是否已实时收到 thinking 增量事件：思考区已实时累积，complete 的 reasoning 全文跳过防重复 */
        private boolean thinkingStreamed = false;

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
                    case "thinking": {
                        // 实时思考增量事件：native 思考段每累积一段就下发，思考区实时滚动
                        String tk = event.optString("content", "");
                        if (!tk.isEmpty()) {
                            thinkingStreamed = true;
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onThinkingUpdate(messageId, 1, "thinking", "思考", tk, 0);
                                }
                            });
                        }
                        break;
                    }
                    case "reasoning": {
                        // 若思考已实时累积（thinkingStreamed），全文不再追加（防思考区重复）；
                        // 否则为 legacy 兜底（无 thinking 事件路径），照常一次性写入
                        if (thinkingStreamed) break;
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
    }

    private void executeSendMessageAgent(ChatCommand command, BridgeCallback callback) {
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
                if (!ensureModelInitialized(callback, command.messageId)) return;
                if (isCancelled) return;

                ensureChatContext();
                if (isCancelled) return;

                // Agent 模式改用 chatJson + tools（原生工具链路，与普通对话同源）：
                // C++ common_chat_templates_apply 按模型模板注入工具定义（Qwen3.5 自动用
                // <tools> + XML 形态），模型按原生格式输出，common_chat_parse 负责解析
                // （Qwen3.5 XML / Qwen3 JSON / 其他模板格式均有 PEG 解析支持），
                // 解析结果经流式 tool_call 事件回调 Java。Java 侧不再拼 system prompt
                // 工具描述 + 正则解析，解析失败时才由 complete 兜底降级。
                String requestJson = buildAgentChatJsonRequest(command.content, command.maxTokens);
                if (requestJson == null) {
                    notifyError(callback, command.messageId, "构建Agent推理请求失败");
                    resetGeneration();
                    return;
                }
                LlamaHelper.chatJson(requestJson, new AgentJsonCallback(command.messageId, callback));

            } catch (Throwable t) {
                AILogger.e(TAG, "Agent send message failed", t);
                notifyError(callback, command.messageId,
                    "Agent执行失败: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                resetGeneration();
            }
        });
    }

    // ========== 停止生成 ==========

    private void executeStopGeneration(BridgeCallback callback) {
        isCancelled = true;
        try {
            if (aiService != null) {
                aiService.chatStop();
            }
        } catch (Throwable t) {
            AILogger.e(TAG, "Stop generation error", t);
        }

        String msgId = currentMessageId;
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onGenerationStopped(msgId);
            }
        });
        resetGeneration();
    }

    // ========== 上下文管理 ==========

    private void executeClearContext(BridgeCallback callback) {
        executor.execute(() -> {
            try {
                if (aiService != null) {
                    aiService.chatClear();
                }
                mainHandler.post(() -> {
                    if (callback != null) callback.onContextCleared();
                });
            } catch (Throwable t) {
                AILogger.e(TAG, "Clear context error", t);
                mainHandler.post(() -> {
                    if (callback != null) callback.onContextCleared();
                });
            }
        });
    }

    private void executeInitContext(ChatCommand command, BridgeCallback callback) {
        executor.execute(() -> {
            try {
                String systemPrompt = command.systemPrompt != null ? command.systemPrompt :
                    "你是一个AI助手，请用中文回答。";
                boolean success = false;
                if (aiService != null) {
                    success = aiService.initChatContext(systemPrompt, systemPrompt, "");
                }
                boolean finalSuccess = success;
                mainHandler.post(() -> {
                    if (callback != null) callback.onContextInitialized(finalSuccess);
                });
            } catch (Throwable t) {
                AILogger.e(TAG, "Init context error", t);
                mainHandler.post(() -> {
                    if (callback != null) callback.onContextInitialized(false);
                });
            }
        });
    }

    // ========== 模型生命周期 ==========

    private void executeInitModel(BridgeCallback callback) {
        executor.execute(() -> {
            try {
                boolean success = false;
                String modelName = "";
                if (aiService != null) {
                    if (!aiService.isInitialized()) {
                        success = aiService.initializeSafe();
                    } else {
                        success = true;
                    }
                    modelName = aiService.getCurrentModelName();
                }
                boolean finalSuccess = success;
                String finalModelName = modelName;
                mainHandler.post(() -> {
                    if (callback != null) callback.onModelInitialized(finalSuccess, finalModelName);
                });
            } catch (Throwable t) {
                AILogger.e(TAG, "Init model error", t);
                mainHandler.post(() -> {
                    if (callback != null) callback.onModelInitialized(false, "");
                });
            }
        });
    }

    private void executeReloadModel(BridgeCallback callback) {
        executor.execute(() -> {
            try {
                boolean success = false;
                if (aiService != null) {
                    success = aiService.reloadCurrentModelSafe();
                }
                boolean finalSuccess = success;
                mainHandler.post(() -> {
                    if (callback != null) callback.onModelReloaded(finalSuccess);
                });
            } catch (Throwable t) {
                AILogger.e(TAG, "Reload model error", t);
                mainHandler.post(() -> {
                    if (callback != null) callback.onModelReloaded(false);
                });
            }
        });
    }

    // ========== 状态查询 ==========

    private void executeGetModelInfo(BridgeCallback callback) {
        executor.execute(() -> {
            try {
                String modelName = aiService != null ? aiService.getCurrentModelName() : "";
                boolean initialized = aiService != null && aiService.isInitialized();
                int gpuLayers = safeGetGpuLayers();
                boolean gpuWorking = safeIsGpuWorking();
                boolean usingGPU = gpuLayers > 0 && gpuWorking;

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onModelInfo(modelName, initialized, usingGPU, gpuLayers);
                    }
                });
            } catch (Throwable t) {
                AILogger.e(TAG, "Get model info error", t);
                mainHandler.post(() -> {
                    if (callback != null) callback.onModelInfo("", false, false, 0);
                });
            }
        });
    }

    private void executeGetTokenCount(ChatCommand command, BridgeCallback callback) {
        executor.execute(() -> {
            try {
                int count = safeCountTokens(command.content);
                mainHandler.post(() -> {
                    if (callback != null) callback.onTokenCount(count);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onTokenCount(0);
                });
            }
        });
    }

    private void executeHandleMemoryPressure(ChatCommand command, BridgeCallback callback) {
        executor.execute(() -> {
            try {
                int result = safeHandleMemoryPressure(command.memoryLevel);
                mainHandler.post(() -> {
                    if (callback != null) callback.onMemoryPressureHandled(result);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onMemoryPressureHandled(0);
                });
            }
        });
    }

    private void executeCheckNativeState(BridgeCallback callback) {
        executor.execute(() -> {
            try {
                boolean valid = safeIsNativeStateValid();
                mainHandler.post(() -> {
                    if (callback != null) callback.onNativeStateChecked(valid);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onNativeStateChecked(false);
                });
            }
        });
    }

    // ========== 内部工具方法 ==========

    private boolean ensureModelInitialized(BridgeCallback callback, String messageId) {
        if (aiService == null) {
            notifyError(callback, messageId, "AI服务未初始化");
            resetGeneration();
            return false;
        }
        if (!aiService.isInitialized()) {
            AILogger.i(TAG, "Model not initialized, attempting safe init...");
            if (!aiService.initializeSafe()) {
                notifyError(callback, messageId, "AI服务初始化失败");
                resetGeneration();
                return false;
            }
        }
        return true;
    }

    private void ensureChatContext() {
        try {
            if (!LlamaHelper.isChatContextActive()) {
                AILogger.i(TAG, "Chat context not active, creating...");
                String systemPrompt = "你是一个AI助手，请用中文回答。";
                aiService.initChatContext(systemPrompt, systemPrompt, "");
            }
        } catch (Throwable t) {
            AILogger.e(TAG, "Ensure chat context error", t);
        }
    }

    private void resetGeneration() {
        isGenerating.set(false);
        currentMessageId = null;
        currentCallback = null;
        agentToolLoopCount = 0;
    }

    // ========== 安全的Native调用包装 ==========

    private int safeGetGpuLayers() {
        try { return LlamaHelper.getGPULayers(); }
        catch (Throwable t) { return 0; }
    }

    private boolean safeIsGpuWorking() {
        try { return LlamaHelper.isGPUWorking(); }
        catch (Throwable t) { return false; }
    }

    private int safeCountTokens(String text) {
        try { return LlamaHelper.countTokens(text); }
        catch (Throwable t) { return 0; }
    }

    private int safeHandleMemoryPressure(int level) {
        try { return LlamaHelper.handleMemoryPressure(level); }
        catch (Throwable t) { return 0; }
    }

    private boolean safeIsNativeStateValid() {
        try { return LlamaHelper.isNativeStateValid(); }
        catch (Throwable t) { return false; }
    }

    // ========== 主线程通知 ==========

    private void notifyStarted(BridgeCallback callback, String messageId) {
        mainHandler.post(() -> {
            if (callback != null) callback.onGenerationStarted(messageId);
        });
    }

    private void notifyError(BridgeCallback callback, String messageId, String error) {
        mainHandler.post(() -> {
            if (callback != null) callback.onGenerationError(messageId, error);
        });
    }

    // ========== 状态查询方法（供UI直接调用，不经过命令） ==========

    public boolean isGenerating() { return isGenerating.get(); }
    public boolean isModelInitialized() {
        return aiService != null && aiService.isInitialized();
    }
    public boolean isChatContextActive() {
        try { return LlamaHelper.isChatContextActive(); }
        catch (Throwable t) { return false; }
    }
    public boolean isNativeStateValid() {
        return safeIsNativeStateValid();
    }
    public boolean isModelInMemory() {
        try { return LlamaHelper.isModelInitialized(); }
        catch (Throwable t) { return false; }
    }
    public String getCurrentModelName() {
        try { return aiService != null ? aiService.getCurrentModelName() : ""; }
        catch (Throwable t) { return ""; }
    }

    /**
     * 关闭桥接器
     */
    public void shutdown() {
        executeStopGeneration(null);
        executor.shutdown();
        AILogger.i(TAG, "Bridge shutdown");
    }

    // ========== Token回调实现 ==========

    /**
     * 普通生成的Token回调
     */
    private class BridgeTokenCallback implements LlamaHelper.TokenCallback {
        private final String messageId;
        private final BridgeCallback callback;
        private final StringBuilder fullResponse = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private int tokenCount = 0;

        BridgeTokenCallback(String messageId, BridgeCallback callback) {
            this.messageId = messageId;
            this.callback = callback;
        }

        @Override
        public void onToken(String token) {
            if (isCancelled) return;
            if (token != null) {
                fullResponse.append(token);
                tokenCount++;
                mainHandler.post(() -> {
                    if (callback != null) callback.onToken(messageId, token);
                });
            }
        }

        @Override
        public void onComplete(String fullText) {
            if (isCancelled) return;
            long elapsed = System.currentTimeMillis() - startTime;
            float tps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
            String content = fullText != null ? fullText : fullResponse.toString();
            int finalCount = tokenCount;
            long finalElapsed = elapsed;
            float finalTps = tps;

            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onGenerationComplete(messageId, content, finalCount, finalElapsed, finalTps);
                }
            });
            resetGeneration();
        }

        @Override
        public void onError(String error) {
            if (isCancelled) return;
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onGenerationError(messageId, error != null ? error : "Unknown error");
                }
            });
            resetGeneration();
        }
    }

    /**
     * Agent模式的Token回调 - 包含工具调用处理
     */
    private class AgentTokenCallback implements LlamaHelper.TokenCallback {
        private final String messageId;
        private final BridgeCallback callback;
        private final StringBuilder fullResponse = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private int tokenCount = 0;

        AgentTokenCallback(String messageId, BridgeCallback callback) {
            this.messageId = messageId;
            this.callback = callback;
        }

        @Override
        public void onToken(String token) {
            if (isCancelled) return;
            if (token != null) {
                fullResponse.append(token);
                tokenCount++;
                mainHandler.post(() -> {
                    if (callback != null) callback.onToken(messageId, token);
                });
            }
        }

        @Override
        public void onComplete(String fullText) {
            if (isCancelled) return;

            String content = fullText != null ? fullText : fullResponse.toString();

            // 检查是否有工具调用：
            // - Qwen3 系：<|tool_call_begin|> 标记
            // - Qwen3.5 系：<tool_call> 块内 <function=...> XML 参数形态（无 tool_call_begin 标记）
            // - 通用/降级：<tool_call> 块内 JSON 形态（含 "name" 字段）
            boolean hasToolCallMarker = content.contains("<|tool_call_begin|>")
                    || content.contains("<function=")
                    || (content.contains("<tool_call>") && content.contains("\"name\""));
            if (hasToolCallMarker && agentService != null &&
                agentToolLoopCount < agentService.getMaxToolLoops()) {

                handleAgentToolCall(content, messageId, callback);
            } else {
                // 普通完成
                long elapsed = System.currentTimeMillis() - startTime;
                float tps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
                int finalCount = tokenCount;
                long finalElapsed = elapsed;
                float finalTps = tps;
                String finalContent = content;

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onGenerationComplete(messageId, finalContent, finalCount, finalElapsed, finalTps);
                    }
                });
                resetGeneration();
            }
        }

        @Override
        public void onError(String error) {
            if (isCancelled) return;
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onGenerationError(messageId, error != null ? error : "Unknown error");
                }
            });
            resetGeneration();
        }

        private void handleAgentToolCall(String content, String messageId, BridgeCallback callback) {
            try {
                List<AgentService.ToolCall> toolCalls = agentService.parseToolCalls(content);
                if (toolCalls == null || toolCalls.isEmpty()) {
                    // 解析失败，直接返回内容
                    completeAgentGeneration(content, messageId, callback);
                    return;
                }

                agentToolLoopCount++;

                for (AgentService.ToolCall call : toolCalls) {
                    // 通知UI工具调用开始
                    String toolName = call.name;
                    String toolArgs = call.arguments;
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onToolCallStart(messageId, toolName, toolArgs);
                        }
                    });

                    // 执行工具
                    AgentService.ToolResult result = agentService.executeTool(call);
                    boolean success = result != null && result.success;
                    String resultStr = result != null && result.result != null ? result.result : "";

                    // 通知UI工具调用完成
                    String finalResultStr = resultStr;
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onToolCallComplete(messageId, toolName, success, finalResultStr);
                        }
                    });

                    // 将工具结果送回模型继续生成
                    if (result != null) {
                        String toolResultMsg = agentService.formatToolResultForContext(result);
                        aiService.chatSend(toolResultMsg, aiConfig.getMaxTokens(), false,
                            new AgentTokenCallback(messageId, callback));
                        return; // 等待新的回调
                    }
                }

                // 所有工具调用完成，直接返回
                completeAgentGeneration(content, messageId, callback);

            } catch (Throwable t) {
                AILogger.e(TAG, "Agent tool call error", t);
                completeAgentGeneration(content, messageId, callback);
            }
        }

        private void completeAgentGeneration(String content, String messageId, BridgeCallback callback) {
            long elapsed = System.currentTimeMillis() - startTime;
            float tps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
            int finalCount = tokenCount;
            long finalElapsed = elapsed;
            float finalTps = tps;
            String finalContent = content;

            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onGenerationComplete(messageId, finalContent, finalCount, finalElapsed, finalTps);
                }
            });
            resetGeneration();
        }
    }

    /**
     * Agent 模式 chatJson 回调（原生工具链路，利用 C++ common_chat_parse 解析结果）：
     * - token -> 正文流式；thinking/reasoning -> 思考区（onThinkingUpdate）
     * - tool_call -> 收集（C++ 已解析并只发 arguments 闭合完整的调用）+ onToolCallStart
     * - complete -> 有工具调用则执行工具、回填 role=tool 历史并发起下一轮；否则最终完成
     * - error -> onGenerationError
     * 每轮 chatJson 使用新的 AgentJsonCallback 实例，状态（正文/工具列表）按轮隔离。
     */
    private class AgentJsonCallback implements LlamaHelper.JsonCallback {
        private final String messageId;
        private final BridgeCallback callback;
        private final StringBuilder bodyBuf = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private int tokenCount = 0;
        private final List<com.oilquiz.app.ai.service.OnlineInferenceService.ToolCallInfo> toolCalls =
                new java.util.ArrayList<>();
        private boolean toolCallsHandled = false;

        AgentJsonCallback(String messageId, BridgeCallback callback) {
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
                    case "thinking":
                    case "reasoning": {
                        String tk = event.optString("content", "");
                        if (!tk.isEmpty()) {
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onThinkingUpdate(messageId, 1, "thinking", "思考", tk, 0);
                                }
                            });
                        }
                        break;
                    }
                    case "tool_call": {
                        // C++ common_chat_parse 解析出的原生工具调用（arguments 已闭合）
                        com.oilquiz.app.ai.service.OnlineInferenceService.ToolCallInfo tc =
                                new com.oilquiz.app.ai.service.OnlineInferenceService.ToolCallInfo();
                        tc.id = event.optString("id", "");
                        if (tc.id == null || tc.id.isEmpty()) {
                            tc.id = "call_" + System.nanoTime();   // Qwen3.5 XML 无 id，生成一个供 tool 消息回填
                        }
                        tc.name = event.optString("name", "");
                        tc.arguments = event.optString("arguments", "{}");
                        if (tc.name.isEmpty()) break;
                        toolCalls.add(tc);
                        final String toolName = tc.name;
                        final String toolArgs = tc.arguments;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onToolCallStart(messageId, toolName, toolArgs);
                        });
                        break;
                    }
                    case "complete": {
                        String content = event.optString("content", "");
                        final String finalContent = content != null && !content.isEmpty()
                                ? content : bodyBuf.toString();
                        if (!toolCalls.isEmpty() && !toolCallsHandled) {
                            toolCallsHandled = true;
                            handleNativeToolCalls(finalContent, messageId, callback);
                            return;
                        }
                        // 最终回答（本轮无工具调用）
                        long elapsed = System.currentTimeMillis() - startTime;
                        float tps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
                        final int finalCount = tokenCount;
                        final long finalElapsed = elapsed;
                        final float finalTps = tps;
                        final String finalAnswer = finalContent;
                        mainHandler.post(() -> {
                            if (callback != null) {
                                callback.onGenerationComplete(messageId, finalAnswer, finalCount, finalElapsed, finalTps);
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
                        break;
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Agent chatJson event parse error: " + e.getMessage());
            }
        }

        /**
         * 执行 C++ 解析出的工具调用并驱动下一轮：
         * 1. 回填 assistant tool_calls 消息（content 为该轮正文）
         * 2. 逐个 executeTool，通知 UI，回填 role=tool 结果消息
         * 3. 发起下一轮 chatJson（无新 user 消息，模型基于工具结果继续）
         */
        private void handleNativeToolCalls(String content, String messageId, BridgeCallback callback) {
            try {
                if (agentService == null || agentToolLoopCount >= agentService.getMaxToolLoops()) {
                    AILogger.w(TAG, "Agent tool loop limit reached: " + agentToolLoopCount);
                    completeNativeGeneration(content, messageId, callback);
                    return;
                }
                agentToolLoopCount++;

                // 1. assistant tool_calls 历史
                appendChatJsonHistoryToolCalls(toolCalls, content);

                // 2. 逐个执行工具 + 回填 tool 结果
                for (com.oilquiz.app.ai.service.OnlineInferenceService.ToolCallInfo tc : toolCalls) {
                    if (isCancelled) return;
                    AgentService.ToolCall call = new AgentService.ToolCall(tc.name, tc.arguments);
                    AgentService.ToolResult result = agentService.executeTool(call);
                    boolean success = result != null && result.success;
                    String resultStr = result != null && result.result != null ? result.result : "";
                    appendChatJsonHistoryToolResult(tc.id, tc.name, resultStr);
                    final String toolName = tc.name;
                    final String finalResultStr = resultStr;
                    final boolean finalSuccess = success;
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onToolCallComplete(messageId, toolName, finalSuccess, finalResultStr);
                        }
                    });
                }

                // 3. 下一轮（模型基于工具结果继续生成）
                if (isCancelled) return;
                String nextReq = buildAgentChatJsonRequest(null, aiConfig.getMaxTokens());
                if (nextReq == null) {
                    completeNativeGeneration(content, messageId, callback);
                    return;
                }
                LlamaHelper.chatJson(nextReq, new AgentJsonCallback(messageId, callback));
            } catch (Throwable t) {
                AILogger.e(TAG, "Agent native tool call error", t);
                completeNativeGeneration(content, messageId, callback);
            }
        }

        private void completeNativeGeneration(String content, String messageId, BridgeCallback callback) {
            long elapsed = System.currentTimeMillis() - startTime;
            float tps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
            final int finalCount = tokenCount;
            final long finalElapsed = elapsed;
            final float finalTps = tps;
            final String finalContent = content;
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onGenerationComplete(messageId, finalContent, finalCount, finalElapsed, finalTps);
                }
            });
            resetGeneration();
        }
    }
}