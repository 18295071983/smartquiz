package com.oilquiz.app.ai.chat.mode;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.agent.software.thinking.ThinkingAssistantEngine;
import com.oilquiz.app.ai.agent.software.thinking.model.ThinkingSession;
import com.oilquiz.app.ai.agent.software.thinking.model.ThinkingState;
import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThinkingModeHandler implements ModeHandler {
    private static final String TAG = "ThinkingMode";

    private final Context appContext;
    private final AIService aiService;
    private final ThinkingAssistantEngine thinkingEngine;
    private final ExecutorService executor;
    private final Map<String, ThinkingSession> sessionMap;
    private final Map<String, String> sessionToMessageMap;

    public ThinkingModeHandler(Context context, AIService aiService) {
        this.appContext = context.getApplicationContext();
        this.aiService = aiService;
        this.thinkingEngine = new ThinkingAssistantEngine(aiService);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ThinkingModeThread");
            t.setDaemon(true);
            return t;
        });
        this.sessionMap = new HashMap<>();
        this.sessionToMessageMap = new HashMap<>();
    }

    @Override
    public void handleMessage(ChatMessage userMessage, String messageId, ModeHandlerCallback callback) {
        if (userMessage == null || userMessage.content == null || userMessage.content.trim().isEmpty()) {
            if (callback != null) {
                callback.onError(messageId, "请输入你想思考的问题");
            }
            return;
        }

        AILogger.i(TAG, "Starting thinking mode for message: " + messageId);

        ChatMessage initialAiMessage = createInitialAIMessage(messageId, userMessage);
        if (callback != null) {
            callback.onMessageCreated(messageId, initialAiMessage);
        }

        executor.execute(() -> {
            try {
                ThinkingSession session = sessionMap.get(messageId);
                if (session == null) {
                    session = thinkingEngine.startSession(userMessage.content);
                    sessionMap.put(messageId, session);
                    sessionToMessageMap.put(session.getSessionId(), messageId);

                    StreamingEvent.ThinkingStepData initStep = new StreamingEvent.ThinkingStepData(
                            0, "THINKING_INIT", "开启思考辅助模式",
                            "让我们一起思考这个问题。你可以逐步分享你的想法，我会适时给出引导。",
                            0
                    );
                    callback.onThinkingUpdate(messageId, initStep);
                }

                ThinkingAssistantEngine.ProcessResult result =
                        thinkingEngine.processMessage(session, userMessage.content);

                if (!result.isSuccess()) {
                    callback.onError(messageId, result.getError());
                    return;
                }

                StreamingEvent.ThinkingStepData analysisStep = new StreamingEvent.ThinkingStepData(
                        0, "THINKING_ANALYSIS", "思考状态分析",
                        buildAnalysisText(result),
                        30
                );
                callback.onThinkingUpdate(messageId, analysisStep);

                StringBuilder fullResponse = new StringBuilder();
                fullResponse.append(result.getResponse()).append("\n\n");

                String responseText = result.getResponse();
                if (responseText != null && !responseText.isEmpty()) {
                    callback.onToken(messageId, responseText);
                    fullResponse.append(responseText);
                }

                if (result.getAnalysis() != null && result.getState() != null) {
                    StreamingEvent.ThinkingStepData stateStep = new StreamingEvent.ThinkingStepData(
                            0, "THINKING_STATE", "当前思考阶段: " + result.getState().getDisplayName(),
                            "继续深入思考，或点击总结查看当前思考进度",
                            60
                    );
                    callback.onThinkingUpdate(messageId, stateStep);
                }

                callback.onComplete(messageId, fullResponse.toString(), null);

            } catch (Exception e) {
                AILogger.e(TAG, "Error in thinking mode: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError(messageId, "思考引擎出错: " + e.getMessage());
                }
            }
        });
    }

    public void handleSummarize(String messageId, ModeHandlerCallback callback) {
        ThinkingSession session = sessionMap.get(messageId);
        if (session == null) {
            if (callback != null) {
                callback.onToken(messageId, "还没有进行思考会话，请先分享你的想法。");
                callback.onComplete(messageId, "还没有进行思考会话。", null);
            }
            return;
        }

        executor.execute(() -> {
            try {
                com.oilquiz.app.ai.agent.software.thinking.Summarizer.SummaryResult summary =
                        thinkingEngine.summarizeSession(session);

                StringBuilder sb = new StringBuilder();
                sb.append("【思考总结】\n\n");
                if (summary.getSummary() != null) {
                    sb.append(summary.getSummary()).append("\n\n");
                }
                if (summary.getKeyPoints() != null && !summary.getKeyPoints().isEmpty()) {
                    sb.append("关键点:\n");
                    for (String kp : summary.getKeyPoints()) {
                        sb.append("• ").append(kp).append("\n");
                    }
                    sb.append("\n");
                }
                if (summary.getRecommendations() != null && !summary.getRecommendations().isEmpty()) {
                    sb.append("建议:\n");
                    for (String rec : summary.getRecommendations()) {
                        sb.append("• ").append(rec).append("\n");
                    }
                    sb.append("\n");
                }
                if (summary.getNextAction() != null) {
                    sb.append("下一步: ").append(summary.getNextAction());
                }

                callback.onToken(messageId, sb.toString());
                callback.onComplete(messageId, sb.toString(), null);

            } catch (Exception e) {
                AILogger.e(TAG, "Error in summarize: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError(messageId, "总结出错: " + e.getMessage());
                }
            }
        });
    }

    public void handlePerspective(String messageId, String userMessage, ModeHandlerCallback callback) {
        ThinkingSession session = sessionMap.get(messageId);
        if (session == null) {
            if (callback != null) {
                callback.onError(messageId, "请先开始思考会话");
            }
            return;
        }

        executor.execute(() -> {
            try {
                ThinkingAssistantEngine.ProcessResult result =
                        thinkingEngine.processWithPerspective(session, userMessage);

                if (!result.isSuccess()) {
                    callback.onError(messageId, result.getError());
                    return;
                }

                callback.onToken(messageId, result.getResponse());
                callback.onComplete(messageId, result.getResponse(), null);

            } catch (Exception e) {
                AILogger.e(TAG, "Error in perspective: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError(messageId, "视角生成出错: " + e.getMessage());
                }
            }
        });
    }

    public void handleReflection(String messageId, String userMessage, ModeHandlerCallback callback) {
        ThinkingSession session = sessionMap.get(messageId);
        if (session == null) {
            if (callback != null) {
                callback.onError(messageId, "请先开始思考会话");
            }
            return;
        }

        executor.execute(() -> {
            try {
                ThinkingAssistantEngine.ProcessResult result =
                        thinkingEngine.processWithReflection(session, userMessage);

                if (!result.isSuccess()) {
                    callback.onError(messageId, result.getError());
                    return;
                }

                callback.onToken(messageId, result.getResponse());
                callback.onComplete(messageId, result.getResponse(), null);

            } catch (Exception e) {
                AILogger.e(TAG, "Error in reflection: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError(messageId, "反思处理出错: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public void cancel(String messageId) {
        AILogger.i(TAG, "Thinking mode cancelled: " + messageId);
    }

    @Override
    public String getModeName() {
        return "thinking_assist";
    }

    public ThinkingAssistantEngine getThinkingEngine() {
        return thinkingEngine;
    }

    private String buildAnalysisText(ThinkingAssistantEngine.ProcessResult result) {
        if (result.getAnalysis() == null) {
            return "继续分享你的想法，让我更好地理解你的思考";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("问题清晰度: ").append(result.getAnalysis().clarity).append("/5\n");
        sb.append("思考深度: ").append(result.getAnalysis().depth).append("/5\n");
        sb.append("情绪状态: ").append(result.getAnalysis().emotion).append("\n");
        if (result.getAnalysis().suggestion != null && !result.getAnalysis().suggestion.isEmpty()) {
            sb.append("建议: ").append(result.getAnalysis().suggestion);
        }
        return sb.toString();
    }

    private ChatMessage createInitialAIMessage(String messageId, ChatMessage userMessage) {
        return new ChatMessage.Builder(ChatMessage.MessageType.AI)
                .id(messageId)
                .parentId(userMessage.id)
                .content("")
                .status(ChatMessage.MessageStatus.GENERATING)
                .build();
    }
}
