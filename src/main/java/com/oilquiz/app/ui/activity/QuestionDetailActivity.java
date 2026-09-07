package com.oilquiz.app.ui.activity;

import com.oilquiz.app.theme.ThemeColors;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.button.MaterialButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.repository.QuestionRepository;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class QuestionDetailActivity extends AppCompatActivity {

    private TextView questionTextTextView;
    private TextView questionTypeTextView;
    private TextView categoryTextView;
    private TextView difficultyTextView;
    private LinearLayout optionsContainer;
    private TextView correctAnswerTextView;
    private TextView explanationTextView;
    private TextView analysisTextView;
    private TextView knowledgePointTextView;
    private TextView tagsTextView;
    private TextView hintTextView;
    private MaterialButton shareButton;
    private MaterialButton favoriteButton;
    private MaterialButton editButton;
    private MaterialButton deleteButton;
    private ProgressBar progressBar;
    private View errorView;
    private TextView errorMessageTextView;

    private QuestionRepository questionRepository;
    private Question currentQuestion;
    private boolean isFavorite = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_detail);

        initViews();
        loadQuestionDetails();
    }

    private void initViews() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        questionTextTextView = findViewById(R.id.questionTextTextView);
        questionTypeTextView = findViewById(R.id.questionTypeTextView);
        categoryTextView = findViewById(R.id.categoryTextView);
        difficultyTextView = findViewById(R.id.difficultyTextView);
        optionsContainer = findViewById(R.id.optionsContainer);
        correctAnswerTextView = findViewById(R.id.correctAnswerTextView);
        explanationTextView = findViewById(R.id.explanationTextView);
        analysisTextView = findViewById(R.id.analysisTextView);
        knowledgePointTextView = findViewById(R.id.knowledgePointTextView);
        tagsTextView = findViewById(R.id.tagsTextView);
        hintTextView = findViewById(R.id.hintTextView);
        shareButton = findViewById(R.id.btn_share);
        favoriteButton = findViewById(R.id.btn_favorite);
        editButton = findViewById(R.id.editButton);
        deleteButton = findViewById(R.id.deleteButton);
        progressBar = findViewById(R.id.progress_bar);
        errorView = findViewById(R.id.error_view);
        errorMessageTextView = findViewById(R.id.error_message);

        questionRepository = new QuestionRepository(getApplication());

        shareButton.setOnClickListener(v -> shareQuestion());
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        // 编辑：返回题库页后由列表编辑（复用题库页的编辑对话框）
        editButton.setOnClickListener(v -> {
            Toast.makeText(this, "请在题库列表中点击题目旁的「编辑」修改", Toast.LENGTH_SHORT).show();
            finish();
        });
        // 删除：删除当前题目并关闭详情页
        deleteButton.setOnClickListener(v -> deleteQuestion());

        progressBar.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
    }

    private void loadQuestionDetails() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("question_id")) {
            long questionId = intent.getLongExtra("question_id", -1);
            if (questionId != -1) {
                progressBar.setVisibility(View.VISIBLE);
                errorView.setVisibility(View.GONE);

                questionRepository.getQuestionById(questionId, new QuestionRepository.RepositoryCallback<Question>() {
                    @Override
                    public void onSuccess(Question question) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            if (question != null) {
                                currentQuestion = question;
                                displayQuestionDetails(question);
                                checkFavoriteStatus(question.getId());
                            } else {
                                showError("题目不存在");
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            showError("加载题目失败：" + error);
                        });
                    }
                });
            } else {
                progressBar.setVisibility(View.GONE);
                showError("无效的题目ID");
            }
        } else {
            progressBar.setVisibility(View.GONE);
            showError("未提供题目ID");
        }
    }

    private void deleteQuestion() {
        if (currentQuestion == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除题目")
                .setMessage("确定要删除这道题目吗？此操作不可恢复。")
                .setPositiveButton("删除", (dialog, which) -> {
                    questionRepository.deleteQuestion(currentQuestion.getId(), new QuestionViewModel.DeleteQuestionCallback() {
                        @Override
                        public void onSuccess() {
                            runOnUiThread(() -> {
                                Toast.makeText(QuestionDetailActivity.this, "题目已删除", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> Toast.makeText(QuestionDetailActivity.this,
                                    "删除失败：" + error, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showError(String message) {
        errorView.setVisibility(View.VISIBLE);
        errorMessageTextView.setText(message);
    }

    private void checkFavoriteStatus(long questionId) {
        // 题目对象自带 favorite 字段（getQuestionById 已加载），直接使用真实状态
        isFavorite = currentQuestion != null && currentQuestion.isFavorite();
        updateFavoriteButton();
    }

    private void updateFavoriteButton() {
        if (isFavorite) {
            favoriteButton.setText("取消收藏");
            favoriteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_favorite_filled, 0, 0, 0);
        } else {
            favoriteButton.setText("收藏");
            favoriteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_favorite_empty, 0, 0, 0);
        }
    }

    private void toggleFavorite() {
        if (currentQuestion == null) return;
        final boolean newValue = !isFavorite;
        questionRepository.setQuestionFavorite(currentQuestion.getId(), newValue,
                new QuestionRepository.RepositoryCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        runOnUiThread(() -> {
                            if (Boolean.TRUE.equals(result)) {
                                isFavorite = newValue;
                                currentQuestion.setFavorite(newValue);
                                updateFavoriteButton();
                            } else {
                                Toast.makeText(QuestionDetailActivity.this,
                                        "收藏操作失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        runOnUiThread(() -> Toast.makeText(QuestionDetailActivity.this,
                                "收藏失败：" + error, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private void shareQuestion() {
        if (currentQuestion != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("题目：").append(currentQuestion.getQuestionText()).append("\n");
            Map<String, String> options = currentQuestion.getOptions();
            if (options != null && !options.isEmpty()) {
                sb.append("选项：\n");
                for (Map.Entry<String, String> e : new TreeMap<>(options).entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        sb.append(e.getKey()).append(". ").append(e.getValue()).append("\n");
                    }
                }
            }
            sb.append("正确答案：").append(currentQuestion.getCorrectAnswer());
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(shareIntent, "分享题目"));
        }
    }

    private void displayQuestionDetails(Question question) {
        questionTextTextView.setText(question.getQuestionText());

        setVisibleText(questionTypeTextView, "题型", question.getQuestionType());
        setVisibleText(categoryTextView, "分类", question.getCategory());
        difficultyTextView.setText("难度: " + getDifficultyText(question.getDifficulty()));
        switch (question.getDifficulty()) {
            case 1:
                difficultyTextView.setTextColor(getResources().getColor(R.color.colorEasy));
                break;
            case 2:
                difficultyTextView.setTextColor(getResources().getColor(R.color.colorMedium));
                break;
            case 3:
                difficultyTextView.setTextColor(getResources().getColor(R.color.colorHard));
                break;
            default:
                difficultyTextView.setTextColor(ThemeColors.attr(this, R.attr.colorPrimary));
                break;
        }

        renderOptions(question);

        String correctAnswer = question.getCorrectAnswer();
        if (correctAnswer != null && !correctAnswer.trim().isEmpty()) {
            correctAnswerTextView.setText("正确答案: " + correctAnswer);
            correctAnswerTextView.setTextColor(getResources().getColor(R.color.colorCorrect));
        } else {
            correctAnswerTextView.setText("正确答案: 无");
            correctAnswerTextView.setTextColor(ThemeColors.attr(this, R.attr.colorPrimary));
        }

        setVisibleText(explanationTextView, "解析", question.getExplanation());
        setVisibleText(analysisTextView, "详细解析", question.getAnalysis());
        setVisibleText(knowledgePointTextView, "知识点", question.getKnowledgePoint());
        setVisibleText(tagsTextView, "标签", question.getTags());
        setVisibleText(hintTextView, "提示", question.getHint());
    }

    /** 动态渲染全部非空选项（A~L），正确答案字母（支持多选如 AB）对应行高亮 */
    private void renderOptions(Question question) {
        optionsContainer.removeAllViews();
        Map<String, String> options = question.getOptions();
        if (options == null || options.isEmpty()) {
            optionsContainer.setVisibility(View.GONE);
            return;
        }
        optionsContainer.setVisibility(View.VISIBLE);

        Set<String> correctLetters = new HashSet<>();
        String answer = question.getCorrectAnswer();
        if (answer != null) {
            for (char c : answer.toUpperCase().replaceAll("\\s+", "").toCharArray()) {
                correctLetters.add(String.valueOf(c));
            }
        }

        float density = getResources().getDisplayMetrics().density;
        for (Map.Entry<String, String> entry : new TreeMap<>(options).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isEmpty()) continue;

            TextView tv = new TextView(this);
            tv.setText(key + ". " + value);
            tv.setTextSize(16);
            boolean isCorrect = correctLetters.contains(key);
            tv.setTextColor(isCorrect
                    ? getResources().getColor(R.color.colorCorrect)
                    : ThemeColors.attr(this, R.attr.colorControlText));
            if (isCorrect) {
                tv.setBackgroundColor(getResources().getColor(R.color.colorCorrectLight));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * density);
            tv.setPadding((int) (8 * density), (int) (4 * density), 0, (int) (4 * density));
            optionsContainer.addView(tv, lp);
        }
    }

    /** 字段为空时隐藏，避免显示空标签 */
    private void setVisibleText(TextView tv, String label, String value) {
        if (value != null && !value.isEmpty()) {
            tv.setText(label + ": " + value);
            tv.setVisibility(View.VISIBLE);
        } else {
            tv.setVisibility(View.GONE);
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

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
