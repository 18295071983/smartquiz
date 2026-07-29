package com.oilquiz.app.ai.chat;

import android.app.Activity;
import android.util.Log;

import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.chat.event.StreamingSubscriber;
import com.oilquiz.app.ai.chat.mode.AgentModeHandler;
import com.oilquiz.app.ai.chat.mode.CreativeWritingModeHandler;
import com.oilquiz.app.ai.chat.mode.DeepThinkingModeHandler;
import com.oilquiz.app.ai.chat.mode.ModeHandler;
import com.oilquiz.app.ai.chat.mode.NormalModeHandler;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatOrchestrator {
    private static final String TAG = "ChatOrchestrator";
    
    private static volatile ChatOrchestrator instance;
    
    private final ChatModeManager modeManager;
    private final AIService aiService;
    private final AgentService agentService;
    private final AIConfig aiConfig;
    private final StreamingBroadcaster broadcaster;
    private final MessageQueue messageQueue;
    
    private final Map<ChatModeManager.ChatMode, ModeHandler> modeHandlers = new HashMap<>();
    private final Map<String, StringBuilder> messageContents = new ConcurrentHashMap<>();
    private final Map<String, ChatMessage> messageMap = new ConcurrentHashMap<>();
    private final Map<String, ChatModeManager.ChatMode> messageModes = new ConcurrentHashMap<>();
    private final Map<String, Integer> messageTokenCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> messageStartTimes = new ConcurrentHashMap<>();
    // 模型推理的实际token数和速度（从onInferenceProgress回调获取，比UI收到的token数更准确）
    private final Map<String, Integer> messageInferenceTokens = new ConcurrentHashMap<>();
    private final Map<String, Float> messageInferenceTps = new ConcurrentHashMap<>();
    
    private Activity activity;
    private OrchestratorListener listener;
    
    private ChatOrchestrator(Activity activity, AIService aiService, AgentService agentService) {
        this.activity = activity;
        this.aiService = aiService;
        this.agentService = agentService;
        this.aiConfig = new AIConfig(activity);
        this.modeManager = ChatModeManager.getInstance(activity);
        this.broadcaster = StreamingBroadcaster.getInstance();
        this.messageQueue = MessageQueue.getInstance();
        
        initModeHandlers();
        initMessageQueue();
        initModeManager();
    }
    
    public static ChatOrchestrator getInstance(Activity activity, AIService aiService, AgentService agentService) {
        if (instance == null) {
            synchronized (ChatOrchestrator.class) {
                if (instance == null) {
                    instance = new ChatOrchestrator(activity, aiService, agentService);
                }
            }
        }
        return instance;
    }
    
    public static ChatOrchestrator getInstance() {
        return instance;
    }
    
    private void initModeHandlers() {
        modeHandlers.put(ChatModeManager.ChatMode.NORMAL, new NormalModeHandler(aiService));
        modeHandlers.put(ChatModeManager.ChatMode.DEEP_THINKING, new DeepThinkingModeHandler(aiService));
        modeHandlers.put(ChatModeManager.ChatMode.CREATIVE, new CreativeWritingModeHandler(aiService));
        if (activity != null) {
            modeHandlers.put(ChatModeManager.ChatMode.AGENT, new AgentModeHandler(activity, aiService, agentService, aiConfig));
        }
        Log.i(TAG, "Mode handlers initialized: " + modeHandlers.size());
    }
    
    private void initMessageQueue() {
        messageQueue.setListener(new MessageQueue.SimpleQueueListener() {
            @Override
            public void onMessageReady(String messageId, ChatMessage message) {
                processMessage(messageId, message);
            }
            
            @Override
            public void onMessageCancelled(String messageId) {
                cancelMessageProcessing(messageId);
            }
        });
    }
    
    private void initModeManager() {
        modeManager.setOnModeChangeListener(new ChatModeManager.OnModeChangeListener() {
            @Override
            public void onModeChanged(ChatModeManager.ChatMode newMode, boolean isAuto) {
                Log.i(TAG, "Mode changed: " + newMode.displayName + ", auto=" + isAuto);
                
                // 更新聊天上下文提示词（不销毁上下文）
                updateContextForMode(newMode);
                
                if (listener != null) {
                    listener.onModeChanged(newMode, isAuto);
                }
            }
            
            @Override
            public void onModeSwitchRequested(ChatModeManager.ChatMode requestedMode, boolean duringGeneration) {
                Log.i(TAG, "Mode switch requested: " + requestedMode.displayName + ", duringGeneration=" + duringGeneration);
                if (listener != null) {
                    listener.onModeSwitchPending(requestedMode, duringGeneration);
                }
            }
            
            @Override
            public void onAutoModeChanged(boolean enabled) {
                Log.i(TAG, "Auto mode changed: " + enabled);
            }
        });
    }
    
    /**
     * 更新聊天上下文以匹配新模式
     * 使用 updateChatPrompts 而不是 initChatContext，避免销毁上下文
     */
    private void updateContextForMode(ChatModeManager.ChatMode mode) {
        if (aiService == null || !aiService.isInitialized()) {
            return;
        }
        
        ChatModeManager.ModeContextPrompts prompts = modeManager.getContextPromptsForMode(mode);
        
        new Thread(() -> {
            try {
                boolean updated = aiService.updateChatPrompts(
                    prompts.globalPrompt, 
                    prompts.systemPrompt, 
                    prompts.normalPrompt
                );
                if (updated) {
                    Log.i(TAG, "Chat context prompts updated for mode: " + mode.displayName);
                } else {
                    Log.w(TAG, "Failed to update chat context prompts for mode: " + mode.displayName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating context for mode: " + e.getMessage());
            }
        }).start();
    }
    
    public void setListener(OrchestratorListener listener) {
        this.listener = listener;
    }
    
    public ChatModeManager getModeManager() {
        return modeManager;
    }
    
    public void sendMessage(String content, List<ChatMessage.Attachment> attachments) {
        if (content == null || content.trim().isEmpty()) {
            Log.w(TAG, "sendMessage: empty content");
            return;
        }
        
        ChatModeManager.ChatMode mode = modeManager.determineMode(content);
        Log.i(TAG, "sendMessage: content=" + truncate(content, 50) + ", mode=" + mode.displayName);
        
        String messageId = messageQueue.generateMessageId();
        
        ChatMessage userMessage = new ChatMessage.Builder(ChatMessage.MessageType.USER)
            .id(messageId + "_user")
            .content(content)
            .attachments(attachments)
            .status(ChatMessage.MessageStatus.SENT)
            .build();
        
        if (listener != null) {
            listener.onUserMessage(userMessage);
        }
        
        messageQueue.submitMessage(messageId, userMessage);
    }
    
    private void processMessage(String messageId, ChatMessage userMessage) {
        ChatModeManager.ChatMode mode = modeManager.getCurrentMode();
        messageModes.put(messageId, mode);
        messageContents.put(messageId, new StringBuilder());
        messageTokenCounts.put(messageId, 0);
        messageStartTimes.put(messageId, System.currentTimeMillis());
        messageInferenceTokens.put(messageId, 0);
        messageInferenceTps.put(messageId, 0f);
        
        modeManager.setGeneratingState(true);
        
        Log.i(TAG, "Processing message: " + messageId + ", mode=" + mode.displayName);
        
        ModeHandler handler = modeHandlers.get(mode);
        if (handler == null) {
            handler = modeHandlers.get(ChatModeManager.ChatMode.NORMAL);
        }
        
        try {
            handler.handleMessage(userMessage, messageId, new ModeHandler.ModeHandlerCallback() {
                @Override
                public void onMessageCreated(String msgId, ChatMessage initialMessage) {
                    handleMessageCreated(msgId, initialMessage);
                }
                
                @Override
                public void onToken(String msgId, String token) {
                    handleToken(msgId, token);
                }
                
                @Override
                public void onThinkingUpdate(String msgId, Object thinkingData) {
                    handleThinkingUpdate(msgId, thinkingData);
                }
                
                @Override
                public void onComplete(String msgId, String content, Object stats) {
                    handleComplete(msgId, content, stats);
                }
                
                @Override
                public void onError(String msgId, String error) {
                    handleError(msgId, error);
                }
                
                @Override
                public void onInferenceProgress(String msgId, int tokenCount, float tokensPerSecond) {
                    handleInferenceProgress(msgId, tokenCount, tokensPerSecond);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error in processMessage: " + messageId, t);
            modeManager.setGeneratingState(false);
            String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            handleError(messageId, "本地执行异常: " + errorMsg);
        }
    }
    
    private void handleMessageCreated(String messageId, ChatMessage initialMessage) {
        messageMap.put(messageId, initialMessage);
        broadcaster.broadcastTo(messageId, StreamingEvent.createMessageCreated(messageId, initialMessage.content));
        Log.d(TAG, "Message created: " + messageId);
    }
    
    private void handleToken(String messageId, String token) {
        if (token == null || token.isEmpty()) return;
        
        StringBuilder content = messageContents.get(messageId);
        if (content == null) {
            content = new StringBuilder();
            messageContents.put(messageId, content);
        }
        content.append(token);
        
        Integer count = messageTokenCounts.get(messageId);
        messageTokenCounts.put(messageId, count != null ? count + 1 : 1);
        
        int position = content.length() - token.length();
        broadcaster.broadcastTo(messageId, StreamingEvent.createTokenAppended(messageId, token, position));
    }
    
    private void handleThinkingUpdate(String messageId, Object thinkingData) {
        if (thinkingData instanceof StreamingEvent.ThinkingStepData) {
            broadcaster.broadcastTo(messageId, 
                StreamingEvent.createThinkingUpdate(messageId, (StreamingEvent.ThinkingStepData) thinkingData));
        }
    }
    
    private void handleInferenceProgress(String messageId, int tokenCount, float tokensPerSecond) {
        // 记录模型推理的实际token数和速度
        messageInferenceTokens.put(messageId, tokenCount);
        if (tokensPerSecond > 0) {
            messageInferenceTps.put(messageId, tokensPerSecond);
        }
        
        broadcaster.broadcastTo(messageId, 
            StreamingEvent.createInferenceProgress(messageId, 
                new StreamingEvent.InferenceProgressData(
                    com.oilquiz.app.ai.chat.ChatMessage.InferencePhase.GENERATING,
                    tokenCount,
                    tokensPerSecond
                )
            )
        );
    }
    
    private void handleComplete(String messageId, String finalContent, Object stats) {
        StringBuilder content = messageContents.get(messageId);
        String fullContent = finalContent != null ? finalContent : 
            (content != null ? content.toString() : "");
        
        Integer uiTokenCount = messageTokenCounts.get(messageId);
        Integer inferenceTokens = messageInferenceTokens.get(messageId);
        Float inferenceTps = messageInferenceTps.get(messageId);
        Long startTime = messageStartTimes.get(messageId);
        long elapsed = startTime != null ? System.currentTimeMillis() - startTime : 0;
        
        // 优先使用模型推理的实际token数和速度（比UI收到的token数更准确）
        int finalTokenCount = (inferenceTokens != null && inferenceTokens > 0) 
            ? inferenceTokens 
            : (uiTokenCount != null ? uiTokenCount : 0);
        float finalTps = (inferenceTps != null && inferenceTps > 0)
            ? inferenceTps
            : (elapsed > 0 ? (finalTokenCount * 1000.0f) / elapsed : 0);
        
        StreamingEvent.StatsData statsData = new StreamingEvent.StatsData(
            finalTokenCount, elapsed, finalTps
        );
        
        ChatMessage message = messageMap.get(messageId);
        if (message != null) {
            message.content = fullContent;
            message.tokensGenerated = finalTokenCount;
            message.generationTimeMs = elapsed;
            message.tokensPerSecond = finalTps;
            message.status = ChatMessage.MessageStatus.COMPLETED;
        }
        
        broadcaster.broadcastTo(messageId, 
            StreamingEvent.createMessageCompleted(messageId, fullContent, statsData));
        
        cleanupMessage(messageId);
        modeManager.setGeneratingState(false);
        
        Log.i(TAG, "Message completed: " + messageId + ", tokens=" + finalTokenCount + ", tps=" + finalTps + ", time=" + elapsed + "ms");
        
        if (listener != null) {
            listener.onMessageComplete(messageId, fullContent, finalTokenCount, elapsed);
        }
    }
    
    private void handleError(String messageId, String error) {
        broadcaster.broadcastTo(messageId, StreamingEvent.createMessageFailed(messageId, error != null ? error : "Unknown error"));
        
        ChatMessage message = messageMap.get(messageId);
        if (message != null) {
            message.status = ChatMessage.MessageStatus.FAILED;
            message.errorDetail = error;
        }
        
        cleanupMessage(messageId);
        modeManager.setGeneratingState(false);
        
        Log.e(TAG, "Message error: " + messageId + ", error=" + error);
        
        if (listener != null) {
            listener.onMessageError(messageId, error);
        }
    }
    
    public void cancelGeneration(String messageId) {
        if (messageId == null) return;
        
        messageQueue.cancelMessage(messageId);
        cancelMessageProcessing(messageId);
    }
    
    private void cancelMessageProcessing(String messageId) {
        ChatModeManager.ChatMode mode = messageModes.get(messageId);
        if (mode != null) {
            ModeHandler handler = modeHandlers.get(mode);
            if (handler != null) {
                handler.cancel(messageId);
            }
        }
        
        broadcaster.broadcastTo(messageId, StreamingEvent.createMessageCancelled(messageId));
        cleanupMessage(messageId);
        modeManager.setGeneratingState(false);
        
        Log.i(TAG, "Message cancelled: " + messageId);
    }
    
    public void cancelAll() {
        messageQueue.cancelAll();
        for (String messageId : messageMap.keySet()) {
            cancelMessageProcessing(messageId);
        }
        Log.i(TAG, "All messages cancelled");
    }
    
    private void cleanupMessage(String messageId) {
        messageContents.remove(messageId);
        messageMap.remove(messageId);
        messageModes.remove(messageId);
        messageTokenCounts.remove(messageId);
        messageStartTimes.remove(messageId);
        messageInferenceTokens.remove(messageId);
        messageInferenceTps.remove(messageId);
    }
    
    public void switchMode(ChatModeManager.ChatMode newMode) {
        if (newMode != null) {
            modeManager.setManualMode(newMode);
        }
    }
    
    public ChatModeManager.ChatMode getCurrentMode() {
        return modeManager.getCurrentMode();
    }

    /**
     * 检查指定消息是否为Agent模式
     */
    public boolean isAgentMessage(String messageId) {
        if (messageId == null) return false;
        ChatModeManager.ChatMode mode = messageModes.get(messageId);
        return mode == ChatModeManager.ChatMode.AGENT;
    }
    
    public void setAutoModeEnabled(boolean enabled) {
        modeManager.setAutoModeEnabled(enabled);
    }
    
    public boolean isAutoModeEnabled() {
        return modeManager.isAutoModeEnabled();
    }
    
    public void subscribe(String messageId, StreamingSubscriber subscriber) {
        if (messageId != null && subscriber != null) {
            broadcaster.subscribe(messageId, subscriber);
        }
    }
    
    public void unsubscribe(String messageId, StreamingSubscriber subscriber) {
        if (messageId != null && subscriber != null) {
            broadcaster.unsubscribe(messageId, subscriber);
        }
    }
    
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    public interface OrchestratorListener {
        void onUserMessage(ChatMessage message);
        void onMessageComplete(String messageId, String content, int tokens, long elapsedMs);
        void onMessageError(String messageId, String error);
        void onModeChanged(ChatModeManager.ChatMode newMode, boolean isAuto);
        void onModeSwitchPending(ChatModeManager.ChatMode requestedMode, boolean duringGeneration);
    }
    
    public static class SimpleOrchestratorListener implements OrchestratorListener {
        @Override
        public void onUserMessage(ChatMessage message) {}
        
        @Override
        public void onMessageComplete(String messageId, String content, int tokens, long elapsedMs) {}
        
        @Override
        public void onMessageError(String messageId, String error) {}
        
        @Override
        public void onModeChanged(ChatModeManager.ChatMode newMode, boolean isAuto) {}
        
        @Override
        public void onModeSwitchPending(ChatModeManager.ChatMode requestedMode, boolean duringGeneration) {}
    }
}
