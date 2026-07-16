package com.oilquiz.app.model;

import java.io.Serializable;

public class AgentTask implements Serializable {
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_IN_PROGRESS = 1;
    public static final int STATUS_COMPLETED = 2;

    private final String title;
    private final String description;
    private int status;
    private final long createdAt;
    private long completedAt;

    public AgentTask(String title, String description, int status, long createdAt) {
        this.title = title;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
        if (status == STATUS_COMPLETED) {
            completedAt = System.currentTimeMillis();
        }
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public String getStatusText() {
        switch (status) {
            case STATUS_PENDING:
                return "待处理";
            case STATUS_IN_PROGRESS:
                return "处理中";
            case STATUS_COMPLETED:
                return "已完成";
            default:
                return "未知";
        }
    }
}
