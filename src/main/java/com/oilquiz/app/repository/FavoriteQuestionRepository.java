package com.oilquiz.app.repository;

import android.content.Context;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.FavoriteQuestionDao;
import com.oilquiz.app.model.FavoriteQuestion;

import java.util.List;

public class FavoriteQuestionRepository {
    private FavoriteQuestionDao favoriteQuestionDao;
    
    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onFailure(String error);
    }

    public FavoriteQuestionRepository(Context context) {
        AppDatabase db = AppDatabase.getDatabase(context);
        favoriteQuestionDao = db.favoriteQuestionDao();
    }

    public long addFavoriteQuestion(FavoriteQuestion favoriteQuestion) {
        return favoriteQuestionDao.insert(favoriteQuestion);
    }

    public void deleteFavoriteQuestion(long id) {
        favoriteQuestionDao.deleteFavoriteQuestion(id);
    }

    public void deleteFavoriteQuestionByUserIdAndQuestionId(long userId, long questionId) {
        favoriteQuestionDao.deleteFavoriteQuestionByUserIdAndQuestionId(userId, questionId);
    }

    public FavoriteQuestion getFavoriteQuestionById(long id) {
        return favoriteQuestionDao.getFavoriteQuestionById(id);
    }

    public List<FavoriteQuestion> getFavoriteQuestionsByUserId(long userId) {
        return favoriteQuestionDao.getFavoriteQuestionsByUserId(userId);
    }

    public List<FavoriteQuestion> getFavoriteQuestionsByQuestionId(long questionId) {
        return favoriteQuestionDao.getFavoriteQuestionsByQuestionId(questionId);
    }

    public FavoriteQuestion getFavoriteQuestionByUserIdAndQuestionId(long userId, long questionId) {
        return favoriteQuestionDao.getFavoriteQuestionByUserIdAndQuestionId(userId, questionId);
    }
    
    public void addFavorite(long questionId, long userId, final RepositoryCallback<Long> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FavoriteQuestion favoriteQuestion = new FavoriteQuestion();
                    favoriteQuestion.setQuestionId(questionId);
                    favoriteQuestion.setUserId(userId);
                    long id = favoriteQuestionDao.insert(favoriteQuestion);
                    callback.onSuccess(id);
                } catch (Exception e) {
                    callback.onFailure(e.getMessage());
                }
            }
        }).start();
    }
    
    public void removeFavorite(long questionId, long userId, final RepositoryCallback<Void> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    favoriteQuestionDao.deleteFavoriteQuestionByUserIdAndQuestionId(userId, questionId);
                    callback.onSuccess(null);
                } catch (Exception e) {
                    callback.onFailure(e.getMessage());
                }
            }
        }).start();
    }
    
    public void getFavoriteQuestions(long userId, final RepositoryCallback<List<FavoriteQuestion>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<FavoriteQuestion> favoriteQuestions = favoriteQuestionDao.getFavoriteQuestionsByUserId(userId);
                    callback.onSuccess(favoriteQuestions);
                } catch (Exception e) {
                    callback.onFailure(e.getMessage());
                }
            }
        }).start();
    }
}
