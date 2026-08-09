package com.oilquiz.app.repository;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.QuestionDao;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuestionRepository {

    private QuestionDao questionDao;
    private ExecutorService executorService;
    private Handler mainHandler;
    
    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onFailure(String error);
    }

    public QuestionRepository(Application application) {
        AppDatabase database = AppDatabase.getDatabase(application);
        questionDao = database.questionDao();
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void getQuestions(final QuestionViewModel.GetQuestionsCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Question> questions = questionDao.getQuestions();
                    // 确保返回空列表而不是null
                    if (questions == null) {
                        questions = new ArrayList<>();
                    }
                    final List<Question> finalQuestions = questions;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(finalQuestions);
                        }
                    });
                } catch (final Exception e) {
                    final String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(errorMsg);
                        }
                    });
                }
            }
        });
    }

    public void getQuestionById(final long id, final QuestionViewModel.GetQuestionCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Question question = questionDao.getQuestionById(id);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(question);
                        }
                    });
                } catch (final Exception e) {
                    final String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.toString();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(errorMsg);
                        }
                    });
                }
            }
        });
    }

    public void getQuestionById(final long id, final RepositoryCallback<Question> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Question question = questionDao.getQuestionById(id);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(question);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void addQuestion(final Question question, final QuestionViewModel.AddQuestionCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    questionDao.insert(question);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void updateQuestion(final Question question, final QuestionViewModel.UpdateQuestionCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    questionDao.update(question);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void deleteQuestion(final long id, final QuestionViewModel.DeleteQuestionCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    questionDao.deleteQuestion(id);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void searchQuestions(final String keyword, final QuestionViewModel.SearchQuestionsCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.searchQuestions(keyword);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getQuestionsByCategory(final String category, final QuestionViewModel.GetQuestionsByCategoryCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.getQuestionsByCategory(category);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    public void getAllQuestions(final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.getQuestions();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    public void getQuestionCount(final RepositoryCallback<Integer> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final int count = questionDao.getQuestionCount();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(count);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    public void getAllCategories(final RepositoryCallback<List<String>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<String> categories = questionDao.getAllCategories();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(categories);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    public void getAllQuestionTypes(final RepositoryCallback<List<String>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<String> types = questionDao.getAllQuestionTypes();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(types);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    public void searchQuestionsByCategory(final String category, final String keyword, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.searchQuestionsByCategory(category, keyword);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getQuestionsByType(final String type, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.getQuestionsByType(type);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getRandomQuestions(final int count, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // v23优化: 使用SQL层RANDOM()+LIMIT，不再全量加载
                    final List<Question> questions = questionDao.getRandomQuestionsLimited(count);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getCustomQuestionTypeMapping(final RepositoryCallback<Map<String, String>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> mapping = new HashMap<>();
                    mapping.put("单选题", "single_choice");
                    mapping.put("多选题", "multiple_choice");
                    mapping.put("判断题", "true_false");
                    mapping.put("填空题", "fill_blank");
                    mapping.put("简答题", "short_answer");
                    final Map<String, String> finalMapping = mapping;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(finalMapping);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getQuestionsByCategory(final String category, final int limit, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // v23优化: 使用SQL层LIMIT，不再全量加载后subList
                    final List<Question> questions;
                    if (limit > 0) {
                        questions = questionDao.getRandomQuestionsByCategoryLimited(category, limit);
                    } else {
                        questions = questionDao.getQuestionsByCategory(category);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    /** v23优化: 按题型限量随机抽题（利用索引） */
    public void getRandomQuestionsByType(final String type, final int limit, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.getRandomQuestionsByType(type, limit);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    /** v23优化: 按分类限量随机抽题（利用索引） */
    public void getRandomQuestionsByCategory(final String category, final int limit, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.getRandomQuestionsByCategoryLimited(category, limit);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }
    
    /** v23优化: 全局限量随机抽题 */
    public void getRandomQuestionsLimited(final int limit, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.getRandomQuestionsLimited(limit);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getQuestionsByDifficulty(final int difficulty, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Question> questions = questionDao.getQuestionsByDifficulty(difficulty);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(questions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void searchQuestionsWithFilters(final String keyword, final String category, final String type, final Integer difficulty, final RepositoryCallback<List<Question>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // 使用DAO方法实现带过滤条件的搜索逻辑
                    List<Question> filteredQuestions = questionDao.searchQuestionsWithFilters(
                            keyword != null ? keyword : "",
                            category != null && !category.isEmpty() ? category : null,
                            type != null && !type.isEmpty() ? type : null,
                            difficulty
                    );
                    final List<Question> finalQuestions = filteredQuestions;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(finalQuestions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getQuestionsWithPagination(final int page, final int pageSize, final QuestionViewModel.GetQuestionsCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Question> questions = questionDao.getQuestions();
                    // 简单的分页实现
                    int start = (page - 1) * pageSize;
                    int end = Math.min(start + pageSize, questions.size());
                    if (start < questions.size()) {
                        questions = questions.subList(start, end);
                    } else {
                        questions = new ArrayList<>();
                    }
                    final List<Question> finalQuestions = questions;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(finalQuestions);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void addQuestions(final List<Question> questions, final QuestionViewModel.BatchOperationCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (Question question : questions) {
                        questionDao.insert(question);
                    }
                    final int count = questions.size();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(count);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void updateQuestions(final List<Question> questions, final QuestionViewModel.BatchOperationCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (Question question : questions) {
                        questionDao.update(question);
                    }
                    final int count = questions.size();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(count);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void deleteQuestions(final List<Long> ids, final QuestionViewModel.BatchOperationCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    for (Long id : ids) {
                        questionDao.deleteQuestion(id);
                    }
                    final int count = ids.size();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(count);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void clearAllQuestions(final QuestionViewModel.BatchOperationCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    questionDao.deleteAllQuestions();
                    final int count = 1;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(count);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void getQuestionCountByCategory(final String category, final QuestionViewModel.GetQuestionCountCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Question> questions = questionDao.getQuestionsByCategory(category);
                    final int count = questions.size();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(count);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public void setQuestionFavorite(final long questionId, final boolean isFavorite, final RepositoryCallback<Boolean> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Question question = questionDao.getQuestionById(questionId);
                    if (question != null) {
                        question.setFavorite(isFavorite);
                        questionDao.update(question);
                        final boolean result = true;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(result);
                            }
                        });
                    } else {
                        final boolean result = false;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(result);
                            }
                        });
                    }
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    // ========== 题目统计更新方法 ==========

    /**
     * 增加题目使用次数
     */
    public void incrementUsageCount(final long questionId, final long timestamp) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    questionDao.incrementUsageCount(questionId, timestamp);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 增加题目正确次数
     */
    public void incrementCorrectCount(final long questionId) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    questionDao.incrementCorrectCount(questionId);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 增加题目错误次数
     */
    public void incrementIncorrectCount(final long questionId) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    questionDao.incrementIncorrectCount(questionId);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
