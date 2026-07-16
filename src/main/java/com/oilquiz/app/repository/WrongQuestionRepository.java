package com.oilquiz.app.repository;

import android.app.Application;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.WrongQuestionDao;
import com.oilquiz.app.model.WrongQuestion;

import java.util.List;

public class WrongQuestionRepository {
    private WrongQuestionDao wrongQuestionDao;

    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public WrongQuestionRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        wrongQuestionDao = db.wrongQuestionDao();
    }

    public void addWrongQuestion(final WrongQuestion wrongQuestion, final RepositoryCallback<Long> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long id = wrongQuestionDao.insert(wrongQuestion);
                    callback.onSuccess(id);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void updateWrongQuestion(final WrongQuestion wrongQuestion, final RepositoryCallback<Void> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    wrongQuestionDao.update(wrongQuestion);
                    callback.onSuccess(null);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void deleteWrongQuestion(final long id, final RepositoryCallback<Void> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    wrongQuestionDao.deleteWrongQuestion(id);
                    callback.onSuccess(null);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void getWrongQuestionById(final long id, final RepositoryCallback<WrongQuestion> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WrongQuestion wrongQuestion = wrongQuestionDao.getWrongQuestionById(id);
                    callback.onSuccess(wrongQuestion);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void getWrongQuestionsByUserId(final long userId, final RepositoryCallback<List<WrongQuestion>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<WrongQuestion> wrongQuestions = wrongQuestionDao.getWrongQuestionsByUserId(userId);
                    callback.onSuccess(wrongQuestions);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void getWrongQuestionsByQuestionId(final long questionId, final RepositoryCallback<List<WrongQuestion>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<WrongQuestion> wrongQuestions = wrongQuestionDao.getWrongQuestionsByQuestionId(questionId);
                    callback.onSuccess(wrongQuestions);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void getWrongQuestionByUserIdAndQuestionId(final long userId, final long questionId, final RepositoryCallback<WrongQuestion> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WrongQuestion wrongQuestion = wrongQuestionDao.getWrongQuestionByUserIdAndQuestionId(userId, questionId);
                    callback.onSuccess(wrongQuestion);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void getWrongQuestions(final RepositoryCallback<List<WrongQuestion>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<WrongQuestion> wrongQuestions = wrongQuestionDao.getWrongQuestions();
                    callback.onSuccess(wrongQuestions);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    public void deleteAllWrongQuestions(final RepositoryCallback<Void> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    wrongQuestionDao.deleteAllWrongQuestions();
                    callback.onSuccess(null);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }
}
