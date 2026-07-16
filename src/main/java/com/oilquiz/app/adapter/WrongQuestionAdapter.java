package com.oilquiz.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.model.WrongQuestion;

import java.util.List;

public class WrongQuestionAdapter extends BaseAdapter {

    private Context context;
    private List<WrongQuestion> wrongQuestions;
    private OnWrongQuestionClickListener listener;

    public interface OnWrongQuestionClickListener {
        void onWrongQuestionClick(WrongQuestion wrongQuestion);
        void onReviewClick(WrongQuestion wrongQuestion);
        void onDeleteClick(WrongQuestion wrongQuestion);
    }

    public WrongQuestionAdapter(Context context, List<WrongQuestion> wrongQuestions, OnWrongQuestionClickListener listener) {
        this.context = context;
        this.wrongQuestions = wrongQuestions;
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return wrongQuestions != null ? wrongQuestions.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        return wrongQuestions.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void updateData(List<WrongQuestion> newList) {
        this.wrongQuestions = newList;
        notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final ViewHolder holder;

        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_wrong_question, parent, false);
            holder = new ViewHolder();
            holder.tvQuestionText = convertView.findViewById(R.id.tv_question_text);
            holder.tvCorrectAnswer = convertView.findViewById(R.id.tv_correct_answer);
            holder.tvUserAnswer = convertView.findViewById(R.id.tv_user_answer);
            holder.tvCategory = convertView.findViewById(R.id.tv_category);
            holder.btnReview = convertView.findViewById(R.id.btn_review);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        WrongQuestion wrongQuestion = wrongQuestions.get(position);
        if (wrongQuestion != null) {
            holder.tvQuestionText.setText(wrongQuestion.getQuestionText());
            holder.tvCorrectAnswer.setText("正确答案: " + wrongQuestion.getCorrectAnswer());
            holder.tvUserAnswer.setText("你的答案: " + wrongQuestion.getUserAnswer());
            holder.tvCategory.setText("分类: " + wrongQuestion.getCategory());

            holder.btnReview.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onReviewClick(wrongQuestion);
                }
            });

            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClick(wrongQuestion);
                }
            });

            convertView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onWrongQuestionClick(wrongQuestion);
                }
            });
        }

        return convertView;
    }

    static class ViewHolder {
        TextView tvQuestionText;
        TextView tvCorrectAnswer;
        TextView tvUserAnswer;
        TextView tvCategory;
        MaterialButton btnReview;
        MaterialButton btnDelete;
    }
}