package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.model.WrongQuestion;
import com.oilquiz.app.repository.WrongQuestionRepository;

import java.util.List;

public class WrongQuestionViewModel extends ViewModel {
    private WrongQuestionRepository wrongQuestionRepository;

    public void init(Application application) {
        wrongQuestionRepository = new WrongQuestionRepository(application);
    }

    public void addWrongQuestion(WrongQuestion wrongQuestion, com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<Long> callback) {
        wrongQuestionRepository.addWrongQuestion(wrongQuestion, callback);
    }

    public void updateWrongQuestion(WrongQuestion wrongQuestion, com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<Void> callback) {
        wrongQuestionRepository.updateWrongQuestion(wrongQuestion, callback);
    }

    public void deleteWrongQuestion(long id, com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<Void> callback) {
        wrongQuestionRepository.deleteWrongQuestion(id, callback);
    }

    public void getWrongQuestionById(long id, com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<WrongQuestion> callback) {
        wrongQuestionRepository.getWrongQuestionById(id, callback);
    }

    public void getWrongQuestionsByUserId(long userId, com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<List<WrongQuestion>> callback) {
        wrongQuestionRepository.getWrongQuestionsByUserId(userId, callback);
    }

    public void getWrongQuestionsByQuestionId(long questionId, com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<List<WrongQuestion>> callback) {
        wrongQuestionRepository.getWrongQuestionsByQuestionId(questionId, callback);
    }

    public void getWrongQuestionByUserIdAndQuestionId(long userId, long questionId, com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<WrongQuestion> callback) {
        wrongQuestionRepository.getWrongQuestionByUserIdAndQuestionId(userId, questionId, callback);
    }

    public void getWrongQuestions(com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<List<WrongQuestion>> callback) {
        wrongQuestionRepository.getWrongQuestions(callback);
    }

    public void deleteAllWrongQuestions(com.oilquiz.app.repository.WrongQuestionRepository.RepositoryCallback<Void> callback) {
        wrongQuestionRepository.deleteAllWrongQuestions(callback);
    }
}
