package com.oilquiz.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.model.Question;

import java.util.ArrayList;
import java.util.List;

public class QuestionAdapter extends RecyclerView.Adapter<QuestionAdapter.ViewHolder> {

    private Context context;
    private List<Question> questions;
    private OnQuestionClickListener onQuestionClickListener;
    private boolean isCardViewMode;
    private boolean isAnswerVisible;

    public interface OnQuestionClickListener {
        void onQuestionClick(Question question);
        void onQuestionLongClick(Question question);
        void onDeleteClick(Question question);
        void onFavoriteClick(Question question, boolean isFavorited);
    }

    public QuestionAdapter(Context context, List<Question> questions, boolean isCardViewMode, boolean isAnswerVisible, OnQuestionClickListener onQuestionClickListener) {
        this.context = context;
        this.questions = questions != null ? questions : new ArrayList<>();
        this.isCardViewMode = isCardViewMode;
        this.isAnswerVisible = isAnswerVisible;
        this.onQuestionClickListener = onQuestionClickListener;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        try {
            int layoutResId = isCardViewMode ? R.layout.item_question : R.layout.item_question_list;
            View view = LayoutInflater.from(context).inflate(layoutResId, parent, false);
            return new ViewHolder(view);
        } catch (Exception e) {
            e.printStackTrace();
            // 创建一个简单的视图作为后备方案，绝对不能返回null！
            View fallbackView = new View(context);
            return new ViewHolder(fallbackView);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        if (holder == null) {
            return;
        }
        if (questions == null || position < 0 || position >= questions.size()) {
            return;
        }
        
        // 检查所有必要的视图是否都不为null
        if (holder.questionNumberTextView == null || holder.questionTypeTextView == null ||
            holder.questionTextTextView == null || holder.categoryTextView == null ||
            holder.difficultyTextView == null || holder.btnFavorite == null || holder.btnDelete == null) {
            return;
        }
        
        final Question question = questions.get(position);
        holder.questionNumberTextView.setText((position + 1) + ".");
        holder.questionTypeTextView.setText(question.getQuestionType() != null ? question.getQuestionType() : "未分类");
        holder.questionTextTextView.setText(question.getQuestionText() != null ? question.getQuestionText() : "");
        holder.categoryTextView.setText(question.getCategory() != null ? question.getCategory() : "");
        String difficultyText = getDifficultyText(question.getDifficulty());
        holder.difficultyTextView.setText("难度: " + difficultyText);

        holder.btnFavorite.setText(question.isFavorite() ? "已收藏" : "收藏");

        if (isAnswerVisible && question.getCorrectAnswer() != null) {
            holder.answerTextView.setVisibility(View.VISIBLE);
            holder.answerTextView.setText("答案: " + question.getCorrectAnswer());
        } else {
            holder.answerTextView.setVisibility(View.GONE);
        }

        java.util.Map<String, String> options = question.getOptions();

        if (holder.optionATextView != null) {
            if (options.containsKey("A")) {
                holder.optionATextView.setVisibility(View.VISIBLE);
                holder.optionATextView.setText("A. " + options.get("A"));
            } else {
                holder.optionATextView.setVisibility(View.GONE);
            }
        }

        if (holder.optionBTextView != null) {
            if (options.containsKey("B")) {
                holder.optionBTextView.setVisibility(View.VISIBLE);
                holder.optionBTextView.setText("B. " + options.get("B"));
            } else {
                holder.optionBTextView.setVisibility(View.GONE);
            }
        }

        if (holder.optionCTextView != null) {
            if (options.containsKey("C")) {
                holder.optionCTextView.setVisibility(View.VISIBLE);
                holder.optionCTextView.setText("C. " + options.get("C"));
            } else {
                holder.optionCTextView.setVisibility(View.GONE);
            }
        }

        if (holder.optionDTextView != null) {
            if (options.containsKey("D")) {
                holder.optionDTextView.setVisibility(View.VISIBLE);
                holder.optionDTextView.setText("D. " + options.get("D"));
            } else {
                holder.optionDTextView.setVisibility(View.GONE);
            }
        }

        holder.itemView.setClickable(false);
        holder.itemView.setFocusable(false);

        holder.btnFavorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onQuestionClickListener != null) {
                    onQuestionClickListener.onFavoriteClick(question, !question.isFavorite());
                }
            }
        });

        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onQuestionClickListener != null) {
                    onQuestionClickListener.onDeleteClick(question);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return questions != null ? questions.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView questionNumberTextView;
        TextView questionTypeTextView;
        TextView questionTextTextView;
        TextView categoryTextView;
        TextView difficultyTextView;
        TextView answerTextView;
        TextView optionATextView;
        TextView optionBTextView;
        TextView optionCTextView;
        TextView optionDTextView;
        MaterialButton btnFavorite;
        MaterialButton btnDelete;

        public ViewHolder(View itemView) {
            super(itemView);
            questionNumberTextView = itemView.findViewById(R.id.questionNumberTextView);
            questionTypeTextView = itemView.findViewById(R.id.questionTypeTextView);
            questionTextTextView = itemView.findViewById(R.id.questionTextTextView);
            categoryTextView = itemView.findViewById(R.id.categoryTextView);
            difficultyTextView = itemView.findViewById(R.id.difficultyTextView);
            answerTextView = itemView.findViewById(R.id.answerTextView);
            optionATextView = itemView.findViewById(R.id.optionATextView);
            optionBTextView = itemView.findViewById(R.id.optionBTextView);
            optionCTextView = itemView.findViewById(R.id.optionCTextView);
            optionDTextView = itemView.findViewById(R.id.optionDTextView);
            btnFavorite = itemView.findViewById(R.id.btn_favorite);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }

    private String getDifficultyText(int difficulty) {
        switch (difficulty) {
            case 1:
                return "简单";
            case 2:
                return "中等";
            case 3:
                return "困难";
            default:
                return "未知";
        }
    }
}