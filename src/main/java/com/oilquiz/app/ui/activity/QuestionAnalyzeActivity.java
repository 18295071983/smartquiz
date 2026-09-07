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
                Toast.makeText(QuestionAnalyzeActivity.this, getString(R.string.h_a45bac47), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.h_27a84ff2), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, getString(R.string.h_f559a2f7), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 更新加载消息
        loadingMessage.setText(getString(R.string.h_effdb1bf));
        loadingSubmessage.setText(getString(R.string.h_6807dc34));
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
                Toast.makeText(this, getString(R.string.h_93de2efb), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.h_4e0c2396), Toast.LENGTH_SHORT).show();
            }
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                // 隐藏加载状态
                loadingLayout.setVisibility(View.GONE);
                // 显示错误信息
                Log.e("QuestionAnalyze", "Error analyzing question", throwable);
                Toast.makeText(this, getString(R.string.h_4dd291fd) + throwable.getMessage(), Toast.LENGTH_SHORT).show();
            });
            return null;
        });
    }

    private void saveAnalysis() {
        // 检查是否有解析结果
        if (explanation.getText().toString().isEmpty()) {
            Toast.makeText(this, getString(R.string.h_4d68b939), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 这里可以实现保存到数据库或文件的逻辑
        // 由于没有具体的保存需求，暂时只显示提示
        Toast.makeText(this, getString(R.string.h_f289e5bc), Toast.LENGTH_SHORT).show();
    }

    private void shareAnalysis() {
        // 检查是否有解析结果
        if (explanation.getText().toString().isEmpty()) {
            Toast.makeText(this, getString(R.string.h_cfd4047e), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            StringBuilder shareContent = new StringBuilder();
            shareContent.append(getString(R.string.h_4560a89e));
            shareContent.append(getString(R.string.h_2872c034)).append(explanation.getText().toString()).append("\n\n");
            shareContent.append(getString(R.string.h_a3aebd68)).append(knowledgePoints.getText().toString()).append("\n\n");
            shareContent.append(getString(R.string.h_0e1502be)).append(learningSuggestions.getText().toString());
            
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, shareContent.toString());
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "题目解析结果");
            startActivity(android.content.Intent.createChooser(shareIntent, "分享解析结果"));
        } catch (Exception e) {
            Log.e("QuestionAnalyze", "Error sharing analysis", e);
            Toast.makeText(this, getString(R.string.h_9074ea4d) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyAnalysis() {
        // 检查是否有解析结果
        if (explanation.getText().toString().isEmpty()) {
            Toast.makeText(this, getString(R.string.h_ef1b5a8a), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            StringBuilder copyContent = new StringBuilder();
            copyContent.append(getString(R.string.h_4560a89e));
            copyContent.append(getString(R.string.h_2872c034)).append(explanation.getText().toString()).append("\n\n");
            copyContent.append(getString(R.string.h_a3aebd68)).append(knowledgePoints.getText().toString()).append("\n\n");
            copyContent.append(getString(R.string.h_0e1502be)).append(learningSuggestions.getText().toString());
            
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("解析结果", copyContent.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.h_4fb42e6e), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("QuestionAnalyze", "Error copying analysis", e);
            Toast.makeText(this, getString(R.string.h_abdfe253) + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                knowledgePoints.setText(getString(R.string.h_6b903660));
                learningSuggestions.setText(getString(R.string.h_6b903660));
            }
        } catch (Exception e) {
            Log.e("QuestionAnalyze", "Error parsing analysis result", e);
            // 解析失败时，直接显示原始结果
            explanation.setText(aiResult);
            knowledgePoints.setText(getString(R.string.h_c38cdfed));
            learningSuggestions.setText(getString(R.string.h_c38cdfed));
        }
    }
}
