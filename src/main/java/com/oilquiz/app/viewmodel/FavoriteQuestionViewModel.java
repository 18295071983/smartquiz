package com.oilquiz.app.viewmodel;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.model.FavoriteQuestion;
import com.oilquiz.app.repository.FavoriteQuestionRepository;

import java.util.List;

public class FavoriteQuestionViewModel extends ViewModel {
    private FavoriteQuestionRepository favoriteQuestionRepository;

    public void init(Context context) {
        favoriteQuestionRepository = new FavoriteQuestionRepository(context);
    }

    public long addFavoriteQuestion(FavoriteQuestion favoriteQuestion) {
        return favoriteQuestionRepository.addFavoriteQuestion(favoriteQuestion);
    }

    public void deleteFavoriteQuestion(long id) {
        favoriteQuestionRepository.deleteFavoriteQuestion(id);
    }

    public void deleteFavoriteQuestionByUserIdAndQuestionId(long userId, long questionId) {
        favoriteQuestionRepository.deleteFavoriteQuestionByUserIdAndQuestionId(userId, questionId);
    }

    public List<FavoriteQuestion> getFavoriteQuestionsByUserId(long userId) {
        return favoriteQuestionRepository.getFavoriteQuestionsByUserId(userId);
    }

    public List<FavoriteQuestion> getFavoriteQuestionsByQuestionId(long questionId) {
        return favoriteQuestionRepository.getFavoriteQuestionsByQuestionId(questionId);
    }

    public FavoriteQuestion getFavoriteQuestionByUserIdAndQuestionId(long userId, long questionId) {
        return favoriteQuestionRepository.getFavoriteQuestionByUserIdAndQuestionId(userId, questionId);
    }
}