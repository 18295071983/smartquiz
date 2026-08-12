package com.oilquiz.app.ai.usage.parser;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 用量解析器
 * 根据配置动态解析 API 响应中的用量字段
 */
public class UsageParser {
    
    private static final String TAG = "UsageParser";
    
    private final ResponseParserConfig config;
    
    /**
     * 解析结果
     */
    public static class ParseResult {
        private final int inputTokens;
        private final int outputTokens;
        private final int cacheHitTokens;
        private final int cacheCreateTokens;
        
        public ParseResult(int inputTokens, int outputTokens, int cacheHitTokens, int cacheCreateTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheHitTokens = cacheHitTokens;
            this.cacheCreateTokens = cacheCreateTokens;
        }
        
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public int getCacheHitTokens() { return cacheHitTokens; }
        public int getCacheCreateTokens() { return cacheCreateTokens; }
        public int getTotalTokens() { return inputTokens + outputTokens + cacheHitTokens + cacheCreateTokens; }
    }
    
    public UsageParser(ResponseParserConfig config) {
        this.config = config;
    }
    
    /**
     * 从 API 响应 JSON 中解析用量
     */
    public ParseResult parse(String responseBody) throws JSONException {
        if (responseBody == null || responseBody.isEmpty()) {
            Log.w(TAG, "响应体为空");
            return new ParseResult(0, 0, 0, 0);
        }
        
        JSONObject json = new JSONObject(responseBody);
        
        // 从根路径提取 usage 节点
        JSONObject usage = extractUsageNode(json, config.rootPath);
        if (usage == null) {
            Log.w(TAG, "未找到 usage 节点");
            return new ParseResult(0, 0, 0, 0);
        }
        
        int inputTokens = extractToken(usage, config.inputTokenPath);
        int outputTokens = extractToken(usage, config.outputTokenPath);
        int cacheHitTokens = config.cacheHitTokenPath != null ? 
                extractToken(usage, config.cacheHitTokenPath) : 0;
        int cacheCreateTokens = config.cacheCreateTokenPath != null ? 
                extractToken(usage, config.cacheCreateTokenPath) : 0;
        
        Log.i(TAG, "解析用量 - 输入: " + inputTokens + ", 输出: " + outputTokens + 
                ", 缓存命中: " + cacheHitTokens + ", 缓存创建: " + cacheCreateTokens);
        
        return new ParseResult(inputTokens, outputTokens, cacheHitTokens, cacheCreateTokens);
    }
    
    /**
     * 从响应体中提取 JSON 根节点
     */
    private JSONObject extractUsageNode(JSONObject json, String rootPath) throws JSONException {
        if (rootPath == null || rootPath.isEmpty()) {
            return json;
        }
        
        // 支持嵌套路径, 如 "usage" 或 "data.usage"
        String[] paths = rootPath.split("\\.");
        JSONObject current = json;
        for (String path : paths) {
            if (current.has(path)) {
                Object obj = current.get(path);
                if (obj instanceof JSONObject) {
                    current = (JSONObject) obj;
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }
        
        return current;
    }
    
    /**
     * 从 usage 节点中提取 Token 数值
     */
    private int extractToken(JSONObject usage, String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        
        try {
            String[] paths = path.split("\\.");
            JSONObject current = usage;
            for (String p : paths) {
                if (current.has(p)) {
                    Object obj = current.get(p);
                    if (obj instanceof JSONObject) {
                        current = (JSONObject) obj;
                    } else {
                        return obj instanceof Number ? ((Number) obj).intValue() : 0;
                    }
                } else {
                    return 0;
                }
            }
            return 0;  // 最终节点是对象而非数值
        } catch (Exception e) {
            Log.w(TAG, "提取 Token 字段失败: " + path);
            return 0;
        }
    }
}
