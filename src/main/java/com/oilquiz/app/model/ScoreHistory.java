package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "score_history")
public class ScoreHistory {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private int correctCount;
    private int totalQuestions;
    private int score;
    private long startTime;
    private long endTime;
    private String category;
    private String difficulty;
    private String quizType;
    private long userId;

    public ScoreHistory() {
    }

    @androidx.room.Ignore
    public ScoreHistory(int correctCount, int totalQuestions, int score, long startTime, 
                       long endTime, String category, String difficulty, String quizType, long userId) {
        this.correctCount = correctCount;
        this.totalQuestions = totalQuestions;
        this.score = score;
        this.startTime = startTime;
        this.endTime = endTime;
        this.category = category;
        this.difficulty = difficulty;
        this.quizType = quizType;
        this.userId = userId;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getCorrectCount() {
        return correctCount;
    }

    public void setCorrectCount(int correctCount) {
        this.correctCount = correctCount;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(int totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getQuizType() {
        return quizType;
    }

    public void setQuizType(String quizType) {
        this.quizType = quizType;
    }
}
