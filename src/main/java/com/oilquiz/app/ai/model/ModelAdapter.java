package com.oilquiz.app.ai.model;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import androidx.cardview.widget.CardView;
import com.oilquiz.app.R;

import java.util.List;

/**
 * ModelAdapter - 模型列表RecyclerView适配器
 * 
 * 功能：
 * - 显示模型列表的每个项
 * - 处理模型选择、下载、删除等操作
 * - 支持模型卡片的布局和交互
 * 
 * 使用方式：
 * RecyclerView recyclerView = findViewById(R.id.model_list);
 * recyclerView.setAdapter(new ModelAdapter(models, action -> {...}));
 * 
 * @author AI Team
 * @since 2024
 */
public class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.ModelViewHolder> {

    private final List<Model> models;
    private final OnModelActionListener actionListener;

    public interface OnModelActionListener {
        void onModelAction(ModelAction action);
    }

    public ModelAdapter(List<Model> models, OnModelActionListener actionListener) {
        this.models = models;
        this.actionListener = actionListener;
    }

    @NonNull
    @Override
    public ModelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model_card, parent, false);
        return new ModelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ModelViewHolder holder, int position) {
        Model model = models.get(position);
        holder.bind(model, actionListener);
    }

    @Override
    public int getItemCount() {
        return models.size();
    }

    public class ModelViewHolder extends RecyclerView.ViewHolder {

        private final CardView modelCard;
        private final ImageView modelIcon;
        private final TextView modelName;
        private final TextView modelDescription;
        private final TextView performanceScore;
        private final TextView qualityScore;
        private final TextView useCases;
        private final TextView architectureValue;
        private final TextView contextLengthValue;
        private final TextView vocabSizeValue;
        private final LinearLayout expandableContent;
        private final MaterialButton selectButton;
        private final MaterialButton expandButton;
        private final MaterialButton deleteButton;
        private final MaterialButton downloadButton;

        public ModelViewHolder(@NonNull View itemView) {
            super(itemView);
            modelCard = itemView.findViewById(R.id.model_card);
            modelIcon = itemView.findViewById(R.id.model_icon);
            modelName = itemView.findViewById(R.id.model_name);
            modelDescription = itemView.findViewById(R.id.model_description);
            performanceScore = itemView.findViewById(R.id.performance_score);
            qualityScore = itemView.findViewById(R.id.quality_score);
            useCases = itemView.findViewById(R.id.use_cases);
            architectureValue = itemView.findViewById(R.id.architecture_value);
            contextLengthValue = itemView.findViewById(R.id.context_length_value);
            vocabSizeValue = itemView.findViewById(R.id.vocab_size_value);
            expandableContent = itemView.findViewById(R.id.expandable_content);
            selectButton = itemView.findViewById(R.id.select_button);
            expandButton = itemView.findViewById(R.id.expand_button);
            deleteButton = itemView.findViewById(R.id.delete_button);
            downloadButton = itemView.findViewById(R.id.download_button);
        }

        public void bind(Model model, OnModelActionListener actionListener) {
            modelName.setText(model.getName());
            modelDescription.setText(model.getDescription());
            performanceScore.setText(String.valueOf(model.getPerformanceScore()));
            qualityScore.setText(String.valueOf(model.getQualityScore()));
            useCases.setText(model.getUseCases());
            architectureValue.setText(model.getArchitecture());
            contextLengthValue.setText(String.valueOf(model.getContextLength()));
            vocabSizeValue.setText(String.valueOf(model.getVocabSize()));

            // 设置选中状态
            if (model.isSelected()) {
                selectButton.setText("已选择");
                selectButton.setEnabled(false);
            } else {
                selectButton.setText("选择");
                selectButton.setEnabled(true);
            }

            // 展开/收起功能
            expandButton.setOnClickListener(v -> {
                if (expandableContent.getVisibility() == View.VISIBLE) {
                    expandableContent.setVisibility(View.GONE);
                    expandButton.setText("查看详情");
                    expandButton.setIconResource(R.drawable.ic_expand_more);
                } else {
                    expandableContent.setVisibility(View.VISIBLE);
                    expandButton.setText("收起详情");
                    expandButton.setIconResource(R.drawable.ic_expand_less);
                }
            });

            // 按钮点击事件
            selectButton.setOnClickListener(v -> actionListener.onModelAction(ModelAction.select(model)));
            downloadButton.setOnClickListener(v -> actionListener.onModelAction(ModelAction.download(model)));
            deleteButton.setOnClickListener(v -> actionListener.onModelAction(ModelAction.delete(model)));
        }
    }
}