package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "favorite_question")
public class FavoriteQuestion {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private long questionId;
    private long userId;
    private long favoriteTime;

    public FavoriteQuestion() {
    }

    @androidx.room.Ignore
    public FavoriteQuestion(long questionId, long userId, long favoriteTime) {
        this.questionId = questionId;
        this.userId = userId;
        this.favoriteTime = favoriteTime;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(long questionId) {
        this.questionId = questionId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getFavoriteTime() {
        return favoriteTime;
    }

    public void setFavoriteTime(long favoriteTime) {
        this.favoriteTime = favoriteTime;
    }
}
