package com.oilquiz.app.ai.usage.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 模型价格配置表
 */
@Entity(
    tableName = "api_price_config",
    indices = {
        @Index(value = {"modelId", "priceType"})
    }
)
public class ApiPriceConfig {
    
    @PrimaryKey(autoGenerate = true)
    private int id;
    
    private String modelId;
    private String providerId;
    private String priceType;
    private double pricePer1M;
    private String currency;
    private int minContext;
    private int maxContext;
    private String displayName;
    private int contextWindow;
    private boolean isActive;
    private long syncTime;
    
    public ApiPriceConfig() {
        this.isActive = true;
        this.contextWindow = 128000;
        this.currency = "CNY";
        this.syncTime = System.currentTimeMillis();
    }
    
    // Getters and Setters
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
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
    
    public String getPriceType() {
        return priceType;
    }
    
    public void setPriceType(String priceType) {
        this.priceType = priceType;
    }
    
    public double getPricePer1M() {
        return pricePer1M;
    }
    
    public void setPricePer1M(double pricePer1M) {
        this.pricePer1M = pricePer1M;
    }
    
    public String getCurrency() {
        return currency;
    }
    
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    
    public int getMinContext() {
        return minContext;
    }
    
    public void setMinContext(int minContext) {
        this.minContext = minContext;
    }
    
    public int getMaxContext() {
        return maxContext;
    }
    
    public void setMaxContext(int maxContext) {
        this.maxContext = maxContext;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public int getContextWindow() {
        return contextWindow;
    }
    
    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public void setActive(boolean active) {
        isActive = active;
    }
    
    public long getSyncTime() {
        return syncTime;
    }
    
    public void setSyncTime(long syncTime) {
        this.syncTime = syncTime;
    }
}
