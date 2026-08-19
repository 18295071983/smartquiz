package com.oilquiz.app.ai.chat;

import android.app.Activity;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentRouter;
import com.oilquiz.app.ai.agent.SmartIntentRecognizer;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.agent.software.AgentSoftwareLayer;
import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.AgentStats;
import com.oilquiz.app.util.AILogger;

import java.util.List;

public class AgentChatHandler {
    private static final String TAG = "AgentChatHandler";

    public enum InferenceMode {
        REACT,
        CHAIN_OF_THOUGHT,
        PLAN_EXECUTE
    }

    public interface AgentChatCallback {
        void onToolCallStart(String toolCallId, String toolName, String args);
        void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result);
        void onToken(String token);
        void onThinkingToken(String token);
        void onThinkingEnd();
        void onComplete(String fullText);
        void onError(String error);
        void onModeSwitched(String mode);
        void onAgentStep(ChatMessage.AgentStepInfo stepInfo);
        void onToolCallUI(String toolName, String args, int position);
        void onToolCallResultUI(int position, boolean success, String result);
        void onAgentStepUpdateUI(int position, String thought, String action, String observation, boolean isCompleted);
        void onInferenceProgress(int tokenCount, float tokensPerSecond);
    }

    private final Activity activity;
    private final AgentService agentService;
    private final AgentChatCallback callback;
    private final AgentRouter engine;
    private final SmartIntentRecognizer intentRecognizer;
    private AgentSoftwareLayer softwareLayer;
    private volatile boolean isShutdown = false;

    private InferenceMode currentInferenceMode = InferenceMode.REACT;

    public AgentChatHandler(Activity activity, AIService aiService, AgentService agentService, AgentChatCallback callback) {
        this(activity, aiService, null, agentService, callback, false);
    }

    public AgentChatHandler(Activity activity, AIService aiService, InferenceRouter inferenceRouter,
                            AgentService agentService, AgentChatCallback callback, boolean useOnlineModel) {
        this.activity = activity;
        this.agentService = agentService;
        this.callback = callback;
        // 使用 AgentRouter 自动路由到本地/在线引擎
        this.engine = new AgentRouter(activity, aiService, inferenceRouter, agentService);
        this.intentRecognizer = SmartIntentRecognizer.getInstance(activity);
        
        // 初始化新的 AgentSoftwareLayer
        this.softwareLayer = new AgentSoftwareLayer(activity, aiService);
        this.softwareLayer.setCallback(new AgentSoftwareLayer.AgentCallback() {
            @Override
            public void onStepUpdate(String step, String detail) {
                AILogger.i(TAG, "Agent step: " + step + " - " + detail);
                if (isValid()) {
                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING, 0, 0);
                    stepInfo.thought = step + ": " + detail;
                    stepInfo.isCompleted = true;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onThinkingUpdate(String thought) {
                if (isValid()) {
                    callback.onThinkingToken(thought);
                }
            }

            @Override
            public void onToolCallStart(String toolName, String args) {
                if (isValid()) {
                    callback.onToolCallStart("software_" + System.nanoTime(), toolName, args);
                    callback.onToolCallUI(toolName, args, -1);
                }
            }

            @Override
            public void onToolCallComplete(String toolName, boolean success, String result) {
                if (isValid()) {
                    OnlineToolResult toolResult = success
                        ? OnlineToolResult.success(null, toolName, result, 0)
                        : OnlineToolResult.failure(null, toolName, result, 0);
                    callback.onToolCallComplete("software_" + System.nanoTime(), toolName, toolResult);
                    callback.onToolCallResultUI(-1, success, result);
                }
            }

            @Override
            public void onToken(String token) {
                if (isValid() && token != null) {
                    callback.onToken(token);
                }
            }

            @Override
            public void onComplete(AgentResponse response) {
                if (isValid()) {
                    callback.onComplete(response.finalAnswer);
                } else if (response != null && response.finalAnswer != null
                        && !response.finalAnswer.trim().isEmpty()) {
                    notifyBackgroundResult(true, response.finalAnswer);
                }
            }

            @Override
            public void onError(String error) {
                if (isValid()) {
                    callback.onError(error);
                } else if (error != null && !error.trim().isEmpty()) {
                    notifyBackgroundResult(false, error);
                }
            }

            @Override
            public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
                if (isValid()) {
                    callback.onInferenceProgress(tokenCount, tokensPerSecond);
                }
            }
        });

        engine.setCallback(new AgentCallback() {
            @Override
            public void onToken(String token) {
                if (isValid()) callback.onToken(token);
            }

            @Override
            public void onThinkingToken(String token) {
                if (isValid()) callback.onThinkingToken(token);
            }

            @Override
            public void onThinkingEnd() {
                if (isValid()) callback.onThinkingEnd();
            }

            @Override
            public void onToolCallStart(String toolCallId, String toolName, String args) {
                if (isValid()) {
                    callback.onToolCallStart(toolCallId, toolName, args);
                    callback.onToolCallUI(toolName, args, -1);
                }
            }

            @Override
            public void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result) {
                if (isValid()) {
                    callback.onToolCallComplete(toolCallId, toolName, result);
                    // 保护：result可能为null
                    boolean success = result != null && result.success;
                    String resultStr = result != null ? result.result : "工具执行返回null";
                    callback.onToolCallResultUI(-1, success, resultStr);
                }
            }

            @Override
            public void onStepUpdate(String step, String detail) {
                if (isValid()) {
                    // 根据步骤内容判断步骤类型
                    ChatMessage.AgentStepInfo.AgentStepType stepType = ChatMessage.AgentStepInfo.AgentStepType.THINKING;

                    if (step.contains("意图") || step.contains("intent") || step.contains("分析")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.PLANNING;
                    } else if (step.contains("执行") || step.contains("action") || step.contains("工具")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.ACTING;
                    } else if (step.contains("结果") || step.contains("观察") || step.contains("observe")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.OBSERVING;
                    } else if (step.contains("反思") || step.contains("reflect")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.REFLECTING;
                    } else if (step.contains("推理") || step.contains("reason")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.REASONING;
                    } else if (step.contains("循环") || step.contains("loop")) {
                        stepType = ChatMessage.AgentStepInfo.AgentStepType.LOOPING;
                    }

                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        stepType,
                        engine.getToolLoopCount() + 1,
                        5
                    );
                    stepInfo.thought = step;
                    stepInfo.detail = detail;
                    stepInfo.reasoningMode = "在线";
                    stepInfo.isCompleted = true;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onComplete(String fullText) {
                if (isValid()) {
                    callback.onComplete(fullText);
                } else if (fullText != null && !fullText.trim().isEmpty()) {
                    // 界面已关闭，任务在后台完成 → 通知提醒（点击回会话查看）
                    notifyBackgroundResult(true, fullText);
                }
            }

            @Override
            public void onError(String error) {
                if (isValid()) {
                    callback.onError(error);
                } else if (error != null && !error.trim().isEmpty()) {
                    // 界面已关闭，后台任务失败 → 通知提醒
                    notifyBackgroundResult(false, error);
                }
            }

            // ========== 新增回调方法 ==========

            @Override
            public void onThinking(String thought) {
                AILogger.i(TAG, "onThinking: " + thought);
                if (isValid()) {
                    // 显示思考内容
                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING,
                        0,
                        0
                    );
                    stepInfo.thought = thought;
                    stepInfo.reasoningMode = "在线";
                    stepInfo.isCompleted = false;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onThinkingStage(String stage) {
                AILogger.i(TAG, "onThinkingStage: " + stage);
                if (isValid()) {
                    // 可以在UI上显示阶段变化
                    callback.onAgentStep(new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING,
                        0,
                        0
                    ));
                }
            }

            // ==========================================
        });

        // 设置推理进度监听器，转发到UI
        engine.setInferenceProgressListener((tokenCount, tokensPerSecond) -> {
            if (isValid()) {
                callback.onInferenceProgress(tokenCount, tokensPerSecond);
            }
        });
    }

    public void setInferenceMode(InferenceMode mode) {
        this.currentInferenceMode = mode;
        // 推理模式（REACT/CoT/Plan）为旧本地 Agent 概念，在线引擎忽略
    }

    public InferenceMode getInferenceMode() {
        return currentInferenceMode;
    }

    public boolean shouldUseAgent(String message) {
        return intentRecognizer.shouldUseAgent(message);
    }

    /**
     * 检查 AgentChatHandler 是否有效（UI 回调可用）。
     * 注意：Activity 销毁/回调失效仅跳过 UI 更新，不阻止引擎后台继续执行任务。
     */
    public boolean isValid() {
        return !isShutdown && callback != null && activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** Activity 是否仍存活（用于判断后台完成时是否需要通知提醒） */
    public boolean isActivityAlive() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /**
     * 后台完成提醒：Activity 已销毁时发系统通知，点击回到 AI 对话界面查看结果。
     */
    private void notifyBackgroundResult(boolean success, String summary) {
        try {
            if (activity == null) return;
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    activity.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String channelId = "ai_agent_result";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        channelId, "AI 任务结果", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Agent 后台任务完成提醒");
                nm.createNotificationChannel(channel);
            }
            android.content.Intent intent = new android.content.Intent(activity,
                    com.oilquiz.app.ui.activity.AIChatActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(activity, 0, intent,
                    android.os.Build.VERSION.SDK_INT >= 23
                            ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                            : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            String text = summary != null && summary.length() > 80 ? summary.substring(0, 80) + "…" : (summary != null ? summary : "");
            android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(activity, channelId)
                    : new android.app.Notification.Builder(activity);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(success ? "✅ AI 任务完成" : "❌ AI 任务失败")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
        } catch (Throwable t) {
            AILogger.w(TAG, "后台完成通知失败: " + t.getMessage());
        }
    }

    /**
     * 安全执行回调操作
     * 在执行前检查 callback 是否有效
     */
    private void safeCallback(java.util.function.Consumer<AgentChatCallback> action) {
        if (isValid()) {
            try {
                action.accept(callback);
            } catch (Exception e) {
                AILogger.e(TAG, "Callback error: " + e.getMessage());
            }
        } else {
            AILogger.w(TAG, "Callback invalid, skipping action");
        }
    }

    public String getIntentType(String message) {
        return intentRecognizer.getIntentType(message);
    }

public SmartIntentRecognizer.IntentResult analyzeIntent(String message) {
        return intentRecognizer.recognize(message);
    }

    public void startAgentLoop(String message, int maxTokens, boolean enableThinking) {
        AILogger.i(TAG, "startAgentLoop: mode=" + currentInferenceMode + ", msg_len=" + message.length());

        // 路由分支：在线模型 → OnlineAgentEngine，本地模型 → UnifiedAgentEngine
        engine.execute(message, maxTokens, enableThinking);
    }

    public void cancel() {
        engine.cancel();
    }

    /** 清空对话历史（用于"新对话"/"清空对话"操作） */
    public void clearHistory() {
        engine.clearHistory();
    }

    /** 手动压缩对话历史：模型生成摘要，保留最近 keepRecent 条消息 */
    public void compressHistory(int keepRecent, java.util.function.Consumer<String> callback) {
        engine.compressHistory(keepRecent, callback);
    }

    /** 清空所有会话的历史（内存 + 全部历史文件） */
    public void clearAllHistory() {
        engine.clearAllHistory();
    }

    /** 切换会话 ID（引擎按会话隔离历史：保存当前 → 恢复目标） */
    public void setSessionId(String sessionId) {
        engine.setSessionId(sessionId);
    }

    public boolean isGenerating() {
        return engine.isGenerating();
    }

    public int getToolLoopCount() {
        return engine.getToolLoopCount();
    }

    public int getLastCacheHitTokens() {
        return engine.getLastCacheHitTokens();
    }

    public int getLastPromptTokens() {
        return engine.getLastPromptTokens();
    }

    public int getLastCompletionTokens() {
        return engine.getLastCompletionTokens();
    }

    public int getExecTotalPromptTokens() {
        return engine.getExecTotalPromptTokens();
    }

    public int getExecTotalCompletionTokens() {
        return engine.getExecTotalCompletionTokens();
    }

    public void shutdown() {
        isShutdown = true;
        engine.shutdown();
        AILogger.i(TAG, "AgentChatHandler shut down");
    }
}
