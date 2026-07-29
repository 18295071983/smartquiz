package com.oilquiz.app.ai.agent.software.thinking.model;

public class ThinkingMessage {
    public enum Role {
        USER, AI, SYSTEM
    }

    private int step;
    private Role role;
    private String content;
    private ThinkingType type;
    private String aspect;
    private String questionType;
    private String reason;
    private long timestamp;

    public ThinkingMessage(Role role, String content, ThinkingType type) {
        this.role = role;
        this.content = content;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public int getStep() { return step; }
    public void setStep(int step) { this.step = step; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public ThinkingType getType() { return type; }
    public void setType(ThinkingType type) { this.type = type; }

    public String getAspect() { return aspect; }
    public void setAspect(String aspect) { this.aspect = aspect; }

    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public long getTimestamp() { return timestamp; }

    public static ThinkingMessage userMessage(String content) {
        return new ThinkingMessage(Role.USER, content, ThinkingType.ANSWER);
    }

    public static ThinkingMessage aiQuestion(String question, String questionType, String reason) {
        ThinkingMessage msg = new ThinkingMessage(Role.AI, question, ThinkingType.QUESTION);
        msg.setQuestionType(questionType);
        msg.setReason(reason);
        return msg;
    }

    public static ThinkingMessage aiSuggestion(String suggestion) {
        return new ThinkingMessage(Role.AI, suggestion, ThinkingType.SUGGESTION);
    }

    public static ThinkingMessage aiPerspective(String perspective) {
        return new ThinkingMessage(Role.AI, perspective, ThinkingType.PERSPECTIVE);
    }

    public static ThinkingMessage aiReflection(String reflection) {
        return new ThinkingMessage(Role.AI, reflection, ThinkingType.REFLECTION);
    }

    public static ThinkingMessage aiSummary(String summary) {
        return new ThinkingMessage(Role.AI, summary, ThinkingType.SUMMARY);
    }

    public String getDisplayText() {
        StringBuilder sb = new StringBuilder();
        if (type != null && type != ThinkingType.ANSWER) {
            sb.append("[").append(type.getDisplayName()).append("] ");
        }
        sb.append(content);
        return sb.toString();
    }
}
