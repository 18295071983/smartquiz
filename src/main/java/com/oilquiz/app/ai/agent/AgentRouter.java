package com.oilquiz.app.ai.agent;

import android.app.Activity;

import com.oilquiz.app.ai.agent.online.OnlineAgentEngine;
import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

/**
 * Agent 统一路由入口（纯在线）。
 *
 * 本地 Agent 已弃用（原生 FC 工具循环在 4B 上不稳定），本地模型使用普通对话；
 * 本路由仅服务在线模型：OnlineAgentEngine（原生 function calling + reasoning_content）。
 */
public class AgentRouter {

    private static final String TAG = "AgentRouter";

    private final Activity activity;
    private final InferenceRouter inferenceRouter;

    /** 在线引擎（懒创建） */
    private OnlineAgentEngine onlineEngine;

    private AgentCallback callback;
    private InferenceProgressListener progressListener;

    public AgentRouter(Activity activity, AIService aiService, AgentService agentService) {
        this(activity, aiService, null, agentService);
    }

    public AgentRouter(Activity activity, AIService aiService, InferenceRouter inferenceRouter,
                       AgentService agentService) {
        this.activity = activity;
        this.inferenceRouter = inferenceRouter;
    }

    // ==================== 回调设置 ====================

    public void setCallback(AgentCallback callback) {
        this.callback = callback;
        if (onlineEngine != null) {
            onlineEngine.setCallback(callback);
        }
    }

    public void setInferenceProgressListener(InferenceProgressListener listener) {
        this.progressListener = listener;
        if (onlineEngine != null) {
            onlineEngine.setInferenceProgressListener(listener);
        }
    }

    public void setReasoningMode(Object mode) {
        // 在线引擎无推理模式概念，忽略（兼容旧调用）
    }

    // ==================== 执行入口 ====================

    public void execute(String message, int maxTokens) {
        // 默认不开深度思考：由调用方显式传入（模型是否支持由引擎层按模型名门控）
        execute(message, maxTokens, false);
    }

    /**
     * 执行 Agent 任务（仅在线模型）。
     * 本地模型已被上层降级普通对话，不进入本路由。
     */
    public void execute(String message, int maxTokens, boolean enableThinking) {
        if (!isOnlineModelActive()) {
            // AGENT-DEADEND-FIX（2026-10-07 实测）：原先这里只打日志就 return —— **不回调任何终态**。
            // 而调用方（AIChatActivity.processChatMessageWithAgent）在进来之前已把状态栏置为
            // 「⏳ 模型处理中…」，且没有任何超时清除逻辑，于是界面**永久卡在"模型处理中"**
            // （用户报障："agent有问题，一直在模型处理中"）。实测日志只有一行
            // "Agent execute skipped: no online model active" 之后再无输出。
            // 路由不可用**必须给出结论**，否则 UI 无从得知该结束等待。
            AILogger.w(TAG, "Agent execute skipped: no online model active → 回调 onError 以免界面卡死");
            if (callback != null) {
                callback.onError("当前没有可用的在线模型，Agent 已取消。"
                        + "请在设置里启用在线模型，或改用本地模型（本地 Agent 路径）。");
            }
            return;
        }
        AILogger.i(TAG, "Online model → OnlineAgentEngine" + (enableThinking ? " (深度思考)" : ""));
        ensureOnlineEngineCreated();
        onlineEngine.execute(message, maxTokens, enableThinking);
    }

    /**
     * 当前是否有可用的在线模型。
     *
     * <p>供上层做**路由决策**用：选定分支之前先问一句，避免把一个本地模型请求送进在线分支，
     * 结果被本类静默跳过、界面卡在"模型处理中"（见 {@link #execute} 的说明）。</p>
     */
    public boolean isOnlineModelAvailable() {
        return isOnlineModelActive();
    }

    // ==================== 生命周期方法 ====================

    public void cancel() {
        if (onlineEngine != null) {
            onlineEngine.cancel();
        }
    }

    public void clearHistory() {
        if (onlineEngine != null) {
            onlineEngine.clearHistory();
        }
    }

    /** 手动压缩对话历史：模型生成摘要，保留最近 keepRecent 条消息（长对话省 tokens） */
    public void compressHistory(int keepRecent, java.util.function.Consumer<String> callback) {
        ensureOnlineEngineCreated();
        onlineEngine.compressHistory(keepRecent, callback);
    }

    /** 清空所有会话的历史（内存 + 全部历史文件） */
    public void clearAllHistory() {
        if (onlineEngine != null) {
            onlineEngine.clearAllHistory();
        }
    }

    /** 切换会话 ID（保存当前会话历史 → 恢复目标会话历史） */
    public void setSessionId(String sessionId) {
        ensureOnlineEngineCreated();
        onlineEngine.setSessionId(sessionId);
    }

    /** 设置当前在线模型 ID（模型切换时调用，引擎按「会话 × 模型」隔离历史文件）。
     *  在线引擎未创建时跳过（本地路径无需记录），下次在线 handler 重建时会重新设置。 */
    public void setModelId(String modelId) {
        if (onlineEngine != null) {
            onlineEngine.setModelId(modelId);
        }
    }

    public boolean isGenerating() {
        return onlineEngine != null && onlineEngine.isGenerating();
    }

    public int getToolLoopCount() {
        return onlineEngine != null ? onlineEngine.getToolLoopCount() : 0;
    }

    public int getLastCacheHitTokens() {
        return onlineEngine != null ? onlineEngine.getLastCacheHitTokens() : 0;
    }

    public int getLastCacheMissTokens() {
        return onlineEngine != null ? onlineEngine.getLastCacheMissTokens() : 0;
    }

    public int getLastPromptTokens() {
        return onlineEngine != null ? onlineEngine.getLastPromptTokens() : 0;
    }

    public int getLastCompletionTokens() {
        return onlineEngine != null ? onlineEngine.getLastCompletionTokens() : 0;
    }

    public int getLastReasoningTokens() {
        return onlineEngine != null ? onlineEngine.getLastReasoningTokens() : 0;
    }

    public int getExecTotalPromptTokens() {
        return onlineEngine != null ? onlineEngine.getExecTotalPromptTokens() : 0;
    }

    public int getExecTotalCompletionTokens() {
        return onlineEngine != null ? onlineEngine.getExecTotalCompletionTokens() : 0;
    }

    public int[] getContextWindowInfo() {
        return onlineEngine != null ? onlineEngine.getContextWindowInfo()
                : new int[]{0, 0, 0};
    }

    public void shutdown() {
        if (onlineEngine != null) {
            onlineEngine.shutdown();
        }
    }

    // ==================== 内部方法 ====================

    /** 当前是否在线模型（本地 Agent 复活入口路由判断用，R3-1） */
    public boolean isOnlineModelActive() {
        try {
            if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
                return true;
            }
            OnlineModelManager.OnlineModelConfig config =
                OnlineModelManager.getInstance(activity).getActiveModel();
            return config != null && config.enabled;
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to check online model: " + e.getMessage());
            return false;
        }
    }

    private void ensureOnlineEngineCreated() {
        if (onlineEngine == null) {
            OnlineToolManager onlineToolManager = new OnlineToolManager(activity);
            OnlineToolManager.setInstance(onlineToolManager);
            // 引擎只持 ApplicationContext（不持有 Activity，避免页面重建/退出泄漏）
            onlineEngine = new OnlineAgentEngine(activity.getApplicationContext(), onlineToolManager);
            if (callback != null) {
                onlineEngine.setCallback(callback);
            }
            if (progressListener != null) {
                onlineEngine.setInferenceProgressListener(progressListener);
            }
        }
    }
}
