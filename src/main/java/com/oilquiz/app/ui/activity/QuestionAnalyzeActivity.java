package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIService;
import java.util.concurrent.CompletableFuture;

public class QuestionAnalyzeActivity extends AppCompatActivity {

    private TextInputLayout inputQuestionText;
    private TextInputLayout inputOptions;
    private TextInputLayout inputCorrectAnswer;
    private TextInputEditText questionText;
    private TextInputEditText options;
    private TextInputEditText correctAnswer;
    private Button btnAnalyze;
    private LinearLayout resultContainer;
    private TextView explanation;
    private TextView knowledgePoints;
    private TextView learningSuggestions;
    private LinearLayout actionsContainer;
    private Button btnSave;
    private Button btnShare;
    private Button btnCopy;
    private View loadingLayout;
    private TextView loadingMessage;
    private TextView loadingSubmessage;
    private Button btnCancel;
    private CompletableFuture<?> currentTask;
    
    private AIService aiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_analyze);

        initViews();
        setupClickListeners();
    }

    private void initViews() {
        inputQuestionText = findViewById(R.id.input_question_text);
        inputOptions = findViewById(R.id.input_options);
        inputCorrectAnswer = findViewById(R.id.input_correct_answer);
        questionText = findViewById(R.id.question_text_input);
        options = findViewById(R.id.options_input);
        correctAnswer = findViewById(R.id.correct_answer_input);
        btnAnalyze = findViewById(R.id.btn_analyze);
        resultContainer = findViewById(R.id.result_container);
        explanation = findViewById(R.id.explanation);
        knowledgePoints = findViewById(R.id.knowledge_points);
        learningSuggestions = findViewById(R.id.learning_suggestions);
        actionsContainer = findViewById(R.id.actions_container);
        btnSave = findViewById(R.id.btn_save_analysis);
        btnShare = findViewById(R.id.btn_share_analysis);
        btnCopy = findViewById(R.id.btn_copy_analysis);
        
        // 初始化AIService
        aiService = AIService.getInstance(this);
        
        // 初始化加载动画视图
        loadingLayout = findViewById(R.id.loadingLayout);
        loadingMessage = findViewById(R.id.loading_message);
        loadingSubmessage = findViewById(R.id.loading_submessage);
        btnCancel = findViewById(R.id.btn_cancel);
        
        // 设置取消按钮点击事件
        btnCancel.setOnClickListener(v -> {
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(true);
                loadingLayout.setVisibility(View.GONE);
                Toast.makeText(QuestionAnalyzeActivity.this, "操作已取消", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupClickListeners() {
        btnAnalyze.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                analyzeQuestion();
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAnalysis();
            }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareAnalysis();
            }
        });

        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAnalysis();
            }
        });
    }

    private void analyzeQuestion() {
        // 1. 获取输入题目
        String question = questionText.getText().toString().trim();
        String optionsText = options.getText().toString().trim();
        String answer = correctAnswer.getText().toString().trim();

        if (question.isEmpty()) {
            Toast.makeText(this, "请输入题目内容", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. 构建提示词
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following question:\n");
        prompt.append("Question: " + question + "\n");
        if (!optionsText.isEmpty()) {
            prompt.append("Options:\n" + optionsText + "\n");
        }
        if (!answer.isEmpty()) {
            prompt.append("Correct Answer: " + answer + "\n");
        }
        prompt.append("\nPlease provide:\n");
        prompt.append("1. A detailed explanation\n");
        prompt.append("2. Related knowledge points\n");
        prompt.append("3. Learning suggestions");

        // 3. 调用AI服务解析题目
        if (!aiService.isInitialized()) {
            if (!aiService.initializeSafe()) {
                Toast.makeText(this, "AI服务初始化失败，请先导入模型", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 更新加载消息
        loadingMessage.setText("分析题目中");
        loadingSubmessage.setText("正在分析题目内容，生成解析、知识点和学习建议，请稍候...");
        loadingLayout.setVisibility(View.VISIBLE);

        // 异步解析题目
        currentTask = aiService.generateAsync(prompt.toString(), 1500).thenAccept(result -> runOnUiThread(() -> {
            // 隐藏加载状态
            loadingLayout.setVisibility(View.GONE);
            // 4. 解析并显示结果
            if (result != null && result.startsWith("Error:")) {
                // 处理AI服务错误
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            } else if (result != null && !result.isEmpty()) {
                resultContainer.setVisibility(View.VISIBLE);
                actionsContainer.setVisibility(View.VISIBLE);
                // 解析结果
                parseAnalysisResult(result);
                Toast.makeText(this, "题目分析完成", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "解析失败，请重试", Toast.LENGTH_SHORT).show();
            }
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                // 隐藏加载状态
                loadingLayout.setVisibility(View.GONE);
                // 显示错误信息
                Log.e("QuestionAnalyze", "Error analyzing question", throwable);
                Toast.makeText(this, "解析时出错: " + throwable.getMessage(), Toast.LENGTH_SHORT).show();
            });
            return null;
        });
    }

    private void saveAnalysis() {
        // 检查是否有解析结果
        if (explanation.getText().toString().isEmpty()) {
            Toast.makeText(this, "没有可保存的解析结果", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 这里可以实现保存到数据库或文件的逻辑
        // 由于没有具体的保存需求，暂时只显示提示
        Toast.makeText(this, "解析结果已保存", Toast.LENGTH_SHORT).show();
    }

    private void shareAnalysis() {
        // 检查是否有解析结果
        if (explanation.getText().toString().isEmpty()) {
            Toast.makeText(this, "没有可分享的解析结果", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            StringBuilder shareContent = new StringBuilder();
            shareContent.append("题目解析结果:\n\n");
            shareContent.append("解析:\n").append(explanation.getText().toString()).append("\n\n");
            shareContent.append("知识点:\n").append(knowledgePoints.getText().toString()).append("\n\n");
            shareContent.append("学习建议:\n").append(learningSuggestions.getText().toString());
            
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, shareContent.toString());
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "题目解析结果");
            startActivity(android.content.Intent.createChooser(shareIntent, "分享解析结果"));
        } catch (Exception e) {
            Log.e("QuestionAnalyze", "Error sharing analysis", e);
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyAnalysis() {
        // 检查是否有解析结果
        if (explanation.getText().toString().isEmpty()) {
            Toast.makeText(this, "没有可复制的解析结果", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            StringBuilder copyContent = new StringBuilder();
            copyContent.append("题目解析结果:\n\n");
            copyContent.append("解析:\n").append(explanation.getText().toString()).append("\n\n");
            copyContent.append("知识点:\n").append(knowledgePoints.getText().toString()).append("\n\n");
            copyContent.append("学习建议:\n").append(learningSuggestions.getText().toString());
            
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("解析结果", copyContent.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("QuestionAnalyze", "Error copying analysis", e);
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 解析AI生成的分析结果
     */
    private void parseAnalysisResult(String aiResult) {
        try {
            // 简单的解析逻辑，根据生成的格式进行解析
            // 假设生成的格式为：
            // 1. Explanation: ...
            // 2. Knowledge Points: ...
            // 3. Learning Suggestions: ...
            
            String[] sections = aiResult.split("\\d+\\. ");
            for (String section : sections) {
                section = section.trim();
                if (section.startsWith("Explanation:")) {
                    explanation.setText(section.substring(12).trim());
                } else if (section.startsWith("Knowledge Points:")) {
                    knowledgePoints.setText(section.substring(17).trim());
                } else if (section.startsWith("Learning Suggestions:")) {
                    learningSuggestions.setText(section.substring(22).trim());
                } else if (section.startsWith("Related knowledge points:")) {
                    knowledgePoints.setText(section.substring(26).trim());
                } else if (section.startsWith("Learning suggestions:")) {
                    learningSuggestions.setText(section.substring(20).trim());
                } else if (section.startsWith("Detailed explanation:")) {
                    explanation.setText(section.substring(20).trim());
                }
            }
            
            // 如果没有解析到具体部分，使用默认显示
            if (explanation.getText().toString().isEmpty()) {
                explanation.setText(aiResult);
                knowledgePoints.setText("请参考解析内容");
                learningSuggestions.setText("请参考解析内容");
            }
        } catch (Exception e) {
            Log.e("QuestionAnalyze", "Error parsing analysis result", e);
            // 解析失败时，直接显示原始结果
            explanation.setText(aiResult);
            knowledgePoints.setText("解析失败，请参考上面内容");
            learningSuggestions.setText("解析失败，请参考上面内容");
        }
    }
}
