package com.oilquiz.app.ai.chat.coordination;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.ai.stats.TokenStatsManager;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AI对话协调器 - 统一管理所有数据源和状态
 * 
 * 职责：
 * 1. 管理核心服务引用（AIService, InferenceRouter, OnlineModelManager）
 * 2. 统一管理聊天历史数据
 * 3. 提供模型状态查询
 * 4. 协调各模块之间的数据访问
 * 5. 处理数据变更通知
 */
public class AIChatCoordinator {

    private static final String TAG = "AIChatCoordinator";

    // ========== 核心服务 ==========
    private final Context context;
    private final Handler mainHandler;
    private AIService aiService;
    private InferenceRouter inferenceRouter;
    private OnlineModelManager onlineModelManager;
    private ChatHistoryManager chatHistoryManager;
    private AIConfig aiConfig;

    // ========== 数据源 ==========
    private final List<ChatMessage> chatHistory = new CopyOnWriteArrayList<>();
    private boolean isInitialized = false;

    // ========== 状态 ==========
    private boolean isGenerating = false;
    private String currentModelName;
    private boolean isUsingOnlineModel;

    // ========== 监听器 ==========
    public interface CoordinatorListener {
        void onChatHistoryChanged();
        void onModelChanged(String modelName, boolean isOnline);
        void onGeneratingStateChanged(boolean isGenerating);
        void onServiceStateChanged(AIServiceState.ServiceStage stage, String message);
    }

    private final List<CoordinatorListener> listeners = new CopyOnWriteArrayList<>();

    public AIChatCoordinator(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ========== 初始化 ==========

    /**
     * 初始化协调器，必须在使用前调用
     */
    public void initialize() {
        if (isInitialized) return;

        try {
            // 初始化核心服务
            aiService = AIService.getInstance(context);
            inferenceRouter = InferenceRouter.getInstance(context);
            onlineModelManager = OnlineModelManager.getInstance(context);
            chatHistoryManager = new ChatHistoryManager(context);
            aiConfig = new AIConfig(context);

            // 同步在线模型配置
            syncOnlineModels();

            // 更新模型状态
            updateModelState();

            isInitialized = true;
            AILogger.i(TAG, "AIChatCoordinator initialized");
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to initialize AIChatCoordinator", e);
        }
    }

    /**
     * 同步在线模型配置
     */
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

    /**
     * 更新模型状态
     */
    private void updateModelState() {
        if (inferenceRouter != null) {
            currentModelName = inferenceRouter.getCurrentModelName();
            isUsingOnlineModel = inferenceRouter.isUsingOnlineModel();
        } else if (aiService != null) {
            currentModelName = aiService.getCurrentModelName();
            isUsingOnlineModel = false;
        }
    }

    // ========== 核心服务访问 ==========

    public AIService getAIService() {
        return aiService;
    }

    public InferenceRouter getInferenceRouter() {
        return inferenceRouter;
    }

    public OnlineModelManager getOnlineModelManager() {
        return onlineModelManager;
    }

    public AIConfig getAIConfig() {
        return aiConfig;
    }

    public ChatHistoryManager getChatHistoryManager() {
        return chatHistoryManager;
    }

    // ========== 聊天历史管理 ==========

    /**
     * 获取聊天历史（只读副本）
     */
    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
    }

