package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.repository.QuestionRepository;
import com.oilquiz.app.repository.ScoreRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class QuizViewModel extends ViewModel {
    private QuestionRepository questionRepository;
    private ScoreRepository scoreRepository;
    private Random random = new Random();

    // 初始化Repository
    public void init(Application application) {
        questionRepository = new QuestionRepository(application);
        scoreRepository = new ScoreRepository(application);
    }

    // 获取指定数量的题目
    public void getQuestionsForQuiz(int count, String type, GetQuestionsCallback callback) {
        if (questionRepository == null) {
            callback.onError("ViewModel未初始化");
            return;
        }

        questionRepository.getQuestionsByType(type, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> questions) {
                // 随机选择指定数量的题目
                List<Question> quizQuestions = getRandomQuestions(questions, count);
                callback.onSuccess(quizQuestions);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    // 根据难度获取题目
    public void getQuestionsForQuizByDifficulty(int count, int difficulty, GetQuestionsCallback callback) {
        if (questionRepository == null) {
            callback.onError("ViewModel未初始化");
            return;
        }

        questionRepository.getQuestionsByDifficulty(difficulty, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> questions) {
                List<Question> quizQuestions = getRandomQuestions(questions, count);
                callback.onSuccess(quizQuestions);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    // 根据多个条件获取题目
    public void getQuestionsForQuizWithFilters(int count, String type, Integer difficulty, String category, GetQuestionsCallback callback) {
        if (questionRepository == null) {
            callback.onError("ViewModel未初始化");
            return;
        }

        questionRepository.searchQuestionsWithFilters("", category, type, difficulty, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> questions) {
                List<Question> quizQuestions = getRandomQuestions(questions, count);
                callback.onSuccess(quizQuestions);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    // 保存成绩
    public void saveScore(ScoreHistory score, SaveScoreCallback callback) {
        if (scoreRepository == null) {
            callback.onError("ViewModel未初始化");
            return;
        }

        try {
            long id = scoreRepository.addScore(score);
            callback.onSuccess(id);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    // 获取用户成绩历史
    public void getScoreHistory(long userId, GetScoreHistoryCallback callback) {
        if (scoreRepository == null) {
            callback.onError("ViewModel未初始化");
            return;
        }

        try {
            List<ScoreHistory> scores = scoreRepository.getScoresByUserId(userId);
            callback.onSuccess(scores);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    // 获取用户最近的成绩
    public void getRecentScores(long userId, int count, GetScoreHistoryCallback callback) {
        if (scoreRepository == null) {
            callback.onError("ViewModel未初始化");
            return;
        }

        try {
            List<ScoreHistory> scores = scoreRepository.getRecentScores(userId, count);
            callback.onSuccess(scores);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    // 计算得分
    public int calculateScore(List<Question> questions, List<String> answers) {
        int score = 0;
        for (int i = 0; i < questions.size() && i < answers.size(); i++) {
            Question question = questions.get(i);
            String userAnswer = answers.get(i);
            if (question.getCorrectAnswer().equals(userAnswer)) {
                score++;
            }
        }
        return score;
    }

    // 计算得分百分比
    public double calculateScorePercentage(List<Question> questions, List<String> answers) {
        if (questions.isEmpty()) {
            return 0.0;
        }
        int score = calculateScore(questions, answers);
        return (double) score / questions.size() * 100;
    }

    // 分析答题结果
    public QuizAnalysis analyzeQuizResults(List<Question> questions, List<String> answers) {
        int correctCount = 0;
        int incorrectCount = 0;
        List<Integer> incorrectIndices = new ArrayList<>();

        for (int i = 0; i < questions.size() && i < answers.size(); i++) {
            Question question = questions.get(i);
            String userAnswer = answers.get(i);
            if (question.getCorrectAnswer().equals(userAnswer)) {
                correctCount++;
            } else {
                incorrectCount++;
                incorrectIndices.add(i);
            }
        }

        return new QuizAnalysis(correctCount, incorrectCount, incorrectIndices);
    }

    // 随机获取题目
    private List<Question> getRandomQuestions(List<Question> questions, int count) {
        List<Question> shuffled = new ArrayList<>(questions);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    // 答题分析结果类
    public static class QuizAnalysis {
        private int correctCount;
        private int incorrectCount;
        private List<Integer> incorrectIndices;

        public QuizAnalysis(int correctCount, int incorrectCount, List<Integer> incorrectIndices) {
            this.correctCount = correctCount;
            this.incorrectCount = incorrectCount;
            this.incorrectIndices = incorrectIndices;
        }

        public int getCorrectCount() {
            return correctCount;
        }

        public int getIncorrectCount() {
            return incorrectCount;
        }

        public List<Integer> getIncorrectIndices() {
            return incorrectIndices;
        }
    }

    // 获取题目回调接口
    public interface GetQuestionsCallback {
        void onSuccess(List<Question> questions);
        void onError(String error);
    }

    // 保存成绩回调接口
    public interface SaveScoreCallback {
        void onSuccess(long id);
        void onError(String error);
    }

    // 获取成绩历史回调接口
    public interface GetScoreHistoryCallback {
        void onSuccess(List<ScoreHistory> scores);
        void onError(String error);
    }
}
