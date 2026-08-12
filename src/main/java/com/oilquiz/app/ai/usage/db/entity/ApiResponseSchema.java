package com.oilquiz.app.ai.usage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 响应格式配置表
 */
@Entity(
    tableName = "api_response_schema"
)
public class ApiResponseSchema {
    
    @NonNull
    @PrimaryKey
    private String schemaId;
    
    private String providerId;
    private String modelId;
    private String rootPath;
    private String inputTokenPath;
    private String outputTokenPath;
    private String cacheHitTokenPath;
    private String cacheCreateTokenPath;
    private long createdAt;
    
    public ApiResponseSchema() {
        this.createdAt = System.currentTimeMillis();
        this.modelId = "";  // 空字符串表示通用配置
    }
    
    // Getters and Setters
    
    public String getSchemaId() {
        return schemaId;
    }
    
    public void setSchemaId(String schemaId) {
        this.schemaId = schemaId;
    }
    
    public String getProviderId() {
        return providerId;
    }
    
    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }
    
    public String getModelId() {
        return modelId;
    }
    
    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
    
    public String getRootPath() {
        return rootPath;
    }
    
    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }
    
    public String getInputTokenPath() {
        return inputTokenPath;
    }
    
    public void setInputTokenPath(String inputTokenPath) {
        this.inputTokenPath = inputTokenPath;
    }
    
    public String getOutputTokenPath() {
        return outputTokenPath;
    }
    
    public void setOutputTokenPath(String outputTokenPath) {
        this.outputTokenPath = outputTokenPath;
    }
    
    public String getCacheHitTokenPath() {
        return cacheHitTokenPath;
    }
    
    public void setCacheHitTokenPath(String cacheHitTokenPath) {
        this.cacheHitTokenPath = cacheHitTokenPath;
    }
    
    public String getCacheCreateTokenPath() {
        return cacheCreateTokenPath;
    }
    
    public void setCacheCreateTokenPath(String cacheCreateTokenPath) {
        this.cacheCreateTokenPath = cacheCreateTokenPath;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
