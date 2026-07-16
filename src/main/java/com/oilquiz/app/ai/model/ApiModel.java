package com.oilquiz.app.ai.model;

import java.io.Serializable;

/**
 * API 模型信息
 */
public class ApiModel implements Serializable {
    
    /**
     * 模型标识
     */
    public String id;
    
    /**
     * 模型显示名称
     */
    public String displayName;
    
    /**
     * 所属组织
     */
    public String ownedBy;
    
    /**
     * 上下文长度
     */
    public int contextLength;
    
    /**
     * 是否已弃用
     */
    public boolean deprecated;
    
    /**
     * 创建时间
     */
    public long createdAt;
    
    /**
     * 模型来源：openai, anthropic, custom
     */
    public String source;
    
    public ApiModel() {}
    
    public ApiModel(String id) {
        this.id = id;
        this.displayName = id;
        this.contextLength = 0;
        this.deprecated = false;
    }
    
    public ApiModel(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.contextLength = 0;
        this.deprecated = false;
    }
    
    /**
     * 创建 OpenAI 格式的模型
     */
    public static ApiModel fromOpenAI(String id, String ownedBy, long createdAt) {
        ApiModel model = new ApiModel();
        model.id = id;
        model.displayName = id;
        model.ownedBy = ownedBy;
        model.createdAt = createdAt;
        model.source = "openai";
        model.deprecated = false;
        model.contextLength = detectContextLength(id);
        return model;
    }
    
    /**
     * 创建 Anthropic 格式的模型
     */
    public static ApiModel fromAnthropic(String name) {
        ApiModel model = new ApiModel();
        model.id = name;
        model.displayName = name;
        model.source = "anthropic";
        model.deprecated = false;
        model.contextLength = detectAnthropicContextLength(name);
        return model;
    }
    
    /**
     * 从模型 ID 检测上下文长度（基于名称规则）
     */
    private static int detectContextLength(String modelId) {
        String lower = modelId.toLowerCase();
        if (lower.contains("32k") || lower.contains("32k")) {
            return 32000;
        }
        if (lower.contains("16k")) {
            return 16000;
        }
        if (lower.contains("gpt-4")) {
            return 8192;
        }
        if (lower.contains("gpt-3.5-turbo-16k")) {
            return 16385;
        }
        return 4096; // 默认 4K
    }
    
    /**
     * 从模型名称检测 Anthropic 模型的上下文长度
     */
    private static int detectAnthropicContextLength(String modelName) {
        String lower = modelName.toLowerCase();
        if (lower.contains("200k")) {
            return 200000;
        }
        if (lower.contains("100k")) {
            return 100000;
        }
        if (lower.contains("opus")) {
            return 200000; // Claude 3 Opus
        }
        if (lower.contains("sonnet")) {
            return 200000; // Claude 3 Sonnet
        }
        if (lower.contains("haiku")) {
            return 200000; // Claude 3 Haiku
        }
        return 4096; // 默认
    }
    
    /**
     * 获取格式化的上下文长度
     */
    public String getFormattedContextLength() {
        if (contextLength >= 1000) {
            return (contextLength / 1000) + "K";
        }
        return String.valueOf(contextLength);
    }
    
    /**
     * 获取模型名称（用于兼容性）
     */
    public String getName() {
        return displayName != null ? displayName : id;
    }
    
    /**
     * 是否是可用的模型（非弃用）
     */
    public boolean isAvailable() {
        return !deprecated;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ApiModel apiModel = (ApiModel) obj;
        return id != null && id.equals(apiModel.id);
    }
    
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
    
    @Override
    public String toString() {
        return displayName + " (" + getFormattedContextLength() + " context)";
    }
}