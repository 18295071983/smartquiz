package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.adapter.QuestionAdapter;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.database.DatabaseManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class QuestionGenerateActivity extends AppCompatActivity {

    private TextInputEditText etTopic;
    private TextInputEditText etQuestionCount;
    private Spinner difficultySpinner;
    private Spinner typeSpinner;
    private MaterialButton btnGenerate;
    private LinearLayout resultContainer;
    private RecyclerView questionsRecycler;
    private LinearLayout actionsContainer;
    private MaterialButton btnSave;
    private MaterialButton btnShare;
    private MaterialButton btnExport;
    private View loadingLayout;
    private TextView loadingMessage;
    private TextView loadingSubmessage;
    private MaterialButton btnCancel;
    private CompletableFuture<?> currentTask;
    
    private List<Question> generatedQuestions;
    private QuestionAdapter questionAdapter;
    private AIService aiService;
    private List<List<Question>> historyList;
    private List<String> historyTitles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_generate);

        initViews();
        setupSpinners();
        setupClickListeners();
        
        // 初始化历史记录
        historyList = new ArrayList<>();
        historyTitles = new ArrayList<>();
    }

    private void initViews() {
        etTopic = findViewById(R.id.et_topic);
        etQuestionCount = findViewById(R.id.et_question_count);
        difficultySpinner = findViewById(R.id.difficulty_spinner);
        typeSpinner = findViewById(R.id.type_spinner);
        btnGenerate = findViewById(R.id.btn_generate_questions);
        resultContainer = findViewById(R.id.result_container);
        questionsRecycler = findViewById(R.id.rv_generated_questions);
        actionsContainer = findViewById(R.id.actions_container);
        btnSave = findViewById(R.id.btn_save);
        btnShare = findViewById(R.id.btn_share);
        btnExport = findViewById(R.id.btn_export);
        
        // 初始化AIService
        aiService = AIService.getInstance(this);
        
        // 初始化加载动画
        loadingLayout = getLayoutInflater().inflate(R.layout.layout_loading, null);
        addContentView(loadingLayout, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadingLayout.setVisibility(View.GONE);
        
        // 初始化加载动画中的视图
        loadingMessage = loadingLayout.findViewById(R.id.loading_message);
        loadingSubmessage = loadingLayout.findViewById(R.id.loading_submessage);
        btnCancel = loadingLayout.findViewById(R.id.btn_cancel);
        
        // 设置取消按钮点击事件
        btnCancel.setOnClickListener(v -> {
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(true);
                loadingLayout.setVisibility(View.GONE);
                Toast.makeText(QuestionGenerateActivity.this, "操作已取消", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupSpinners() {
        // 设置难度选项
        String[] difficultyOptions = {"简单", "中等", "困难"};
        setupSpinner(difficultySpinner, difficultyOptions);

        // 设置题目类型选项
        String[] typeOptions = {"选择题", "判断题", "简答题", "论述题"};
        setupSpinner(typeSpinner, typeOptions);
    }

    private void setupSpinner(Spinner spinner, String[] options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void setupClickListeners() {
        btnGenerate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateQuestions();
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveQuestions();
            }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareQuestions();
            }
        });

        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportQuestions();
            }
        });
    }

    private void generateQuestions() {
        // 1. 获取输入参数
        final String topic = etTopic.getText().toString().trim();
        final String countStr = etQuestionCount.getText().toString().trim();
        final String difficulty = difficultySpinner.getSelectedItem().toString();
        final String type = typeSpinner.getSelectedItem().toString();

        if (topic.isEmpty() || countStr.isEmpty()) {
            // 显示错误提示
            return;
        }

        int count = Integer.parseInt(countStr);

        // 2. 构建提示词
        String prompt = String.format(
                "Generate %d %s difficulty %s questions about %s. Each question should include:\n" +
                "- Question text\n" +
                "- Multiple choice options (4 options)\n" +
                "- Correct answer\n" +
                "- Explanation\n",
                count, difficulty, type, topic
        );

        // 3. 调用AI服务生成题目
        if (!aiService.isInitialized()) {
            if (!aiService.initializeSafe()) {
                Toast.makeText(this, "AI服务初始化失败，请先导入模型", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 更新加载消息
        loadingMessage.setText("生成题目中");
        loadingSubmessage.setText("正在根据您的需求生成题目，请稍候...");
        loadingLayout.setVisibility(View.VISIBLE);

        // 异步生成题目
        currentTask = aiService.generateAsync(prompt, 2000).thenAccept(result -> runOnUiThread(() -> {
            // 隐藏加载状态
            loadingLayout.setVisibility(View.GONE);
            // 4. 解析并显示结果
            generatedQuestions = parseGeneratedQuestions(result, topic, difficulty, type);
            if (result != null && result.startsWith("Error:")) {
                // 处理AI服务错误
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            } else if (generatedQuestions != null && !generatedQuestions.isEmpty()) {
                resultContainer.setVisibility(View.VISIBLE);
                actionsContainer.setVisibility(View.VISIBLE);
                setupRecyclerView();
                
                // 保存到历史记录
                saveToHistory(generatedQuestions, topic, difficulty, type);
                
                Toast.makeText(this, "题目生成成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "无法解析生成的题目，请重试", Toast.LENGTH_SHORT).show();
            }
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                // 隐藏加载状态
                loadingLayout.setVisibility(View.GONE);
                // 显示错误信息
                Log.e("QuestionGenerate", "Error generating questions", throwable);
                Toast.makeText(this, "生成题目时出错: " + throwable.getMessage(), Toast.LENGTH_SHORT).show();
            });
            return null;
        });
    }


    
    /**
     * 解析AI生成的题目
     */
    private List<Question> parseGeneratedQuestions(String aiResult, String topic, String difficulty, String type) {
        List<Question> questions = new ArrayList<>();
        try {
            // 简单的解析逻辑，根据生成的格式进行解析
            // 假设生成的格式为：
            // 1. Question: ...
            //    Options:
            //    A. ...
            //    B. ...
            //    C. ...
            //    D. ...
            //    Correct Answer: A
            //    Explanation: ...
            
            String[] questionBlocks = aiResult.split("\n\n");
            for (String block : questionBlocks) {
                if (block.contains("Question:")) {
                    Question question = new Question();
                    question.setCategory(topic);
                    question.setDifficulty(getDifficultyValue(difficulty));
                    question.setQuestionType(type);
                    
                    // 解析题目内容
                    String[] lines = block.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.startsWith("Question:")) {
                            question.setQuestionText(line.substring(9).trim());
                        } else if (line.startsWith("Options:")) {
                            // 解析选项
                            for (int j = i + 1; j < lines.length; j++) {
                                String optionLine = lines[j].trim();
                                if (optionLine.startsWith("A.")) {
                                    question.setOptionA(optionLine.substring(3).trim());
                                } else if (optionLine.startsWith("B.")) {
                                    question.setOptionB(optionLine.substring(3).trim());
                                } else if (optionLine.startsWith("C.")) {
                                    question.setOptionC(optionLine.substring(3).trim());
                                } else if (optionLine.startsWith("D.")) {
                                    question.setOptionD(optionLine.substring(3).trim());
                                } else if (optionLine.startsWith("Correct Answer:")) {
                                    question.setCorrectAnswer(optionLine.substring(15).trim());
                                } else if (optionLine.startsWith("Explanation:")) {
                                    question.setExplanation(optionLine.substring(12).trim());
                                } else if (optionLine.isEmpty()) {
                                    break;
                                }
                            }
                        }
                    }
                    
                    // 只有当题目内容和答案都不为空时才添加
                    if (question.getQuestionText() != null && !question.getQuestionText().isEmpty() &&
                        question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                        questions.add(question);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("QuestionGenerate", "Error parsing generated questions", e);
        }
        return questions;
    }
    
    /**
     * 将难度字符串转换为数值
     */
    private int getDifficultyValue(String difficulty) {
        switch (difficulty) {
            case "简单":
                return 1;
            case "中等":
                return 2;
            case "困难":
                return 3;
            default:
                return 2;
        }
    }
    
    /**
     * 设置RecyclerView显示题目
     */
    private void setupRecyclerView() {
        questionsRecycler.setLayoutManager(new LinearLayoutManager(this));
        questionAdapter = new QuestionAdapter(this, generatedQuestions, true, true, new QuestionAdapter.OnQuestionClickListener() {
            @Override
            public void onQuestionClick(Question question) {
                // 点击题目时的处理
            }
            
            @Override
            public void onQuestionLongClick(Question question) {
                // 长按题目时的处理
            }
            
            @Override
            public void onDeleteClick(Question question) {
                // 删除题目时的处理
                generatedQuestions.remove(question);
                questionAdapter.notifyDataSetChanged();
            }
            
            @Override
            public void onFavoriteClick(Question question, boolean isFavorited) {
                // 收藏题目时的处理
                question.setFavorite(isFavorited);
                questionAdapter.notifyDataSetChanged();
            }
        });
        questionsRecycler.setAdapter(questionAdapter);
    }
    
    /**
     * 保存题目到数据库
     */
    private void saveQuestions() {
        if (generatedQuestions == null || generatedQuestions.isEmpty()) {
            Toast.makeText(this, "没有可保存的题目", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            DatabaseManager databaseManager = DatabaseManager.getInstance(this);
            boolean success = databaseManager.addQuestions(generatedQuestions).get();
            if (success) {
                Toast.makeText(this, "成功保存 " + generatedQuestions.size() + " 道题目", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存题目失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e("QuestionGenerate", "Error saving questions", e);
            Toast.makeText(this, "保存题目时出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 分享题目
     */
    private void shareQuestions() {
        if (generatedQuestions == null || generatedQuestions.isEmpty()) {
            Toast.makeText(this, "没有可分享的题目", Toast.LENGTH_SHORT).show();
            return;
        }
        
        StringBuilder shareContent = new StringBuilder();
        shareContent.append("生成的题目:\n\n");
        
        for (int i = 0; i < generatedQuestions.size(); i++) {
            Question question = generatedQuestions.get(i);
            shareContent.append((i + 1)).append(". " + question.getQuestionText()).append("\n");
            
            // 添加选项
            if (question.getOptionA() != null) {
                shareContent.append("A. " + question.getOptionA()).append("\n");
            }
            if (question.getOptionB() != null) {
                shareContent.append("B. " + question.getOptionB()).append("\n");
            }
            if (question.getOptionC() != null) {
                shareContent.append("C. " + question.getOptionC()).append("\n");
            }
            if (question.getOptionD() != null) {
                shareContent.append("D. " + question.getOptionD()).append("\n");
            }
            
            shareContent.append("答案: " + question.getCorrectAnswer()).append("\n");
            if (question.getExplanation() != null) {
                shareContent.append("解析: " + question.getExplanation()).append("\n");
            }
            shareContent.append("\n");
        }
        
        // 创建分享Intent
        android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, shareContent.toString());
        shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "生成的题目");
        startActivity(android.content.Intent.createChooser(shareIntent, "分享题目"));
    }
    
    /**
     * 保存生成的题目到历史记录
     */
    private void saveToHistory(List<Question> questions, String topic, String difficulty, String type) {
        // 限制历史记录数量为10条
        if (historyList.size() >= 10) {
            historyList.remove(0);
            historyTitles.remove(0);
        }
        
        // 创建历史记录标题
        String title = topic + " (" + difficulty + " " + type + ") " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
        
        // 保存到历史记录
        historyList.add(new ArrayList<>(questions));
        historyTitles.add(title);
    }
    
    /**
     * 导出题目为文本文件
     */
    private void exportQuestions() {
        if (generatedQuestions == null || generatedQuestions.isEmpty()) {
            Toast.makeText(this, "没有可导出的题目", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // 创建导出内容
            StringBuilder exportContent = new StringBuilder();
            exportContent.append("# 生成的题目\n\n");
            
            for (int i = 0; i < generatedQuestions.size(); i++) {
                Question question = generatedQuestions.get(i);
                exportContent.append("## 题目 " + (i + 1) + "\n");
                exportContent.append("**题目**: " + question.getQuestionText() + "\n");
                
                // 添加选项
                if (question.getOptionA() != null) {
                    exportContent.append("**A**: " + question.getOptionA() + "\n");
                }
                if (question.getOptionB() != null) {
                    exportContent.append("**B**: " + question.getOptionB() + "\n");
                }
                if (question.getOptionC() != null) {
                    exportContent.append("**C**: " + question.getOptionC() + "\n");
                }
                if (question.getOptionD() != null) {
                    exportContent.append("**D**: " + question.getOptionD() + "\n");
                }
                
                exportContent.append("**答案**: " + question.getCorrectAnswer() + "\n");
                if (question.getExplanation() != null) {
                    exportContent.append("**解析**: " + question.getExplanation() + "\n");
                }
                exportContent.append("\n");
            }
            
            // 创建文件
            java.io.File exportDir = new java.io.File(getExternalFilesDir(null), "exports");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }
            
            String fileName = "questions_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".txt";
            java.io.File exportFile = new java.io.File(exportDir, fileName);
            
            // 写入文件
            java.io.FileWriter writer = new java.io.FileWriter(exportFile);
            writer.write(exportContent.toString());
            writer.close();
            
            // 提示用户
            Toast.makeText(this, "题目已导出到: " + exportFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            
            // 分享文件
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.fromFile(exportFile));
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "导出的题目");
            startActivity(android.content.Intent.createChooser(shareIntent, "分享导出文件"));
        } catch (Exception e) {
            Log.e("QuestionGenerate", "Error exporting questions", e);
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
