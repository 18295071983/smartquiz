package com.oilquiz.app.ai.agent.software.thinking.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ThinkingContext {
    private String originalQuestion;
    private String clarifiedQuestion;
    private Map<String, Object> aspects;
    private List<String> keyPoints;
    private List<String> blindSpots;
    private List<String> nextSteps;
    private List<String> exploredAspects;  // 已探索过的视角（去重）
    private ThinkingStyle preferredStyle;
    private int clarity;
    private int depth;
    private int information;
    private String emotion;

    public ThinkingContext() {
        this.aspects = new HashMap<>();
        this.keyPoints = new ArrayList<>();
        this.blindSpots = new ArrayList<>();
        this.nextSteps = new ArrayList<>();
        this.exploredAspects = new ArrayList<>();
        this.preferredStyle = ThinkingStyle.SOCRATIC;
    }

    public String getOriginalQuestion() { return originalQuestion; }
    public void setOriginalQuestion(String originalQuestion) { this.originalQuestion = originalQuestion; }

    public String getClarifiedQuestion() { return clarifiedQuestion; }
    public void setClarifiedQuestion(String clarifiedQuestion) { this.clarifiedQuestion = clarifiedQuestion; }

    public Map<String, Object> getAspects() { return aspects; }
    public void setAspects(Map<String, Object> aspects) { this.aspects = aspects; }

    public List<String> getKeyPoints() { return keyPoints; }
    public void setKeyPoints(List<String> keyPoints) { this.keyPoints = keyPoints; }

    public List<String> getBlindSpots() { return blindSpots; }
    public void setBlindSpots(List<String> blindSpots) { this.blindSpots = blindSpots; }

    public List<String> getNextSteps() { return nextSteps; }
    public void setNextSteps(List<String> nextSteps) { this.nextSteps = nextSteps; }

    public ThinkingStyle getPreferredStyle() { return preferredStyle; }
    public void setPreferredStyle(ThinkingStyle preferredStyle) { this.preferredStyle = preferredStyle; }

    public int getClarity() { return clarity; }
    public void setClarity(int clarity) { this.clarity = clarity; }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }

    public int getInformation() { return information; }
    public void setInformation(int information) { this.information = information; }

    public String getEmotion() { return emotion; }
    public void setEmotion(String emotion) { this.emotion = emotion; }

    public void addAspect(String key, Object value) {
        aspects.put(key, value);
    }

    public void addKeyPoint(String point) {
        if (!keyPoints.contains(point)) {
            keyPoints.add(point);
        }
    }

    public void addBlindSpot(String spot) {
        if (!blindSpots.contains(spot)) {
            blindSpots.add(spot);
        }
    }

    public void addNextStep(String step) {
        if (!nextSteps.contains(step)) {
            nextSteps.add(step);
        }
    }

    /** 返回已探索过的视角列表（返回副本，保证外部不可直接修改内部） */
    public List<String> getExploredAspects() {
        return new ArrayList<>(exploredAspects);
    }

    /** 追加已探索的视角（自动去重） */
    public void addExploredAspect(String aspect) {
        if (aspect != null && !exploredAspects.contains(aspect)) {
            exploredAspects.add(aspect);
        }
    }

    public String toSummaryString() {
        StringBuilder sb = new StringBuilder();
        sb.append("问题: ").append(clarifiedQuestion != null ? clarifiedQuestion : originalQuestion).append("\n");
        sb.append("清晰度: ").append(clarity).append("/5, 深度: ").append(depth).append("/5\n");
        sb.append("关键点: ").append(String.join("、", keyPoints)).append("\n");
        if (!blindSpots.isEmpty()) {
            sb.append("待思考: ").append(String.join("、", blindSpots)).append("\n");
        }
        if (!nextSteps.isEmpty()) {
            sb.append("建议: ").append(String.join("、", nextSteps));
        }
        return sb.toString();
    }
}
