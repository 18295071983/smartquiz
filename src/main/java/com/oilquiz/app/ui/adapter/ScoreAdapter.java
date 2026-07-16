package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.ScoreHistory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class ScoreAdapter extends ArrayAdapter<ScoreHistory> {
    private Context context;
    private List<ScoreHistory> scoreHistoryList;
    private SimpleDateFormat dateFormat;

    public ScoreAdapter(Context context, List<ScoreHistory> scoreHistoryList) {
        super(context, 0, scoreHistoryList);
        this.context = context;
        this.scoreHistoryList = scoreHistoryList;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_score, parent, false);
            holder = new ViewHolder();
            holder.tvScoreDate = convertView.findViewById(R.id.tv_score_date);
            holder.tvScoreValue = convertView.findViewById(R.id.tv_score_value);
            holder.tvScoreDetails = convertView.findViewById(R.id.tv_score_details);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        ScoreHistory scoreHistory = scoreHistoryList.get(position);

        // 格式化日期
        String dateStr = dateFormat.format(new Date(scoreHistory.getEndTime()));
        holder.tvScoreDate.setText(dateStr);

        // 设置分数
        holder.tvScoreValue.setText(scoreHistory.getScore() + "分");

        // 设置详细信息
        String details = scoreHistory.getCategory() + " - " + scoreHistory.getDifficulty() + " - " + 
                scoreHistory.getCorrectCount() + "/" + scoreHistory.getTotalQuestions();
        holder.tvScoreDetails.setText(details);

        return convertView;
    }

    static class ViewHolder {
        TextView tvScoreDate;
        TextView tvScoreValue;
        TextView tvScoreDetails;
    }
}
