package com.oilquiz.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Question;

import java.util.ArrayList;
import java.util.List;

public class QuestionAdapterEnhance extends RecyclerView.Adapter<QuestionAdapterEnhance.ViewHolder> {

    private final Context context;
    private List<Question> questions = new ArrayList<>();
    private OnQuestionClickListener listener;

    public interface OnQuestionClickListener {
        void onQuestionClick(Question question, int position);
        void onFavoriteClick(Question question, int position);
        void onDeleteClick(Question question, int position);
    }

    public void setOnQuestionClickListener(OnQuestionClickListener listener) {
        this.listener = listener;
    }

    public QuestionAdapterEnhance(Context context) {
        this.context = context;
    }

    public void submitList(List<Question> newQuestions) {
        if (newQuestions == null) {
            this.questions = new ArrayList<>();
        } else {
            this.questions = new ArrayList<>(newQuestions);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_question_improved, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Question question = questions.get(position);
        holder.bind(question, position);
    }

    @Override
    public int getItemCount() {
        return questions.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private TextView questionTextTextView;
        private TextView tvOptionsPreview;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            questionTextTextView = itemView.findViewById(R.id.questionTextTextView);
            tvOptionsPreview = itemView.findViewById(R.id.tvOptionsPreview);
        }

        void bind(Question question, int position) {
            String questionText = question.getQuestionText();
            if (questionText == null) questionText = "";
            questionTextTextView.setText(questionText);

            String optionsPreview = generateOptionsPreview(question);
            tvOptionsPreview.setText(optionsPreview);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onQuestionClick(question, position);
                }
            });
        }

        private String generateOptionsPreview(Question question) {
            StringBuilder sb = new StringBuilder();
            java.util.Map<String, String> options = question.getOptions();
            
            if (options != null && !options.isEmpty()) {
                try {
                    if (options.containsKey("A")) sb.append("A ");
                    if (options.containsKey("B")) sb.append("B ");
                    if (options.containsKey("C")) sb.append("C ");
                    if (options.containsKey("D")) sb.append("D ");
                } catch (Exception e) {
                    sb.append("查看选项");
                }
            }
            
            return sb.length() > 0 ? sb.toString() : "无选项";
        }
    }
}