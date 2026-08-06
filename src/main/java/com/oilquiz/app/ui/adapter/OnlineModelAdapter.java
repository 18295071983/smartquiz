package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 在线模型适配器 - 增强版
 * 支持多种视图类型、DiffUtil 部分更新、更丰富的 UI
 */
public class OnlineModelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_MODEL = 1;
    private static final int VIEW_TYPE_EMPTY = 2;
    private static final int VIEW_TYPE_ADD_BUTTON = 3;

    private Context context;
    private List<DisplayItem> displayItems = new ArrayList<>();
    private OnOnlineModelClickListener listener;
    private String currentModelName;

    public interface OnOnlineModelClickListener {
        void onModelClick(String modelName);
        void onDeleteClick(String modelName);
        void onEnableToggle(String modelName, boolean enabled);
        void onAddClick();
        void onFetchModelsClick(String modelName);
        void onModelSelected(String modelName, String selectedModel);
        /** 编辑已有在线模型配置 */
        default void onEditClick(String modelName) {}
    }

    private static class DisplayItem {
        int type;
        String modelName;
        String headerTitle;
        String modelType;
        String apiUrl;
        int status; // 0=unknown, 1=online, 2=offline, 3=error
        long latencyMs;
        long lastUsedTime;
        double costEstimate;
        boolean isEnabled;
        boolean isCurrent;
        List<String> modelList;
        String selectedModel;
        String cachedModelsJson;

        static DisplayItem header(String title) {
            DisplayItem item = new DisplayItem();
            item.type = VIEW_TYPE_HEADER;
            item.headerTitle = title;
            return item;
        }

        static DisplayItem model(String modelName, String modelType, String apiUrl,
                                 int status, long latencyMs, long lastUsedTime,
                                 double costEstimate, boolean isEnabled, boolean isCurrent,
                                 List<String> modelList, String selectedModel, String cachedModelsJson) {
            DisplayItem item = new DisplayItem();
            item.type = VIEW_TYPE_MODEL;
            item.modelName = modelName;
            item.modelType = modelType;
            item.apiUrl = apiUrl;
            item.status = status;
            item.latencyMs = latencyMs;
            item.lastUsedTime = lastUsedTime;
            item.costEstimate = costEstimate;
            item.isEnabled = isEnabled;
            item.isCurrent = isCurrent;
            item.modelList = modelList;
            item.selectedModel = selectedModel;
            item.cachedModelsJson = cachedModelsJson;
            return item;
        }

        static DisplayItem empty() {
            DisplayItem item = new DisplayItem();
            item.type = VIEW_TYPE_EMPTY;
            return item;
        }

        static DisplayItem addButton() {
            DisplayItem item = new DisplayItem();
            item.type = VIEW_TYPE_ADD_BUTTON;
            return item;
        }
    }

    public OnlineModelAdapter(Context context, OnOnlineModelClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    /**
     * 兼容旧代码的构造函数
     */
    public OnlineModelAdapter(Context context, List<OnlineModel> models, String currentModelName, OnOnlineModelClickListener listener) {
        this.context = context;
        this.listener = listener;
        this.currentModelName = currentModelName;
        if (models != null) {
            updateData(models);
        }
    }

    public void setCurrentModel(String currentModelName) {
        this.currentModelName = currentModelName;
    }

    /**
     * 更新数据 - List<OnlineModel> 版本
     */
    public void updateData(List<OnlineModel> models) {
        // 确保在主线程执行 DiffUtil.calculateDiff 和 dispatchUpdatesTo
        if (Looper.myLooper() != Looper.getMainLooper()) {
            android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
            handler.post(() -> updateData(models));
            return;
        }

        List<DisplayItem> oldItems = new ArrayList<>(this.displayItems);
        List<DisplayItem> newItems = buildDisplayItemsFromOnlineModel(models);

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldItems.size();
            }

            @Override
            public int getNewListSize() {
                return newItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                DisplayItem oldItem = oldItems.get(oldItemPosition);
                DisplayItem newItem = newItems.get(newItemPosition);
                if (oldItem.type != newItem.type) {
                    return false;
                }
                if (oldItem.type == VIEW_TYPE_MODEL) {
                    return oldItem.modelName != null && oldItem.modelName.equals(newItem.modelName);
                }
                if (oldItem.type == VIEW_TYPE_HEADER) {
                    return oldItem.headerTitle != null && oldItem.headerTitle.equals(newItem.headerTitle);
                }
                return oldItem.type == VIEW_TYPE_EMPTY || oldItem.type == VIEW_TYPE_ADD_BUTTON;
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                DisplayItem oldItem = oldItems.get(oldItemPosition);
                DisplayItem newItem = newItems.get(newItemPosition);
                if (oldItem.type == VIEW_TYPE_MODEL) {
                    return oldItem.status == newItem.status
                            && oldItem.latencyMs == newItem.latencyMs
                            && oldItem.isEnabled == newItem.isEnabled
                            && oldItem.isCurrent == newItem.isCurrent
                            && oldItem.lastUsedTime == newItem.lastUsedTime;
                }
                return true;
            }
        }, false);

        this.displayItems = newItems;
        diffResult.dispatchUpdatesTo(this);
    }

    /**
     * 更新数据 - OnlineModelConfig 版本（兼容旧代码）
     */
    public void updateData(List<OnlineModelManager.OnlineModelConfig> configs, String activeModelId) {
        // 确保在主线程执行 DiffUtil.calculateDiff 和 dispatchUpdatesTo
        if (Looper.myLooper() != Looper.getMainLooper()) {
            android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
            handler.post(() -> updateData(configs, activeModelId));
            return;
        }

        List<DisplayItem> oldItems = new ArrayList<>(this.displayItems);
        List<DisplayItem> newItems = buildDisplayItemsFromConfig(configs, activeModelId);

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldItems.size();
            }

            @Override
            public int getNewListSize() {
                return newItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                DisplayItem oldItem = oldItems.get(oldItemPosition);
                DisplayItem newItem = newItems.get(newItemPosition);
                if (oldItem.type != newItem.type) {
                    return false;
                }
                if (oldItem.type == VIEW_TYPE_MODEL) {
                    return oldItem.modelName != null && oldItem.modelName.equals(newItem.modelName);
                }
                if (oldItem.type == VIEW_TYPE_HEADER) {
                    return oldItem.headerTitle != null && oldItem.headerTitle.equals(newItem.headerTitle);
                }
                return oldItem.type == VIEW_TYPE_EMPTY || oldItem.type == VIEW_TYPE_ADD_BUTTON;
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                DisplayItem oldItem = oldItems.get(oldItemPosition);
                DisplayItem newItem = newItems.get(newItemPosition);
                if (oldItem.type == VIEW_TYPE_MODEL) {
                    return oldItem.status == newItem.status
                            && oldItem.isEnabled == newItem.isEnabled
                            && oldItem.isCurrent == newItem.isCurrent;
                }
                return true;
            }
        }, false);

        this.displayItems = newItems;
        diffResult.dispatchUpdatesTo(this);
    }

    private List<DisplayItem> buildDisplayItemsFromOnlineModel(List<OnlineModel> models) {
        List<DisplayItem> items = new ArrayList<>();

        if (models == null || models.isEmpty()) {
            items.add(DisplayItem.empty());
        } else {
            for (OnlineModel model : models) {
                items.add(DisplayItem.model(
                        model.name,
                        model.modelType,
                        model.apiUrl,
                        model.status,
                        model.latencyMs,
                        model.lastUsedTime,
                        model.costEstimate,
                        model.isEnabled,
                        model.name.equals(currentModelName),
                        null,
                        null,
                        null
                ));
            }
        }

        items.add(DisplayItem.addButton());
        return items;
    }

    /**
     * 从 OnlineModelConfig 构建显示项
     */
    private List<DisplayItem> buildDisplayItemsFromConfig(List<OnlineModelManager.OnlineModelConfig> configs, String activeModelId) {
        List<DisplayItem> items = new ArrayList<>();

        if (configs == null || configs.isEmpty()) {
            items.add(DisplayItem.empty());
        } else {
            for (OnlineModelManager.OnlineModelConfig config : configs) {
                boolean isCurrent = activeModelId != null && activeModelId.equals(config.id);
                List<String> modelList = parseCachedModels(config.cachedModelsJson);
                String selectedModel = config.selectedModel != null ? config.selectedModel : config.modelName;
                items.add(DisplayItem.model(
                        config.name,
                        selectedModel,
                        config.apiUrl,
                        0, // status unknown initially
                        0, // latency unknown initially
                        0, // lastUsedTime unknown initially
                        0, // costEstimate unknown initially
                        config.enabled,
                        isCurrent,
                        modelList,
                        selectedModel,
                        config.cachedModelsJson
                ));
            }
        }

        items.add(DisplayItem.addButton());
        return items;
    }

    private List<String> parseCachedModels(String cachedModelsJson) {
        if (cachedModelsJson == null || cachedModelsJson.isEmpty()) {
            return null;
        }
        // 兼容旧格式（逗号分隔的 ID 字符串）
        String trimmed = cachedModelsJson.trim();
        if (!trimmed.startsWith("[")) {
            List<String> legacyList = new ArrayList<>();
            String[] ids = trimmed.split(",");
            for (String id : ids) {
                String t = id.trim();
                if (!t.isEmpty()) {
                    legacyList.add(t);
                }
            }
            return legacyList.isEmpty() ? null : legacyList;
        }
        try {
            JSONArray arr = new JSONArray(trimmed);
            List<String> modelList = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                // 优先使用 id（API 调用需要模型 ID 而非显示名）
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

    @Override
    public int getItemViewType(int position) {
        return displayItems.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {
            case VIEW_TYPE_HEADER:
                View headerView = inflater.inflate(R.layout.item_model_section_header, parent, false);
                return new HeaderViewHolder(headerView);
            case VIEW_TYPE_MODEL:
                View modelView = inflater.inflate(R.layout.item_online_model, parent, false);
                return new OnlineModelViewHolder(modelView);
            case VIEW_TYPE_EMPTY:
                View emptyView = inflater.inflate(R.layout.item_empty_model, parent, false);
                return new EmptyViewHolder(emptyView);
            case VIEW_TYPE_ADD_BUTTON:
                View addView = inflater.inflate(R.layout.item_add_model, parent, false);
                return new AddButtonViewHolder(addView);
            default:
                View defaultView = inflater.inflate(R.layout.item_empty_model, parent, false);
                return new EmptyViewHolder(defaultView);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem item = displayItems.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(item.headerTitle);
        } else if (holder instanceof OnlineModelViewHolder) {
            ((OnlineModelViewHolder) holder).bind(item);
        } else if (holder instanceof AddButtonViewHolder) {
            ((AddButtonViewHolder) holder).bind();
        }
    }

    @Override
    public int getItemCount() {
        return displayItems.size();
    }

    public void updateModelStatus(String modelName, int status, long latencyMs) {
        for (int i = 0; i < displayItems.size(); i++) {
            DisplayItem item = displayItems.get(i);
            if (item.type == VIEW_TYPE_MODEL && modelName.equals(item.modelName)) {
                item.status = status;
                item.latencyMs = latencyMs;
                notifyItemChanged(i, "STATUS_UPDATE");
                break;
            }
        }
    }

    public void updateModelEnabled(String modelName, boolean enabled) {
        for (int i = 0; i < displayItems.size(); i++) {
            DisplayItem item = displayItems.get(i);
            if (item.type == VIEW_TYPE_MODEL && modelName.equals(item.modelName)) {
                item.isEnabled = enabled;
                notifyItemChanged(i, "ENABLE_UPDATE");
                break;
            }
        }
    }

    class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView headerTextView;

        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            headerTextView = itemView.findViewById(R.id.section_header_title);
        }

        public void bind(String title) {
            headerTextView.setText(title);
        }
    }

    class EmptyViewHolder extends RecyclerView.ViewHolder {
        TextView emptyTextView;
        ImageView emptyIcon;

        public EmptyViewHolder(@NonNull View itemView) {
            super(itemView);
            emptyTextView = itemView.findViewById(R.id.empty_text);
            emptyIcon = itemView.findViewById(R.id.empty_icon);
        }
    }

    class AddButtonViewHolder extends RecyclerView.ViewHolder {
        MaterialButton addButton;

        public AddButtonViewHolder(@NonNull View itemView) {
            super(itemView);
            addButton = itemView.findViewById(R.id.add_model_button);
            addButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAddClick();
                }
            });
        }

        public void bind() {
            // Button is already set up in constructor
        }
    }

    class OnlineModelViewHolder extends RecyclerView.ViewHolder {
        TextView modelNameTextView;
        TextView modelTypeTextView;
        TextView statusTextView;
        TextView apiUrlTextView;
        View statusIndicator;
        MaterialButton deleteButton;
        MaterialButton editButton;
        MaterialButton enableButton;
        MaterialButton fetchModelsButton;
        Spinner modelSpinner;
        ArrayAdapter<String> spinnerAdapter;
        boolean isBinding = false;

        public OnlineModelViewHolder(View itemView) {
            super(itemView);
            modelNameTextView = itemView.findViewById(R.id.online_model_name);
            modelTypeTextView = itemView.findViewById(R.id.online_model_type);
            statusTextView = itemView.findViewById(R.id.online_model_status);
            apiUrlTextView = itemView.findViewById(R.id.online_model_url);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            deleteButton = itemView.findViewById(R.id.delete_online_model_button);
            editButton = itemView.findViewById(R.id.edit_online_model_button);
            enableButton = itemView.findViewById(R.id.enable_button);
            fetchModelsButton = itemView.findViewById(R.id.fetch_models_button);
            modelSpinner = itemView.findViewById(R.id.model_spinner);

            List<String> emptyList = new ArrayList<>();
            emptyList.add("请先获取模型列表");
            spinnerAdapter = new ArrayAdapter<>(context, 
                    android.R.layout.simple_spinner_dropdown_item, emptyList);
            if (modelSpinner != null) {
                modelSpinner.setAdapter(spinnerAdapter);
            }

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    DisplayItem item = displayItems.get(position);
                    if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                        listener.onModelClick(item.modelName);
                    }
                }
            });

            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    DisplayItem item = displayItems.get(position);
                    if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                        // 长按显示详情或编辑选项
                        showModelOptions(item);
                    }
                }
                return true;
            });

            deleteButton.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    DisplayItem item = displayItems.get(position);
                    if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                        listener.onDeleteClick(item.modelName);
                    }
                }
            });

            if (editButton != null) {
                editButton.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && listener != null) {
                        DisplayItem item = displayItems.get(position);
                        if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                            listener.onEditClick(item.modelName);
                        }
                    }
                });
            }

            if (enableButton != null) {
                enableButton.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && listener != null) {
                        DisplayItem item = displayItems.get(position);
                        if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                            boolean newState = !item.isEnabled;
                            listener.onEnableToggle(item.modelName, newState);
                        }
                    }
                });
            }

            if (fetchModelsButton != null) {
                fetchModelsButton.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && listener != null) {
                        DisplayItem item = displayItems.get(position);
                        if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                            listener.onFetchModelsClick(item.modelName);
                        }
                    }
                });
            }

            if (modelSpinner != null) {
                modelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        if (isBinding) return;
                        int adapterPosition = getAdapterPosition();
                        if (adapterPosition != RecyclerView.NO_POSITION && listener != null) {
                            DisplayItem item = displayItems.get(adapterPosition);
                            if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                                Object selectedItem = modelSpinner.getSelectedItem();
                                if (selectedItem != null && !selectedItem.toString().equals("请先获取模型列表")) {
                                    listener.onModelSelected(item.modelName, selectedItem.toString());
                                }
                            }
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
            }
        }

        public void bind(DisplayItem item) {
            isBinding = true;
            try {
                modelNameTextView.setText(item.modelName);
                modelTypeTextView.setText(item.modelType != null ? item.modelType : "");
                apiUrlTextView.setText(item.apiUrl != null ? item.apiUrl : "");

                // Status indicator
                int indicatorDrawable;
                int statusTextRes;
                switch (item.status) {
                    case 1: // online
                        indicatorDrawable = R.drawable.status_indicator_online;
                        statusTextRes = R.string.status_online;
                        break;
                    case 2: // offline
                        indicatorDrawable = R.drawable.status_indicator_offline;
                        statusTextRes = R.string.status_offline;
                        break;
                    case 3: // error
                        indicatorDrawable = R.drawable.status_indicator_error;
                        statusTextRes = R.string.status_error;
                        break;
                    default: // unknown
                        indicatorDrawable = R.drawable.status_indicator_unknown;
                        statusTextRes = R.string.status_unknown;
                        break;
                }
                statusIndicator.setBackgroundResource(indicatorDrawable);

                // Status text
                if (item.isCurrent) {
                    statusTextView.setText(R.string.current_using);
                    statusTextView.setTextColor(context.getResources().getColor(R.color.success_color));
                } else {
                    statusTextView.setText(R.string.click_to_switch);
                    statusTextView.setTextColor(context.getResources().getColor(R.color.primary));
                }

                // Enable button
                if (enableButton != null) {
                    enableButton.setText(item.isEnabled ? R.string.disable : R.string.enable);
                }

                // Model spinner
                if (modelSpinner != null && spinnerAdapter != null) {
                    spinnerAdapter.clear();
                    if (item.modelList != null && !item.modelList.isEmpty()) {
                        spinnerAdapter.addAll(item.modelList);
                        if (item.selectedModel != null) {
                            int selectedIndex = item.modelList.indexOf(item.selectedModel);
                            if (selectedIndex >= 0) {
                                modelSpinner.setSelection(selectedIndex);
                            }
                        }
                    } else {
                        spinnerAdapter.add("请先获取模型列表");
                    }
                }
            } finally {
                isBinding = false;
            }
        }

        private String formatLatency(long latencyMs) {
            if (latencyMs < 1000) {
                return latencyMs + "ms";
            } else {
                return String.format(Locale.getDefault(), "%.1fs", latencyMs / 1000.0);
            }
        }

        private String formatLastUsed(long timestamp) {
            if (timestamp <= 0) return "";

            long diff = System.currentTimeMillis() - timestamp;
            long seconds = diff / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            long days = hours / 24;

            if (days > 0) {
                return context.getString(R.string.last_used_days, (int) days);
            } else if (hours > 0) {
                return context.getString(R.string.last_used_hours, (int) hours);
            } else if (minutes > 0) {
                return context.getString(R.string.last_used_minutes, (int) minutes);
            } else {
                return context.getString(R.string.last_used_just_now);
            }
        }

        private void showModelOptions(DisplayItem item) {
            if (context == null) return;

            String[] options = {"查看详情", "编辑配置", "测试连接", "复制API地址"};
            new android.app.AlertDialog.Builder(context)
                .setTitle(item.modelName)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // 查看详情
                            showModelDetails(item);
                            break;
                        case 1: // 编辑配置
                            if (listener != null) {
                                listener.onEditClick(item.modelName);
                            }
                            break;
                        case 2: // 测试连接
                            if (listener != null) {
                                listener.onFetchModelsClick(item.modelName);
                            }
                            break;
                        case 3: // 复制API地址
                            copyToClipboard(item.apiUrl);
                            break;
                    }
                })
                .show();
        }

        private void showModelDetails(DisplayItem item) {
            if (context == null) return;

            String details = "模型名称: " + item.modelName + "\n" +
                           "模型类型: " + (item.modelType != null ? item.modelType : "未知") + "\n" +
                           "API地址: " + (item.apiUrl != null ? item.apiUrl : "未知") + "\n" +
                           "状态: " + getStatusText(item.status) + "\n" +
                           "延迟: " + item.latencyMs + "ms\n" +
                           "是否启用: " + (item.isEnabled ? "是" : "否");

            new android.app.AlertDialog.Builder(context)
                .setTitle("模型详情")
                .setMessage(details)
                .setPositiveButton("确定", null)
                .show();
        }

        private String getStatusText(int status) {
            switch (status) {
                case 1: return "在线";
                case 2: return "离线";
                case 3: return "错误";
                default: return "未知";
            }
        }

        private void copyToClipboard(String text) {
            if (context == null || text == null) return;
            android.content.ClipboardManager clipboard = 
                (android.content.ClipboardManager) context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("API URL", text);
                clipboard.setPrimaryClip(clip);
                android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 在线模型数据类
     */
    public static class OnlineModel {
        public String name;
        public String modelType;
        public String apiUrl;
        public int status; // 0=unknown, 1=online, 2=offline, 3=error
        public long latencyMs;
        public long lastUsedTime;
        public double costEstimate;
        public boolean isEnabled;

        public OnlineModel(String name) {
            this.name = name;
            this.status = 0;
            this.isEnabled = true;
        }
    }
}