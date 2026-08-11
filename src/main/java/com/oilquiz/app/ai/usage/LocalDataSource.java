package com.oilquiz.app.ai.usage;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 本地数据源
 * 缓存远程配置到本地文件
 */
public class LocalDataSource {
    
    private static final String TAG = "LocalDataSource";
    
    private final Context context;
    
    public LocalDataSource(Context context) {
        this.context = context;
    }
    
    /**
     * 保存配置到本地缓存
     * @param config 配置对象
     * @param jsonStr JSON 字符串
     * @return 是否保存成功
     */
    public boolean saveConfig(RemoteConfig config, String jsonStr) {
        File cacheFile = getCacheFile();
        
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(jsonStr.getBytes("UTF-8"));
            
            // 保存版本号和缓存时间
            SharedPreferencesUtil.putInt(context, "config_version", config.getConfigVersion());
            SharedPreferencesUtil.putLong(context, "config_cache_time", System.currentTimeMillis());
            
            Log.i(TAG, "配置缓存成功: " + cacheFile.getAbsolutePath() + 
                  ", 版本: " + config.getConfigVersion());
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "配置缓存失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 从本地缓存加载配置
     * @return 缓存的配置, 无缓存返回 null
     */
    public RemoteConfig loadCachedConfig() {
        File cacheFile = getCacheFile();
        
        if (!cacheFile.exists()) {
            Log.i(TAG, "本地缓存不存在");
            return null;
        }
        
        // 检查缓存是否过期
        long cacheTime = SharedPreferencesUtil.getLong(context, "config_cache_time", 0);
        if (System.currentTimeMillis() - cacheTime > ConfigConstants.CACHE_EXPIRY_MS) {
            Log.i(TAG, "本地缓存已过期, 删除缓存文件");
            cacheFile.delete();
            return null;
        }
        
        try (FileInputStream fis = new FileInputStream(cacheFile)) {
            // 使用 ByteArrayOutputStream 确保完整读取所有字节
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            String jsonStr = baos.toString("UTF-8");
            
            // 解析 JSON
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            
            RemoteConfig config = new RemoteConfig();
            config.setConfigVersion(json.optInt("configVersion", 0));
            config.setLastUpdated(json.optString("lastUpdated", ""));
            
            RemoteConfig.ProviderConfig[] providers = parseProviders(json);
            config.setProviders(java.util.Arrays.asList(providers));
            
            RemoteConfig.ResponseSchema[] schemas = parseResponseSchemas(json);
            config.setResponseSchemas(java.util.Arrays.asList(schemas));
            
            RemoteConfig.ModelConfig[] models = parseModels(json);
            config.setModels(java.util.Arrays.asList(models));
            
            RemoteConfig.PriceConfig[] prices = parsePrices(json);
            config.setPrices(java.util.Arrays.asList(prices));
            
            Log.i(TAG, "本地缓存加载成功, 版本: " + config.getConfigVersion());
            return config;
            
        } catch (Exception e) {
            Log.e(TAG, "本地缓存解析失败: " + e.getMessage(), e);
            // 删除损坏的缓存文件
            cacheFile.delete();
            return null;
        }
    }
    
    /**
     * 删除本地缓存
     */
    public void clearCache() {
        File cacheFile = getCacheFile();
        if (cacheFile.exists()) {
            cacheFile.delete();
            SharedPreferencesUtil.remove(context, "config_version");
            SharedPreferencesUtil.remove(context, "config_cache_time");
            Log.i(TAG, "本地缓存已清除");
        }
    }
    
    /**
     * 获取缓存文件
     */
    private File getCacheFile() {
        File appDir = com.oilquiz.app.infra.Storage.getAppDir(context);
        return new File(appDir, ConfigConstants.CONFIG_CACHE_FILE);
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
