package com.oilquiz.app.util.quiz;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.QuizMode;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.model.WrongQuestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 测验会话管理类
 * 根据不同的测验模式生成题目
 */
public class QuizSessionManager {
    private static final String TAG = "QuizSessionManager";
    
    private final Context context;
    private final AppDatabase database;
    private final ExecutorService executor;
    private final Gson gson;
    
    // 当前会话配置
    private QuizMode currentMode;
    private int questionCount = 10;
    private int difficulty = -1; // -1 表示全部难度
    private String category = null;
    private String questionType = null;
    
    // 当前会话的题目
    private List<Question> sessionQuestions;
    private int currentIndex = 0;
    private long sessionStartTime;
    private long sessionId;
    
    // 答案记录
    private Map<Long, String> userAnswers; // questionId -> userAnswer
    private Map<Long, Boolean> questionResults; // questionId -> isCorrect

    public QuizSessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.database = AppDatabase.getDatabase(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.gson = new Gson();
        this.userAnswers = new HashMap<>();
        this.questionResults = new HashMap<>();
    }

    // ==================== 会话配置 ====================
    
    public QuizSessionManager setMode(QuizMode mode) {
        this.currentMode = mode;
        return this;
    }
    
    public QuizSessionManager setQuestionCount(int count) {
        this.questionCount = Math.max(1, Math.min(count, 100));
        return this;
    }
    
    public QuizSessionManager setDifficulty(int difficulty) {
        this.difficulty = difficulty;
        return this;
    }
    
    public QuizSessionManager setCategory(String category) {
        this.category = category;
        return this;
    }
    
    public QuizSessionManager setQuestionType(String questionType) {
        this.questionType = questionType;
        return this;
    }

    // ==================== 生成题目 ====================
    
    /**
     * 异步生成测验题目
     */
    public Future<QuizSession> generateSession() {
        return executor.submit(() -> {
            try {
                sessionStartTime = System.currentTimeMillis();
                sessionId = sessionStartTime;
                
                // 根据模式生成题目
                sessionQuestions = generateQuestionsByMode();
                
                // 打乱顺序（如果是随机模式）
                if (currentMode == QuizMode.RANDOM) {
                    Collections.shuffle(sessionQuestions);
                }
                
                // 限制数量
                if (sessionQuestions.size() > questionCount) {
                    sessionQuestions = sessionQuestions.subList(0, questionCount);
                }
                
                // 创建会话
                QuizSession session = new QuizSession();
                session.sessionId = sessionId;
                session.mode = currentMode;
                session.questions = sessionQuestions;
                session.totalCount = sessionQuestions.size();
                session.currentIndex = 0;
                session.startTime = sessionStartTime;
                
                Log.i(TAG, "生成测验会话: 模式=" + currentMode.getDisplayName() + 
                      ", 题目数=" + sessionQuestions.size());
                
                return session;
                
            } catch (Exception e) {
                Log.e(TAG, "生成测验会话失败", e);
                return null;
            }
        });
    }

    /**
     * 根据模式生成题目
     */
    private List<Question> generateQuestionsByMode() throws Exception {
        switch (currentMode) {
            case CHAPTER:
                return generateChapterQuestions();
            case RANDOM:
                return generateRandomQuestions();
            case SPECIAL:
                return generateSpecialQuestions();
            case EXAM:
                return generateExamQuestions();
            case REVIEW:
                return generateReviewQuestions();
            case WEAK:
                return generateWeakQuestions();
            default:
                return generateChapterQuestions();
        }
    }

    /**
     * 章节练习：按分类生成
     */
    private List<Question> generateChapterQuestions() throws Exception {
        // 直接获取所有题目
        List<Question> allQuestions = database.questionDao().getQuestions();
        
        if (category != null && !category.isEmpty()) {
            allQuestions.removeIf(q -> !category.equals(q.getCategory()));
        }
        
        if (difficulty > 0) {
            allQuestions.removeIf(q -> q.getDifficulty() != difficulty);
        }
        
        if (questionType != null && !questionType.isEmpty()) {
            allQuestions.removeIf(q -> !questionType.equals(q.getQuestionType()));
        }
        
        // 按分类排序
        Collections.sort(allQuestions, (a, b) -> {
            String catA = a.getCategory() != null ? a.getCategory() : "";
            String catB = b.getCategory() != null ? b.getCategory() : "";
            return catA.compareTo(catB);
        });
        
        return allQuestions;
    }

    /**
     * 随机练习：随机抽取
     */
    private List<Question> generateRandomQuestions() throws Exception {
        List<Question> allQuestions = database.questionDao().getQuestions();
        
        // 过滤
        if (difficulty > 0) {
            allQuestions.removeIf(q -> q.getDifficulty() != difficulty);
        }
        if (questionType != null && !questionType.isEmpty()) {
            allQuestions.removeIf(q -> !questionType.equals(q.getQuestionType()));
        }
        
        // 随机打乱
        Collections.shuffle(allQuestions);
        
        return allQuestions;
    }

