package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.repository.ScoreRepository;

import java.util.List;

public class ScoreViewModel extends ViewModel {
    private ScoreRepository scoreRepository;

    public void init(Application application) {
        scoreRepository = new ScoreRepository(application);
    }

    public long addScore(ScoreHistory score) {
        return scoreRepository.addScore(score);
    }

    public void deleteScore(long id) {
        scoreRepository.deleteScore(id);
    }

    public void deleteScoresByUserId(long userId) {
        scoreRepository.deleteScoresByUserId(userId);
    }

    public List<ScoreHistory> getScoresByUserId(long userId) {
        return scoreRepository.getScoresByUserId(userId);
    }

    public List<ScoreHistory> getScoresByCategory(String category) {
        return scoreRepository.getScoresByCategory(category);
    }

    public List<ScoreHistory> getScoresByDifficulty(String difficulty) {
        return scoreRepository.getScoresByDifficulty(difficulty);
    }

    public float getAverageScoreByUserId(long userId) {
        return scoreRepository.getAverageScoreByUserId(userId);
    }

    public List<ScoreHistory> getRecentScores(long userId, int limit) {
        return scoreRepository.getRecentScores(userId, limit);
    }
}
