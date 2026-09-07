package com.oilquiz.app.ui.dialog;

import com.oilquiz.app.R;

import com.oilquiz.app.theme.ThemeColors;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.oilquiz.app.ai.model.ApiModel;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.service.ModelListFetcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音模型选择器底部弹窗（通用：语音识别 ASR / 语音合成 TTS）
 *
 * 与 OCRModelSelectorDialog 交互一致：列出所有启用 API 端点下的具体模型，
 * 用户为语音识别或语音合成单独指定模型后，对应语音功能即可直接使用。
 */
public class SpeechModelSelectorDialog {

    /** 语音功能模式 */
    public enum Mode {
        ASR("选择语音识别模型", "为语音识别（语音转文字）选择专用模型，不选择则自动使用支持音频的模型"),
        TTS("选择语音合成模型", "为语音合成（文字转语音）选择专用模型，不选择则自动使用支持音频的模型");

        final String title;
        final String subtitle;

        Mode(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private final Context context;
    private final Mode mode;
    private Dialog dialog;
    private final OnlineModelManager modelManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnModelSelectedListener listener;
    private String currentModelId;   // 当前使用的端点 ID（null = 自动选择）
    private String currentModelName; // 当前使用的具体模型名（null = 端点默认模型）

    // UI
    private RecyclerView modelsRecycler;
    private View emptyState;
    private ModelAdapter adapter;
    private final List<ModelItem> items = new ArrayList<>();

    public interface OnModelSelectedListener {
        void onModelSelected(String modelId, String modelName);
    }

    /**
     * 列表项：端点 + 具体模型名（modelName 为 null 表示"自动选择"项）
     */
    private static class ModelItem {
        final String endpointId;
        final String endpointName;
        final String modelName;
        final boolean isAuto;

        ModelItem(String endpointId, String endpointName, String modelName, boolean isAuto) {
            this.endpointId = endpointId;
            this.endpointName = endpointName;
            this.modelName = modelName;
            this.isAuto = isAuto;
        }

        static ModelItem auto() {
            return new ModelItem(null, null, null, true);
        }
    }

    public SpeechModelSelectorDialog(Context context, Mode mode) {
        this.context = context;
        this.mode = mode;
        this.modelManager = OnlineModelManager.getInstance(context);
        String feature = featureKey();
        this.currentModelId = modelManager.getFeatureModelId(feature);
        this.currentModelName = modelManager.getFeatureModelName(feature);
    }

    public SpeechModelSelectorDialog setListener(OnModelSelectedListener listener) {
        this.listener = listener;
        return this;
    }

    private String featureKey() {
        return mode == Mode.ASR ? OnlineModelManager.FEATURE_ASR : OnlineModelManager.FEATURE_TTS;
    }

    public void show() {
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(
                com.oilquiz.app.R.layout.dialog_speech_model_selector, null);
        dialog.setContentView(view);

        // 底部弹窗样式
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        initViews(view);
        loadModels();

        dialog.show();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void initViews(View view) {
        modelsRecycler = view.findViewById(com.oilquiz.app.R.id.models_recycler);
        emptyState = view.findViewById(com.oilquiz.app.R.id.empty_state);

        TextView tvTitle = view.findViewById(com.oilquiz.app.R.id.tv_dialog_title);
        TextView tvSubtitle = view.findViewById(com.oilquiz.app.R.id.tv_dialog_subtitle);
        if (tvTitle != null) tvTitle.setText(mode.title);
        if (tvSubtitle != null) tvSubtitle.setText(mode.subtitle);

        View btnClose = view.findViewById(com.oilquiz.app.R.id.btn_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss());
        }

        modelsRecycler.setLayoutManager(new LinearLayoutManager(context));
    }

    private void loadModels() {
        List<OnlineModelConfig> allModels = modelManager.getModelList();
        List<OnlineModelConfig> enabledModels = new ArrayList<>();

        if (allModels != null) {
            for (OnlineModelConfig config : allModels) {
                if (config.enabled) {
                    enabledModels.add(config);
                }
            }
        }

        if (enabledModels.isEmpty()) {
            modelsRecycler.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
            return;
        }

        modelsRecycler.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);

        // 构建列表：自动选择项 + 各端点下的具体模型
        items.clear();
        items.add(ModelItem.auto());

        for (OnlineModelConfig config : enabledModels) {
            List<String> modelNames = parseCachedModels(config.cachedModelsJson);
            if (modelNames != null && !modelNames.isEmpty()) {
                // 按能力类型过滤缓存的模型
                modelNames = filterModelNames(modelNames, mode);
                if (!modelNames.isEmpty()) {
                    for (String name : modelNames) {
                        items.add(new ModelItem(config.id, config.name, name, false));
                    }
                }
            } else {
                // 无缓存：显示加载占位项，并异步拉取
                items.add(new ModelItem(config.id, config.name, null, false));
                fetchModelsAsync(config);
            }
        }

        adapter = new ModelAdapter(items, currentModelId, currentModelName, this::onItemSelected);
        modelsRecycler.setAdapter(adapter);
    }

