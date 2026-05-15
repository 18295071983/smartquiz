package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "chat_history")
public class ChatHistory {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private long userId;
    private String message;
    private String sender;
    private long timestamp;

    public ChatHistory() {
    }

    @androidx.room.Ignore
    public ChatHistory(long userId, String message, String sender, long timestamp) {
        this.userId = userId;
        this.message = message;
        this.sender = sender;
        this.timestamp = timestamp;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
