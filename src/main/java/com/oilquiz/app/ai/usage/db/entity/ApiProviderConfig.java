package com.oilquiz.app.ai.usage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 服务商配置表
 */
@Entity(tableName = "api_provider_config")
public class ApiProviderConfig {
    
    @NonNull
    @PrimaryKey
    private String providerId;
    
    private String displayName;
    private String apiBase;
    private String authType;
    private String authHeaderName;
    private boolean isActive;
    private int configVersion;
    private long createdAt;
    private long updatedAt;
    
    public ApiProviderConfig() {
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.isActive = true;
        this.configVersion = 1;
    }
    
    // Getters and Setters
    
    public String getProviderId() {
        return providerId;
    }
    
    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public String getApiBase() {
        return apiBase;
    }
    
    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }
    
    public String getAuthType() {
        return authType;
    }
    
    public void setAuthType(String authType) {
        this.authType = authType;
    }
    
    public String getAuthHeaderName() {
        return authHeaderName;
    }
    
    public void setAuthHeaderName(String authHeaderName) {
        this.authHeaderName = authHeaderName;
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public void setActive(boolean active) {
        isActive = active;
    }
    
    public int getConfigVersion() {
        return configVersion;
    }
    
    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
    
    public long getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
