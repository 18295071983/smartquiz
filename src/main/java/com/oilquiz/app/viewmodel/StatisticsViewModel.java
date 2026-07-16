package com.oilquiz.app.viewmodel;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.oilquiz.app.model.Statistics;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.repository.StatisticsRepository;

import java.util.List;

public class StatisticsViewModel extends AndroidViewModel {
    private StatisticsRepository statisticsRepository;
    private MutableLiveData<Statistics> statisticsLiveData;
    private MutableLiveData<List<ScoreHistory>> recentScoresLiveData;

    public StatisticsViewModel(Application application) {
        super(application);
        statisticsRepository = new StatisticsRepository(application);
        statisticsLiveData = new MutableLiveData<>();
        recentScoresLiveData = new MutableLiveData<>();
    }

    public void loadStatistics(long userId) {
        new Thread(() -> {
            Statistics statistics = statisticsRepository.getStatistics(userId);
            statisticsLiveData.postValue(statistics);
        }).start();
    }

    public void loadRecentScores(long userId, int limit) {
        new Thread(() -> {
            List<ScoreHistory> recentScores = statisticsRepository.getRecentScores(userId, limit);
            recentScoresLiveData.postValue(recentScores);
        }).start();
    }

    public LiveData<Statistics> getStatistics() {
        return statisticsLiveData;
    }

    public LiveData<List<ScoreHistory>> getRecentScores() {
        return recentScoresLiveData;
    }

    public float getAverageScore(long userId) {
        return statisticsRepository.getAverageScore(userId);
    }

    public int getTotalWrongQuestions(long userId) {
        return statisticsRepository.getTotalWrongQuestions(userId);
    }

    public int getTotalNotes(long userId) {
        return statisticsRepository.getTotalNotes(userId);
    }
}
