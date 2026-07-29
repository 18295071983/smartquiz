package com.oilquiz.app.ai.agent.software.model;

import java.util.ArrayList;
import java.util.List;

/**
 * ThinkingChain - 思考链
 */
public class ThinkingChain {
    private final List<ThinkingStep> steps;
    
    public ThinkingChain() {
        this.steps = new ArrayList<>();
    }
    
    public void addStep(ThinkingStep step) {
        steps.add(step);
    }
    
    public List<ThinkingStep> getSteps() {
        return steps;
    }
    
    public int getStepCount() {
        return steps.size();
    }
    
    public ThinkingStep getLastStep() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }
}
