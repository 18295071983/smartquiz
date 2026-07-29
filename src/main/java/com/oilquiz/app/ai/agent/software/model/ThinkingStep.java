package com.oilquiz.app.ai.agent.software.model;

/**
 * ThinkingStep - 思考步骤
 */
public class ThinkingStep {
    private final Thought thought;
    private Action action;
    private Observation observation;
    
    public ThinkingStep(Thought thought, Action action, Observation observation) {
        this.thought = thought;
        this.action = action;
        this.observation = observation;
    }
    
    public Thought getThought() { return thought; }
    
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    
    public Observation getObservation() { return observation; }
    public void setObservation(Observation observation) { this.observation = observation; }
}
