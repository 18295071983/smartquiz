package com.oilquiz.app.adapter;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.model.Question;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class QuestionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private Context context;
    private List<Question> questions;
    private OnQuestionClickListener onQuestionClickListener;
    private boolean isCardViewMode;
    private boolean isAnswerVisible;

    // 统计数据
    private int totalCount;
    private int favoriteCount;
    private int categoryCount;

    public interface OnQuestionClickListener {
        void onQuestionClick(Question question);
        void onQuestionLongClick(Question question);
        void onDeleteClick(Question question);
        void onFavoriteClick(Question question, boolean isFavorited);
    }

    public QuestionAdapter(Context context, List<Question> questions, boolean isCardViewMode,
                           boolean isAnswerVisible, OnQuestionClickListener listener) {
        this.context = context;
        this.questions = questions != null ? questions : new ArrayList<>();
        this.isCardViewMode = isCardViewMode;
        this.isAnswerVisible = isAnswerVisible;
        this.onQuestionClickListener = listener;
    }

    /** 更新统计数据并刷新 header */
    public void updateStats(int total, int favorite, int category) {
        this.totalCount = total;
        this.favoriteCount = favorite;
        this.categoryCount = category;
        notifyItemChanged(0);
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        return 1 + (questions != null ? questions.size() : 0); // header + items
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_question_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            int layoutResId = isCardViewMode ? R.layout.item_question : R.layout.item_question_list;
            View view = LayoutInflater.from(context).inflate(layoutResId, parent, false);
            return new ItemViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            bindHeader((HeaderViewHolder) holder);
        } else if (holder instanceof ItemViewHolder) {
            bindItem((ItemViewHolder) holder, position - 1); // position 0 is header
        }
    }

    private void bindHeader(HeaderViewHolder h) {
        h.totalCountTextView.setText(String.valueOf(totalCount));
        h.favoriteCountTextView.setText(String.valueOf(favoriteCount));
        h.categoryCountTextView.setText(String.valueOf(categoryCount));
    }

    private void bindItem(ItemViewHolder h, int index) {
        if (questions == null || index < 0 || index >= questions.size()) return;
        final Question question = questions.get(index);
        if (question == null) return;

        // 题号
        h.questionNumberTextView.setText((index + 1) + ".");

        // 题型
        String type = question.getQuestionType();
        h.questionTypeTextView.setText(type != null ? type : "未分类");

        // 题目内容（完整显示）
        h.questionTextTextView.setText(question.getQuestionText() != null ? question.getQuestionText() : "");

        // 分类
        h.categoryTextView.setText(question.getCategory() != null ? question.getCategory() : "");

        // 难度（使用 Question 便捷方法）
        h.difficultyTextView.setText(question.getDifficultyText());

        // 收藏按钮
        if (h.btnFavorite != null) {
            h.btnFavorite.setIconResource(
                question.isFavorite()
                    ? android.R.drawable.btn_star_big_on
                    : android.R.drawable.btn_star_big_off
            );
            h.btnFavorite.setOnClickListener(v -> {
                if (onQuestionClickListener != null) {
                    onQuestionClickListener.onFavoriteClick(question, !question.isFavorite());
                }
            });
        }

        // 删除按钮
        if (h.btnDelete != null) {
            h.btnDelete.setOnClickListener(v -> {
                if (onQuestionClickListener != null) {
                    onQuestionClickListener.onDeleteClick(question);
                }
            });
        }

        // 题目配图
        bindQuestionImage(h, question);

        // 选项（动态渲染所有选项 A~L）
        bindDynamicOptions(h, question);

        // 答案和解析
        boolean showAnswer = isAnswerVisible && question.getCorrectAnswer() != null;
        if (h.answerTextView != null) {
            if (showAnswer) {
                h.answerTextView.setVisibility(View.VISIBLE);
                String displayAnswer = question.getDisplayAnswer();
                h.answerTextView.setText("答案: " + displayAnswer);
            } else {
                h.answerTextView.setVisibility(View.GONE);
            }
        }

        // 解析（答案可见时同步显示）
        bindExplanation(h, question, showAnswer);
    }

    /**
     * 动态渲染所有选项（A~L）到 optionsLayout 容器中。
     * 使用 TreeMap 保证按字母顺序排列，答案可见时高亮正确选项。
     */
    private void bindDynamicOptions(ItemViewHolder h, Question question) {
        // 列表模式没有 optionsLayout，跳过
        if (h.optionsLayout == null) return;

        h.optionsLayout.removeAllViews();

        Map<String, String> options = question.getOptions();
        if (options == null || options.isEmpty()) {
            h.optionsLayout.setVisibility(View.GONE);
            return;
        }

        h.optionsLayout.setVisibility(View.VISIBLE);

        // 解析正确答案字母集合（用于高亮）
        Set<String> correctLetters = new HashSet<>();
        if (isAnswerVisible && question.getCorrectAnswer() != null) {
            String answer = question.getCorrectAnswer().trim().toUpperCase().replaceAll("\\s+", "");
            for (char c : answer.toCharArray()) {
                correctLetters.add(String.valueOf(c));
            }
        }

        // 按字母顺序排序选项
        Map<String, String> sorted = new TreeMap<>(options);
        float density = context.getResources().getDisplayMetrics().density;

        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isEmpty()) continue;

            // 选项行容器
            LinearLayout optionRow = new LinearLayout(context);
            optionRow.setOrientation(LinearLayout.HORIZONTAL);
            optionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            optionRow.setPadding(
                (int) (12 * density),
                (int) (10 * density),
                (int) (12 * density),
                (int) (10 * density)
            );

            // 判断是否为正确答案选项
            boolean isCorrect = isAnswerVisible && correctLetters.contains(key);
            if (isCorrect) {
                optionRow.setBackgroundResource(R.drawable.option_bg_correct);
            } else {
                optionRow.setBackgroundResource(R.drawable.option_bg_default);
            }

            // 字母标记
            TextView letterView = new TextView(context);
            letterView.setText(key);
            letterView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            letterView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            letterView.setTextColor(isCorrect
                ? 0xFF4CAF50  // success green
                : 0xFF666666);
            letterView.setGravity(android.view.Gravity.CENTER);
            int letterSize = (int) (28 * density);
            LinearLayout.LayoutParams letterLp = new LinearLayout.LayoutParams(letterSize, letterSize);
            letterLp.setMarginEnd((int) (10 * density));
            optionRow.addView(letterView, letterLp);

            // 选项内容
            TextView contentView = new TextView(context);
            contentView.setText(value);
            contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            contentView.setTextColor(isCorrect
                ? 0xFF4CAF50
                : 0xFF333333);
            contentView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            optionRow.addView(contentView);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = (int) (6 * density);
            h.optionsLayout.addView(optionRow, lp);
        }
    }

    /**
     * 渲染题目配图（从 imageUri 加载）
     */
    private void bindQuestionImage(ItemViewHolder h, Question question) {
        if (h.questionImageView == null) return;

        String imageUri = question.getImageUri();
        if (imageUri == null || imageUri.isEmpty()) {
            h.questionImageView.setVisibility(View.GONE);
            h.questionImageView.setImageBitmap(null);
            return;
        }

        try {
            if (imageUri.startsWith("/")) {
                Bitmap bitmap = BitmapFactory.decodeFile(imageUri);
                if (bitmap != null) {
                    h.questionImageView.setImageBitmap(bitmap);
                    h.questionImageView.setVisibility(View.VISIBLE);
                } else {
                    h.questionImageView.setVisibility(View.GONE);
                }
            } else if (imageUri.startsWith("content://") || imageUri.startsWith("file://")) {
                h.questionImageView.setImageURI(Uri.parse(imageUri));
                h.questionImageView.setVisibility(View.VISIBLE);
            } else {
                int resId = context.getResources().getIdentifier(imageUri, "drawable", context.getPackageName());
                if (resId != 0) {
                    h.questionImageView.setImageResource(resId);
                    h.questionImageView.setVisibility(View.VISIBLE);
                } else {
                    h.questionImageView.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            h.questionImageView.setVisibility(View.GONE);
        }
    }

    /**
     * 渲染题目解析（答案可见时同步显示）
     */
    private void bindExplanation(ItemViewHolder h, Question question, boolean showAnswer) {
        if (h.explanationTextView == null) return;

        String explanation = question.getExplanation();
        String analysis = question.getAnalysis();
        // 优先使用详细解析，回退到普通解析
        String text = (analysis != null && !analysis.isEmpty()) ? analysis : explanation;

        if (showAnswer && text != null && !text.isEmpty()) {
            h.explanationTextView.setVisibility(View.VISIBLE);
            h.explanationTextView.setText("💡 解析: " + text);
        } else {
            h.explanationTextView.setVisibility(View.GONE);
        }
    }

    // ========== Header ViewHolder ==========
    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView totalCountTextView;
        TextView favoriteCountTextView;
        TextView categoryCountTextView;

        HeaderViewHolder(View itemView) {
            super(itemView);
            totalCountTextView = itemView.findViewById(R.id.totalCountTextView);
            favoriteCountTextView = itemView.findViewById(R.id.favoriteCountTextView);
            categoryCountTextView = itemView.findViewById(R.id.categoryCountTextView);
        }
    }

    // ========== Item ViewHolder ==========
    static class ItemViewHolder extends RecyclerView.ViewHolder {
        TextView questionNumberTextView;
        TextView questionTypeTextView;
        TextView questionTextTextView;
        TextView categoryTextView;
        TextView difficultyTextView;
        TextView answerTextView;
        TextView explanationTextView;
        ImageView questionImageView;
        MaterialButton btnFavorite;
        MaterialButton btnDelete;
        LinearLayout optionsLayout; // 动态选项容器（卡片模式有，列表模式为null）

        ItemViewHolder(View itemView) {
            super(itemView);
            questionNumberTextView = itemView.findViewById(R.id.questionNumberTextView);
            questionTypeTextView = itemView.findViewById(R.id.questionTypeTextView);
            questionTextTextView = itemView.findViewById(R.id.questionTextTextView);
            categoryTextView = itemView.findViewById(R.id.categoryTextView);
            difficultyTextView = itemView.findViewById(R.id.difficultyTextView);
            answerTextView = itemView.findViewById(R.id.answerTextView);
            explanationTextView = itemView.findViewById(R.id.explanationTextView);
            questionImageView = itemView.findViewById(R.id.questionImageView);
            btnFavorite = itemView.findViewById(R.id.btn_favorite);
            btnDelete = itemView.findViewById(R.id.btn_delete);
            optionsLayout = itemView.findViewById(R.id.optionsLayout);
        }
    }
}