    /**
     * 异步拉取端点下的模型列表（按能力过滤）
     */
    private void fetchModelsAsync(OnlineModelConfig config) {
        try {
            // 获取要过滤的能力类型
            String capability = null;
            switch (mode) {
                case TTS: capability = "TTS"; break;
                case ASR: capability = "ASR"; break;
            }
            ModelListFetcher.getInstance(context)
                    .fetchModels(config.apiUrl, config.apiKey, capability)
                    .whenComplete((models, error) -> mainHandler.post(() -> {
                        if (dialog == null || !dialog.isShowing()) return;
                        if (error != null || models == null || models.isEmpty()) return;

                        // 按能力类型过滤模型
                        List<ApiModel> filteredModels = filterModelsByCapability(models, mode);
                        if (filteredModels.isEmpty()) return;

                        // 保存缓存（JSON 数组格式）
                        try {
                            JSONArray arr = new JSONArray();
                            for (ApiModel m : filteredModels) {
                                JSONObject obj = new JSONObject();
                                obj.put("id", m.id);
                                if (m.displayName != null) obj.put("name", m.displayName);
                                arr.put(obj);
                            }
                            modelManager.saveCachedModels(config.id, arr.toString());
                        } catch (Exception ignored) {}

                        rebuildItemsWithFetchedModels(config, filteredModels);
                    }));
        } catch (Exception ignored) {}
    }

    /**
     * 按能力类型过滤模型名（字符串版本）
     */
    private static List<String> filterModelNames(List<String> modelNames, Mode mode) {
        List<String> filtered = new ArrayList<>();
        String[] keywordPattern = null;

        if (mode == Mode.TTS) {
            // TTS 模型白名单关键词
            keywordPattern = new String[]{
                "tts", "cosyvoice", "speech-02", "speech-01", "speech-2.5",
                "qwen-tts", "qwen3-tts", "qwen3.5-tts", "qwen-audio-tts",
                "x4_", "minimax-tts"
            };
        } else {
            // ASR 模型白名单关键词
            keywordPattern = new String[]{
                "asr", "paraformer", "sensevoice", "gummy", "whisper",
                "16k_zh", "volcengine_streaming"
            };
        }

        for (String name : modelNames) {
            String nl = name.toLowerCase();
            for (String keyword : keywordPattern) {
                if (nl.contains(keyword)) {
                    filtered.add(name);
                    break;
                }
            }
        }

        return filtered;
    }

    /**
     * 按能力类型过滤模型
     */
    private static List<ApiModel> filterModelsByCapability(List<ApiModel> models, Mode mode) {
        List<ApiModel> filtered = new ArrayList<>();
        String[] keywordPattern = null;

        if (mode == Mode.TTS) {
            // TTS 模型白名单关键词
            keywordPattern = new String[]{
                "tts", "cosyvoice", "speech-02", "speech-01", "speech-2.5",
                "qwen-tts", "qwen3-tts", "qwen3.5-tts", "qwen-audio-tts",
                "x4_", "minimax-tts"
            };
        } else {
            // ASR 模型白名单关键词
            keywordPattern = new String[]{
                "asr", "paraformer", "sensevoice", "gummy", "whisper",
                "16k_zh", "volcengine_streaming"
            };
        }

        for (ApiModel model : models) {
            String id = model.id.toLowerCase();
            for (String keyword : keywordPattern) {
                if (id.contains(keyword)) {
                    filtered.add(model);
                    break;
                }
            }
        }

        return filtered;
    }

