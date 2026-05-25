package com.oilquiz.app.ai.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.SmartIntentRecognizer;
import com.oilquiz.app.ai.agent.UnifiedAgentEngine;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.service.AIService;
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
        this(activity, aiService, null, agentService, callback, false);
    }

    public AgentChatHandler(Activity activity, AIService aiService, InferenceRouter inferenceRouter,
                            AgentService agentService, AgentChatCallback callback, boolean useOnlineModel) {
        this.activity = activity;
        this.agentService = agentService;
        this.callback = callback;
        this.engine = new UnifiedAgentEngine(activity, aiService, inferenceRouter, agentService, useOnlineModel);
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
                    // 保护：result可能为null
                    boolean success = result != null && result.success;
                    String resultStr = result != null ? result.result : "工具执行返回null";
                    callback.onToolCallResultUI(-1, success, resultStr);
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

            // ========== 新增回调方法 ==========

            @Override
            public void onNeedMoreInfo(String missingInfo, String context, java.util.List<String> suggestions) {
                AILogger.i(TAG, "onNeedMoreInfo: " + missingInfo);
                if (callback != null) {
                    // 显示输入对话框
                    showInputDialog(missingInfo, context, suggestions);
                }
            }

            @Override
            public void onExecutionPaused(String reason, String currentState) {
                AILogger.i(TAG, "onExecutionPaused: " + reason);
                if (callback != null) {
                    // 显示暂停状态
                    callback.onAgentStep(new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.PAUSED,
                        engine.getToolLoopCount() + 1,
                        0
                    ));
                }
            }

            @Override
            public void onExecutionResuming(String userInput) {
                AILogger.i(TAG, "onExecutionResuming: " + userInput);
                // 恢复中，通知用户
            }

            @Override
            public void onThinking(String thought) {
                AILogger.i(TAG, "onThinking: " + thought);
                if (callback != null) {
                    // 显示思考内容
                    ChatMessage.AgentStepInfo stepInfo = new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING,
                        0,
                        0
                    );
                    stepInfo.thought = thought;
                    stepInfo.isCompleted = false;
                    callback.onAgentStep(stepInfo);
                }
            }

            @Override
            public void onThinkingStage(String stage) {
                AILogger.i(TAG, "onThinkingStage: " + stage);
                if (callback != null) {
                    // 可以在UI上显示阶段变化
                    callback.onAgentStep(new ChatMessage.AgentStepInfo(
                        ChatMessage.AgentStepInfo.AgentStepType.THINKING,
                        0,
                        0
                    ));
                }
            }

            @Override
            public void onInputValidationResult(String paramName, com.oilquiz.app.ai.agent.InputValidator.ValidationResult result) {
                AILogger.i(TAG, "onInputValidationResult: " + paramName + ", valid=" + result.valid);
                if (callback != null) {
                    if (result.valid) {
                        // 验证成功
                        AILogger.i(TAG, "Input validation passed, continuing...");
                    } else {
                        // 验证失败，显示错误
                        String errorMsg = com.oilquiz.app.ai.agent.InputValidator.generateErrorMessage(result, paramName);
                        showValidationError(paramName, errorMsg, result.correctFormat, result.examples);
                    }
                }
            }

            // ==========================================
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

    // ========== UI 交互方法 ==========

    /**
     * 显示输入对话框，请求用户补充信息
     */
    private void showInputDialog(final String missingInfo, final String context, final List<String> suggestions) {
        activity.runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            LayoutInflater inflater = activity.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.dialog_input_parameter, null);
            
            // 获取视图组件
            TextView titleText = dialogView.findViewById(R.id.dialogTitle);
            TextView promptText = dialogView.findViewById(R.id.promptText);
            TextView contextText = dialogView.findViewById(R.id.contextText);
            TextView suggestLabel = dialogView.findViewById(R.id.suggestLabel);
            TextView suggestionsText = dialogView.findViewById(R.id.suggestionsText);
            EditText input = dialogView.findViewById(R.id.inputField);
            Button btnCancel = dialogView.findViewById(R.id.btnCancel);
            Button btnSubmit = dialogView.findViewById(R.id.btnSubmit);
            
            // 设置内容
            promptText.setText(missingInfo);
            
            // 显示上下文
            if (context != null && !context.isEmpty()) {
                contextText.setText(activity.getString(R.string.dialog_task_prefix) + context);
                contextText.setVisibility(View.VISIBLE);
            }
            
            // 显示建议
            if (suggestions != null && !suggestions.isEmpty()) {
                suggestLabel.setText(R.string.dialog_suggestions_label);
                suggestLabel.setVisibility(View.VISIBLE);
                
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < suggestions.size(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append("• ").append(suggestions.get(i));
                }
                suggestionsText.setText(sb.toString());
                suggestionsText.setVisibility(View.VISIBLE);
            }
            
            // 设置输入框提示
            input.setHint(R.string.dialog_need_more_info_hint);
            
            // 设置按钮监听器
            btnSubmit.setOnClickListener(v -> {
                String userInput = input.getText().toString().trim();
                if (!TextUtils.isEmpty(userInput)) {
                    // 调用 resumeExecution 恢复执行
                    engine.resumeExecution(userInput);
                } else {
                    Toast.makeText(activity, R.string.dialog_input_empty, Toast.LENGTH_SHORT).show();
                }
            });
            
            builder.setView(dialogView);
            builder.setCancelable(false);
            builder.show();
        });
    }
    
    /**
     * 显示验证错误
     */
    private void showValidationError(String paramName, String errorMsg, 
                                      String correctFormat, List<String> examples) {
        activity.runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            LayoutInflater inflater = activity.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.dialog_validation_error, null);
            
            // 获取视图组件
            TextView titleText = dialogView.findViewById(R.id.dialogTitle);
            TextView paramNameText = dialogView.findViewById(R.id.paramNameText);
            TextView errorMessageText = dialogView.findViewById(R.id.errorMessageText);
            TextView formatLabel = dialogView.findViewById(R.id.formatLabel);
            TextView formatText = dialogView.findViewById(R.id.formatText);
            TextView exampleLabel = dialogView.findViewById(R.id.exampleLabel);
            TextView examplesText = dialogView.findViewById(R.id.examplesText);
            TextView retryCountText = dialogView.findViewById(R.id.retryCountText);
            View warningContainer = dialogView.findViewById(R.id.warningContainer);
            Button btnCancel = dialogView.findViewById(R.id.btnCancel);
            Button btnRetry = dialogView.findViewById(R.id.btnRetry);
            
            // 设置标题
            String fullTitle = activity.getString(R.string.dialog_validation_error_title) + " - " + paramName;
            titleText.setText(fullTitle);
            paramNameText.setText(paramName);
            
            // 设置错误信息
            StringBuilder errorBuilder = new StringBuilder();
            errorBuilder.append(activity.getString(R.string.dialog_error_prefix, paramName));
            errorBuilder.append("\n\n");
            errorBuilder.append(errorMsg);
            errorMessageText.setText(errorBuilder.toString());
            
            // 显示正确格式
            if (correctFormat != null) {
                formatLabel.setVisibility(View.VISIBLE);
                formatLabel.setText(R.string.dialog_correct_format_label);
                formatText.setText(correctFormat);
                formatText.setVisibility(View.VISIBLE);
            }
            
            // 显示示例
            if (examples != null && !examples.isEmpty()) {
                exampleLabel.setVisibility(View.VISIBLE);
                exampleLabel.setText(R.string.dialog_examples_label);
                
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(3, examples.size()); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append("• ").append(examples.get(i));
                }
                examplesText.setText(sb.toString());
                examplesText.setVisibility(View.VISIBLE);
            }
            
            // 显示重试次数
            int retryCount = engine.getRetryCount();
            retryCountText.setText(activity.getString(R.string.dialog_retry_count, retryCount));
            
            // 如果只剩1次机会，显示警告
            if (retryCount >= 2) {
                warningContainer.setVisibility(View.VISIBLE);
            } else {
                warningContainer.setVisibility(View.GONE);
            }
            
            // 设置按钮监听器
            btnRetry.setOnClickListener(v -> {
                // 重新显示输入对话框
                showInputDialog("请输入正确的" + paramName, errorMsg, examples);
            });
            
            btnCancel.setOnClickListener(v -> {
                engine.cancelPause();
            });
            
            builder.setView(dialogView);
            builder.setCancelable(false);
            builder.show();
        });
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
