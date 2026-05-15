package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;
import com.oilquiz.app.adapter.WrongQuestionAdapter;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.WrongQuestion;
import com.oilquiz.app.repository.QuestionRepository;
import com.oilquiz.app.viewmodel.WrongQuestionViewModel;

public class WrongQuestionActivity extends AppCompatActivity implements WrongQuestionAdapter.OnWrongQuestionClickListener {

    private WrongQuestionViewModel wrongQuestionViewModel;
    private QuestionRepository questionRepository;
    private ListView wrongQuestionListView;
    private WrongQuestionAdapter wrongQuestionAdapter;
    private TextView statsTextView;
    private CardView cardViewReviewAll;
    private CardView cardViewClearAll;
    private ExecutorService executorService;
    private List<WrongQuestion> currentWrongQuestions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wrong_question);

        wrongQuestionViewModel = new ViewModelProvider(this).get(WrongQuestionViewModel.class);
        wrongQuestionViewModel.init(getApplication());
        questionRepository = new QuestionRepository(getApplication());
        executorService = Executors.newSingleThreadExecutor();

        wrongQuestionListView = findViewById(R.id.lv_wrong_questions);
        statsTextView = findViewById(R.id.statsTextView);
        cardViewReviewAll = findViewById(R.id.cardViewReviewAll);
        cardViewClearAll = findViewById(R.id.cardViewClearAll);

        setupButtons();
        loadWrongQuestions();
    }

    private void setupButtons() {
        if (cardViewReviewAll != null) {
            cardViewReviewAll.setOnClickListener(v -> reviewAllWrongQuestions());
        }

        if (cardViewClearAll != null) {
            cardViewClearAll.setOnClickListener(v -> showClearAllConfirmationDialog());
        }
    }

    private void loadWrongQuestions() {
        wrongQuestionViewModel.getWrongQuestions(new com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<List<WrongQuestion>>() {
            @Override
            public void onSuccess(List<WrongQuestion> wrongQuestions) {
                currentWrongQuestions = wrongQuestions != null ? wrongQuestions : new ArrayList<>();
                updateStats(currentWrongQuestions.size());
                loadQuestionDetails(currentWrongQuestions);
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(WrongQuestionActivity.this, "加载错题失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void loadQuestionDetails(List<WrongQuestion> wrongQuestions) {
        if (wrongQuestions == null || wrongQuestions.isEmpty()) {
            runOnUiThread(() -> {
                if (wrongQuestionAdapter == null) {
                    wrongQuestionAdapter = new WrongQuestionAdapter(WrongQuestionActivity.this, new ArrayList<>(), WrongQuestionActivity.this);
                    wrongQuestionListView.setAdapter(wrongQuestionAdapter);
                } else {
                    wrongQuestionAdapter.updateData(new ArrayList<>());
                }
            });
            return;
        }

        executorService.execute(() -> {
            List<WrongQuestion> updatedWrongQuestions = new ArrayList<>();
            for (WrongQuestion wrongQuestion : wrongQuestions) {
                try {
                    AppDatabase db = AppDatabase.getDatabase(getApplication());
                    Question question = db.questionDao().getQuestionById(wrongQuestion.getQuestionId());
                    
                    if (question != null) {
                        wrongQuestion.setQuestionText(question.getQuestionText());
                        wrongQuestion.setCorrectAnswer(question.getCorrectAnswer());
                        wrongQuestion.setCategory(question.getCategory());
                        wrongQuestion.setQuestionType(question.getQuestionType());
                        wrongQuestion.setExplanation(question.getExplanation());
                        wrongQuestion.setOptionA(question.getOptionA());
                        wrongQuestion.setOptionB(question.getOptionB());
                        wrongQuestion.setOptionC(question.getOptionC());
                        wrongQuestion.setOptionD(question.getOptionD());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                updatedWrongQuestions.add(wrongQuestion);
            }

            runOnUiThread(() -> {
                if (wrongQuestionAdapter == null) {
                    wrongQuestionAdapter = new WrongQuestionAdapter(WrongQuestionActivity.this, updatedWrongQuestions, WrongQuestionActivity.this);
                    wrongQuestionListView.setAdapter(wrongQuestionAdapter);
                } else {
                    wrongQuestionAdapter.updateData(updatedWrongQuestions);
                }
            });
        });
    }

    private void updateStats(int count) {
        runOnUiThread(() -> {
            if (statsTextView != null) {
                statsTextView.setText("共 " + count + " 道错题");
            }
        });
    }

    private void reviewAllWrongQuestions() {
        if (currentWrongQuestions == null || currentWrongQuestions.isEmpty()) {
            Toast.makeText(this, "没有错题需要复习", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, QuizActivity.class);
        intent.putExtra(QuizActivity.EXTRA_QUIZ_MODE, "review");
        startActivity(intent);
    }

    private void showClearAllConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("清空错题")
                .setMessage("确定要清空所有错题吗？此操作不可恢复。")
                .setPositiveButton("确定", (dialog, which) -> clearAllWrongQuestions())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearAllWrongQuestions() {
        wrongQuestionViewModel.deleteAllWrongQuestions(new com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                runOnUiThread(() -> {
                    Toast.makeText(WrongQuestionActivity.this, "清空成功", Toast.LENGTH_SHORT).show();
                    loadWrongQuestions();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(WrongQuestionActivity.this, "清空失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onWrongQuestionClick(WrongQuestion wrongQuestion) {
        // 点击错题项，跳转到题目详情页面
        Intent intent = new Intent(this, QuizActivity.class);
        intent.putExtra(QuizActivity.EXTRA_QUIZ_MODE, "review");
        intent.putExtra("question_id", wrongQuestion.getQuestionId());
        startActivity(intent);
    }

    @Override
    public void onReviewClick(WrongQuestion wrongQuestion) {
        // 点击复习按钮，跳转到题目详情页面
        Intent intent = new Intent(this, QuizActivity.class);
        intent.putExtra(QuizActivity.EXTRA_QUIZ_MODE, "review");
        intent.putExtra("question_id", wrongQuestion.getQuestionId());
        startActivity(intent);
    }

    @Override
    public void onDeleteClick(WrongQuestion wrongQuestion) {
        new AlertDialog.Builder(this)
                .setTitle("删除错题")
                .setMessage("确定要删除这道错题吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    wrongQuestionViewModel.deleteWrongQuestion(wrongQuestion.getId(), new com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<Void>() {
                           @Override
                           public void onSuccess(Void result) {
                               runOnUiThread(new Runnable() {
                                   @Override
                                   public void run() {
                                       Toast.makeText(WrongQuestionActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                                       loadWrongQuestions();
                                   }
                               });
                           }

                           @Override
                           public void onError(String error) {
                               runOnUiThread(new Runnable() {
                                   @Override
                                   public void run() {
                                       Toast.makeText(WrongQuestionActivity.this, "删除失败: " + error, Toast.LENGTH_SHORT).show();
                                   }
                               });
                           }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadWrongQuestions(); // 重新加载错题列表
    }
}
