package com.oilquiz.app.ai.chat;

import android.app.Activity;
import com.oilquiz.app.ai.agent.SmartIntentRecognizer;
import com.oilquiz.app.ai.agent.UnifiedAgentEngine;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

public class AgentChatHandler {
    private static final String TAG = "AgentChatHandler";

    public enum InferenceMode {
        REACT,
        CHAIN_OF_THOUGHT,
        PLAN_EXECUTE
    }

    public interface AgentChatCallback {
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, AgentService.ToolResult result);
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
    }

    private final Activity activity;
    private final AgentService agentService;
    private final AgentChatCallback callback;
    private final UnifiedAgentEngine engine;
    private final SmartIntentRecognizer intentRecognizer;

    private InferenceMode currentInferenceMode = InferenceMode.REACT;

    public AgentChatHandler(Activity activity, AIService aiService, AgentService agentService, AgentChatCallback callback) {
        this.activity = activity;
        this.agentService = agentService;
        this.callback = callback;
        this.engine = new UnifiedAgentEngine(activity, aiService, agentService);
        this.intentRecognizer = SmartIntentRecognizer.getInstance(activity);

        engine.setCallback(new UnifiedAgentEngine.AgentCallback() {
            @Override
            public void onToken(String token) {
                if (callback != null) callback.onToken(token);
            }

            @Override
            public void onThinkingToken(String token) {
                if (callback != null) callback.onThinkingToken(token);
            }

            @Override
            public void onThinkingEnd() {
                if (callback != null) callback.onThinkingEnd();
            }

            @Override
            public void onToolCallStart(String toolName, String args) {
                if (callback != null) {
                    callback.onToolCallStart(toolName, args);
                    callback.onToolCallUI(toolName, args, -1);
                }
            }

            @Override
            public void onToolCallComplete(String toolName, AgentService.ToolResult result) {
                if (callback != null) {
                    callback.onToolCallComplete(toolName, result);
                    callback.onToolCallResultUI(-1, result.success, result.result);
                }
            }

            @Override
            public void onStepUpdate(String step, String detail) {
                if (callback != null) {
                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING,
                        engine.getToolLoopCount() + 1,
                        5
                    );
                    stepInfo.thought = step + ": " + detail;
                    stepInfo.isCompleted = true;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onComplete(String fullText) {
                if (callback != null) callback.onComplete(fullText);
            }

            @Override
            public void onError(String error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    public void setInferenceMode(InferenceMode mode) {
        this.currentInferenceMode = mode;
        switch (mode) {
            case REACT:
                engine.setReasoningMode(UnifiedAgentEngine.ReasoningMode.REACT);
                break;
            case CHAIN_OF_THOUGHT:
                engine.setReasoningMode(UnifiedAgentEngine.ReasoningMode.CHAIN_OF_THOUGHT);
                break;
            case PLAN_EXECUTE:
                engine.setReasoningMode(UnifiedAgentEngine.ReasoningMode.PLAN_EXECUTE);
                break;
        }
    }

    public InferenceMode getInferenceMode() {
        return currentInferenceMode;
    }

    public boolean shouldUseAgent(String message) {
        return intentRecognizer.shouldUseAgent(message);
    }

    public String getIntentType(String message) {
        return intentRecognizer.getIntentType(message);
    }

    public SmartIntentRecognizer.IntentResult analyzeIntent(String message) {
        return intentRecognizer.recognize(message);
    }

    public void startAgentLoop(String message, int maxTokens, boolean enableThinking) {
        AILogger.i(TAG, "startAgentLoop: mode=" + currentInferenceMode + ", msg_len=" + message.length());

        SmartIntentRecognizer.IntentResult intent = intentRecognizer.recognize(message);
        AILogger.i(TAG, "Intent: " + intent.intent.id + " conf=" + intent.confidence + " source=" + intent.source);

        ChatMessage.AgentStepInfo planStep = new ChatMessage.AgentStepInfo(
            ChatMessage.AgentStepInfo.AgentStepType.PLANNING, 1, 5);
        planStep.thought = "意图识别: " + intent.intent.displayName
            + " (" + String.format("%.0f%%", intent.confidence * 100) + ")"
            + " → " + currentInferenceMode.name() + "模式";
        planStep.isCompleted = true;
        if (callback != null) callback.onAgentStep(planStep);

        engine.execute(message, maxTokens, enableThinking);
    }

    public void cancel() {
        engine.cancel();
    }

    public boolean isGenerating() {
        return engine.isGenerating();
    }

    public int getToolLoopCount() {
        return engine.getToolLoopCount();
    }

    public String getCurrentResponse() {
        return engine.getCurrentResponse();
    }

    public String getCurrentThinking() {
        return engine.getCurrentThinking();
    }

    public void shutdown() {
        engine.shutdown();
    }
}