    /**
     * 专项练习：错题或收藏
     */
    private List<Question> generateSpecialQuestions() throws Exception {
        List<Question> questions = new ArrayList<>();
        
        // 获取错题
        List<WrongQuestion> wrongQuestions = database.wrongQuestionDao().getWrongQuestions();
        
        for (WrongQuestion wq : wrongQuestions) {
            Question question = database.questionDao().getQuestionById(wq.getQuestionId());
            if (question != null) {
                questions.add(question);
            }
        }
        
        // 获取收藏 - 简化实现，直接返回空列表
        List<Question> favorites = new ArrayList<>();
        questions.addAll(favorites);
        
        return questions;
    }

    /**
     * 模拟考试：计时考试
     */
    private List<Question> generateExamQuestions() throws Exception {
        List<Question> allQuestions = database.questionDao().getQuestions();
        
        // 模拟考试需要混合难度
        List<Question> easy = new ArrayList<>();
        List<Question> medium = new ArrayList<>();
        List<Question> hard = new ArrayList<>();
        
        for (Question q : allQuestions) {
            switch (q.getDifficulty()) {
                case 1: easy.add(q); break;
                case 2: medium.add(q); break;
                case 3: hard.add(q); break;
            }
        }
        
        // 按比例分配：简单40%，中等40%，困难20%
        List<Question> examQuestions = new ArrayList<>();
        Collections.shuffle(easy);
        Collections.shuffle(medium);
        Collections.shuffle(hard);
        
        int easyCount = (int) (questionCount * 0.4);
        int mediumCount = (int) (questionCount * 0.4);
        int hardCount = questionCount - easyCount - mediumCount;
        
        examQuestions.addAll(easy.subList(0, Math.min(easyCount, easy.size())));
        examQuestions.addAll(medium.subList(0, Math.min(mediumCount, medium.size())));
        examQuestions.addAll(hard.subList(0, Math.min(hardCount, hard.size())));
        
        // 随机排序
        Collections.shuffle(examQuestions);
        
        return examQuestions;
    }

    /**
     * 复习模式：回顾已做过的题目
     */
    private List<Question> generateReviewQuestions() throws Exception {
        List<Question> questions = new ArrayList<>();
        
        // 获取最近做过的题目 - 直接返回所有题目（简化实现）
        questions = database.questionDao().getQuestions();
        
        // 限制数量
        if (questions.size() > questionCount) {
            questions = questions.subList(0, questionCount);
        }
        
        return questions;
    }

    /**
     * 薄弱点练习：易错题
     */
    private List<Question> generateWeakQuestions() throws Exception {
        List<Question> questions = new ArrayList<>();
        
        // 获取错题
        List<WrongQuestion> wrongQuestions = database.wrongQuestionDao().getWrongQuestions();
        
        // 按错误次数排序
        Collections.sort(wrongQuestions, (a, b) -> 
            Integer.compare(b.getWrongCount(), a.getWrongCount()));
        
        for (WrongQuestion wq : wrongQuestions) {
            if (questions.size() >= questionCount) break;
            
            Question question = database.questionDao().getQuestionById(wq.getQuestionId());
            if (question != null) {
                questions.add(question);
            }
        }
        
        return questions;
    }

    private boolean containsQuestion(List<Question> list, long id) {
        for (Question q : list) {
            if (q.getId() == id) return true;
        }
        return false;
    }

    // ==================== 答题操作 ====================
    
    /**
     * 获取当前题目
     */
    public Question getCurrentQuestion() {
        if (sessionQuestions == null || sessionQuestions.isEmpty() || 
            currentIndex >= sessionQuestions.size()) {
            return null;
        }
        return sessionQuestions.get(currentIndex);
    }
    
    /**
     * 提交答案
     */
    public AnswerResult submitAnswer(String answer) {
        Question question = getCurrentQuestion();
        if (question == null) {
            return new AnswerResult(false, "没有当前题目");
        }
        
        boolean isCorrect = checkAnswer(question, answer);
        
        // 记录答案
        userAnswers.put(question.getId(), answer);
        questionResults.put(question.getId(), isCorrect);
        
        // 更新错题本
        if (!isCorrect) {
            addToWrongQuestions(question);
        }
        
        return new AnswerResult(isCorrect, question.getCorrectAnswer());
    }
    
    /**
     * 检查答案是否正确
     */
    private boolean checkAnswer(Question question, String userAnswer) {
        if (userAnswer == null || question.getCorrectAnswer() == null) {
            return false;
        }
        
        String correct = question.getCorrectAnswer().trim().toLowerCase();
        String user = userAnswer.trim().toLowerCase();
        
        // 兼容选项格式
        if (correct.startsWith("答案:") || correct.startsWith("正确答案:")) {
            correct = correct.substring(correct.indexOf(":") + 1).trim();
        }
        
        return correct.equals(user);
    }
    
