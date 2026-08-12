package com.oilquiz.app.ai.agent;

import android.app.Activity;

import com.oilquiz.app.ai.agent.online.OnlineAgentEngine;
import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.InferenceProgressListener;

/**
 * Agent 统一路由入口。
 *
 * 路由逻辑：一个分支判断
 * - 在线模型 → OnlineAgentEngine（原生 function calling + reasoning_content）
 * - 本地模型 → UnifiedAgentEngine（generateWithTools + 原生 FC + 思考模式）
 *
 * 对外接口与 {@link UnifiedAgentEngine} 兼容，UI 层无需感知引擎差异。
 */
public class AgentRouter {

    private static final String TAG = "AgentRouter";

    public enum EngineType {
        LOCAL("本地引擎"),
        ONLINE("在线引擎");

        public final String displayName;
        EngineType(String displayName) { this.displayName = displayName; }
    }

    private final Activity activity;
    private final AIService aiService;
    private final InferenceRouter inferenceRouter;
    private final AgentService agentService;

    /** 本地引擎 */
    private final UnifiedAgentEngine localEngine;

    /** 在线引擎（懒创建） */
    private OnlineAgentEngine onlineEngine;

    /** 上次使用的引擎类型 */
    private EngineType lastUsedEngine;

    private AgentCallback callback;
    private InferenceProgressListener progressListener;

    public AgentRouter(Activity activity, AIService aiService, AgentService agentService) {
        this(activity, aiService, null, agentService);
    }

    public AgentRouter(Activity activity, AIService aiService, InferenceRouter inferenceRouter,
                       AgentService agentService) {
        this.activity = activity;
        this.aiService = aiService;
        this.inferenceRouter = inferenceRouter;
        this.agentService = agentService;
        this.localEngine = new UnifiedAgentEngine(activity, aiService, inferenceRouter, agentService, false);
        this.lastUsedEngine = EngineType.LOCAL;
    }

    // ==================== 回调设置 ====================

    public void setCallback(AgentCallback callback) {
        this.callback = callback;
        localEngine.setCallback(callback);
        if (onlineEngine != null) {
            onlineEngine.setCallback(callback);
        }
    }

    public void setInferenceProgressListener(InferenceProgressListener listener) {
        this.progressListener = listener;
        localEngine.setInferenceProgressListener(listener);
        if (onlineEngine != null) {
            onlineEngine.setInferenceProgressListener(listener);
        }
    }

    public void setReasoningMode(UnifiedAgentEngine.ReasoningMode mode) {
        localEngine.setReasoningMode(mode);
    }

    // ==================== 执行入口 ====================

    public void execute(String message, int maxTokens) {
        execute(message, maxTokens, true);
    }

    /**
     * 执行 Agent 任务。一个分支：
     * - 在线模型 → OnlineAgentEngine
     * - 本地模型 → localEngine（UnifiedAgentEngine）
     */
    public void execute(String message, int maxTokens, boolean enableThinking) {
        boolean isOnline = isOnlineModelActive();

        if (isOnline) {
            lastUsedEngine = EngineType.ONLINE;
            AILogger.i(TAG, "Online model → OnlineAgentEngine");
            ensureOnlineEngineCreated();
            onlineEngine.execute(message, maxTokens);
        } else {
            lastUsedEngine = EngineType.LOCAL;
            AILogger.i(TAG, "Local model → UnifiedAgentEngine");
            localEngine.execute(message, maxTokens, enableThinking);
        }
    }

    // ==================== 生命周期方法 ====================

    public void cancel() {
        localEngine.cancel();
        if (onlineEngine != null) {
            onlineEngine.cancel();
        }
    }

    public void clearHistory() {
        if (onlineEngine != null) {
            onlineEngine.clearHistory();
        }
        localEngine.clearHistory();
    }

    public boolean isGenerating() {
        if (lastUsedEngine == EngineType.ONLINE && onlineEngine != null) {
            return onlineEngine.isGenerating();
        }
        return localEngine.isGenerating();
    }

    public EngineType getCurrentEngineType() {
        return lastUsedEngine;
    }

    public EngineType getLastUsedEngine() {
        return lastUsedEngine;
    }

    // ==================== 本地引擎兼容方法 ====================

    public int getToolLoopCount() {
        if (lastUsedEngine == EngineType.ONLINE && onlineEngine != null) {
            return onlineEngine.getToolLoopCount();
        }
        return localEngine.getToolLoopCount();
    }

    public UnifiedAgentEngine.ReasoningMode getCurrentMode() {
        if (lastUsedEngine == EngineType.ONLINE) {
            return UnifiedAgentEngine.ReasoningMode.REACT;
        }
        return localEngine.getCurrentMode();
    }

    public String getCurrentResponse() {
        if (lastUsedEngine == EngineType.ONLINE) {
            return "";
        }
        return localEngine.getCurrentResponse();
    }

    public String getCurrentThinking() {
        if (lastUsedEngine == EngineType.ONLINE) {
            return "";
        }
        return localEngine.getCurrentThinking();
    }

    public int getRetryCount() {
        return localEngine.getRetryCount();
    }

    public void resumeExecution(String userInput) {
        localEngine.resumeExecution(userInput);
    }

    public void cancelPause() {
        localEngine.cancelPause();
    }

    public void shutdown() {
        localEngine.shutdown();
    }

    public UnifiedAgentEngine getLocalEngine() {
        return localEngine;
    }

    // ==================== 在线引擎兼容方法 ====================

    public void setOnlineFallbackEnabled(boolean enabled) {
        if (onlineEngine != null) {
            onlineEngine.setLocalFallbackEnabled(enabled);
        }
    }

    public boolean isOnlineUsingLocalFallback() {
        return onlineEngine != null && onlineEngine.isUsingLocalFallback();
    }

    public void forceOnlineLocalFallback() {
        if (onlineEngine != null) {
            onlineEngine.forceLocalFallback();
        }
    }

    public void resetOnlineFallbackState() {
        if (onlineEngine != null) {
            onlineEngine.resetFallbackState();
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
