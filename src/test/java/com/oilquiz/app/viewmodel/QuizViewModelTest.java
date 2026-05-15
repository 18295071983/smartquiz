package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.repository.QuestionRepository;
import com.oilquiz.app.repository.ScoreRepository;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class QuizViewModelTest {

    private QuizViewModel quizViewModel;
    private Application application;

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private ScoreRepository scoreRepository;

    @Mock
    private QuizViewModel.GetQuestionsCallback getQuestionsCallback;

    @Mock
    private QuizViewModel.SaveScoreCallback saveScoreCallback;

    @Mock
    private QuizViewModel.GetScoreHistoryCallback getScoreHistoryCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        application = ApplicationProvider.getApplicationContext();
        quizViewModel = new QuizViewModel();
        // 使用反射设置questionRepository和scoreRepository字段
        try {
            java.lang.reflect.Field questionRepositoryField = QuizViewModel.class.getDeclaredField("questionRepository");
            questionRepositoryField.setAccessible(true);
            questionRepositoryField.set(quizViewModel, questionRepository);
            
            java.lang.reflect.Field scoreRepositoryField = QuizViewModel.class.getDeclaredField("scoreRepository");
            scoreRepositoryField.setAccessible(true);
            scoreRepositoryField.set(quizViewModel, scoreRepository);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testCalculateScore() {
        // 创建测试题目
        List<Question> questions = new ArrayList<>();
        Question question1 = new Question();
        question1.setCorrectAnswer("A");
        questions.add(question1);

        Question question2 = new Question();
        question2.setCorrectAnswer("B");
        questions.add(question2);

        Question question3 = new Question();
        question3.setCorrectAnswer("C");
        questions.add(question3);

        // 创建测试答案
        List<String> answers = new ArrayList<>();
        answers.add("A"); // 正确
        answers.add("B"); // 正确
        answers.add("D"); // 错误

        // 计算得分
        int score = quizViewModel.calculateScore(questions, answers);
        assertEquals("得分应该是2", 2, score);
    }

    @Test
    public void testCalculateScorePercentage() {
        // 创建测试题目
        List<Question> questions = new ArrayList<>();
        Question question1 = new Question();
        question1.setCorrectAnswer("A");
        questions.add(question1);

        Question question2 = new Question();
        question2.setCorrectAnswer("B");
        questions.add(question2);

        Question question3 = new Question();
        question3.setCorrectAnswer("C");
        questions.add(question3);

        // 创建测试答案
        List<String> answers = new ArrayList<>();
        answers.add("A"); // 正确
        answers.add("B"); // 正确
        answers.add("D"); // 错误

        // 计算得分百分比
        double percentage = quizViewModel.calculateScorePercentage(questions, answers);
        assertEquals("得分百分比应该是66.67", 66.67, percentage, 0.01);
    }

    @Test
    public void testAnalyzeQuizResults() {
        // 创建测试题目
        List<Question> questions = new ArrayList<>();
        Question question1 = new Question();
        question1.setCorrectAnswer("A");
        questions.add(question1);

        Question question2 = new Question();
        question2.setCorrectAnswer("B");
        questions.add(question2);

        Question question3 = new Question();
        question3.setCorrectAnswer("C");
        questions.add(question3);

        // 创建测试答案
        List<String> answers = new ArrayList<>();
        answers.add("A"); // 正确
        answers.add("B"); // 正确
        answers.add("D"); // 错误

        // 分析答题结果
        QuizViewModel.QuizAnalysis analysis = quizViewModel.analyzeQuizResults(questions, answers);
        assertEquals("正确题目数应该是2", 2, analysis.getCorrectCount());
        assertEquals("错误题目数应该是1", 1, analysis.getIncorrectCount());
        assertEquals("错误题目索引应该是[2]", 1, analysis.getIncorrectIndices().size());
        assertEquals("错误题目索引应该是2", Integer.valueOf(2), analysis.getIncorrectIndices().get(0));
    }

    @Test
    public void testGetQuestionsForQuiz() {
        int count = 5;
        String type = "单选题";
        quizViewModel.getQuestionsForQuiz(count, type, getQuestionsCallback);
        // 验证repository方法是否被调用
        verify(questionRepository).getQuestionsByType(eq(type), any(QuestionRepository.RepositoryCallback.class));
    }

    @Test
    public void testGetQuestionsForQuizByDifficulty() {
        int count = 5;
        int difficulty = 3;
        quizViewModel.getQuestionsForQuizByDifficulty(count, difficulty, getQuestionsCallback);
        // 验证repository方法是否被调用
        verify(questionRepository).getQuestionsByDifficulty(eq(difficulty), any(QuestionRepository.RepositoryCallback.class));
    }

    @Test
    public void testGetQuestionsForQuizWithFilters() {
        int count = 5;
        String type = "单选题";
        Integer difficulty = 3;
        String category = "数学";
        quizViewModel.getQuestionsForQuizWithFilters(count, type, difficulty, category, getQuestionsCallback);
        // 验证repository方法是否被调用
        verify(questionRepository).searchQuestionsWithFilters(eq(""), eq(category), eq(type), eq(difficulty), any(QuestionRepository.RepositoryCallback.class));
    }

    @Test
    public void testSaveScore() {
        ScoreHistory score = new ScoreHistory();
        score.setUserId(1L);
        score.setScore(85);
        score.setTotalQuestions(10);
        score.setQuizType("模拟考试");
        quizViewModel.saveScore(score, saveScoreCallback);
        // 验证repository方法是否被调用
        verify(scoreRepository).addScore(eq(score));
    }

    @Test
    public void testGetScoreHistory() {
        long userId = 1L;
        quizViewModel.getScoreHistory(userId, getScoreHistoryCallback);
        // 验证repository方法是否被调用
        verify(scoreRepository).getScoresByUserId(eq(userId));
    }

    @Test
    public void testGetRecentScores() {
        long userId = 1L;
        int count = 5;
        quizViewModel.getRecentScores(userId, count, getScoreHistoryCallback);
        // 验证repository方法是否被调用
        verify(scoreRepository).getRecentScores(eq(userId), eq(count));
    }
}