package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class OnlineModelManager {
    private static final String TAG = "OnlineModelManager";
    private static final String PREFS_NAME = "online_models_config";
    private static final String KEY_MODELS = "models_json";
    private static final String KEY_ACTIVE_ID = "active_model_id";
    private static final String KEY_FEATURE_MODELS = "feature_models"; // 功能专用模型 Map
    private static final String KEY_FEATURE_MODEL_NAMES = "feature_model_names"; // 功能专用模型的具体模型名
    private static final String KEY_TTS_VOICE = "tts_voice"; // TTS 音色（如 alloy/longwan）
    
    // 功能专用模型的 feature key 常量
    public static final String FEATURE_OCR = "ocr";           // OCR 文字识别
    public static final String FEATURE_TRANSLATION = "translation"; // 翻译
    public static final String FEATURE_SUMMARY = "summary";     // 摘要生成
    public static final String FEATURE_CODE = "code";           // 代码生成
    public static final String FEATURE_ASR = "asr";             // 语音识别（Speech-to-Text）
    public static final String FEATURE_TTS = "tts";             // 语音合成（Text-to-Speech）
    // 后续可继续添加更多功能...

    private static volatile OnlineModelManager INSTANCE;
    private final Context context;
    private final SharedPreferences prefs;
    private final List<OnlineModelConfig> modelList = new CopyOnWriteArrayList<>();
    private String activeModelId;
    private final Map<String, String> featureModelIds = new ConcurrentHashMap<>(); // feature -> modelId（API 端点配置）
    private final Map<String, String> featureModelNames = new ConcurrentHashMap<>(); // feature -> 具体模型名（同一 API Key 下的某个模型）
    private final List<ModelChangeListener> listeners = new CopyOnWriteArrayList<>();

    public static class OnlineModelConfig {
        public String id;
        public String name;
        public String apiUrl;
        public String modelName;
        public String apiKey;
        /** 讯飞 APISecret / 百度 SecretKey / 火山引擎 Token（按端点类型使用） */
        public String apiSecret;
        /** 讯飞 AppID / 火山引擎 AppID / 百度 AppID（按端点类型使用） */
        public String appId;
        public boolean enabled;
        public long createdAt;
        
        // 增强字段
        public String selectedModel;              // 用户选择的模型
        public long lastFetchTime;                // 上次获取模型列表时间
        public String cachedModelsJson;           // 缓存的可用模型列表（JSON）
        public UsageInfo usageInfo;               // 使用量信息
        public long lastUsageFetchTime;           // 上次获取使用量时间
        public boolean autoFetchModels;           // 是否自动获取模型列表
        
        // === 新增：模型能力元数据 ===
        public ModelCapabilities capabilities;    // 模型能力标签
        public int contextWindow = 4096;          // 上下文窗口大小（tokens）
        public int maxOutputTokens = 2048;        // 最大输出长度
        public boolean supportsVision = false;    // 是否支持视觉理解
        public boolean supportsAudio = false;     // 是否支持音频处理
        public boolean supportsCode = false;      // 是否擅长代码生成
        public double costPerMillionTokens = 0;   // 每百万token成本（美元）
        
        public OnlineModelConfig() {
            this.capabilities = new ModelCapabilities();
        }
        
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
            this.capabilities = new ModelCapabilities();
        }
        
        /**
         * 检查模型是否具备指定能力
         */
        public boolean hasCapability(String capability) {
            if (capabilities == null) return false;
            
            switch (capability.toLowerCase()) {
                case "vision":
                    return supportsVision || capabilities.supportsImageInput;
                case "audio":
                    return supportsAudio || capabilities.supportsAudioInput;
                case "coding":
                    return supportsCode || capabilities.supportsCodeGeneration;
                case "long_context":
                    return contextWindow >= 32768; // 32K+ 视为长上下文
                default:
                    return false;
            }
        }
    }
    
    /**
     * 模型能力描述
     */
    public static class ModelCapabilities {
        public boolean supportsStreaming = true;
        public boolean supportsFunctionCalling = false;
        public boolean supportsCodeGeneration = false;
        public boolean supportsImageInput = false;      // 图像输入
        public boolean supportsAudioInput = false;      // 音频输入
        public int maxOutputTokens = 4096;
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
        
        // 加载功能专用模型 Map
        String featureJson = prefs.getString(KEY_FEATURE_MODELS, null);
        if (featureJson != null) {
            try {
                JSONObject obj = new JSONObject(featureJson);
                JSONArray keys = obj.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String key = keys.getString(i);
                        featureModelIds.put(key, obj.getString(key));
                    }
                }
            } catch (Exception ignored) {}
        }
        
        // 加载功能专用模型的具体模型名
        String featureNamesJson = prefs.getString(KEY_FEATURE_MODEL_NAMES, null);
        if (featureNamesJson != null) {
            try {
                JSONObject obj = new JSONObject(featureNamesJson);
                JSONArray keys = obj.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String key = keys.getString(i);
                        String name = obj.optString(key, null);
                        if (name != null && !name.isEmpty()) {
                            featureModelNames.put(key, name);
                        }
                    }
                }
            } catch (Exception e) {
                AILogger.w(TAG, "Parse feature models failed: " + e.getMessage());
            }
        }
        
        // 向后兼容：迁移旧的 ocr_model_id 到新的 Map 结构
        String legacyOcrId = prefs.getString("ocr_model_id", null);
        if (legacyOcrId != null && !featureModelIds.containsKey(FEATURE_OCR)) {
            featureModelIds.put(FEATURE_OCR, legacyOcrId);
            prefs.edit().remove("ocr_model_id").apply(); // 清除旧 key
            saveFeatureModels();
        }
        
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
                    config.apiSecret = obj.optString("apiSecret", null);
                    config.appId = obj.optString("appId", null);
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
                obj.put("apiSecret", config.apiSecret != null ? config.apiSecret : "");
                obj.put("appId", config.appId != null ? config.appId : "");
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
            
            // 构建功能专用模型 JSON
            JSONObject featureObj = new JSONObject();
            for (Map.Entry<String, String> entry : featureModelIds.entrySet()) {
                if (entry.getValue() != null) {
                    featureObj.put(entry.getKey(), entry.getValue());
                }
            }
            
            // 构建功能专用模型名 JSON
            JSONObject featureNamesObj = new JSONObject();
            for (Map.Entry<String, String> entry : featureModelNames.entrySet()) {
                if (entry.getValue() != null) {
                    featureNamesObj.put(entry.getKey(), entry.getValue());
                }
            }
            
            prefs.edit().putString(KEY_MODELS, arr.toString())
                .putString(KEY_ACTIVE_ID, activeModelId)
                .putString(KEY_FEATURE_MODELS, featureObj.toString())
                .putString(KEY_FEATURE_MODEL_NAMES, featureNamesObj.toString()).apply();
        } catch (Exception e) {
            AILogger.e(TAG, "save在线模型配置fail", e);
        }
    }
    
    /**
     * 单独保存功能专用模型（不触发模型列表变更通知）
     */
    private void saveFeatureModels() {
        try {
            JSONObject featureObj = new JSONObject();
            for (Map.Entry<String, String> entry : featureModelIds.entrySet()) {
                if (entry.getValue() != null) {
                    featureObj.put(entry.getKey(), entry.getValue());
                }
            }
            JSONObject featureNamesObj = new JSONObject();
            for (Map.Entry<String, String> entry : featureModelNames.entrySet()) {
                if (entry.getValue() != null) {
                    featureNamesObj.put(entry.getKey(), entry.getValue());
                }
            }
            prefs.edit().putString(KEY_FEATURE_MODELS, featureObj.toString())
                .putString(KEY_FEATURE_MODEL_NAMES, featureNamesObj.toString()).apply();
        } catch (Exception e) {
            AILogger.e(TAG, "saveFeatureModels fail: " + e.getMessage());
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
        
        // 清除功能专用模型中对该模型的引用
        boolean featureChanged = featureModelIds.values().removeIf(v -> modelId.equals(v));
        if (featureChanged) {
            saveFeatureModels();
        }
        
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

    // ========== 功能专用模型通用框架 ==========
    
    /**
     * 设置功能专用模型
     * @param feature 功能标识（如 FEATURE_OCR, FEATURE_TRANSLATION 等）
     * @param modelId 模型 ID，传 null 表示清除专用模型配置
     */
    public void setFeatureModel(String feature, String modelId) {
        setFeatureModel(feature, modelId, null);
    }
    
    /**
     * 设置功能专用模型（含具体模型名）
     * 同一个 API Key 下可能有多个模型，此方法允许为功能指定具体的模型名
     * @param feature 功能标识
     * @param modelId API 端点配置 ID
     * @param modelName 具体模型名（null 表示使用该端点配置的默认模型）
     */
    public void setFeatureModel(String feature, String modelId, String modelName) {
        if (feature == null) return;
        if (modelId == null) {
            featureModelIds.remove(feature);
            featureModelNames.remove(feature);
        } else {
            featureModelIds.put(feature, modelId);
            if (modelName != null && !modelName.isEmpty()) {
                featureModelNames.put(feature, modelName);
            } else {
                featureModelNames.remove(feature);
            }
        }
        saveFeatureModels();
    }
    
    /**
     * 获取功能专用模型的具体模型名
     * @param feature 功能标识
     * @return 具体模型名，未设置则返回 null（调用方应使用端点配置的默认模型）
     */
    public String getFeatureModelName(String feature) {
        return featureModelNames.get(feature);
    }
    
    /**
     * 获取功能专用模型配置
     * @param feature 功能标识
     * @return 模型配置，如果未设置或模型已禁用则返回 null
     */
    public OnlineModelConfig getFeatureModel(String feature) {
        String modelId = featureModelIds.get(feature);
        if (modelId != null) {
            OnlineModelConfig config = getModel(modelId);
            if (config != null && config.enabled) return config;
        }
        return null;
    }
    
    /**
     * 获取功能专用模型 ID
     * @param feature 功能标识
     * @return 模型 ID，如果未设置则返回 null
     */
    public String getFeatureModelId(String feature) {
        return featureModelIds.get(feature);
    }
    
    /**
     * 检查是否已配置功能专用模型
     */
    public boolean hasFeatureModel(String feature) {
        return getFeatureModel(feature) != null;
    }
    
    /**
     * 获取所有已配置的功能专用模型
     * @return feature -> modelId 的不可变 Map
     */
    public Map<String, String> getAllFeatureModels() {
        return java.util.Collections.unmodifiableMap(new java.util.HashMap<>(featureModelIds));
    }
    
    // ========== OCR 专用模型（便捷方法） ==========
    
    /**
     * 设置 OCR 专用模型
     * OCR 模型独立于聊天模型，允许用户为 OCR 任务选择专门的视觉模型
     */
    public void setOCRModel(String modelId) {
        setFeatureModel(FEATURE_OCR, modelId);
    }
    
    /**
     * 设置 OCR 专用模型（含具体模型名）
     * 同一个 API Key 下有多个模型时，可为 OCR 单独指定某个视觉模型
     */
    public void setOCRModel(String modelId, String modelName) {
        setFeatureModel(FEATURE_OCR, modelId, modelName);
    }
    
    /**
     * 获取 OCR 专用模型的具体模型名（可能为 null，表示用端点默认模型）
     */
    public String getOCRModelName() {
        return getFeatureModelName(FEATURE_OCR);
    }
    
    /**
     * 获取 OCR 专用模型配置
     * @return OCR 模型配置，如果未设置则返回 null
     */
    public OnlineModelConfig getOCRModel() {
        return getFeatureModel(FEATURE_OCR);
    }
    
    /**
     * 获取 OCR 模型 ID
     */
    public String getOCRModelId() {
        return getFeatureModelId(FEATURE_OCR);
    }
    
    /**
     * 检查是否已配置 OCR 专用模型
     */
    public boolean hasOCRModel() {
        return hasFeatureModel(FEATURE_OCR);
    }

    // ========== 语音识别（ASR）专用模型（便捷方法） ==========

    /**
     * 设置语音识别专用模型（端点配置）
     */
    public void setASRModel(String modelId) {
        setFeatureModel(FEATURE_ASR, modelId);
    }

    /**
     * 设置语音识别专用模型（含具体模型名，如 whisper-1 / paraformer-v2）
     */
    public void setASRModel(String modelId, String modelName) {
        setFeatureModel(FEATURE_ASR, modelId, modelName);
    }

    /**
     * 获取语音识别专用模型的具体模型名（可能为 null）
     */
    public String getASRModelName() {
        return getFeatureModelName(FEATURE_ASR);
    }

    /**
     * 获取语音识别专用模型配置，未设置返回 null
     */
    public OnlineModelConfig getASRModel() {
        return getFeatureModel(FEATURE_ASR);
    }

    /**
     * 获取语音识别模型 ID
     */
    public String getASRModelId() {
        return getFeatureModelId(FEATURE_ASR);
    }

    /**
     * 检查是否已配置语音识别专用模型
     */
    public boolean hasASRModel() {
        return hasFeatureModel(FEATURE_ASR);
    }

    // ========== 语音合成（TTS）专用模型（便捷方法） ==========

    /**
     * 设置语音合成专用模型（端点配置）
     */
    public void setTTSModel(String modelId) {
        setFeatureModel(FEATURE_TTS, modelId);
    }

    /**
     * 设置语音合成专用模型（含具体模型名，如 tts-1 / cosyvoice-v2）
     */
    public void setTTSModel(String modelId, String modelName) {
        setFeatureModel(FEATURE_TTS, modelId, modelName);
    }

    /**
     * 获取语音合成专用模型的具体模型名（可能为 null）
     */
    public String getTTSModelName() {
        return getFeatureModelName(FEATURE_TTS);
    }

    /**
     * 获取语音合成专用模型配置，未设置返回 null
     */
    public OnlineModelConfig getTTSModel() {
        return getFeatureModel(FEATURE_TTS);
    }

    /**
     * 获取语音合成模型 ID
     */
    public String getTTSModelId() {
        return getFeatureModelId(FEATURE_TTS);
    }

    /**
     * 检查是否已配置语音合成专用模型
     */
    public boolean hasTTSModel() {
        return hasFeatureModel(FEATURE_TTS);
    }

    // ========== TTS 音色配置 ==========

    /**
     * 保存 TTS 音色（传 null 清除，恢复默认音色）
     */
    public void setTtsVoice(String voice) {
        if (voice == null || voice.isEmpty()) {
            prefs.edit().remove(KEY_TTS_VOICE).apply();
        } else {
            prefs.edit().putString(KEY_TTS_VOICE, voice).apply();
        }
    }

    /**
     * 获取已保存的 TTS 音色，未设置返回 null
     */
    public String getTtsVoice() {
        return prefs.getString(KEY_TTS_VOICE, null);
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
        // 显示名称优先使用用户在 API 配置中起的名字，避免多个同服务商配置无法区分
        // 实际模型名称（modelName/selectedModel）单独存储并在 UI 副标题显示
        String displayName = buildDisplayName(apiConfig);

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
     * 全量同步 APIKeyManager 中的 AI 配置：新增、更新、删除
     * - APIConfig 新增 -> 创建对应 OnlineModelConfig
     * - APIConfig 更新 -> 更新对应 OnlineModelConfig 的字段
     * - APIConfig 删除 -> 删除对应 OnlineModelConfig
     * - 非 AI 类型的 APIConfig 不会被同步
     * @return 发生变更的数量（新增 + 更新 + 删除）
     */
    public int syncFromAPIKeyManager() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        List<APIConfig> apiConfigs = apiKeyManager.getAllAPIConfigs();

        // 收集所有 AI 类型的 APIConfig ID
        java.util.Set<String> aiConfigIds = new java.util.HashSet<>();
        for (APIConfig apiConfig : apiConfigs) {
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
            if (apiConfig.getApiHost() == null || apiConfig.getApiHost().isEmpty() ||
                apiConfig.getApiKey() == null || apiConfig.getApiKey().isEmpty()) {
                continue;
            }
            aiConfigIds.add(apiConfig.getId());
        }

        int changes = 0;

        // 注意：删除由 removeByAPIConfigId 显式触发，这里只处理新增和更新

        // 新增 + 更新
        for (APIConfig apiConfig : apiConfigs) {
            if (!aiConfigIds.contains(apiConfig.getId())) {
                continue;
            }

            OnlineModelConfig existing = getModel(apiConfig.getId());
            if (existing == null) {
                // 新增
                OnlineModelConfig config = convertFromAPIConfig(apiConfig);
                if (config != null) {
                    modelList.add(config);
                    changes++;
                }
            } else {
                // 更新（仅当字段变化时）
                boolean changed = false;
                String newName = buildDisplayName(apiConfig);
                if (!equals(existing.name, newName)) {
                    existing.name = newName;
                    changed = true;
                }
                if (!equals(existing.apiUrl, apiConfig.getApiHost() != null ? apiConfig.getApiHost() : "")) {
                    existing.apiUrl = apiConfig.getApiHost() != null ? apiConfig.getApiHost() : "";
                    changed = true;
                }
                if (!equals(existing.apiKey, apiConfig.getApiKey() != null ? apiConfig.getApiKey() : "")) {
                    existing.apiKey = apiConfig.getApiKey() != null ? apiConfig.getApiKey() : "";
                    changed = true;
                }
                String newModelName = apiConfig.getModelName() != null ? apiConfig.getModelName() : "";
                if (!equals(existing.modelName, newModelName)) {
                    existing.modelName = newModelName;
                    if (newModelName != null && !newModelName.isEmpty()) {
                        existing.selectedModel = newModelName;
                    }
                    changed = true;
                }
                if (existing.enabled != apiConfig.isActive()) {
                    existing.enabled = apiConfig.isActive();
                    changed = true;
                }
                if (changed) {
                    changes++;
                }
            }
        }

        if (changes > 0) {
            saveToPrefs();
            notifyListChanged();
        }

        return changes;
    }

    /**
     * 同步单个 APIConfig 到 OnlineModelManager（保存/更新时调用）
     * @param apiConfigId APIConfig ID
     * @return 是否发生变更
     */
    public boolean syncSingleAPIConfig(String apiConfigId) {
        if (apiConfigId == null || apiConfigId.isEmpty()) {
            return false;
        }
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        APIConfig apiConfig = apiKeyManager.getAPIConfigById(apiConfigId);
        if (apiConfig == null) {
            // APIConfig 已被删除，移除对应的 OnlineModelConfig
            return removeByAPIConfigId(apiConfigId);
        }

        // 检查是否是 AI 类型
        String category = apiConfig.getCategory();
        String serviceType = apiConfig.getServiceType();
        boolean isAIConfig = (category != null && category.equals(APIConfig.Category.AI)) ||
                            (serviceType != null && (
                                serviceType.equals(APIConfig.ServiceType.OPENAI) ||
                                serviceType.equals(APIConfig.ServiceType.ANTHROPIC) ||
                                serviceType.equals(APIConfig.ServiceType.GOOGLE) ||
                                serviceType.equals(APIConfig.ServiceType.CUSTOM)
                            ));

        OnlineModelConfig existing = getModel(apiConfigId);
        if (!isAIConfig) {
            // 非 AI 类型，如果存在对应 OnlineModelConfig 则移除
            if (existing != null) {
                modelList.remove(existing);
                if (apiConfigId.equals(activeModelId)) {
                    activeModelId = null;
                    notifyActiveChanged();
                }
                saveToPrefs();
                notifyListChanged();
                return true;
            }
            return false;
        }

        // 检查必要字段
        if (apiConfig.getApiHost() == null || apiConfig.getApiHost().isEmpty() ||
            apiConfig.getApiKey() == null || apiConfig.getApiKey().isEmpty()) {
            return false;
        }

        if (existing == null) {
            // 新增
            OnlineModelConfig config = convertFromAPIConfig(apiConfig);
            if (config != null) {
                modelList.add(config);
                saveToPrefs();
                notifyListChanged();
                return true;
            }
            return false;
        } else {
            // 更新
            boolean changed = false;
            String newName = buildDisplayName(apiConfig);
            if (!equals(existing.name, newName)) {
                existing.name = newName;
                changed = true;
            }
            if (!equals(existing.apiUrl, apiConfig.getApiHost() != null ? apiConfig.getApiHost() : "")) {
                existing.apiUrl = apiConfig.getApiHost() != null ? apiConfig.getApiHost() : "";
                changed = true;
            }
            if (!equals(existing.apiKey, apiConfig.getApiKey() != null ? apiConfig.getApiKey() : "")) {
                existing.apiKey = apiConfig.getApiKey() != null ? apiConfig.getApiKey() : "";
                changed = true;
            }
            String newModelName = apiConfig.getModelName() != null ? apiConfig.getModelName() : "";
            if (!equals(existing.modelName, newModelName)) {
                existing.modelName = newModelName;
                if (newModelName != null && !newModelName.isEmpty()) {
                    existing.selectedModel = newModelName;
                }
                changed = true;
            }
            if (existing.enabled != apiConfig.isActive()) {
                existing.enabled = apiConfig.isActive();
                changed = true;
            }
            if (changed) {
                saveToPrefs();
                notifyListChanged();
                return true;
            }
            return false;
        }
    }

    /**
     * 根据 APIConfig ID 移除对应的 OnlineModelConfig
     * @param apiConfigId APIConfig ID
     * @return 是否发生变更
     */
    public boolean removeByAPIConfigId(String apiConfigId) {
        if (apiConfigId == null || apiConfigId.isEmpty()) {
            return false;
        }
        boolean removed = modelList.removeIf(c -> apiConfigId.equals(c.id));
        if (removed) {
            if (apiConfigId.equals(activeModelId)) {
                activeModelId = null;
                notifyActiveChanged();
            }
            saveToPrefs();
            notifyListChanged();
        }
        return removed;
    }

    private String buildDisplayName(APIConfig apiConfig) {
        // 优先使用用户在 API 配置中起的名称，这是用户识别配置的依据
        String userName = apiConfig.getName();
        if (userName != null && !userName.isEmpty()) {
            return userName;
        }
        // 回退：服务商名 + 模型名
        String serviceType = apiConfig.getServiceType();
        String serviceName = getServiceTypeChineseName(serviceType);
        String modelName = apiConfig.getModelName();
        if (modelName != null && !modelName.isEmpty()) {
            return serviceName + " · " + modelName;
        }
        return serviceName;
    }

    private boolean equals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
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
