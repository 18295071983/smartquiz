package com.oilquiz.app.ai.performance;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;

import java.util.List;

/**
 * OptimizationSuggestionAdapter - 优化建议列表适配器
 * 
 * 功能：
 * - 显示AI性能优化建议列表
 * - 处理建议项的点击事件
 * - 支持RecyclerView列表展示
 * 
 * @author AI Team
 * @since 2024
 */
public class OptimizationSuggestionAdapter extends RecyclerView.Adapter<OptimizationSuggestionAdapter.SuggestionViewHolder> {

    private final List<OptimizationSuggestion> suggestions;
    private final OnSuggestionClickListener clickListener;

    public interface OnSuggestionClickListener {
        void onSuggestionClick(OptimizationSuggestion suggestion);
    }

    public OptimizationSuggestionAdapter(List<OptimizationSuggestion> suggestions, OnSuggestionClickListener clickListener) {
        this.suggestions = suggestions;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public SuggestionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_optimization_suggestion, parent, false);
        return new SuggestionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SuggestionViewHolder holder, int position) {
        OptimizationSuggestion suggestion = suggestions.get(position);
        holder.bind(suggestion, clickListener);
    }

    @Override
    public int getItemCount() {
        return suggestions.size();
    }

    public class SuggestionViewHolder extends RecyclerView.ViewHolder {

        private final MaterialCardView suggestionCard;
        private final TextView titleText;
        private final TextView descriptionText;
        private final TextView priorityText;
        private final TextView improvementText;
        private final MaterialButton applyButton;

        public SuggestionViewHolder(@NonNull View itemView) {
            super(itemView);
            suggestionCard = itemView.findViewById(R.id.suggestion_card);
            titleText = itemView.findViewById(R.id.suggestion_title);
            descriptionText = itemView.findViewById(R.id.suggestion_description);
            priorityText = itemView.findViewById(R.id.suggestion_priority);
            improvementText = itemView.findViewById(R.id.suggestion_improvement);
            applyButton = itemView.findViewById(R.id.apply_button);
        }

        public void bind(OptimizationSuggestion suggestion, OnSuggestionClickListener clickListener) {
            titleText.setText(suggestion.title);
            descriptionText.setText(suggestion.description);
            improvementText.setText(suggestion.estimatedImprovement);

            // 设置优先级颜色和文本
            switch (suggestion.priority) {
                case HIGH:
                    priorityText.setText("高优先级");
                    priorityText.setTextColor(itemView.getContext().getResources().getColor(R.color.error));
                    break;
                case MEDIUM:
                    priorityText.setText("中优先级");
                    priorityText.setTextColor(itemView.getContext().getResources().getColor(R.color.warning));
                    break;
                case LOW:
                    priorityText.setText("低优先级");
                    priorityText.setTextColor(itemView.getContext().getResources().getColor(R.color.success));
                    break;
            }

            // 应用按钮点击事件
            applyButton.setOnClickListener(v -> clickListener.onSuggestionClick(suggestion));
        }
    }
}