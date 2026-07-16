package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "wrong_question")
public class WrongQuestion {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private long questionId;
    private long userId;
    private int wrongCount;
    private long lastWrongTime;
    private String userAnswer;
    
    @androidx.room.Ignore
    private String questionText;
    
    @androidx.room.Ignore
    private String correctAnswer;
    
    @androidx.room.Ignore
    private String category;
    
    @androidx.room.Ignore
    private String questionType;
    
    @androidx.room.Ignore
    private String explanation;
    
    @androidx.room.Ignore
    private String optionA;
    
    @androidx.room.Ignore
    private String optionB;
    
    @androidx.room.Ignore
    private String optionC;
    
    @androidx.room.Ignore
    private String optionD;

    public WrongQuestion() {
    }

    @androidx.room.Ignore
    public WrongQuestion(long questionId, long userId, int wrongCount, long lastWrongTime, String userAnswer) {
        this.questionId = questionId;
        this.userId = userId;
        this.wrongCount = wrongCount;
        this.lastWrongTime = lastWrongTime;
        this.userAnswer = userAnswer;
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

    public int getWrongCount() {
        return wrongCount;
    }

    public void setWrongCount(int wrongCount) {
        this.wrongCount = wrongCount;
    }

    public long getLastWrongTime() {
        return lastWrongTime;
    }

    public void setLastWrongTime(long lastWrongTime) {
        this.lastWrongTime = lastWrongTime;
    }

    public String getUserAnswer() {
        return userAnswer;
    }

    public void setUserAnswer(String userAnswer) {
        this.userAnswer = userAnswer;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getOptionA() {
        return optionA;
    }

    public void setOptionA(String optionA) {
        this.optionA = optionA;
    }

    public String getOptionB() {
        return optionB;
    }

    public void setOptionB(String optionB) {
        this.optionB = optionB;
    }

    public String getOptionC() {
        return optionC;
    }

    public void setOptionC(String optionC) {
        this.optionC = optionC;
    }

    public String getOptionD() {
        return optionD;
    }

    public void setOptionD(String optionD) {
        this.optionD = optionD;
    }
}
