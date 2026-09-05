package com.oilquiz.app.ai.model;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ModelPresetConfig {
    private static final String TAG = "ModelPresetConfig";
    private static final String PRESETS_FILE = "models_presets.json";

    public static class ModelPreset {
        public String id;
        public String name;
        public long sizeBytes;
        public int contextLength;
        public int minRamMB;
        public String description;
        public String quantization;
        public String architecture;
        public int vocabSize;
        public String parameters;
        public String memory;
        public String tps;
        public float performanceScore;
        public float qualityScore;
        public String useCases;

        // 下载字段（models_presets.json 为模型唯一权威来源，含下载所需全部信息）
        public String downloadUrl;
        public int recommendedGpuLayers;
        public String mmprojUrl;
        public long mmprojSizeMB;
        public String sha256;
        public String mmprojSha256;
        public String backupUrl;
        public String backupMmprojUrl;
        public String backupSha256;
        public String backupMmprojSha256;
    }

    public static class PresetContainer {
        public List<ModelPreset> presets;
    }

    public static List<ModelPreset> loadPresets(Context context) {
        try {
            InputStream is = context.getAssets().open(PRESETS_FILE);
            InputStreamReader reader = new InputStreamReader(is);
            PresetContainer container = new Gson().fromJson(reader, PresetContainer.class);
            reader.close();
            if (container != null && container.presets != null) {
                return container.presets;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load model presets", e);
        }
        return new ArrayList<>();
    }

    public static ModelPreset findPreset(List<ModelPreset> presets, String modelId) {
        for (ModelPreset p : presets) {
            if (p.id.equals(modelId)) return p;
        }
        return null;
    }

    public static ModelInfo toModelInfo(ModelPreset preset) {
        ModelInfo info = new ModelInfo();
        info.id = preset.id;
        info.name = preset.name;
        info.description = preset.description;
        info.sizeMB = preset.sizeBytes / 1000000;
        info.contextLength = preset.contextLength;
        info.quantization = preset.quantization;
        info.minRamMB = preset.minRamMB;
        return info;
    }

    /**
     * 转换为下载管理器使用的 ModelPresetInfo（含下载 URL / 哈希 / 备用源等字段）。
     * models_presets.json 是模型唯一权威来源，下载预设也统一从这里转换。
     */
    public static ModelDownloadManager.ModelPresetInfo toPresetInfo(ModelPreset preset) {
        if (preset == null) return null;
        return new ModelDownloadManager.ModelPresetInfo(
                preset.id,
                preset.name,
                preset.description != null ? preset.description : "",
                preset.downloadUrl != null ? preset.downloadUrl : "",
                preset.sizeBytes / 1000000L,
                preset.quantization != null ? preset.quantization : "",
                preset.contextLength,
                preset.minRamMB,
                preset.recommendedGpuLayers,
                preset.mmprojUrl,
                preset.mmprojSizeMB,
                preset.sha256,
                preset.mmprojSha256,
                preset.backupUrl,
                preset.backupMmprojUrl,
                preset.backupSha256,
                preset.backupMmprojSha256);
    }

    public static Model toDisplayModel(ModelPreset preset) {
        return new Model(
            preset.id,
            preset.name,
            preset.description != null ? preset.description : "",
            preset.architecture != null ? preset.architecture : "",
            preset.contextLength,
            preset.vocabSize,
            preset.parameters != null ? preset.parameters : "",
            preset.quantization != null ? preset.quantization : "",
            preset.memory != null ? preset.memory : "",
            preset.tps != null ? preset.tps : "",
            preset.performanceScore,
            preset.qualityScore,
            preset.useCases != null ? preset.useCases : "",
            false
        );
    }
}