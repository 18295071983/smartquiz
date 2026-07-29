package com.oilquiz.app.ai.chat.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.live.MutableChatList;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.refactor.AIInferenceCore;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * AIChatViewModel - AI对话视图模型
 * 
 * 功能：
 * 1. 管理聊天消息数据
 * 2. 处理消息发送和接收
 * 3. 管理模型状态
 * 4. 处理生成状态
 * 5. 自动保存历史记录
 */
@HiltViewModel
public class AIChatViewModel extends AndroidViewModel {

    private static final String TAG = "AIChatViewModel";

    // 核心服务（通过 Hilt 注入）
    @Inject
    public AIService aiService;
    @Inject
    public InferenceRouter inferenceRouter;
    @Inject
    public OnlineModelManager onlineModelManager;
    @Inject
    public ChatHistoryManager chatHistoryManager;
    @Inject
    public AIConfig aiConfig;
    @Inject
    public ExecutorService executor;

    // 数据源
    private final MutableChatList chatMessages = new MutableChatList();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 状态
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private String currentModelName;
    private boolean isUsingOnlineModel;

    // LiveData
    private final MutableLiveData<String> modelNameLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> generatingStateLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> initializationLiveData = new MutableLiveData<>(false);

    // 流式生成状态
    private StringBuilder currentStreamingContent;
    private StringBuilder currentThinkingContent;
    private boolean isInThinking = false;
    private int currentStreamingMessageIndex = -1;

    @Inject
    public AIChatViewModel(@NonNull Application application) {
        super(application);
    }

    // ========== 初始化 ==========

    /**
     * 初始化 ViewModel（依赖已通过 Hilt 注入）
     */
    public void initialize() {
        if (isInitialized.get()) return;

        try {
        executor.execute(() -> {
            try {
                // 验证注入的依赖
                if (aiService == null) {
                    mainHandler.post(() -> errorLiveData.setValue("AI服务初始化失败"));
                    return;
                }

                // 同步在线模型
                syncOnlineModels();

                // 更新模型状态
                updateModelState();

                // 加载聊天历史
                loadChatHistory();

                isInitialized.set(true);
                mainHandler.post(() -> {
                    initializationLiveData.setValue(true);
                    modelNameLiveData.setValue(currentModelName);
                });

                AILogger.i(TAG, "ViewModel initialized successfully");

            } catch (Exception e) {
                AILogger.e(TAG, "Failed to initialize ViewModel", e);
                mainHandler.post(() -> errorLiveData.setValue("初始化失败: " + e.getMessage()));
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.e(TAG, "Executor rejected initialization task", e);
            mainHandler.post(() -> errorLiveData.setValue("初始化失败: 线程池已关闭"));
        }
    }

    private void syncOnlineModels() {
        if (onlineModelManager == null) return;
        try {
            int imported = onlineModelManager.importFromAPIKeyManager();
            if (imported > 0) {
                AILogger.i(TAG, "Synced " + imported + " online model configs");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to sync online models: " + e.getMessage());
        }
    }

    private void updateModelState() {
        if (inferenceRouter != null) {
            currentModelName = inferenceRouter.getCurrentModelName();
            isUsingOnlineModel = inferenceRouter.isUsingOnlineModel();
        } else if (aiService != null) {
            currentModelName = aiService.getCurrentModelName();
            isUsingOnlineModel = false;
        }
    }

    private void loadChatHistory() {
        if (chatHistoryManager == null) return;
        try {
            List<ChatMessage> loaded = chatHistoryManager.loadAIChatHistory();
            if (loaded != null && !loaded.isEmpty()) {
                chatMessages.addAll(loaded);
                AILogger.i(TAG, "Loaded " + loaded.size() + " chat messages");
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to load chat history: " + e.getMessage());
        }
    }

    // ========== 消息操作 ==========

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        if (content == null || content.isEmpty()) return;

        ChatMessage message = ChatMessage.createUserMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            System.currentTimeMillis()
        );
        chatMessages.add(message);
        saveHistoryAsync();
    }

    /**
     * 添加 AI 消息
     */
    public void addAIMessage(String content) {
        if (content == null) content = "";

        ChatMessage message = ChatMessage.createAIMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            System.currentTimeMillis(),
            null, 0, 0
        );
        chatMessages.add(message);
        saveHistoryAsync();
    }

    /**
     * 添加系统消息
     */
    public void addSystemMessage(String content) {
        if (content == null || content.isEmpty()) return;

        ChatMessage message = ChatMessage.createSystemMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            ChatMessage.SystemMessageType.INFO,
            System.currentTimeMillis()
        );
        chatMessages.add(message);
    }

    /**
     * 添加错误消息
     */
    public void addErrorMessage(String title, String detail, boolean retryable) {
        ChatMessage message = ChatMessage.createErrorMessage(title, detail, retryable);
        chatMessages.add(message);
    }

    /**
     * 清空聊天历史
     */
    public void clearChatHistory() {
        chatMessages.clear();
        if (chatHistoryManager != null) {
            try {
                executor.execute(() -> chatHistoryManager.clearAIChatHistory());
            } catch (java.util.concurrent.RejectedExecutionException e) {
                AILogger.w(TAG, "Executor rejected clear history task: " + e.getMessage());
            }
        }
        if (aiService != null) {
            aiService.chatClear();
        }
    }

    // ========== 消息发送 ==========

    /**
     * 发送消息
     */
    public void sendMessage(String content) {
        if (content == null || content.isEmpty()) return;
        if (isGenerating.get()) {
            mainHandler.post(() -> errorLiveData.setValue("正在生成中，请稍候"));
            return;
        }

        // 添加用户消息
        addUserMessage(content);

        // 开始生成
        startGeneration(content);
    }

    private void startGeneration(String message) {
        isGenerating.set(true);
        mainHandler.post(() -> generatingStateLiveData.setValue(true));

        // 创建 AI 消息占位
        String messageId = java.util.UUID.randomUUID().toString();
        ChatMessage aiMessage = ChatMessage.createAIMessage(messageId, "", System.currentTimeMillis(), null, 0, 0);
        aiMessage.inferenceProgress = new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.INITIALIZING);
        aiMessage.status = ChatMessage.MessageStatus.GENERATING;
        chatMessages.add(aiMessage);
        currentStreamingMessageIndex = chatMessages.size() - 1;

        // 初始化流式内容
        currentStreamingContent = new StringBuilder();
        currentThinkingContent = new StringBuilder();

        // 根据模型类型选择推理方式
        if (isUsingOnlineModel && inferenceRouter != null) {
            startOnlineInference(message);
        } else if (aiService != null) {
            startLocalInference(message);
        } else {
            mainHandler.post(() -> {
                errorLiveData.setValue("AI服务未初始化");
                endGeneration();
            });
        }
    }

