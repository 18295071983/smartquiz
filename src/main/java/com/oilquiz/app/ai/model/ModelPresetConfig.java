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