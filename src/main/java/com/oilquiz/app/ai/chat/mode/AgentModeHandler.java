package com.oilquiz.app.ai.chat.mode;

import android.content.Context;

import com.oilquiz.app.ai.agent.AgentExecutionEngine;
import com.oilquiz.app.ai.agent.AgentExecutionState;
import com.oilquiz.app.ai.agent.ExecutionEvent;
import com.oilquiz.app.ai.agent.ExecutionEventListener;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

/**
 * Agent 模式处理器 - 使用 AgentExecutionEngine 完全与UI解耦
 *
 * 架构：
 *   AgentModeHandler（协调者，不执行模型）
 *         ↓ 委托执行
 *   AgentExecutionEngine（后台引擎，独立线程）
 *         ↓ 更新数据
 *   AgentExecutionState（纯数据结构）
 *         ↓ 事件通知
 *   ExecutionEventListener → ModeHandlerCallback → UI
 *
 * 特点：
 * 1. 不持有 Activity 引用
 * 2. 模型执行完全在后台线程
 * 3. 通过事件驱动UI更新
 * 4. native崩溃被引擎隔离
 */
public class AgentModeHandler implements ModeHandler {
    private static final String TAG = "AgentMode";

    private final Context appContext;
    private final AIService aiService;
    private final AgentService agentService;
    private final AIConfig aiConfig;
    private final AgentExecutionEngine engine;

    public AgentModeHandler(Context context, AIService aiService,
                            AgentService agentService, AIConfig aiConfig) {
        this.appContext = context.getApplicationContext();
        this.aiService = aiService;
        this.agentService = agentService;
        this.aiConfig = aiConfig;
        this.engine = new AgentExecutionEngine(appContext, aiService, agentService, aiConfig);
    }

    @Override
    public void handleMessage(ChatMessage userMessage, String messageId, ModeHandlerCallback callback) {
        if (userMessage == null || userMessage.content == null) {
            if (callback != null) {
                callback.onError(messageId, "Invalid message");
            }
            return;
        }

        AILogger.i(TAG, "Starting agent mode for message: " + messageId);

        // 检查 Agent 是否启用
        if (aiConfig == null || !aiConfig.isAgentEnabled()) {
            AILogger.e(TAG, "Agent is not enabled");
            if (callback != null) {
                callback.onError(messageId, "请先在AI设置中开启Agent功能");
            }
            return;
        }

        // 创建初始 AI 消息
        ChatMessage initialAiMessage = createInitialAIMessage(messageId, userMessage);
        if (callback != null) {
            callback.onMessageCreated(messageId, initialAiMessage);
        }

        // 委托给引擎执行，通过事件回调通知UI
        engine.execute(messageId, userMessage.content, new ExecutionEventListener() {
            @Override
            public void onExecutionEvent(ExecutionEvent event) {
                if (callback == null) return;
                handleEngineEvent(event, messageId, callback);
            }
        });
    }

    /**
     * 将引擎事件转换为ModeHandlerCallback调用
     */
    private void handleEngineEvent(ExecutionEvent event, String messageId, ModeHandlerCallback callback) {
        switch (event.type) {
            case EXECUTION_STARTED:
                // 引擎已启动，无需额外通知
                break;

            case TOKEN_GENERATED:
                if (event.text != null) {
                    callback.onToken(messageId, event.text);
                }
                break;

            case THINKING_TOKEN:
                if (event.text != null) {
                    // 只更新Agent执行面板的思考过程，不显示在聊天文本中
                    callback.onThinkingUpdate(messageId, new StreamingEvent.ThinkingStepData(
                        0, "THINKING", "思考过程", event.text, 50
                    ));
                }
                break;

            case STEP_STARTED:
                StreamingEvent.ThinkingStepData stepData = new StreamingEvent.ThinkingStepData(
                    event.stepNumber, event.stepType, event.stepTitle, event.stepContent, event.progress
                );
                callback.onThinkingUpdate(messageId, stepData);
                break;

            case TOOL_CALL_STARTED:
                callback.onThinkingUpdate(messageId, new StreamingEvent.ThinkingStepData(
                    0, "TOOL_CALL", "调用工具: " + event.toolName, event.toolArgs, 50
                ));
                break;

            case TOOL_CALL_COMPLETED:
                String resultContent = "执行结果: " + (event.success ? "成功" : "失败") + "\n";
                if (event.toolResult != null) {
                    resultContent += "返回内容:\n" + event.toolResult;
                }
                callback.onThinkingUpdate(messageId, new StreamingEvent.ThinkingStepData(
                    0, "TOOL_RESULT", "工具执行完成: " + event.toolName, resultContent, 100
                ));
                break;

            case INFERENCE_PROGRESS:
                callback.onInferenceProgress(messageId, event.tokenCount, event.tokensPerSecond);
                break;

            case EXECUTION_COMPLETED:
                callback.onComplete(messageId, event.text, null);
                break;

            case EXECUTION_FAILED:
                callback.onError(messageId, event.errorMessage);
                break;

            case NEED_USER_INPUT:
                // UI层应处理此事件显示输入对话框
                AILogger.i(TAG, "Need user input: " + event.missingInfo);
                break;

            case VALIDATION_ERROR:
                // UI层应处理此事件显示验证错误
                AILogger.w(TAG, "Validation error: " + event.errorMessage);
                break;

            case MODE_SWITCHED:
                callback.onThinkingUpdate(messageId, new StreamingEvent.ThinkingStepData(
                    0, "MODE_SWITCH", "切换模式", "当前模式: " + event.text, 0
                ));
                break;

            default:
                break;
        }
    }

    @Override
    public void cancel(String messageId) {
        if (messageId != null) {
            engine.cancel(messageId);
            AILogger.i(TAG, "Agent mode cancelled: " + messageId);
        }
    }

    @Override
    public String getModeName() {
        return "agent";
    }

    /**
     * 获取执行状态（供UI读取）
     */
    public AgentExecutionState getExecutionState(String messageId) {
        return engine.getState(messageId);
    }

    private ChatMessage createInitialAIMessage(String messageId, ChatMessage userMessage) {
        return new ChatMessage.Builder(ChatMessage.MessageType.AI)
            .id(messageId)
            .parentId(userMessage.id)
            .content("")
            .status(ChatMessage.MessageStatus.GENERATING)
            .agentMode(true)
            .build();
    }
}