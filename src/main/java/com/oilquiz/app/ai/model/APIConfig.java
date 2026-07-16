package com.oilquiz.app.ai.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class APIConfig implements Serializable {
    
    public static final String STATUS_VALID = Status.VALID;
    
    private String id;
    private String name;
    private String serviceType;
    private String apiKey;
    private String apiHost;
    private String modelName;
    private String description;
    private String category;
    private boolean isActive;
    private long createdAt;
    private long lastUsedAt;
    private int useCount;
    private String status;
    private int timeout;
    private Map<String, String> customHeaders;
    private Map<String, String> additionalParams;

    public APIConfig() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.isActive = true;
        this.useCount = 0;
        this.status = "unknown";
        this.timeout = 30;
        this.customHeaders = new HashMap<>();
        this.additionalParams = new HashMap<>();
    }

    public APIConfig(String name, String serviceType, String apiKey) {
        this();
        this.name = name;
        this.serviceType = serviceType;
        this.apiKey = apiKey;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiHost() { return apiHost; }
    public void setApiHost(String apiHost) { this.apiHost = apiHost; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(long lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public int getUseCount() { return useCount; }
    public void setUseCount(int useCount) { this.useCount = useCount; }
    public void incrementUseCount() { this.useCount++; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    public Map<String, String> getCustomHeaders() {
        if (customHeaders == null) {
            customHeaders = new HashMap<>();
        }
        return customHeaders;
    }
    public void setCustomHeaders(Map<String, String> customHeaders) { this.customHeaders = customHeaders; }
    public void addCustomHeader(String key, String value) {
        if (customHeaders == null) {
            customHeaders = new HashMap<>();
        }
        this.customHeaders.put(key, value);
    }

    public Map<String, String> getAdditionalParams() {
        if (additionalParams == null) {
            additionalParams = new HashMap<>();
        }
        return additionalParams;
    }
    public void setAdditionalParams(Map<String, String> additionalParams) { this.additionalParams = additionalParams; }
    public void addAdditionalParam(String key, String value) {
        if (additionalParams == null) {
            additionalParams = new HashMap<>();
        }
        this.additionalParams.put(key, value);
    }

    public String getMaskedApiKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    public static class ServiceType {
        public static final String OPENAI = "openai";
        public static final String ANTHROPIC = "anthropic";
        public static final String GOOGLE = "google";
        public static final String OPENWEATHERMAP = "openweathermap";
        public static final String HEFENG_WEATHER = "hefeng_weather";
        public static final String BING_SEARCH = "bing_search";
        public static final String GOOGLE_MAPS = "google_maps";
        public static final String CUSTOM = "custom";
    }

    public static class Status {
        public static final String UNKNOWN = "unknown";
        public static final String VALID = "valid";
        public static final String INVALID = "invalid";
        public static final String EXPIRED = "expired";
        public static final String RATE_LIMITED = "rate_limited";
    }

    public static class Category {
        public static final String AI = "ai";
        public static final String WEATHER = "weather";
        public static final String SEARCH = "search";
        public static final String MAPS = "maps";
        public static final String OTHER = "other";
    }
}
