package com.oilquiz.app.ai.refactor;

import android.content.Context;
import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.model.InferenceType;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AIInferenceCore {
    private static final String TAG = "AIInferenceCore";

    private final Context context;
    private final ExecutorService executor;
    private final Object inferenceLock = new Object();
    private InferenceRouter router;

    private static AIInferenceCore instance;

    private AIInferenceCore(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AI-Inference-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized AIInferenceCore getInstance(Context context) {
        if (instance == null) {
            instance = new AIInferenceCore(context);
        }
        return instance;
    }

    private InferenceRouter getRouter() {
        if (router == null) {
            router = InferenceRouter.getInstance(context);
        }
        return router;
    }

    public boolean isLocalAvailable() {
        try {
            return LlamaHelper.isModelInitialized();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查在线模型是否可用
     */
    public boolean isOnlineAvailable() {
        OnlineModelManager manager = OnlineModelManager.getInstance(context);
        return manager.getActiveModel() != null;
    }

    /**
     * 获取当前推理类型
     */
    public InferenceType getCurrentInferenceType() {
        return getRouter().getCurrentInferenceType();
    }

    /**
     * 检查当前模型是否可用
     */
    public boolean isCurrentModelAvailable() {
        return getRouter().isCurrentModelAvailable();
    }

    private boolean ensureModelReady() {
        if (LlamaHelper.isModelInitialized()) {
            return true;
        }

        AIService aiService = AIService.getInstance(context);
        if (aiService == null) {
            AILogger.e(TAG, "AIService instance is null, cannot auto-load model");
            return false;
        }

        if (aiService.isInitialized()) {
            return true;
        }

        AILogger.i(TAG, "Model not loaded, attempting auto-load via AIService...");

        String[] availableModels = aiService.getAvailableModels();
        if (availableModels == null || availableModels.length == 0) {
            AILogger.e(TAG, "No available models found for auto-load");
            return false;
        }

        String modelName = availableModels[0];
        AILogger.i(TAG, "Auto-loading model: " + modelName);

        boolean success = aiService.switchModel(modelName);
        if (success) {
            AILogger.i(TAG, "Auto-load model succeeded: " + modelName);
        } else {
            AILogger.e(TAG, "Auto-load model failed: " + modelName);
        }
        return success;
    }

    public CompletableFuture<String> generateAsync(String prompt, InferenceConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (inferenceLock) {
                // 优先使用 InferenceRouter 进行自动路由
                InferenceType type = getRouter().getCurrentInferenceType();
                
                // 如果是本地模型且未准备好，尝试自动加载
                if (type == InferenceType.LOCAL && !ensureModelReady()) {
                    // 如果本地模型不可用，尝试在线模型
                    OnlineModelManager manager = OnlineModelManager.getInstance(context);
                    if (manager.hasActiveOnlineModel()) {
                        type = InferenceType.ONLINE;
                        AILogger.i(TAG, "Local model not ready, falling back to online model");
                    } else {
                        throw new IllegalStateException("没有可用的模型。请导入本地模型或配置在线模型。");
                    }
                }

                try {
                    if (type == InferenceType.ONLINE) {
                        // 使用在线推理
                        return getRouter().generate(prompt, config).join();
                    } else {
                        // 使用本地推理（保持原有逻辑）
                        AIService aiService = AIService.getInstance(context);
                        if (aiService != null && aiService.isInitialized()) {
                            List<com.oilquiz.app.ai.util.PromptBuilder.Message> history = new ArrayList<>();
                            if (config.history != null) {
                                for (ChatMessage msg : config.history) {
                                    if (msg.isUserMessage()) {
                                        history.add(new com.oilquiz.app.ai.util.PromptBuilder.Message("user", msg.content));
                                    } else if (msg.isAIMessage()) {
                                        history.add(new com.oilquiz.app.ai.util.PromptBuilder.Message("assistant", msg.content));
                                    }
                                }
                            }
                            return aiService.generateSync(prompt, history, config.maxTokens);
                        } else {
                            List<ChatMessage> messages = config.buildMessages(prompt);
                            String promptText = buildPromptFromMessages(messages);
                            return LlamaHelper.generate(promptText, config.maxTokens, config.temperature);
                        }
                    }
                } catch (Exception e) {
                    AILogger.e(TAG, "Inference error: " + e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }
        }, executor);
    }

    public String generateSync(String prompt, InferenceConfig config) {
        synchronized (inferenceLock) {
            InferenceType type = getRouter().getCurrentInferenceType();
            
            if (type == InferenceType.LOCAL && !ensureModelReady()) {
                OnlineModelManager manager = OnlineModelManager.getInstance(context);
                if (manager.hasActiveOnlineModel()) {
                    type = InferenceType.ONLINE;
                } else {
                    throw new IllegalStateException("没有可用的模型。请导入本地模型或配置在线模型。");
                }
            }

            try {
                if (type == InferenceType.ONLINE) {
                    return getRouter().generate(prompt, config).join();
                } else {
                    AIService aiService = AIService.getInstance(context);
                    if (aiService != null && aiService.isInitialized()) {
                        List<com.oilquiz.app.ai.util.PromptBuilder.Message> history = new ArrayList<>();
                        if (config.history != null) {
                            for (ChatMessage msg : config.history) {
                                if (msg.isUserMessage()) {
                                    history.add(new com.oilquiz.app.ai.util.PromptBuilder.Message("user", msg.content));
                                } else if (msg.isAIMessage()) {
                                    history.add(new com.oilquiz.app.ai.util.PromptBuilder.Message("assistant", msg.content));
                                }
                            }
                        }
                        return aiService.generateSync(prompt, history, config.maxTokens);
                    } else {
                        List<ChatMessage> messages = config.buildMessages(prompt);
                        String promptText = buildPromptFromMessages(messages);
                        return LlamaHelper.generate(promptText, config.maxTokens, config.temperature);
                    }
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Sync inference error: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 流式生成
     */
    public void generateStream(String prompt, InferenceConfig config, StreamCallback callback) {
        InferenceType type = getRouter().getCurrentInferenceType();
        
        if (type == InferenceType.LOCAL && !ensureModelReady()) {
            OnlineModelManager manager = OnlineModelManager.getInstance(context);
            if (manager.hasActiveOnlineModel()) {
                type = InferenceType.ONLINE;
            } else {
                callback.onError("没有可用的模型。请导入本地模型或配置在线模型。");
                return;
            }
        }

        getRouter().generateStream(prompt, config, callback);
    }

    public void shutdown() {
        executor.shutdown();
        if (router != null) {
            router.shutdown();
        }
    }

    private String buildPromptFromMessages(List<ChatMessage> messages) {
        com.oilquiz.app.ai.util.PromptBuilder.PromptRequest request = new com.oilquiz.app.ai.util.PromptBuilder.PromptRequest();
        for (ChatMessage msg : messages) {
            if (msg.isSystemMessage()) {
                request.system(msg.content);
            } else if (msg.isUserMessage()) {
                request.query(msg.content);
            } else if (msg.isAIMessage()) {
            }
        }
        return request.build();
    }

    public static class InferenceConfig {
        public String systemPrompt = "请用中文回答。";
        public int maxTokens = 8192;
        public float temperature = 0.7f;
        public float topP = 0.9f;
        public int topK = 40;
        public List<ChatMessage> history;

        public List<ChatMessage> buildMessages(String userPrompt) {
            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(ChatMessage.createSystemMessage(
                    UUID.randomUUID().toString(), systemPrompt,
                    ChatMessage.SystemMessageType.INFO, System.currentTimeMillis()));
            }
            if (history != null) {
                messages.addAll(history);
            }
            messages.add(ChatMessage.createUserMessage(
                UUID.randomUUID().toString(), userPrompt, System.currentTimeMillis()));
            return messages;
        }
    }
}
