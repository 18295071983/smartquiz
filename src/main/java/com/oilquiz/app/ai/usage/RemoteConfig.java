package com.oilquiz.app.ai.usage;

import java.util.List;

/**
 * 远程 AI 用量配置 (从 Gitee 拉取)
 */
public class RemoteConfig {
    
    private int configVersion;
    private String lastUpdated;
    private List<ProviderConfig> providers;
    private List<ResponseSchema> responseSchemas;
    private List<ModelConfig> models;
    private List<PriceConfig> prices;
    private List<RequestSchema> requestSchemas;
    private RemoteConnection remoteConfig;
    
    // Getters and Setters
    
    public int getConfigVersion() {
        return configVersion;
    }
    
    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }
    
    public String getLastUpdated() {
        return lastUpdated;
    }
    
    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    
    public List<ProviderConfig> getProviders() {
        return providers;
    }
    
    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers;
    }
    
    public List<ResponseSchema> getResponseSchemas() {
        return responseSchemas;
    }
    
    public void setResponseSchemas(List<ResponseSchema> responseSchemas) {
        this.responseSchemas = responseSchemas;
    }
    
    public List<ModelConfig> getModels() {
        return models;
    }
    
    public void setModels(List<ModelConfig> models) {
        this.models = models;
    }
    
    public List<PriceConfig> getPrices() {
        return prices;
    }
    
    public void setPrices(List<PriceConfig> prices) {
        this.prices = prices;
    }
    
    public List<RequestSchema> getRequestSchemas() {
        return requestSchemas;
    }
    
    public void setRequestSchemas(List<RequestSchema> requestSchemas) {
        this.requestSchemas = requestSchemas;
    }
    
    public RemoteConnection getRemoteConfig() {
        return remoteConfig;
    }
    
    public void setRemoteConfig(RemoteConnection remoteConfig) {
        this.remoteConfig = remoteConfig;
    }
    
    /**
     * 服务商配置
     */
    public static class ProviderConfig {
        private String providerId;
        private String displayName;
        private String apiBase;
        private String authType;
        private String authHeaderName;
        
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }
        
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        
        public String getAuthType() { return authType; }
        public void setAuthType(String authType) { this.authType = authType; }
        
        public String getAuthHeaderName() { return authHeaderName; }
        public void setAuthHeaderName(String authHeaderName) { this.authHeaderName = authHeaderName; }
    }
    
    /**
     * 响应格式配置
     */
    public static class ResponseSchema {
        private String schemaId;
        private String providerId;
        private String modelId;
        private String rootPath;
        private String inputTokenPath;
        private String outputTokenPath;
        private String cacheHitTokenPath;
        private String cacheCreateTokenPath;
        
        public String getSchemaId() { return schemaId; }
        public void setSchemaId(String schemaId) { this.schemaId = schemaId; }
        
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }
        
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        
        public String getRootPath() { return rootPath; }
        public void setRootPath(String rootPath) { this.rootPath = rootPath; }
        
        public String getInputTokenPath() { return inputTokenPath; }
        public void setInputTokenPath(String inputTokenPath) { this.inputTokenPath = inputTokenPath; }
        
        public String getOutputTokenPath() { return outputTokenPath; }
        public void setOutputTokenPath(String outputTokenPath) { this.outputTokenPath = outputTokenPath; }
        
        public String getCacheHitTokenPath() { return cacheHitTokenPath; }
        public void setCacheHitTokenPath(String cacheHitTokenPath) { this.cacheHitTokenPath = cacheHitTokenPath; }
        
        public String getCacheCreateTokenPath() { return cacheCreateTokenPath; }
        public void setCacheCreateTokenPath(String cacheCreateTokenPath) { this.cacheCreateTokenPath = cacheCreateTokenPath; }
    }
    
    /**
     * 模型配置
     */
    public static class ModelConfig {
        private String modelId;
        private String providerId;
        private String displayName;
        private int contextWindow;
        
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }
        
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        
        public int getContextWindow() { return contextWindow; }
        public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    }
    
    /**
     * 价格配置
     */
    public static class PriceConfig {
        private String modelId;
        private String providerId;
        private String priceType;
        private double pricePer1M;
        private String currency;
        private int minContext;
        private int maxContext;
        
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }
        
        public String getPriceType() { return priceType; }
        public void setPriceType(String priceType) { this.priceType = priceType; }
        
        public double getPricePer1M() { return pricePer1M; }
        public void setPricePer1M(double pricePer1M) { this.pricePer1M = pricePer1M; }
        
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        
        public int getMinContext() { return minContext; }
        public void setMinContext(int minContext) { this.minContext = minContext; }
        
        public int getMaxContext() { return maxContext; }
        public void setMaxContext(int maxContext) { this.maxContext = maxContext; }
    }
    
    /**
     * 请求格式配置
     */
    public static class RequestSchema {
        private String schemaId;
        private String providerId;
        private String modelId;
        private String requestBodyPath;
        private String messagesPath;
        private String messageRolePath;
        private String messageContentPath;
        private String systemMessageRole;
        private String userMessageRole;
        private String assistantMessageRole;
        private String temperaturePath;
        private String topPPath;
        private String maxTokensPath;
        private String streamPath;
        private boolean supportsStream;
        
        public String getSchemaId() { return schemaId; }
        public void setSchemaId(String schemaId) { this.schemaId = schemaId; }
        
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }
        
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        
        public String getRequestBodyPath() { return requestBodyPath; }
        public void setRequestBodyPath(String requestBodyPath) { this.requestBodyPath = requestBodyPath; }
        
        public String getMessagesPath() { return messagesPath; }
        public void setMessagesPath(String messagesPath) { this.messagesPath = messagesPath; }
        
        public String getMessageRolePath() { return messageRolePath; }
        public void setMessageRolePath(String messageRolePath) { this.messageRolePath = messageRolePath; }
        
        public String getMessageContentPath() { return messageContentPath; }
        public void setMessageContentPath(String messageContentPath) { this.messageContentPath = messageContentPath; }
        
        public String getSystemMessageRole() { return systemMessageRole; }
        public void setSystemMessageRole(String systemMessageRole) { this.systemMessageRole = systemMessageRole; }
        
        public String getUserMessageRole() { return userMessageRole; }
        public void setUserMessageRole(String userMessageRole) { this.userMessageRole = userMessageRole; }
        
        public String getAssistantMessageRole() { return assistantMessageRole; }
        public void setAssistantMessageRole(String assistantMessageRole) { this.assistantMessageRole = assistantMessageRole; }
        
        public String getTemperaturePath() { return temperaturePath; }
        public void setTemperaturePath(String temperaturePath) { this.temperaturePath = temperaturePath; }
        
        public String getTopPPath() { return topPPath; }
        public void setTopPPath(String topPPath) { this.topPPath = topPPath; }
        
        public String getMaxTokensPath() { return maxTokensPath; }
        public void setMaxTokensPath(String maxTokensPath) { this.maxTokensPath = maxTokensPath; }
        
        public String getStreamPath() { return streamPath; }
        public void setStreamPath(String streamPath) { this.streamPath = streamPath; }
        
        public boolean isSupportsStream() { return supportsStream; }
        public void setSupportsStream(boolean supportsStream) { this.supportsStream = supportsStream; }
    }
    
    /**
     * 远程连接配置
     */
    public static class RemoteConnection {
        private String giteeUrl;
        private int connectTimeoutMs;
        private int readTimeoutMs;
        private boolean autoSync;
        private int syncIntervalHours;
        
        public String getGiteeUrl() { return giteeUrl; }
        public void setGiteeUrl(String giteeUrl) { this.giteeUrl = giteeUrl; }
        
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        
        public boolean isAutoSync() { return autoSync; }
        public void setAutoSync(boolean autoSync) { this.autoSync = autoSync; }
        
        public int getSyncIntervalHours() { return syncIntervalHours; }
        public void setSyncIntervalHours(int syncIntervalHours) { this.syncIntervalHours = syncIntervalHours; }
    }
}
