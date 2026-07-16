package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.oilquiz.app.model.ScoreHistory;

import java.util.List;

@Dao
public interface ScoreDao {
    @Insert
    long insert(ScoreHistory score);

    @Query("DELETE FROM score_history WHERE id = :id")
    void deleteScore(long id);

    @Query("DELETE FROM score_history WHERE userId = :userId")
    void deleteScoresByUserId(long userId);

    @Query("SELECT * FROM score_history WHERE userId = :userId")
    List<ScoreHistory> getScoresByUserId(long userId);

    @Query("SELECT * FROM score_history WHERE category = :category")
    List<ScoreHistory> getScoresByCategory(String category);

    @Query("SELECT * FROM score_history WHERE difficulty = :difficulty")
    List<ScoreHistory> getScoresByDifficulty(String difficulty);

    @Query("SELECT AVG(score) FROM score_history WHERE userId = :userId")
    float getAverageScoreByUserId(long userId);

    @Query("SELECT * FROM score_history WHERE userId = :userId ORDER BY endTime DESC LIMIT :limit")
    List<ScoreHistory> getRecentScores(long userId, int limit);
}
