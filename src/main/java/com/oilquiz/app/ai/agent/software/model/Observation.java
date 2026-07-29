package com.oilquiz.app.ai.agent.software.model;

/**
 * Observation - 观察结果
 */
public class Observation {
    private final String content;
    private final boolean isFinal;
    
    private Observation(String content, boolean isFinal) {
        this.content = content;
        this.isFinal = isFinal;
    }
    
    public static Observation complete(String content) {
        return new Observation(content, true);
    }
    
    public static Observation continue_observation(String content) {
        return new Observation(content, false);
    }
    
    public String getContent() { return content; }
    public boolean isFinal() { return isFinal; }
}
