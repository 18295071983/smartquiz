package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModelRegistry {
    private static final String TAG = "ModelRegistry";
    private static final String REGISTRY_FILE = "model_registry.json";

    private static volatile ModelRegistry INSTANCE;

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, ModelMetadata> modelRegistry = new ConcurrentHashMap<>();
    private final Map<String, ModelMetadata> downloadedModels = new ConcurrentHashMap<>();
    private final List<RegistryListener> listeners = new ArrayList<>();

    private ModelRegistry(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("model_registry_prefs", Context.MODE_PRIVATE);
        loadRegistry();
        scanDownloadedModels();
    }

    public static void initialize(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelRegistry.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelRegistry(context);
                    Log.i(TAG, "ModelRegistry initialized");
                }
            }
        }
    }

    public static ModelRegistry getInstance(Context context) {
        if (INSTANCE == null) {
            initialize(context);
        }
        return INSTANCE;
    }

    private void loadRegistry() {
        String json = prefs.getString(REGISTRY_FILE, null);
        if (json == null) return;

        try {
            JSONObject jsonObject = new JSONObject(json);
            JSONArray modelsArray = jsonObject.optJSONArray("models");
            if (modelsArray == null) return;

            for (int i = 0; i < modelsArray.length(); i++) {
                JSONObject modelJson = modelsArray.getJSONObject(i);
                ModelMetadata metadata = parseModelMetadata(modelJson);
                modelRegistry.put(metadata.modelId, metadata);

                if (metadata.isDownloaded) {
                    downloadedModels.put(metadata.modelId, metadata);
                }
            }

            Log.i(TAG, "Loaded " + modelRegistry.size() + " models from disk");
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse registry JSON", e);
        }
    }

    private void saveToDisk() {
        try {
            JSONObject jsonObject = new JSONObject();
            JSONArray modelsArray = new JSONArray();

            for (ModelMetadata metadata : modelRegistry.values()) {
                modelsArray.put(metadataToJson(metadata));
            }

            jsonObject.put("models", modelsArray);
            jsonObject.put("lastUpdated", System.currentTimeMillis());

            prefs.edit().putString(REGISTRY_FILE, jsonObject.toString()).apply();
            Log.d(TAG, "Saved " + modelRegistry.size() + " models to disk");
        } catch (Exception e) {
            Log.e(TAG, "Failed to save registry to disk", e);
        }
    }

    private ModelMetadata parseModelMetadata(JSONObject json) throws Exception {
        ModelParameters params = new ModelParameters(
            json.getJSONObject("parameters").getInt("vocab_size"),
            json.getJSONObject("parameters").getInt("hidden_size"),
            json.getJSONObject("parameters").getInt("num_layers"),
            json.getJSONObject("parameters").getInt("num_attention_heads"),
            json.getJSONObject("parameters").getInt("num_kv_heads"),
            json.getJSONObject("parameters").getInt("max_position_embeddings")
        );

        ModelCapabilities capabilities = new ModelCapabilities();
        JSONObject capJson = json.optJSONObject("capabilities");
        if (capJson != null) {
            capabilities.supportsStreaming = capJson.optBoolean("supports_streaming", true);
            capabilities.supportsFunctionCalling = capJson.optBoolean("supports_function_calling", false);
            capabilities.supportsCodeGeneration = capJson.optBoolean("supports_code_generation", false);
            capabilities.maxOutputTokens = capJson.optInt("max_output_tokens", 4096);
        }

        ModelRequirements requirements = new ModelRequirements(
            json.optLong("min_ram_mb", 4096),
            json.optLong("recommended_ram_mb", 6144),
            json.optLong("min_storage_mb", 3000),
            json.optInt("min_api_level", 24)
        );

        return new ModelMetadata(
            json.getString("model_id"),
            json.getString("model_name"),
            json.getString("model_filename"),
            json.getString("model_url"),
            json.getLong("model_size_mb"),
            json.getString("quantization"),
            json.getInt("context_size"),
            params,
            capabilities,
            requirements,
            json.getString("version"),
            json.getString("checksum"),
            ModelCategory.valueOf(json.optString("category", "GENERAL")),
            json.optBoolean("is_default", false),
            json.optBoolean("is_downloaded", false),
            json.has("download_date") ? json.getLong("download_date") : 0,
            json.has("last_used_date") ? json.getLong("last_used_date") : 0
        );
    }

    private JSONObject metadataToJson(ModelMetadata metadata) throws Exception {
        JSONObject json = new JSONObject();
        json.put("model_id", metadata.modelId);
        json.put("model_name", metadata.modelName);
        json.put("model_filename", metadata.modelFilename);
        json.put("model_url", metadata.modelUrl);
        json.put("model_size_mb", metadata.modelSizeMB);
        json.put("quantization", metadata.quantization);
        json.put("context_size", metadata.contextSize);

        JSONObject paramsJson = new JSONObject();
        paramsJson.put("vocab_size", metadata.parameters.vocabSize);
        paramsJson.put("hidden_size", metadata.parameters.hiddenSize);
        paramsJson.put("num_layers", metadata.parameters.numLayers);
        paramsJson.put("num_attention_heads", metadata.parameters.numAttentionHeads);
        paramsJson.put("num_kv_heads", metadata.parameters.numKeyValueHeads);
        paramsJson.put("max_position_embeddings", metadata.parameters.maxPositionEmbeddings);
        json.put("parameters", paramsJson);

        JSONObject capJson = new JSONObject();
        capJson.put("supports_streaming", metadata.capabilities.supportsStreaming);
        capJson.put("supports_function_calling", metadata.capabilities.supportsFunctionCalling);
        capJson.put("supports_code_generation", metadata.capabilities.supportsCodeGeneration);
        capJson.put("max_output_tokens", metadata.capabilities.maxOutputTokens);
        json.put("capabilities", capJson);

        json.put("version", metadata.version);
        json.put("checksum", metadata.checksum);
        json.put("category", metadata.category.name());
        json.put("is_default", metadata.isDefault);
        json.put("is_downloaded", metadata.isDownloaded);
        json.put("download_date", metadata.downloadDate);
        json.put("last_used_date", metadata.lastUsedDate);

        return json;
    }

    public void scanDownloadedModels() {
        File modelsDir = new File(context.getFilesDir(), "models");
        File externalModelsDir = context.getExternalFilesDir(null);
        if (externalModelsDir != null) {
            externalModelsDir = new File(externalModelsDir, "models");
        }

        List<File> dirs = new ArrayList<>();
        if (modelsDir.exists()) dirs.add(modelsDir);
        if (externalModelsDir != null && externalModelsDir.exists()) dirs.add(externalModelsDir);

        for (File dir : dirs) {
            File[] files = dir.listFiles((file, name) -> name.endsWith(".gguf"));
            if (files != null) {
                for (File file : files) {
                    for (ModelMetadata metadata : modelRegistry.values()) {
                        if (metadata.modelFilename.equals(file.getName())) {
                            ModelMetadata updated = new ModelMetadata(
                                metadata.modelId, metadata.modelName, metadata.modelFilename,
                                metadata.modelUrl, metadata.modelSizeMB, metadata.quantization,
                                metadata.contextSize, metadata.parameters, metadata.capabilities,
                                metadata.requirements, metadata.version, metadata.checksum,
                                metadata.category, metadata.isDefault, true, file.lastModified(),
                                metadata.lastUsedDate
                            );
                            modelRegistry.put(metadata.modelId, updated);
                            downloadedModels.put(metadata.modelId, updated);
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Scanned downloaded models: " + downloadedModels.size() + " found");
    }

    public void addModel(ModelMetadata metadata) {
        modelRegistry.put(metadata.modelId, metadata);
        if (metadata.isDownloaded) {
            downloadedModels.put(metadata.modelId, metadata);
        }
        for (RegistryListener listener : listeners) {
            listener.onModelAdded(metadata);
        }
        saveToDisk();
    }

    public void removeModel(String modelId) {
        ModelMetadata removed = modelRegistry.remove(modelId);
        if (removed != null) {
            downloadedModels.remove(modelId);
            for (RegistryListener listener : listeners) {
                listener.onModelRemoved(modelId);
            }
            saveToDisk();
        }
    }

    public void updateModel(ModelMetadata metadata) {
        modelRegistry.put(metadata.modelId, metadata);
        if (metadata.isDownloaded) {
            downloadedModels.put(metadata.modelId, metadata);
        } else {
            downloadedModels.remove(metadata.modelId);
        }
        for (RegistryListener listener : listeners) {
            listener.onModelUpdated(metadata);
        }
        saveToDisk();
    }

    public void markAsDownloaded(String modelId, long downloadDate) {
        ModelMetadata metadata = modelRegistry.get(modelId);
        if (metadata != null) {
            ModelMetadata updated = new ModelMetadata(
                metadata.modelId, metadata.modelName, metadata.modelFilename,
                metadata.modelUrl, metadata.modelSizeMB, metadata.quantization,
                metadata.contextSize, metadata.parameters, metadata.capabilities,
                metadata.requirements, metadata.version, metadata.checksum,
                metadata.category, metadata.isDefault, true, downloadDate,
                metadata.lastUsedDate
            );
            updateModel(updated);
        }
    }

    public void markAsUsed(String modelId) {
        ModelMetadata metadata = modelRegistry.get(modelId);
        if (metadata != null) {
            ModelMetadata updated = new ModelMetadata(
                metadata.modelId, metadata.modelName, metadata.modelFilename,
                metadata.modelUrl, metadata.modelSizeMB, metadata.quantization,
                metadata.contextSize, metadata.parameters, metadata.capabilities,
                metadata.requirements, metadata.version, metadata.checksum,
                metadata.category, metadata.isDefault, metadata.isDownloaded,
                metadata.downloadDate, System.currentTimeMillis()
            );
            updateModel(updated);
        }
    }

    public ModelMetadata getModel(String modelId) {
        return modelRegistry.get(modelId);
    }

    public Map<String, ModelMetadata> getAllModels() {
        return modelRegistry;
    }

    public Map<String, ModelMetadata> getDownloadedModels() {
        return downloadedModels;
    }

    public List<ModelMetadata> getModelsByCategory(ModelCategory category) {
        List<ModelMetadata> result = new ArrayList<>();
        for (ModelMetadata metadata : modelRegistry.values()) {
            if (metadata.category == category) {
                result.add(metadata);
            }
        }
        return result;
    }

    public ModelMetadata getDefaultModel() {
        for (ModelMetadata metadata : modelRegistry.values()) {
            if (metadata.isDefault) {
                return metadata;
            }
        }
        return null;
    }

    public List<ModelMetadata> searchModels(String query) {
        List<ModelMetadata> result = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (ModelMetadata metadata : modelRegistry.values()) {
            if (metadata.modelName.toLowerCase().contains(lowerQuery) ||
                metadata.modelId.toLowerCase().contains(lowerQuery) ||
                metadata.quantization.toLowerCase().contains(lowerQuery)) {
                result.add(metadata);
            }
        }
        return result;
    }

    public String getModelPath(ModelMetadata metadata) {
        File externalDir = context.getExternalFilesDir(null);
        File dir;
        if (externalDir != null && externalDir.getFreeSpace() > metadata.modelSizeMB * 1024 * 1024) {
            dir = new File(externalDir, "models");
        } else {
            dir = new File(context.getFilesDir(), "models");
        }
        return new File(dir, metadata.modelFilename).getAbsolutePath();
    }

    public void addListener(RegistryListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RegistryListener listener) {
        listeners.remove(listener);
    }

    public void clear() {
        modelRegistry.clear();
        downloadedModels.clear();
        prefs.edit().remove(REGISTRY_FILE).apply();
    }

    public int getModelCount() {
        return modelRegistry.size();
    }

    public int getDownloadedCount() {
        return downloadedModels.size();
    }

    public interface RegistryListener {
        void onModelAdded(ModelMetadata model);
        void onModelRemoved(String modelId);
        void onModelUpdated(ModelMetadata model);
        void onRegistryLoaded();
    }

    public static class ModelMetadata {
        public final String modelId;
        public final String modelName;
        public final String modelFilename;
        public final String modelUrl;
        public final long modelSizeMB;
        public final String quantization;
        public final int contextSize;
        public final ModelParameters parameters;
        public final ModelCapabilities capabilities;
        public final ModelRequirements requirements;
        public final String version;
        public final String checksum;
        public final ModelCategory category;
        public final boolean isDefault;
        public final boolean isDownloaded;
        public final long downloadDate;
        public final long lastUsedDate;

        public ModelMetadata(String modelId, String modelName, String modelFilename, String modelUrl,
                           long modelSizeMB, String quantization, int contextSize, ModelParameters parameters,
                           ModelCapabilities capabilities, ModelRequirements requirements, String version,
                           String checksum, ModelCategory category, boolean isDefault, boolean isDownloaded,
                           long downloadDate, long lastUsedDate) {
            this.modelId = modelId;
            this.modelName = modelName;
            this.modelFilename = modelFilename;
            this.modelUrl = modelUrl;
            this.modelSizeMB = modelSizeMB;
            this.quantization = quantization;
            this.contextSize = contextSize;
            this.parameters = parameters;
            this.capabilities = capabilities;
            this.requirements = requirements;
            this.version = version;
            this.checksum = checksum;
            this.category = category;
            this.isDefault = isDefault;
            this.isDownloaded = isDownloaded;
            this.downloadDate = downloadDate;
            this.lastUsedDate = lastUsedDate;
        }
    }

    public static class ModelParameters {
        public final int vocabSize;
        public final int hiddenSize;
        public final int numLayers;
        public final int numAttentionHeads;
        public final int numKeyValueHeads;
        public final int maxPositionEmbeddings;

        public ModelParameters(int vocabSize, int hiddenSize, int numLayers, int numAttentionHeads,
                            int numKeyValueHeads, int maxPositionEmbeddings) {
            this.vocabSize = vocabSize;
            this.hiddenSize = hiddenSize;
            this.numLayers = numLayers;
            this.numAttentionHeads = numAttentionHeads;
            this.numKeyValueHeads = numKeyValueHeads;
            this.maxPositionEmbeddings = maxPositionEmbeddings;
        }
    }

    public static class ModelCapabilities {
        public boolean supportsStreaming = true;
        public boolean supportsFunctionCalling = false;
        public boolean supportsCodeGeneration = false;
        public int maxOutputTokens = 4096;
    }

    public static class ModelRequirements {
        public final long minRamMB;
        public final long recommendedRamMB;
        public final long minStorageMB;
        public final int minApiLevel;

        public ModelRequirements(long minRamMB, long recommendedRamMB, long minStorageMB, int minApiLevel) {
            this.minRamMB = minRamMB;
            this.recommendedRamMB = recommendedRamMB;
            this.minStorageMB = minStorageMB;
            this.minApiLevel = minApiLevel;
        }
    }

    public enum ModelCategory {
        CHAT, CODE, GENERAL, SPECIALIZED
    }
}

class ModelPreferences {
    private static final String PREFS_NAME = "model_preferences";
    private static final String KEY_CURRENT_MODEL = "current_model";
    private static final String KEY_PREFERRED_CATEGORY = "preferred_category";
    private static final String KEY_AUTO_SELECT_MODEL = "auto_select_model";
    private static final String KEY_DOWNLOAD_WIFI_ONLY = "download_wifi_only";

    private final android.content.SharedPreferences prefs;

    public ModelPreferences(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getCurrentModelId() {
        return prefs.getString(KEY_CURRENT_MODEL, null);
    }

    public void setCurrentModelId(String modelId) {
        prefs.edit().putString(KEY_CURRENT_MODEL, modelId).apply();
    }

    public ModelRegistry.ModelCategory getPreferredCategory() {
        String name = prefs.getString(KEY_PREFERRED_CATEGORY, ModelRegistry.ModelCategory.CHAT.name());
        try {
            return ModelRegistry.ModelCategory.valueOf(name);
        } catch (Exception e) {
            return ModelRegistry.ModelCategory.CHAT;
        }
    }

    public void setPreferredCategory(ModelRegistry.ModelCategory category) {
        prefs.edit().putString(KEY_PREFERRED_CATEGORY, category.name()).apply();
    }

    public boolean isAutoSelectModel() {
        return prefs.getBoolean(KEY_AUTO_SELECT_MODEL, true);
    }

    public void setAutoSelectModel(boolean autoSelect) {
        prefs.edit().putBoolean(KEY_AUTO_SELECT_MODEL, autoSelect).apply();
    }

    public boolean isDownloadWifiOnly() {
        return prefs.getBoolean(KEY_DOWNLOAD_WIFI_ONLY, true);
    }

    public void setDownloadWifiOnly(boolean wifiOnly) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_WIFI_ONLY, wifiOnly).apply();
    }

    public int getModelPriority(String modelId) {
        return prefs.getInt("priority_" + modelId, 0);
    }

    public void setModelPriority(String modelId, int priority) {
        prefs.edit().putInt("priority_" + modelId, priority).apply();
    }
}