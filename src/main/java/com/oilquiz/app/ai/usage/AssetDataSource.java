package com.oilquiz.app.ai.usage;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Assets 内置默认数据源
 * 当远程和本地都不可用时使用
 */
public class AssetDataSource {
    
    private static final String TAG = "AssetDataSource";
    private static final String CONFIG_FILE = "ai_usage_config_default.json";
    
    private final Context context;
    
    public AssetDataSource(Context context) {
        this.context = context;
    }
    
    /**
     * 加载内置默认配置
     * @return 配置对象, 加载失败返回 null
     */
    public RemoteConfig loadDefaultConfig() {
        try {
            String jsonStr = readAssetFile(CONFIG_FILE);
            
            if (jsonStr == null || jsonStr.isEmpty()) {
                Log.e(TAG, "Assets 文件为空");
                return null;
            }
            
            // 解析 JSON (复用 GiteeRemoteDataSource 的解析逻辑)
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            
            RemoteConfig config = new RemoteConfig();
            config.setConfigVersion(json.optInt("configVersion", ConfigConstants.DEFAULT_CONFIG_VERSION));
            config.setLastUpdated(json.optString("lastUpdated", "built-in"));
            
            RemoteConfig.ProviderConfig[] providers = parseProviders(json);
            config.setProviders(java.util.Arrays.asList(providers));
            
            RemoteConfig.ResponseSchema[] schemas = parseResponseSchemas(json);
            config.setResponseSchemas(java.util.Arrays.asList(schemas));
            
            RemoteConfig.ModelConfig[] models = parseModels(json);
            config.setModels(java.util.Arrays.asList(models));
            
            RemoteConfig.PriceConfig[] prices = parsePrices(json);
            config.setPrices(java.util.Arrays.asList(prices));
            
            Log.i(TAG, "Assets 默认配置加载成功, 版本: " + config.getConfigVersion());
            return config;
            
        } catch (Exception e) {
            Log.e(TAG, "Assets 默认配置加载失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 读取 Assets 文件
     */
    private String readAssetFile(String fileName) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(fileName)))) {
            
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
    
    /**
     * 解析 providers 数组
     */
    private RemoteConfig.ProviderConfig[] parseProviders(org.json.JSONObject json) throws org.json.JSONException {
        org.json.JSONArray arr = json.optJSONArray("providers");
        if (arr == null) {
            return new RemoteConfig.ProviderConfig[0];
        }
        
        RemoteConfig.ProviderConfig[] result = new RemoteConfig.ProviderConfig[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            RemoteConfig.ProviderConfig item = new RemoteConfig.ProviderConfig();
            item.setProviderId(obj.optString("providerId", ""));
            item.setDisplayName(obj.optString("displayName", ""));
            item.setApiBase(obj.optString("apiBase", ""));
            item.setAuthType(obj.optString("authType", ""));
            item.setAuthHeaderName(obj.optString("authHeaderName", ""));
            result[i] = item;
        }
        return result;
    }
    
    /**
     * 解析 responseSchemas 数组
     */
    private RemoteConfig.ResponseSchema[] parseResponseSchemas(org.json.JSONObject json) throws org.json.JSONException {
        org.json.JSONArray arr = json.optJSONArray("responseSchemas");
        if (arr == null) {
            return new RemoteConfig.ResponseSchema[0];
        }
        
        RemoteConfig.ResponseSchema[] result = new RemoteConfig.ResponseSchema[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            RemoteConfig.ResponseSchema item = new RemoteConfig.ResponseSchema();
            item.setSchemaId(obj.optString("schemaId", ""));
            item.setProviderId(obj.optString("providerId", ""));
            item.setModelId(obj.optString("modelId", ""));
            item.setRootPath(obj.optString("rootPath", ""));
            item.setInputTokenPath(obj.optString("inputTokenPath", ""));
            item.setOutputTokenPath(obj.optString("outputTokenPath", ""));
            item.setCacheHitTokenPath(obj.optString("cacheHitTokenPath", (String) null));
            item.setCacheCreateTokenPath(obj.optString("cacheCreateTokenPath", (String) null));
            result[i] = item;
        }
        return result;
    }
    
    /**
     * 解析 models 数组
     */
    private RemoteConfig.ModelConfig[] parseModels(org.json.JSONObject json) throws org.json.JSONException {
        org.json.JSONArray arr = json.optJSONArray("models");
        if (arr == null) {
            return new RemoteConfig.ModelConfig[0];
        }
        
        RemoteConfig.ModelConfig[] result = new RemoteConfig.ModelConfig[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            RemoteConfig.ModelConfig item = new RemoteConfig.ModelConfig();
            item.setModelId(obj.optString("modelId", ""));
            item.setProviderId(obj.optString("providerId", ""));
            item.setDisplayName(obj.optString("displayName", ""));
            item.setContextWindow(obj.optInt("contextWindow", 128000));
            result[i] = item;
        }
        return result;
    }
    
    /**
     * 解析 prices 数组
     */
    private RemoteConfig.PriceConfig[] parsePrices(org.json.JSONObject json) throws org.json.JSONException {
        org.json.JSONArray arr = json.optJSONArray("prices");
        if (arr == null) {
            return new RemoteConfig.PriceConfig[0];
        }
        
        RemoteConfig.PriceConfig[] result = new RemoteConfig.PriceConfig[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            RemoteConfig.PriceConfig item = new RemoteConfig.PriceConfig();
            item.setModelId(obj.optString("modelId", ""));
            item.setProviderId(obj.optString("providerId", ""));
            item.setPriceType(obj.optString("priceType", ""));
            item.setPricePer1M(obj.optDouble("pricePer1M", 0.0));
            item.setCurrency(obj.optString("currency", ConfigConstants.DEFAULT_CURRENCY));
            item.setMinContext(obj.optInt("minContext", 1));
            item.setMaxContext(obj.optInt("maxContext", 128000));
            result[i] = item;
        }
        return result;
    }
}
