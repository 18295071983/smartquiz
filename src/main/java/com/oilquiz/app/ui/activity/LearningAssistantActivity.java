package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIService;
import java.util.concurrent.CompletableFuture;

public class LearningAssistantActivity extends AppCompatActivity {

    private RadioGroup assistantModeGroup;
    private RadioButton radioLearningPlan;
    private RadioButton radioConceptExplanation;
    private RadioButton radioExamPreparation;
    private MaterialCardView planModeContainer;
    private MaterialCardView conceptModeContainer;
    private MaterialCardView examModeContainer;
    private AutoCompleteTextView planDifficultySpinner;
    private AutoCompleteTextView conceptDifficultySpinner;
    private Button btnGeneratePlan;
    private Button btnExplainConcept;
    private Button btnGeneratePreparation;
    private LinearLayout resultContainer;
    private TextView resultTitle;
    private TextView resultContent;
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
        setContentView(R.layout.activity_learning_assistant);

        initViews();
        setupSpinners();
        setupRadioGroup();
        setupClickListeners();
    }

    private void initViews() {
        assistantModeGroup = findViewById(R.id.assistant_mode_group);
        radioLearningPlan = findViewById(R.id.radio_learning_plan);
        radioConceptExplanation = findViewById(R.id.radio_concept_explanation);
        radioExamPreparation = findViewById(R.id.radio_exam_preparation);
        planModeContainer = findViewById(R.id.plan_mode_container);
        conceptModeContainer = findViewById(R.id.concept_mode_container);
        examModeContainer = findViewById(R.id.exam_mode_container);
        planDifficultySpinner = findViewById(R.id.plan_difficulty_spinner);
        conceptDifficultySpinner = findViewById(R.id.concept_difficulty_spinner);
        btnGeneratePlan = findViewById(R.id.btn_generate_plan);
        btnExplainConcept = findViewById(R.id.btn_explain_concept);
        btnGeneratePreparation = findViewById(R.id.btn_generate_preparation);
        resultContainer = findViewById(R.id.result_container);
        resultTitle = findViewById(R.id.result_title);
        resultContent = findViewById(R.id.result_content);
        actionsContainer = findViewById(R.id.actions_container);
        btnSave = findViewById(R.id.btn_save_assistant);
        btnShare = findViewById(R.id.btn_share_assistant);
        btnCopy = findViewById(R.id.btn_copy_assistant);
        
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
                Toast.makeText(LearningAssistantActivity.this, getString(R.string.h_a45bac47), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupSpinners() {
        // 设置难度选项
        String[] difficultyOptions = {"简单", "中等", "困难"};
        setupSpinner(planDifficultySpinner, difficultyOptions);
        setupSpinner(conceptDifficultySpinner, difficultyOptions);

        // 默认选择
        planDifficultySpinner.setText(getString(R.string.h_eadd05ba), false);
        conceptDifficultySpinner.setText(getString(R.string.h_eadd05ba), false);
    }

    private void setupSpinner(AutoCompleteTextView spinner, String[] options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, options);
        spinner.setAdapter(adapter);
    }

    private void setupRadioGroup() {
        assistantModeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                updateUI();
            }
        });
    }

    private void updateUI() {
        if (radioLearningPlan.isChecked()) {
            planModeContainer.setVisibility(View.VISIBLE);
            conceptModeContainer.setVisibility(View.GONE);
            examModeContainer.setVisibility(View.GONE);
        } else if (radioConceptExplanation.isChecked()) {
            planModeContainer.setVisibility(View.GONE);
            conceptModeContainer.setVisibility(View.VISIBLE);
            examModeContainer.setVisibility(View.GONE);
        } else {
            planModeContainer.setVisibility(View.GONE);
            conceptModeContainer.setVisibility(View.GONE);
            examModeContainer.setVisibility(View.VISIBLE);
        }
    }

    private void setupClickListeners() {
        btnGeneratePlan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateLearningPlan();
            }
        });

        btnExplainConcept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explainConcept();
            }
        });

        btnGeneratePreparation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateExamPreparation();
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveResult();
            }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareResult();
            }
        });

        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyResult();
            }
        });
    }

    private void generateLearningPlan() {
        // 1. 获取输入参数
        String subject = ((TextInputEditText) findViewById(R.id.input_subject_edit)).getText().toString().trim();
        String weeklyHoursStr = ((TextInputEditText) findViewById(R.id.input_weekly_hours_edit)).getText().toString().trim();
        String difficulty = planDifficultySpinner.getText().toString();

        if (subject.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_9a3d12cb), Toast.LENGTH_SHORT).show();
            return;
        }
        if (weeklyHoursStr.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_3af18ee1), Toast.LENGTH_SHORT).show();
            return;
        }

        int weeklyHours;
        try {
            weeklyHours = Integer.parseInt(weeklyHoursStr);
            if (weeklyHours <= 0) {
                Toast.makeText(this, getString(R.string.h_e0e06b77), Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, getString(R.string.h_30068cb9), Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. 构建提示词
        String prompt = String.format(
                "Generate a personalized learning plan for studying %s with %d hours of study per week at an %s difficulty level. The plan should include:\n" +
                "- Weekly breakdown of topics\n" +
                "- Daily study schedule\n" +
                "- Recommended learning resources\n" +
                "- Progress tracking milestones\n",
                subject, weeklyHours, difficulty
        );

        // 3. 调用AI服务生成学习计划
        generateContent(prompt, "学习计划");
    }

    private void explainConcept() {
        // 1. 获取输入概念
        String concept = ((TextInputEditText) findViewById(R.id.input_concept_edit)).getText().toString().trim();
        String difficulty = conceptDifficultySpinner.getText().toString();

        if (concept.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_3a6cd904), Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. 构建提示词
        String prompt = String.format(
                "Explain the concept of %s at an %s difficulty level. The explanation should be:\n" +
                "- Clear and easy to understand\n" +
                "- Technically accurate\n" +
                "- Supported with examples\n" +
                "- Appropriate for someone with basic knowledge\n",
                concept, difficulty
        );

        // 3. 调用AI服务解释概念
        generateContent(prompt, "概念解释");
    }

    private void generateExamPreparation() {
        // 1. 获取输入参数
        String examType = ((TextInputEditText) findViewById(R.id.input_exam_type_edit)).getText().toString().trim();
        String daysLeftStr = ((TextInputEditText) findViewById(R.id.input_days_left_edit)).getText().toString().trim();

        if (examType.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_11c3b109), Toast.LENGTH_SHORT).show();
            return;
        }
        if (daysLeftStr.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_32a80cd3), Toast.LENGTH_SHORT).show();
            return;
        }

        int daysLeft;
        try {
            daysLeft = Integer.parseInt(daysLeftStr);
            if (daysLeft <= 0) {
                Toast.makeText(this, getString(R.string.h_1e6bb976), Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, getString(R.string.h_bbce3450), Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. 构建提示词
        String prompt = String.format(
                "Generate a %d-day preparation plan for the %s exam. The plan should include:\n" +
                "- Daily study schedule\n" +
                "- Key topics to focus on\n" +
                "- Practice exam strategy\n" +
                "- Tips for exam day\n",
                daysLeft, examType
        );

        // 3. 调用AI服务生成备考建议
        generateContent(prompt, "考试备考建议");
    }

    private void generateContent(String prompt, String title) {
        if (!aiService.isInitialized()) {
            if (!aiService.initializeSafe()) {
                Toast.makeText(this, getString(R.string.h_f559a2f7), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 更新加载消息
        if (title.equals(getString(R.string.h_99d0dfe7))) {
            loadingMessage.setText(getString(R.string.h_a71e0fb1));
            loadingSubmessage.setText(getString(R.string.h_fa17813e));
        } else if (title.equals(getString(R.string.h_11be49c7))) {
            loadingMessage.setText(getString(R.string.h_6ebb8f9a));
            loadingSubmessage.setText(getString(R.string.h_c145e97d));
        } else if (title.equals(getString(R.string.h_7731015b))) {
            loadingMessage.setText(getString(R.string.h_b0569798));
            loadingSubmessage.setText(getString(R.string.h_77dc270a));
        }
        loadingLayout.setVisibility(View.VISIBLE);

        // 异步生成内容
        currentTask = aiService.generateAsync(prompt, 1500).thenAccept(result -> runOnUiThread(() -> {
            // 隐藏加载状态
            loadingLayout.setVisibility(View.GONE);
            // 显示结果
            if (result != null && result.startsWith("Error:")) {
                // 处理AI服务错误
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            } else if (result != null && !result.isEmpty()) {
                resultContainer.setVisibility(View.VISIBLE);
                actionsContainer.setVisibility(View.VISIBLE);
                resultTitle.setText(title);
                resultContent.setText(result);
                Toast.makeText(this, title + getString(R.string.h_b6c4a445), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.h_9d7f7ec4), Toast.LENGTH_SHORT).show();
            }
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                // 隐藏加载状态
                loadingLayout.setVisibility(View.GONE);
                // 显示错误信息
                Log.e("LearningAssistant", "Error generating content", throwable);
                Toast.makeText(this, getString(R.string.h_ff291c78) + throwable.getMessage(), Toast.LENGTH_SHORT).show();
            });
            return null;
        });
    }

    private void saveResult() {
        // 检查是否有生成结果
        if (resultContent.getText().toString().isEmpty()) {
            Toast.makeText(this, getString(R.string.h_afa9c07e), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 这里可以实现保存到数据库或文件的逻辑
        // 由于没有具体的保存需求，暂时只显示提示
        Toast.makeText(this, getString(R.string.h_1c34535a), Toast.LENGTH_SHORT).show();
    }

    private void shareResult() {
        // 检查是否有生成结果
        if (resultContent.getText().toString().isEmpty()) {
            Toast.makeText(this, getString(R.string.h_e9a0e8eb), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            StringBuilder shareContent = new StringBuilder();
            shareContent.append(resultTitle.getText().toString()).append(":\n\n");
            shareContent.append(resultContent.getText().toString());
            
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, shareContent.toString());
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, resultTitle.getText().toString());
            startActivity(android.content.Intent.createChooser(shareIntent, "分享内容"));
        } catch (Exception e) {
            Log.e("LearningAssistant", "Error sharing result", e);
            Toast.makeText(this, getString(R.string.h_9074ea4d) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyResult() {
        // 检查是否有生成结果
        if (resultContent.getText().toString().isEmpty()) {
            Toast.makeText(this, getString(R.string.h_42ee3bba), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            StringBuilder copyContent = new StringBuilder();
            copyContent.append(resultTitle.getText().toString()).append(":\n\n");
            copyContent.append(resultContent.getText().toString());
            
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("学习助手结果", copyContent.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.h_4fb42e6e), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("LearningAssistant", "Error copying result", e);
            Toast.makeText(this, getString(R.string.h_abdfe253) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
