package com.oilquiz.app.ai.usage;

/**
 * 配置常量 (仅保留硬编码的默认值, 用于降级场景)
 */
public final class ConfigConstants {
    
    private ConfigConstants() {}
    
    // 超时配置 (毫秒)
    public static final int DEFAULT_CONNECT_TIMEOUT = 5000;
    public static final int DEFAULT_READ_TIMEOUT = 10000;
    
    // 缓存配置
    public static final long CACHE_EXPIRY_MS = 30 * 60 * 1000; // 30 分钟
    public static final String CONFIG_CACHE_FILE = "ai_usage_config_cache.json";
    
    // 配置大小限制
    public static final int MAX_CONFIG_SIZE = 50 * 1024; // 50 KB
    
    // 默认值
    public static final int DEFAULT_CONFIG_VERSION = 0;
    public static final String DEFAULT_CURRENCY = "CNY";
}

