package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.oilquiz.app.R;

public class AICenterActivity extends AppCompatActivity {

    private MaterialCardView cardModelManagement;
    private MaterialCardView cardQuestionGenerator;
    private MaterialCardView cardQuestionAnalyzer;
    private MaterialCardView cardLearningAssistant;
    private MaterialCardView cardTranslator;
    private MaterialCardView cardAIChat;
    private MaterialCardView cardAgentChat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_center);

        initViews();
        setupClickListeners();
    }

    private void initViews() {
        cardModelManagement = findViewById(R.id.card_model_management);
        cardQuestionGenerator = findViewById(R.id.card_question_generator);
        cardQuestionAnalyzer = findViewById(R.id.card_question_analyzer);
        cardLearningAssistant = findViewById(R.id.card_learning_assistant);
        cardTranslator = findViewById(R.id.card_translator);
        cardAIChat = findViewById(R.id.card_ai_chat);
        cardAgentChat = findViewById(R.id.card_agent_chat);
    }

    private void setupClickListeners() {
        cardModelManagement.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AICenterActivity.this, ModelImportActivity.class));
            }
        });

        cardQuestionGenerator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AICenterActivity.this, QuestionGenerateActivity.class));
            }
        });

        cardQuestionAnalyzer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AICenterActivity.this, QuestionAnalyzeActivity.class));
            }
        });

        cardLearningAssistant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AICenterActivity.this, LearningAssistantActivity.class));
            }
        });

        cardTranslator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AICenterActivity.this, TranslateActivity.class));
            }
        });

        cardAIChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AICenterActivity.this, AIChatActivity.class));
            }
        });

        cardAgentChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AICenterActivity.this, AgentChatActivity.class));
            }
        });
    }
}