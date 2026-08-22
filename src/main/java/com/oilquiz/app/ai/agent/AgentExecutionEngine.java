package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.agent.software.AgentSoftwareLayer;
import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.refactor.ContextWindowManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent执行引擎 - 完全独立于UI的后台执行器
 *
 * 设计原则：
 * 1. 不持有Activity引用，完全与UI解耦
 * 2. 在独立线程池中执行模型推理
 * 3. 通过AgentExecutionState暴露执行状态（数据驱动）
 * 4. 通过ExecutionEventListener通知UI事件（事件驱动）
 * 5. 所有native调用被try-catch隔离，崩溃不传播
 * 6. 支持取消、暂停、恢复
 *
 * 架构：
 *   UI层（ChatAdapter）
 *         ↑ 读取数据
 *   AgentExecutionState（纯数据）
 *         ↑ 更新
 *   AgentExecutionEngine（后台执行）
 *         ↓ 调用
 *   AIService / AgentService（模型层）
 *         ↓ JNI
 *   本地模型（native层）
 */
public class AgentExecutionEngine {

    private static final String TAG = "AgentExecEngine";

    private final Context appContext;
    private final AIService aiService;
    private final AgentService agentService;
    private final AIConfig aiConfig;

    // 后台执行线程池 - 单线程保证顺序执行
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-exec-engine");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception in engine thread", throwable);
        });
        return t;
    });

    // 主线程Handler，用于安全地通知UI
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 活跃的执行任务
    private final Set<String> activeExecutions = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, AgentExecutionState> stateMap = new ConcurrentHashMap<>();
    private final java.util.Map<String, ExecutionEventListener> listenerMap = new ConcurrentHashMap<>();
    private final java.util.Map<String, AtomicInteger> stepCounters = new ConcurrentHashMap<>();
    private final java.util.Map<String, AgentSoftwareLayer> softwareLayerMap = new ConcurrentHashMap<>();

    public AgentExecutionEngine(Context context, AIService aiService,
                                  AgentService agentService, AIConfig aiConfig) {
        this.appContext = context.getApplicationContext();
        this.aiService = aiService;
        this.agentService = agentService;
        this.aiConfig = aiConfig;
    }

    /**
     * 启动Agent执行 - 在后台线程运行
     */
    public void execute(String messageId, String userMessage, ExecutionEventListener listener) {
        if (messageId == null || userMessage == null) {
            AILogger.e(TAG, "execute: invalid params");
            return;
        }

        // 创建执行状态
        AgentExecutionState state = new AgentExecutionState(messageId);
        stateMap.put(messageId, state);
        listenerMap.put(messageId, listener);
        stepCounters.put(messageId, new AtomicInteger(0));
        activeExecutions.add(messageId);

        // 检查Agent是否启用
        if (aiConfig == null || !aiConfig.isAgentEnabled()) {
            state.fail("请先在AI设置中开启Agent功能");
            emitEvent(messageId, ExecutionEvent.failed(messageId, "请先在AI设置中开启Agent功能"));
            cleanup(messageId);
            return;
        }

        // 后台线程执行
        executor.execute(() -> {
            try {
                runAgentLoop(messageId, userMessage, state);
            } catch (Throwable t) {
                AILogger.e(TAG, "Agent execution crashed: " + messageId, t);
                state.fail(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                emitEvent(messageId, ExecutionEvent.failed(messageId,
                    "执行异常: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())));
                cleanup(messageId);
            }
        });
    }

    /**
     * Agent执行主循环 - 在后台线程运行
     * 直接使用AgentSoftwareLayer（只需Context，不依赖Activity）
     */
    private void runAgentLoop(String messageId, String userMessage, AgentExecutionState state) {
        AILogger.i(TAG, "Starting agent loop: " + messageId);

        // 标记开始
        state.start();
        state.addLog("ENGINE", "Agent执行开始");
        emitEvent(messageId, ExecutionEvent.started(messageId));

        // 主动管理上下文：使用率 >= 75% 时裁剪历史，防止 agent 执行后继续对话时超限
        try {
            ContextWindowManager ctxMgr = ContextWindowManager.getInstance(appContext);
            float usage = ctxMgr.getContextUsagePercent();
            if (usage >= 0.75f) {
                AILogger.i(TAG, String.format("Context usage %.1f%% before agent, trimming", usage * 100));
                state.addLog("ENGINE", String.format("上下文 %.0f%%，裁剪历史", usage * 100));
                ctxMgr.ensureSpaceForResponse(2000);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Proactive context management failed: " + e.getMessage());
        }

        // 确保聊天上下文已初始化（裁剪可能已清除上下文，需重建）
        ensureChatContext();

        // 直接创建AgentSoftwareLayer（只需Context，完全与UI解耦）
        AgentSoftwareLayer softwareLayer = new AgentSoftwareLayer(appContext, aiService);
        softwareLayer.setCallback(createSoftwareCallback(messageId, state));
        softwareLayerMap.put(messageId, softwareLayer);

        // 在后台执行（R8-1：Agent 模式 enableThinking=false，与产品决策一致）
        softwareLayer.processMessage(userMessage, false);
    }

    /**
     * 创建AgentSoftwareLayer回调 - 将回调转换为AgentExecutionState更新和ExecutionEvent
     */
    private AgentSoftwareLayer.AgentCallback createSoftwareCallback(
            String messageId, AgentExecutionState state) {
        return new AgentSoftwareLayer.AgentCallback() {
            @Override
            public void onStepUpdate(String step, String detail) {
                if (!isActive(messageId)) return;
                AtomicInteger counter = stepCounters.get(messageId);
                int stepNum = counter != null ? counter.incrementAndGet() : 0;

                AgentExecutionState.BlockType blockType = inferBlockType(step);
                state.addBlock(blockType, step, detail);

                emitEvent(messageId, ExecutionEvent.step(messageId, stepNum,
                    blockType.name(), step, detail, 50));
            }

            @Override
            public void onThinkingUpdate(String thought) {
                if (!isActive(messageId) || thought == null) return;

                // 创建或更新THINKING block
                int idx = state.getCurrentBlockIndex();
                AgentExecutionState.ExecutionBlock currentBlock = idx >= 0 ? state.getBlock(idx) : null;

                // 如果当前block不是THINKING类型，创建新的THINKING block
                if (currentBlock == null ||
                    currentBlock.getType() != AgentExecutionState.BlockType.THINKING) {
                    AgentExecutionState.ExecutionBlock block = state.addBlock(
                        AgentExecutionState.BlockType.THINKING,
                        "思考过程", thought);
                    block.setStatus(AgentExecutionState.ExecutionBlock.BlockStatus.RUNNING);
                } else {
                    // 当前block是THINKING类型，追加内容
                    state.appendBlockContent(idx, thought);
                }

                emitEvent(messageId, ExecutionEvent.thinkingToken(messageId, thought));
            }

            @Override
            public void onToolCallStart(String toolName, String args) {
                if (!isActive(messageId)) return;
                AILogger.i(TAG, "Tool call start: " + toolName);

                AgentExecutionState.ExecutionBlock block = state.addBlock(
                    AgentExecutionState.BlockType.TOOL_CALL,
                    "调用工具: " + toolName, "");
                block.setToolName(toolName);
                block.setToolArgs(args);

                emitEvent(messageId, ExecutionEvent.toolCallStarted(messageId, toolName, args));
            }

            @Override
            public void onToolCallComplete(String toolName, boolean success, String result) {
                if (!isActive(messageId)) return;
                AILogger.i(TAG, "Tool call complete: " + toolName);

                AgentExecutionState.ExecutionBlock block = state.addBlock(
                    AgentExecutionState.BlockType.TOOL_RESULT,
                    success ? "工具执行成功" : "工具执行失败", result != null ? result : "");
                block.setToolName(toolName);
                block.setToolSuccess(success);
                block.setToolResult(result);
                block.setStatus(AgentExecutionState.ExecutionBlock.BlockStatus.COMPLETED);

                emitEvent(messageId, ExecutionEvent.toolCallCompleted(messageId, toolName, success,
                    result != null ? result : ""));
            }

            @Override
            public void onToken(String token) {
                if (!isActive(messageId) || token == null) return;
                emitEvent(messageId, ExecutionEvent.token(messageId, token));
            }

            @Override
            public void onComplete(AgentResponse response) {
                if (!isActive(messageId)) return;
                AILogger.i(TAG, "Agent completed: " + messageId);

                String finalAnswer = response != null && response.finalAnswer != null
                    ? response.finalAnswer : "";
                state.complete(finalAnswer);
                emitEvent(messageId, ExecutionEvent.completed(messageId, finalAnswer));
                cleanup(messageId);
            }

            @Override
            public void onError(String error) {
                if (!isActive(messageId)) return;
                AILogger.e(TAG, "Agent error: " + messageId + ", " + error);

                state.fail(error);
                state.addBlock(AgentExecutionState.BlockType.ERROR, "执行错误", error);
                emitEvent(messageId, ExecutionEvent.failed(messageId, error));
                cleanup(messageId);
            }

            @Override
            public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
                if (!isActive(messageId)) return;
                state.updateTokenStats(tokenCount, tokensPerSecond);
                emitEvent(messageId, ExecutionEvent.inferenceProgress(messageId, tokenCount, tokensPerSecond));
            }
        };
    }

    /**
     * 根据步骤内容推断Block类型
     */
    private AgentExecutionState.BlockType inferBlockType(String step) {
        if (step == null) return AgentExecutionState.BlockType.TEXT;
        if (step.contains("意图") || step.contains("intent") || step.contains("分析")) {
            return AgentExecutionState.BlockType.PLANNING;
        } else if (step.contains("执行") || step.contains("action") || step.contains("工具")) {
            return AgentExecutionState.BlockType.TOOL_CALL;
        } else if (step.contains("结果") || step.contains("观察") || step.contains("observe")) {
            return AgentExecutionState.BlockType.OBSERVATION;
        } else if (step.contains("反思") || step.contains("reflect")) {
            return AgentExecutionState.BlockType.REFLECTION;
        } else if (step.contains("推理") || step.contains("reason") || step.contains("思考")) {
            return AgentExecutionState.BlockType.INFERENCE;
        }
        return AgentExecutionState.BlockType.TEXT;
    }

    /**
     * 确保聊天上下文已初始化
     */
    private void ensureChatContext() {
        try {
            if (aiService != null && !LlamaHelper.isChatContextActive()) {
                AILogger.i(TAG, "Initializing chat context...");
                aiService.initChatContext("", "", "");
            }
        } catch (Throwable t) {
            AILogger.e(TAG, "Failed to init chat context", t);
        }
    }

    /**
     * 发送事件到UI（主线程安全）
     */
    private void emitEvent(String messageId, ExecutionEvent event) {
        ExecutionEventListener listener = listenerMap.get(messageId);
        if (listener == null) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                listener.onExecutionEvent(event);
            } catch (Exception e) {
                Log.e(TAG, "Listener error", e);
            }
        } else {
            mainHandler.post(() -> {
                try {
                    ExecutionEventListener l = listenerMap.get(messageId);
                    if (l != null) {
                        l.onExecutionEvent(event);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Listener error on main thread", e);
                }
            });
        }
    }

    /**
     * 检查执行是否活跃
     */
    private boolean isActive(String messageId) {
        return activeExecutions.contains(messageId);
    }

    /**
     * 获取执行状态
     */
    public AgentExecutionState getState(String messageId) {
        return stateMap.get(messageId);
    }

    /**
     * 取消执行
     */
    public void cancel(String messageId) {
        if (messageId == null) return;
        activeExecutions.remove(messageId);

        AgentExecutionState state = stateMap.get(messageId);
        if (state != null) {
            state.cancel();
        }

        emitEvent(messageId, ExecutionEvent.cancelled(messageId));
        cleanup(messageId);
    }

    /**
     * 清理资源
     */
    private void cleanup(String messageId) {
        activeExecutions.remove(messageId);
        stepCounters.remove(messageId);

        // 延迟清理state和listener，允许UI获取最终状态
        mainHandler.postDelayed(() -> {
            softwareLayerMap.remove(messageId);
        }, 1000);

        mainHandler.postDelayed(() -> {
            stateMap.remove(messageId);
            listenerMap.remove(messageId);
        }, 5000);
    }

    /**
     * 关闭引擎
     */
    public void shutdown() {
        for (String messageId : activeExecutions) {
            cancel(messageId);
        }
        executor.shutdown();
        AILogger.i(TAG, "Engine shutdown");
    }
}