package com.oilquiz.app.ai.usage.parser;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.entity.ApiResponseSchema;

/**
 * 动态解析器路由器
 * 根据 modelId + providerId 自动选择对应的解析器
 */
public class ParserRouter {
    
    private static final String TAG = "ParserRouter";
    
    private final Context context;
    
    public ParserRouter(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * 获取指定模型的解析器
     */
    public UsageParser getParser(String modelId, String providerId) {
        try {
            ApiResponseSchema schema = DatabaseManager.getInstance(context)
                    .schemaDao()
                    .findBy(modelId, providerId);
            
            if (schema != null) {
                Log.i(TAG, "找到精确匹配 Schema - modelId: " + modelId);
                return new UsageParser(ResponseParserConfig.fromSchema(schema));
            }
            
            // 找不到精确匹配, 尝试通用配置 (modelId = "")
            schema = DatabaseManager.getInstance(context)
                    .schemaDao()
                    .findByGeneric(providerId);
            
            if (schema != null) {
                Log.i(TAG, "使用通用 Schema - providerId: " + providerId);
                return new UsageParser(ResponseParserConfig.fromSchema(schema));
            }
            
            Log.w(TAG, "未找到解析器配置, 使用默认 OpenAI 格式");
            // 默认 OpenAI 格式
            return new UsageParser(new ResponseParserConfig(
                "usage",           // rootPath
                "prompt_tokens",   // inputTokenPath
                "completion_tokens", // outputTokenPath
                null,              // cacheHitTokenPath
                null               // cacheCreateTokenPath
            ));
            
        } catch (Exception e) {
            Log.e(TAG, "获取解析器失败: " + e.getMessage());
            // 兜底默认格式
            return new UsageParser(new ResponseParserConfig(
                "usage", "prompt_tokens", "completion_tokens", null, null
            ));
        }
    }
}
