package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ModelManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地模型适配器 - 增强版
 * 支持多种视图类型、DiffUtil 部分更新、更丰富的 UI
 */
public class ModelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_MODEL = 1;
    private static final int VIEW_TYPE_EMPTY = 2;

    private Context context;
    private List<DisplayItem> displayItems = new ArrayList<>();
    private OnModelClickListener listener;
    private String currentModelName;
    private ModelManager modelManager;

    public interface OnModelClickListener {
        void onModelClick(String modelName);
    }

    private static class DisplayItem {
        int type;
        String modelName;
        String headerTitle;

        static DisplayItem header(String title) {
            DisplayItem item = new DisplayItem();
            item.type = VIEW_TYPE_HEADER;
            item.headerTitle = title;
            return item;
        }

        static DisplayItem model(String modelName) {
            DisplayItem item = new DisplayItem();
            item.type = VIEW_TYPE_MODEL;
            item.modelName = modelName;
            return item;
        }

        static DisplayItem empty() {
            DisplayItem item = new DisplayItem();
            item.type = VIEW_TYPE_EMPTY;
            return item;
        }
    }

    public ModelAdapter(Context context, List<String> modelNames, String currentModelName, OnModelClickListener listener) {
        this.context = context;
        this.currentModelName = currentModelName;
        this.listener = listener;
        this.modelManager = new ModelManager(context);
        updateItems(modelNames);
    }

    private void updateItems(List<String> modelNames) {
        List<DisplayItem> newItems = new ArrayList<>();
        newItems.add(DisplayItem.header("本地模型"));
        if (modelNames == null || modelNames.isEmpty()) {
            newItems.add(DisplayItem.empty());
        } else {
            for (String modelName : modelNames) {
                newItems.add(DisplayItem.model(modelName));
            }
        }
        this.displayItems = newItems;
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
                View modelView = inflater.inflate(R.layout.item_model, parent, false);
                return new ModelViewHolder(modelView);
            case VIEW_TYPE_EMPTY:
            default:
                View emptyView = inflater.inflate(R.layout.item_empty_model, parent, false);
                return new EmptyViewHolder(emptyView);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem item = displayItems.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(item.headerTitle);
        } else if (holder instanceof ModelViewHolder) {
            ((ModelViewHolder) holder).bind(item.modelName, item.modelName.equals(currentModelName));
        }
    }

    @Override
    public int getItemCount() {
        return displayItems.size();
    }

    public void updateData(List<String> newModelNames, String newCurrentModelName) {
        List<DisplayItem> oldItems = new ArrayList<>(this.displayItems);
        this.currentModelName = newCurrentModelName;
        updateItems(newModelNames);
        List<DisplayItem> newItems = this.displayItems;

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
                if (oldItem.type == VIEW_TYPE_MODEL && oldItem.modelName != null && newItem.modelName != null) {
                    return oldItem.modelName.equals(newItem.modelName);
                }
                if (oldItem.type == VIEW_TYPE_HEADER && oldItem.headerTitle != null && newItem.headerTitle != null) {
                    return oldItem.headerTitle.equals(newItem.headerTitle);
                }
                return oldItem.type == VIEW_TYPE_EMPTY;
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                DisplayItem oldItem = oldItems.get(oldItemPosition);
                DisplayItem newItem = newItems.get(newItemPosition);
                if (oldItem.type == VIEW_TYPE_MODEL) {
                    if (oldItem.modelName != null && newItem.modelName != null) {
                        boolean oldActive = oldItem.modelName.equals(currentModelName);
                        boolean newActive = newItem.modelName.equals(newCurrentModelName);
                        return oldItem.modelName.equals(newItem.modelName) && oldActive == newActive;
                    }
                }
                return true;
            }
        });
        diffResult.dispatchUpdatesTo(this);
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

    class ModelViewHolder extends RecyclerView.ViewHolder {
        TextView modelNameTextView;
        TextView modelSizeTextView;
        TextView statusTextView;
        TextView modelPathTextView;
        View modelCard;

        public ModelViewHolder(View itemView) {
            super(itemView);
            modelNameTextView = itemView.findViewById(R.id.model_name);
            modelSizeTextView = itemView.findViewById(R.id.model_size);
            statusTextView = itemView.findViewById(R.id.model_status);
            modelPathTextView = itemView.findViewById(R.id.model_path);
            modelCard = itemView.findViewById(R.id.model_card);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    DisplayItem item = displayItems.get(position);
                    if (item.type == VIEW_TYPE_MODEL && item.modelName != null) {
                        listener.onModelClick(item.modelName);
                    }
                }
            });
        }

        public void bind(String modelName, boolean isCurrent) {
            try {
                modelNameTextView.setText(modelName);

                long size = modelManager.getModelSize(modelName);
                modelSizeTextView.setText(formatFileSize(size));

                if (modelPathTextView != null) {
                    String modelPath = modelManager.getModelPath(modelName);
                    modelPathTextView.setText("模型路径: " + (modelPath != null ? modelPath : "未知"));
                }

                if (isCurrent) {
                    statusTextView.setText("当前使用");
                    statusTextView.setTextColor(context.getResources().getColor(R.color.success_color));
                    if (modelCard != null) {
                        modelCard.setBackgroundColor(context.getResources().getColor(R.color.card_background));
                    }
                    itemView.setBackgroundColor(context.getResources().getColor(R.color.card_background));
                } else {
                    statusTextView.setText("点击切换");
                    statusTextView.setTextColor(context.getResources().getColor(R.color.primary));
                    if (modelCard != null) {
                        modelCard.setBackgroundColor(context.getResources().getColor(R.color.background));
                    }
                    itemView.setBackgroundColor(context.getResources().getColor(R.color.background));
                }
            } catch (Exception e) {
                e.printStackTrace();
                modelNameTextView.setText(modelName);
                statusTextView.setText("加载失败");
            }
        }

        private String formatFileSize(long size) {
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return (size / 1024) + " KB";
            } else if (size < 1024 * 1024 * 1024) {
                return (size / (1024 * 1024)) + " MB";
            } else {
                return (size / (1024 * 1024 * 1024)) + " GB";
            }
        }
    }
}
