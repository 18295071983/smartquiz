package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.repository.QuestionRepository;

import java.util.List;

/**
 * 题目相关的ViewModel，提供题目管理的各种功能
 */
public class QuestionViewModel extends AndroidViewModel {

    private QuestionRepository questionRepository;

    /**
     * 构造函数
     * @param application 应用上下文
     */
    public QuestionViewModel(Application application) {
        super(application);
        questionRepository = new QuestionRepository(application);
    }

    /**
     * 获取所有题目
     * @param callback 回调
     */
    public void getQuestions(GetQuestionsCallback callback) {
        questionRepository.getQuestions(callback);
    }

    /**
     * 分页获取题目
     * @param page 页码
     * @param pageSize 每页数量
     * @param callback 回调
     */
    public void getQuestionsWithPagination(int page, int pageSize, GetQuestionsCallback callback) {
        questionRepository.getQuestionsWithPagination(page, pageSize, callback);
    }

    /**
     * 根据ID获取题目
     * @param id 题目ID
     * @param callback 回调
     */
    public void getQuestionById(long id, GetQuestionCallback callback) {
        questionRepository.getQuestionById(id, callback);
    }

    /**
     * 添加题目
     * @param question 题目
     * @param callback 回调
     */
    public void addQuestion(Question question, AddQuestionCallback callback) {
        questionRepository.addQuestion(question, callback);
    }

    /**
     * 批量添加题目
     * @param questions 题目列表
     * @param callback 回调
     */
    public void addQuestions(List<Question> questions, BatchOperationCallback callback) {
        questionRepository.addQuestions(questions, callback);
    }

    /**
     * 更新题目
     * @param question 题目
     * @param callback 回调
     */
    public void updateQuestion(Question question, UpdateQuestionCallback callback) {
        questionRepository.updateQuestion(question, callback);
    }

    /**
     * 批量更新题目
     * @param questions 题目列表
     * @param callback 回调
     */
    public void updateQuestions(List<Question> questions, BatchOperationCallback callback) {
        questionRepository.updateQuestions(questions, callback);
    }

    /**
     * 删除题目
     * @param id 题目ID
     * @param callback 回调
     */
    public void deleteQuestion(long id, DeleteQuestionCallback callback) {
        questionRepository.deleteQuestion(id, callback);
    }

    /**
     * 删除题目（重载方法，无回调）
     * @param id 题目ID
     */
    public void deleteQuestion(long id) {
        deleteQuestion(id, null);
    }

    /**
     * 批量删除题目
     * @param ids 题目ID列表
     * @param callback 回调
     */
    public void deleteQuestions(List<Long> ids, BatchOperationCallback callback) {
        questionRepository.deleteQuestions(ids, callback);
    }

    /**
     * 清空所有题目
     * @param callback 回调
     */
    public void clearAllQuestions(BatchOperationCallback callback) {
        questionRepository.clearAllQuestions(callback);
    }

    /**
     * 搜索题目
     * @param keyword 关键词
     * @param callback 回调
     */
    public void searchQuestions(String keyword, SearchQuestionsCallback callback) {
        questionRepository.searchQuestions(keyword, callback);
    }

