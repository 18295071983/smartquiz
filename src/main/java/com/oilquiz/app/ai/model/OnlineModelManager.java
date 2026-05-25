package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class OnlineModelManager {
    private static final String TAG = "OnlineModelManager";
    private static final String PREFS_NAME = "online_models_config";
    private static final String KEY_MODELS = "models_json";
    private static final String KEY_ACTIVE_ID = "active_model_id";

    private static volatile OnlineModelManager INSTANCE;
    private final Context context;
    private final SharedPreferences prefs;
    private final List<OnlineModelConfig> modelList = new CopyOnWriteArrayList<>();
    private String activeModelId;
    private final List<ModelChangeListener> listeners = new CopyOnWriteArrayList<>();

    public static class OnlineModelConfig {
        public String id;
        public String name;
        public String apiUrl;
        public String modelName;
        public String apiKey;
        public boolean enabled;
        public long createdAt;
        
        // 增强字段
        public String selectedModel;              // 用户选择的模型
        public long lastFetchTime;                // 上次获取模型列表时间
        public String cachedModelsJson;           // 缓存的可用模型列表（JSON）
        public UsageInfo usageInfo;               // 使用量信息
        public long lastUsageFetchTime;           // 上次获取使用量时间
        public boolean autoFetchModels;           // 是否自动获取模型列表
        
        public OnlineModelConfig() {}
        
        public OnlineModelConfig(String id, String name, String apiUrl, String modelName,
                                 String apiKey, boolean enabled, long createdAt) {
            this.id = id;
            this.name = name;
            this.apiUrl = apiUrl;
            this.modelName = modelName;
            this.apiKey = apiKey;
            this.enabled = enabled;
            this.createdAt = createdAt;
            this.autoFetchModels = true;
        }
    }

    public interface ModelChangeListener {
        void onModelListChanged();
        void onActiveModelChanged(String activeModelId);
    }

    private OnlineModelManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromPrefs();
    }

    public static OnlineModelManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (OnlineModelManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OnlineModelManager(context);
                }
            }
        }
        return INSTANCE;
    }

    private void loadFromPrefs() {
        String json = prefs.getString(KEY_MODELS, null);
        activeModelId = prefs.getString(KEY_ACTIVE_ID, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    OnlineModelConfig config = new OnlineModelConfig(
                        obj.getString("id"), obj.getString("name"),
                        obj.getString("apiUrl"), obj.getString("modelName"),
                        obj.getString("apiKey"), obj.optBoolean("enabled", true),
                        obj.optLong("createdAt", System.currentTimeMillis()));
                    
                    // 加载增强字段
                    config.selectedModel = obj.optString("selectedModel", null);
                    config.lastFetchTime = obj.optLong("lastFetchTime", 0);
                    config.cachedModelsJson = obj.optString("cachedModelsJson", null);
                    config.autoFetchModels = obj.optBoolean("autoFetchModels", true);
                    config.lastUsageFetchTime = obj.optLong("lastUsageFetchTime", 0);
                    
                    // 加载使用量信息
                    if (obj.has("usageInfo")) {
                        JSONObject usageObj = obj.getJSONObject("usageInfo");
                        config.usageInfo = new UsageInfo();
                        config.usageInfo.totalQuota = usageObj.optLong("totalQuota", 0);
                        config.usageInfo.usedQuota = usageObj.optLong("usedQuota", 0);
                        config.usageInfo.remainingQuota = usageObj.optLong("remainingQuota", 0);
                        config.usageInfo.quotaType = usageObj.optString("quotaType", "tokens");
                        config.usageInfo.period = usageObj.optString("period", "monthly");
                        config.usageInfo.totalCost = usageObj.optDouble("totalCost", 0);
                        config.usageInfo.usedCost = usageObj.optDouble("usedCost", 0);
                        config.usageInfo.lastUpdated = usageObj.optLong("lastUpdated", 0);
                    }
                    
                    modelList.add(config);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "load在线模型配置fail", e);
            }
        }
    }

    private void saveToPrefs() {
        try {
            JSONArray arr = new JSONArray();
            for (OnlineModelConfig config : modelList) {
                JSONObject obj = new JSONObject();
                obj.put("id", config.id);
                obj.put("name", config.name);
                obj.put("apiUrl", config.apiUrl);
                obj.put("modelName", config.modelName);
                obj.put("apiKey", config.apiKey);
                obj.put("enabled", config.enabled);
                obj.put("createdAt", config.createdAt);
                obj.put("selectedModel", config.selectedModel != null ? config.selectedModel : "");
                obj.put("lastFetchTime", config.lastFetchTime);
                obj.put("cachedModelsJson", config.cachedModelsJson != null ? config.cachedModelsJson : "");
                obj.put("autoFetchModels", config.autoFetchModels);
                obj.put("lastUsageFetchTime", config.lastUsageFetchTime);
                
                // 保存使用量信息
                if (config.usageInfo != null) {
                    JSONObject usageObj = new JSONObject();
                    usageObj.put("totalQuota", config.usageInfo.totalQuota);
                    usageObj.put("usedQuota", config.usageInfo.usedQuota);
                    usageObj.put("remainingQuota", config.usageInfo.remainingQuota);
                    usageObj.put("quotaType", config.usageInfo.quotaType);
                    usageObj.put("period", config.usageInfo.period);
                    usageObj.put("totalCost", config.usageInfo.totalCost);
                    usageObj.put("usedCost", config.usageInfo.usedCost);
                    usageObj.put("lastUpdated", config.usageInfo.lastUpdated);
                    obj.put("usageInfo", usageObj);
                }
                
                arr.put(obj);
            }
            prefs.edit().putString(KEY_MODELS, arr.toString())
                .putString(KEY_ACTIVE_ID, activeModelId).apply();
        } catch (Exception e) {
            AILogger.e(TAG, "save在线模型配置fail", e);
        }
    }

    public OnlineModelConfig addModel(String name, String apiUrl, String modelName, String apiKey) {
        OnlineModelConfig config = new OnlineModelConfig(
            UUID.randomUUID().toString(), name, apiUrl, modelName, apiKey, true, System.currentTimeMillis());
        modelList.add(config);
        saveToPrefs();
        notifyListChanged();
        return config;
    }

    /**
     * 保存模型列表到 SharedPreferences
     */
    public void save() {
        saveToPrefs();
    }

    public void removeModel(String modelId) {
        boolean wasActive = modelId.equals(activeModelId);
        modelList.removeIf(c -> c.id.equals(modelId));
        if (wasActive) { activeModelId = null; notifyActiveChanged(); }
        saveToPrefs();
        notifyListChanged();
    }

    public void setActiveModel(String modelId) {
        OnlineModelConfig config = getModel(modelId);
        if (config == null || !config.enabled) return;
        activeModelId = modelId;
        saveToPrefs();
        notifyActiveChanged();
    }

    public void stopActiveModel() {
        activeModelId = null;
        saveToPrefs();
        notifyActiveChanged();
    }

    public OnlineModelConfig getActiveModel() {
        return activeModelId != null ? getModel(activeModelId) : null;
    }

    public OnlineModelConfig getModel(String modelId) {
        for (OnlineModelConfig c : modelList) {
            if (c.id.equals(modelId)) return c;
        }
        return null;
    }

    public List<OnlineModelConfig> getModelList() { return new ArrayList<>(modelList); }
    public boolean hasModels() { return !modelList.isEmpty(); }

    public void addListener(ModelChangeListener listener) { listeners.add(listener); }
    public void removeListener(ModelChangeListener listener) { listeners.remove(listener); }

    private void notifyListChanged() {
        for (ModelChangeListener l : listeners) l.onModelListChanged();
    }

    private void notifyActiveChanged() {
        for (ModelChangeListener l : listeners) l.onActiveModelChanged(activeModelId);
    }

    // ========== 增强方法 ==========

    /**
     * 获取推理类型
     * @param modelId 模型ID
     * @return 推理类型枚举
     */
    public InferenceType getInferenceType(String modelId) {
        return InferenceType.ONLINE;
    }

    /**
     * 检查是否是在线模型
     * @param modelId 模型ID
     * @return 如果是在线模型返回true
     */
    public boolean isOnlineModel(String modelId) {
        return getModel(modelId) != null;
    }

    /**
     * 获取模型显示名称
     * @param modelId 模型ID
     * @return 模型显示名称
     */
    public String getModelDisplayName(String modelId) {
        OnlineModelConfig config = getModel(modelId);
        if (config != null) {
            return config.name + " (" + config.modelName + ")";
        }
        return modelId;
    }

    /**
     * 验证配置是否有效
     * @param config 在线模型配置
     * @return 如果配置有效返回true
     */
    public boolean validateConfig(OnlineModelConfig config) {
        if (config == null) {
            return false;
        }
        if (config.apiUrl == null || config.apiUrl.isEmpty()) {
            return false;
        }
        if (config.modelName == null || config.modelName.isEmpty()) {
            return false;
        }
        if (config.apiKey == null || config.apiKey.isEmpty()) {
            return false;
        }
        // 简单的URL格式验证
        if (!config.apiUrl.startsWith("http://") && !config.apiUrl.startsWith("https://")) {
            return false;
        }
        return true;
    }

    /**
     * 检测 API 类型
     * @param apiUrl API URL
     * @return API 类型描述字符串
     */
    public String detectAPIType(String apiUrl) {
        if (apiUrl == null) {
            return "unknown";
        }
        String lowerUrl = apiUrl.toLowerCase();
        if (lowerUrl.contains("anthropic")) {
            return "Anthropic Claude";
        }
        if (lowerUrl.contains("openai")) {
            return "OpenAI";
        }
        if (lowerUrl.contains("google") || lowerUrl.contains("generativelanguage")) {
            return "Google Gemini";
        }
        if (lowerUrl.contains("azure")) {
            return "Azure OpenAI";
        }
        if (lowerUrl.contains("groq")) {
            return "Groq";
        }
        if (lowerUrl.contains("together")) {
            return "Together AI";
        }
        return "Custom";
    }

    /**
     * 更新模型配置
     * @param modelId 模型ID
     * @param name 新名称（可为null，保留原值）
     * @param apiUrl 新API地址（可为null，保留原值）
     * @param modelName 新模型名称（可为null，保留原值）
     * @param apiKey 新API密钥（可为null，保留原值）
     * @param enabled 是否启用
     */
    public void updateModel(String modelId, String name, String apiUrl, String modelName,
                            String apiKey, boolean enabled) {
        OnlineModelConfig config = getModel(modelId);
        if (config == null) {
            return;
        }
        if (name != null) config.name = name;
        if (apiUrl != null) config.apiUrl = apiUrl;
        if (modelName != null) config.modelName = modelName;
        if (apiKey != null) config.apiKey = apiKey;
        config.enabled = enabled;
        saveToPrefs();
        notifyListChanged();
    }

    /**
     * 获取已启用的模型列表
     * @return 启用的在线模型列表
     */
    public List<OnlineModelConfig> getEnabledModels() {
        List<OnlineModelConfig> enabled = new ArrayList<>();
        for (OnlineModelConfig config : modelList) {
            if (config.enabled) {
                enabled.add(config);
            }
        }
        return enabled;
    }

    /**
     * 检查是否有激活的在线模型
     * @return 如果有激活的在线模型返回true
     */
    public boolean hasActiveOnlineModel() {
        return activeModelId != null;
    }

    // ========== 增强方法 ==========

    /**
     * 保存用户选择的模型
     * @param modelId 模型ID
     * @param selectedModel 用户选择的模型名称
     */
    public void saveSelectedModel(String modelId, String selectedModel) {
        OnlineModelConfig config = getModel(modelId);
        if (config != null) {
            config.selectedModel = selectedModel;
            config.modelName = selectedModel; // 同时更新 modelName
            saveToPrefs();
            notifyListChanged();
        }
    }

    /**
     * 获取用户选择的模型
     * @param modelId 模型ID
     * @return 用户选择的模型名称
     */
    public String getSelectedModel(String modelId) {
        OnlineModelConfig config = getModel(modelId);
        return config != null ? config.selectedModel : null;
    }

    /**
     * 保存缓存的模型列表
     * @param modelId 模型ID
     * @param modelsJson 模型列表 JSON
     */
    public void saveCachedModels(String modelId, String modelsJson) {
        OnlineModelConfig config = getModel(modelId);
        if (config != null) {
            config.cachedModelsJson = modelsJson;
            config.lastFetchTime = System.currentTimeMillis();
            saveToPrefs();
        }
    }

    /**
     * 获取缓存的模型列表
     * @param modelId 模型ID
     * @return 缓存的模型列表 JSON
     */
    public String getCachedModels(String modelId) {
        OnlineModelConfig config = getModel(modelId);
        return config != null ? config.cachedModelsJson : null;
    }

    /**
     * 检查缓存是否过期（超过6小时）
     * @param modelId 模型ID
     * @return 如果缓存过期返回true
     */
    public boolean isCacheExpired(String modelId) {
        OnlineModelConfig config = getModel(modelId);
        if (config == null || config.lastFetchTime == 0) {
            return true;
        }
        long sixHours = 6 * 60 * 60 * 1000;
        return (System.currentTimeMillis() - config.lastFetchTime) > sixHours;
    }

    /**
     * 保存使用量信息
     * @param modelId 模型ID
     * @param usageInfo 使用量信息
     */
    public void saveUsageInfo(String modelId, UsageInfo usageInfo) {
        OnlineModelConfig config = getModel(modelId);
        if (config != null) {
            config.usageInfo = usageInfo;
            config.lastUsageFetchTime = System.currentTimeMillis();
            saveToPrefs();
        }
    }

    /**
     * 获取使用量信息
     * @param modelId 模型ID
     * @return 使用量信息
     */
    public UsageInfo getUsageInfo(String modelId) {
        OnlineModelConfig config = getModel(modelId);
        return config != null ? config.usageInfo : null;
    }

    /**
     * 完整更新模型配置
     * @param config 新的配置
     */
    public void updateModelConfig(OnlineModelConfig config) {
        if (config == null || config.id == null) {
            return;
        }
        OnlineModelConfig existing = getModel(config.id);
        if (existing != null) {
            int index = modelList.indexOf(existing);
            modelList.set(index, config);
            saveToPrefs();
            notifyListChanged();
        }
    }

    // ========== API配置转换 ==========

    /**
     * 将APIConfig转换为OnlineModelConfig
     * @param apiConfig API配置
     * @return 在线模型配置
     */
    public OnlineModelConfig convertFromAPIConfig(APIConfig apiConfig) {
        if (apiConfig == null) {
            return null;
        }
        // 生成"服务商+模型名称"的格式
        String serviceType = apiConfig.getServiceType();
        String serviceName = getServiceTypeChineseName(serviceType);
        String modelName = apiConfig.getModelName();
        String displayName;
        
        if (modelName != null && !modelName.isEmpty()) {
            displayName = serviceName + " · " + modelName;
        } else {
            displayName = apiConfig.getName() != null ? apiConfig.getName() : serviceName;
        }
        
        OnlineModelConfig config = new OnlineModelConfig(
            apiConfig.getId() != null ? apiConfig.getId() : UUID.randomUUID().toString(),
            displayName,
            apiConfig.getApiHost() != null ? apiConfig.getApiHost() : "",
            apiConfig.getModelName() != null ? apiConfig.getModelName() : "",
            apiConfig.getApiKey() != null ? apiConfig.getApiKey() : "",
            apiConfig.isActive(),
            apiConfig.getCreatedAt() > 0 ? apiConfig.getCreatedAt() : System.currentTimeMillis()
        );
        config.selectedModel = apiConfig.getModelName();
        return config;
    }
    
    private String getServiceTypeChineseName(String serviceType) {
        if (serviceType == null) {
            return "自定义";
        }
        switch (serviceType) {
            case APIConfig.ServiceType.OPENAI:
                return "OpenAI";
            case APIConfig.ServiceType.ANTHROPIC:
                return "Anthropic";
            case APIConfig.ServiceType.GOOGLE:
                return "Google";
            case APIConfig.ServiceType.CUSTOM:
                return "自定义";
            default:
                return serviceType;
        }
    }

    /**
     * 从APIKeyManager导入所有有效的AI配置
     * @return 导入的模型数量
     */
    public int importFromAPIKeyManager() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        List<APIConfig> apiConfigs = apiKeyManager.getAllAPIConfigs();
        int imported = 0;
        
        for (APIConfig apiConfig : apiConfigs) {
            // 只导入AI类型的配置
            String category = apiConfig.getCategory();
            String serviceType = apiConfig.getServiceType();
            boolean isAIConfig = (category != null && category.equals(APIConfig.Category.AI)) ||
                                (serviceType != null && (
                                    serviceType.equals(APIConfig.ServiceType.OPENAI) ||
                                    serviceType.equals(APIConfig.ServiceType.ANTHROPIC) ||
                                    serviceType.equals(APIConfig.ServiceType.GOOGLE) ||
                                    serviceType.equals(APIConfig.ServiceType.CUSTOM)
                                ));
            
            if (!isAIConfig) {
                continue;
            }
            
            // 检查必要字段
            if (apiConfig.getApiHost() == null || apiConfig.getApiHost().isEmpty() ||
                apiConfig.getApiKey() == null || apiConfig.getApiKey().isEmpty()) {
                continue;
            }
            
            // 检查是否已存在
            boolean exists = false;
            for (OnlineModelConfig existing : modelList) {
                if (existing.id.equals(apiConfig.getId())) {
                    exists = true;
                    break;
                }
                if (existing.apiUrl != null && existing.apiUrl.equals(apiConfig.getApiHost()) &&
                    existing.apiKey != null && existing.apiKey.equals(apiConfig.getApiKey())) {
                    exists = true;
                    break;
                }
            }
            
            if (!exists) {
                OnlineModelConfig config = convertFromAPIConfig(apiConfig);
                if (config != null) {
                    modelList.add(config);
                    imported++;
                }
            }
        }
        
        if (imported > 0) {
            saveToPrefs();
            notifyListChanged();
        }
        
        return imported;
    }

    /**
     * 检查是否有可用的在线模型
     * @return 如果有可用的在线模型返回true
     */
    public boolean hasAvailableOnlineModel() {
        for (OnlineModelConfig config : modelList) {
            if (config.enabled && config.apiUrl != null && !config.apiUrl.isEmpty() &&
                config.apiKey != null && !config.apiKey.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取第一个可用的在线模型
     * @return 第一个可用的在线模型配置，如果没有则返回null
     */
    public OnlineModelConfig getFirstAvailableOnlineModel() {
        for (OnlineModelConfig config : modelList) {
            if (config.enabled && config.apiUrl != null && !config.apiUrl.isEmpty() &&
                config.apiKey != null && !config.apiKey.isEmpty()) {
                return config;
            }
        }
        return null;
    }
}
