package com.oilquiz.app.ui.activity;

import com.oilquiz.app.ui.base.BaseActivity;
import androidx.lifecycle.ViewModelProvider;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.app.AlertDialog;
import java.io.Serializable;
import android.widget.Button;
import androidx.appcompat.widget.SearchView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import java.util.ArrayList;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.ConfigManager;
import com.oilquiz.app.adapter.QuestionAdapter;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.util.List;
import androidx.recyclerview.widget.RecyclerView;

public class QuestionActivity extends BaseActivity {

    private RecyclerView questionListView;
    private Button btnToggleView;
    private Button btnToggleAnswer;
    private Button btnRefresh;
    private Button btnClearAll;
    private Button btnFrontendView;
    private SearchView searchView;
    private Spinner questionTypeSpinner;
    private Spinner difficultySpinner;
    private QuestionAdapter questionAdapter;
    private QuestionViewModel questionViewModel;
    private boolean isCardViewMode = true; // 默认使用卡片视图
    private boolean isAnswerVisible = true; // 默认显示答案

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_question;
    }

    @Override
    protected void initView() {
        // 设置Toolbar
        setupToolbar("题库管理");

        questionListView = findViewById(R.id.questionListView);
        btnToggleView = findViewById(R.id.btn_toggle_view);
        btnToggleAnswer = findViewById(R.id.btn_toggle_answer);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnClearAll = findViewById(R.id.btn_clear_all);
        btnFrontendView = findViewById(R.id.btn_frontend_view);
        searchView = findViewById(R.id.searchView);
        questionTypeSpinner = findViewById(R.id.spinner_question_type);
        difficultySpinner = findViewById(R.id.spinner_difficulty);
        
        // 初始化按钮文本
        btnToggleView.setText(isCardViewMode ? "列表视图" : "卡片视图");
        btnToggleAnswer.setText(isAnswerVisible ? "隐藏答案" : "显示答案");
    }

    @Override
    protected void initData() {
        setupViewModel();
        setupSpinners();
        loadQuestions();
    }

    @Override
    protected void initListener() {
        // 视图切换按钮点击事件
        btnToggleView.setOnClickListener(v -> toggleViewMode());

        // 答案显示/隐藏按钮点击事件
        btnToggleAnswer.setOnClickListener(v -> toggleAnswerVisibility());
        
        // 刷新按钮点击事件
        btnRefresh.setOnClickListener(v -> refreshQuestions());
        
        // 清空全部按钮点击事件
        btnClearAll.setOnClickListener(v -> showClearAllConfirmationDialog());
        
        // 前端界面按钮点击事件
        btnFrontendView.setOnClickListener(v -> {
            Intent intent = new Intent(QuestionActivity.this, com.oilquiz.app.WebViewActivity.class);
            intent.putExtra("url", "file:///android_asset/pages/question-renderer.html");
            intent.putExtra("title", "题目渲染器");
            startActivity(intent);
        });

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchQuestions(query);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText.isEmpty()) {
                    filterQuestions();
                }
                return false;
            }
        });
    }

    private void setupSpinners() {
        // 设置难度 Spinner（保持硬编码，因为难度是固定的）
        List<String> difficulties = new ArrayList<>();
        difficulties.add("全部");
        difficulties.add("简单");
        difficulties.add("中等");
        difficulties.add("困难");
        ArrayAdapter<String> difficultyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, difficulties);
        difficultyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        difficultySpinner.setAdapter(difficultyAdapter);
        
        // 先设置默认的题型适配器
        List<String> defaultQuestionTypes = new ArrayList<>();
        defaultQuestionTypes.add("全部");
        defaultQuestionTypes.add("单选题");
        defaultQuestionTypes.add("多选题");
        defaultQuestionTypes.add("判断题");
        defaultQuestionTypes.add("填空题");
        defaultQuestionTypes.add("简答题");
        ArrayAdapter<String> defaultQuestionTypeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, defaultQuestionTypes);
        defaultQuestionTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        questionTypeSpinner.setAdapter(defaultQuestionTypeAdapter);
        
        // 设置监听器
        setupSpinnerListeners();
        
        // 从数据库加载实际的题型
        loadQuestionTypesFromDatabase();
    }
    
    private void setupSpinnerListeners() {
        questionTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filterQuestions();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        difficultySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filterQuestions();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
    
    private void loadQuestionTypesFromDatabase() {
        questionViewModel.getAllQuestionTypes(new QuestionViewModel.GetQuestionTypesCallback() {
            @Override
            public void onSuccess(List<String> types) {
                runOnUiThread(() -> {
                    List<String> questionTypes = new ArrayList<>();
                    questionTypes.add("全部");
                    questionTypes.addAll(types);
                    ArrayAdapter<String> questionTypeAdapter = new ArrayAdapter<>(QuestionActivity.this,
                            android.R.layout.simple_spinner_item, questionTypes);
                    questionTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    questionTypeSpinner.setAdapter(questionTypeAdapter);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    // 如果从数据库加载失败，使用默认值
                    List<String> questionTypes = new ArrayList<>();
                    questionTypes.add("全部");
                    questionTypes.add("单选题");
                    questionTypes.add("多选题");
                    questionTypes.add("判断题");
                    questionTypes.add("填空题");
                    questionTypes.add("简答题");
                    ArrayAdapter<String> questionTypeAdapter = new ArrayAdapter<>(QuestionActivity.this,
                            android.R.layout.simple_spinner_item, questionTypes);
                    questionTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    questionTypeSpinner.setAdapter(questionTypeAdapter);
                });
            }
        });
    }

    private void setupViewModel() {
        questionViewModel = new ViewModelProvider(this).get(QuestionViewModel.class);
    }

    private void loadQuestions() {
        questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(List<Question> questions) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setupAdapter(questions);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(QuestionActivity.this, "获取题目失败：" + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    // 切换视图模式
    private void toggleViewMode() {
        isCardViewMode = !isCardViewMode;
        // 更新按钮文本
        btnToggleView.setText(isCardViewMode ? "列表视图" : "卡片视图");
        // 重新加载适配器以应用新的视图模式
        loadQuestions();
    }

    // 切换答案显示/隐藏
    private void toggleAnswerVisibility() {
        isAnswerVisible = !isAnswerVisible;
        // 更新按钮文本
        btnToggleAnswer.setText(isAnswerVisible ? "隐藏答案" : "显示答案");
        // 重新加载适配器以应用新的答案显示状态
        loadQuestions();
    }

    private void searchQuestions(String query) {
        questionViewModel.searchQuestions(query, new QuestionViewModel.SearchQuestionsCallback() {
            @Override
            public void onSuccess(List<Question> questions) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setupAdapter(questions);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(QuestionActivity.this, "搜索失败：" + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void setupAdapter(List<Question> questions) {
        questionAdapter = new QuestionAdapter(this, questions, isCardViewMode, isAnswerVisible, new QuestionAdapter.OnQuestionClickListener() {
            @Override
            public void onQuestionClick(Question question) {
                navigateToQuestionDetail(question.getId());
            }

            @Override
            public void onQuestionLongClick(Question question) {
            }

            @Override
            public void onDeleteClick(Question question) {
                // 实现删除逻辑
                new AlertDialog.Builder(QuestionActivity.this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除这道题目吗？")
                        .setPositiveButton("确定", (dialog, which) -> {
                            deleteQuestion(question.getId());
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }

            @Override
            public void onFavoriteClick(Question question, boolean isFavorited) {
                questionViewModel.setQuestionFavorite(question.getId(), isFavorited, new QuestionViewModel.SetFavoriteCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(QuestionActivity.this, isFavorited ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(QuestionActivity.this, "操作失败: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        });
        questionListView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        questionListView.setAdapter(questionAdapter);
    }
    
    // 筛选题目
    private void filterQuestions() {
        String questionType = "全部";
        String difficulty = "全部";
        String searchText = "";
        
        if (questionTypeSpinner != null && questionTypeSpinner.getSelectedItem() != null) {
            questionType = questionTypeSpinner.getSelectedItem().toString();
        }
        if (difficultySpinner != null && difficultySpinner.getSelectedItem() != null) {
            difficulty = difficultySpinner.getSelectedItem().toString();
        }
        if (searchView != null && searchView.getQuery() != null) {
            searchText = searchView.getQuery().toString();
        }
        
        questionViewModel.filterQuestions(questionType, "", difficulty, searchText, new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(List<Question> questions) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setupAdapter(questions);
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(QuestionActivity.this, "筛选失败：" + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
    
    // 刷新题目
    private void refreshQuestions() {
        resetFilters();
        loadQuestions();
    }

    private void navigateToQuestionDetail(long questionId) {
        Intent intent = new Intent(this, QuestionDetailActivity.class);
        intent.putExtra("question_id", questionId);
        startActivity(intent);
    }

    private void deleteQuestion(long questionId) {
        questionViewModel.deleteQuestion(questionId, new QuestionViewModel.DeleteQuestionCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(QuestionActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                        loadQuestions();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(QuestionActivity.this, "删除失败：" + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetFilters();
        loadQuestions();
    }
    
    private void resetFilters() {
        if (searchView != null) {
            searchView.setQuery("", false);
            searchView.clearFocus();
        }
        if (questionTypeSpinner != null) {
            questionTypeSpinner.setSelection(0);
        }
        if (difficultySpinner != null) {
            difficultySpinner.setSelection(0);
        }
        isAnswerVisible = true; // 重置时保持答案显示
        if (btnToggleAnswer != null) {
            btnToggleAnswer.setText("隐藏答案");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            loadQuestions();
        }
    }
    
    private void showClearAllConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部题目")
                .setMessage("确定要清空所有题目吗？此操作不可恢复。")
                .setPositiveButton("确定", (dialog, which) -> clearAllQuestions())
                .setNegativeButton("取消", null)
                .show();
    }
    
    private void clearAllQuestions() {
        questionViewModel.clearAllQuestions(new QuestionViewModel.BatchOperationCallback() {
            @Override
            public void onSuccess(int count) {
                runOnUiThread(() -> {
                    Toast.makeText(QuestionActivity.this, "清空成功", Toast.LENGTH_SHORT).show();
                    loadQuestions();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(QuestionActivity.this, "清空失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }
}
