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
        execute(message, maxTokens, true);
    }

    /**
     * 执行 Agent 任务（仅在线模型）。
     * 本地模型已被上层降级普通对话，不进入本路由。
     */
    public void execute(String message, int maxTokens, boolean enableThinking) {
        if (!isOnlineModelActive()) {
            AILogger.w(TAG, "Agent execute skipped: no online model active");
            return;
        }
        AILogger.i(TAG, "Online model → OnlineAgentEngine");
        ensureOnlineEngineCreated();
        onlineEngine.execute(message, maxTokens);
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

    public boolean isGenerating() {
        return onlineEngine != null && onlineEngine.isGenerating();
    }

    public int getToolLoopCount() {
        return onlineEngine != null ? onlineEngine.getToolLoopCount() : 0;
    }

    public int getLastCacheHitTokens() {
        return onlineEngine != null ? onlineEngine.getLastCacheHitTokens() : 0;
    }

    public int getLastPromptTokens() {
        return onlineEngine != null ? onlineEngine.getLastPromptTokens() : 0;
    }

    public int getLastCompletionTokens() {
        return onlineEngine != null ? onlineEngine.getLastCompletionTokens() : 0;
    }

    public void shutdown() {
        if (onlineEngine != null) {
            onlineEngine.shutdown();
        }
    }

    // ==================== 内部方法 ====================

    private boolean isOnlineModelActive() {
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
            onlineEngine = new OnlineAgentEngine(activity, onlineToolManager);
            if (callback != null) {
                onlineEngine.setCallback(callback);
            }
            if (progressListener != null) {
                onlineEngine.setInferenceProgressListener(progressListener);
            }
        }
    }
}
