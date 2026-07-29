package com.oilquiz.app.ai.agent.software.thinking.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ThinkingSession {
    private String sessionId;
    private String userQuestion;
    private ThinkingState state;
    private List<ThinkingMessage> messages;
    private int depth;
    private List<String> exploredAspects;
    private List<String> pendingQuestions;
    private ThinkingContext context;
    private long startTime;
    private long lastUpdateTime;

    public ThinkingSession() {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.state = ThinkingState.EXPLORING;
        this.messages = new ArrayList<>();
        this.exploredAspects = new ArrayList<>();
        this.pendingQuestions = new ArrayList<>();
        this.context = new ThinkingContext();
        this.depth = 0;
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = startTime;
    }

    public String getSessionId() { return sessionId; }

    public String getUserQuestion() { return userQuestion; }
    public void setUserQuestion(String userQuestion) {
        this.userQuestion = userQuestion;
        this.context.setOriginalQuestion(userQuestion);
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public ThinkingState getState() { return state; }
    public void setState(ThinkingState state) { this.state = state; this.lastUpdateTime = System.currentTimeMillis(); }

    public List<ThinkingMessage> getMessages() { return messages; }
    public void addMessage(ThinkingMessage message) {
        message.setStep(messages.size() + 1);
        messages.add(message);
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public int getDepth() { return depth; }
    public void incrementDepth() { this.depth++; }

    public List<String> getExploredAspects() { return exploredAspects; }
    public void addExploredAspect(String aspect) {
        if (!exploredAspects.contains(aspect)) {
            exploredAspects.add(aspect);
        }
    }

    public List<String> getPendingQuestions() { return pendingQuestions; }
    public void addPendingQuestion(String question) {
        pendingQuestions.add(question);
    }

    public ThinkingContext getContext() { return context; }

    public long getStartTime() { return startTime; }
    public long getLastUpdateTime() { return lastUpdateTime; }

    public boolean isExpired(long timeoutMs) {
        return (System.currentTimeMillis() - lastUpdateTime) > timeoutMs;
    }

    public int getMessageCount() {
        return messages.size();
    }

    public String getLastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == ThinkingMessage.Role.USER) {
                return messages.get(i).getContent();
            }
        }
        return null;
    }

    public List<ThinkingMessage> getRecentMessages(int count) {
        int start = Math.max(0, messages.size() - count);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }
}
