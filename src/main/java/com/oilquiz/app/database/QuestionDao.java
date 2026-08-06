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

    // ========== 收藏相关 ==========
    @Query("SELECT * FROM question WHERE favorite = 1")
    List<Question> getFavoriteQuestions();

    @Query("SELECT COUNT(*) FROM question WHERE favorite = 1")
    int getFavoriteCount();

    @Query("UPDATE question SET favorite = :favorite WHERE id = :id")
    void setFavorite(long id, boolean favorite);

    // ========== 分类统计 ==========
    @Query("SELECT COUNT(*) FROM question WHERE category IS NOT NULL AND category != '' GROUP BY category")
    List<Integer> getCategoryQuestionCounts();

    @Query("SELECT COUNT(*) FROM question WHERE category = :category")
    int getQuestionCountByCategory(String category);

    @Query("SELECT COUNT(*) FROM question WHERE questionType = :type")
    int getQuestionCountByType(String type);

    // ========== 标签相关 ==========
    @Query("SELECT * FROM question WHERE tags LIKE '%' || :tag || '%'")
    List<Question> getQuestionsByTag(String tag);

    @Query("SELECT DISTINCT tags FROM question WHERE tags IS NOT NULL AND tags != ''")
    List<String> getAllTags();

    // ========== 知识点相关 ==========
    @Query("SELECT DISTINCT knowledgePoint FROM question WHERE knowledgePoint IS NOT NULL AND knowledgePoint != ''")
    List<String> getAllKnowledgePoints();

    @Query("SELECT * FROM question WHERE knowledgePoint = :knowledgePoint")
    List<Question> getQuestionsByKnowledgePoint(String knowledgePoint);

    // ========== 状态过滤 ==========
    @Query("SELECT * FROM question WHERE status = 0 ORDER BY createdAt DESC")
    List<Question> getActiveQuestions();

    @Query("SELECT * FROM question WHERE status = 0 AND (:category IS NULL OR category = :category) AND (:type IS NULL OR questionType = :type) AND (:difficulty IS NULL OR difficulty = :difficulty) ORDER BY createdAt DESC")
    List<Question> getActiveQuestionsFiltered(String category, String type, Integer difficulty);

    // ========== 随机抽题 ==========
    @Query("SELECT * FROM question WHERE status = 0 AND category = :category ORDER BY RANDOM() LIMIT :limit")
    List<Question> getRandomQuestionsByCategory(String category, int limit);

    @Query("SELECT * FROM question WHERE status = 0 AND difficulty = :difficulty ORDER BY RANDOM() LIMIT :limit")
    List<Question> getRandomQuestionsByDifficulty(int difficulty, int limit);

    @Query("SELECT * FROM question WHERE status = 0 ORDER BY RANDOM() LIMIT :limit")
    List<Question> getRandomQuestions(int limit);

    // ========== 统计更新 ==========
    @Query("UPDATE question SET usageCount = usageCount + 1, lastUsedAt = :timestamp WHERE id = :id")
    void incrementUsageCount(long id, long timestamp);

    @Query("UPDATE question SET correctCount = correctCount + 1 WHERE id = :id")
    void incrementCorrectCount(long id);

    @Query("UPDATE question SET incorrectCount = incorrectCount + 1 WHERE id = :id")
    void incrementIncorrectCount(long id);

    // ========== 难度分布统计 ==========
    @Query("SELECT COUNT(*) FROM question WHERE status = 0 AND difficulty = :difficulty")
    int getActiveQuestionCountByDifficulty(int difficulty);

    // ========== 子分类 ==========
    @Query("SELECT DISTINCT subCategory FROM question WHERE subCategory IS NOT NULL AND subCategory != '' AND category = :category")
    List<String> getSubCategories(String category);
    
    // ========== v21 新增查询 ==========
    
    // 获取母题的所有子题
    @Query("SELECT * FROM question WHERE parentId = :parentId ORDER BY sortOrder ASC")
    List<Question> getSubQuestions(long parentId);
    
    // 获取有配图的题目
    @Query("SELECT * FROM question WHERE imageUri IS NOT NULL AND imageUri != ''")
    List<Question> getQuestionsWithImages();
    
    // 按排序顺序获取题目（组卷用）
    @Query("SELECT * FROM question WHERE status = 0 ORDER BY sortOrder ASC, createdAt DESC")
    List<Question> getActiveQuestionsSorted();
    
    // 按分类和排序获取
    @Query("SELECT * FROM question WHERE status = 0 AND category = :category ORDER BY sortOrder ASC, createdAt DESC")
    List<Question> getActiveQuestionsByCategorySorted(String category);
    
    // 搜索时同时搜索answerText字段
    @Query("SELECT * FROM question WHERE questionText LIKE '%' || :keyword || '%' OR answerText LIKE '%' || :keyword || '%'")
    List<Question> searchQuestionsIncludingAnswer(String keyword);
}
