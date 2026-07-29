package com.oilquiz.app.ai.agent.software.model;

/**
 * Thought - 思考内容
 */
public class Thought {
    private final String content;
    private final boolean isComplete;
    
    public Thought(String content, boolean isComplete) {
        this.content = content;
        this.isComplete = isComplete;
    }
    
    public String getContent() { return content; }
    public boolean isComplete() { return isComplete; }
}
