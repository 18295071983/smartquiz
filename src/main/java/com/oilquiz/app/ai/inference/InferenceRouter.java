package com.oilquiz.app.ai.inference;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.jni.ChatRequest;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.model.InferenceType;
import com.oilquiz.app.ai.model.ModelManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.refactor.AIInferenceCore;
import com.oilquiz.app.ai.refactor.AIInferenceCore.InferenceConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.ai.util.PromptBuilder;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推理路由调度器
 * 根据当前激活的模型类型，自动路由到本地或在线推理
 */
public class InferenceRouter {

    private static final String TAG = "InferenceRouter";

    private static volatile InferenceRouter INSTANCE;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    private OnlineModelManager onlineModelManager;
    private AIService aiService;
    private OnlineInferenceService onlineInferenceService;
    private AIInferenceCore inferenceCore;

    private InferenceRouter(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Inference-Router-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static InferenceRouter getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (InferenceRouter.class) {
                if (INSTANCE == null) {
                    INSTANCE = new InferenceRouter(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 获取当前推理类型
     */
    public InferenceType getCurrentInferenceType() {
        ensureServicesInitialized();
        
        // 检查是否有激活的在线模型
        OnlineModelManager.OnlineModelConfig activeOnline = onlineModelManager.getActiveModel();
        if (activeOnline != null) {
            return InferenceType.ONLINE;
        }
        
        // 检查是否有激活的本地模型
        if (aiService != null && aiService.isInitialized()) {
            return InferenceType.LOCAL;
        }
        
        return InferenceType.LOCAL; // 默认本地
    }

    /**
     * 获取当前模型名称（优先返回用户选择的实际模型名，而非 API 配置名）
     */
    public String getCurrentModelName() {
        ensureServicesInitialized();
        
        // 检查是否有激活的在线模型
        OnlineModelManager.OnlineModelConfig activeOnline = onlineModelManager.getActiveModel();
        if (activeOnline != null) {
            // 优先返回用户选择的实际模型名（如 gpt-4-turbo），而非 API 配置名
            if (activeOnline.selectedModel != null && !activeOnline.selectedModel.isEmpty()) {
                return activeOnline.selectedModel;
            }
            if (activeOnline.modelName != null && !activeOnline.modelName.isEmpty()) {
                return activeOnline.modelName;
            }
            return activeOnline.name;
        }
        
        // 检查是否有激活的本地模型
        if (aiService != null && aiService.isInitialized()) {
            return aiService.getCurrentModelName();
        }
        
        return null;
    }

    /**
     * 获取当前推理类型描述
     */
    public String getCurrentInferenceTypeDisplay() {
        return getCurrentInferenceType().getDisplayName();
    }

    /**
     * 检查当前模型是否可用
     */
    public boolean isCurrentModelAvailable() {
        ensureServicesInitialized();
        
        InferenceType type = getCurrentInferenceType();
        
        if (type == InferenceType.ONLINE) {
            OnlineModelManager.OnlineModelConfig config = onlineModelManager.getActiveModel();
            return config != null && config.enabled;
        } else {
            return aiService != null && aiService.isInitialized();
        }
    }

    /**
     * 统一生成接口 - 异步
     */
    public CompletableFuture<String> generate(String prompt, InferenceConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            ensureServicesInitialized();
            
            InferenceType type = getCurrentInferenceType();
            
            if (type == InferenceType.ONLINE) {
                return generateOnline(prompt, config);
            } else {
                return generateLocal(prompt, config);
            }
        }, executor);
    }

    /**
     * 统一生成接口 - 同步
     */
    public String generateSync(String prompt, InferenceConfig config) {
        try {
            return generate(prompt, config).get();
        } catch (Exception e) {
            AILogger.e(TAG, "Sync generate failed: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 统一流式生成接口
     */
    public void generateStream(String prompt, InferenceConfig config, StreamCallback callback) {
        ensureServicesInitialized();
        
        InferenceType type = getCurrentInferenceType();
        
        if (type == InferenceType.ONLINE) {
            generateOnlineStream(prompt, config, callback);
        } else {
            generateLocalStream(prompt, config, callback);
        }
    }

    /**
     * 切换模型
     */
    public boolean switchModel(String modelId) {
        ensureServicesInitialized();
        
        // 检查是否是在线模型
        OnlineModelManager.OnlineModelConfig onlineConfig = onlineModelManager.getModel(modelId);
        if (onlineConfig != null) {
            onlineModelManager.setActiveModel(modelId);
            return true;
        }
        
        // 尝试作为本地模型处理
        if (aiService != null) {
            return aiService.switchModel(modelId);
        }
        
        return false;
    }

    /**
     * 停止当前推理
     */
    public void stopInference() {
        if (LlamaHelper.isModelInitialized()) {
            LlamaHelper.stopGeneration();
        }
    }

    /**
     * 设置激活的在线模型
     */
    public void setActiveOnlineModel(String modelId) {
        onlineModelManager.setActiveModel(modelId);
    }

    /**
     * 停止在线模型
     */
    public void stopOnlineModel() {
        onlineModelManager.stopActiveModel();
    }

    /**
     * 检查是否使用在线模型
     */
    public boolean isUsingOnlineModel() {
        return getCurrentInferenceType() == InferenceType.ONLINE;
    }

    /**
     * 检查是否使用本地模型
     */
    public boolean isUsingLocalModel() {
        return getCurrentInferenceType() == InferenceType.LOCAL;
    }

    // ========== 私有方法 ==========

    private void ensureServicesInitialized() {
        if (onlineModelManager == null) {
            onlineModelManager = OnlineModelManager.getInstance(context);
        }
        if (aiService == null) {
            aiService = AIService.getInstance(context);
        }
        if (onlineInferenceService == null) {
            onlineInferenceService = OnlineInferenceService.getInstance(context);
        }
        if (inferenceCore == null) {
            inferenceCore = AIInferenceCore.getInstance(context);
        }
    }

    /**
     * 本地模型生成
     */
    private String generateLocal(String prompt, InferenceConfig config) {
        try {
            if (aiService != null && aiService.isInitialized()) {
                return aiService.generateSync(prompt, config.maxTokens);
            } else {
                // 回退到直接使用 LlamaHelper，使用消息列表让 native 层自动适配模型格式
                List<PromptBuilder.Message> messages = new ArrayList<>();
                messages.add(new PromptBuilder.Message("system", "你是一个乐于助人的AI助手。请用中文回答用户的问题。"));
                if (config.history != null) {
                    for (ChatMessage msg : config.history) {
                        String role = msg.isAIMessage() ? "assistant" : "user";
                        messages.add(new PromptBuilder.Message(role, msg.content));
                    }
                }
                messages.add(new PromptBuilder.Message("user", prompt));
                return LlamaHelper.generate(messages, config.maxTokens, config.temperature);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Local generate failed: " + e.getMessage(), e);
            throw new RuntimeException("本地模型推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在线模型生成
     */
    private String generateOnline(String prompt, InferenceConfig config) {
        try {
            OnlineModelManager.OnlineModelConfig onlineConfig = onlineModelManager.getActiveModel();
            if (onlineConfig == null) {
                throw new IllegalStateException("没有激活的在线模型");
            }
            
            List<ChatMessage> history = config.history;
            return onlineInferenceService.generateSync(prompt, onlineConfig, history, config.maxTokens);
        } catch (Exception e) {
            AILogger.e(TAG, "Online generate failed: " + e.getMessage(), e);
            throw new RuntimeException("在线模型推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 本地模型流式生成
     */
    private void generateLocalStream(String prompt, InferenceConfig config, StreamCallback callback) {
        callback.onStart();
        
        StringBuilder fullText = new StringBuilder();
        final boolean[] stopped = {false};
        
        try {
            // 使用 AIService 的流式生成
            if (aiService != null && aiService.isInitialized()) {
                aiService.generateStream(prompt, config.maxTokens, new AIService.GenerateStreamCallback() {
                    @Override
                    public void onToken(String token) {
                        if (!stopped[0]) {
                            fullText.append(token);
                            mainHandler.post(() -> callback.onToken(token));
                        }
                    }

                    @Override
                    public void onSuccess(String fullText) {
                        mainHandler.post(() -> callback.onComplete(fullText));
                    }

                    @Override
                    public void onError(Exception error) {
                        mainHandler.post(() -> callback.onError(error.getMessage()));
                    }
                });
            } else {
                // 回退到直接使用 LlamaHelper，使用消息列表让 native 层自动适配模型格式
                List<PromptBuilder.Message> messages = new ArrayList<>();
                messages.add(new PromptBuilder.Message("system", "你是一个乐于助人的AI助手。请用中文回答用户的问题。"));
                if (config.history != null) {
                    for (ChatMessage msg : config.history) {
                        String role = msg.isAIMessage() ? "assistant" : "user";
                        messages.add(new PromptBuilder.Message(role, msg.content));
                    }
                }
                messages.add(new PromptBuilder.Message("user", prompt));

                LlamaHelper.generateStream(messages, config.maxTokens, config.temperature, 0.9f, 40, false,
                    new LlamaHelper.TokenCallback() {
                        @Override
                        public void onToken(String token) {
                            if (!stopped[0]) {
                                fullText.append(token);
                                mainHandler.post(() -> callback.onToken(token));
                            }
                        }

                        @Override
                        public void onComplete(String fullText) {
                            mainHandler.post(() -> callback.onComplete(fullText));
                        }

                        @Override
                        public void onError(String error) {
                            mainHandler.post(() -> callback.onError(error));
                        }
                    });
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Local stream generate failed: " + e.getMessage(), e);
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    }

    /**
     * 在线模型流式生成
     */
    private void generateOnlineStream(String prompt, InferenceConfig config, StreamCallback callback) {
        try {
            OnlineModelManager.OnlineModelConfig onlineConfig = onlineModelManager.getActiveModel();
            if (onlineConfig == null) {
                callback.onError("没有激活的在线模型");
                return;
            }
            
            List<ChatMessage> history = config.history;
            onlineInferenceService.generateStream(prompt, onlineConfig, history, config.maxTokens,
                new StreamCallback() {
                    @Override
                    public void onStart() {
                        callback.onStart();
                    }

                    @Override
                    public void onToken(String token) {
                        callback.onToken(token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        callback.onComplete(fullText);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }

                    @Override
                    public void onTokenStats(int promptTokens, int completionTokens) {
                        // 转发在线模型 API 返回的 Token 统计
                        callback.onTokenStats(promptTokens, completionTokens);
                    }
                });
        } catch (Exception e) {
            AILogger.e(TAG, "Online stream generate failed: " + e.getMessage(), e);
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    }

    /**
     * 关闭路由
     */
    public void shutdown() {
        executor.shutdown();
        if (onlineInferenceService != null) {
            onlineInferenceService.shutdown();
        }
    }
}