package com.oilquiz.app.ai.usage;

import android.util.Log;

import com.oilquiz.app.infra.Network;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Gitee 远程数据源
 * 从 Gitee 获取 AI 用量配置文件
 */
public class GiteeRemoteDataSource {
    
    private static final String TAG = "GiteeRemoteDataSource";
    
    private String configUrl;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    
    /**
     * 无参构造 - 首次不传 URL, 需要先调用 initFromConfig()
     */
    public GiteeRemoteDataSource() {
        this.connectTimeoutMs = ConfigConstants.DEFAULT_CONNECT_TIMEOUT;
        this.readTimeoutMs = ConfigConstants.DEFAULT_READ_TIMEOUT;
    }
    
    /**
     * 从配置文件中初始化 (必须先调用此方法才能使用)
     * @param config 远程配置
     */
    public void initFromConfig(RemoteConfig config) {
        if (config != null && config.getRemoteConfig() != null) {
            RemoteConfig.RemoteConnection rc = config.getRemoteConfig();
            this.configUrl = rc.getGiteeUrl();
            this.connectTimeoutMs = rc.getConnectTimeoutMs() > 0 ? rc.getConnectTimeoutMs() : this.connectTimeoutMs;
            this.readTimeoutMs = rc.getReadTimeoutMs() > 0 ? rc.getReadTimeoutMs() : this.readTimeoutMs;
            Log.i(TAG, "初始化远程数据源: URL=" + this.configUrl + 
                   ", connect=" + connectTimeoutMs + "ms, read=" + readTimeoutMs + "ms");
        } else {
            Log.w(TAG, "配置文件中没有 remoteConfig, 使用默认值");
        }
    }
    
    /**
     * 同步获取配置 (在主线程外调用)
     * @return 配置 JSON 字符串
     * @throws IOException 网络异常
     * @throws JSONException JSON 解析异常
     */
    public RemoteConfig fetchConfigSync() throws IOException, JSONException {
        if (configUrl == null || configUrl.isEmpty()) {
            throw new IOException("Gitee URL 未配置, 请先调用 initFromConfig()");
        }
        
        Log.i(TAG, "开始拉取远程配置: " + configUrl);
        
        String jsonStr = Network.get(configUrl);
        
        // 验证配置大小
        if (jsonStr.length() > ConfigConstants.MAX_CONFIG_SIZE) {
            throw new IOException("配置文件过大: " + jsonStr.length());
        }
        
        Log.i(TAG, "远程配置获取成功, 大小: " + jsonStr.length() + " bytes");
        
        // 解析 JSON
        JSONObject json = new JSONObject(jsonStr);
        
        RemoteConfig config = new RemoteConfig();
        config.setConfigVersion(json.optInt("configVersion", 0));
        config.setLastUpdated(json.optString("lastUpdated", ""));
        
        // 解析 providers
        RemoteConfig.ProviderConfig[] providers = parseProviders(json);
        config.setProviders(java.util.Arrays.asList(providers));
        
        // 解析 responseSchemas
        RemoteConfig.ResponseSchema[] schemas = parseResponseSchemas(json);
        config.setResponseSchemas(java.util.Arrays.asList(schemas));
        
        // 解析 models
        RemoteConfig.ModelConfig[] models = parseModels(json);
        config.setModels(java.util.Arrays.asList(models));
        
        // 解析 prices
        RemoteConfig.PriceConfig[] prices = parsePrices(json);
        config.setPrices(java.util.Arrays.asList(prices));
        
        // 解析 requestSchemas
        RemoteConfig.RequestSchema[] requestSchemas = parseRequestSchemas(json);
        config.setRequestSchemas(java.util.Arrays.asList(requestSchemas));
        
        return config;
    }
    
    /**
     * 异步获取配置
     * @param listener 结果监听器
     */
    public void fetchConfigAsync(ConfigFetchListener listener) {
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(configUrl)
                .build();
        
        Network.getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "远程配置获取失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    IOException e = new IOException("HTTP 状态码: " + response.code());
                    Log.e(TAG, "远程配置获取失败: " + e.getMessage());
                    if (listener != null) {
                        listener.onError(e);
                    }
                    return;
                }
                
                ResponseBody body = response.body();
                if (body == null) {
                    IOException e = new IOException("响应体为空");
                    Log.e(TAG, e.getMessage());
                    if (listener != null) {
                        listener.onError(e);
                    }
                    return;
                }
                
                try {
                    String jsonStr = body.string();
                    
                    // 验证配置大小
                    if (jsonStr.length() > ConfigConstants.MAX_CONFIG_SIZE) {
                        throw new IOException("配置文件过大: " + jsonStr.length());
                    }
                    
                    JSONObject json = new JSONObject(jsonStr);
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
                    
                    RemoteConfig.RequestSchema[] requestSchemas = parseRequestSchemas(json);
                    config.setRequestSchemas(java.util.Arrays.asList(requestSchemas));
                    
                    if (listener != null) {
                        listener.onSuccess(config);
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "配置解析失败: " + e.getMessage(), e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                }
            }
        });
    }
    
    /**
     * 解析 providers 数组
     */
    private RemoteConfig.ProviderConfig[] parseProviders(JSONObject json) throws JSONException {
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
    private RemoteConfig.ResponseSchema[] parseResponseSchemas(JSONObject json) throws JSONException {
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
    private RemoteConfig.ModelConfig[] parseModels(JSONObject json) throws JSONException {
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
    private RemoteConfig.PriceConfig[] parsePrices(JSONObject json) throws JSONException {
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
    
    /**
     * 解析 requestSchemas 数组
     */
    private RemoteConfig.RequestSchema[] parseRequestSchemas(JSONObject json) throws JSONException {
        org.json.JSONArray arr = json.optJSONArray("requestSchemas");
        if (arr == null) {
            return new RemoteConfig.RequestSchema[0];
        }
        
        RemoteConfig.RequestSchema[] result = new RemoteConfig.RequestSchema[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            RemoteConfig.RequestSchema item = new RemoteConfig.RequestSchema();
            item.setSchemaId(obj.optString("schemaId", ""));
            item.setProviderId(obj.optString("providerId", ""));
            item.setModelId(obj.optString("modelId", ""));
            item.setRequestBodyPath(obj.optString("requestBodyPath", ""));
            item.setMessagesPath(obj.optString("messagesPath", ""));
            item.setMessageRolePath(obj.optString("messageRolePath", ""));
            item.setMessageContentPath(obj.optString("messageContentPath", ""));
            item.setSystemMessageRole(obj.optString("systemMessageRole", ""));
            item.setUserMessageRole(obj.optString("userMessageRole", ""));
            item.setAssistantMessageRole(obj.optString("assistantMessageRole", ""));
            item.setTemperaturePath(obj.optString("temperaturePath", ""));
            item.setTopPPath(obj.optString("topPPath", ""));
            item.setMaxTokensPath(obj.optString("maxTokensPath", ""));
            item.setStreamPath(obj.optString("streamPath", ""));
            item.setSupportsStream(obj.optBoolean("supportsStream", true));
            result[i] = item;
        }
        return result;
    }
    
    /**
     * 配置获取监听器
     */
    public interface ConfigFetchListener {
        void onSuccess(RemoteConfig config);
        void onError(Exception e);
    }
}
