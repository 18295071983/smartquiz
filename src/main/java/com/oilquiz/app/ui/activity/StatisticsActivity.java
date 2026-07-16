package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Statistics;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.ui.adapter.ScoreAdapter;
import com.oilquiz.app.viewmodel.StatisticsViewModel;

import java.util.List;

public class StatisticsActivity extends AppCompatActivity {
    private StatisticsViewModel statisticsViewModel;
    private TextView tvTotalQuestions;
    private TextView tvCorrectAnswers;
    private TextView tvWrongAnswers;
    private TextView tvAccuracyRate;
    private TextView tvHighestScore;
    private TextView tvAverageScore;
    private TextView tvTotalStudyTime;
    private TextView tvTotalWrongQuestions;
    private TextView tvTotalNotes;
    private ListView lvRecentScores;
    private ScoreAdapter scoreAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_statistics);

        // 初始化视图
        initViews();

        // 初始化ViewModel
        statisticsViewModel = new ViewModelProvider(this).get(StatisticsViewModel.class);

        // 加载统计数据（这里使用默认用户ID 1，实际应用中应该从登录状态获取）
        long userId = 1;
        loadStatistics(userId);
        loadRecentScores(userId);
    }

    private void initViews() {
        tvTotalQuestions = findViewById(R.id.tv_total_questions);
        tvCorrectAnswers = findViewById(R.id.tv_correct_answers);
        tvWrongAnswers = findViewById(R.id.tv_wrong_answers);
        tvAccuracyRate = findViewById(R.id.tv_accuracy_rate);
        tvHighestScore = findViewById(R.id.tv_highest_score);
        tvAverageScore = findViewById(R.id.tv_average_score);
        tvTotalStudyTime = findViewById(R.id.tv_total_study_time);
        tvTotalWrongQuestions = findViewById(R.id.tv_total_wrong_questions);
        tvTotalNotes = findViewById(R.id.tv_total_notes);
        lvRecentScores = findViewById(R.id.lv_recent_scores);
    }

    private void loadStatistics(long userId) {
        statisticsViewModel.loadStatistics(userId);
        statisticsViewModel.getStatistics().observe(this, statistics -> {
            if (statistics != null) {
                updateStatisticsUI(statistics);
            }
        });
    }

    private void loadRecentScores(long userId) {
        statisticsViewModel.loadRecentScores(userId, 10); // 加载最近10条记录
        statisticsViewModel.getRecentScores().observe(this, scoreHistories -> {
            if (scoreHistories != null) {
                updateRecentScoresUI(scoreHistories);
            }
        });
    }

    private void updateStatisticsUI(Statistics statistics) {
        tvTotalQuestions.setText(String.valueOf(statistics.getTotalQuestionsAnswered()));
        tvCorrectAnswers.setText(String.valueOf(statistics.getCorrectAnswers()));
        tvWrongAnswers.setText(String.valueOf(statistics.getWrongAnswers()));
        tvAccuracyRate.setText(String.format("%.1f%%", statistics.getAccuracyRate()));
        tvHighestScore.setText(String.valueOf(statistics.getHighestScore()));
        tvAverageScore.setText(String.format("%.1f", statistics.getAverageScore()));
        
        // 格式化学习时间
        int totalSeconds = statistics.getTotalStudyTime();
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 0) {
            tvTotalStudyTime.setText(minutes + " " + getString(R.string.minutes) + " " + seconds + " " + getString(R.string.seconds));
        } else {
            tvTotalStudyTime.setText(seconds + " " + getString(R.string.seconds));
        }
        
        tvTotalWrongQuestions.setText(String.valueOf(statistics.getTotalWrongQuestions()));
        tvTotalNotes.setText(String.valueOf(statistics.getTotalNotes()));
    }

    private void updateRecentScoresUI(List<ScoreHistory> scoreHistories) {
        scoreAdapter = new ScoreAdapter(this, scoreHistories);
        lvRecentScores.setAdapter(scoreAdapter);
    }
}
