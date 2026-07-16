package com.oilquiz.app.ui.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.repository.QuestionRepository;

public class QuestionDetailActivity extends AppCompatActivity {

    private TextView questionTextTextView;
    private TextView categoryTextView;
    private TextView difficultyTextView;
    private TextView optionATextView;
    private TextView optionBTextView;
    private TextView optionCTextView;
    private TextView optionDTextView;
    private TextView correctAnswerTextView;
    private TextView explanationTextView;
    private MaterialButton shareButton;
    private MaterialButton favoriteButton;
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
        categoryTextView = findViewById(R.id.categoryTextView);
        difficultyTextView = findViewById(R.id.difficultyTextView);
        optionATextView = findViewById(R.id.optionATextView);
        optionBTextView = findViewById(R.id.optionBTextView);
        optionCTextView = findViewById(R.id.optionCTextView);
        optionDTextView = findViewById(R.id.optionDTextView);
        correctAnswerTextView = findViewById(R.id.correctAnswerTextView);
        explanationTextView = findViewById(R.id.explanationTextView);
        shareButton = findViewById(R.id.btn_share);
        favoriteButton = findViewById(R.id.btn_favorite);
        progressBar = findViewById(R.id.progress_bar);
        errorView = findViewById(R.id.error_view);
        errorMessageTextView = findViewById(R.id.error_message);

        questionRepository = new QuestionRepository(getApplication());

        shareButton.setOnClickListener(v -> shareQuestion());
        favoriteButton.setOnClickListener(v -> toggleFavorite());

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
    
    private void showError(String message) {
        errorView.setVisibility(View.VISIBLE);
        errorMessageTextView.setText(message);
    }
    
    private void checkFavoriteStatus(long questionId) {
        isFavorite = false;
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
        if (currentQuestion != null) {
            isFavorite = !isFavorite;
            updateFavoriteButton();
        }
    }
    
    private void shareQuestion() {
        if (currentQuestion != null) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            String shareText = "题目：" + currentQuestion.getQuestionText() + "\n" +
                    "选项：\n" +
                    "A. " + currentQuestion.getOptionA() + "\n" +
                    "B. " + currentQuestion.getOptionB() + "\n" +
                    "C. " + currentQuestion.getOptionC() + "\n" +
                    "D. " + currentQuestion.getOptionD() + "\n" +
                    "正确答案：" + currentQuestion.getCorrectAnswer();
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
            startActivity(Intent.createChooser(shareIntent, "分享题目"));
        }
    }

    private void displayQuestionDetails(Question question) {
        questionTextTextView.setText(question.getQuestionText());
        
        categoryTextView.setText("分类: " + question.getCategory());
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
                difficultyTextView.setTextColor(getResources().getColor(R.color.colorPrimary));
                break;
        }
        
        optionATextView.setText("A. " + (question.getOptionA() != null ? question.getOptionA() : ""));
        optionBTextView.setText("B. " + (question.getOptionB() != null ? question.getOptionB() : ""));
        optionCTextView.setText("C. " + (question.getOptionC() != null ? question.getOptionC() : ""));
        optionDTextView.setText("D. " + (question.getOptionD() != null ? question.getOptionD() : ""));
        
        String correctAnswer = question.getCorrectAnswer();
        if (correctAnswer != null) {
            correctAnswerTextView.setText("正确答案: " + correctAnswer);
            correctAnswerTextView.setTextColor(getResources().getColor(R.color.colorCorrect));
            
            switch (correctAnswer.toUpperCase()) {
                case "A":
                    optionATextView.setBackgroundColor(getResources().getColor(R.color.colorCorrectLight));
                    break;
                case "B":
                    optionBTextView.setBackgroundColor(getResources().getColor(R.color.colorCorrectLight));
                    break;
                case "C":
                    optionCTextView.setBackgroundColor(getResources().getColor(R.color.colorCorrectLight));
                    break;
                case "D":
                    optionDTextView.setBackgroundColor(getResources().getColor(R.color.colorCorrectLight));
                    break;
            }
        } else {
            correctAnswerTextView.setText("正确答案: 无");
            correctAnswerTextView.setTextColor(getResources().getColor(R.color.colorPrimary));
        }
        
        String explanation = question.getExplanation();
        if (explanation != null && !explanation.isEmpty()) {
            explanationTextView.setText("解析: " + explanation);
        } else {
            explanationTextView.setText("解析: 无");
            explanationTextView.setTextColor(getResources().getColor(R.color.gray_500));
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