    /**
     * 拉取成功后重建列表项（将占位项替换为真实模型项）
     */
    private void rebuildItemsWithFetchedModels(OnlineModelConfig config, List<ApiModel> models) {
        List<ModelItem> newItems = new ArrayList<>();
        boolean inserted = false;
        for (ModelItem item : items) {
            if (!item.isAuto && config.id.equals(item.endpointId)) {
                if (!inserted) {
                    for (ApiModel m : models) {
                        newItems.add(new ModelItem(config.id, config.name, m.id, false));
                    }
                    inserted = true;
                }
            } else {
                newItems.add(item);
            }
        }
        if (!inserted) {
            for (ApiModel m : models) {
                newItems.add(new ModelItem(config.id, config.name, m.id, false));
            }
        }

        items.clear();
        items.addAll(newItems);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 选中某个模型
     */
    private void onItemSelected(ModelItem item) {
        String feature = featureKey();
        String featureLabel = mode == Mode.ASR ? "语音识别模型" : "语音合成模型";

        if (item.isAuto) {
            modelManager.setFeatureModel(feature, null);
            currentModelId = null;
            currentModelName = null;
            if (listener != null) {
                listener.onModelSelected(null, null);
            }
            Toast.makeText(context, featureLabel + "：自动选择", Toast.LENGTH_SHORT).show();
        } else {
            modelManager.setFeatureModel(feature, item.endpointId, item.modelName);
            currentModelId = item.endpointId;
            currentModelName = item.modelName;
            if (listener != null) {
                listener.onModelSelected(item.endpointId, item.modelName);
            }
            Toast.makeText(context, featureLabel + "：" + item.modelName, Toast.LENGTH_SHORT).show();
        }
        dismiss();
    }

    /**
     * 解析缓存的模型列表 JSON（兼容旧格式）
     */
    private List<String> parseCachedModels(String cachedModelsJson) {
        if (cachedModelsJson == null || cachedModelsJson.isEmpty()) {
            return null;
        }
        String trimmed = cachedModelsJson.trim();
        if (!trimmed.startsWith("[")) {
            List<String> legacyList = new ArrayList<>();
            for (String id : trimmed.split(",")) {
                String t = id.trim();
                if (!t.isEmpty()) legacyList.add(t);
            }
            return legacyList.isEmpty() ? null : legacyList;
        }
        try {
            JSONArray arr = new JSONArray(trimmed);
            List<String> modelList = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.has("id")) {
                    modelList.add(obj.getString("id"));
                } else if (obj.has("name")) {
                    modelList.add(obj.getString("name"));
                }
            }
            return modelList;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * RecyclerView 适配器
     */
    private static class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.ViewHolder> {

        private final List<ModelItem> items;
        private String selectedEndpointId;
        private String selectedModelName;
        private final ItemClickListener clickListener;

        interface ItemClickListener {
            void onItemClick(ModelItem item);
        }

        ModelAdapter(List<ModelItem> items, String selectedEndpointId, String selectedModelName,
                     ItemClickListener clickListener) {
            this.items = items;
            this.selectedEndpointId = selectedEndpointId;
            this.selectedModelName = selectedModelName;
            this.clickListener = clickListener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(com.oilquiz.app.R.layout.item_speech_model, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ModelItem item = items.get(position);

            boolean isSelected;
            if (item.isAuto) {
                holder.tvModelName.setText("自动选择（推荐）");
                holder.tvModelDetail.setText("按音频能力自动选择最合适的模型");
                holder.chipAudio.setVisibility(View.GONE);
                isSelected = selectedEndpointId == null;
            } else if (item.modelName == null) {
                // 加载占位项
                holder.tvModelName.setText(item.endpointName);
                holder.tvModelDetail.setText("正在获取模型列表...");
                holder.chipAudio.setVisibility(View.GONE);
                isSelected = false;
                holder.itemView.setOnClickListener(null);
                return;
            } else {
                holder.tvModelName.setText(item.modelName);
                holder.tvModelDetail.setText("来自：" + item.endpointName);
                boolean hasAudio = isAudioModelByName(item.modelName);
                holder.chipAudio.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
                isSelected = item.endpointId != null && item.endpointId.equals(selectedEndpointId)
                        && item.modelName != null && item.modelName.equals(selectedModelName);
            }

            holder.rbSelected.setChecked(isSelected);
            holder.cardModel.setStrokeColor(isSelected
                    ? ThemeColors.get(holder.itemView.getContext(), R.color.primary)
                    : Color.TRANSPARENT);

            holder.itemView.setOnClickListener(v -> clickListener.onItemClick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        /**
         * 按模型名启发式判断是否为音频模型
         */
        private boolean isAudioModelByName(String modelName) {
            if (modelName == null) return false;
            String ml = modelName.toLowerCase();
            return ml.contains("whisper") || ml.contains("tts") || ml.contains("paraformer")
                    || ml.contains("sensevoice") || ml.contains("cosyvoice") || ml.contains("sambert")
                    || ml.contains("asr") || ml.contains("audio") || ml.contains("speech")
                    || ml.contains("voice");
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView cardModel;
            RadioButton rbSelected;
            TextView tvModelName;
            TextView tvModelDetail;
            Chip chipAudio;

            ViewHolder(View itemView) {
                super(itemView);
                cardModel = itemView.findViewById(com.oilquiz.app.R.id.card_model);
                rbSelected = itemView.findViewById(com.oilquiz.app.R.id.rb_selected);
                tvModelName = itemView.findViewById(com.oilquiz.app.R.id.tv_model_name);
                tvModelDetail = itemView.findViewById(com.oilquiz.app.R.id.tv_model_detail);
                chipAudio = itemView.findViewById(com.oilquiz.app.R.id.chip_audio);
            }
        }
    }
}
