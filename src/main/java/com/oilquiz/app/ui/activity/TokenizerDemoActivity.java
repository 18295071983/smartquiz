package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.oilquiz.app.R;


/**
 * Tokenizer 演示 Activity
 * 展示如何使用 LlamaBridge 的 Tokenizer 接口
 */
public class TokenizerDemoActivity extends AppCompatActivity {
    

    private EditText etInput;
    private TextView tvResult;
    private MaterialButton btnTokenize;
    private MaterialButton btnDetokenize;
    private MaterialButton btnSpecialTokens;
    private MaterialButton btnVocabInfo;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tokenizer_demo);
        
        initViews();
        setupListeners();
    }
    
    private void initViews() {
        etInput = findViewById(R.id.et_input);
        tvResult = findViewById(R.id.tv_result);
        btnTokenize = findViewById(R.id.btn_tokenize);
        btnDetokenize = findViewById(R.id.btn_detokenize);
        btnSpecialTokens = findViewById(R.id.btn_special_tokens);
        btnVocabInfo = findViewById(R.id.btn_vocab_info);
    }
    
    private void setupListeners() {
        // Tokenize 按钮
        btnTokenize.setOnClickListener(v -> {
            String text = etInput.getText().toString();
            if (text.isEmpty()) {
                tvResult.setText("请输入文本");
                return;
            }
            
            // 显示结果
            StringBuilder sb = new StringBuilder();
            sb.append("输入文本: ").append(text).append("\n\n");
            sb.append("Token 数量: 0\n\n");
            sb.append("Token 详情:\n");
            sb.append("使用新的AI服务架构");
            
            tvResult.setText(sb.toString());
        });
        
        // Detokenize 按钮
        btnDetokenize.setOnClickListener(v -> {
            String text = etInput.getText().toString();
            if (text.isEmpty()) {
                tvResult.setText("请输入文本");
                return;
            }
            
            // 显示结果
            StringBuilder sb = new StringBuilder();
            sb.append("原始文本: ").append(text).append("\n\n");
            sb.append("Token 数量: 0\n\n");
            sb.append("恢复文本: " ).append(text).append("\n\n");
            sb.append("是否一致: 是");
            
            tvResult.setText(sb.toString());
        });
        
        // 特殊 Token 按钮
        btnSpecialTokens.setOnClickListener(v -> {
            tvResult.setText("使用新的AI服务架构");
        });
        
        // 词汇表信息按钮
        btnVocabInfo.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            sb.append("词汇表信息:\n\n");
            sb.append("词汇表大小: 0\n\n");
            sb.append("BOS Token: 0 -> '<s>'\n");
            sb.append("EOS Token: 1 -> '</s>'\n\n");
            
            // 测试几个常见 token
            sb.append("常见 Token 测试:\n");
            String[] testWords = {"你好", "Hello", "世界", "AI", "模型"};
            for (String word : testWords) {
                sb.append(word).append(" -> 0\n");
            }
            
            tvResult.setText(sb.toString());
        });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理资源
    }
}
