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
import android.widget.ScrollView;
import android.widget.ImageView;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;

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
import java.util.Map;

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
    private RadioGroup radioGroupOptions; // 动态创建，放入optionsContainer
    private LinearLayout optionsContainer;
        private ImageView questionImageView; // 题目配图
        private android.media.MediaPlayer audioPlayer; // 题目音频
    private List<com.google.android.material.radiobutton.MaterialRadioButton> radioButtons = new ArrayList<>();
    private com.google.android.material.textfield.TextInputLayout answerInputLayout;
    private EditText editTextUserAnswer;
    private MaterialButton buttonPrevious;
    private MaterialButton buttonNext;
    private MaterialButton buttonMark;
    private MaterialButton buttonSubmit;
    private MaterialButton buttonShowAnswer;
    private TextView textViewExplanation;
    private View linearLayoutExplanation;

    private CountDownTimer countDownTimer;

    private LinearLayout navigationLayout;
    private List<Question> originalQuestions;
    private GestureDetector gestureDetector;
    private String questionOrderMode = "顺序";
    // checkBoxContainer 已合并到 optionsContainer
    private LinearLayout checkBoxContainer; // 多选题复选框容器
    private List<com.google.android.material.checkbox.MaterialCheckBox> checkBoxes = new ArrayList<>();
    private android.text.TextWatcher answerTextWatcher; // 填空题TextWatcher（避免重复注册）

    private ProgressBar progressBar;
    private View answerCard; // 答案卡片
    // 缓存视图引用（避免重复findViewById）
    private ScrollView scrollViewContent;
    private TextView textViewAnswer;
    // 当前题型缓存（避免重复normalize）
    private String currentNormalizedType;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_quiz_modern;
    }

    private TextView textViewQuestionType;
    private TextView textViewQuestionNumber;
    
    @Override
    protected void initView() {
        // 设置工具栏（带返回按钮）
        setupToolbar("答题");
        
        // 初始化UI组件
        textViewQuestion = findViewById(R.id.textViewQuestion);
        textViewProgress = findViewById(R.id.textViewProgress);
        textViewTimer = findViewById(R.id.textViewTimer);
        textViewScore = findViewById(R.id.textViewScore);
        textViewQuestionType = findViewById(R.id.textViewQuestionType);
        textViewQuestionNumber = findViewById(R.id.textViewQuestionNumber);
        progressBar = findViewById(R.id.progressBar);
        buttonPrevious = findViewById(R.id.buttonPrevious);
        buttonNext = findViewById(R.id.buttonNext);
        buttonMark = findViewById(R.id.buttonMark);
        buttonShowAnswer = findViewById(R.id.buttonShowAnswer);
        buttonSubmit = findViewById(R.id.buttonSubmit);
        
        // 初始化背诵模式专用组件
        textViewExplanation = findViewById(R.id.textViewExplanation);
        linearLayoutExplanation = findViewById(R.id.linearLayoutExplanation);
        
        // 初始化非背诵模式组件
        optionsContainer = findViewById(R.id.optionsContainer);
        answerInputLayout = findViewById(R.id.answerInputLayout);
        editTextUserAnswer = findViewById(R.id.editTextUserAnswer);
        answerCard = findViewById(R.id.answerCard);
        
        // 缓存频繁访问的视图引用
        scrollViewContent = findViewById(R.id.scrollViewContent);
        textViewAnswer = findViewById(R.id.textViewAnswer);
        
        // 设置显示答案按钮的点击事件
        if (buttonShowAnswer != null) {
            buttonShowAnswer.setOnClickListener(v -> showAnswerButtonClicked());
        }
        
        // 设置提交按钮的点击事件
        if (buttonSubmit != null) {
            buttonSubmit.setOnClickListener(v -> submitQuiz());
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
        String modeTitle;
        switch (quizMode) {
            case "practice":
                modeTitle = "练习模式";
                setModeTheme(R.color.primary_color, R.color.primary_dark);
                break;
            case "exam":
                modeTitle = "考试模式";
                setModeTheme(R.color.blue, R.color.primary_dark);
                break;
            case "review":
                modeTitle = "复习模式";
                setModeTheme(R.color.green, R.color.success_color);
                break;
            case "challenge":
                modeTitle = "挑战模式";
                setModeTheme(R.color.red, R.color.error_color);
                break;
            case "recite":
                modeTitle = "背诵模式";
                setModeTheme(R.color.yellow, R.color.warning_color);
                break;
            default:
                modeTitle = quizMode != null ? quizMode : "答题";
                setModeTheme(R.color.primary_color, R.color.primary_dark);
        }
        // 更新工具栏标题
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(modeTitle);
        }
        // 根据模式设置UI元素可见性
        setupModeUI();
    }

    /**
     * 根据模式设置UI元素可见性
     * 背诵: 无得分/无计时/答案+解析始终显示/无查看答案按钮/无标记
     * 练习: 有得分/无计时/选后可看答案/有查看答案按钮/有标记
     * 考试: 无得分/有计时/交卷后可看/无查看答案按钮/有标记
     * 复习: 无得分/无计时/答案+解析始终显示/无查看答案按钮/有标记
     * 挑战: 有得分/有计时/不可看答案/无查看答案按钮/有标记
     */
    private void setupModeUI() {
        boolean isRecite = "recite".equals(quizMode);
        boolean isPractice = "practice".equals(quizMode);
        boolean isExam = "exam".equals(quizMode);
        boolean isReview = "review".equals(quizMode);
        boolean isChallenge = "challenge".equals(quizMode);

        // 得分显示：练习、挑战模式
        if (textViewScore != null) {
            textViewScore.setVisibility((isPractice || isChallenge) ? View.VISIBLE : View.GONE);
        }

        // 计时器显示：考试、挑战模式
        if (textViewTimer != null) {
            textViewTimer.setVisibility((isExam || isChallenge) ? View.VISIBLE : View.GONE);
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
        
        // 选项卡片背景由渲染引擎统一管理
        
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
    
    // 新的数据库匹配方法（直接查询，跳过count检查减少一次DB往返）
    private void matchDatabaseQuestions(QuestionRepository repository) {
        Log.d("QuizActivity", "开始查询题目: type=" + questionType + ", category=" + category + ", count=" + questionCount);
        
        if (questionType != null && !questionType.isEmpty() && !questionType.equals("all") && !questionType.equals("全部")) {
            matchQuestionsByType(repository, questionType);
        } else if (category != null && !category.isEmpty()) {
            matchQuestionsByCategory(repository, category, questionCount);
        } else {
            matchAllQuestions(repository, questionCount);
        }
    }
    
    // 根据题目类型匹配题目（v23优化: 使用索引+LIMIT）
    private void matchQuestionsByType(QuestionRepository repository, String type) {
        int limit = (questionCount > 0) ? questionCount : 100;
        Log.d("QuizActivity", "根据题目类型匹配题目: " + type + ", limit=" + limit);
        repository.getRandomQuestionsByType(type, limit, new QuestionRepository.RepositoryCallback<List<Question>>() {
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
                        Toast.makeText(QuizActivity.this, "题库为空或加载异常，将使用示例题目", Toast.LENGTH_SHORT).show();
                        handleDatabaseError();
                    }
                });
            }
        });
    }
    
    // 根据分类匹配题目（v23优化: 使用索引+LIMIT）
    private void matchQuestionsByCategory(QuestionRepository repository, String category, int limit) {
        int effectiveLimit = (limit > 0) ? limit : 100;
        Log.d("QuizActivity", "根据分类匹配题目: " + category + ", limit=" + effectiveLimit);
        repository.getRandomQuestionsByCategory(category, effectiveLimit, new QuestionRepository.RepositoryCallback<List<Question>>() {
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
                                Toast.makeText(QuizActivity.this, "题库为空或加载异常，将使用示例题目", Toast.LENGTH_SHORT).show();
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
    
    // 匹配所有题目或随机题目（v23优化: 使用SQL层LIMIT）
    private void matchAllQuestions(QuestionRepository repository, int questionCount) {
        int limit = (questionCount > 0) ? questionCount : 100;
        Log.d("QuizActivity", "获取随机题目: limit=" + limit);
        repository.getRandomQuestionsLimited(limit, new QuestionRepository.RepositoryCallback<List<Question>>() {
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
                                Toast.makeText(QuizActivity.this, "题库为空或加载异常，将使用示例题目", Toast.LENGTH_SHORT).show();
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
            
            // 尝试恢复之前的答题进度（如果存在）
            loadQuizProgress();
            
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
        if (isFinishing() || isDestroyed()) return;
        if (questions == null || questions.isEmpty() || currentQuestionIndex < 0 || currentQuestionIndex >= questions.size()) {
            Toast.makeText(this, "题目数据不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Question question = questions.get(currentQuestionIndex);
        if (question == null) {
            Toast.makeText(this, "题目对象为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // 滚动到顶部
            if (scrollViewContent != null) {
                scrollViewContent.smoothScrollTo(0, 0);
            }
            
            // 题目文本
            if (textViewQuestion != null) {
                String questionText = question.getQuestionText();
                textViewQuestion.setText(questionText != null ? questionText : "");
            }
            
            // 题号
            if (textViewQuestionNumber != null) {
                textViewQuestionNumber.setText(String.valueOf(currentQuestionIndex + 1));
            }
            
            // 进度
            if (textViewProgress != null) {
                textViewProgress.setText((currentQuestionIndex + 1) + "/" + questions.size());
            }
            
            // 进度条
            if (progressBar != null) {
                progressBar.setMax(questions.size());
                progressBar.setProgress(currentQuestionIndex + 1);
            }
            
            // 得分
            if (textViewScore != null) {
                textViewScore.setText("得分: " + correctCount);
            }
            
            // 题型（只计算一次，缓存到字段供后续方法复用）
            currentNormalizedType = normalizeQuestionType(question.getQuestionType(), question);
            if (textViewQuestionType != null) {
                textViewQuestionType.setText(currentNormalizedType);
            }

            // 渲染题目选项区域（构建控件 + 绑定事件 + 恢复选择）
            boolean isReadOnlyMode = "recite".equals(quizMode) || "review".equals(quizMode);
            renderQuestionOptions(currentNormalizedType, question, isReadOnlyMode);
            restoreUserSelection();
            
            // 题目配图（在选项渲染之后插入，避免被removeAllViews清除）
            showQuestionImage(question);

            // 加载答案和解析数据
            showAnswer(question, currentNormalizedType);
            showExplanation(question);
            
            // 根据模式控制答案和解析的显示/隐藏
            controlAnswerVisibilityByMode();

            // 更新按钮状态 + 导航按钮样式
            updateButtonStates();
            updateAllNavButtons();
            scrollToCurrentQuestion();
        } catch (Exception e) {
            Log.e("QuizActivity", "显示题目异常: " + e.getMessage(), e);
            Toast.makeText(this, "显示题目失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    // 根据模式控制答案和解析的显示/隐藏
    private void controlAnswerVisibilityByMode() {
        boolean isRecite = "recite".equals(quizMode);
        boolean isPractice = "practice".equals(quizMode);
        boolean isReview = "review".equals(quizMode);
    
        // 答案卡片可见性：背诵/复习模式显示，但无答案数据时隐藏
        if (answerCard != null) {
            boolean hasAnswerData = false;
            if (textViewAnswer != null) {
                String answerText = textViewAnswer.getText() != null ? textViewAnswer.getText().toString() : "";
                hasAnswerData = !answerText.isEmpty() && !answerText.contains("暂无答案");
            }
            answerCard.setVisibility((isRecite || isReview) && hasAnswerData ? View.VISIBLE : View.GONE);
        }
    
        // 解析区域可见性：背诵/复习模式且有解析内容时显示，其他情况隐藏
        if (linearLayoutExplanation != null) {
            boolean hasExplanation = false;
            if (questions != null && currentQuestionIndex < questions.size() && currentQuestionIndex >= 0) {
                String explanation = questions.get(currentQuestionIndex).getExplanation();
                hasExplanation = explanation != null && !explanation.isEmpty();
            }
            linearLayoutExplanation.setVisibility((isRecite || isReview) && hasExplanation ? View.VISIBLE : View.GONE);
        }
    
        // “查看答案”按钮可见性：仅练习模式显示
        if (buttonShowAnswer != null) {
            buttonShowAnswer.setVisibility(isPractice ? View.VISIBLE : View.GONE);
        }
    }
    
    // 更新按钮状态
    private void updateButtonStates() {
        if (questions == null || questions.isEmpty()) return;
            
        boolean isRecite = "recite".equals(quizMode);
        boolean isPractice = "practice".equals(quizMode);
        boolean isExam = "exam".equals(quizMode);
        boolean isReview = "review".equals(quizMode);
        boolean isChallenge = "challenge".equals(quizMode);
        boolean isLastQuestion = currentQuestionIndex >= questions.size() - 1;
            
        // 上一题按钮：挑战模式不允许回退
        if (buttonPrevious != null) {
            if (isChallenge) {
                buttonPrevious.setVisibility(View.GONE);
            } else {
                buttonPrevious.setVisibility(currentQuestionIndex > 0 ? View.VISIBLE : View.GONE);
            }
        }
            
        // 下一题按钮文字
        if (buttonNext != null) {
            if (isLastQuestion) {
                if (isRecite) {
                    buttonNext.setText("完成");
                } else if (isExam) {
                    buttonNext.setText("交卷");
                } else {
                    buttonNext.setText("完成");
                }
            } else {
                buttonNext.setText("→");
            }
        }
            
        // 标记按钮：练习/考试/复习/挑战模式可见，背诵模式隐藏
        if (buttonMark != null) {
            boolean showMark = isPractice || isExam || isReview || isChallenge;
            buttonMark.setVisibility(showMark ? View.VISIBLE : View.GONE);
            // 更新标记状态文字
            if (showMark && markedQuestions != null && currentQuestionIndex < markedQuestions.size()) {
                boolean isMarked = markedQuestions.get(currentQuestionIndex);
                buttonMark.setText(isMarked ? "★ 已标记" : "☆ 标记");
            }
        }
    }
    
    // 显示题目配图
    private void showQuestionImage(Question question) {
        if (optionsContainer == null) return;
        
        // 先移除旧的配图
        if (questionImageView != null) {
            optionsContainer.removeView(questionImageView);
            questionImageView = null;
        }
        
        String imageUri = question.getImageUri();
        if (imageUri == null || imageUri.isEmpty()) return;
        
        try {
            questionImageView = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 16);
            questionImageView.setLayoutParams(params);
            questionImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            questionImageView.setAdjustViewBounds(true);
            
            // 尝试加载图片
            if (imageUri.startsWith("/")) {
                // 本地文件路径
                Bitmap bitmap = BitmapFactory.decodeFile(imageUri);
                if (bitmap != null) {
                    questionImageView.setImageBitmap(bitmap);
                    optionsContainer.addView(questionImageView, 0);
                }
            } else if (imageUri.startsWith("content://") || imageUri.startsWith("file://")) {
                android.net.Uri uri = android.net.Uri.parse(imageUri);
                questionImageView.setImageURI(uri);
                optionsContainer.addView(questionImageView, 0);
            } else {
                // 尝试作为资源ID
                int resId = getResources().getIdentifier(imageUri, "drawable", getPackageName());
                if (resId != 0) {
                    questionImageView.setImageResource(resId);
                    optionsContainer.addView(questionImageView, 0);
                }
            }
        } catch (Exception e) {
            Log.w("QuizActivity", "加载题目配图失败: " + e.getMessage());
            if (questionImageView != null) {
                optionsContainer.removeView(questionImageView);
                questionImageView = null;
            }
        }
    }
    
    // 显示答案（接收已规范化的题型，避免重复normalize）
    private void showAnswer(Question question, String normalizedType) {
        if (textViewAnswer == null) return;
        
        String answerText;
        boolean isTextType = "填空题".equals(normalizedType) || "简答题".equals(normalizedType) 
                           || "问答题".equals(normalizedType) || "论述题".equals(normalizedType);
        
        if (isTextType) {
            // 文本型题目：优先使用answerText字段
            String stdAnswer = question.getAnswerText();
            if (stdAnswer != null && !stdAnswer.isEmpty()) {
                answerText = "正确答案: " + stdAnswer;
            } else {
                answerText = buildAnswerText(question, question.getCorrectAnswer(), normalizedType);
                if (answerText == null) answerText = "正确答案: 暂无答案";
            }
        } else {
            // 选择型题目：使用correctAnswer（字母）
            answerText = buildAnswerText(question, question.getCorrectAnswer(), normalizedType);
            if (answerText == null) answerText = "正确答案: 暂无答案";
        }
        
        textViewAnswer.setText(answerText);
        // 无答案时隐藏答案卡片
        if ("正确答案: 暂无答案".equals(answerText) && answerCard != null) {
            answerCard.setVisibility(View.GONE);
        }
    }
    
    // 构建答案文本，包含选项内容（接收已规范化的题型，避免重复normalize）
    private String buildAnswerText(Question question, String correctAnswer, String normalizedType) {
        if (correctAnswer == null || correctAnswer.isEmpty()) {
            return null;
        }
        
        StringBuilder answerBuilder = new StringBuilder();
        answerBuilder.append("正确答案: ");
        
        if ("单选题".equals(normalizedType) || "判断题".equals(normalizedType)) {
            // 单选题/判断题：显示选项字母和内容
            String optionContent = getOptionContentByLetter(question, correctAnswer);
            if (optionContent != null && !optionContent.isEmpty()) {
                answerBuilder.append(correctAnswer).append(". ").append(optionContent);
            } else {
                answerBuilder.append(correctAnswer);
            }
        } else if ("多选题".equals(normalizedType)) {
            // 多选题：显示所有正确选项（兼容 "AC" 和 "A,C" 两种格式）
            String cleaned = correctAnswer.replaceAll("[,，\\s]", "");
            String[] answers;
            if (correctAnswer.contains(",") || correctAnswer.contains("，")) {
                // 逗号分隔格式："A,C,D"
                answers = correctAnswer.split("[,，]");
            } else {
                // 无分隔符格式："ACD" → 拆为单个字母
                answers = new String[cleaned.length()];
                for (int k = 0; k < cleaned.length(); k++) {
                    answers[k] = String.valueOf(cleaned.charAt(k));
                }
            }
            for (int i = 0; i < answers.length; i++) {
                String answer = answers[i].trim();
                if (answer.isEmpty()) continue;
                if (i > 0 && answerBuilder.length() > "正确答案: ".length()) {
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
            // 填空题、简答题等：优先使用answerText字段
            String stdAnswer = question.getAnswerText();
            if (stdAnswer != null && !stdAnswer.isEmpty()) {
                answerBuilder.append(stdAnswer);
            } else {
                // 回退到correctAnswer
                answerBuilder.append(correctAnswer);
            }
        }
        
        return answerBuilder.toString();
    }
    
    // 根据选项字母获取选项内容（支持A-L）
    private String getOptionContentByLetter(Question question, String letter) {
        if (letter == null || letter.isEmpty()) {
            return null;
        }
        
        // 使用Question的通用方法，支持A-L所有选项
        return question.getOptionByLetter(letter);
    }
    
    // 切换答案显示/隐藏（练习模式下使用）
    private void toggleAnswerVisibility() {
        if (answerCard != null) {
            // 无答案数据时不切换显示
            String answerText = (textViewAnswer != null && textViewAnswer.getText() != null) ? textViewAnswer.getText().toString() : "";
            boolean hasAnswerData = !answerText.isEmpty() && !answerText.contains("暂无答案");
            if (hasAnswerData) {
                answerCard.setVisibility(answerCard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        }
        if (linearLayoutExplanation != null) {
            linearLayoutExplanation.setVisibility(linearLayoutExplanation.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }
    }
    
    // 显示解析（加载数据 + 根据内容有无控制可见性）
    private void showExplanation(Question question) {
        if (textViewExplanation == null || linearLayoutExplanation == null) return;
            
        String explanation = question.getExplanation();
        if (explanation != null && !explanation.isEmpty()) {
            textViewExplanation.setText(explanation);
            // 有解析内容：可见性由模式控制（controlAnswerVisibilityByMode）
        } else {
            // 无解析内容：直接隐藏解析区域
            if (linearLayoutExplanation != null) {
                linearLayoutExplanation.setVisibility(View.GONE);
            }
        }
    }
    
    // 显示答案按钮点击事件（仅练习模式触发）
    private void showAnswerButtonClicked() {
        if (questions != null && currentQuestionIndex < questions.size() && currentQuestionIndex >= 0) {
            Question question = questions.get(currentQuestionIndex);
            String normalizedType = normalizeQuestionType(question.getQuestionType(), question);
            // 加载答案和解析数据
            showAnswer(question, normalizedType);
            showExplanation(question);
            // 切换答案和解析的显示状态
            toggleAnswerVisibility();
        }
    }
    
    // 设置题目导航
    private void setupQuestionNavigation() {
        if (navigationLayout != null && questions != null) {
            navigationLayout.removeAllViews();
            
            // 圆形按钮尺寸（dp→px）
            int sizePx = (int) (40 * getResources().getDisplayMetrics().density + 0.5f);
            int marginPx = (int) (4 * getResources().getDisplayMetrics().density + 0.5f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(sizePx, sizePx);
            params.setMargins(marginPx, 0, marginPx, 0);
            
            for (int i = 0; i < questions.size(); i++) {
                final int questionIndex = i;
                com.google.android.material.button.MaterialButton navButton = new com.google.android.material.button.MaterialButton(this);
                navButton.setLayoutParams(params);
                navButton.setText(String.valueOf(i + 1));
                navButton.setTextSize(13);
                navButton.setTag(questionIndex);
                navButton.setGravity(android.view.Gravity.CENTER);
                // 移除MaterialButton默认最小尺寸和内边距
                navButton.setMinimumWidth(0);
                navButton.setMinimumHeight(0);
                navButton.setIconPadding(0);
                navButton.setInsetTop(0);
                navButton.setInsetBottom(0);
                navButton.setCornerRadius(0); // 圆形由drawable控制
                navButton.setIcon(null);
                
                // 根据题目状态设置初始样式（基于userAnswers判断是否真正已作答）
                if (i == currentQuestionIndex) {
                    navButton.setBackgroundResource(R.drawable.nav_button_current);
                    navButton.setTextColor(getResources().getColor(R.color.white));
                } else if (userAnswers != null && i < userAnswers.size()
                        && userAnswers.get(i) != null && !userAnswers.get(i).isEmpty()) {
                    navButton.setBackgroundResource(R.drawable.nav_button_answered);
                    navButton.setTextColor(getResources().getColor(R.color.white));
                } else {
                    navButton.setBackgroundResource(R.drawable.nav_button_unanswered);
                    navButton.setTextColor(getResources().getColor(R.color.text_secondary));
                }
                
                // 标记的题目显示星号
                if (markedQuestions.get(i)) {
                    navButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_star, 0);
                }
                
                navButton.setOnClickListener(v -> {
                    saveUserSelection();
                    currentQuestionIndex = questionIndex;
                    saveQuizProgress(); // 导航时统一保存进度
                    showCurrentQuestion();
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
                    
                    // 根据题目状态更新按钮样式（基于userAnswers判断是否真正已作答）
                    String answer = (userAnswers != null && questionIndex < userAnswers.size())
                            ? userAnswers.get(questionIndex) : "";
                    boolean isAnswered = answer != null && !answer.isEmpty();
                                        
                    if (questionIndex == currentQuestionIndex) {
                        // 当前题目：主色圆形
                        navButton.setBackgroundResource(R.drawable.nav_button_current);
                        navButton.setTextColor(getResources().getColor(R.color.white));
                    } else if (isAnswered) {
                        // 已作答题目：浅主色圆形
                        navButton.setBackgroundResource(R.drawable.nav_button_answered);
                        navButton.setTextColor(getResources().getColor(R.color.white));
                    } else {
                        // 未作答题目：白底灰边圆形
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
    
    // ======================== 题目渲染引擎 ========================
    // 统一的题目渲染入口：根据题型动态构建选项UI + 绑定事件 + 恢复选择
    // 设计原则：一次调用完成所有渲染，避免重复normalize和多次遍历
    
    /**
     * 渲染题目选项区域（统一入口）
     * 根据题型创建对应的交互控件（单选RadioGroup/多选CheckBox/填空EditText），
     * 并绑定实时保存监听器。
     *
     * @param normalizedType 规范化后的题型（单选题/多选题/判断题/填空题等）
     * @param question       题目数据对象
     * @param isReadOnlyMode 是否只读（背诵/复习模式）
     */
    private void renderQuestionOptions(String normalizedType, Question question, boolean isReadOnlyMode) {
        // 1. 清空动态容器和状态
        if (optionsContainer != null) optionsContainer.removeAllViews();
        radioButtons.clear();
        checkBoxes.clear();
        radioGroupOptions = null;
        checkBoxContainer = null;
        if (answerInputLayout != null) {
            answerInputLayout.setVisibility(View.GONE);
            // 重置输入框布局参数
            answerInputLayout.setPadding(0, 0, 0, 0);
            answerInputLayout.setBackground(null);
        }
        
        boolean hasOptions = hasValidOptions(question);
        boolean isTextType = "填空题".equals(normalizedType) || "简答题".equals(normalizedType)
                || "问答题".equals(normalizedType) || "论述题".equals(normalizedType);
        
        if (normalizedType == null || optionsContainer == null) return;
        
        // 2. 根据题型构建对应的交互控件
        switch (normalizedType) {
            case "单选题":
            case "判断题":
                buildRadioGroupUI(question, hasOptions);
                applyReadOnlyToRadioGroup(isReadOnlyMode);
                attachRadioListeners();
                break;
                
            case "多选题":
                buildCheckBoxUI(question, hasOptions);
                applyReadOnlyToCheckBox(isReadOnlyMode);
                attachCheckBoxListeners();
                break;
                
            case "填空题":
            case "简答题":
            case "问答题":
            case "论述题":
                buildTextInputUI(normalizedType, isReadOnlyMode);
                attachTextInputListener();
                break;
                
            default:
                // 未知题型默认按单选题处理
                buildRadioGroupUI(question, hasOptions);
                applyReadOnlyToRadioGroup(isReadOnlyMode);
                attachRadioListeners();
                break;
        }
        
        // 3. 布局协调：根据题型动态调整间距
        if (isTextType) {
            // 文本型题目：隐藏选项容器，输入框已有视觉包装
            if (optionsContainer != null) optionsContainer.setVisibility(View.GONE);
        } else if (optionsContainer != null && optionsContainer.getChildCount() == 0) {
            // 选择型但无选项：隐藏容器
            optionsContainer.setVisibility(View.GONE);
        } else if (optionsContainer != null) {
            optionsContainer.setVisibility(View.VISIBLE);
        }
    }
    
    // --- 控件构建 ---
    
    /** 构建单选/判断题的 RadioGroup 选项UI */
    private void buildRadioGroupUI(Question question, boolean hasOptions) {
        radioGroupOptions = new RadioGroup(this);
        radioGroupOptions.setOrientation(RadioGroup.VERTICAL);
        optionsContainer.addView(radioGroupOptions);
        
        if (hasOptions) {
            populateOptionViews(question, false);
        }
        // 无选项时不添加任何控件，optionsContainer会在renderQuestionOptions中自动隐藏
    }
    
    /** 构建多选题的 CheckBox 选项UI */
    private void buildCheckBoxUI(Question question, boolean hasOptions) {
        checkBoxContainer = new LinearLayout(this);
        checkBoxContainer.setOrientation(LinearLayout.VERTICAL);
        optionsContainer.addView(checkBoxContainer);
        
        if (hasOptions) {
            populateOptionViews(question, true);
        }
        // 无选项时不添加任何控件，optionsContainer会在renderQuestionOptions中自动隐藏
    }
    
    /** 构建填空题/简答题的文本输入UI（按题型区分视觉风格和尺寸） */
    private void buildTextInputUI(String normalizedType, boolean isReadOnlyMode) {
        if (answerInputLayout != null) answerInputLayout.setVisibility(View.VISIBLE);
        if (editTextUserAnswer != null) {
            editTextUserAnswer.setVisibility(View.VISIBLE);
            
            // 按题型设置输入提示和样式
            boolean isLongAnswer = "简答题".equals(normalizedType) || "问答题".equals(normalizedType) || "论述题".equals(normalizedType);
            
            if ("填空题".equals(normalizedType)) {
                answerInputLayout.setHint("请填写答案");
                editTextUserAnswer.setMinLines(1);
                editTextUserAnswer.setMaxLines(1);
                editTextUserAnswer.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            } else if ("简答题".equals(normalizedType)) {
                answerInputLayout.setHint("请简要回答");
                editTextUserAnswer.setMinLines(3);
                editTextUserAnswer.setMaxLines(6);
                editTextUserAnswer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            } else if ("问答题".equals(normalizedType)) {
                answerInputLayout.setHint("请详细回答");
                editTextUserAnswer.setMinLines(4);
                editTextUserAnswer.setMaxLines(8);
                editTextUserAnswer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            } else if ("论述题".equals(normalizedType)) {
                answerInputLayout.setHint("请展开论述");
                editTextUserAnswer.setMinLines(5);
                editTextUserAnswer.setMaxLines(10);
                editTextUserAnswer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            }
            
            editTextUserAnswer.setGravity(isLongAnswer ? android.view.Gravity.TOP : android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            editTextUserAnswer.setEnabled(!isReadOnlyMode);
            
            // 为输入框添加视觉包装背景（与选项卡片风格统一）
            int padV = (int) (12 * getResources().getDisplayMetrics().density + 0.5f);
            int padH = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
            answerInputLayout.setPadding(padH, padV, padH, padV);
            answerInputLayout.setBackgroundResource(R.drawable.option_bg_default);
        }
    }
    
    // --- 选项填充（统一处理单选/多选） ---
    
    /**
     * 根据题目选项数据，批量创建选项控件并添加到容器
     * @param question    题目数据
     * @param isCheckBox  true=创建CheckBox（多选），false=创建RadioButton（单选/判断）
     */
    private void populateOptionViews(Question question, boolean isCheckBox) {
        List<java.util.Map.Entry<String, String>> entries = getValidOptionEntries(question);
        int optionCount = entries.size();
        
        // 根据选项数量动态调整间距：选项越多间距越紧凑
        int marginDp;
        if (optionCount <= 4) {
            marginDp = 10;
        } else if (optionCount <= 8) {
            marginDp = 8;
        } else {
            marginDp = 6;
        }
        int marginPx = (int) (marginDp * getResources().getDisplayMetrics().density + 0.5f);
        
        for (java.util.Map.Entry<String, String> entry : entries) {
            View optionView = createOptionView(entry.getValue(), entry.getKey(), isCheckBox, marginPx);
            if (isCheckBox) {
                checkBoxContainer.addView(optionView);
                checkBoxes.add((com.google.android.material.checkbox.MaterialCheckBox) optionView);
            } else {
                radioGroupOptions.addView(optionView);
                radioButtons.add((com.google.android.material.radiobutton.MaterialRadioButton) optionView);
            }
        }
    }
    
    /**
     * 创建单个选项卡片控件（统一RadioButton和CheckBox）
     * 卡片样式：字母编号 + 选项文本，卡片式背景，动态间距
     *
     * @param optionText  选项文本内容
     * @param letterKey   选项字母标识（A/B/C/D...）
     * @param isCheckBox  true=CheckBox，false=RadioButton
     * @param marginPx    选项之间的下间距（像素）
     * @return 创建好的选项控件
     */
    private View createOptionView(String optionText, String letterKey, boolean isCheckBox, int marginPx) {
        CompoundButton optionView;
        if (isCheckBox) {
            optionView = new com.google.android.material.checkbox.MaterialCheckBox(this);
        } else {
            optionView = new com.google.android.material.radiobutton.MaterialRadioButton(this);
        }
        
        optionView.setId(View.generateViewId());
        optionView.setTag(letterKey);
        // 隐藏原生圆点，使用卡片式背景（必须用ColorDrawable对象，不能传int颜色值0）
        optionView.setButtonDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        optionView.setText("  " + letterKey + ". " + optionText);
        optionView.setTextAppearance(R.style.TextAppearance_SmartQuiz_BodyLarge);
        optionView.setTextColor(getResources().getColor(R.color.text_primary));
        optionView.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        // 布局参数（动态间距）
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, marginPx);
        optionView.setLayoutParams(params);
        
        // 卡片式默认背景 + 内边距
        optionView.setBackgroundResource(R.drawable.option_bg_default);
        int pad = getResources().getDimensionPixelSize(R.dimen.spacing_16);
        optionView.setPadding(pad, pad, pad, pad);
        
        return optionView;
    }
    
    // --- 事件绑定 ---
    
    /** 绑定单选组监听器：选中变化时保存答案 + 刷新选项卡片背景（不写SP，导航时统一保存） */
    private void attachRadioListeners() {
        if (radioGroupOptions != null) {
            radioGroupOptions.setOnCheckedChangeListener((group, checkedId) -> {
                saveUserSelection();
                updateOptionBackgrounds();
            });
        }
    }
    
    /** 绑定多选框监听器：选中变化时实时保存 + 刷新选项卡片背景 */
    private void attachCheckBoxListeners() {
        // CheckBox的监听器已在createOptionView中逐个绑定（因为CheckBox没有Group级别的监听）
        // 此处无需额外操作，保留方法以保持结构一致性
    }
    
    /** 绑定填空题文本输入监听器：文本变化时保存答案（不写SP，导航时统一保存） */
    private void attachTextInputListener() {
        if (editTextUserAnswer != null) {
            if (answerTextWatcher != null) {
                editTextUserAnswer.removeTextChangedListener(answerTextWatcher);
            }
            answerTextWatcher = new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    saveUserSelection();
                }
            };
            editTextUserAnswer.addTextChangedListener(answerTextWatcher);
        }
    }
    
    // --- 只读模式控制 ---
    
    /** 在背诵/复习模式下禁用RadioGroup中所有RadioButton */
    private void applyReadOnlyToRadioGroup(boolean isReadOnlyMode) {
        if (!isReadOnlyMode || radioGroupOptions == null) return;
        for (int i = 0; i < radioGroupOptions.getChildCount(); i++) {
            View child = radioGroupOptions.getChildAt(i);
            if (child instanceof RadioButton) child.setEnabled(false);
        }
    }
    
    /** 在背诵/复习模式下禁用CheckBox容器中所有CheckBox */
    private void applyReadOnlyToCheckBox(boolean isReadOnlyMode) {
        if (!isReadOnlyMode || checkBoxContainer == null) return;
        for (int i = 0; i < checkBoxContainer.getChildCount(); i++) {
            View child = checkBoxContainer.getChildAt(i);
            if (child instanceof CheckBox) child.setEnabled(false);
        }
    }
    
    // --- 选项卡片背景刷新 ---
    
    /**
     * 统一刷新所有选项卡片的视觉状态
     * 选中项 → option_bg_selected + 主题色文字
     * 未选中 → option_bg_default + 默认文字色
     */
    private void updateOptionBackgrounds() {
        int selectedColor = getResources().getColor(R.color.primary);
        int defaultColor = getResources().getColor(R.color.text_primary);
        
        for (com.google.android.material.radiobutton.MaterialRadioButton rb : radioButtons) {
            if (rb.isChecked()) {
                rb.setBackgroundResource(R.drawable.option_bg_selected);
                rb.setTextColor(selectedColor);
            } else {
                rb.setBackgroundResource(R.drawable.option_bg_default);
                rb.setTextColor(defaultColor);
            }
        }
        for (com.google.android.material.checkbox.MaterialCheckBox cb : checkBoxes) {
            if (cb.isChecked()) {
                cb.setBackgroundResource(R.drawable.option_bg_selected);
                cb.setTextColor(selectedColor);
            } else {
                cb.setBackgroundResource(R.drawable.option_bg_default);
                cb.setTextColor(defaultColor);
            }
        }
    }
    
    // 检查题目是否有有效的选项
    private boolean hasValidOptions(Question question) {
        java.util.Map<String, String> options = question.getOptions();
        return options != null && !options.isEmpty();
    }
    
    // 获取所有有效的选项（按字母排序，支持A-L）
    private List<String> getValidOptions(Question question) {
        List<String> options = new ArrayList<>();
        java.util.Map<String, String> optionMap = question.getOptions();
        if (optionMap != null && !optionMap.isEmpty()) {
            java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>(optionMap);
            for (java.util.Map.Entry<String, String> entry : sorted.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    options.add(entry.getValue());
                }
            }
        }
        return options;
    }
    
    // 获取所有有效选项的Entry列表（保留字母Key，按字母排序）
    private List<java.util.Map.Entry<String, String>> getValidOptionEntries(Question question) {
        List<java.util.Map.Entry<String, String>> entries = new ArrayList<>();
        java.util.Map<String, String> optionMap = question.getOptions();
        if (optionMap != null && !optionMap.isEmpty()) {
            java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>(optionMap);
            for (java.util.Map.Entry<String, String> entry : sorted.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }
    
    /**
     * 规范化答案字母：去除逗号、空白字符，转大写，排序后拼接
     * 兼容 "AC" / "A,C" / "A, C" / "A，C" 等各种格式
     */
    private String normalizeAnswerLetters(String answer) {
        if (answer == null) return "";
        // 去除所有逗号（中英文）、空格、制表符
        String cleaned = answer.replaceAll("[,，\\s]", "").toUpperCase();
        // 排序字母，确保顺序无关
        char[] chars = cleaned.toCharArray();
        java.util.Arrays.sort(chars);
        return new String(chars);
    }
    
    // 显示答题结果反馈（正确/错误高亮）
    private void showAnswerFeedback(Question question) {
        String correctAnswer = question.getCorrectAnswer();
        String userAnswer = userAnswers.get(currentQuestionIndex);
        String questionType = question.getQuestionType();
        String normalizedType = normalizeQuestionType(questionType, question);
        
        if ("单选题".equals(normalizedType) || "判断题".equals(normalizedType)) {
            for (com.google.android.material.radiobutton.MaterialRadioButton rb : radioButtons) {
                String tag = (String) rb.getTag();
                if (tag != null && tag.equals(correctAnswer)) {
                    rb.setBackgroundResource(R.drawable.option_bg_correct);
                    rb.setTextColor(getResources().getColor(R.color.success));
                } else if (tag != null && tag.equals(userAnswer) && !tag.equals(correctAnswer)) {
                    rb.setBackgroundResource(R.drawable.option_bg_wrong);
                    rb.setTextColor(getResources().getColor(R.color.error));
                }
            }
        } else if ("多选题".equals(normalizedType)) {
            for (com.google.android.material.checkbox.MaterialCheckBox cb : checkBoxes) {
                String tag = (String) cb.getTag();
                if (tag == null) continue;
                boolean isCorrectOption = correctAnswer != null && correctAnswer.contains(tag);
                boolean isUserSelected = userAnswer != null && userAnswer.contains(tag);
                if (isCorrectOption) {
                    cb.setBackgroundResource(R.drawable.option_bg_correct);
                    cb.setTextColor(getResources().getColor(R.color.success));
                } else if (isUserSelected && !isCorrectOption) {
                    cb.setBackgroundResource(R.drawable.option_bg_wrong);
                    cb.setTextColor(getResources().getColor(R.color.error));
                }
            }
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
                        // 恢复单选按钮选择（从tag读取字母Key对比）
                        for (com.google.android.material.radiobutton.MaterialRadioButton rb : radioButtons) {
                            String tag = (String) rb.getTag();
                            if (tag != null && tag.equals(userAnswer)) {
                                rb.setChecked(true);
                                break;
                            }
                        }
                        break;
                        
                    case "多选题":
                        // 恢复复选框选择（从tag读取字母Key对比）
                        for (com.google.android.material.checkbox.MaterialCheckBox cb : checkBoxes) {
                            String tag = (String) cb.getTag();
                            cb.setChecked(tag != null && userAnswer.contains(tag));
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
                for (com.google.android.material.radiobutton.MaterialRadioButton rb : radioButtons) {
                    rb.setChecked(false);
                }
                for (com.google.android.material.checkbox.MaterialCheckBox cb : checkBoxes) {
                    cb.setChecked(false);
                }
                if (editTextUserAnswer != null) {
                    editTextUserAnswer.setText("");
                }
            }
            // 恢复后统一刷新选项卡片背景
            updateOptionBackgrounds();
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
                    // 保存单选按钮选择（从tag读取字母Key）
                    for (com.google.android.material.radiobutton.MaterialRadioButton rb : radioButtons) {
                        if (rb.isChecked()) {
                            userAnswer = (String) rb.getTag();
                            break;
                        }
                    }
                    break;
                    
                case "多选题":
                    // 保存复选框选择（从tag读取字母Key）
                    StringBuilder answerBuilder = new StringBuilder();
                    for (com.google.android.material.checkbox.MaterialCheckBox cb : checkBoxes) {
                        if (cb.isChecked()) {
                            answerBuilder.append((String) cb.getTag());
                        }
                    }
                    userAnswer = answerBuilder.toString();
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
            String questionType = question.getQuestionType();
            String normalizedType = normalizeQuestionType(questionType, question);
            
            // 根据题型选择正确的标准答案字段
            String correctAnswer;
            boolean isTextType = "填空题".equals(normalizedType) || "简答题".equals(normalizedType)
                    || "问答题".equals(normalizedType) || "论述题".equals(normalizedType);
            if (isTextType) {
                // 文本题型：优先使用answerText（实际答案文本），回退到correctAnswer
                correctAnswer = (question.getAnswerText() != null && !question.getAnswerText().isEmpty())
                        ? question.getAnswerText() : question.getCorrectAnswer();
            } else {
                correctAnswer = question.getCorrectAnswer();
            }
            
            if (correctAnswer != null && !correctAnswer.isEmpty()) {
                boolean isCorrect = false;
                
                switch (normalizedType) {
                    case "单选题":
                    case "判断题":
                        // 单选题和判断题：去除空白后直接比较
                        isCorrect = normalizeAnswerLetters(correctAnswer).equals(normalizeAnswerLetters(userAnswer));
                        break;
                    case "多选题":
                        // 多选题：统一去除逗号/空白后排序比较
                        isCorrect = normalizeAnswerLetters(correctAnswer).equals(normalizeAnswerLetters(userAnswer));
                        break;
                    case "填空题":
                    case "简答题":
                    case "问答题":
                    case "论述题":
                        // 文本题型：忽略首尾空白比较
                        String ua = userAnswer != null ? userAnswer.trim() : "";
                        isCorrect = correctAnswer.trim().equals(ua);
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
                
                // 显示选项卡片的结果反馈（正确/错误高亮）
                showAnswerFeedback(question);
                
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
            saveQuizProgress(); // 导航时统一保存进度
            showCurrentQuestion();
        }
    }
    
    // 显示下一题
    private void showNextQuestion() {
        // 保存当前题目的答案
        saveUserSelection();
        
        if (currentQuestionIndex < questions.size() - 1) {
            currentQuestionIndex++;
            saveQuizProgress(); // 导航时统一保存进度
            showCurrentQuestion();
        } else {
            // 最后一题：考试/挑战模式提交，背诵/复习模式直接结束
            if ("exam".equals(quizMode) || "challenge".equals(quizMode) || "practice".equals(quizMode)) {
                submitQuiz();
            } else {
                // 背诵/复习模式：直接返回
                finish();
            }
        }
    }
    
    // 切换标记题目
    private void toggleMarkQuestion() {
        if (currentQuestionIndex >= 0 && currentQuestionIndex < markedQuestions.size()) {
            markedQuestions.set(currentQuestionIndex, !markedQuestions.get(currentQuestionIndex));
            // 刷新标记按钮UI
            updateButtonStates();
            // 持久化标记状态
            saveQuizProgress();
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
        
        // 更新题目统计字段（使用次数、正确/错误次数）
        updateQuestionStats();
        
        // 记录错题
        for (int i = 0; i < wrongQuestions.size(); i++) {
            recordWrongQuestion(wrongQuestions.get(i), userAnswersList.get(i));
        }
        
        // 显示结果
        showQuizResult(score, correctCount, totalQuestions, timeUsed);
        
        // 清除进度保存
        clearQuizProgress();
    }
    
    /** 更新所有参与答题的题目的统计字段 */
    private void updateQuestionStats() {
        if (questions == null || questions.isEmpty()) return;
        
        QuestionRepository repo = new QuestionRepository(getApplication());
        long now = System.currentTimeMillis();
        
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            if (q == null) continue;
            
            // 增加使用次数
            repo.incrementUsageCount(q.getId(), now);
            
            // 判断是否正确并更新对应计数
            String userAnswer = (i < userAnswers.size()) ? userAnswers.get(i) : "";
            String correctAnswer = q.getCorrectAnswer();
            if (correctAnswer != null && !correctAnswer.isEmpty()) {
                boolean isCorrect = q.checkAnswer(userAnswer);
                if (isCorrect) {
                    repo.incrementCorrectCount(q.getId());
                } else {
                    repo.incrementIncorrectCount(q.getId());
                }
            }
        }
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
        boolean isExam = "exam".equals(quizMode);
        boolean isChallenge = "challenge".equals(quizMode);
        
        // 仅考试和挑战模式启用计时
        if (!isExam && !isChallenge) return;
        if (textViewTimer == null) return;
        
        startTime = System.currentTimeMillis();
        
        // 考试模式：10分钟限时；挑战模式：5分钟限时
        long totalTimeMs;
        if (isExam) {
            totalTimeMs = 10 * 60 * 1000L; // 10分钟
        } else {
            totalTimeMs = 5 * 60 * 1000L; // 5分钟
        }
        
        countDownTimer = new CountDownTimer(totalTimeMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int minutes = (int) (millisUntilFinished / 60000);
                int seconds = (int) ((millisUntilFinished % 60000) / 1000);
                String timeStr = String.format("%02d:%02d", minutes, seconds);
                textViewTimer.setText(timeStr);
                
                // 最后1分钟变红色警告
                if (millisUntilFinished < 60000) {
                    textViewTimer.setTextColor(getResources().getColor(R.color.error_color));
                }
            }
            
            @Override
            public void onFinish() {
                textViewTimer.setText("00:00");
                Toast.makeText(QuizActivity.this, "时间到！自动交卷", Toast.LENGTH_LONG).show();
                submitQuiz();
            }
        };
        countDownTimer.start();
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
        // 释放音频播放器
        if (audioPlayer != null) {
            if (audioPlayer.isPlaying()) audioPlayer.stop();
            audioPlayer.release();
            audioPlayer = null;
        }
    }
}