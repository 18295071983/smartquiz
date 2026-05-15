package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Transaction;

import com.oilquiz.app.model.Question;

import java.util.List;

@Dao
public interface QuestionDao {
    @Insert
    long insert(Question question);

    @Transaction
    @Insert
    void insertAll(List<Question> questions);

    @Update
    void update(Question question);

    @Query("DELETE FROM question WHERE id = :id")
    void deleteQuestion(long id);

    @Query("DELETE FROM question")
    void deleteAllQuestions();

    @Query("SELECT * FROM question WHERE id = :id")
    Question getQuestionById(long id);

    @Query("SELECT * FROM question WHERE category = :category")
    List<Question> getQuestionsByCategory(String category);

    @Query("SELECT * FROM question WHERE difficulty = :difficulty")
    List<Question> getQuestionsByDifficulty(int difficulty);

    @Query("SELECT COUNT(*) FROM question WHERE difficulty = 1")
    int getEasyQuestionCount();

    @Query("SELECT COUNT(*) FROM question WHERE difficulty = 2")
    int getMediumQuestionCount();

    @Query("SELECT COUNT(*) FROM question WHERE difficulty = 3")
    int getHardQuestionCount();

    @Query("SELECT COUNT(*) FROM question WHERE difficulty IS NULL OR difficulty = 0")
    int getNoDifficultyQuestionCount();

    @Query("SELECT * FROM question WHERE questionType = :type")
    List<Question> getQuestionsByType(String type);

    @Query("SELECT * FROM question WHERE questionText LIKE '%' || :keyword || '%'")
    List<Question> searchQuestions(String keyword);

    @Query("SELECT * FROM question LIMIT :limit OFFSET :offset")
    List<Question> getQuestionsByPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM question")
    int getQuestionCount();

    @Query("SELECT * FROM question")
    List<Question> getQuestions();

    @Query("SELECT DISTINCT category FROM question WHERE category IS NOT NULL AND category != ''")
    List<String> getAllCategories();

    @Query("SELECT DISTINCT questionType FROM question WHERE questionType IS NOT NULL AND questionType != ''")
    List<String> getAllQuestionTypes();

    @Query("SELECT * FROM question WHERE category = :category AND questionText LIKE '%' || :keyword || '%'")
    List<Question> searchQuestionsByCategory(String category, String keyword);

    @Query("SELECT * FROM question WHERE (:category IS NULL OR category = :category) AND (:type IS NULL OR questionType = :type) AND (:difficulty IS NULL OR difficulty = :difficulty) AND (questionText LIKE '%' || :keyword || '%' OR optionA LIKE '%' || :keyword || '%' OR optionB LIKE '%' || :keyword || '%' OR optionC LIKE '%' || :keyword || '%' OR optionD LIKE '%' || :keyword || '%' OR explanation LIKE '%' || :keyword || '%')")
    List<Question> searchQuestionsWithFilters(String keyword, String category, String type, Integer difficulty);
}
