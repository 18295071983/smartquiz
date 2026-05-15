package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "log_entry")
public class LogEntry {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private Long userId;
    private String action;
    private String detail;
    private long timestamp;
    private String level;

    public LogEntry() {
    }

    @androidx.room.Ignore
    public LogEntry(Long userId, String action, String detail, long timestamp, String level) {
        this.userId = userId;
        this.action = action;
        this.detail = detail;
        this.timestamp = timestamp;
        this.level = level;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }
}
