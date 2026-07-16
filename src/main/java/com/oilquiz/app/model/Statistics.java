package com.oilquiz.app.model;

public class Statistics {
    private int totalQuestionsAnswered;
    private int correctAnswers;
    private int wrongAnswers;
    private double accuracyRate;
    private int highestScore;
    private double averageScore;
    private String bestCategory;
    private String weakestCategory;
    private int totalStudyTime;
    private int totalWrongQuestions;
    private int totalNotes;

    public Statistics() {
    }

    public Statistics(int totalQuestionsAnswered, int correctAnswers, int wrongAnswers, 
                    double accuracyRate, int highestScore, double averageScore, 
                    String bestCategory, String weakestCategory, int totalStudyTime, 
                    int totalWrongQuestions, int totalNotes) {
        this.totalQuestionsAnswered = totalQuestionsAnswered;
        this.correctAnswers = correctAnswers;
        this.wrongAnswers = wrongAnswers;
        this.accuracyRate = accuracyRate;
        this.highestScore = highestScore;
        this.averageScore = averageScore;
        this.bestCategory = bestCategory;
        this.weakestCategory = weakestCategory;
        this.totalStudyTime = totalStudyTime;
        this.totalWrongQuestions = totalWrongQuestions;
        this.totalNotes = totalNotes;
    }

    public int getTotalQuestionsAnswered() {
        return totalQuestionsAnswered;
    }

    public void setTotalQuestionsAnswered(int totalQuestionsAnswered) {
        this.totalQuestionsAnswered = totalQuestionsAnswered;
    }

    public int getCorrectAnswers() {
        return correctAnswers;
    }

    public void setCorrectAnswers(int correctAnswers) {
        this.correctAnswers = correctAnswers;
    }

    public int getWrongAnswers() {
        return wrongAnswers;
    }

    public void setWrongAnswers(int wrongAnswers) {
        this.wrongAnswers = wrongAnswers;
    }

    public double getAccuracyRate() {
        return accuracyRate;
    }

    public void setAccuracyRate(double accuracyRate) {
        this.accuracyRate = accuracyRate;
    }

    public int getHighestScore() {
        return highestScore;
    }

    public void setHighestScore(int highestScore) {
        this.highestScore = highestScore;
    }

    public double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(double averageScore) {
        this.averageScore = averageScore;
    }

    public String getBestCategory() {
        return bestCategory;
    }

    public void setBestCategory(String bestCategory) {
        this.bestCategory = bestCategory;
    }

    public String getWeakestCategory() {
        return weakestCategory;
    }

    public void setWeakestCategory(String weakestCategory) {
        this.weakestCategory = weakestCategory;
    }

    public int getTotalStudyTime() {
        return totalStudyTime;
    }

    public void setTotalStudyTime(int totalStudyTime) {
        this.totalStudyTime = totalStudyTime;
    }

    public int getTotalWrongQuestions() {
        return totalWrongQuestions;
    }

    public void setTotalWrongQuestions(int totalWrongQuestions) {
        this.totalWrongQuestions = totalWrongQuestions;
    }

    public int getTotalNotes() {
        return totalNotes;
    }

    public void setTotalNotes(int totalNotes) {
        this.totalNotes = totalNotes;
    }
}
