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

                // Agent模式使用普通生成，工具调用在回调中处理
                aiService.chatSend(command.content, command.maxTokens, false,
                    new AgentTokenCallback(command.messageId, callback));

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

            // 检查是否有工具调用
            if (content.contains("<|tool_call_begin|>") && agentService != null &&
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
}