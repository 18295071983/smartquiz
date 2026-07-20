package com.oilquiz.app.ai.chat.mode;

import android.app.Activity;
import android.util.Log;

import com.oilquiz.app.ai.chat.AgentChatHandler;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.util.AILogger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 模式处理器 - 与本地 AIService 完全兼容
 * 
 * 修复点：
 * 1. 使用 ChatModeManager 统一提示词
 * 2. 复用 AgentChatHandler 实例
 * 3. 确保聊天上下文已初始化
 */
public class AgentModeHandler implements ModeHandler {
    private static final String TAG = "AgentMode";
    
    private final Activity activity;
    private final AIService aiService;
    private final AgentService agentService;
    private final Set<String> activeMessageIds;
    
    // 复用 AgentChatHandler 实例，避免每次创建新实例
    private AgentChatHandler sharedAgentHandler;
    
    public AgentModeHandler(Activity activity, AIService aiService, AgentService agentService) {
        this.activity = activity;
        this.aiService = aiService;
        this.agentService = agentService;
        this.activeMessageIds = ConcurrentHashMap.newKeySet();
    }
    
    /**
     * 获取或创建共享的 AgentChatHandler
     */
    private AgentChatHandler getOrCreateAgentHandler(AgentChatHandler.AgentChatCallback callback) {
        if (sharedAgentHandler == null) {
            sharedAgentHandler = new AgentChatHandler(
                activity, aiService, agentService, callback);
        }
        return sharedAgentHandler;
    }
    
    @Override
    public void handleMessage(ChatMessage userMessage, String messageId, ModeHandlerCallback callback) {
        if (userMessage == null || userMessage.content == null) {
            if (callback != null) {
                callback.onError(messageId, "Invalid message");
            }
            return;
        }
        
        activeMessageIds.add(messageId);
        
        // 创建初始 AI 消息
        ChatMessage initialAiMessage = createInitialAIMessage(messageId, userMessage);
        if (callback != null) {
            callback.onMessageCreated(messageId, initialAiMessage);
        }
        
        AILogger.i(TAG, "Starting agent mode for message: " + messageId);
        
        try {
            // 确保聊天上下文已初始化
            ensureChatContext();
            
            // 使用 ChatModeManager 的统一 Agent 提示词
            String systemPrompt = ChatModeManager.getInstance(activity)
                .getModeSystemPrompt(ChatModeManager.ChatMode.AGENT);
            AILogger.i(TAG, "Agent system prompt: " + systemPrompt.substring(0, Math.min(50, systemPrompt.length())) + "...");
            
            // 创建回调
            AgentChatHandler.AgentChatCallback agentCallback = createAgentCallback(messageId, callback);
            
            // 获取或创建 AgentChatHandler（复用实例）
            AgentChatHandler agentHandler = getOrCreateAgentHandler(agentCallback);
            
            // 启动 Agent 循环
            agentHandler.startAgentLoop(userMessage.content, 1024, true);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to start agent mode", e);
            if (callback != null) {
                callback.onError(messageId, "Agent执行失败: " + e.getMessage());
            }
            activeMessageIds.remove(messageId);
        }
    }
    
    /**
     * 确保聊天上下文已初始化
     */
    private void ensureChatContext() {
        if (aiService != null && !LlamaHelper.isChatContextActive()) {
            AILogger.i(TAG, "Chat context not active, initializing...");
            aiService.initChatContext("", "", "");
        }
    }
    