    private void startOnlineInference(String message) {
        try {
        executor.execute(() -> {
            try {
                AIInferenceCore.InferenceConfig config = new AIInferenceCore.InferenceConfig();
                config.maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 8192;
                config.temperature = 0.7f;

                // 构建历史上下文
                List<ChatMessage> history = new ArrayList<>();
                for (ChatMessage msg : chatMessages.toImmutableList()) {
                    if (msg.type == ChatMessage.MessageType.USER || msg.type == ChatMessage.MessageType.AI) {
                        history.add(msg);
                    }
                }
                if (history.size() > 20) {
                    history = history.subList(history.size() - 20, history.size());
                }
                config.history = history;

                inferenceRouter.generateStream(message, config, new StreamCallback() {
                    @Override
                    public void onStart() {
                        updateInferencePhase(ChatMessage.InferencePhase.ENCODING);
                    }

                    @Override
                    public void onToken(String token) {
                        handleStreamingToken(token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        completeGeneration(fullText);
                    }

                    @Override
                    public void onError(String error) {
                        handleGenerationError(error);
                    }
                });
            } catch (Exception e) {
                AILogger.e(TAG, "Online inference failed", e);
                mainHandler.post(() -> handleGenerationError("推理失败: " + e.getMessage()));
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.e(TAG, "Executor rejected online inference task", e);
            handleGenerationError("线程池已关闭，请重启应用");
        }
    }

    private void startLocalInference(String message) {
        try {
        executor.execute(() -> {
            try {
                int maxTokens = aiConfig != null ? aiConfig.getMaxTokens() : 1024;
                aiService.chatSend(message, maxTokens, false, new LlamaHelper.TokenCallback() {
                    @Override
                    public void onToken(String token) {
                        handleStreamingToken(token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        completeGeneration(fullText);
                    }

                    @Override
                    public void onError(String error) {
                        handleGenerationError(error);
                    }
                });
            } catch (Exception e) {
                AILogger.e(TAG, "Local inference failed", e);
                mainHandler.post(() -> handleGenerationError("推理失败: " + e.getMessage()));
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.e(TAG, "Executor rejected local inference task", e);
            handleGenerationError("线程池已关闭，请重启应用");
        }
    }

    // ========== 流式处理 ==========

    private void handleStreamingToken(String token) {
        if (token == null || token.isEmpty()) return;

        // 处理思考标签
        if (token.equals("[THINK_BEGIN]")) {
            isInThinking = true;
            return;
        }
        if (token.equals("[THINK_END]")) {
            isInThinking = false;
            updateMessageThinkingContent();
            return;
        }

        // 处理内容
        if (isInThinking) {
            currentThinkingContent.append(token);
        } else {
            currentStreamingContent.append(token);
        }

        // 更新 UI
        mainHandler.post(this::updateStreamingMessage);
    }

    private void updateStreamingMessage() {
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatMessages.size()) {
            return;
        }

        ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
        if (currentStreamingContent.length() > 0) {
            msg.content = currentStreamingContent.toString();
        }
        if (currentThinkingContent.length() > 0) {
            msg.thinkingContent = currentThinkingContent.toString();
        }
        msg.status = ChatMessage.MessageStatus.GENERATING;

        chatMessages.notifyItemChanged(currentStreamingMessageIndex);
    }

    private void updateMessageThinkingContent() {
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatMessages.size()) {
            return;
        }

        ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
        msg.thinkingContent = currentThinkingContent.toString();
        chatMessages.notifyItemChanged(currentStreamingMessageIndex);
    }

    private void updateInferencePhase(ChatMessage.InferencePhase phase) {
        if (currentStreamingMessageIndex < 0 || currentStreamingMessageIndex >= chatMessages.size()) {
            return;
        }

        ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
        msg.inferenceProgress = new ChatMessage.InferenceProgress(phase);
        chatMessages.notifyItemChanged(currentStreamingMessageIndex);
    }

    private void completeGeneration(String fullText) {
        mainHandler.post(() -> {
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatMessages.size()) {
                ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
                msg.content = fullText != null ? fullText : currentStreamingContent.toString();
                msg.status = ChatMessage.MessageStatus.COMPLETED;
                msg.inferenceProgress = null;
                chatMessages.notifyItemChanged(currentStreamingMessageIndex);
            }

            endGeneration();
            saveHistoryAsync();
        });
    }

    private void handleGenerationError(String error) {
        mainHandler.post(() -> {
            if (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < chatMessages.size()) {
                ChatMessage msg = chatMessages.get(currentStreamingMessageIndex);
                msg.content = error != null ? "生成失败: " + error : "生成失败";
                msg.status = ChatMessage.MessageStatus.FAILED;
                msg.inferenceProgress = null;
                chatMessages.notifyItemChanged(currentStreamingMessageIndex);
            }

            endGeneration();
            errorLiveData.setValue(error);
        });
    }

    private void endGeneration() {
        isGenerating.set(false);
        currentStreamingContent = null;
        currentThinkingContent = null;
        isInThinking = false;
        currentStreamingMessageIndex = -1;
        mainHandler.post(() -> generatingStateLiveData.setValue(false));
    }

    // ========== 模型操作 ==========

    /**
     * 停止生成
     */
    public void stopGeneration() {
        if (aiService != null) {
            aiService.chatStop();
        }
        endGeneration();
    }

    /**
     * 获取当前模型名称
     */
    public String getCurrentModelName() {
        return currentModelName;
    }

    /**
     * 是否使用在线模型
     */
    public boolean isUsingOnlineModel() {
        return isUsingOnlineModel;
    }

    /**
     * AI 服务是否已初始化
     */
    public boolean isAIServiceInitialized() {
        return aiService != null && aiService.isInitialized();
    }

    // ========== 数据保存 ==========

    private void saveHistoryAsync() {
        if (chatHistoryManager == null) return;
        try {
        executor.execute(() -> {
            try {
                List<ChatMessage> copy = chatMessages.toMutableList();
                chatHistoryManager.saveAIChatHistory(copy);
            } catch (Exception e) {
                AILogger.w(TAG, "Failed to save chat history: " + e.getMessage());
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            AILogger.w(TAG, "Executor rejected save history task: " + e.getMessage());
        }
    }

    // ========== LiveData 获取 ==========

    /**
     * 获取聊天消息列表（可观察）
     */
    public LiveData<List<ChatMessage>> getChatMessages() {
        return chatMessages.asLiveData();
    }

    /**
     * 获取聊天消息列表（用于 Adapter）
     */
    public MutableChatList getChatMessageList() {
        return chatMessages;
    }

    /**
     * 获取模型名称（可观察）
     */
    public LiveData<String> getModelName() {
        return modelNameLiveData;
    }

    /**
     * 获取生成状态（可观察）
     */
    public LiveData<Boolean> isGenerating() {
        return generatingStateLiveData;
    }

    /**
     * 获取错误信息（可观察）
     */
    public LiveData<String> getError() {
        return errorLiveData;
    }

    /**
     * 获取初始化状态（可观察）
     */
    public LiveData<Boolean> isInitialized() {
        return initializationLiveData;
    }

    // ========== 清理 ==========

    @Override
    protected void onCleared() {
        super.onCleared();
        // 注意：executor 是 @Singleton 共享实例，不能在此关闭
        // 否则会导致后续创建的 ViewModel 无法使用 executor（RejectedExecutionException）
        AILogger.i(TAG, "ViewModel cleared");
    }
}
