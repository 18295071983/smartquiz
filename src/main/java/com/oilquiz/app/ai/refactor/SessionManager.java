package com.oilquiz.app.ai.refactor;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理器 - 管理对话会话和历史
 * 
 * 职责：
 * 1. 创建和管理会话生命周期
 * 2. 存储和检索对话历史
 * 3. 管理会话状态
 */
public class SessionManager {
    private static final int MAX_HISTORY_SIZE = 50;
    private static final int SHORT_TERM_MEMORY_SIZE = 20;
    
    private final Map<String, Session> sessions = new HashMap<>();
    private String currentSessionId;
    
    /**
     * 创建新会话
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId);
        sessions.put(sessionId, session);
        currentSessionId = sessionId;
        return sessionId;
    }
    
    /**
     * 获取当前会话
     */
    public Session getCurrentSession() {
        if (currentSessionId == null) {
            createSession();
        }
        return sessions.get(currentSessionId);
    }
    
    /**
     * 获取指定会话
     */
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    /**
     * 设置当前会话
     */
    public void setCurrentSession(String sessionId) {
        if (sessions.containsKey(sessionId)) {
            currentSessionId = sessionId;
        }
    }
    
    /**
     * 删除会话
     */
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
        if (currentSessionId.equals(sessionId)) {
            currentSessionId = null;
        }
    }
    
    /**
     * 获取所有会话列表
     */
    public List<String> getAllSessionIds() {
        return new ArrayList<>(sessions.keySet());
    }
    
    /**
     * 会话类
     */
    public static class Session {
        private final String sessionId;
        private final List<ChatMessage> history = new ArrayList<>();
        private final List<String> shortTermMemory = new ArrayList<>();
        private SessionState state = SessionState.IDLE;
        private long createdAt;
        private long lastUpdated;
        
        public Session(String sessionId) {
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
            this.lastUpdated = createdAt;
        }
        
        public String getId() {
            return sessionId;
        }
        
        public void addMessage(ChatMessage message) {
            history.add(message);
            lastUpdated = System.currentTimeMillis();
            
            while (history.size() > MAX_HISTORY_SIZE) {
                history.remove(0);
            }
        }
        
        public void addToShortTermMemory(String content) {
            shortTermMemory.add(content);
            while (shortTermMemory.size() > SHORT_TERM_MEMORY_SIZE) {
                shortTermMemory.remove(0);
            }
        }
        
        public List<ChatMessage> getHistory() {
            return new ArrayList<>(history);
        }
        
        public List<ChatMessage> getRecentHistory(int count) {
            int start = Math.max(0, history.size() - count);
            return history.subList(start, history.size());
        }
        
        public String getShortTermMemorySummary() {
            if (shortTermMemory.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, shortTermMemory.size() - 5); i < shortTermMemory.size(); i++) {
                sb.append(shortTermMemory.get(i)).append("\n");
            }
            return sb.toString().trim();
        }
        
        public SessionState getState() {
            return state;
        }
        
        public void setState(SessionState state) {
            this.state = state;
        }
        
        public long getCreatedAt() {
            return createdAt;
        }
        
        public long getLastUpdated() {
            return lastUpdated;
        }
        
        public int getMessageCount() {
            return history.size();
        }
        
        public void clearHistory() {
            history.clear();
        }
    }
    
    /**
     * 会话状态枚举
     */
    public enum SessionState {
        IDLE, THINKING, PLANNING, EXECUTING, COMPLETED, ERROR
    }
}