    /**
     * 添加到错题本
     */
    private void addToWrongQuestions(Question question) {
        executor.execute(() -> {
            try {
                WrongQuestion wq = database.wrongQuestionDao()
                    .getWrongQuestionByUserIdAndQuestionId(0, question.getId());
                
                if (wq == null) {
                    wq = new WrongQuestion();
                    wq.setQuestionId(question.getId());
                    wq.setUserId(0);
                    wq.setWrongCount(1);
                    wq.setLastWrongTime(System.currentTimeMillis());
                    wq.setUserAnswer(userAnswers.get(question.getId()));
                    database.wrongQuestionDao().insert(wq);
                } else {
                    wq.setWrongCount(wq.getWrongCount() + 1);
                    wq.setLastWrongTime(System.currentTimeMillis());
                    wq.setUserAnswer(userAnswers.get(question.getId()));
                    database.wrongQuestionDao().update(wq);
                }
            } catch (Exception e) {
                Log.e(TAG, "添加错题失败", e);
            }
        });
    }
    
    /**
     * 下一题
     */
    public boolean moveToNext() {
        if (currentIndex < sessionQuestions.size() - 1) {
            currentIndex++;
            return true;
        }
        return false;
    }
    
    /**
     * 上一题
     */
    public boolean moveToPrevious() {
        if (currentIndex > 0) {
            currentIndex--;
            return true;
        }
        return false;
    }
    
    /**
     * 跳转到指定题
     */
    public boolean jumpToQuestion(int index) {
        if (index >= 0 && index < sessionQuestions.size()) {
            currentIndex = index;
            return true;
        }
        return false;
    }

    // ==================== 完成测验 ====================
    
    /**
     * 完成测验，保存成绩
     */
    public Future<ScoreHistory> finishQuiz() {
        return executor.submit(() -> {
            if (sessionQuestions == null || sessionQuestions.isEmpty()) {
                return null;
            }
            
            long endTime = System.currentTimeMillis();
            int correctCount = 0;
            
            for (Boolean result : questionResults.values()) {
                if (result != null && result) {
                    correctCount++;
                }
            }
            
            // 计算正确率
            float accuracy = (float) correctCount / sessionQuestions.size() * 100;
            
            // 生成题目IDs JSON
            long[] questionIds = new long[sessionQuestions.size()];
            for (int i = 0; i < sessionQuestions.size(); i++) {
                questionIds[i] = sessionQuestions.get(i).getId();
            }
            String questionIdsJson = gson.toJson(questionIds);
            
            // 生成答案记录JSON
            String answersJson = gson.toJson(userAnswers);
            
            // 保存成绩
            ScoreHistory score = new ScoreHistory();
            score.setScore((int) accuracy);
            score.setTotalQuestions(sessionQuestions.size());
            score.setCorrectCount(correctCount);
            score.setEndTime(endTime);
            score.setStartTime(sessionStartTime);
            score.setQuizType(currentMode.getCode());
            
            database.scoreDao().insert(score);
            
            Log.i(TAG, "保存成绩: 正确率=" + accuracy + "%, 用时=" + 
                  (endTime - sessionStartTime) / 1000 + "秒");
            
            return score;
        });
    }

    // ==================== Getters ====================
    
    public int getCurrentIndex() {
        return currentIndex;
    }
    
    public int getTotalCount() {
        return sessionQuestions != null ? sessionQuestions.size() : 0;
    }
    
    public int getCorrectCount() {
        int count = 0;
        for (Boolean result : questionResults.values()) {
            if (result != null && result) count++;
        }
        return count;
    }
    
    public QuizMode getCurrentMode() {
        return currentMode;
    }
    
    public List<Question> getSessionQuestions() {
        return sessionQuestions;
    }
    
    public Map<Long, String> getUserAnswers() {
        return userAnswers;
    }
    
    public boolean isSessionComplete() {
        return sessionQuestions != null && 
               currentIndex >= sessionQuestions.size() - 1 &&
               !userAnswers.isEmpty();
    }

    // ==================== 内部类 ====================
    
    /**
     * 测验会话
     */
    public static class QuizSession {
        public long sessionId;
        public QuizMode mode;
        public List<Question> questions;
        public int totalCount;
        public int currentIndex;
        public long startTime;
        
        // 无参构造（JSON反序列化需要）
        public QuizSession() {}
    }
    
    /**
     * 答题结果
     */
    public static class AnswerResult {
        public boolean isCorrect;
        public String correctAnswer;
        public String message;
        
        public AnswerResult(boolean isCorrect, String correctAnswer) {
            this.isCorrect = isCorrect;
            this.correctAnswer = correctAnswer;
            this.message = isCorrect ? "回答正确" : "回答错误";
        }
    }
}