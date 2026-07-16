package com.oilquiz.app.repository;

import android.content.Context;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.ScoreDao;
import com.oilquiz.app.database.WrongQuestionDao;
import com.oilquiz.app.database.NoteDao;
import com.oilquiz.app.model.Statistics;
import com.oilquiz.app.model.ScoreHistory;

import java.util.List;

public class StatisticsRepository {
    private ScoreDao scoreDao;
    private WrongQuestionDao wrongQuestionDao;
    private NoteDao noteDao;

    public StatisticsRepository(Context context) {
        AppDatabase db = AppDatabase.getDatabase(context);
        scoreDao = db.scoreDao();
        wrongQuestionDao = db.wrongQuestionDao();
        noteDao = db.noteDao();
    }

    public Statistics getStatistics(long userId) {
        // 获取所有分数历史
        List<ScoreHistory> scores = scoreDao.getScoresByUserId(userId);
        
        // 计算基本统计数据
        int totalQuestionsAnswered = 0;
        int correctAnswers = 0;
        int highestScore = 0;
        int totalScore = 0;
        int totalStudyTime = 0;
        
        for (ScoreHistory score : scores) {
            totalQuestionsAnswered += score.getTotalQuestions();
            correctAnswers += score.getCorrectCount();
            highestScore = Math.max(highestScore, score.getScore());
            totalScore += score.getScore();
            totalStudyTime += (int) (score.getEndTime() - score.getStartTime()) / 1000; // 转换为秒
        }
        
        int wrongAnswers = totalQuestionsAnswered - correctAnswers;
        double accuracyRate = totalQuestionsAnswered > 0 ? 
                (double) correctAnswers / totalQuestionsAnswered * 100 : 0;
        double averageScore = scores.size() > 0 ? 
                (double) totalScore / scores.size() : 0;
        
        // 获取最佳和最弱分类（这里需要根据实际情况实现）
        String bestCategory = "暂无数据";
        String weakestCategory = "暂无数据";
        
        // 获取总错题数
        int totalWrongQuestions = wrongQuestionDao.getWrongQuestionCountByUserId(userId);
        
        // 获取总笔记数
        int totalNotes = noteDao.getNoteCountByUserId(userId);
        
        return new Statistics(
                totalQuestionsAnswered,
                correctAnswers,
                wrongAnswers,
                accuracyRate,
                highestScore,
                averageScore,
                bestCategory,
                weakestCategory,
                totalStudyTime,
                totalWrongQuestions,
                totalNotes
        );
    }

    public List<ScoreHistory> getRecentScores(long userId, int limit) {
        return scoreDao.getRecentScores(userId, limit);
    }

    public float getAverageScore(long userId) {
        return scoreDao.getAverageScoreByUserId(userId);
    }

    public int getTotalWrongQuestions(long userId) {
        return wrongQuestionDao.getWrongQuestionCountByUserId(userId);
    }

    public int getTotalNotes(long userId) {
        return noteDao.getNoteCountByUserId(userId);
    }
}
