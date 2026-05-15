package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ModelManager;

import java.util.List;

public class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.ModelViewHolder> {

    private Context context;
    private List<String> modelNames;
    private OnModelClickListener listener;
    private String currentModelName;
    private ModelManager modelManager;

    public interface OnModelClickListener {
        void onModelClick(String modelName);
    }

    public ModelAdapter(Context context, List<String> modelNames, String currentModelName, OnModelClickListener listener) {
        this.context = context;
        this.modelNames = modelNames;
        this.currentModelName = currentModelName;
        this.listener = listener;
        this.modelManager = new ModelManager(context);
    }

    @Override
    public ModelViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_model, parent, false);
        return new ModelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ModelViewHolder holder, int position) {
        String modelName = modelNames.get(position);
        holder.bind(modelName, modelName.equals(currentModelName));
    }

    @Override
    public int getItemCount() {
        return modelNames != null ? modelNames.size() : 0;
    }

    public void updateData(List<String> modelNames, String currentModelName) {
        this.modelNames = modelNames;
        this.currentModelName = currentModelName;
        notifyDataSetChanged();
    }

    class ModelViewHolder extends RecyclerView.ViewHolder {
        TextView modelNameTextView;
        TextView modelSizeTextView;
        TextView statusTextView;

        public ModelViewHolder(View itemView) {
            super(itemView);
            modelNameTextView = itemView.findViewById(R.id.model_name);
            modelSizeTextView = itemView.findViewById(R.id.model_size);
            statusTextView = itemView.findViewById(R.id.model_status);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onModelClick(modelNames.get(position));
                }
            });
        }

        public void bind(String modelName, boolean isCurrent) {
            try {
                modelNameTextView.setText(modelName);
                
                // 显示模型大小
                long size = modelManager.getModelSize(modelName);
                modelSizeTextView.setText(formatFileSize(size));

                // 显示模型路径
                TextView modelPathTextView = itemView.findViewById(R.id.model_path);
                if (modelPathTextView != null) {
                    String modelPath = modelManager.getModelPath(modelName);
                    modelPathTextView.setText("模型路径: " + (modelPath != null ? modelPath : "未知"));
                }

                // 显示状态
                if (isCurrent) {
                    statusTextView.setText("当前使用");
                    statusTextView.setTextColor(context.getResources().getColor(R.color.success_color));
                    itemView.setBackgroundColor(context.getResources().getColor(R.color.card_background));
                } else {
                    statusTextView.setText("点击切换");
                    statusTextView.setTextColor(context.getResources().getColor(R.color.primary));
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
