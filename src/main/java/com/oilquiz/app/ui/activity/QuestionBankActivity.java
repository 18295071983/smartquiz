package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.oilquiz.app.R;
import com.oilquiz.app.adapter.QuestionAdapter;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.util.export.ExportFileSaver;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.render.ExcelUtil;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class QuestionBankActivity extends BaseActivity {

    private static final int REQUEST_CODE_PICK_FILE = 1001;

    private RecyclerView questionListView;
    private QuestionAdapter questionAdapter;
    private EditText searchEditText;
    private List<Question> allQuestions = new ArrayList<>();
    private List<Question> filteredQuestions = new ArrayList<>();
    private String currentFilterCategory = null;
    private String currentFilterType = null;
    private Integer currentFilterDifficulty = null;

    @Inject
    QuestionViewModel questionViewModel;

    @Inject
    ExportManager exportManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_question_bank;
    }

    @Override
    protected void initView() {
        setupToolbar("题库管理");
        
        questionListView = findViewById(R.id.questionListView);
        questionListView.setLayoutManager(new LinearLayoutManager(this));
        searchEditText = findViewById(R.id.searchEditText);
    }

    @Override
    protected void initData() {
        exportManager.init(this);
        loadQuestions();
    }

    @Override
    protected void initListener() {
        // 搜索框实时过滤
        if (searchEditText != null) {
            searchEditText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    applyFilters();
                }
            });
        }

        // 筛选按钮
        View btnFilter = findViewById(R.id.btnFilter);
        if (btnFilter != null) {
            btnFilter.setOnClickListener(v -> showFilterDialog());
        }

        // 题库智能修复入口
        View btnRepair = findViewById(R.id.btnRepair);
        if (btnRepair != null) {
            btnRepair.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, QuestionRepairActivity.class)));
        }

        // 视图模式切换
        View btnListView = findViewById(R.id.btnListView);
        if (btnListView != null) {
            btnListView.setOnClickListener(v -> {
                isCardViewMode = false;
                loadQuestions();
            });
        }
        
        View btnCardView = findViewById(R.id.btnCardView);
        if (btnCardView != null) {
            btnCardView.setOnClickListener(v -> {
                isCardViewMode = true;
                loadQuestions();
            });
        }
    }
    
    // 更新视图模式按钮的状态
    private void updateViewModeButtons() {
        View btnListView = findViewById(R.id.btnListView);
        View btnCardView = findViewById(R.id.btnCardView);
        
        if (btnListView != null && btnCardView != null) {
            if (isCardViewMode) {
                ((com.google.android.material.button.MaterialButton)btnCardView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.primary, null));
                ((com.google.android.material.button.MaterialButton)btnListView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.gray_500, null));
            } else {
                ((com.google.android.material.button.MaterialButton)btnListView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.primary, null));
                ((com.google.android.material.button.MaterialButton)btnCardView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.gray_500, null));
            }
        }
    }

    // 显示批量操作对话框
    private void showBatchOperationsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("批量操作")
                .setItems(new String[]{"批量删除", "批量收藏", "批量导出"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            batchDeleteQuestions();
                            break;
                        case 1:
                            batchFavoriteQuestions();
                            break;
                        case 2:
                            exportQuestions();
                            break;
                    }
                })
                .show();
    }

    private boolean isCardViewMode = true; // 默认使用卡片视图
    private boolean isAnswerVisible = false; // 默认隐藏答案

    private void loadQuestions() {
        // 加载所有题目
        questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(java.util.List<com.oilquiz.app.model.Question> questions) {
                if (questions == null) {
                    questions = new ArrayList<>();
                }
                
                // 如果数据库中没有题目，添加测试题目
                if (questions.isEmpty()) {
                    questions = getMockQuestions();
                }
                
                allQuestions = questions;
                filteredQuestions = new ArrayList<>(questions);
                updateQuestionList();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(QuestionBankActivity.this, "加载题目失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    // 更新题目列表
    private void updateQuestionList() {
        if (questionListView != null) {
            if (filteredQuestions == null) {
                filteredQuestions = new ArrayList<>();
            }
            
            // 重建适配器（数据列表引用已变，必须重建）
            questionAdapter = new QuestionAdapter(this, filteredQuestions, isCardViewMode, isAnswerVisible, listener);
            questionListView.setAdapter(questionAdapter);
        }
        
        // 更新统计信息（通过适配器 header）
        updateStatistics();
        // 更新视图模式按钮状态
        updateViewModeButtons();
    }

    private final QuestionAdapter.OnQuestionClickListener listener = new QuestionAdapter.OnQuestionClickListener() {
        @Override
        public void onQuestionClick(Question question) {
            if (question != null) showQuestionDetailDialog(question);
        }

        @Override
        public void onQuestionLongClick(Question question) {
            if (question != null) showQuestionEditDialog(question);
        }

        @Override
        public void onDeleteClick(Question question) {
            if (question != null) showDeleteConfirmationDialog(question);
        }

        @Override
        public void onFavoriteClick(Question question, boolean isFavorited) {
            if (question != null) {
                questionViewModel.setQuestionFavorite(question.getId(), isFavorited, new QuestionViewModel.SetFavoriteCallback() {
                    @Override
                    public void onSuccess() {
                        question.setFavorite(isFavorited);
                        Toast.makeText(QuestionBankActivity.this, isFavorited ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                        // 刷新列表和统计
                        applyFilters();
                    }
                    @Override
                    public void onError(String error) {
                        Toast.makeText(QuestionBankActivity.this, "操作失败: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };
    
    // 更新统计信息（通过适配器 header）
    private void updateStatistics() {
        if (questionAdapter == null) return;
        if (allQuestions == null || allQuestions.isEmpty()) {
            questionAdapter.updateStats(0, 0, 0);
            return;
        }
        
        int total = allQuestions.size();
        int favoriteCount = 0;
        Set<String> categorySet = new HashSet<>();

        for (Question question : allQuestions) {
            if (question != null) {
                if (question.isFavorite()) favoriteCount++;
                String cat = question.getCategory();
                if (cat != null && !cat.isEmpty()) categorySet.add(cat);
            }
        }

        questionAdapter.updateStats(total, favoriteCount, categorySet.size());
    }

    // ========== 搜索与筛选 ==========

    /** 根据搜索框和筛选条件过滤题目 */
    private void applyFilters() {
        String keyword = (searchEditText != null) ? searchEditText.getText().toString().trim() : "";
        
        filteredQuestions = new ArrayList<>();
        for (Question q : allQuestions) {
            if (q == null) continue;
            // 分类筛选
            if (currentFilterCategory != null && !currentFilterCategory.equals(q.getCategory())) continue;
            // 题型筛选
            if (currentFilterType != null && !currentFilterType.equals(q.getQuestionType())) continue;
            // 难度筛选
            if (currentFilterDifficulty != null && currentFilterDifficulty != q.getDifficulty()) continue;
            // 关键词搜索
            if (!keyword.isEmpty()) {
                boolean match = false;
                if (q.getQuestionText() != null && q.getQuestionText().contains(keyword)) match = true;
                if (q.getCategory() != null && q.getCategory().contains(keyword)) match = true;
                if (q.getOptionA() != null && q.getOptionA().contains(keyword)) match = true;
                if (q.getOptionB() != null && q.getOptionB().contains(keyword)) match = true;
                if (q.getOptionC() != null && q.getOptionC().contains(keyword)) match = true;
                if (q.getOptionD() != null && q.getOptionD().contains(keyword)) match = true;
                if (!match) continue;
            }
            filteredQuestions.add(q);
        }
        updateQuestionList();
    }

    /** 显示筛选对话框 */
    private void showFilterDialog() {
        // 获取所有分类和题型
        List<String> categories = new ArrayList<>();
        categories.add("全部");
        Set<String> catSet = new HashSet<>();
        for (Question q : allQuestions) {
            if (q != null && q.getCategory() != null && !q.getCategory().isEmpty()) {
                catSet.add(q.getCategory());
            }
        }
        categories.addAll(catSet);

        List<String> types = new ArrayList<>();
        types.add("全部");
        types.add("单选题");
        types.add("多选题");
        types.add("判断题");
        types.add("填空题");
        types.add("简答题");

        String[] difficulties = {"全部", "简单", "中等", "困难"};

        // 找到当前选中索引
        int catIdx = currentFilterCategory == null ? 0 : Math.max(0, categories.indexOf(currentFilterCategory));
        int typeIdx = currentFilterType == null ? 0 : Math.max(0, types.indexOf(currentFilterType));
        int diffIdx = currentFilterDifficulty == null ? 0 : currentFilterDifficulty;

        final int[] selectedCat = {catIdx};
        final int[] selectedType = {typeIdx};
        final int[] selectedDiff = {diffIdx};

        new AlertDialog.Builder(this)
            .setTitle("筛选条件")
            .setView(buildFilterView(categories, types, difficulties, selectedCat, selectedType, selectedDiff))
            .setPositiveButton("确定", (dialog, which) -> {
                currentFilterCategory = selectedCat[0] == 0 ? null : categories.get(selectedCat[0]);
                currentFilterType = selectedType[0] == 0 ? null : types.get(selectedType[0]);
                currentFilterDifficulty = selectedDiff[0] == 0 ? null : selectedDiff[0];
                applyFilters();
            })
            .setNegativeButton("重置", (dialog, which) -> {
                currentFilterCategory = null;
                currentFilterType = null;
                currentFilterDifficulty = null;
                applyFilters();
            })
            .setNeutralButton("取消", null)
            .show();
    }

    private View buildFilterView(List<String> categories, List<String> types, String[] difficulties,
                                  int[] selectedCat, int[] selectedType, int[] selectedDiff) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        // 分类 Spinner
        android.widget.TextView catLabel = new android.widget.TextView(this);
        catLabel.setText("分类:");
        catLabel.setTextSize(14);
        layout.addView(catLabel);
        android.widget.Spinner catSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> catAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categories);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        catSpinner.setAdapter(catAdapter);
        catSpinner.setSelection(selectedCat[0]);
        catSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selectedCat[0] = position; }
        });
        layout.addView(catSpinner);

        // 题型 Spinner
        android.widget.TextView typeLabel = new android.widget.TextView(this);
        typeLabel.setText("题型:");
        typeLabel.setTextSize(14);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = padding / 2;
        layout.addView(typeLabel, lp);
        android.widget.Spinner typeSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> typeAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, types);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(typeAdapter);
        typeSpinner.setSelection(selectedType[0]);
        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selectedType[0] = position; }
        });
        layout.addView(typeSpinner);

        // 难度 Spinner
        android.widget.TextView diffLabel = new android.widget.TextView(this);
        diffLabel.setText("难度:");
        diffLabel.setTextSize(14);
        layout.addView(diffLabel, lp);
        android.widget.Spinner diffSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> diffAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, difficulties);
        diffAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        diffSpinner.setAdapter(diffAdapter);
        diffSpinner.setSelection(selectedDiff[0]);
        diffSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selectedDiff[0] = position; }
        });
        layout.addView(diffSpinner);

        return layout;
    }

    // ========== 题目详情/编辑 ==========

    /** 显示题目详情对话框 */
    private void showQuestionDetailDialog(Question question) {
        StringBuilder sb = new StringBuilder();
        sb.append("题型: ").append(question.getQuestionType() != null ? question.getQuestionType() : "未设置").append("\n");
        sb.append("分类: ").append(question.getCategory() != null ? question.getCategory() : "未分类").append("\n");
        sb.append("难度: ").append(question.getDifficultyText()).append("\n");
        if (question.getPoints() > 0) sb.append("分值: ").append(question.getPoints()).append("\n");
        if (question.getKnowledgePoint() != null && !question.getKnowledgePoint().isEmpty())
            sb.append("知识点: ").append(question.getKnowledgePoint()).append("\n");
        if (question.getTags() != null && !question.getTags().isEmpty())
            sb.append("标签: ").append(question.getTags()).append("\n");
        sb.append("\n").append(question.getQuestionText()).append("\n\n");
        
        // 配图提示
        if (question.hasImage()) sb.append("[有配图]\n");
        if (question.hasAudio()) sb.append("[有音频]\n");
        
        java.util.Map<String, String> options = question.getOptions();
        for (java.util.Map.Entry<String, String> entry : options.entrySet()) {
            sb.append(entry.getKey()).append(". ").append(entry.getValue()).append("\n");
        }
        
        // 答案显示：选择题显示字母，填空题/简答题显示answerText
        String normalizedType = question.getQuestionType();
        boolean isTextAnswer = "填空题".equals(normalizedType) || "简答题".equals(normalizedType)
            || "问答题".equals(normalizedType) || "论述题".equals(normalizedType);
        
        if (isTextAnswer && question.getAnswerText() != null && !question.getAnswerText().isEmpty()) {
            sb.append("\n标准答案: ").append(question.getAnswerText());
        } else {
            sb.append("\n答案: ").append(question.getCorrectAnswer() != null ? question.getCorrectAnswer() : "未设置");
        }
        
        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            sb.append("\n解析: ").append(question.getExplanation());
        }
        if (question.getAnalysis() != null && !question.getAnalysis().isEmpty()) {
            sb.append("\n详细解析: ").append(question.getAnalysis());
        }
        if (question.getSource() != null && !question.getSource().isEmpty()) {
            sb.append("\n来源: ").append(question.getSource());
        }

        new AlertDialog.Builder(this)
            .setTitle("题目详情 #" + question.getId())
            .setMessage(sb.toString())
            .setPositiveButton("编辑", (dialog, which) -> showQuestionEditDialog(question))
            .setNegativeButton("关闭", null)
            .show();
    }

    /** 显示题目编辑对话框（完整版：可修改所有字段） */
    private void showQuestionEditDialog(Question question) {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scrollView.addView(layout);

        EditText editType = new EditText(this);
        editType.setHint("题型");
        editType.setText(question.getQuestionType() != null ? question.getQuestionType() : "单选题");
        layout.addView(editType);

        EditText editText = new EditText(this);
        editText.setHint("题目内容");
        editText.setText(question.getQuestionText());
        editText.setMinLines(2);
        layout.addView(editText);

        // 动态选项容器
        java.util.List<EditText> optionEdits = new java.util.ArrayList<>();
        String[] optionLabels = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"};
        
        for (String label : optionLabels) {
            EditText editOption = new EditText(this);
            editOption.setHint("选项 " + label);
            // 填充已有选项值
            java.util.Map<String, String> options = question.getOptions();
            if (options.containsKey(label)) {
                editOption.setText(options.get(label));
            }
            layout.addView(editOption);
            optionEdits.add(editOption);
        }

        EditText editAnswer = new EditText(this);
        editAnswer.setHint("正确答案(选择题填字母如A/AB)");
        editAnswer.setText(question.getCorrectAnswer() != null ? question.getCorrectAnswer() : "");
        layout.addView(editAnswer);

        EditText editAnswerText = new EditText(this);
        editAnswerText.setHint("标准答案文本(填空题/简答题填实际答案)");
        editAnswerText.setText(question.getAnswerText() != null ? question.getAnswerText() : "");
        editAnswerText.setMinLines(2);
        layout.addView(editAnswerText);

        EditText editCategory = new EditText(this);
        editCategory.setHint("分类");
        editCategory.setText(question.getCategory() != null ? question.getCategory() : "");
        layout.addView(editCategory);

        android.widget.RadioGroup rgDifficulty = new android.widget.RadioGroup(this);
        rgDifficulty.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton rb1 = new android.widget.RadioButton(this);
        rb1.setText("简单"); rb1.setId(1);
        android.widget.RadioButton rb2 = new android.widget.RadioButton(this);
        rb2.setText("中等"); rb2.setId(2);
        android.widget.RadioButton rb3 = new android.widget.RadioButton(this);
        rb3.setText("困难"); rb3.setId(3);
        rgDifficulty.addView(rb1);
        rgDifficulty.addView(rb2);
        rgDifficulty.addView(rb3);
        rgDifficulty.check(question.getDifficulty());
        layout.addView(rgDifficulty);

        EditText editExplanation = new EditText(this);
        editExplanation.setHint("解析 (可选)");
        editExplanation.setText(question.getExplanation() != null ? question.getExplanation() : "");
        layout.addView(editExplanation);

        new AlertDialog.Builder(this)
            .setTitle("编辑题目 #" + question.getId())
            .setView(scrollView)
            .setPositiveButton("保存", (dialog, which) -> {
                String qText = editText.getText().toString().trim();
                String answer = editAnswer.getText().toString().trim();
                String stdAnswerText = editAnswerText.getText().toString().trim();
                if (qText.isEmpty()) {
                    Toast.makeText(this, "题目内容不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 选择题必须有答案字母，填空题/简答题必须有标准答案文本
                String qType = editType.getText().toString().trim();
                boolean isTextType = "填空题".equals(qType) || "简答题".equals(qType)
                    || "问答题".equals(qType) || "论述题".equals(qType);
                if (!isTextType && answer.isEmpty()) {
                    Toast.makeText(this, "选择题答案不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (isTextType && answer.isEmpty() && stdAnswerText.isEmpty()) {
                    Toast.makeText(this, "请填写标准答案", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                question.setQuestionText(qText);
                question.setQuestionType(qType);
                if (question.getQuestionType().isEmpty()) question.setQuestionType("单选题");
                
                // 动态设置选项
                java.util.Map<String, String> options = new java.util.TreeMap<>();
                for (int i = 0; i < optionEdits.size(); i++) {
                    String optionText = optionEdits.get(i).getText().toString().trim();
                    if (!optionText.isEmpty()) {
                        options.put(optionLabels[i], optionText);
                    }
                }
                question.setOptions(options);
                
                question.setCorrectAnswer(answer);
                question.setAnswerText(stdAnswerText);
                question.setCategory(editCategory.getText().toString().trim());
                question.setDifficulty(rgDifficulty.getCheckedRadioButtonId());
                question.setExplanation(editExplanation.getText().toString().trim());
                question.touch();
                
                questionViewModel.updateQuestion(question, new QuestionViewModel.UpdateQuestionCallback() {
                    @Override public void onSuccess() {
                        Toast.makeText(QuestionBankActivity.this, "保存成功", Toast.LENGTH_SHORT).show();
                        loadQuestions();
                    }
                    @Override public void onError(String error) {
                        Toast.makeText(QuestionBankActivity.this, "保存失败: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // 生成测试题目
    private List<Question> getMockQuestions() {
        List<Question> questions = new ArrayList<>();

        // 单选题
        Question q1 = new Question();
        q1.setQuestionText("下列哪个是正确的？");
        q1.setOptionA("选项A");
        q1.setOptionB("选项B");
        q1.setOptionC("选项C");
        q1.setOptionD("选项D");
        q1.setQuestionType("单选题");
        q1.setDifficulty(2);
        q1.setCategory("测试分类");
        q1.setCorrectAnswer("A");
        questions.add(q1);

        // 多选题
        Question q2 = new Question();
        q2.setQuestionText("下列哪些是正确的？");
        q2.setOptionA("选项A");
        q2.setOptionB("选项B");
        q2.setOptionC("选项C");
        q2.setOptionD("选项D");
        q2.setQuestionType("多选题");
        q2.setDifficulty(2);
        q2.setCategory("测试分类");
        q2.setCorrectAnswer("AB");
        questions.add(q2);

        // 判断题
        Question q3 = new Question();
        q3.setQuestionText("这是一个判断题。");
        q3.setOptionA("正确");
        q3.setOptionB("错误");
        q3.setQuestionType("判断题");
        q3.setDifficulty(1);
        q3.setCategory("测试分类");
        q3.setCorrectAnswer("正确");
        questions.add(q3);

        // 填空题
        Question q4 = new Question();
        q4.setQuestionText("填空题：请填写答案");
        q4.setQuestionType("填空题");
        q4.setDifficulty(2);
        q4.setCategory("测试分类");
        q4.setCorrectAnswer("答案");
        questions.add(q4);

        // 简答题
        Question q5 = new Question();
        q5.setQuestionText("简答题：请简要回答");
        q5.setQuestionType("简答题");
        q5.setDifficulty(3);
        q5.setCategory("测试分类");
        q5.setCorrectAnswer("这是简答题的答案");
        questions.add(q5);

        return questions;
    }



    private void showDeleteConfirmationDialog(final Question question) {
        new AlertDialog.Builder(this)
                .setTitle("删除题目")
                .setMessage("确定要删除这道题目吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    questionViewModel.deleteQuestion(question.getId(), new QuestionViewModel.DeleteQuestionCallback() {
                        @Override
                        public void onSuccess() {
                            Toast.makeText(QuestionBankActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                            loadQuestions(); // 重新加载题目列表
                        }

                        @Override
                        public void onError(String error) {
                            Toast.makeText(QuestionBankActivity.this, "删除失败: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importQuestions() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }

    private void exportQuestions() {
        // 显示导出选项对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导出题目");
        builder.setItems(new String[]{"导出为Excel", "导出为PDF", "导出为Word", "导出为HTML", "导出为Markdown"}, (dialog, which) -> {
            // 显示进度对话框
            AlertDialog progressDialog = new AlertDialog.Builder(QuestionBankActivity.this)
                    .setTitle("导出中")
                    .setMessage("正在准备导出，请稍候...")
                    .setCancelable(false)
                    .create();
            progressDialog.show();
            
            questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
                @Override
                public void onSuccess(java.util.List<com.oilquiz.app.model.Question> questions) {
                    if (questions.isEmpty()) {
                        progressDialog.dismiss();
                        Toast.makeText(QuestionBankActivity.this, "没有题目可以导出", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 在后台线程执行导出
                    new Thread(() -> {
                        try {
                            File file = null;
                            String exportType = "";
                            switch (which) {
                                case 0: // Excel
                                    file = exportManager.exportToExcel(QuestionBankActivity.this, questions);
                                    exportType = "Excel";
                                    break;
                                case 1: // PDF
                                    file = exportManager.exportToPDF(QuestionBankActivity.this, questions);
                                    exportType = "PDF";
                                    break;
                                case 2: // Word
                                    file = exportManager.exportToWord(QuestionBankActivity.this, questions);
                                    exportType = "Word";
                                    break;
                                case 3: // HTML
                                    file = exportManager.exportToHTML(QuestionBankActivity.this, questions);
                                    exportType = "HTML";
                                    break;
                                case 4: // Markdown
                                    file = exportManager.exportToMarkdown(QuestionBankActivity.this, questions);
                                    exportType = "Markdown";
                                    break;
                            }
                            
                            final File finalFile = file;
                            final String finalExportType = exportType;
                            
                            runOnUiThread(() -> {
                                progressDialog.dismiss();
                                // 导出线程回调时页面可能已销毁：避免弹窗崩溃
                                if (isFinishing() || isDestroyed()) return;
                                if (finalFile != null && finalFile.exists()) {
                                    // 复制到公共「下载/OilQuiz」目录，保证文件管理器可见可编辑
                                    String savedPath = ExportFileSaver.copyToDownloads(
                                            QuestionBankActivity.this, finalFile,
                                            getMimeType(finalFile.getAbsolutePath()));
                                    showExportSuccessDialog(finalFile, finalExportType, savedPath);
                                } else {
                                    Toast.makeText(QuestionBankActivity.this, "导出失败: 无法创建文件", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> {
                                progressDialog.dismiss();
                                Toast.makeText(QuestionBankActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    }).start();
                }

                @Override
                public void onError(String error) {
                    progressDialog.dismiss();
                    Toast.makeText(QuestionBankActivity.this, "获取题目失败: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        });
        builder.show();
    }
    
    // 显示导出成功对话框
    private void showExportSuccessDialog(File file, String exportType, String savedPath) {
        new AlertDialog.Builder(this)
                .setTitle("导出成功")
                .setMessage("文件类型: " + exportType + "\n保存位置: "
                        + (savedPath != null ? savedPath : file.getAbsolutePath())
                        + "\n文件大小: " + (file.length() / 1024) + " KB")
                .setPositiveButton("查看文件", (dialog, which) -> openExportFile(file))
                .setNegativeButton("分享文件", (dialog, which) -> shareExportFile(file))
                .setNeutralButton("确定", null)
                .show();
    }
    
    // 打开导出的文件
    private void openExportFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "com.oilquiz.app.fileprovider",
                        file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
            }
            
            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setDataAndType(uri, mimeType);
            
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "选择打开方式"));
            } else {
                Toast.makeText(this, "没有找到可以打开此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // 分享导出的文件
    private void shareExportFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            Uri uri;
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "com.oilquiz.app.fileprovider",
                        file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
            }
            
            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "导出文件");
            intent.putExtra(Intent.EXTRA_TEXT, "这是从OilQuiz应用导出的文件：" + file.getName());
            
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "分享文件"));
            } else {
                Toast.makeText(this, "没有找到可以分享此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法分享文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // 获取文件MIME类型
    private String getMimeType(String filePath) {
        String extension = filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "html":
                return "text/html";
            case "md":
                return "text/markdown";
            default:
                return "application/octet-stream";
        }
    }
    
    // 批量删除题目
    private void batchDeleteQuestions() {
        if (filteredQuestions == null || filteredQuestions.isEmpty()) {
            Toast.makeText(this, "没有题目可以删除", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定要删除所有筛选后的题目吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        int deleteCount = 0;
                        for (Question question : filteredQuestions) {
                            if (question != null) {
                                questionViewModel.deleteQuestion(question.getId(), new QuestionViewModel.DeleteQuestionCallback() {
                                    @Override
                                    public void onSuccess() {
                                        // 可以添加成功回调处理
                                    }

                                    @Override
                                    public void onError(String error) {
                                        // 可以添加错误回调处理
                                    }
                                });
                                deleteCount++;
                            }
                        }
                        Toast.makeText(this, "删除成功，共删除 " + deleteCount + " 道题目", Toast.LENGTH_SHORT).show();
                        loadQuestions(); // 重新加载题目列表
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    // 批量收藏题目
    private void batchFavoriteQuestions() {
        if (filteredQuestions == null || filteredQuestions.isEmpty()) {
            Toast.makeText(this, "没有题目可以收藏", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("批量收藏")
                .setMessage("确定要收藏所有筛选后的题目吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        int favoriteCount = 0;
                        for (Question question : filteredQuestions) {
                            if (question != null) {
                                questionViewModel.setQuestionFavorite(question.getId(), true);
                                favoriteCount++;
                            }
                        }
                        Toast.makeText(this, "收藏成功，共收藏 " + favoriteCount + " 道题目", Toast.LENGTH_SHORT).show();
                        loadQuestions(); // 重新加载题目列表
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "收藏失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /** 显示添加题目对话框 */
    private void showAddQuestionDialog() {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scrollView.addView(layout);

        EditText editType = new EditText(this);
        editType.setHint("题型 (单选题/多选题/判断题/填空题/简答题)");
        layout.addView(editType);

        EditText editText = new EditText(this);
        editText.setHint("题目内容");
        editText.setMinLines(2);
        layout.addView(editText);

        // 动态选项容器
        java.util.List<EditText> optionEdits = new java.util.ArrayList<>();
        String[] optionLabels = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"};
        
        for (String label : optionLabels) {
            EditText editOption = new EditText(this);
            editOption.setHint("选项 " + label);
            layout.addView(editOption);
            optionEdits.add(editOption);
        }

        EditText editAnswer = new EditText(this);
        editAnswer.setHint("正确答案 (如: A 或 AB 或 正确)");
        layout.addView(editAnswer);

        EditText editCategory = new EditText(this);
        editCategory.setHint("分类");
        layout.addView(editCategory);

        android.widget.RadioGroup rgDifficulty = new android.widget.RadioGroup(this);
        rgDifficulty.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton rb1 = new android.widget.RadioButton(this);
        rb1.setText("简单"); rb1.setId(1);
        android.widget.RadioButton rb2 = new android.widget.RadioButton(this);
        rb2.setText("中等"); rb2.setId(2);
        android.widget.RadioButton rb3 = new android.widget.RadioButton(this);
        rb3.setText("困难"); rb3.setId(3);
        rgDifficulty.addView(rb1);
        rgDifficulty.addView(rb2);
        rgDifficulty.addView(rb3);
        rgDifficulty.check(2); // 默认中等
        layout.addView(rgDifficulty);

        EditText editExplanation = new EditText(this);
        editExplanation.setHint("解析 (可选)");
        layout.addView(editExplanation);

        new AlertDialog.Builder(this)
            .setTitle("添加题目")
            .setView(scrollView)
            .setPositiveButton("保存", (dialog, which) -> {
                String qText = editText.getText().toString().trim();
                String answer = editAnswer.getText().toString().trim();
                if (qText.isEmpty() || answer.isEmpty()) {
                    Toast.makeText(this, "题目内容和答案不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                Question q = new Question();
                q.setQuestionText(qText);
                q.setQuestionType(editType.getText().toString().trim());
                if (q.getQuestionType().isEmpty()) q.setQuestionType("单选题");
                
                // 动态设置选项（只设置有内容的选项）
                java.util.Map<String, String> options = new java.util.TreeMap<>();
                for (int i = 0; i < optionEdits.size(); i++) {
                    String optionText = optionEdits.get(i).getText().toString().trim();
                    if (!optionText.isEmpty()) {
                        options.put(optionLabels[i], optionText);
                    }
                }
                q.setOptions(options);
                
                q.setCorrectAnswer(answer);
                q.setCategory(editCategory.getText().toString().trim());
                q.setDifficulty(rgDifficulty.getCheckedRadioButtonId());
                q.setExplanation(editExplanation.getText().toString().trim());
                q.initDefaults();

                questionViewModel.addQuestion(q, new QuestionViewModel.AddQuestionCallback() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(QuestionBankActivity.this, "添加成功", Toast.LENGTH_SHORT).show();
                        loadQuestions();
                    }
                    @Override
                    public void onError(String error) {
                        Toast.makeText(QuestionBankActivity.this, "添加失败: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // 生成题目模板
    private void generateQuestionTemplate() {
        try {
            if (exportManager != null) {
                File templateFile = exportManager.generateExcelTemplate(this);
                if (templateFile != null && templateFile.exists()) {
                    Toast.makeText(this, "模板生成成功，文件保存在: " + templateFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "生成模板失败: 无法创建文件", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "生成模板失败: 导出管理器未初始化", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "生成模板失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    // 处理导入逻辑
                    importQuestionsFromFile(uri);
                }
            }
        }
    }

    private void importQuestionsFromFile(Uri uri) {
        if (uri == null) {
            Toast.makeText(this, "文件Uri为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // 从Uri获取文件路径
            String filePath = uri.getPath();
            if (filePath == null) {
                Toast.makeText(this, "无法获取文件路径", Toast.LENGTH_SHORT).show();
                return;
            }
            
            File file = new File(filePath);
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            
            Toast.makeText(this, "开始导入题目...", Toast.LENGTH_SHORT).show();
            
            // 使用ExcelUtil进行智能导入
            ExcelUtil.smartImport(file, new ExcelUtil.ImportCallback() {
                @Override
                public void onProgress(int current, int total) {
                    runOnUiThread(() -> {
                        // 可以在这里显示导入进度
                        Toast.makeText(QuestionBankActivity.this, "导入进度: " + current + "/" + total, Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(QuestionBankActivity.this, "导入失败: " + message, Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onComplete(java.util.List<Question> importedQuestions, ExcelUtil.ImportResult result) {
                    runOnUiThread(() -> {
                        // 保存导入的题目到数据库
                        if (importedQuestions != null) {
                            for (Question question : importedQuestions) {
                                if (question != null) {
                                    questionViewModel.insertQuestion(question);
                                }
                            }
                            
                            Toast.makeText(QuestionBankActivity.this, "导入成功! 导入了 " + result.validQuestions + " 道题目", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(QuestionBankActivity.this, "导入失败: 没有有效的题目", Toast.LENGTH_SHORT).show();
                        }
                        loadQuestions(); // 重新加载题目列表
                    });
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.question_bank_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_question) {
            showAddQuestionDialog();
            return true;
        } else if (id == R.id.action_start_quiz) {
            startQuiz();
            return true;
        } else if (id == R.id.action_toggle_answer) {
            toggleAnswerVisibility();
            return true;
        } else if (id == R.id.action_import_questions) {
            importQuestions();
            return true;
        } else if (id == R.id.action_export_questions) {
            exportQuestions();
            return true;
        } else if (id == R.id.action_batch_delete) {
            batchDeleteQuestions();
            return true;
        } else if (id == R.id.action_batch_favorite) {
            batchFavoriteQuestions();
            return true;
        } else if (id == R.id.action_generate_template) {
            generateQuestionTemplate();
            return true;
        } else if (id == R.id.action_refresh) {
            loadQuestions();
            return true;
        } else if (id == R.id.action_clear_all) {
            clearAllQuestions();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // 切换视图模式
    private void toggleViewMode() {
        isCardViewMode = !isCardViewMode;
        loadQuestions();
        invalidateOptionsMenu();
    }
    
    // 切换答案显示/隐藏
    private void toggleAnswerVisibility() {
        isAnswerVisible = !isAnswerVisible;
        loadQuestions();
        invalidateOptionsMenu();
    }
    
    // 开始测验（跳转到模式选择页）
    private void startQuiz() {
        Intent intent = new Intent(this, StartQuizActivity.class);
        startActivity(intent);
    }

    // 清空所有题目
    private void clearAllQuestions() {
        new AlertDialog.Builder(this)
                .setTitle("清空数据库")
                .setMessage("确定要清空数据库中的所有题目吗？此操作不可恢复！")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        questionViewModel.clearAllQuestions(new QuestionViewModel.BatchOperationCallback() {
                            @Override
                            public void onSuccess(int count) {
                                Toast.makeText(QuestionBankActivity.this, "数据库已清空，共删除 " + count + " 道题目", Toast.LENGTH_SHORT).show();
                                loadQuestions(); // 重新加载题目列表
                            }

                            @Override
                            public void onError(String error) {
                                Toast.makeText(QuestionBankActivity.this, "清空失败: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "清空失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadQuestions();
    }
}