    /**
     * 带过滤条件的搜索题目
     * @param keyword 关键词
     * @param category 分类
     * @param type 类型
     * @param difficulty 难度
     * @param callback 回调
     */
    public void searchQuestionsWithFilters(String keyword, String category, String type, Integer difficulty, SearchQuestionsCallback callback) {
        questionRepository.searchQuestionsWithFilters(keyword, category, type, difficulty, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 根据分类获取题目
     * @param category 分类
     * @param callback 回调
     */
    public void getQuestionsByCategory(String category, GetQuestionsByCategoryCallback callback) {
        questionRepository.getQuestionsByCategory(category, callback);
    }

    /**
     * 根据类型获取题目
     * @param type 类型
     * @param callback 回调
     */
    public void getQuestionsByType(String type, GetQuestionsCallback callback) {
        questionRepository.getQuestionsByType(type, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 根据难度获取题目
     * @param difficulty 难度
     * @param callback 回调
     */
    public void getQuestionsByDifficulty(int difficulty, GetQuestionsCallback callback) {
        questionRepository.getQuestionsByDifficulty(difficulty, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 获取题目总数
     * @param callback 回调
     */
    public void getQuestionCount(GetQuestionCountCallback callback) {
        questionRepository.getQuestionCount(new QuestionRepository.RepositoryCallback<Integer>() {
            @Override
            public void onSuccess(Integer result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 根据分类获取题目数量
     * @param category 分类
     * @param callback 回调
     */
    public void getQuestionCountByCategory(String category, GetQuestionCountCallback callback) {
        questionRepository.getQuestionCountByCategory(category, callback);
    }

    /**
     * 获取题目列表回调接口
     */
    public interface GetQuestionsCallback {
        void onSuccess(List<Question> questions);
        void onError(String error);
    }

    /**
     * 获取单个题目回调接口
     */
    public interface GetQuestionCallback {
        void onSuccess(Question question);
        void onError(String error);
    }

    /**
     * 添加题目回调接口
     */
    public interface AddQuestionCallback {
        void onSuccess();
        void onError(String error);
    }

    /**
     * 更新题目回调接口
     */
    public interface UpdateQuestionCallback {
        void onSuccess();
        void onError(String error);
    }

    /**
     * 删除题目回调接口
     */
    public interface DeleteQuestionCallback {
        void onSuccess();
        void onError(String error);
    }

    /**
     * 批量操作回调接口
     */
    public interface BatchOperationCallback {
        void onSuccess(int count);
        void onError(String error);
    }

    /**
     * 搜索题目回调接口
     */
    public interface SearchQuestionsCallback {
        void onSuccess(List<Question> questions);
        void onError(String error);
    }

    /**
     * 根据分类获取题目回调接口
     */
    public interface GetQuestionsByCategoryCallback {
        void onSuccess(List<Question> questions);
        void onError(String error);
    }

    /**
     * 获取题目数量回调接口
     */
    public interface GetQuestionCountCallback {
        void onSuccess(int count);
        void onError(String error);
    }

    /**
     * 过滤题目
     * @param questionType 题型
     * @param category 分类
     * @param difficulty 难度
     * @param searchText 搜索文本
     * @param callback 回调
     */
    public void filterQuestions(String questionType, String category, String difficulty, String searchText, GetQuestionsCallback callback) {
        // 实现过滤逻辑
        Integer difficultyValue = null;
        if (difficulty != null) {
            // 将难度字符串转换为对应的数字
            switch (difficulty) {
                case "简单":
                    difficultyValue = 1;
                    break;
                case "中等":
                    difficultyValue = 2;
                    break;
                case "困难":
                    difficultyValue = 3;
                    break;
                case "全部":
                    difficultyValue = null;
                    break;
                default:
                    try {
                        difficultyValue = Integer.parseInt(difficulty);
                    } catch (NumberFormatException e) {
                        difficultyValue = null;
                    }
                    break;
            }
        }
        
        // 处理题型
        String typeValue = null;
        if (questionType != null && !questionType.equals("全部")) {
            typeValue = questionType;
        }
        
        // 处理分类
        String categoryValue = null;
        if (category != null && !category.equals("全部")) {
            categoryValue = category;
        }
        
        questionRepository.searchQuestionsWithFilters(searchText, categoryValue, typeValue, difficultyValue, new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 设置题目收藏状态
     * @param questionId 题目ID
     * @param isFavorite 是否收藏
     */
    public void setQuestionFavorite(long questionId, boolean isFavorite) {
        setQuestionFavorite(questionId, isFavorite, null);
    }

    /**
     * 设置题目收藏状态（带回调）
     * @param questionId 题目ID
     * @param isFavorite 是否收藏
     * @param callback 回调
     */
    public void setQuestionFavorite(long questionId, boolean isFavorite, SetFavoriteCallback callback) {
        questionRepository.setQuestionFavorite(questionId, isFavorite, new QuestionRepository.RepositoryCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (callback != null) {
                    callback.onSuccess();
                }
            }

            @Override
            public void onFailure(String error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }

    /**
     * 插入题目
     * @param question 题目
     */
    public void insertQuestion(Question question) {
        // 实现插入题目的逻辑
    }

    /**
     * 获取所有分类
     * @param callback 回调
     */
    public void getAllCategories(GetCategoriesCallback callback) {
        questionRepository.getAllCategories(new QuestionRepository.RepositoryCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 获取所有题目类型
     * @param callback 回调
     */
    public void getAllQuestionTypes(GetQuestionTypesCallback callback) {
        questionRepository.getAllQuestionTypes(new QuestionRepository.RepositoryCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> result) {
                callback.onSuccess(result);
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 获取分类列表回调接口
     */
    public interface GetCategoriesCallback {
        void onSuccess(List<String> categories);
        void onError(String error);
    }

    /**
     * 获取题目类型列表回调接口
     */
    public interface GetQuestionTypesCallback {
        void onSuccess(List<String> types);
        void onError(String error);
    }

    /**
     * 设置收藏状态回调接口
     */
    public interface SetFavoriteCallback {
        void onSuccess();
        void onError(String error);
    }
}