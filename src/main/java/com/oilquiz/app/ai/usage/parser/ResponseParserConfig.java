package com.oilquiz.app.ai.usage.parser;

import com.oilquiz.app.ai.usage.db.entity.ApiResponseSchema;

/**
 * 响应解析器配置
 * 从数据库加载, 描述如何从 API 响应中提取 Token 字段
 */
public class ResponseParserConfig {
    
    public final String rootPath;
    public final String inputTokenPath;
    public final String outputTokenPath;
    public final String cacheHitTokenPath;
    public final String cacheCreateTokenPath;
    
    public ResponseParserConfig(String rootPath, String inputTokenPath, String outputTokenPath,
                                 String cacheHitTokenPath, String cacheCreateTokenPath) {
        this.rootPath = rootPath;
        this.inputTokenPath = inputTokenPath;
        this.outputTokenPath = outputTokenPath;
        this.cacheHitTokenPath = cacheHitTokenPath;
        this.cacheCreateTokenPath = cacheCreateTokenPath;
    }
    
    /**
     * 从 ApiResponseSchema 构建
     */
    public static ResponseParserConfig fromSchema(ApiResponseSchema schema) {
        return new ResponseParserConfig(
            schema.getRootPath(),
            schema.getInputTokenPath(),
            schema.getOutputTokenPath(),
            schema.getCacheHitTokenPath(),
            schema.getCacheCreateTokenPath()
        );
    }
}
