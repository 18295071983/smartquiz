package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "study_plan")
public class StudyPlan {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private String planName;
    private int targetQuestions;
    private int completedQuestions;
    private long startDate;
    private long endDate;
    private long userId;
    private String status;

    public StudyPlan() {
    }

    @androidx.room.Ignore
    public StudyPlan(String planName, int targetQuestions, int completedQuestions, 
                    long startDate, long endDate, long userId, String status) {
        this.planName = planName;
        this.targetQuestions = targetQuestions;
        this.completedQuestions = completedQuestions;
        this.startDate = startDate;
        this.endDate = endDate;
        this.userId = userId;
        this.status = status;
    }

    @androidx.room.Ignore
    public StudyPlan(long id, String planName, int targetQuestions, int completedQuestions, 
                    long startDate, long endDate, long userId, String status) {
        this.id = id;
        this.planName = planName;
        this.targetQuestions = targetQuestions;
        this.completedQuestions = completedQuestions;
        this.startDate = startDate;
        this.endDate = endDate;
        this.userId = userId;
        this.status = status;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public int getTargetQuestions() {
        return targetQuestions;
    }

    public void setTargetQuestions(int targetQuestions) {
        this.targetQuestions = targetQuestions;
    }

    public int getCompletedQuestions() {
        return completedQuestions;
    }

    public void setCompletedQuestions(int completedQuestions) {
        this.completedQuestions = completedQuestions;
    }

    public long getStartDate() {
        return startDate;
    }

    public void setStartDate(long startDate) {
        this.startDate = startDate;
    }

    public long getEndDate() {
        return endDate;
    }

    public void setEndDate(long endDate) {
        this.endDate = endDate;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getProgress() {
        return targetQuestions > 0 ? (double) completedQuestions / targetQuestions : 0;
    }

    @Override
    public String toString() {
        return planName;
    }
}
