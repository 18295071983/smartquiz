package com.oilquiz.app.ui.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;


import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.manager.ConfigManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StartQuizActivity extends AppCompatActivity {

    private RadioGroup radioGroupMode;
    private Spinner spinnerQuestionType;
    private Spinner spinnerQuestionOrder;
    private MaterialButton buttonStartQuiz;

    private String selectedMode = "practice";
    private String selectedQuestionType = "全部";
    private String selectedQuestionOrder = "顺序";
    private QuestionViewModel questionViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_quiz);

        questionViewModel = new QuestionViewModel(getApplication());

        initUI();
        setupSpinners();
        setupQuizModes();
        loadQuestionTypes();
        setupListeners();
    }

    private void setupQuizModes() {
        ConfigManager configManager = ConfigManager.getInstance(this);
        List<Map<String, String>> quizModes = configManager.getQuizModes();
        
        radioGroupMode.removeAllViews();
        
        for (int i = 0; i < quizModes.size(); i++) {
            Map<String, String> mode = quizModes.get(i);
            String label = mode.get("label");
            String value = mode.get("value");
            
            com.google.android.material.card.MaterialCardView cardView = new com.google.android.material.card.MaterialCardView(this);
            RadioGroup.LayoutParams cardParams = new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.spacing_12));
            cardView.setLayoutParams(cardParams);
            cardView.setRadius(getResources().getDimensionPixelSize(R.dimen.card_corner_radius_small));
            cardView.setCardElevation(getResources().getDimensionPixelSize(R.dimen.elevation_small));
            cardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_background));
            cardView.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.divider_height));
            cardView.setStrokeColor(ContextCompat.getColor(this, R.color.primary_color));
            
            LinearLayout cardLayout = new LinearLayout(this);
            cardLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            cardLayout.setOrientation(LinearLayout.HORIZONTAL);
            cardLayout.setPadding(
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16)
            );
            cardLayout.setGravity(Gravity.CENTER_VERTICAL);
            
            com.google.android.material.radiobutton.MaterialRadioButton radioButton = new com.google.android.material.radiobutton.MaterialRadioButton(this);
            LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            radioParams.setMargins(0, 0, getResources().getDimensionPixelSize(R.dimen.spacing_12), 0);
            radioButton.setLayoutParams(radioParams);
            radioButton.setButtonTintList(ContextCompat.getColorStateList(this, R.color.primary_color));
            radioButton.setId(View.generateViewId());
            
            TextView textView = new TextView(this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            textParams.weight = 1;
            textView.setLayoutParams(textParams);
            textView.setText(label);
            textView.setTextAppearance(R.style.TextAppearance_SmartQuiz_BodyLarge);
            textView.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            
            cardLayout.addView(radioButton);
            cardLayout.addView(textView);
            cardView.addView(cardLayout);
            
            radioGroupMode.addView(cardView);
            
            final String finalValue = value;
            final com.google.android.material.card.MaterialCardView finalCardView = cardView;
            final com.google.android.material.radiobutton.MaterialRadioButton finalRadioButton = radioButton;
            final TextView finalTextView = textView;
            
            cardLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < radioGroupMode.getChildCount(); j++) {
                        View child = radioGroupMode.getChildAt(j);
                        if (child instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView cv = (com.google.android.material.card.MaterialCardView) child;
                            LinearLayout ll = (LinearLayout) cv.getChildAt(0);
                            com.google.android.material.radiobutton.MaterialRadioButton rb = (com.google.android.material.radiobutton.MaterialRadioButton) ll.getChildAt(0);
                            TextView tv = (TextView) ll.getChildAt(1);
                            rb.setChecked(false);
                            cv.setCardElevation(getResources().getDimensionPixelSize(R.dimen.elevation_small));
                            cv.setCardBackgroundColor(ContextCompat.getColor(StartQuizActivity.this, R.color.card_background));
                            cv.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.divider_height));
                            cv.setStrokeColor(ContextCompat.getColor(StartQuizActivity.this, R.color.primary_color));
                            tv.setTextColor(ContextCompat.getColor(StartQuizActivity.this, R.color.text_primary));
                        }
                    }
                    finalRadioButton.setChecked(true);
                    finalCardView.setCardElevation(getResources().getDimensionPixelSize(R.dimen.elevation_medium));
                    finalCardView.setCardBackgroundColor(ContextCompat.getColor(StartQuizActivity.this, R.color.primary_color));
                    finalCardView.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.divider_height));
                    finalCardView.setStrokeColor(ContextCompat.getColor(StartQuizActivity.this, R.color.primary_color));
                    finalTextView.setTextColor(Color.WHITE);
                    selectedMode = finalValue;
                }
            });
            
            if (i == 0) {
                radioButton.setChecked(true);
                cardView.setCardElevation(getResources().getDimensionPixelSize(R.dimen.elevation_medium));
                cardView.setCardBackgroundColor(ContextCompat.getColor(this, R.color.primary_color));
                cardView.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.divider_height));
                cardView.setStrokeColor(ContextCompat.getColor(this, R.color.primary_color));
                textView.setTextColor(Color.WHITE);
                selectedMode = value;
            }
        }
    }
    
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density);
    }

    private void initUI() {
        radioGroupMode = findViewById(R.id.radioGroupMode);
        spinnerQuestionType = findViewById(R.id.spinnerQuestionType);
        spinnerQuestionOrder = findViewById(R.id.spinnerQuestionOrder);
        buttonStartQuiz = findViewById(R.id.buttonStartQuiz);
    }

    private void setupSpinners() {
        ConfigManager configManager = ConfigManager.getInstance(this);
        
        List<String> questionOrderOptions = configManager.getQuestionOrders();
        ArrayAdapter<String> orderAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, questionOrderOptions);
        orderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerQuestionOrder.setAdapter(orderAdapter);
    }

    private void loadQuestionTypes() {
        questionViewModel.getAllQuestionTypes(new QuestionViewModel.GetQuestionTypesCallback() {
            @Override
            public void onSuccess(List<String> questionTypes) {
                List<String> typeList = new ArrayList<>();
                typeList.add("全部");
                if (questionTypes != null && !questionTypes.isEmpty()) {
                    typeList.addAll(questionTypes);
                } else {
                    // 题库为空，提示用户
                    Toast.makeText(StartQuizActivity.this, "题库为空，请先在题库管理中导入题目", Toast.LENGTH_LONG).show();
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<>(StartQuizActivity.this,
                        android.R.layout.simple_spinner_item, typeList);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerQuestionType.setAdapter(adapter);
            }

            @Override
            public void onError(String error) {
                // 数据库异常时仍显示默认题型，避免界面空白
                List<String> typeList = new ArrayList<>();
                typeList.add("全部");
                typeList.add("单选题");
                typeList.add("多选题");
                typeList.add("判断题");
                typeList.add("填空题");
                typeList.add("简答题");
                ArrayAdapter<String> adapter = new ArrayAdapter<>(StartQuizActivity.this,
                        android.R.layout.simple_spinner_item, typeList);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerQuestionType.setAdapter(adapter);
                Toast.makeText(StartQuizActivity.this, "题库加载异常，请检查后重试", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupListeners() {
        // 模式选择已通过卡片点击事件处理，无需额外监听器

        spinnerQuestionType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedQuestionType = parent.getItemAtPosition(position).toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spinnerQuestionOrder.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedQuestionOrder = parent.getItemAtPosition(position).toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        buttonStartQuiz.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startQuiz();
            }
        });
    }

    private void startQuiz() {
        if ("全部".equals(selectedQuestionType)) {
            questionViewModel.getQuestionCount(new QuestionViewModel.GetQuestionCountCallback() {
                @Override
                public void onSuccess(int count) {
                    startQuizWithCount(count);
                }

                @Override
                public void onError(String error) {
                    startQuizWithCount(-1);
                }
            });
        } else {
            questionViewModel.getQuestionsByType(selectedQuestionType, new QuestionViewModel.GetQuestionsCallback() {
                @Override
                public void onSuccess(List<Question> questions) {
                    int count = questions != null ? questions.size() : 0;
                    startQuizWithCount(count);
                }

                @Override
                public void onError(String error) {
                    startQuizWithCount(-1);
                }
            });
        }
    }

    private void startQuizWithCount(int actualQuestionCount) {
        Intent intent = new Intent(StartQuizActivity.this, QuizActivity.class);
        intent.putExtra(QuizActivity.EXTRA_QUIZ_MODE, selectedMode);
        intent.putExtra("question_type", selectedQuestionType);
        intent.putExtra("question_order", selectedQuestionOrder);
        intent.putExtra("actual_question_count", actualQuestionCount);

        startActivity(intent);
        finish();
    }
}