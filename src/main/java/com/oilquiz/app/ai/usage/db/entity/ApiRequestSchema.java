package com.oilquiz.app.ai.usage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 请求格式配置表
 * 配置每个服务商的请求体结构（如消息格式、参数等）
 */
@Entity(
    tableName = "api_request_schema"
)
public class ApiRequestSchema {
    
    @NonNull
    @PrimaryKey
    private String schemaId;
    
    private String providerId;
    private String modelId;
    
    // 请求体配置
    private String requestBodyPath;           // 请求体根路径: "messages"
    private String messagesPath;              // 消息数组路径: "messages"
    private String messageRolePath;           // 角色字段路径: "role"
    private String messageContentPath;        // 内容字段路径: "content"
    private String systemMessageRole;         // System 角色值: "system"
    private String userMessageRole;           // User 角色值: "user"
    private String assistantMessageRole;      // Assistant 角色值: "assistant"
    
    // 参数配置
    private String temperaturePath;           // temperature 字段路径: "temperature"
    private String topPPath;                  // top_p 字段路径: "top_p"
    private String maxTokensPath;             // max_tokens 字段路径: "max_tokens"
    private String streamPath;                // stream 字段路径: "stream"
    
    // 是否支持流式
    private boolean supportsStream;
    private long createdAt;
    
    public ApiRequestSchema() {
        this.createdAt = System.currentTimeMillis();
        this.modelId = "";  // 空字符串表示通用配置
        this.supportsStream = true;
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
    
    public String getRequestBodyPath() {
        return requestBodyPath;
    }
    
    public void setRequestBodyPath(String requestBodyPath) {
        this.requestBodyPath = requestBodyPath;
    }
    
    public String getMessagesPath() {
        return messagesPath;
    }
    
    public void setMessagesPath(String messagesPath) {
        this.messagesPath = messagesPath;
    }
    
    public String getMessageRolePath() {
        return messageRolePath;
    }
    
    public void setMessageRolePath(String messageRolePath) {
        this.messageRolePath = messageRolePath;
    }
    
    public String getMessageContentPath() {
        return messageContentPath;
    }
    
    public void setMessageContentPath(String messageContentPath) {
        this.messageContentPath = messageContentPath;
    }
    
    public String getSystemMessageRole() {
        return systemMessageRole;
    }
    
    public void setSystemMessageRole(String systemMessageRole) {
        this.systemMessageRole = systemMessageRole;
    }
    
    public String getUserMessageRole() {
        return userMessageRole;
    }
    
    public void setUserMessageRole(String userMessageRole) {
        this.userMessageRole = userMessageRole;
    }
    
    public String getAssistantMessageRole() {
        return assistantMessageRole;
    }
    
    public void setAssistantMessageRole(String assistantMessageRole) {
        this.assistantMessageRole = assistantMessageRole;
    }
    
    public String getTemperaturePath() {
        return temperaturePath;
    }
    
    public void setTemperaturePath(String temperaturePath) {
        this.temperaturePath = temperaturePath;
    }
    
    public String getTopPPath() {
        return topPPath;
    }
    
    public void setTopPPath(String topPPath) {
        this.topPPath = topPPath;
    }
    
    public String getMaxTokensPath() {
        return maxTokensPath;
    }
    
    public void setMaxTokensPath(String maxTokensPath) {
        this.maxTokensPath = maxTokensPath;
    }
    
    public String getStreamPath() {
        return streamPath;
    }
    
    public void setStreamPath(String streamPath) {
        this.streamPath = streamPath;
    }
    
    public boolean supportsStream() {
        return supportsStream;
    }
    
    public void setSupportsStream(boolean supportsStream) {
        this.supportsStream = supportsStream;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
