package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.oilquiz.app.model.FavoriteQuestion;

import java.util.List;

@Dao
public interface FavoriteQuestionDao {
    @Insert
    long insert(FavoriteQuestion favoriteQuestion);

    @Query("DELETE FROM favorite_question WHERE id = :id")
    void deleteFavoriteQuestion(long id);

    @Query("DELETE FROM favorite_question WHERE userId = :userId AND questionId = :questionId")
    void deleteFavoriteQuestionByUserIdAndQuestionId(long userId, long questionId);

    @Query("SELECT * FROM favorite_question WHERE id = :id")
    FavoriteQuestion getFavoriteQuestionById(long id);

    @Query("SELECT * FROM favorite_question WHERE userId = :userId")
    List<FavoriteQuestion> getFavoriteQuestionsByUserId(long userId);

    @Query("SELECT * FROM favorite_question WHERE questionId = :questionId")
    List<FavoriteQuestion> getFavoriteQuestionsByQuestionId(long questionId);

    @Query("SELECT * FROM favorite_question WHERE userId = :userId AND questionId = :questionId")
    FavoriteQuestion getFavoriteQuestionByUserIdAndQuestionId(long userId, long questionId);
}
