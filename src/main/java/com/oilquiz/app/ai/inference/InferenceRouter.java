package com.oilquiz.app.ai.inference;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.callback.StreamCallback;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.engine.NpuEngineRouter;
import com.oilquiz.app.ai.engine.NpuLlmChat;
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

    /** NPU（GenieX）引擎开关的持久化 */
    private static final String PREFS_NPU = "npu_engine_prefs";
    private static final String KEY_NPU_ENABLED = "npu_enabled";

    private static volatile InferenceRouter INSTANCE;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;

    /** 是否把 NPU（Qualcomm GenieX）选为当前推理引擎 */
    private volatile boolean npuEnabled;
    
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
        try {
            this.npuEnabled = this.context.getSharedPreferences(PREFS_NPU, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NPU_ENABLED, false);
        } catch (Throwable ignored) {
            this.npuEnabled = false;
        }
    }

    // ==================== NPU（Qualcomm GenieX）引擎 ====================

    /** 是否已把 NPU 选为当前推理引擎 */
    public boolean isNpuEngineEnabled() {
        return npuEnabled;
    }

    /**
     * 切到 NPU 引擎（持久化），并异步确保 GenieX 侧载模型已加载。
     *
     * @param listener 加载结果回调（可为 null：那就在首次生成时按需加载）
     */
    public void enableNpuEngine(NpuLlmChat.LoadListener listener) {
        setNpuEnabled(true);
        // 互斥：先释放本地 llama.cpp 的权重再加载 NPU，避免"两份模型同时常驻"
        // （0.6B 0.4GB / 4B 2.4GB，两份就是双倍内存 + 双份 KV cache）
        releaseLocalModelForNpu();
        // NPU-SERVICE-OWNED: 加载统一走 AIService（后台线程/幂等/超时），这里把
        // NpuLlmChat.LoadListener 适配成服务的 InitializeCallback，对外签名不变。
        com.oilquiz.app.ai.service.AIService.getInstance(context).ensureNpuLoadedAsync(ok -> {
            if (listener == null) return;
            if (ok) listener.onLoaded(com.oilquiz.app.ai.service.AIService.getInstance(context).getNpuModelName());
            else listener.onError("NPU 模型加载失败");
        });
    }

    public void enableNpuEngine() {
        enableNpuEngine(null);
    }

    /** 切回本地 llama.cpp（在"选择模型"里选其它模型时调用） */
    public void disableNpuEngine() {
        // 互斥：关掉 NPU 就释放 GenieX 的权重与会话，避免与随后加载的本地模型两份并存
        try { NpuLlmChat.release(); } catch (Throwable ignored) { }
        logNativeMem("disableNpuEngine 释放 NPU 后");
        setNpuEnabled(false);
    }

    private void setNpuEnabled(boolean enabled) {
        npuEnabled = enabled;
        try { NpuLlmChat.setEngineEnabled(enabled); } catch (Throwable ignored) { }
        try {
            context.getSharedPreferences(PREFS_NPU, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_NPU_ENABLED, enabled).apply();
        } catch (Throwable t) {
            AILogger.w(TAG, "持久化 NPU 开关失败: " + t.getMessage());
        }
        AILogger.i(TAG, "NPU 引擎" + (enabled ? "已启用" : "已关闭"));
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

        // NPU（GenieX）优先：它是用户显式选择的端侧推理引擎
        if (npuEnabled) {
            return InferenceType.NPU;
        }

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

        if (npuEnabled) {
            String npuModel = NpuLlmChat.getCurrentModel();
            if (npuModel == null || npuModel.isEmpty()) {
                return "Qwen3（NPU/GenieX）";
            }
            return npuModel + "（NPU）";
        }
        
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

        if (type == InferenceType.NPU) {
            // 只把"错误"当不可用：IDLE 表示还没加载，首次生成时会按需加载
            String st = NpuLlmChat.getStateName();
            return !"ERROR".equals(st);
        }

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

            if (type == InferenceType.NPU) {
                return generateNpuSync(prompt, config);
            }
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

        if (type == InferenceType.NPU) {
            generateNpuStream(prompt, config, callback);
        } else if (type == InferenceType.ONLINE) {
            generateOnlineStream(prompt, config, callback);
        } else {
            generateLocalStream(prompt, config, callback);
        }
    }

    // ==================== NPU（GenieX）生成实现 ====================

    /**
     * 把当前对话历史转成 GenieX 需要的 roles/contents 两个平行数组。
     *
     * <p>为什么带 system：本地 llama.cpp 路径也是这么拼的（见 generateLocalStream），
     * 保证两种引擎的回答风格一致。
     */
    private void buildNpuMessages(String prompt, InferenceConfig config,
                                  List<String> roles, List<String> contents) {
        roles.add("system");
        contents.add("你是答题宝智能助手，请用中文简洁、准确地回答用户问题。");
        if (config != null && config.history != null) {
            for (ChatMessage msg : config.history) {
                if (msg == null || msg.content == null || msg.content.isEmpty()) continue;
                roles.add(msg.isAIMessage() ? "assistant" : "user");
                contents.add(msg.content);
            }
        }
        roles.add("user");
        contents.add(prompt);
    }

    /**
     * NPU 流式生成：按需加载 GenieX 侧载模型 → 套 chat template → 流式回调。
     * 任何一步失败都通过 callback.onError 上报（界面据此提示或回退 llama.cpp）。
     */
    private void generateNpuStream(String prompt, InferenceConfig config, StreamCallback callback) {
        callback.onStart();
        final StringBuilder fullText = new StringBuilder();
        NpuLlmChat.ensureLoadedAsync(context, new NpuLlmChat.LoadListener() {
            @Override
            public void onLoaded(String modelName) {
                List<String> roles = new ArrayList<>();
                List<String> contents = new ArrayList<>();
                buildNpuMessages(prompt, config, roles, contents);
                NpuLlmChat.sendChatAsync(
                        roles.toArray(new String[0]),
                        contents.toArray(new String[0]),
                        Math.max(1, config == null ? 1024 : config.maxTokens),
                        config != null && config.enableThinking,
                        new NpuLlmChat.GenerateListener() {
                            @Override
                            public void onToken(String text) {
                                fullText.append(text);
                                mainHandler.post(() -> callback.onToken(text));
                            }

                            @Override
                            public void onCompleted(int tokens, float tps, long elapsedMs) {
                                mainHandler.post(() -> {
                                    callback.onComplete(fullText.toString());
                                    callback.onTokenStats(0, tokens);
                                });
                            }

                            @Override
                            public void onError(String message) {
                                mainHandler.post(() -> callback.onError("NPU 推理失败: " + message));
                            }
                        });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> callback.onError("NPU 模型不可用: " + message));
            }
        });
    }

    /** NPU 同步生成（给 generate/generateSync 这类无流式的调用方用） */
    private String generateNpuSync(String prompt, InferenceConfig config) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] out = new String[]{null};
        final String[] err = new String[]{null};
        generateNpuStream(prompt, config, new StreamCallback() {
            @Override
            public void onComplete(String fullText) {
                out[0] = fullText;
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                err[0] = error;
                latch.countDown();
            }
        });
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
                throw new RuntimeException("NPU 推理超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("NPU 推理被中断", e);
        }
        if (err[0] != null) {
            throw new RuntimeException(err[0]);
        }
        return out[0] == null ? "" : out[0];
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
        // NPU（GenieX）引擎也要能停
        try {
            NpuLlmChat.stopGenerate();
        } catch (Throwable t) {
            AILogger.w(TAG, "stopGenexX failed: " + t.getMessage());
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
                messages.add(new PromptBuilder.Message("system", "你是答题宝智能助手，请用中文简洁、准确地回答用户问题。"));
                if (config.history != null) {
                    for (ChatMessage msg : config.history) {
                        String role = msg.isAIMessage() ? "assistant" : "user";
                        messages.add(new PromptBuilder.Message(role, msg.content));
                    }
                }
                messages.add(new PromptBuilder.Message("user", prompt));
                return NpuEngineRouter.generate(messages, config.maxTokens, config.temperature, 0.9f, 40);
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
                messages.add(new PromptBuilder.Message("system", "你是答题宝智能助手，请用中文简洁、准确地回答用户问题。"));
                if (config.history != null) {
                    for (ChatMessage msg : config.history) {
                        String role = msg.isAIMessage() ? "assistant" : "user";
                        messages.add(new PromptBuilder.Message(role, msg.content));
                    }
                }
                messages.add(new PromptBuilder.Message("user", prompt));

                NpuEngineRouter.generateStream(messages, config.maxTokens, config.temperature, 0.9f, 40, config != null && config.enableThinking,
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
                config.enableThinking,
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
                    public void onThinkingToken(String token) {
                        // 转发在线思考（reasoning_content）增量
                        callback.onThinkingToken(token);
                    }

                    @Override
                    public void onThinkingEnd() {
                        // 转发思考结束信号（正文首次出现或流结束补发）
                        callback.onThinkingEnd();
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

    /** 切换引擎前释放本地 llama.cpp 权重（互斥，避免两份模型常驻） */
    private void releaseLocalModelForNpu() {
        try {
            logNativeMem("释放本地模型前");
            com.oilquiz.app.ai.jni.LlamaHelper.release();
            logNativeMem("释放本地模型后");
        } catch (Throwable t) {
            com.oilquiz.app.util.AILogger.w("InferenceRouter", "释放本地模型失败: " + t);
        }
    }

    /** 原生堆占用（模型权重都在这里，用来验证"是否占了两份"） */
    private void logNativeMem(String tag) {
        try {
            long nativeHeap = android.os.Debug.getNativeHeapAllocatedSize();
            com.oilquiz.app.util.AILogger.i("InferenceRouter",
                    tag + ": nativeHeap=" + (nativeHeap / 1024 / 1024 / 1024.0) + " GB");
        } catch (Throwable ignored) {
        }
    }
}