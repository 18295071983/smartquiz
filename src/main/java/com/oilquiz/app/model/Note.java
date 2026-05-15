package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "note")
public class Note {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private String title;
    private String content;
    private long createTime;
    private long updateTime;
    private long userId;
    private Long relatedQuestionId;

    public Note() {
    }

    @androidx.room.Ignore
    public Note(String title, String content, long createTime, long updateTime, 
               long userId, Long relatedQuestionId) {
        this.title = title;
        this.content = content;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.userId = userId;
        this.relatedQuestionId = relatedQuestionId;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public Long getRelatedQuestionId() {
        return relatedQuestionId;
    }

    public void setRelatedQuestionId(Long relatedQuestionId) {
        this.relatedQuestionId = relatedQuestionId;
    }

    public long getCreatedAt() {
        return createTime;
    }
} 
