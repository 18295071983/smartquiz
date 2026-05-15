package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;
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
}
