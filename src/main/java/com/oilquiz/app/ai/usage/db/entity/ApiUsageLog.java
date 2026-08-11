package com.oilquiz.app.ai.usage.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 用量日志表
 */
@Entity(
    tableName = "api_usage_log",
    indices = {
        @Index(value = {"userId", "callTime"}),
        @Index(value = {"traceId"}),
        @Index(value = {"modelId"})
    }
)
public class ApiUsageLog {
    
    @PrimaryKey(autoGenerate = true)
    private int id;
    
    private String traceId;
    private String sessionId;
    private String userId;
    private String modelId;
    private String providerId;
    
    private int inputTokens;
    private int outputTokens;
    private int cacheHitTokens;
    private int cacheCreateTokens;
    private int totalTokens;
    
    private double inputCost;
    private double outputCost;
    private double cacheCost;
    private double totalCost;
    private double cacheSavedCost;
    
    private String status;
    private String errorMessage;
    private long duration;
    private long callTime;
    
    public ApiUsageLog() {
        this.status = "success";
        this.callTime = System.currentTimeMillis();
    }
    
    // Getters and Setters
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public String getTraceId() {
        return traceId;
    }
    
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getModelId() {
        return modelId;
    }
    
    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
    
    public String getProviderId() {
        return providerId;
    }
    
    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }
    
    public int getInputTokens() {
        return inputTokens;
    }
    
    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }
    
    public int getOutputTokens() {
        return outputTokens;
    }
    
    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }
    
    public int getCacheHitTokens() {
        return cacheHitTokens;
    }
    
    public void setCacheHitTokens(int cacheHitTokens) {
        this.cacheHitTokens = cacheHitTokens;
    }
    
    public int getCacheCreateTokens() {
        return cacheCreateTokens;
    }
    
    public void setCacheCreateTokens(int cacheCreateTokens) {
        this.cacheCreateTokens = cacheCreateTokens;
    }
    
    public int getTotalTokens() {
        return totalTokens;
    }
    
    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
    
    public double getInputCost() {
        return inputCost;
    }
    
    public void setInputCost(double inputCost) {
        this.inputCost = inputCost;
    }
    
    public double getOutputCost() {
        return outputCost;
    }
    
    public void setOutputCost(double outputCost) {
        this.outputCost = outputCost;
    }
    
    public double getCacheCost() {
        return cacheCost;
    }
    
    public void setCacheCost(double cacheCost) {
        this.cacheCost = cacheCost;
    }
    
    public double getTotalCost() {
        return totalCost;
    }
    
    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }
    
    public double getCacheSavedCost() {
        return cacheSavedCost;
    }
    
    public void setCacheSavedCost(double cacheSavedCost) {
        this.cacheSavedCost = cacheSavedCost;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public long getDuration() {
        return duration;
    }
    
    public void setDuration(long duration) {
        this.duration = duration;
    }
    
    public long getCallTime() {
        return callTime;
    }
    
    public void setCallTime(long callTime) {
        this.callTime = callTime;
    }
}