    /**
     * 获取聊天历史（原始引用，用于UI绑定）
     */
    public List<ChatMessage> getChatHistoryRef() {
        return chatHistory;
    }

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        ChatMessage msg = ChatMessage.createUserMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            System.currentTimeMillis()
        );
        chatHistory.add(msg);
        notifyChatHistoryChanged();
        saveHistoryAsync();
    }

    /**
     * 添加AI消息
     */
    public void addAIMessage(String content) {
        ChatMessage msg = ChatMessage.createAIMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            System.currentTimeMillis(),
            null, 0, 0
        );
        chatHistory.add(msg);
        notifyChatHistoryChanged();
        saveHistoryAsync();
    }

    /**
     * 添加系统消息
     */
    public void addSystemMessage(String content) {
        ChatMessage msg = ChatMessage.createSystemMessage(
            java.util.UUID.randomUUID().toString(),
            content,
            ChatMessage.SystemMessageType.INFO,
            System.currentTimeMillis()
        );
        chatHistory.add(msg);
        notifyChatHistoryChanged();
    }

    /**
     * 添加错误消息
     */
    public void addErrorMessage(String title, String detail, boolean retryable) {
        ChatMessage msg = ChatMessage.createErrorMessage(title, detail, retryable);
        chatHistory.add(msg);
        notifyChatHistoryChanged();
    }

    /**
     * 更新消息内容
     */
    public void updateMessageContent(int index, String content) {
        if (index >= 0 && index < chatHistory.size()) {
            chatHistory.get(index).content = content;
            notifyChatHistoryChanged();
        }
    }

    /**
     * 删除消息
     */
    public void removeMessage(int index) {
        if (index >= 0 && index < chatHistory.size()) {
            chatHistory.remove(index);
            notifyChatHistoryChanged();
            saveHistoryAsync();
        }
    }

    /**
     * 清空聊天历史
     */
    public void clearChatHistory() {
        chatHistory.clear();
        notifyChatHistoryChanged();
        if (chatHistoryManager != null) {
            new Thread(() -> chatHistoryManager.clearAIChatHistory()).start();
        }
        if (aiService != null) {
            aiService.chatClear();
        }
    }

    /**
     * 加载聊天历史
     */
    public void loadChatHistory() {
        if (chatHistoryManager == null) return;
        new Thread(() -> {
            List<ChatMessage> loaded = chatHistoryManager.loadAIChatHistory();
            if (loaded != null && !loaded.isEmpty()) {
                chatHistory.addAll(loaded);
                mainHandler.post(this::notifyChatHistoryChanged);
            }
        }).start();
    }

    /**
     * 异步保存聊天历史
     */
    public void saveHistoryAsync() {
        if (chatHistoryManager == null) return;
        new Thread(() -> {
            List<ChatMessage> copy = new ArrayList<>(chatHistory);
            chatHistoryManager.saveAIChatHistory(copy);
        }).start();
    }

    // ========== 模型状态 ==========

    /**
     * 获取当前模型名称
     */
    public String getCurrentModelName() {
        updateModelState();
        return currentModelName;
    }

    /**
     * 是否使用在线模型
     */
    public boolean isUsingOnlineModel() {
        updateModelState();
        return isUsingOnlineModel;
    }

    /**
     * AI服务是否已初始化
     */
    public boolean isAIServiceInitialized() {
        return aiService != null && aiService.isInitialized();
    }

    /**
     * 获取AI服务状态
     */
    public AIServiceState getAIServiceState() {
        return aiService != null ? aiService.getServiceState() : null;
    }

    /**
     * 获取当前服务阶段
     */
    public AIServiceState.ServiceStage getCurrentStage() {
        AIServiceState state = getAIServiceState();
        return state != null ? state.getCurrentStage() : AIServiceState.ServiceStage.UNINITIALIZED;
    }

    // ========== 生成控制 ==========

    /**
     * 是否正在生成
     */
    public boolean isGenerating() {
        return isGenerating;
    }

    /**
     * 设置生成状态
     */
    public void setGenerating(boolean generating) {
        if (this.isGenerating != generating) {
            this.isGenerating = generating;
            mainHandler.post(() -> {
                for (CoordinatorListener listener : listeners) {
                    listener.onGeneratingStateChanged(generating);
                }
            });
        }
    }

    /**
     * 停止生成
     */
    public void stopGeneration() {
        if (aiService != null) {
            aiService.chatStop();
        }
        setGenerating(false);
    }

    // ========== 监听器管理 ==========

    public void addListener(CoordinatorListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(CoordinatorListener listener) {
        listeners.remove(listener);
    }

    private void notifyChatHistoryChanged() {
        mainHandler.post(() -> {
            for (CoordinatorListener listener : listeners) {
                listener.onChatHistoryChanged();
            }
        });
    }

    private void notifyModelChanged() {
        mainHandler.post(() -> {
            for (CoordinatorListener listener : listeners) {
                listener.onModelChanged(currentModelName, isUsingOnlineModel);
            }
        });
    }

    // ========== 清理 ==========

    /**
     * 释放资源
     */
    public void cleanup() {
        listeners.clear();
        // 不清空 chatHistory，保留历史记录以便下次进入时加载
        isInitialized = false;
        AILogger.i(TAG, "AIChatCoordinator cleaned up");
    }
}
