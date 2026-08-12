package com.oilquiz.app.ui.dialog;

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
 * OCR 模型选择器底部弹窗
 *
 * 展示所有可用 API Key 下的具体模型（同一个 API Key 可能提供多个模型），
 * 用户可为 OCR 单独指定某个视觉模型，与聊天模型完全解耦。
 *
 * 列表结构：
 * 1. "自动选择（推荐）" - 不指定具体模型，按能力自动选择
 * 2. 每个启用的 API 端点下，列出其提供的具体模型（来自缓存或实时拉取）
 */
public class OCRModelSelectorDialog {

    private final Context context;
    private Dialog dialog;
    private final OnlineModelManager modelManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnModelSelectedListener listener;
    private String currentOcrModelId;   // 当前 OCR 使用的端点 ID（null = 自动选择）
    private String currentOcrModelName; // 当前 OCR 使用的具体模型名（null = 端点默认模型）

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
        final String endpointId;    // API 端点配置 ID（自动选择项为 null）
        final String endpointName;  // API 端点显示名
        final String modelName;     // 具体模型名（自动选择项为 null）
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

    public OCRModelSelectorDialog(Context context) {
        this.context = context;
        this.modelManager = OnlineModelManager.getInstance(context);
        this.currentOcrModelId = modelManager.getOCRModelId();
        this.currentOcrModelName = modelManager.getOCRModelName();
    }

    public OCRModelSelectorDialog setListener(OnModelSelectedListener listener) {
        this.listener = listener;
        return this;
    }

    public void show() {
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(
                com.oilquiz.app.R.layout.dialog_ocr_model_selector, null);
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
                for (String name : modelNames) {
                    items.add(new ModelItem(config.id, config.name, name, false));
                }
            } else {
                // 无缓存：显示加载占位项，并异步拉取
                items.add(new ModelItem(config.id, config.name, null, false));
                fetchModelsAsync(config);
            }
        }

        adapter = new ModelAdapter(items, currentOcrModelId, currentOcrModelName, this::onItemSelected);
        modelsRecycler.setAdapter(adapter);
    }

    /**
     * 异步拉取端点下的模型列表
     */
    private void fetchModelsAsync(OnlineModelConfig config) {
        try {
            ModelListFetcher.getInstance(context)
                    .fetchModels(config.apiUrl, config.apiKey)
                    .whenComplete((models, error) -> mainHandler.post(() -> {
                        if (dialog == null || !dialog.isShowing()) return;
                        if (error != null || models == null || models.isEmpty()) return;

                        // 保存缓存（JSON 数组格式，与聊天模型选择器兼容）
                        try {
                            JSONArray arr = new JSONArray();
                            for (ApiModel m : models) {
                                JSONObject obj = new JSONObject();
                                obj.put("id", m.id);
                                if (m.displayName != null) obj.put("name", m.displayName);
                                arr.put(obj);
                            }
                            modelManager.saveCachedModels(config.id, arr.toString());
                        } catch (Exception ignored) {}

                        // 将占位项替换为真实模型项
                        rebuildItemsWithFetchedModels(config, models);
                    }));
        } catch (Exception ignored) {}
    }

    /**
     * 拉取成功后重建列表项（将占位项替换为真实模型项）
     */
    private void rebuildItemsWithFetchedModels(OnlineModelConfig config, List<ApiModel> models) {
        List<ModelItem> newItems = new ArrayList<>();
        boolean inserted = false;
        for (ModelItem item : items) {
            if (!item.isAuto && config.id.equals(item.endpointId)) {
                // 该端点的占位项：原位替换为真实模型项
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
        if (item.isAuto) {
            // 自动选择：清除专用模型配置
            modelManager.setOCRModel(null);
            currentOcrModelId = null;
            currentOcrModelName = null;
            if (listener != null) {
                listener.onModelSelected(null, null);
            }
            Toast.makeText(context, "OCR 模型：自动选择", Toast.LENGTH_SHORT).show();
        } else {
            // 保存端点 + 具体模型名
            modelManager.setOCRModel(item.endpointId, item.modelName);
            currentOcrModelId = item.endpointId;
            currentOcrModelName = item.modelName;
            if (listener != null) {
                listener.onModelSelected(item.endpointId, item.modelName);
            }
            Toast.makeText(context, "OCR 模型：" + item.modelName, Toast.LENGTH_SHORT).show();
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
            // 旧格式：逗号分隔的 ID 字符串
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
        private String selectedEndpointId; // null = 自动选择
        private String selectedModelName;  // null = 端点默认模型
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
                    .inflate(com.oilquiz.app.R.layout.item_ocr_model, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ModelItem item = items.get(position);

            boolean isSelected;
            if (item.isAuto) {
                holder.tvModelName.setText("自动选择（推荐）");
                holder.tvModelDetail.setText("按视觉能力自动选择最合适的模型");
                holder.chipVision.setVisibility(View.GONE);
                isSelected = selectedEndpointId == null;
            } else if (item.modelName == null) {
                // 加载占位项
                holder.tvModelName.setText(item.endpointName);
                holder.tvModelDetail.setText("正在获取模型列表...");
                holder.chipVision.setVisibility(View.GONE);
                isSelected = false;
                holder.itemView.setOnClickListener(null);
                return;
            } else {
                // 具体模型项
                holder.tvModelName.setText(item.modelName);
                holder.tvModelDetail.setText("来自：" + item.endpointName);
                boolean hasVision = isVisionModelByName(item.modelName);
                holder.chipVision.setVisibility(hasVision ? View.VISIBLE : View.GONE);
                // 选中条件：端点相同 且 具体模型名相同
                isSelected = item.endpointId != null && item.endpointId.equals(selectedEndpointId)
                        && item.modelName != null && item.modelName.equals(selectedModelName);
            }

            // 选中状态
            holder.rbSelected.setChecked(isSelected);
            holder.cardModel.setStrokeColor(isSelected
                    ? holder.itemView.getContext().getResources().getColor(
                            com.oilquiz.app.R.color.primary)
                    : Color.TRANSPARENT);

            // 点击事件
            holder.itemView.setOnClickListener(v -> clickListener.onItemClick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        /**
         * 按模型名启发式判断是否为视觉模型
         */
        private boolean isVisionModelByName(String modelName) {
            if (modelName == null) return false;
            String ml = modelName.toLowerCase();
            return ml.contains("vision") || ml.contains("-vl") || ml.contains("gpt-4o")
                    || ml.contains("gemini") || ml.contains("claude-3")
                    || ml.contains("qwen-vl") || ml.contains("qwen2-vl") || ml.contains("qwen2.5-vl")
                    || ml.contains("qwen3-vl") || ml.contains("qvq") || ml.contains("internvl")
                    || ml.contains("llava") || ml.contains("moondream") || ml.contains("glm-4v");
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView cardModel;
            RadioButton rbSelected;
            TextView tvModelName;
            TextView tvModelDetail;
            Chip chipVision;

            ViewHolder(View itemView) {
                super(itemView);
                cardModel = itemView.findViewById(com.oilquiz.app.R.id.card_model);
                rbSelected = itemView.findViewById(com.oilquiz.app.R.id.rb_selected);
                tvModelName = itemView.findViewById(com.oilquiz.app.R.id.tv_model_name);
                tvModelDetail = itemView.findViewById(com.oilquiz.app.R.id.tv_model_detail);
                chipVision = itemView.findViewById(com.oilquiz.app.R.id.chip_vision);
            }
        }
    }
}
