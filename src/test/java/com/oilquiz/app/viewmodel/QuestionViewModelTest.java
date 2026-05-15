package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.repository.QuestionRepository;

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
public class QuestionViewModelTest {

    private QuestionViewModel questionViewModel;
    private Application application;

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private QuestionViewModel.GetQuestionsCallback getQuestionsCallback;

    @Mock
    private QuestionViewModel.GetQuestionCallback getQuestionCallback;

    @Mock
    private QuestionViewModel.AddQuestionCallback addQuestionCallback;

    @Mock
    private QuestionViewModel.UpdateQuestionCallback updateQuestionCallback;

    @Mock
    private QuestionViewModel.DeleteQuestionCallback deleteQuestionCallback;

    @Mock
    private QuestionViewModel.BatchOperationCallback batchOperationCallback;

    @Mock
    private QuestionViewModel.SearchQuestionsCallback searchQuestionsCallback;

    @Mock
    private QuestionViewModel.GetQuestionsByCategoryCallback getQuestionsByCategoryCallback;

    @Mock
    private QuestionViewModel.GetQuestionCountCallback getQuestionCountCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        application = ApplicationProvider.getApplicationContext();
        // 这里需要修改QuestionViewModel的构造函数，使其接受QuestionRepository参数
        // 或者使用反射来设置questionRepository字段
        questionViewModel = new QuestionViewModel(application);
        // 使用反射设置questionRepository字段
        try {
            java.lang.reflect.Field field = QuestionViewModel.class.getDeclaredField("questionRepository");
            field.setAccessible(true);
            field.set(questionViewModel, questionRepository);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testGetQuestions() {
        questionViewModel.getQuestions(getQuestionsCallback);
        // 验证QuestionRepository的getQuestions方法是否被调用
        verify(questionRepository).getQuestions(getQuestionsCallback);
    }

    @Test
    public void testGetQuestionsWithPagination() {
        int page = 1;
        int pageSize = 10;
        questionViewModel.getQuestionsWithPagination(page, pageSize, getQuestionsCallback);
        // 验证QuestionRepository的getQuestionsWithPagination方法是否被调用
        verify(questionRepository).getQuestionsWithPagination(page, pageSize, getQuestionsCallback);
    }

    @Test
    public void testGetQuestionById() {
        long id = 1L;
        questionViewModel.getQuestionById(id, getQuestionCallback);
        // 验证QuestionRepository的getQuestionById方法是否被调用
        verify(questionRepository).getQuestionById(id, getQuestionCallback);
    }

    @Test
    public void testAddQuestion() {
        Question question = new Question();
        question.setQuestionText("测试题目");
        question.setCorrectAnswer("A");
        questionViewModel.addQuestion(question, addQuestionCallback);
        // 验证QuestionRepository的addQuestion方法是否被调用
        verify(questionRepository).addQuestion(question, addQuestionCallback);
    }

    @Test
    public void testAddQuestions() {
        List<Question> questions = new ArrayList<>();
        Question question1 = new Question();
        question1.setQuestionText("测试题目1");
        question1.setCorrectAnswer("A");
        questions.add(question1);

        Question question2 = new Question();
        question2.setQuestionText("测试题目2");
        question2.setCorrectAnswer("B");
        questions.add(question2);

        questionViewModel.addQuestions(questions, batchOperationCallback);
        // 验证QuestionRepository的addQuestions方法是否被调用
        verify(questionRepository).addQuestions(questions, batchOperationCallback);
    }

    @Test
    public void testUpdateQuestion() {
        Question question = new Question();
        question.setId(1L);
        question.setQuestionText("更新后的测试题目");
        question.setCorrectAnswer("A");
        questionViewModel.updateQuestion(question, updateQuestionCallback);
        // 验证QuestionRepository的updateQuestion方法是否被调用
        verify(questionRepository).updateQuestion(question, updateQuestionCallback);
    }

    @Test
    public void testUpdateQuestions() {
        List<Question> questions = new ArrayList<>();
        Question question1 = new Question();
        question1.setId(1L);
        question1.setQuestionText("更新后的测试题目1");
        question1.setCorrectAnswer("A");
        questions.add(question1);

        Question question2 = new Question();
        question2.setId(2L);
        question2.setQuestionText("更新后的测试题目2");
        question2.setCorrectAnswer("B");
        questions.add(question2);

        questionViewModel.updateQuestions(questions, batchOperationCallback);
        // 验证QuestionRepository的updateQuestions方法是否被调用
        verify(questionRepository).updateQuestions(questions, batchOperationCallback);
    }

    @Test
    public void testDeleteQuestion() {
        long id = 1L;
        questionViewModel.deleteQuestion(id, deleteQuestionCallback);
        // 验证QuestionRepository的deleteQuestion方法是否被调用
        verify(questionRepository).deleteQuestion(id, deleteQuestionCallback);
    }

    @Test
    public void testDeleteQuestions() {
        List<Long> ids = new ArrayList<>();
        ids.add(1L);
        ids.add(2L);
        questionViewModel.deleteQuestions(ids, batchOperationCallback);
        // 验证QuestionRepository的deleteQuestions方法是否被调用
        verify(questionRepository).deleteQuestions(ids, batchOperationCallback);
    }

    @Test
    public void testSearchQuestions() {
        String keyword = "测试";
        questionViewModel.searchQuestions(keyword, searchQuestionsCallback);
        // 验证QuestionRepository的searchQuestions方法是否被调用
        verify(questionRepository).searchQuestions(keyword, searchQuestionsCallback);
    }

    @Test
    public void testSearchQuestionsWithFilters() {
        String keyword = "测试";
        String category = "数学";
        String type = "单选题";
        Integer difficulty = 3;
        questionViewModel.searchQuestionsWithFilters(keyword, category, type, difficulty, searchQuestionsCallback);
        // 验证QuestionRepository的searchQuestionsWithFilters方法是否被调用
        verify(questionRepository).searchQuestionsWithFilters(eq(keyword), eq(category), eq(type), eq(difficulty), any(QuestionRepository.RepositoryCallback.class));
    }

    @Test
    public void testGetQuestionsByCategory() {
        String category = "数学";
        questionViewModel.getQuestionsByCategory(category, getQuestionsByCategoryCallback);
        // 验证QuestionRepository的getQuestionsByCategory方法是否被调用
        verify(questionRepository).getQuestionsByCategory(category, getQuestionsByCategoryCallback);
    }

    @Test
    public void testGetQuestionsByType() {
        String type = "单选题";
        questionViewModel.getQuestionsByType(type, getQuestionsCallback);
        // 验证QuestionRepository的getQuestionsByType方法是否被调用
        verify(questionRepository).getQuestionsByType(eq(type), any(QuestionRepository.RepositoryCallback.class));
    }

    @Test
    public void testGetQuestionsByDifficulty() {
        int difficulty = 3;
        questionViewModel.getQuestionsByDifficulty(difficulty, getQuestionsCallback);
        // 验证QuestionRepository的getQuestionsByDifficulty方法是否被调用
        verify(questionRepository).getQuestionsByDifficulty(eq(difficulty), any(QuestionRepository.RepositoryCallback.class));
    }

    @Test
    public void testGetQuestionCount() {
        questionViewModel.getQuestionCount(getQuestionCountCallback);
        // 验证QuestionRepository的getQuestionCount方法是否被调用
        verify(questionRepository).getQuestionCount(any(QuestionRepository.RepositoryCallback.class));
    }

    @Test
    public void testGetQuestionCountByCategory() {
        String category = "数学";
        questionViewModel.getQuestionCountByCategory(category, getQuestionCountCallback);
        // 验证QuestionRepository的getQuestionCountByCategory方法是否被调用
        verify(questionRepository).getQuestionCountByCategory(category, getQuestionCountCallback);
    }
}