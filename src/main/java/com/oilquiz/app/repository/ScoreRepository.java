package com.oilquiz.app.repository;

import android.app.Application;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.ScoreDao;
import com.oilquiz.app.model.ScoreHistory;

import java.util.List;

public class ScoreRepository {
    private ScoreDao scoreDao;

    public ScoreRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        scoreDao = db.scoreDao();
    }

    public long addScore(ScoreHistory score) {
        return scoreDao.insert(score);
    }

    public void deleteScore(long id) {
        scoreDao.deleteScore(id);
    }

    public void deleteScoresByUserId(long userId) {
        scoreDao.deleteScoresByUserId(userId);
    }

    public List<ScoreHistory> getScoresByUserId(long userId) {
        return scoreDao.getScoresByUserId(userId);
    }

    public List<ScoreHistory> getScoresByCategory(String category) {
        return scoreDao.getScoresByCategory(category);
    }

    public List<ScoreHistory> getScoresByDifficulty(String difficulty) {
        return scoreDao.getScoresByDifficulty(difficulty);
    }

    public float getAverageScoreByUserId(long userId) {
        return scoreDao.getAverageScoreByUserId(userId);
    }

    public List<ScoreHistory> getRecentScores(long userId, int limit) {
        return scoreDao.getRecentScores(userId, limit);
    }
}
