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
    /** NPU 原生格式（qairt 预编译 QNN context）清单：独立文件，由 GenieX ModelManagerWrapper 下载 */
    private static final String NPU_NATIVE_PRESETS_FILE = "models_presets_npu_native.json";

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

        // ===== GenieX 官方目录信息（NPU 原生/qairt 与多模态走 SDK 自身下载与加载）=====
        public String geniexModelName;    // 官方 catalog 的 modelName
        public String geniexRuntime;      // llama_cpp | qairt
        public String geniexType;         // llm | vlm
        public String geniexQuant;
        public String hub;                // HUGGINGFACE | MODELSCOPE | AUTO
        public String chipset;            // 例如 SM8750
        public Boolean applicable;        // 本机是否适用（null = 未标注）
        public String unapplicableReason;
        public Boolean supportsVision;
        public Boolean supportsThinking;
        public Boolean supportsFC;
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
                // 追加 NPU 原生格式（qairt）清单：失败不影响 GGUF 主清单
                try {
                    InputStream is2 = context.getAssets().open(NPU_NATIVE_PRESETS_FILE);
                    InputStreamReader reader2 = new InputStreamReader(is2);
                    PresetContainer c2 = new Gson().fromJson(reader2, PresetContainer.class);
                    reader2.close();
                    if (c2 != null && c2.presets != null) {
                        container.presets.addAll(c2.presets);
                        Log.i(TAG, "NPU 原生(qairt)清单已合并: " + c2.presets.size() + " 条");
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "NPU 原生清单加载失败（忽略）: " + e2.getMessage());
                }
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