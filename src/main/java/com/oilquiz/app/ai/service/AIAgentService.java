package com.oilquiz.app.ai.service;

import android.content.Context;
import com.oilquiz.app.ai.agent.SmartIntentRecognizer;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class AIAgentService {
    private static final String TAG = "AIAgentService";
    private static final int MAX_HISTORY_ENTRIES = 30;
    private static final int MAX_HISTORY_CONTEXT_LENGTH = 2000;
    private static final long SESSION_TIMEOUT_MS = 1800000;
    private static final int DEFAULT_MAX_TOKENS = 512;

    private final AIService aiService;
    private final Context context;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, SmartIntentRecognizer.IntentResult> lastIntents = new ConcurrentHashMap<>();

    public AIAgentService(Context context, AIService aiService) {
        this.context = context;
        this.aiService = aiService;
    }

    private static class Session {
        final String sessionId;
        final List<SessionEntry> history = new ArrayList<>();
        long lastActivityTime = System.currentTimeMillis();

        Session(String sessionId) { this.sessionId = sessionId; }

        boolean isExpired() {
            return System.currentTimeMillis() - lastActivityTime > SESSION_TIMEOUT_MS;
        }
    }

    private static class SessionEntry {
        final String role;
        final String content;
        final long timestamp;

        SessionEntry(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public CompletableFuture<String> processRequest(String sessionId, String request) {
        cleanupExpiredSessions();

        return CompletableFuture.supplyAsync(() -> {
            try {
                Session session = sessions.computeIfAbsent(sessionId, Session::new);
                session.lastActivityTime = System.currentTimeMillis();

                session.history.add(new SessionEntry("user", request));
                trimHistory(session.history);

                SmartIntentRecognizer recognizer = SmartIntentRecognizer.getInstance(context);
                SmartIntentRecognizer.IntentResult intent = recognizer.recognize(request);
                lastIntents.put(sessionId, intent);

                String contextPrompt = buildHistoryContextPrompt(session);
                int contextTokens = estimateTokens(contextPrompt);
                int availableTokens = Math.max(DEFAULT_MAX_TOKENS / 2, DEFAULT_MAX_TOKENS - contextTokens);
                String fullPrompt = contextPrompt + "\n当前用户: " + request;

                AILogger.i(TAG, "Processing request: intent=" + intent.intent.id
                    + " conf=" + intent.confidence
                    + " history=" + session.history.size()
                    + " contextTokens=" + contextTokens
                    + " availableTokens=" + availableTokens);

                String response = aiService.generateSync(fullPrompt, availableTokens);

                session.history.add(new SessionEntry("assistant", response != null ? response : ""));
                trimHistory(session.history);

                return response != null ? response : "抱歉，我暂时无法回答。";
            } catch (Exception e) {
                AILogger.e(TAG, "Error processing request: " + e.getMessage());
                return "处理请求时出错: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> processAgentRequest(String sessionId, String request) {
        cleanupExpiredSessions();

        return CompletableFuture.supplyAsync(() -> {
            try {
                Session session = sessions.computeIfAbsent(sessionId, Session::new);
                session.lastActivityTime = System.currentTimeMillis();

                session.history.add(new SessionEntry("user", request));

                String contextPrompt = buildAgentContextPrompt(session, request);

                SmartIntentRecognizer recognizer = SmartIntentRecognizer.getInstance(context);
                SmartIntentRecognizer.IntentResult intent = recognizer.recognize(request);
                lastIntents.put(sessionId, intent);

                String intentHint = "";
                if (intent.needsTool() && intent.confidence >= 0.3) {
                    intentHint = "\n[系统提示] 意图识别：" + intent.intent.displayName
                        + " (置信度:" + String.format("%.0f%%", intent.confidence * 100) + ")";
                    if (intent.extractedEntity != null) {
                        intentHint += "\n提取关键实体：" + intent.extractedEntity;
                    }
                    intentHint += "\n如果需要，可以调用相关工具获取实时信息。\n";
                }

                String fullPrompt = contextPrompt + intentHint + "\n用户问题: " + request;
                String response = aiService.generateSync(fullPrompt, DEFAULT_MAX_TOKENS);

                if (response != null) {
                    session.history.add(new SessionEntry("assistant", response));
                    trimHistory(session.history);
                }

                return response != null ? response : "抱歉，我暂时无法回答。";
            } catch (Exception e) {
                AILogger.e(TAG, "Error processing agent request: " + e.getMessage());
                return "处理Agent请求时出错: " + e.getMessage();
            }
        });
    }

    private String buildHistoryContextPrompt(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("[对话历史 - 共").append(session.history.size()).append("条]\n");

        int totalChars = 0;
        List<SessionEntry> recentEntries = new ArrayList<>();
        for (int i = session.history.size() - 1; i >= 0 && totalChars < MAX_HISTORY_CONTEXT_LENGTH; i--) {
            SessionEntry entry = session.history.get(i);
            recentEntries.add(0, entry);
            totalChars += Math.min(entry.content.length(), 300);
        }

        for (SessionEntry entry : recentEntries) {
            String roleTag = entry.role.equals("user") ? "用户" : "助手";
            String content = entry.content.length() > 300
                ? entry.content.substring(0, 300) + "..." : entry.content;
            sb.append(roleTag).append(": ").append(content).append("\n");
        }

        if (recentEntries.size() < session.history.size()) {
            sb.append("[...省略了").append(session.history.size() - recentEntries.size()).append("条更早的对话...]\n");
        }

        return sb.toString();
    }

    private String buildAgentContextPrompt(Session session, String currentRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Agent对话上下文]\n");
        sb.append("当前对话轮次: ").append(session.history.size() / 2 + 1).append("\n");

        List<SessionEntry> recentEntries = new ArrayList<>();
        int totalChars = 0;
        for (int i = session.history.size() - 1; i >= 0 && totalChars < MAX_HISTORY_CONTEXT_LENGTH && recentEntries.size() < 20; i--) {
            SessionEntry entry = session.history.get(i);
            recentEntries.add(0, entry);
            totalChars += Math.min(entry.content.length(), 200);
        }

        sb.append("\n最近对话摘要:\n");
        for (SessionEntry entry : recentEntries) {
            String roleTag = entry.role.equals("user") ? "用户" : "AI";
            String content = entry.content.length() > 200
                ? entry.content.substring(0, 200) + "..." : entry.content;
            sb.append(roleTag).append(": ").append(content).append("\n");
        }

        sb.append("\n请在理解以上对话上下文的基础上回答用户问题。");
        if (session.history.size() >= 6) {
            sb.append("注意保持对话的连贯性，参考之前的讨论内容。");
        }

        return sb.toString();
    }

    private void trimHistory(List<SessionEntry> history) {
        while (history.size() > MAX_HISTORY_ENTRIES) {
            history.remove(0);
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return chineseChars + (otherChars / 4);
    }

    public String getHistorySummary(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null || session.history.isEmpty()) return "无对话记录";

        StringBuilder sb = new StringBuilder();
        sb.append("对话共").append(session.history.size()).append("条记录。");

        int userCount = 0;
        int assistantCount = 0;
        for (SessionEntry entry : session.history) {
            if (entry.role.equals("user")) userCount++;
            else assistantCount++;
        }
        sb.append("用户消息").append(userCount).append("条，助手回复").append(assistantCount).append("条。");
        return sb.toString();
    }

    public String getLastIntentType(String sessionId) {
        SmartIntentRecognizer.IntentResult intent = lastIntents.get(sessionId);
        return intent != null ? intent.intent.id : "unknown";
    }

    public List<Map<String, String>> getSessionHistory(String sessionId) {
        List<Map<String, String>> result = new ArrayList<>();
        Session session = sessions.get(sessionId);
        if (session == null) return result;
        for (SessionEntry entry : session.history) {
            Map<String, String> map = new HashMap<>();
            map.put("role", entry.role);
            map.put("content", entry.content);
            result.add(map);
        }
        return result;
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        lastIntents.remove(sessionId);
        AILogger.i(TAG, "Session cleared: " + sessionId);
    }

    private void cleanupExpiredSessions() {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getValue().isExpired()) {
                expired.add(entry.getKey());
            }
        }
        for (String id : expired) {
            sessions.remove(id);
            lastIntents.remove(id);
        }
        if (!expired.isEmpty()) {
            AILogger.i(TAG, "Cleaned up " + expired.size() + " expired sessions");
        }
    }

    public List<String> getActiveSessions() {
        cleanupExpiredSessions();
        return new ArrayList<>(sessions.keySet());
    }
}