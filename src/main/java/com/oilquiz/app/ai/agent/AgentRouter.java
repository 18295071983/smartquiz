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
 * 根据当前激活的模型类型自动选择执行引擎：
 * - 在线模型激活时 → 使用 {@link OnlineAgentEngine}（原生 function calling + reasoning_content 流式）
 * - 本地模型激活时 → 使用 {@link UnifiedAgentEngine}（文本 TOOLS_CALL 格式 + ReAct/CoT 循环）
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

    /** 本地引擎（始终创建，本地模型时使用） */
    private final UnifiedAgentEngine localEngine;

    /** 在线引擎（懒创建，在线模型时使用） */
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

    /**
     * 设置回调（两个引擎都会设置）
     */
    public void setCallback(AgentCallback callback) {
        this.callback = callback;
        localEngine.setCallback(callback);
        if (onlineEngine != null) {
            onlineEngine.setCallback(callback);
        }
    }

    /**
     * 设置推理进度监听器（两个引擎都支持）
     */
    public void setInferenceProgressListener(InferenceProgressListener listener) {
        this.progressListener = listener;
        localEngine.setInferenceProgressListener(listener);
        if (onlineEngine != null) {
            onlineEngine.setInferenceProgressListener(listener);
        }
    }

    /**
     * 设置推理模式（仅本地引擎支持，在线引擎使用原生 function calling 不需要）
     */
    public void setReasoningMode(UnifiedAgentEngine.ReasoningMode mode) {
        localEngine.setReasoningMode(mode);
    }

    /**
     * 执行 Agent 任务。自动路由到合适的引擎。
     */
    public void execute(String message, int maxTokens) {
        execute(message, maxTokens, true);
    }

    /**
     * 执行 Agent 任务。自动路由到合适的引擎。
     */
    public void execute(String message, int maxTokens, boolean enableThinking) {
        EngineType type = resolveEngineType();
        lastUsedEngine = type;

        if (type == EngineType.ONLINE) {
            AILogger.i(TAG, "Routing to OnlineAgentEngine (native function calling)");
            executeOnline(message, maxTokens);
        } else {
            AILogger.i(TAG, "Routing to UnifiedAgentEngine (local)");
            localEngine.execute(message, maxTokens, enableThinking);
        }
    }

    /**
     * 取消当前执行
     */
    public void cancel() {
        localEngine.cancel();
        if (onlineEngine != null) {
            onlineEngine.cancel();
        }
    }

    /**
     * 是否正在生成
     */
    public boolean isGenerating() {
        if (lastUsedEngine == EngineType.ONLINE && onlineEngine != null) {
            return onlineEngine.isGenerating();
        }
        return localEngine.isGenerating();
    }

    /**
     * 获取当前使用的引擎类型
     */
    public EngineType getCurrentEngineType() {
        return resolveEngineType();
    }

    /**
     * 获取上次使用的引擎类型
     */
    public EngineType getLastUsedEngine() {
        return lastUsedEngine;
    }

    // ========== 本地引擎兼容方法 ==========

    public int getToolLoopCount() {
        if (lastUsedEngine == EngineType.ONLINE && onlineEngine != null) {
            return onlineEngine.getToolLoopCount();
        }
        return localEngine.getToolLoopCount();
    }

    public UnifiedAgentEngine.ReasoningMode getCurrentMode() {
        if (lastUsedEngine == EngineType.ONLINE) {
            return UnifiedAgentEngine.ReasoningMode.REACT; // 在线引擎默认 ReAct
        }
        return localEngine.getCurrentMode();
    }

    public String getCurrentResponse() {
        if (lastUsedEngine == EngineType.ONLINE) {
            return ""; // 在线引擎通过 onToken 流式返回，无需缓存
        }
        return localEngine.getCurrentResponse();
    }

    public String getCurrentThinking() {
        if (lastUsedEngine == EngineType.ONLINE) {
            return ""; // 在线引擎通过 onThinkingToken 流式返回
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

    /**
     * 获取底层本地引擎（兼容特殊调用）
     */
    public UnifiedAgentEngine getLocalEngine() {
        return localEngine;
    }

    // ========== 内部方法 ==========

    /**
     * 解析当前应该使用的引擎类型
     */
    private EngineType resolveEngineType() {
        try {
            if (inferenceRouter != null && inferenceRouter.isUsingOnlineModel()) {
                return EngineType.ONLINE;
            }
            // 也检查 OnlineModelManager
            if (inferenceRouter != null) {
                OnlineModelManager.OnlineModelConfig config =
                    OnlineModelManager.getInstance(activity).getActiveModel();
                if (config != null && config.enabled) {
                    return EngineType.ONLINE;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to resolve engine type: " + e.getMessage());
        }
        return EngineType.LOCAL;
    }

    /**
     * 使用在线引擎执行
     */
    private void executeOnline(String message, int maxTokens) {
        if (onlineEngine == null) {
            // 创建在线模型专用工具管理器（独立于 AgentService）
            OnlineToolManager onlineToolManager = new OnlineToolManager(activity);
            onlineEngine = new OnlineAgentEngine(activity, onlineToolManager);
            if (callback != null) {
                onlineEngine.setCallback(callback);
            }
            if (progressListener != null) {
                onlineEngine.setInferenceProgressListener(progressListener);
            }
        }
        onlineEngine.execute(message, maxTokens);
    }
}
