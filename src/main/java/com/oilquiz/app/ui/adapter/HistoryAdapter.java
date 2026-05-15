package com.oilquiz.app.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.ScoreHistory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    private List<ScoreHistory> scoreHistoryList;
    private OnHistoryItemClickListener listener;

    public interface OnHistoryItemClickListener {
        void onReviewClick(ScoreHistory scoreHistory);
    }

    public HistoryAdapter(List<ScoreHistory> scoreHistoryList, OnHistoryItemClickListener listener) {
        this.scoreHistoryList = scoreHistoryList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        ScoreHistory scoreHistory = scoreHistoryList.get(position);
        
        // 设置测验模式
        String quizMode = scoreHistory.getQuizType();
        if (quizMode != null) {
            switch (quizMode) {
                case "practice":
                    holder.textViewQuizMode.setText("练习模式");
                    break;
                case "exam":
                    holder.textViewQuizMode.setText("考试模式");
                    break;
                case "review":
                    holder.textViewQuizMode.setText("复习模式");
                    break;
                case "challenge":
                    holder.textViewQuizMode.setText("挑战模式");
                    break;
                case "recite":
                    holder.textViewQuizMode.setText("背诵模式");
                    break;
                default:
                    holder.textViewQuizMode.setText(quizMode);
            }
        } else {
            holder.textViewQuizMode.setText("未知模式");
        }
        
        // 设置得分
        holder.textViewScore.setText(scoreHistory.getScore() + "分");
        
        // 设置题目数量和正确数量
        holder.textViewQuestionCount.setText(scoreHistory.getTotalQuestions() + "题");
        holder.textViewCorrectCount.setText(scoreHistory.getCorrectCount() + "题");
        
        // 计算用时
        long timeUsed = scoreHistory.getEndTime() - scoreHistory.getStartTime();
        int minutes = (int) (timeUsed / 60000);
        int seconds = (int) ((timeUsed % 60000) / 1000);
        holder.textViewTimeUsed.setText(String.format("%02d:%02d", minutes, seconds));
        
        // 设置日期时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date date = new Date(scoreHistory.getStartTime());
        holder.textViewDate.setText(sdf.format(date));
        
        // 设置查看详情按钮点击事件
        holder.buttonReview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onReviewClick(scoreHistory);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return scoreHistoryList != null ? scoreHistoryList.size() : 0;
    }

    public void updateData(List<ScoreHistory> newData) {
        scoreHistoryList = newData;
        notifyDataSetChanged();
    }

    public static class HistoryViewHolder extends RecyclerView.ViewHolder {
        TextView textViewQuizMode;
        TextView textViewScore;
        TextView textViewQuestionCount;
        TextView textViewCorrectCount;
        TextView textViewTimeUsed;
        TextView textViewDate;
        MaterialButton buttonReview;

        public HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewQuizMode = itemView.findViewById(R.id.textViewQuizMode);
            textViewScore = itemView.findViewById(R.id.textViewScore);
            textViewQuestionCount = itemView.findViewById(R.id.textViewQuestionCount);
            textViewCorrectCount = itemView.findViewById(R.id.textViewCorrectCount);
            textViewTimeUsed = itemView.findViewById(R.id.textViewTimeUsed);
            textViewDate = itemView.findViewById(R.id.textViewDate);
            buttonReview = itemView.findViewById(R.id.buttonReview);
        }
    }
}
