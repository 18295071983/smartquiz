package com.oilquiz.app.ui.activity;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.os.CountDownTimer;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ProgressBar;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.model.StudyPlan;
import com.oilquiz.app.model.WrongQuestion;
import com.oilquiz.app.repository.QuestionRepository;
import com.oilquiz.app.repository.ScoreRepository;
import com.oilquiz.app.repository.StudyPlanRepository;
import com.oilquiz.app.repository.WrongQuestionRepository;
import com.oilquiz.app.viewmodel.QuestionViewModel;
import com.oilquiz.app.manager.ThemeColorManager;
import com.oilquiz.app.R;
import com.oilquiz.app.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class QuizActivity extends BaseActivity {

    public static final String EXTRA_QUIZ_MODE = "quiz_mode";
    public static final String EXTRA_QUESTION_COUNT = "question_count";
    public static final String EXTRA_CATEGORY = "category";

    private String quizMode;
    private int questionCount;
    private String category;
    private String questionType;
    private List<Question> questions;
    private List<String> userAnswers;
    private List<Boolean> markedQuestions;
    private int currentQuestionIndex = 0;
    private int correctCount = 0;
    private long startTime;
    private int currentThemeColor; // 当前主题色
    private ThemeColorManager themeColorManager;

    private TextView textViewQuestion;
    private TextView textViewProgress;
    private TextView textViewTimer;
    private TextView textViewScore;
    private RadioGroup radioGroupOptions;
    private List<com.google.android.material.radiobutton.MaterialRadioButton> radioButtons = new ArrayList<>();
    private com.google.android.material.textfield.TextInputLayout answerInputLayout;
    private EditText editTextUserAnswer;
    private MaterialButton buttonCheckAnswer;
    private MaterialButton buttonPrevious;
    private MaterialButton buttonNext;
    private MaterialButton buttonMark;
    private MaterialButton buttonSubmit;
    private MaterialButton buttonShowAnswer;
    private Spinner spinnerQuestionType;
    private TextView textViewExplanation;
    private View linearLayoutExplanation;

    private CountDownTimer countDownTimer;

    private LinearLayout navigationLayout;
    private TextView textViewMode;
    private List<String> questionTypes;
    private List<Question> originalQuestions;
    private GestureDetector gestureDetector;
    private String questionOrderMode = "顺序";
    private Spinner spinnerQuestionOrder;
    private LinearLayout checkBoxContainer;
    private List<com.google.android.material.checkbox.MaterialCheckBox> checkBoxes = new ArrayList<>();

    private ProgressBar progressBar;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_quiz_modern;
    }

    private TextView textViewQuestionType;
    
    @Override
    protected void initView() {
        // 设置工具栏（带返回按钮）
        setupToolbar("答题");
        
        // 初始化UI组件
        textViewQuestion = findViewById(R.id.textViewQuestion);
        textViewProgress = findViewById(R.id.textViewProgress);
        textViewTimer = findViewById(R.id.textViewTimer);
        textViewMode = findViewById(R.id.textViewMode);
        textViewScore = findViewById(R.id.textViewScore);
        textViewQuestionType = findViewById(R.id.textViewQuestionType);
        progressBar = findViewById(R.id.progressBar);
        buttonPrevious = findViewById(R.id.buttonPrevious);
        buttonNext = findViewById(R.id.buttonNext);
        buttonMark = findViewById(R.id.buttonMark);
        buttonShowAnswer = findViewById(R.id.buttonShowAnswer);
        
        // 初始化背诵模式专用组件
        textViewExplanation = findViewById(R.id.textViewExplanation);
        linearLayoutExplanation = findViewById(R.id.linearLayoutExplanation);
        
        // 初始化非背诵模式组件
        radioGroupOptions = findViewById(R.id.radioGroupOptions);
        answerInputLayout = findViewById(R.id.answerInputLayout);
        editTextUserAnswer = findViewById(R.id.editTextUserAnswer);
        
        // 设置显示答案按钮的点击事件
        if (buttonShowAnswer != null) {
            buttonShowAnswer.setOnClickListener(v -> showAnswerButtonClicked());
        }
        
        // 初始化多选题容器
        LinearLayout contentLayout = findViewById(R.id.card_content);
        if (contentLayout != null) {
            // 创建多选题容器
            checkBoxContainer = new LinearLayout(this);
            checkBoxContainer.setId(View.generateViewId());
            checkBoxContainer.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.spacing_16));
            checkBoxContainer.setLayoutParams(params);
            checkBoxContainer.setVisibility(View.GONE);
            contentLayout.addView(checkBoxContainer);
        }
    }

    @Override
    protected void initData() {
        // 初始化主题色管理器
        themeColorManager = new ThemeColorManager();
        currentThemeColor = themeColorManager.getCurrentThemeColorValue(this);
        
        // 获取传入的参数
        String tempQuizMode = null;
        if (getIntent() != null) {
            tempQuizMode = getIntent().getStringExtra(EXTRA_QUIZ_MODE);
        }

        // 获取传入的参数
        if (getIntent() != null) {
            quizMode = tempQuizMode;
            questionCount = getIntent().getIntExtra(EXTRA_QUESTION_COUNT, -1);
            category = getIntent().getStringExtra(EXTRA_CATEGORY);
            questionType = getIntent().getStringExtra("question_type");
            questionOrderMode = getIntent().getStringExtra("question_order");
            int actualQuestionCount = getIntent().getIntExtra("actual_question_count", -1);
            if (actualQuestionCount > 0) {
                questionCount = actualQuestionCount;
            }
        }
        
        // 默认使用筛选后的题目实际数量，不限制数量
        if (questionCount == -1) {
            questionCount = -1; // -1 表示获取所有符合条件的题目
        }
        
        // 如果Intent中没有传递题目顺序，则使用默认值
        if (questionOrderMode == null) {
            questionOrderMode = "顺序";
        }
        
        // 如果Intent中没有传递题目类型，则从SharedPreferences中读取
        if (questionType == null) {
            android.content.SharedPreferences sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE);
            questionType = sharedPreferences.getString("question_type", "全部");
        }
        
        // 保存当前选择到SharedPreferences
        android.content.SharedPreferences sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE);
        sharedPreferences.edit().putInt("question_count", questionCount).apply();
        sharedPreferences.edit().putString("question_type", questionType).apply();

        // 设置默认模式
        if (quizMode == null || quizMode.isEmpty()) {
            quizMode = "recite"; // 默认背诵模式
        }

        // 显示模式信息
        updateModeDisplay();

        // 初始化答案和标记列表
        userAnswers = new ArrayList<>();
        markedQuestions = new ArrayList<>();

        // 加载自定义题目类型映射
        loadCustomQuestionTypeMappings();
        
        // 初始化题目数据
        initQuestions();

        // 开始计时
        startTime = System.currentTimeMillis();
        startTimer();
        
        // 初始化手势检测器
        initGestureDetector();
    }

    @Override
    protected void initListener() {
        // 设置按钮点击事件
        if (buttonPrevious != null) {
            buttonPrevious.setOnClickListener(v -> showPreviousQuestion());
        }
        if (buttonNext != null) {
            buttonNext.setOnClickListener(v -> showNextQuestion());
        }
        if (buttonMark != null) {
            buttonMark.setOnClickListener(v -> toggleMarkQuestion());
        }
    }
    
    /**
     * 初始化手势检测器
     */
    private void initGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                // 计算滑动距离
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                
                // 确保是水平滑动且距离足够大
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100) {
                    if (diffX > 0) {
                        // 向右滑动，显示上一题
                        if (currentQuestionIndex > 0) {
                            showPreviousQuestion();
                        }
                    } else {
                        // 向左滑动，显示下一题
                        if (currentQuestionIndex < questions.size() - 1) {
                            showNextQuestion();
                        }
                    }
                    return true;
                }
                return false;
            }
        });
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // 更新模式显示
    private void updateModeDisplay() {
        if (textViewMode != null) {
            switch (quizMode) {
                case "practice":
                    textViewMode.setText("练习模式");
                    setModeTheme(R.color.primary_color, R.color.primary_dark);
                    break;
                case "exam":
                    textViewMode.setText("考试模式");
                    setModeTheme(R.color.blue, R.color.primary_dark);
                    break;
                case "review":
                    textViewMode.setText("复习模式");
                    setModeTheme(R.color.green, R.color.success_color);
                    break;
                case "challenge":
                    textViewMode.setText("挑战模式");
                    setModeTheme(R.color.red, R.color.error_color);
                    break;
                case "recite":
                    textViewMode.setText("背诵模式");
                    setModeTheme(R.color.yellow, R.color.warning_color);
                    break;
                default:
                    textViewMode.setText(quizMode);
                    setModeTheme(R.color.primary_color, R.color.primary_dark);
            }
        }
    }

    // 设置模式主题
    private void setModeTheme(int primaryColorRes, int darkColorRes) {
        // 使用用户选择的主题色
        currentThemeColor = themeColorManager.getCurrentThemeColorValue(this);
        int primaryColor = currentThemeColor;
        int darkColor = getResources().getColor(darkColorRes);
        
        // 更新顶部栏背景
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(primaryColor);
        }
        
        // 更新得分文本颜色
        if (textViewScore != null) {
            textViewScore.setTextColor(primaryColor);
        }
        
        // 更新进度条颜色
        if (progressBar != null) {
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(primaryColor));
        }
        
        // 更新按钮颜色
        MaterialButton buttonNext = findViewById(R.id.buttonNext);
        if (buttonNext != null) {
            // 保持XML中定义的背景，只更新文字颜色为黑色
            buttonNext.setTextColor(getResources().getColor(R.color.black));
        }
        
        MaterialButton buttonPrevious = findViewById(R.id.buttonPrevious);
        if (buttonPrevious != null) {
            buttonPrevious.setTextColor(getResources().getColor(R.color.black));
        }
        
        MaterialButton buttonMark = findViewById(R.id.buttonMark);
        if (buttonMark != null) {
            buttonMark.setTextColor(getResources().getColor(R.color.black));
        }
        
        MaterialButton buttonShowAnswer = findViewById(R.id.buttonShowAnswer);
        if (buttonShowAnswer != null) {
            buttonShowAnswer.setTextColor(getResources().getColor(R.color.black));
        }
        
        // 更新选项按钮颜色
        updateOptionButtonColors();
        
        // 更新导航按钮颜色
        updateAllNavButtons();
    }

    // 初始化题目数据
    private void initQuestions() {
        Log.d("QuizActivity", "开始初始化题目数据");
        try {
            // 检查Activity是否已经结束
            if (isFinishing() || isDestroyed()) {
                Log.d("QuizActivity", "Activity已经结束，跳过初始化");
                return;
            }
            
            // 确保questionCount不为负数
            if (questionCount <= 0) {
                questionCount = 10;
                Log.d("QuizActivity", "questionCount为负数，设置为默认值10");
            }
            
            Log.d("QuizActivity", "初始化QuestionRepository...");
            QuestionRepository repository = new QuestionRepository(getApplication());
            Log.d("QuizActivity", "QuestionRepository初始化成功");
            
            // 记录开始初始化题目数据
            Log.d("QuizActivity", "开始初始化题目数据: questionType=" + questionType + ", category=" + category + ", questionCount=" + questionCount);
            
            // 使用新的数据库匹配方法
            matchDatabaseQuestions(repository);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("QuizActivity", "初始化题目数据异常: " + e.getMessage());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(QuizActivity.this, "初始化题目数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    // 使用模拟数据作为备选
                    try {
                        // 确保questionCount不为负数
                        int count = questionCount > 0 ? questionCount : 10;
                        Log.d("QuizActivity", "使用模拟数据，数量: " + count);
                        questions = getMockQuestions(count);
                        // 根据题目顺序模式处理模拟数据
                        if ("随机".equals(questionOrderMode)) {
                            // 随机打乱模拟题目
                            java.util.Collections.shuffle(questions);
                            Log.d("QuizActivity", "随机打乱模拟题目");
                        }
                        initAnswerLists();
                        Log.d("QuizActivity", "模拟数据初始化成功");
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        Log.e("QuizActivity", "初始化模拟数据也失败: " + ex.getMessage());
                        Toast.makeText(QuizActivity.this, "初始化模拟数据也失败", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                }
            });
        }
    }
    
    // 新的数据库匹配方法
    private void matchDatabaseQuestions(QuestionRepository repository) {
        // 首先检查数据库中是否有题目
        repository.getQuestionCount(new QuestionRepository.RepositoryCallback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                Log.d("QuizActivity", "数据库题目数量: " + count);
                
                // 根据不同条件匹配数据库题目
                if (questionType != null && !questionType.isEmpty() && !questionType.equals("all") && !questionType.equals("全部")) {
                    // 根据题目类型匹配题目
                    matchQuestionsByType(repository, questionType);
                } else if (category != null && !category.isEmpty()) {
                    // 根据分类匹配题目
                    matchQuestionsByCategory(repository, category, questionCount);
                } else {
                    // 匹配所有题目或随机题目
                    matchAllQuestions(repository, questionCount);
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e("QuizActivity", "获取题目数量失败: " + error);
                // 执行失败，使用模拟数据作为备选
                handleDatabaseError();
            }
        });
    }
    
    // 根据题目类型匹配题目
    private void matchQuestionsByType(QuestionRepository repository, String type) {
        Log.d("QuizActivity", "根据题目类型匹配题目: " + type);
        repository.getQuestionsByType(type, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> result) {
                // 检查Activity是否已经结束
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Log.d("QuizActivity", "获取题目成功，数量: " + (result != null ? result.size() : 0));
                            processQuestionResult(result);
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(QuizActivity.this, "处理题目数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            handleDatabaseError();
                        }
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                // 检查Activity是否已经结束
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.e("QuizActivity", "获取题目失败: " + error);
                        Toast.makeText(QuizActivity.this, "获取题目失败: " + error, Toast.LENGTH_SHORT).show();
                        handleDatabaseError();
                    }
                });
            }
        });
    }
    
    // 根据分类匹配题目
    private void matchQuestionsByCategory(QuestionRepository repository, String category, int limit) {
        Log.d("QuizActivity", "根据分类匹配题目: " + category + ", 数量: " + limit);
        if (limit == -1) {
            // 不限制获取所有题目
            repository.getQuestionsByCategory(category, new QuestionViewModel.GetQuestionsByCategoryCallback() {
                @Override
                public void onSuccess(List<Question> result) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.d("QuizActivity", "获取题目成功，数量: " + (result != null ? result.size() : 0));
                                processQuestionResult(result);
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(QuizActivity.this, "处理题目数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                handleDatabaseError();
                            }
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Log.e("QuizActivity", "获取题目失败: " + error);
                            Toast.makeText(QuizActivity.this, "获取题目失败: " + error, Toast.LENGTH_SHORT).show();
                            handleDatabaseError();
                        }
                    });
                }
            });
        } else {
            // 限制获取指定数量的题目
            repository.getQuestionsByCategory(category, limit, new QuestionRepository.RepositoryCallback<List<Question>>() {
                @Override
                public void onSuccess(List<Question> result) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.d("QuizActivity", "获取题目成功，数量: " + (result != null ? result.size() : 0));
                                processQuestionResult(result);
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(QuizActivity.this, "处理题目数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                handleDatabaseError();
                            }
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.e("QuizActivity", "获取题目失败: " + error);
                                Toast.makeText(QuizActivity.this, "获取题目失败: " + error, Toast.LENGTH_SHORT).show();
                                // 使用模拟数据
                                questions = getMockQuestions(questionCount);
                                initAnswerLists();
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(QuizActivity.this, "初始化模拟数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        }
                    });
                }
            });
        }
    }
    
    // 匹配所有题目或随机题目
    private void matchAllQuestions(QuestionRepository repository, int questionCount) {
        Log.d("QuizActivity", "获取所有题目或随机题目: questionCount=" + questionCount);
        if (questionCount == -1) {
            // 获取所有题目
            repository.getAllQuestions(new QuestionRepository.RepositoryCallback<List<Question>>() {
                @Override
                public void onSuccess(List<Question> result) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.d("QuizActivity", "获取题目成功，数量: " + (result != null ? result.size() : 0));
                                processQuestionResult(result);
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(QuizActivity.this, "初始化题目数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.e("QuizActivity", "获取题目失败: " + error);
                                Toast.makeText(QuizActivity.this, "获取题目失败: " + error, Toast.LENGTH_SHORT).show();
                                // 使用模拟数据
                                questions = getMockQuestions(questionCount);
                                initAnswerLists();
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(QuizActivity.this, "初始化模拟数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        }
                    });
                }
            });
        } else {
            // 随机获取题目
            repository.getRandomQuestions(questionCount, new QuestionRepository.RepositoryCallback<List<Question>>() {
                @Override
                public void onSuccess(List<Question> result) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.d("QuizActivity", "获取题目成功，数量: " + (result != null ? result.size() : 0));
                                processQuestionResult(result);
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(QuizActivity.this, "初始化题目数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.e("QuizActivity", "获取题目失败: " + error);
                                Toast.makeText(QuizActivity.this, "获取题目失败: " + error, Toast.LENGTH_SHORT).show();
                                // 使用模拟数据
                                questions = getMockQuestions(questionCount);
                                initAnswerLists();
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(QuizActivity.this, "初始化模拟数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        }
                    });
                }
            });
        }
    }
    
    // 处理题目结果
    private void processQuestionResult(List<Question> result) {
        if (result == null || result.isEmpty()) {
            Log.d("QuizActivity", "题目列表为空，使用模拟数据");
            questions = getMockQuestions(questionCount);
        } else {
            questions = result;
            // 根据题目顺序模式处理题目列表
            if ("随机".equals(questionOrderMode)) {
                // 随机打乱题目
                java.util.Collections.shuffle(questions);
            }
            // 如果是"顺序"模式，保持原始顺序
        }
        initAnswerLists();
    }
    
    // 处理数据库错误
    private void handleDatabaseError() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 使用模拟数据作为备选
                    questions = getMockQuestions(questionCount);
                    initAnswerLists();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(QuizActivity.this, "初始化模拟数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });
    }

    // 初始化答案列表
    private void initAnswerLists() {
        try {
            // 检查Activity是否已经结束
            if (isFinishing() || isDestroyed()) {
                return;
            }
            
            if (questions == null || questions.isEmpty()) {
                // 如果题目列表为空，使用模拟数据
                try {
                    int count = questionCount > 0 ? questionCount : 10;
                    questions = getMockQuestions(count);
                    // 根据题目顺序模式处理模拟数据
                    if ("随机".equals(questionOrderMode)) {
                        // 随机打乱模拟题目
                        java.util.Collections.shuffle(questions);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(QuizActivity.this, "初始化模拟数据失败", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
                    return;
                }
            }
            
            // 保存原始题目列表用于筛选
            originalQuestions = new ArrayList<>(questions);
            
            // 清空并重新初始化答案和标记列表
            userAnswers.clear();
            markedQuestions.clear();
            
            for (int i = 0; i < questions.size(); i++) {
                userAnswers.add("");
                markedQuestions.add(false);
            }
            
            // 增强时间管理
            enhanceTimeManagement();
            
            // 初始化题目导航，在所有模式下都显示
            navigationLayout = findViewById(R.id.navigationLayout);
            if (navigationLayout != null) {
                setupQuestionNavigation();
            }
            
            // 显示第一题
            showCurrentQuestion();
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(QuizActivity.this, "初始化答案列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }
    }

    // 显示当前题目
    private void showCurrentQuestion() {
        try {
            // 检查Activity是否已经结束
            if (isFinishing() || isDestroyed()) {
                return;
            }
            
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (questions != null && !questions.isEmpty() && currentQuestionIndex >= 0 && currentQuestionIndex < questions.size()) {
                            Question question = questions.get(currentQuestionIndex);
                            if (question != null) {
                                // 检查UI组件是否已经初始化
                                if (textViewQuestion != null) {
                                    String questionText = question.getQuestionText();
                                    textViewQuestion.setText((currentQuestionIndex + 1) + ". " + (questionText != null ? questionText : ""));
                                }
                                
                                // 显示进度
                                if (textViewProgress != null) {
                                    textViewProgress.setText((currentQuestionIndex + 1) + "/" + questions.size());
                                }
                                
                                // 更新进度条
                                if (progressBar != null) {
                                    // 动态设置进度条最大值
                                    progressBar.setMax(questions.size());
                                    // 设置当前进度
                                    progressBar.setProgress(currentQuestionIndex + 1);
                                }
                                
                                // 更新得分显示
                                if (textViewScore != null) {
                                    textViewScore.setText("得分: " + correctCount);
                                }
                                
                                // 显示题型
                                if (textViewQuestionType != null) {
                                    String questionType = question.getQuestionType();
                                    String normalizedType = normalizeQuestionType(questionType, question);
                                    textViewQuestionType.setText(normalizedType);
                                }

                                // 根据题目类型显示不同的UI
                                String questionType = question.getQuestionType();
                                String normalizedType = normalizeQuestionType(questionType, question);
                                showUIForQuestionType(normalizedType, questionType, question);

                                // 恢复之前的选择
                                restoreUserSelection();

                                // 为当前题目设置实时保存
                                setupRealTimeSave();

                                // 加载答案和解析数据
                                showAnswer(question);
                                showExplanation(question);
                                
                                // 根据模式控制答案和解析的显示/隐藏
                                controlAnswerVisibilityByMode();

                                // 更新按钮状态
                                updateButtonStates();
                                
                                // 更新导航按钮样式
                                updateAllNavButtons();
                                // 滚动到当前题目的导航按钮
                                scrollToCurrentQuestion();
                            } else {
                                Toast.makeText(QuizActivity.this, "题目对象为空", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(QuizActivity.this, "题目数据不存在", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(QuizActivity.this, "显示题目失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(QuizActivity.this, "显示题目失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
    
    // 根据模式控制答案和解析的显示/隐藏
    private void controlAnswerVisibilityByMode() {
        View answerCard = findViewById(R.id.answerCard);
        if (answerCard != null) {
            switch (quizMode) {
                case "recite":
                    // 背诵模式：默认显示答案和解析
                    answerCard.setVisibility(View.VISIBLE);
                    if (linearLayoutExplanation != null) {
                        linearLayoutExplanation.setVisibility(View.VISIBLE);
                    }
                    break;
                case "practice":
                    // 练习模式：可随时查看答案
                    answerCard.setVisibility(View.GONE);
                    if (linearLayoutExplanation != null) {
                        linearLayoutExplanation.setVisibility(View.GONE);
                    }
                    break;
                case "exam":
                    // 考试模式：禁止查看答案
                    answerCard.setVisibility(View.GONE);
                    if (linearLayoutExplanation != null) {
                        linearLayoutExplanation.setVisibility(View.GONE);
                    }
                    if (buttonShowAnswer != null) {
                        buttonShowAnswer.setVisibility(View.GONE);
                    }
                    break;
                case "review":
                    // 复习模式：默认显示答案和解析
                    answerCard.setVisibility(View.VISIBLE);
                    if (linearLayoutExplanation != null) {
                        linearLayoutExplanation.setVisibility(View.VISIBLE);
                    }
                    break;
                case "challenge":
                    // 挑战模式：禁止查看答案
                    answerCard.setVisibility(View.GONE);
                    if (linearLayoutExplanation != null) {
                        linearLayoutExplanation.setVisibility(View.GONE);
                    }
                    if (buttonShowAnswer != null) {
                        buttonShowAnswer.setVisibility(View.GONE);
                    }
                    break;
                default:
                    // 默认：隐藏答案和解析
                    answerCard.setVisibility(View.GONE);
                    if (linearLayoutExplanation != null) {
                        linearLayoutExplanation.setVisibility(View.GONE);
                    }
            }
        }
    }
    
    // 更新按钮状态
    private void updateButtonStates() {
        if (buttonPrevious != null) {
            buttonPrevious.setEnabled(currentQuestionIndex > 0);
        }
        if (buttonNext != null) {
            if (quizMode != null && quizMode.equals("recite")) {
                // 背诵模式：除了最后一题显示"→"，最后一题显示"结束"
                if (currentQuestionIndex < questions.size() - 1) {
                    buttonNext.setText("→");
                } else {
                    buttonNext.setText("结束");
                }
            } else {
                // 其他模式：正常显示
                if (currentQuestionIndex < questions.size() - 1) {
                    buttonNext.setText("→");
                } else {
                    buttonNext.setText("提交");
                }
            }
        }
        
        // 根据模式控制标记按钮
        if (buttonMark != null) {
            if (quizMode != null && (quizMode.equals("exam") || quizMode.equals("challenge"))) {
                // 考试和挑战模式允许标记题目
                buttonMark.setVisibility(View.VISIBLE);
            } else {
                // 其他模式隐藏标记按钮
                buttonMark.setVisibility(View.GONE);
            }
        }
    }
    
    // 显示答案
    private void showAnswer(Question question) {
        // 查找答案卡片和其中的textViewAnswer
        View answerCard = findViewById(R.id.answerCard);
        TextView textViewAnswer = findViewById(R.id.textViewAnswer);
        
        if (textViewAnswer != null) {
            String correctAnswer = question.getCorrectAnswer();
            String answerText = buildAnswerText(question, correctAnswer);
            if (answerText != null && !answerText.isEmpty()) {
                textViewAnswer.setText(answerText);
                // 不在这里设置可见性，由showCurrentQuestion和toggleAnswerVisibility控制
            } else {
                textViewAnswer.setText("正确答案: 暂无答案");
                if (answerCard != null) {
                    answerCard.setVisibility(View.GONE);
                }
            }
        }
    }
    
    // 构建答案文本，包含选项内容
    private String buildAnswerText(Question question, String correctAnswer) {
        if (correctAnswer == null || correctAnswer.isEmpty()) {
            return null;
        }
        
        StringBuilder answerBuilder = new StringBuilder();
        answerBuilder.append("正确答案: ");
        
        // 判断题型
        String questionType = question.getQuestionType();
        String normalizedType = normalizeQuestionType(questionType, question);
        
        if ("单选题".equals(normalizedType) || "判断题".equals(normalizedType)) {
            // 单选题/判断题：显示选项字母和内容
            String optionContent = getOptionContentByLetter(question, correctAnswer);
            if (optionContent != null && !optionContent.isEmpty()) {
                answerBuilder.append(correctAnswer).append(". ").append(optionContent);
            } else {
                answerBuilder.append(correctAnswer);
            }
        } else if ("多选题".equals(normalizedType)) {
            // 多选题：显示所有正确选项
            String[] answers = correctAnswer.split("[,，]");
            for (int i = 0; i < answers.length; i++) {
                String answer = answers[i].trim();
                if (i > 0) {
                    answerBuilder.append("，");
                }
                String optionContent = getOptionContentByLetter(question, answer);
                if (optionContent != null && !optionContent.isEmpty()) {
                    answerBuilder.append(answer).append(". ").append(optionContent);
                } else {
                    answerBuilder.append(answer);
                }
            }
        } else {
            // 填空题、简答题等：直接显示答案内容
            answerBuilder.append(correctAnswer);
        }
        
        return answerBuilder.toString();
    }
    
    // 根据选项字母获取选项内容
    private String getOptionContentByLetter(Question question, String letter) {
        if (letter == null || letter.isEmpty()) {
            return null;
        }
        
        String upperLetter = letter.toUpperCase();
        switch (upperLetter) {
            case "A":
                return question.getOptionA();
            case "B":
                return question.getOptionB();
            case "C":
                return question.getOptionC();
            case "D":
                return question.getOptionD();
            default:
                return null;
        }
    }
    
    // 切换答案显示/隐藏
    private void toggleAnswerVisibility() {
        // 操作答案卡片的可见性，而不是直接操作textViewAnswer
        View answerCard = findViewById(R.id.answerCard);
        if (answerCard != null) {
            if (answerCard.getVisibility() == View.VISIBLE) {
                answerCard.setVisibility(View.GONE);
            } else {
                answerCard.setVisibility(View.VISIBLE);
            }
        }
        
        // 同时切换解析的显示
        if (linearLayoutExplanation != null) {
            if (linearLayoutExplanation.getVisibility() == View.VISIBLE) {
                linearLayoutExplanation.setVisibility(View.GONE);
            } else {
                linearLayoutExplanation.setVisibility(View.VISIBLE);
            }
        }
    }
    
    // 显示解析（只加载数据，不控制显示/隐藏）
    private void showExplanation(Question question) {
        if (textViewExplanation != null) {
            String explanation = question.getExplanation();
            if (explanation != null && !explanation.isEmpty()) {
                textViewExplanation.setText(explanation);
                // 注意：不在这里设置可见性，由 toggleAnswerVisibility 控制
            }
        }
    }
    
    // 显示答案按钮点击事件
    private void showAnswerButtonClicked() {
        // 根据模式控制是否允许查看答案
        if (quizMode != null && (quizMode.equals("exam") || quizMode.equals("challenge"))) {
            // 考试和挑战模式禁止查看答案
            Toast.makeText(this, "该模式下禁止查看答案", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (questions != null && currentQuestionIndex < questions.size() && currentQuestionIndex >= 0) {
            Question question = questions.get(currentQuestionIndex);
            // 先确保答案和解析数据已加载
            showAnswer(question);
            showExplanation(question);
            // 切换答案和解析的显示状态
            toggleAnswerVisibility();
        }
    }

    // 保存用户题目类型选择偏好
    private void saveQuestionTypePreference(String selectedType) {
        android.content.SharedPreferences sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE);
        sharedPreferences.edit().putString("preferred_question_type", selectedType).apply();
    }
    
    // 加载用户题目类型选择偏好
    private String loadQuestionTypePreference() {
        android.content.SharedPreferences sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE);
        return sharedPreferences.getString("preferred_question_type", "全部题目");
    }
    
    // 初始化题目类型筛选
    private void initQuestionTypeFilter() {
        if (spinnerQuestionType != null) {
            // 从数据库直接获取当前题库的题目类型
            QuestionRepository repository = new QuestionRepository(getApplication());
            repository.getAllQuestionTypes(new QuestionRepository.RepositoryCallback<List<String>>() {
                @Override
                public void onSuccess(List<String> result) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 准备题目类型列表
                            questionTypes = new ArrayList<>();
                            questionTypes.add("全部题目");
                            
                            // 收集所有唯一的题目类型
                            java.util.Set<String> uniqueTypes = new java.util.HashSet<>();
                            if (result != null) {
                                for (String type : result) {
                                    if (type != null && !type.isEmpty()) {
                                        uniqueTypes.add(type);
                                    }
                                }
                            }
                            
                            // 添加未分类
                            uniqueTypes.add("未分类");
                            
                            // 添加到列表并使用用户友好的名称
                            for (String type : uniqueTypes) {
                                String displayName = getFriendlyQuestionTypeName(type);
                                if (!questionTypes.contains(displayName)) {
                                    questionTypes.add(displayName);
                                }
                            }
                            
                            // 为Spinner设置适配器
                            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(QuizActivity.this,
                                    android.R.layout.simple_spinner_item, questionTypes);
                            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerQuestionType.setAdapter(adapter);
                            
                            // 设置选择监听器
                            spinnerQuestionType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                                private boolean firstSelection = true;
                                
                                @Override
                                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                                    // 忽略初始选择事件，避免重复筛选
                                    if (firstSelection) {
                                        firstSelection = false;
                                        return;
                                    }
                                    
                                    String selectedDisplayName = questionTypes.get(position);
                                    // 保存用户偏好
                                    saveQuestionTypePreference(selectedDisplayName);
                                    filterQuestionsByType(selectedDisplayName);
                                }
                                
                                @Override
                                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                                }
                            });
                            
                            // 根据用户偏好设置默认选择
                            String preferredType = loadQuestionTypePreference();
                            int preferredPosition = questionTypes.indexOf(preferredType);
                            if (preferredPosition >= 0) {
                                spinnerQuestionType.setSelection(preferredPosition);
                            }
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    // 检查Activity是否已经结束
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 出错时使用当前加载的题目列表中的类型作为备选
                            if (questions != null) {
                                // 准备题目类型列表
                                questionTypes = new ArrayList<>();
                                questionTypes.add("全部题目");
                                
                                // 收集所有唯一的题目类型
                                java.util.Set<String> uniqueTypes = new java.util.HashSet<>();
                                for (Question question : questions) {
                                    if (question != null) {
                                        String questionType = question.getQuestionType();
                                        String normalizedType = normalizeQuestionType(questionType, question);
                                        uniqueTypes.add(normalizedType);
                                    }
                                }
                                
                                // 添加未分类
                                uniqueTypes.add("未分类");
                                
                                // 添加到列表并使用用户友好的名称
                                for (String type : uniqueTypes) {
                                    String displayName = getFriendlyQuestionTypeName(type);
                                    if (!questionTypes.contains(displayName)) {
                                        questionTypes.add(displayName);
                                    }
                                }
                                
                                // 为Spinner设置适配器
                                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(QuizActivity.this,
                                        android.R.layout.simple_spinner_item, questionTypes);
                                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                                spinnerQuestionType.setAdapter(adapter);
                                
                                // 设置选择监听器
                                spinnerQuestionType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                                    private boolean firstSelection = true;
                                    
                                    @Override
                                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                                        // 忽略初始选择事件，避免重复筛选
                                        if (firstSelection) {
                                            firstSelection = false;
                                            return;
                                        }
                                        
                                        String selectedDisplayName = questionTypes.get(position);
                                        // 保存用户偏好
                                        saveQuestionTypePreference(selectedDisplayName);
                                        filterQuestionsByType(selectedDisplayName);
                                    }
                                    
                                    @Override
                                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                                    }
                                });
                                
                                // 根据用户偏好设置默认选择
                                String preferredType = loadQuestionTypePreference();
                                int preferredPosition = questionTypes.indexOf(preferredType);
                                if (preferredPosition >= 0) {
                                    spinnerQuestionType.setSelection(preferredPosition);
                                }
                            } else {
                                // 如果当前没有加载的题目，只显示"全部题目"
                                questionTypes = new ArrayList<>();
                                questionTypes.add("全部题目");
                                questionTypes.add("未分类");
                                
                                // 为Spinner设置适配器
                                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(QuizActivity.this,
                                        android.R.layout.simple_spinner_item, questionTypes);
                                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                                spinnerQuestionType.setAdapter(adapter);
                                
                                // 设置选择监听器
                                spinnerQuestionType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                                    private boolean firstSelection = true;
                                    
                                    @Override
                                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                                        // 忽略初始选择事件，避免重复筛选
                                        if (firstSelection) {
                                            firstSelection = false;
                                            return;
                                        }
                                        
                                        String selectedDisplayName = questionTypes.get(position);
                                        // 保存用户偏好
                                        saveQuestionTypePreference(selectedDisplayName);
                                        filterQuestionsByType(selectedDisplayName);
                                    }
                                    
                                    @Override
                                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                                    }
                                });
                            }
                        }
                    });
                }
            });
        }
    }
    
    // 过滤题目按类型
    private void filterQuestionsByType(String selectedType) {
        if (originalQuestions != null) {
            if ("全部题目".equals(selectedType)) {
                questions = new ArrayList<>(originalQuestions);
            } else if ("未分类".equals(selectedType)) {
                questions = new ArrayList<>();
                for (Question question : originalQuestions) {
                    if (question != null && (question.getQuestionType() == null || question.getQuestionType().isEmpty())) {
                        questions.add(question);
                    }
                }
            } else {
                questions = new ArrayList<>();
                for (Question question : originalQuestions) {
                    if (question != null) {
                        String questionType = question.getQuestionType();
                        String normalizedType = normalizeQuestionType(questionType, question);
                        String friendlyType = getFriendlyQuestionTypeName(normalizedType);
                        if (selectedType.equals(friendlyType)) {
                            questions.add(question);
                        }
                    }
                }
            }
            
            // 重新初始化答案列表
            initAnswerLists();
        }
    }
    
    // 初始化题目顺序选择器
    private void initQuestionOrderSelector() {
        if (spinnerQuestionOrder != null) {
            List<String> orderOptions = new ArrayList<>();
            orderOptions.add("顺序");
            orderOptions.add("随机");
            
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, orderOptions);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerQuestionOrder.setAdapter(adapter);
            
            spinnerQuestionOrder.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                private boolean firstSelection = true;
                
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (firstSelection) {
                        firstSelection = false;
                        return;
                    }
                    
                    questionOrderMode = orderOptions.get(position);
                    if ("随机".equals(questionOrderMode)) {
                        java.util.Collections.shuffle(questions);
                    } else {
                        // 顺序模式，恢复原始顺序
                        questions = new ArrayList<>(originalQuestions);
                    }
                    initAnswerLists();
                }
                
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });
        }
    }
    
    // 设置题目导航
    private void setupQuestionNavigation() {
        if (navigationLayout != null && questions != null) {
            navigationLayout.removeAllViews();
            
            // 设置固定大小的圆形按钮，进一步增大宽度以确保三位数的题目编号能完整显示
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    100, // 进一步增大宽度以适应三位数
                    60  // 适中的高度
            );
            params.setMargins(
                    getResources().getDimensionPixelSize(R.dimen.spacing_8),
                    0,
                    getResources().getDimensionPixelSize(R.dimen.spacing_8),
                    0
            );
            
            for (int i = 0; i < questions.size(); i++) {
                final int questionIndex = i;
                com.google.android.material.button.MaterialButton navButton = new com.google.android.material.button.MaterialButton(this);
                navButton.setLayoutParams(params);
                String questionNumber = String.valueOf(i + 1);
                navButton.setText(questionNumber);
                
                // 根据题目编号长度自动调整字体大小
                if (questionNumber.length() > 2) {
                    // 三位数或四位数，使用较小的字体
                    navButton.setTextSize(14);
                } else {
                    // 一位数或两位数，使用正常字体
                    navButton.setTextSize(16);
                }
                
                navButton.setTag(questionIndex);
                navButton.setGravity(android.view.Gravity.CENTER);
                
                // 根据题目状态设置按钮样式
                if (i == currentQuestionIndex) {
                    // 当前题目
                    navButton.setBackgroundColor(getResources().getColor(R.color.gray_100));
                    navButton.setTextColor(getResources().getColor(R.color.text_primary));
                    navButton.setStrokeWidth(2);
                    navButton.setStrokeColor(android.content.res.ColorStateList.valueOf(currentThemeColor));
                } else if (i < currentQuestionIndex) {
                    // 已回答的题目
                    navButton.setBackgroundColor(getResources().getColor(R.color.gray_50));
                    navButton.setTextColor(getResources().getColor(R.color.text_primary));
                    navButton.setStrokeWidth(1);
                    navButton.setStrokeColor(android.content.res.ColorStateList.valueOf(currentThemeColor));
                } else {
                    // 未回答的题目
                    navButton.setBackgroundColor(getResources().getColor(R.color.gray_50));
                    navButton.setTextColor(getResources().getColor(R.color.text_primary));
                    navButton.setStrokeWidth(1);
                    navButton.setStrokeColor(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.gray_400)));
                }
                
                // 移除图标，只显示题目编号，确保数字能够完整显示
                navButton.setIcon(null);
                
                // 标记的题目显示星号
                if (markedQuestions.get(i)) {
                    navButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_star, 0);
                }
                
                // 设置点击事件
                navButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        saveUserSelection();
                        currentQuestionIndex = questionIndex;
                        showCurrentQuestion();
                    }
                });
                
                navigationLayout.addView(navButton);
            }
        }
    }
    
    // 更新所有导航按钮
    private void updateAllNavButtons() {
        if (navigationLayout != null && questions != null) {
            int childCount = navigationLayout.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = navigationLayout.getChildAt(i);
                if (child instanceof com.google.android.material.button.MaterialButton) {
                    com.google.android.material.button.MaterialButton navButton = (com.google.android.material.button.MaterialButton) child;
                    int questionIndex = (int) navButton.getTag();
                    
                    // 根据题目状态更新按钮样式
                    if (questionIndex == currentQuestionIndex) {
                        // 当前题目
                        navButton.setBackgroundResource(R.drawable.nav_button_current);
                        navButton.setTextColor(getResources().getColor(R.color.white));
                    } else if (questionIndex < currentQuestionIndex) {
                        // 已回答的题目
                        navButton.setBackgroundResource(R.drawable.nav_button_answered);
                        navButton.setTextColor(getResources().getColor(R.color.white));
                    } else {
                        // 未回答的题目
                        navButton.setBackgroundResource(R.drawable.nav_button_unanswered);
                        navButton.setTextColor(currentThemeColor);
                    }
                    
                    // 标记的题目显示星号
                    if (markedQuestions.get(questionIndex)) {
                        navButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_star, 0);
                    } else {
                        navButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    }
                }
            }
        }
    }
    
    // 滚动到当前题目
    private void scrollToCurrentQuestion() {
        if (navigationLayout != null && questions != null && currentQuestionIndex >= 0) {
            // 计算当前按钮的位置
            int childCount = navigationLayout.getChildCount();
            if (currentQuestionIndex < childCount) {
                View currentButton = navigationLayout.getChildAt(currentQuestionIndex);
                if (currentButton != null) {
                    // 滚动到当前按钮
                    currentButton.post(new Runnable() {
                        @Override
                        public void run() {
                            currentButton.requestFocus();
                            // 找到HorizontalScrollView父容器
                            android.view.ViewParent parent = navigationLayout.getParent();
                            if (parent instanceof android.widget.HorizontalScrollView) {
                                android.widget.HorizontalScrollView scrollView = (android.widget.HorizontalScrollView) parent;
                                // 计算滚动位置，使当前按钮居中
                                int scrollX = currentButton.getLeft() - scrollView.getWidth() / 2 + currentButton.getWidth() / 2;
                                scrollView.smoothScrollTo(scrollX, 0);
                            } else {
                                // 兼容旧的滚动方式
                                navigationLayout.scrollTo(currentButton.getLeft() - navigationLayout.getWidth() / 2 + currentButton.getWidth() / 2, 0);
                            }
                        }
                    });
                }
            }
        }
    }
    
    // 增强时间管理
    private void enhanceTimeManagement() {
        // 根据模式设置不同的时间限制
        long timeLimit = 0;
        if (quizMode != null && questions != null && !questions.isEmpty()) {
            switch (quizMode) {
                case "exam":
                    // 考试模式：每道题2分钟
                    timeLimit = questions.size() * 2 * 60 * 1000;
                    break;
                case "challenge":
                    // 挑战模式：10分钟
                    timeLimit = 10 * 60 * 1000;
                    break;
                case "practice":
                    // 练习模式：每道题3分钟
                    timeLimit = questions.size() * 3 * 60 * 1000;
                    break;
                case "review":
                    // 复习模式：每道题2.5分钟
                    timeLimit = questions.size() * 2 * 60 * 1000 + questions.size() * 30 * 1000;
                    break;
                default:
                    // 其他模式：无时间限制
                    timeLimit = 0;
                    break;
            }
        }
        
        if (timeLimit > 0) {
            startCountDownTimer(timeLimit);
        } else {
            // 无时间限制时显示 "无限制"
            if (textViewTimer != null) {
                textViewTimer.setText("无限制");
            }
        }
    }
    
    // 开始倒计时
    private void startCountDownTimer(long timeLimit) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        
        final long totalTime = timeLimit;
        
        countDownTimer = new CountDownTimer(timeLimit, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (textViewTimer != null) {
                    int minutes = (int) (millisUntilFinished / 60000);
                    int seconds = (int) ((millisUntilFinished % 60000) / 1000);
                    textViewTimer.setText(String.format("%02d:%02d", minutes, seconds));
                    
                    // 根据剩余时间设置不同的颜色
                    float progress = (float) millisUntilFinished / totalTime;
                    if (progress > 0.5) {
                        // 剩余时间充足
                        textViewTimer.setTextColor(getResources().getColor(R.color.white));
                    } else if (progress > 0.25) {
                        // 剩余时间中等
                        textViewTimer.setTextColor(getResources().getColor(R.color.yellow));
                    } else {
                        // 剩余时间不足
                        textViewTimer.setTextColor(getResources().getColor(R.color.red));
                        
                        // 最后10秒添加闪烁效果
                        if (millisUntilFinished < 10000) {
                            textViewTimer.animate()
                                    .alpha(0.5f)
                                    .setDuration(300)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            textViewTimer.animate().alpha(1.0f).setDuration(300);
                                        }
                                    });
                        }
                    }
                }
            }
            
            @Override
            public void onFinish() {
                if (textViewTimer != null) {
                    textViewTimer.setText("00:00");
                    textViewTimer.setTextColor(getResources().getColor(R.color.red));
                    
                    // 时间到的动画效果
                    textViewTimer.animate()
                            .scaleX(1.2f)
                            .scaleY(1.2f)
                            .setDuration(500)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    textViewTimer.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300);
                                }
                            });
                }
                // 时间到，自动提交
                submitQuiz();
            }
        }.start();
    }
    
    // 设置实时保存
    private void setupRealTimeSave() {
        // 为单选按钮组添加实时保存
        if (radioGroupOptions != null) {
            radioGroupOptions.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    saveUserSelection();
                    saveQuizProgress();
                }
            });
        }
        
        // 为填空题编辑框添加实时保存
        if (editTextUserAnswer != null) {
            editTextUserAnswer.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    saveUserSelection();
                    saveQuizProgress();
                }
            });
        }
    }
    
    // 保存测验进度
    private void saveQuizProgress() {
        android.content.SharedPreferences sharedPreferences = getSharedPreferences("quiz_progress", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = sharedPreferences.edit();
        
        // 保存当前题目索引
        editor.putInt("currentQuestionIndex", currentQuestionIndex);
        
        // 保存用户答案
        StringBuilder answersBuilder = new StringBuilder();
        for (String answer : userAnswers) {
            answersBuilder.append(answer).append("|");
        }
        editor.putString("userAnswers", answersBuilder.toString());
        
        // 保存标记的题目
        StringBuilder markedBuilder = new StringBuilder();
        for (Boolean marked : markedQuestions) {
            markedBuilder.append(marked ? "1" : "0").append("|");
        }
        editor.putString("markedQuestions", markedBuilder.toString());
        
        // 保存开始时间
        editor.putLong("startTime", startTime);
        
        editor.apply();
    }
    
    // 加载测验进度
    private void loadQuizProgress() {
        android.content.SharedPreferences sharedPreferences = getSharedPreferences("quiz_progress", MODE_PRIVATE);
        
        // 加载当前题目索引
        int savedIndex = sharedPreferences.getInt("currentQuestionIndex", -1);
        if (savedIndex >= 0 && savedIndex < questions.size()) {
            currentQuestionIndex = savedIndex;
        }
        
        // 加载用户答案
        String answersString = sharedPreferences.getString("userAnswers", "");
        if (!answersString.isEmpty()) {
            String[] savedAnswers = answersString.split("\\|");
            for (int i = 0; i < savedAnswers.length && i < userAnswers.size(); i++) {
                userAnswers.set(i, savedAnswers[i]);
            }
        }
        
        // 加载标记的题目
        String markedString = sharedPreferences.getString("markedQuestions", "");
        if (!markedString.isEmpty()) {
            String[] savedMarked = markedString.split("\\|");
            for (int i = 0; i < savedMarked.length && i < markedQuestions.size(); i++) {
                markedQuestions.set(i, "1".equals(savedMarked[i]));
            }
        }
        
        // 加载开始时间
        long savedStartTime = sharedPreferences.getLong("startTime", 0);
        if (savedStartTime > 0) {
            startTime = savedStartTime;
        }
    }
    
    // 根据题目类型显示不同的UI
    private void showUIForQuestionType(String normalizedType, String originalType, Question question) {
        // 隐藏所有选项UI
        if (radioGroupOptions != null) {
            radioGroupOptions.setVisibility(View.GONE);
        }
        if (checkBoxContainer != null) {
            checkBoxContainer.setVisibility(View.GONE);
        }
        if (answerInputLayout != null) {
            answerInputLayout.setVisibility(View.GONE);
        }
        if (editTextUserAnswer != null) {
            editTextUserAnswer.setVisibility(View.GONE);
        }
        
        // 检查是否有选项
        boolean hasOptions = hasValidOptions(question);
        
        // 添加详细调试日志
        Log.d("QuizActivity", "显示题目UI: normalizedType=" + normalizedType + ", originalType=" + originalType);
        Log.d("QuizActivity", "选项检查: hasOptions=" + hasOptions);
        Log.d("QuizActivity", "选项A=" + question.getOptionA());
        Log.d("QuizActivity", "选项B=" + question.getOptionB());
        Log.d("QuizActivity", "选项C=" + question.getOptionC());
        Log.d("QuizActivity", "选项D=" + question.getOptionD());
        
        // 添加调试日志
        Log.d("QuizActivity", "显示题目UI: type=" + normalizedType + ", hasOptions=" + hasOptions);
        Log.d("QuizActivity", "选项: A=" + question.getOptionA() + ", B=" + question.getOptionB() + ", C=" + question.getOptionC() + ", D=" + question.getOptionD());
        
        // 判断是否是背诵模式
        boolean isReciteMode = quizMode != null && quizMode.equals("recite");
        
        // 根据题目类型显示对应的UI
        if (normalizedType != null) {
            switch (normalizedType) {
                case "单选题":
                case "判断题":
                    // 显示单选按钮组（即使没有选项也显示，以便用户知道这是单选题）
                    if (radioGroupOptions != null) {
                        if (hasOptions) {
                            radioGroupOptions.setVisibility(View.VISIBLE);
                            // 根据实际选项数量显示选项
                            updateRadioButtonVisibility(question);
                        } else {
                            // 如果没有选项，显示提示信息
                            radioGroupOptions.removeAllViews();
                            TextView hintText = new TextView(this);
                            hintText.setText("（此题无选项，请直接作答）");
                            hintText.setTextColor(getResources().getColor(R.color.text_secondary));
                            radioGroupOptions.addView(hintText);
                            radioGroupOptions.setVisibility(View.VISIBLE);
                        }
                        // 背诵模式下禁用单选按钮
                        if (isReciteMode) {
                            for (int i = 0; i < radioGroupOptions.getChildCount(); i++) {
                                View child = radioGroupOptions.getChildAt(i);
                                if (child instanceof RadioButton) {
                                    child.setEnabled(false);
                                }
                            }
                        }
                    }
                    break;
                    
                case "多选题":
                    // 显示复选框组
                    if (checkBoxContainer != null) {
                        if (hasOptions) {
                            checkBoxContainer.setVisibility(View.VISIBLE);
                            // 根据实际选项数量显示选项
                            updateCheckBoxVisibility(question);
                        } else {
                            // 如果没有选项，显示提示信息
                            checkBoxContainer.removeAllViews();
                            TextView hintText = new TextView(this);
                            hintText.setText("（此题无选项，请直接作答）");
                            hintText.setTextColor(getResources().getColor(R.color.text_secondary));
                            checkBoxContainer.addView(hintText);
                            checkBoxContainer.setVisibility(View.VISIBLE);
                        }
                        // 背诵模式下禁用复选框
                        if (isReciteMode) {
                            for (int i = 0; i < checkBoxContainer.getChildCount(); i++) {
                                View child = checkBoxContainer.getChildAt(i);
                                if (child instanceof CheckBox) {
                                    child.setEnabled(false);
                                }
                            }
                        }
                    }
                    break;
                    
                case "填空题":
                case "简答题":
                case "问答题":
                case "论述题":
                    // 确保隐藏选项UI
                    if (radioGroupOptions != null) {
                        radioGroupOptions.setVisibility(View.GONE);
                    }
                    if (checkBoxContainer != null) {
                        checkBoxContainer.setVisibility(View.GONE);
                    }
                    // 显示编辑框容器和编辑框（所有模式都显示，但背诵模式禁用）
                    if (answerInputLayout != null) {
                        answerInputLayout.setVisibility(View.VISIBLE);
                    }
                    if (editTextUserAnswer != null) {
                        editTextUserAnswer.setVisibility(View.VISIBLE);
                        // 根据题型设置不同的提示文字
                        if ("填空题".equals(normalizedType)) {
                            answerInputLayout.setHint("请填写答案");
                        } else if ("简答题".equals(normalizedType)) {
                            answerInputLayout.setHint("请简要回答");
                        } else if ("问答题".equals(normalizedType)) {
                            answerInputLayout.setHint("请详细回答");
                        } else if ("论述题".equals(normalizedType)) {
                            answerInputLayout.setHint("请展开论述");
                        }
                        // 背诵模式下禁用编辑框
                        if (isReciteMode) {
                            editTextUserAnswer.setEnabled(false);
                        } else {
                            editTextUserAnswer.setEnabled(true);
                        }
                    }
                    break;
                    
                default:
                    // 默认情况下，当作单选题处理
                    if (radioGroupOptions != null) {
                        if (hasOptions) {
                            radioGroupOptions.setVisibility(View.VISIBLE);
                            // 根据实际选项数量显示选项
                            updateRadioButtonVisibility(question);
                        } else {
                            // 如果没有选项，显示提示信息
                            radioGroupOptions.removeAllViews();
                            TextView hintText = new TextView(this);
                            hintText.setText("（此题无选项，请直接作答）");
                            hintText.setTextColor(getResources().getColor(R.color.text_secondary));
                            radioGroupOptions.addView(hintText);
                            radioGroupOptions.setVisibility(View.VISIBLE);
                        }
                        // 背诵模式下禁用单选按钮
                        if (isReciteMode) {
                            for (int i = 0; i < radioGroupOptions.getChildCount(); i++) {
                                View child = radioGroupOptions.getChildAt(i);
                                if (child instanceof RadioButton) {
                                    child.setEnabled(false);
                                }
                            }
                        }
                    }
                    break;
            }
        }
    }
    
    // 检查题目是否有有效的选项
    private boolean hasValidOptions(Question question) {
        return (question.getOptionA() != null && !question.getOptionA().isEmpty()) ||
               (question.getOptionB() != null && !question.getOptionB().isEmpty()) ||
               (question.getOptionC() != null && !question.getOptionC().isEmpty()) ||
               (question.getOptionD() != null && !question.getOptionD().isEmpty());
    }
    
    // 获取所有有效的选项
    private List<String> getValidOptions(Question question) {
        List<String> options = new ArrayList<>();
        if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
            options.add(question.getOptionA());
        }
        if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
            options.add(question.getOptionB());
        }
        if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
            options.add(question.getOptionC());
        }
        if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
            options.add(question.getOptionD());
        }
        return options;
    }
    
    // 创建单个 RadioButton
    private com.google.android.material.radiobutton.MaterialRadioButton createRadioButton(String optionText, int index) {
        com.google.android.material.radiobutton.MaterialRadioButton radioButton = new com.google.android.material.radiobutton.MaterialRadioButton(this);
        radioButton.setId(View.generateViewId());
        radioButton.setTag(index);
        radioButton.setText(((char) ('A' + index)) + ". " + optionText);
        radioButton.setTextAppearance(R.style.TextAppearance_SmartQuiz_BodyLarge);
        radioButton.setTextColor(getResources().getColor(R.color.text_primary));
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        int marginBottom = (int) getResources().getDimension(R.dimen.radio_button_margin_bottom);
        params.setMargins(0, 0, 0, marginBottom);
        radioButton.setLayoutParams(params);
        
        radioButton.setBackgroundResource(R.drawable.option_background);
        radioButton.setPadding(
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16)
        );
        radioButton.setButtonTintList(android.content.res.ColorStateList.valueOf(currentThemeColor));
        
        return radioButton;
    }
    
    // 创建单个 CheckBox
    private com.google.android.material.checkbox.MaterialCheckBox createCheckBox(String optionText, int index) {
        com.google.android.material.checkbox.MaterialCheckBox checkBox = new com.google.android.material.checkbox.MaterialCheckBox(this);
        checkBox.setId(View.generateViewId());
        checkBox.setTag(index);
        checkBox.setText(((char) ('A' + index)) + ". " + optionText);
        checkBox.setTextAppearance(R.style.TextAppearance_SmartQuiz_BodyLarge);
        checkBox.setTextColor(getResources().getColor(R.color.text_primary));
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        int marginBottom = (int) getResources().getDimension(R.dimen.radio_button_margin_bottom);
        params.setMargins(0, 0, 0, marginBottom);
        checkBox.setLayoutParams(params);
        
        checkBox.setBackgroundResource(R.drawable.option_background);
        checkBox.setPadding(
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16),
                getResources().getDimensionPixelSize(R.dimen.spacing_16)
        );
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(currentThemeColor));
        
        // 为复选框添加实时保存监听器
        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                saveUserSelection();
                saveQuizProgress();
            }
        });
        
        return checkBox;
    }
    
    // 更新单选按钮（动态创建）
    private void updateRadioButtonVisibility(Question question) {
        if (radioGroupOptions != null) {
            radioGroupOptions.removeAllViews();
            radioButtons.clear();
            
            List<String> options = getValidOptions(question);
            for (int i = 0; i < options.size(); i++) {
                com.google.android.material.radiobutton.MaterialRadioButton radioButton = createRadioButton(options.get(i), i);
                radioGroupOptions.addView(radioButton);
                radioButtons.add(radioButton);
            }
        }
    }
    
    // 更新复选框（动态创建）
    private void updateCheckBoxVisibility(Question question) {
        if (checkBoxContainer != null) {
            checkBoxContainer.removeAllViews();
            checkBoxes.clear();
            
            List<String> options = getValidOptions(question);
            for (int i = 0; i < options.size(); i++) {
                com.google.android.material.checkbox.MaterialCheckBox checkBox = createCheckBox(options.get(i), i);
                checkBoxContainer.addView(checkBox);
                checkBoxes.add(checkBox);
            }
        }
    }
    
    // 更新选项按钮颜色
    private void updateOptionButtonColors() {
        // 更新单选按钮颜色
        for (com.google.android.material.radiobutton.MaterialRadioButton radioButton : radioButtons) {
            radioButton.setButtonTintList(android.content.res.ColorStateList.valueOf(currentThemeColor));
        }
        
        // 更新复选框颜色
        for (com.google.android.material.checkbox.MaterialCheckBox checkBox : checkBoxes) {
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(currentThemeColor));
        }
    }
    
    // 恢复用户选择
    private void restoreUserSelection() {
        if (currentQuestionIndex >= 0 && currentQuestionIndex < userAnswers.size()) {
            String userAnswer = userAnswers.get(currentQuestionIndex);
            if (userAnswer != null && !userAnswer.isEmpty()) {
                Question question = questions.get(currentQuestionIndex);
                String questionType = question.getQuestionType();
                String normalizedType = normalizeQuestionType(questionType, question);
                
                switch (normalizedType) {
                    case "单选题":
                    case "判断题":
                        // 恢复单选按钮选择
                        if (radioGroupOptions != null) {
                            radioGroupOptions.clearCheck();
                            for (int i = 0; i < radioButtons.size(); i++) {
                                String optionLabel = String.valueOf((char) ('A' + i));
                                if (optionLabel.equals(userAnswer)) {
                                    radioButtons.get(i).setChecked(true);
                                    break;
                                }
                            }
                        }
                        break;
                        
                    case "多选题":
                        // 恢复复选框选择
                        if (checkBoxContainer != null) {
                            for (int i = 0; i < checkBoxes.size(); i++) {
                                String optionLabel = String.valueOf((char) ('A' + i));
                                checkBoxes.get(i).setChecked(userAnswer.contains(optionLabel));
                            }
                        }
                        break;
                        
                    case "填空题":
                    case "简答题":
                    case "问答题":
                    case "论述题":
                        // 恢复编辑框内容
                        if (editTextUserAnswer != null) {
                            editTextUserAnswer.setText(userAnswer);
                        }
                        break;
                }
            } else {
                // 清除选择
                if (radioGroupOptions != null) {
                    radioGroupOptions.clearCheck();
                }
                if (checkBoxContainer != null) {
                    for (CheckBox checkBox : checkBoxes) {
                        checkBox.setChecked(false);
                    }
                }
                if (editTextUserAnswer != null) {
                    editTextUserAnswer.setText("");
                }
            }
        }
    }
    
    // 保存用户选择
    private void saveUserSelection() {
        if (currentQuestionIndex >= 0 && currentQuestionIndex < userAnswers.size()) {
            Question question = questions.get(currentQuestionIndex);
            String questionType = question.getQuestionType();
            String normalizedType = normalizeQuestionType(questionType, question);
            String userAnswer = "";
            
            switch (normalizedType) {
                case "单选题":
                case "判断题":
                    // 保存单选按钮选择
                    if (radioGroupOptions != null) {
                        for (int i = 0; i < radioButtons.size(); i++) {
                            if (radioButtons.get(i).isChecked()) {
                                userAnswer = String.valueOf((char) ('A' + i));
                                break;
                            }
                        }
                    }
                    break;
                    
                case "多选题":
                    // 保存复选框选择
                    if (checkBoxContainer != null) {
                        StringBuilder answerBuilder = new StringBuilder();
                        for (int i = 0; i < checkBoxes.size(); i++) {
                            if (checkBoxes.get(i).isChecked()) {
                                answerBuilder.append((char) ('A' + i));
                            }
                        }
                        userAnswer = answerBuilder.toString();
                    }
                    break;
                    
                case "填空题":
                case "简答题":
                case "问答题":
                case "论述题":
                    // 保存编辑框内容
                    if (editTextUserAnswer != null) {
                        userAnswer = editTextUserAnswer.getText().toString().trim();
                    }
                    break;
            }
            
            userAnswers.set(currentQuestionIndex, userAnswer);
        }
    }
    
    // 检查答案
    private void checkAnswer() {
        if (currentQuestionIndex >= 0 && currentQuestionIndex < questions.size()) {
            saveUserSelection();
            Question question = questions.get(currentQuestionIndex);
            String userAnswer = userAnswers.get(currentQuestionIndex);
            String correctAnswer = question.getCorrectAnswer();
            
            if (correctAnswer != null && !correctAnswer.isEmpty()) {
                boolean isCorrect = false;
                String questionType = question.getQuestionType();
                String normalizedType = normalizeQuestionType(questionType, question);
                
                switch (normalizedType) {
                    case "单选题":
                    case "判断题":
                        // 单选题和判断题：直接比较答案
                        isCorrect = correctAnswer.equals(userAnswer);
                        break;
                    case "多选题":
                        // 多选题：比较排序后的答案
                        if (userAnswer != null) {
                            char[] userChars = userAnswer.toCharArray();
                            char[] correctChars = correctAnswer.toCharArray();
                            java.util.Arrays.sort(userChars);
                            java.util.Arrays.sort(correctChars);
                            isCorrect = java.util.Arrays.equals(userChars, correctChars);
                        }
                        break;
                    case "填空题":
                    case "简答题":
                    case "问答题":
                    case "论述题":
                        // 填空题、简答题、问答题和论述题：简单比较
                        isCorrect = correctAnswer.equals(userAnswer);
                        break;
                }
                
                // 显示答案和解析
                TextView textViewAnswer = findViewById(R.id.textViewAnswer);
                if (textViewAnswer != null) {
                    textViewAnswer.setVisibility(View.VISIBLE);
                    if (isCorrect) {
                        textViewAnswer.setTextColor(getResources().getColor(R.color.green));
                        textViewAnswer.setText("正确答案: " + correctAnswer + " ✓");
                        correctCount++;
                        
                        // 正确答案的动画效果
                        textViewAnswer.animate()
                                .scaleX(1.1f)
                                .scaleY(1.1f)
                                .setDuration(300)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        textViewAnswer.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200);
                                    }
                                });
                    } else {
                        textViewAnswer.setTextColor(getResources().getColor(R.color.red));
                        textViewAnswer.setText("正确答案: " + correctAnswer + " ✗");
                        
                        // 错误答案的动画效果
                        textViewAnswer.animate()
                                .translationX(-10)
                                .setDuration(100)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        textViewAnswer.animate().translationX(10).setDuration(100)
                                                .withEndAction(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        textViewAnswer.animate().translationX(0).setDuration(100);
                                                    }
                                                });
                                    }
                                });
                    }
                }
                
                // 显示解析
                if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    if (textViewExplanation != null) {
                        textViewExplanation.setText(question.getExplanation());
                    }
                    if (linearLayoutExplanation != null) {
                        linearLayoutExplanation.setVisibility(View.VISIBLE);
                        // 解析区域的淡入动画
                        linearLayoutExplanation.setAlpha(0f);
                        linearLayoutExplanation.animate().alpha(1f).setDuration(500);
                    }
                }
                
                // 更新得分显示
                if (textViewScore != null) {
                    textViewScore.setText("得分: " + correctCount);
                    // 得分更新的动画效果
                    textViewScore.animate()
                            .scaleX(1.2f)
                            .scaleY(1.2f)
                            .setDuration(200)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    textViewScore.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100);
                                }
                            });
                }
                
                // 记录错题
                if (!isCorrect) {
                    recordWrongQuestion(question, userAnswer);
                }
            }
        }
    }
    
    // 记录错题
    private void recordWrongQuestion(Question question, String userAnswer) {
        WrongQuestion wrongQuestion = new WrongQuestion();
        wrongQuestion.setQuestionId(question.getId());
        // 假设当前用户ID为1，实际应用中应该从用户会话中获取
        wrongQuestion.setUserId(1L);
        wrongQuestion.setWrongCount(1);
        wrongQuestion.setLastWrongTime(System.currentTimeMillis());
        wrongQuestion.setUserAnswer(userAnswer);
        
        WrongQuestionRepository repository = new WrongQuestionRepository(getApplication());
        repository.addWrongQuestion(wrongQuestion, new WrongQuestionRepository.RepositoryCallback<Long>() {
            @Override
            public void onSuccess(Long id) {
                Log.d("QuizActivity", "错题记录成功: " + id);
            }
            
            @Override
            public void onError(String error) {
                Log.e("QuizActivity", "错题记录失败: " + error);
            }
        });
    }
    
    // 显示上一题
    private void showPreviousQuestion() {
        // 保存当前题目的答案
        saveUserSelection();
        
        if (currentQuestionIndex > 0) {
            currentQuestionIndex--;
            showCurrentQuestion();
        }
    }
    
    // 显示下一题
    private void showNextQuestion() {
        // 保存当前题目的答案
        saveUserSelection();
        
        if (currentQuestionIndex < questions.size() - 1) {
            currentQuestionIndex++;
            showCurrentQuestion();
        } else {
            // 最后一题，提交答案
            submitQuiz();
        }
    }
    
    // 切换标记题目
    private void toggleMarkQuestion() {
        if (currentQuestionIndex >= 0 && currentQuestionIndex < markedQuestions.size()) {
            markedQuestions.set(currentQuestionIndex, !markedQuestions.get(currentQuestionIndex));
            // 更新标记状态
        }
    }
    
    // 提交测验
    private void submitQuiz() {
        // 保存最后一题的答案
        saveUserSelection();
        
        // 计算得分
        int totalQuestions = questions.size();
        int correctCount = 0;
        List<Question> wrongQuestions = new ArrayList<>();
        List<String> userAnswersList = new ArrayList<>();
        
        for (int i = 0; i < totalQuestions; i++) {
            Question question = questions.get(i);
            String userAnswer = userAnswers.get(i);
            String correctAnswer = question.getCorrectAnswer();
            
            if (correctAnswer != null && !correctAnswer.isEmpty()) {
                boolean isCorrect = false;
                String questionType = question.getQuestionType();
                String normalizedType = normalizeQuestionType(questionType, question);
                
                switch (normalizedType) {
                    case "单选题":
                    case "判断题":
                        isCorrect = correctAnswer.equals(userAnswer);
                        break;
                    case "多选题":
                        if (userAnswer != null) {
                            char[] userChars = userAnswer.toCharArray();
                            char[] correctChars = correctAnswer.toCharArray();
                            java.util.Arrays.sort(userChars);
                            java.util.Arrays.sort(correctChars);
                            isCorrect = java.util.Arrays.equals(userChars, correctChars);
                        }
                        break;
                    case "填空题":
                    case "简答题":
                    case "问答题":
                    case "论述题":
                        isCorrect = correctAnswer.equals(userAnswer);
                        break;
                }
                
                if (isCorrect) {
                    correctCount++;
                } else {
                    wrongQuestions.add(question);
                    userAnswersList.add(userAnswer);
                }
            }
        }
        
        // 计算得分百分比
        int score = totalQuestions > 0 ? (correctCount * 100) / totalQuestions : 0;
        long timeUsed = System.currentTimeMillis() - startTime;
        
        // 保存得分记录
        saveScoreRecord(score, totalQuestions, correctCount, timeUsed);
        
        // 记录错题
        for (int i = 0; i < wrongQuestions.size(); i++) {
            recordWrongQuestion(wrongQuestions.get(i), userAnswersList.get(i));
        }
        
        // 显示结果
        showQuizResult(score, correctCount, totalQuestions, timeUsed);
        
        // 清除进度保存
        clearQuizProgress();
    }
    
    // 显示测验结果
    private void showQuizResult(int score, int correctCount, int totalQuestions, long timeUsed) {
        // 计算用时
        int minutes = (int) (timeUsed / 60000);
        int seconds = (int) ((timeUsed % 60000) / 1000);
        String timeUsedStr = String.format("%02d:%02d", minutes, seconds);
        
        // 根据模式生成不同的结果信息
        String modeMessage = getModeResultMessage(score, correctCount, totalQuestions);
        
        // 创建结果对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("测验完成");
        builder.setMessage(
            modeMessage + "\n"
            + "得分: " + score + "分\n"
            + "正确: " + correctCount + "题\n"
            + "总题数: " + totalQuestions + "题\n"
            + "用时: " + timeUsedStr + "\n"
            + "正确率: " + (totalQuestions > 0 ? (correctCount * 100) / totalQuestions : 0) + "%"
        );
        
        // 添加按钮
        builder.setPositiveButton("查看错题", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                // 跳转到错题本
                Intent intent = new Intent(QuizActivity.this, WrongQuestionActivity.class);
                startActivity(intent);
                finish();
            }
        });
        
        builder.setNegativeButton("返回主页", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                finish();
            }
        });
        
        builder.setNeutralButton("重新测验", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                // 重新开始测验
                recreate();
            }
        });
        
        builder.setCancelable(false);
        builder.show();
    }
    
    // 根据模式获取结果信息
    private String getModeResultMessage(int score, int correctCount, int totalQuestions) {
        switch (quizMode) {
            case "practice":
                if (score >= 90) {
                    return "练习完成！你做得非常好，继续保持！";
                } else if (score >= 70) {
                    return "练习完成！你做得不错，继续加油！";
                } else {
                    return "练习完成！需要多加练习哦！";
                }
            case "exam":
                if (score >= 60) {
                    return "考试通过！恭喜你！";
                } else {
                    return "考试未通过，需要继续努力！";
                }
            case "review":
                if (score >= 80) {
                    return "复习完成！掌握得很好！";
                } else {
                    return "复习完成！还有一些知识点需要巩固！";
                }
            case "challenge":
                if (score >= 90) {
                    return "挑战成功！你真是太厉害了！";
                } else if (score >= 70) {
                    return "挑战完成！表现不错！";
                } else {
                    return "挑战失败，再来一次吧！";
                }
            case "recite":
                return "背诵完成！希望你已经掌握了这些知识点！";
            default:
                return "测验完成！";
        }
    }
    
    // 保存得分记录
    private void saveScoreRecord(int score, int totalQuestions, int correctCount, long timeUsed) {
        ScoreHistory scoreHistory = new ScoreHistory();
        scoreHistory.setScore(score);
        scoreHistory.setTotalQuestions(totalQuestions);
        scoreHistory.setCorrectCount(correctCount);
        scoreHistory.setStartTime(System.currentTimeMillis() - timeUsed);
        scoreHistory.setEndTime(System.currentTimeMillis());
        // 假设当前用户ID为1，实际应用中应该从用户会话中获取
        scoreHistory.setUserId(1L);
        
        ScoreRepository repository = new ScoreRepository(getApplication());
        try {
            long id = repository.addScore(scoreHistory);
            Log.d("QuizActivity", "得分记录成功: " + id);
        } catch (Exception e) {
            Log.e("QuizActivity", "得分记录失败: " + e.getMessage());
        }
    }
    
    // 清除测验进度
    private void clearQuizProgress() {
        android.content.SharedPreferences sharedPreferences = getSharedPreferences("quiz_progress", MODE_PRIVATE);
        sharedPreferences.edit().clear().apply();
    }
    
    // 开始计时
    private void startTimer() {
        // 实现计时逻辑
    }
    
    // 加载自定义题目类型映射
    private void loadCustomQuestionTypeMappings() {
        // 实现自定义题目类型映射加载逻辑
    }
    
    // 规范化题目类型
    private String normalizeQuestionType(String questionType, Question question) {
        if (questionType == null) {
            return "未分类";
        }
        
        String normalizedType = questionType.toLowerCase().trim();
        
        // 映射常见的题目类型变体
        if (normalizedType.contains("单选") || normalizedType.contains("single")) {
            return "单选题";
        } else if (normalizedType.contains("多选") || normalizedType.contains("multiple")) {
            return "多选题";
        } else if (normalizedType.contains("判断") || normalizedType.contains("true") || normalizedType.contains("false")) {
            return "判断题";
        } else if (normalizedType.contains("填空")) {
            return "填空题";
        } else if (normalizedType.contains("简答")) {
            return "简答题";
        } else if (normalizedType.contains("问答")) {
            return "问答题";
        } else if (normalizedType.contains("论述")) {
            return "论述题";
        }
        
        return questionType;
    }
    
    // 获取友好的题目类型名称
    private String getFriendlyQuestionTypeName(String type) {
        // 实现友好题目类型名称获取逻辑
        return type != null ? type : "未分类";
    }
    

    
    // 获取模拟题目
    private List<Question> getMockQuestions(int count) {
        List<Question> mockQuestions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Question question = new Question();
            int typeIndex = i % 5; // 5种题型循环
            
            switch (typeIndex) {
                case 0:
                    // 单选题
                    question.setQuestionText("单选题 " + (i + 1) + ": 下列哪项是正确的？");
                    question.setQuestionType("单选题");
                    question.setOptionA("选项A");
                    question.setOptionB("选项B");
                    question.setOptionC("选项C");
                    question.setOptionD("选项D");
                    question.setCorrectAnswer("A");
                    question.setExplanation("这是单选题的解析，正确答案是A。");
                    break;
                case 1:
                    // 多选题
                    question.setQuestionText("多选题 " + (i + 1) + ": 下列哪些是正确的？");
                    question.setQuestionType("多选题");
                    question.setOptionA("选项A");
                    question.setOptionB("选项B");
                    question.setOptionC("选项C");
                    question.setOptionD("选项D");
                    question.setCorrectAnswer("AB");
                    question.setExplanation("这是多选题的解析，正确答案是A和B。");
                    break;
                case 2:
                    // 判断题
                    question.setQuestionText("判断题 " + (i + 1) + ": 这是一个正确的陈述。");
                    question.setQuestionType("判断题");
                    question.setOptionA("正确");
                    question.setOptionB("错误");
                    question.setCorrectAnswer("A");
                    question.setExplanation("这是判断题的解析，正确答案是正确。");
                    break;
                case 3:
                    // 填空题
                    question.setQuestionText("填空题 " + (i + 1) + ": ()是一种编程语言。");
                    question.setQuestionType("填空题");
                    question.setCorrectAnswer("Java");
                    question.setExplanation("这是填空题的解析，正确答案是Java。");
                    break;
                case 4:
                    // 简答题
                    question.setQuestionText("简答题 " + (i + 1) + ": 请简述Java的特点。");
                    question.setQuestionType("简答题");
                    question.setCorrectAnswer("Java是一种面向对象的编程语言，具有跨平台、安全性、可移植性等特点。");
                    question.setExplanation("这是简答题的解析，Java的主要特点包括：面向对象、跨平台、安全性、可移植性等。");
                    break;
            }
            mockQuestions.add(question);
        }
        return mockQuestions;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放资源
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}