package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ApiModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型列表适配器
 */
public class ModelListAdapter extends RecyclerView.Adapter<ModelListAdapter.ModelViewHolder> {

    private Context context;
    private List<ApiModel> models;
    private String selectedModelId;
    private OnModelSelectListener listener;

    public interface OnModelSelectListener {
        void onModelSelected(ApiModel model);
    }

    public ModelListAdapter(Context context, List<ApiModel> models, String selectedModelId, OnModelSelectListener listener) {
        this.context = context;
        this.models = models != null ? models : new ArrayList<>();
        this.selectedModelId = selectedModelId;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ModelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_model_list, parent, false);
        return new ModelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ModelViewHolder holder, int position) {
        ApiModel model = models.get(position);
        holder.bind(model, model.id.equals(selectedModelId));
    }

    @Override
    public int getItemCount() {
        return models != null ? models.size() : 0;
    }

    public void updateData(List<ApiModel> models, String selectedModelId) {
        this.models = models != null ? models : new ArrayList<>();
        this.selectedModelId = selectedModelId;
        notifyDataSetChanged();
    }

    public void setSelectedModel(String modelId) {
        String oldSelected = this.selectedModelId;
        this.selectedModelId = modelId;
        // 刷新旧选择项和新选择项
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id.equals(oldSelected) || models.get(i).id.equals(modelId)) {
                notifyItemChanged(i);
            }
        }
    }

    class ModelViewHolder extends RecyclerView.ViewHolder {
        private RadioButton modelRadio;
        private TextView modelNameText;
        private TextView modelContextText;

        public ModelViewHolder(@NonNull View itemView) {
            super(itemView);
            modelRadio = itemView.findViewById(R.id.model_radio);
            modelNameText = itemView.findViewById(R.id.model_name);
            modelContextText = itemView.findViewById(R.id.model_context);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    ApiModel model = models.get(position);
                    setSelectedModel(model.id);
                    if (listener != null) {
                        listener.onModelSelected(model);
                    }
                }
            });

            modelRadio.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    ApiModel model = models.get(position);
                    setSelectedModel(model.id);
                    if (listener != null) {
                        listener.onModelSelected(model);
                    }
                }
            });
        }

        public void bind(ApiModel model, boolean isSelected) {
            modelNameText.setText(model.displayName);
            modelContextText.setText("Context: " + model.getFormattedContextLength());
            modelRadio.setChecked(isSelected);
            
            // 根据是否可用设置样式
            if (model.isAvailable()) {
                modelNameText.setTextColor(context.getResources().getColor(R.color.text_primary));
            } else {
                modelNameText.setTextColor(context.getResources().getColor(R.color.text_tertiary));
            }
        }
    }
}