    /**
     * 创建 Agent 回调
     */
    private AgentChatHandler.AgentChatCallback createAgentCallback(
            String messageId, ModeHandlerCallback callback) {
        return new AgentChatHandler.AgentChatCallback() {
            private final StringBuilder fullResponse = new StringBuilder();
            private long startTime = System.currentTimeMillis();
            private int tokenCount = 0;
            
            @Override
            public void onToolCallStart(String toolName, String args) {
                if (!activeMessageIds.contains(messageId)) return;
                AILogger.i(TAG, "Tool call start: " + toolName + " for " + messageId);
                sendThinkingUpdate(callback, messageId, 0, "TOOL_CALL", 
                    "调用工具: " + toolName, args, -1);
            }
            
            @Override
            public void onToolCallComplete(String toolName, AgentService.ToolResult result) {
                if (!activeMessageIds.contains(messageId)) return;
                AILogger.i(TAG, "Tool call complete: " + toolName + ", success=" + result.success);
            }
            
            @Override
            public void onToken(String token) {
                if (!activeMessageIds.contains(messageId)) return;
                if (token != null) {
                    fullResponse.append(token);
                    tokenCount++;
                    if (callback != null) {
                        callback.onToken(messageId, token);
                    }
                }
            }
            
            @Override
            public void onThinkingToken(String token) {
                if (!activeMessageIds.contains(messageId)) return;
            }
            
            @Override
            public void onThinkingEnd() {
            }
            
            @Override
            public void onComplete(String fullText) {
                if (!activeMessageIds.contains(messageId)) return;
                
                long elapsed = System.currentTimeMillis() - startTime;
                float tps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;
                
                if (callback != null) {
                    Object stats = createStats(tokenCount, elapsed, tps);
                    callback.onComplete(messageId, 
                        fullText != null ? fullText : fullResponse.toString(), stats);
                }
                activeMessageIds.remove(messageId);
                AILogger.i(TAG, "Agent mode completed: " + messageId + ", tokens: " + tokenCount);
            }
            
            @Override
            public void onError(String error) {
                if (!activeMessageIds.contains(messageId)) return;
                
                if (callback != null) {
                    callback.onError(messageId, error);
                }
                activeMessageIds.remove(messageId);
                AILogger.e(TAG, "Agent mode error: " + messageId + ", error: " + error);
            }
            
            @Override
            public void onModeSwitched(String mode) {
            }
            
            @Override
            public void onAgentStep(ChatMessage.AgentStepInfo stepInfo) {
                if (!activeMessageIds.contains(messageId)) return;
                AILogger.d(TAG, "Agent step: " + stepInfo.thought);
            }
            
            @Override
            public void onToolCallUI(String toolName, String args, int position) {
            }
            
            @Override
            public void onToolCallResultUI(int position, boolean success, String result) {
            }
            
            @Override
            public void onAgentStepUpdateUI(int position, String thought, String action,
                                            String observation, boolean isCompleted) {
            }

            @Override
            public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
                if (!activeMessageIds.contains(messageId)) return;

                // 更新本地token统计
                long elapsed = System.currentTimeMillis() - startTime;
                float currentTps = elapsed > 0 ? (tokenCount * 1000.0f) / elapsed : 0;

                AILogger.d(TAG, "Inference progress: tokens=" + tokenCount
                    + ", tps=" + String.format("%.1f", currentTps)
                    + ", elapsed=" + elapsed + "ms");

                // 传递推理进度到上层回调
                if (callback != null) {
                    callback.onInferenceProgress(messageId, tokenCount, tokensPerSecond);
                }
            }
        };
    }
    
    private void sendThinkingUpdate(ModeHandlerCallback callback, String messageId,
                                     int stepNumber, String stepType, String title,
                                     String content, int progress) {
        if (callback != null && activeMessageIds.contains(messageId)) {
            StreamingEvent.ThinkingStepData stepData = new StreamingEvent.ThinkingStepData(
                stepNumber, stepType, title, content, progress
            );
            callback.onThinkingUpdate(messageId, stepData);
        }
    }
    
    private Object createStats(int tokens, long elapsedMs, float tps) {
        return new Object() {
            public int totalTokens = tokens;
            public long generationTimeMs = elapsedMs;
            public float tokensPerSecond = tps;
        };
    }
    
    @Override
    public void cancel(String messageId) {
        if (messageId != null) {
            activeMessageIds.remove(messageId);
            AILogger.i(TAG, "Agent mode cancelled: " + messageId);
        }
    }
    
    @Override
    public String getModeName() {
        return "agent";
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
