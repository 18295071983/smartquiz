package com.oilquiz.app.ai.chat.mode;

import android.app.Activity;
import android.util.Log;

import com.oilquiz.app.ai.chat.AgentChatHandler;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AgentModeHandler implements ModeHandler {
    private static final String TAG = "AgentMode";
    
    private final Activity activity;
    private final AIService aiService;
    private final AgentService agentService;
    private final Set<String> activeMessageIds;
    
    public AgentModeHandler(Activity activity, AIService aiService, AgentService agentService) {
        this.activity = activity;
        this.aiService = aiService;
        this.agentService = agentService;
        this.activeMessageIds = ConcurrentHashMap.newKeySet();
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
        
        ChatMessage initialAiMessage = createInitialAIMessage(messageId, userMessage);
        if (callback != null) {
            callback.onMessageCreated(messageId, initialAiMessage);
        }
        
        Log.i(TAG, "Starting agent mode for message: " + messageId);
        
        try {
            AgentChatHandler agentHandler = new AgentChatHandler(
                activity, aiService, agentService,
                new AgentChatHandler.AgentChatCallback() {
                    private final StringBuilder fullResponse = new StringBuilder();
                    private long startTime = System.currentTimeMillis();
                    private int tokenCount = 0;
                    
                    @Override
                    public void onToolCallStart(String toolName, String args) {
                        if (!activeMessageIds.contains(messageId)) return;
                        Log.i(TAG, "Tool call start: " + toolName + " for " + messageId);
                        sendThinkingUpdate(callback, messageId, 0, "TOOL_CALL", 
                            "调用工具: " + toolName, args, -1);
                    }
                    
                    @Override
                    public void onToolCallComplete(String toolName, AgentService.ToolResult result) {
                        if (!activeMessageIds.contains(messageId)) return;
                        Log.i(TAG, "Tool call complete: " + toolName + ", success=" + result.success);
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
                        Log.i(TAG, "Agent mode completed: " + messageId + ", tokens: " + tokenCount);
                    }
                    
                    @Override
                    public void onError(String error) {
                        if (!activeMessageIds.contains(messageId)) return;
                        
                        if (callback != null) {
                            callback.onError(messageId, error);
                        }
                        activeMessageIds.remove(messageId);
                        Log.e(TAG, "Agent mode error: " + messageId + ", error: " + error);
                    }
                    
                    @Override
                    public void onModeSwitched(String mode) {
                    }
                    
                    @Override
                    public void onAgentStep(ChatMessage.AgentStepInfo stepInfo) {
                        if (!activeMessageIds.contains(messageId)) return;
                        Log.d(TAG, "Agent step: " + stepInfo.thought);
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
                }
            );
            
            agentHandler.startAgentLoop(userMessage.content, 1024, true);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start agent mode", e);
            if (callback != null) {
                callback.onError(messageId, "Agent执行失败: " + e.getMessage());
            }
            activeMessageIds.remove(messageId);
        }
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
            Log.i(TAG, "Agent mode cancelled: " + messageId);
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
