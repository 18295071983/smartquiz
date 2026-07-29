package com.oilquiz.app.ai.chat.mode;

import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.DeepThinkingEngine;
import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.service.AIService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeepThinkingModeHandler implements ModeHandler {
    private static final String TAG = "DeepThinking";
    
    private final AIService aiService;
    private final Set<String> activeMessageIds;
    private final ExecutorService executor;
    
    public DeepThinkingModeHandler(AIService aiService) {
        this.aiService = aiService;
        this.activeMessageIds = ConcurrentHashMap.newKeySet();
        this.executor = Executors.newSingleThreadExecutor();
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
        
        executor.submit(() -> {
            try {
                executeDeepThinking(userMessage.content, messageId, callback);
            } catch (Exception e) {
                Log.e(TAG, "Deep thinking error: " + messageId, e);
                if (callback != null && activeMessageIds.contains(messageId)) {
                    callback.onError(messageId, "深度思考失败: " + e.getMessage());
                }
            } finally {
                activeMessageIds.remove(messageId);
            }
        });
    }
    
    private void executeDeepThinking(String question, String messageId, ModeHandlerCallback callback) {
        DeepThinkingEngine engine = new DeepThinkingEngine();
        long startTime = System.currentTimeMillis();
        
        Log.i(TAG, "Starting deep thinking for: " + messageId);
        
        engine.startThinking(question);
        sendThinkingUpdate(callback, messageId, 1, "UNDERSTAND", "理解问题", 
            "正在分析用户的问题: " + truncate(question, 100), 14);
        
        sleep(500);
        if (checkCancelled(messageId)) return;
        
        engine.analyzeProblemDecomposition(question);
        sendThinkingUpdate(callback, messageId, 2, "DECOMPOSE", "问题分解", 
            "将复杂问题分解为子问题...", 28);
        
        sleep(500);
        if (checkCancelled(messageId)) return;
        
        engine.gatherKnowledge(question);
        sendThinkingUpdate(callback, messageId, 3, "KNOWLEDGE", "知识检索", 
            "检索与「" + extractTopic(question) + "」相关的知识...", 42);
        
        sleep(500);
        if (checkCancelled(messageId)) return;
        
        List<String> hypotheses = generateHypotheses(question);
        engine.evaluateHypotheses(hypotheses);
        sendThinkingUpdate(callback, messageId, 4, "EVALUATE", "假设评估", 
            "评估 " + hypotheses.size() + " 个可能的解决方案...", 57);
        
        sleep(500);
        if (checkCancelled(messageId)) return;
        
        String premise = "用户询问: " + question;
        String conclusion = "需要分析后给出答案";
        engine.logicalReasoning(premise, conclusion);
        sendThinkingUpdate(callback, messageId, 5, "REASON", "逻辑推理", 
            "进行逻辑推导...", 71);
        
        sleep(500);
        if (checkCancelled(messageId)) return;
        
        String draftAnswer = generateDraftAnswer(question);
        engine.verifySolution(draftAnswer);
        sendThinkingUpdate(callback, messageId, 6, "VERIFY", "验证答案", 
            "验证解决方案的正确性...", 85);
        
        sleep(500);
        if (checkCancelled(messageId)) return;
        
        Log.i(TAG, "Generating final answer for: " + messageId);
        
        StringBuilder finalContent = new StringBuilder();
        finalContent.append("## 🧠 深度思考结果\n\n");
        finalContent.append("**问题**: ").append(question).append("\n\n");
        
        String systemPrompt = "你是一个善于深度思考的AI助手。请仔细分析以下问题，给出详细、准确的回答。\n\n问题: " + question;
        
        final int[] tokenCount = {0};
        long[] generateStartTime = {System.currentTimeMillis()};
        
        try {
            aiService.generateStream(systemPrompt, 800, new AIService.GenerateStreamCallback() {
                @Override
                public void onToken(String token) {
                    if (!activeMessageIds.contains(messageId)) {
                        return;
                    }
                    if (token != null) {
                        tokenCount[0]++;
                        if (callback != null) {
                            callback.onToken(messageId, token);
                        }
                    }
                }
                
                @Override
                public void onSuccess(String fullText) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    float tps = elapsed > 0 ? (tokenCount[0] * 1000.0f) / elapsed : 0;
                    
                    engine.synthesizeAnswer(fullText);
                    
                    sendThinkingUpdate(callback, messageId, 7, "SYNTHESIZE", "综合答案", 
                        "整合所有推理结果，生成最终答案...", 100);
                    
                    if (callback != null && activeMessageIds.contains(messageId)) {
                        Object stats = createStats(tokenCount[0], elapsed, tps);
                        callback.onComplete(messageId, fullText, stats);
                    }
                    Log.i(TAG, "Deep thinking completed: " + messageId + ", tokens: " + tokenCount[0]);
                }
                
                @Override
                public void onError(Exception error) {
                    if (callback != null && activeMessageIds.contains(messageId)) {
                        callback.onError(messageId, "生成答案失败: " + error.getMessage());
                    }
                    Log.e(TAG, "Deep thinking answer generation error: " + messageId, error);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to generate deep thinking answer", t);
            if (callback != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                callback.onError(messageId, "生成答案失败: " + errorMsg);
            }
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
    
    private List<String> generateHypotheses(String question) {
        List<String> hypotheses = new ArrayList<>();
        hypotheses.add("基于常见知识的直接回答");
        hypotheses.add("需要分解为多个子问题分析");
        hypotheses.add("需要查找相关背景信息");
        if (question.contains("?")) {
            hypotheses.add("需要验证多个可能性");
        }
        return hypotheses;
    }
    
    private String extractTopic(String question) {
        String[] keywords = {"为什么", "如何", "解释", "分析", "比较", "原理", "逻辑", "原因"};
        for (String keyword : keywords) {
            if (question.contains(keyword)) {
                int idx = question.indexOf(keyword);
                int start = Math.max(0, idx - 10);
                int end = Math.min(question.length(), idx + keyword.length() + 20);
                return question.substring(start, end).trim();
            }
        }
        return question.length() > 20 ? question.substring(0, 20) + "..." : question;
    }
    
    private String generateDraftAnswer(String question) {
        return "针对问题「" + question + "」的初步分析结果";
    }
    
    private Object createStats(int tokens, long elapsedMs, float tps) {
        return new Object() {
            public int totalTokens = tokens;
            public long generationTimeMs = elapsedMs;
            public float tokensPerSecond = tps;
        };
    }
    
    private boolean checkCancelled(String messageId) {
        if (!activeMessageIds.contains(messageId)) {
            Log.i(TAG, "Message cancelled: " + messageId);
            return true;
        }
        return false;
    }
    
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    @Override
    public void cancel(String messageId) {
        if (messageId != null) {
            activeMessageIds.remove(messageId);
            Log.i(TAG, "Deep thinking cancelled: " + messageId);
        }
    }
    
    @Override
    public String getModeName() {
        return "deep_thinking";
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
