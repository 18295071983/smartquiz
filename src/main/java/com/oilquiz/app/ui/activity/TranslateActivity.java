package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
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

import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIService;
import java.util.concurrent.CompletableFuture;

public class TranslateActivity extends AppCompatActivity {

    private RadioGroup translateModeGroup;
    private RadioButton radioTextTranslate;
    private RadioButton radioQuestionTranslate;
    private RadioButton radioLanguageDetect;
    private TextInputLayout inputTextLayout;
    private EditText inputText;
    private AutoCompleteTextView sourceLanguageSpinner;
    private AutoCompleteTextView targetLanguageSpinner;
    private Button btnTranslate;
    private LinearLayout resultContainer;
    private TextView translateResult;
    private LinearLayout actionsContainer;
    private Button btnCopy;
    private Button btnShare;
    private View loadingLayout;
    private TextView loadingMessage;
    private TextView loadingSubmessage;
    private Button btnCancel;
    private CompletableFuture<?> currentTask;
    
    private AIService aiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translate);

        initViews();
        setupSpinners();
        setupRadioGroup();
        setupClickListeners();
    }

    private void initViews() {
        translateModeGroup = findViewById(R.id.translate_mode_group);
        radioTextTranslate = findViewById(R.id.radio_text_translate);
        radioQuestionTranslate = findViewById(R.id.radio_question_translate);
        radioLanguageDetect = findViewById(R.id.radio_language_detect);
        inputTextLayout = findViewById(R.id.input_text_layout);
        inputText = findViewById(R.id.input_text);
        sourceLanguageSpinner = findViewById(R.id.source_language_spinner);
        targetLanguageSpinner = findViewById(R.id.target_language_spinner);
        btnTranslate = findViewById(R.id.btn_translate);
        resultContainer = findViewById(R.id.result_container);
        translateResult = findViewById(R.id.translate_result);
        actionsContainer = findViewById(R.id.actions_container);
        btnCopy = findViewById(R.id.btn_copy);
        btnShare = findViewById(R.id.btn_share_translate);
        
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
                Toast.makeText(TranslateActivity.this, getString(R.string.h_a45bac47), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupSpinners() {
        // 设置语言选项
        String[] languageOptions = {"中文", "英文", "日文", "韩文", "法文", "德文"};
        setupSpinner(sourceLanguageSpinner, languageOptions);
        setupSpinner(targetLanguageSpinner, languageOptions);

        // 默认选择
        sourceLanguageSpinner.setText(getString(R.string.h_a7bac223), false);
        targetLanguageSpinner.setText(getString(R.string.h_f9fb6a06), false);
    }

    private void setupSpinner(AutoCompleteTextView spinner, String[] options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, options);
        spinner.setAdapter(adapter);
    }

    private void setupRadioGroup() {
        translateModeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                updateUI();
            }
        });
    }

    private void updateUI() {
        if (radioLanguageDetect.isChecked()) {
            // 语言检测模式
            inputTextLayout.setHint(getString(R.string.h_5e9c2ff3));
        } else if (radioQuestionTranslate.isChecked()) {
            // 题目翻译模式
            inputTextLayout.setHint(getString(R.string.h_1ea9776a));
        } else {
            // 文本翻译模式
            inputTextLayout.setHint(getString(R.string.h_7bd72694));
        }
    }

    private void setupClickListeners() {
        btnTranslate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                translate();
            }
        });

        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyResult();
            }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareResult();
            }
        });
    }

    private void translate() {
        // 1. 获取输入文本
        String input = inputText.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_f467e05d), Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. 根据模式构建提示词
        String prompt;
        if (radioLanguageDetect.isChecked()) {
            // 语言检测模式
            prompt = "Detect the language of the following text: \n" + input + "\n\nPlease output only the language name.";
        } else if (radioQuestionTranslate.isChecked()) {
            // 题目翻译模式
            String targetLanguage = targetLanguageSpinner.getText().toString();
            prompt = String.format(
                    "Translate the following question to %s. Keep the format and structure:\n" +
                    "%s",
                    targetLanguage, input
            );
        } else {
            // 文本翻译模式
            String targetLanguage = targetLanguageSpinner.getText().toString();
            prompt = String.format(
                    "Translate the following text to %s:\n" +
                    "%s",
                    targetLanguage, input
            );
        }

        // 3. 调用AI服务进行翻译
        if (!aiService.isInitialized()) {
            if (!aiService.initializeSafe()) {
                Toast.makeText(this, getString(R.string.h_f559a2f7), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 更新加载消息
        if (radioLanguageDetect.isChecked()) {
            loadingMessage.setText(getString(R.string.h_9113c7ef));
            loadingSubmessage.setText(getString(R.string.h_c563f559));
        } else if (radioQuestionTranslate.isChecked()) {
            loadingMessage.setText(getString(R.string.h_8beafa09));
            loadingSubmessage.setText(getString(R.string.h_0bd89a98));
        } else {
            loadingMessage.setText(getString(R.string.h_34e008d0));
            loadingSubmessage.setText(getString(R.string.h_3c372740));
        }
        loadingLayout.setVisibility(View.VISIBLE);

        // 异步翻译
        currentTask = aiService.generateAsync(prompt, 1000).thenAccept(result -> runOnUiThread(() -> {
            // 隐藏加载状态
            loadingLayout.setVisibility(View.GONE);
            // 4. 显示翻译结果
            if (result != null && result.startsWith("Error:")) {
                // 处理AI服务错误
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            } else if (result != null && !result.isEmpty()) {
                resultContainer.setVisibility(View.VISIBLE);
                actionsContainer.setVisibility(View.VISIBLE);
                translateResult.setText(result);
                Toast.makeText(this, getString(R.string.h_92c9ef31), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.h_a5d2dadc), Toast.LENGTH_SHORT).show();
            }
        })).exceptionally(throwable -> {
            runOnUiThread(() -> {
                // 隐藏加载状态
                loadingLayout.setVisibility(View.GONE);
                // 显示错误信息
                Log.e("Translate", "Error translating text", throwable);
                Toast.makeText(this, getString(R.string.h_1e9d2db6) + throwable.getMessage(), Toast.LENGTH_SHORT).show();
            });
            return null;
        });
    }

    private void copyResult() {
        String result = translateResult.getText().toString().trim();
        if (result.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_42ee3bba), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("翻译结果", result);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.h_4fb42e6e), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("Translate", "Error copying result", e);
            Toast.makeText(this, getString(R.string.h_abdfe253) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareResult() {
        String result = translateResult.getText().toString().trim();
        if (result.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_e9a0e8eb), Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, result);
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "翻译结果");
            startActivity(android.content.Intent.createChooser(shareIntent, "分享翻译结果"));
        } catch (Exception e) {
            Log.e("Translate", "Error sharing result", e);
            Toast.makeText(this, getString(R.string.h_9074ea4d) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
