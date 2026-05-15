package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.oilquiz.app.model.WrongQuestion;

import java.util.List;

@Dao
public interface WrongQuestionDao {
    @Insert
    long insert(WrongQuestion wrongQuestion);

    @Update
    void update(WrongQuestion wrongQuestion);

    @Query("DELETE FROM wrong_question WHERE id = :id")
    void deleteWrongQuestion(long id);

    @Query("SELECT * FROM wrong_question WHERE id = :id")
    WrongQuestion getWrongQuestionById(long id);

    @Query("SELECT * FROM wrong_question WHERE userId = :userId")
    List<WrongQuestion> getWrongQuestionsByUserId(long userId);

    @Query("SELECT * FROM wrong_question WHERE questionId = :questionId")
    List<WrongQuestion> getWrongQuestionsByQuestionId(long questionId);

    @Query("SELECT * FROM wrong_question WHERE userId = :userId AND questionId = :questionId")
    WrongQuestion getWrongQuestionByUserIdAndQuestionId(long userId, long questionId);

    @Query("SELECT * FROM wrong_question")
    List<WrongQuestion> getWrongQuestions();

    @Query("SELECT COUNT(*) FROM wrong_question WHERE userId = :userId")
    int getWrongQuestionCountByUserId(long userId);

    @Query("DELETE FROM wrong_question")
    void deleteAllWrongQuestions();
}
