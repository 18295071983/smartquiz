package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.model.StudyPlan;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StudyPlanAdapter extends BaseAdapter {

    private Context context;
    private List<StudyPlan> studyPlans;
    private OnStudyPlanClickListener listener;

    public interface OnStudyPlanClickListener {
        void onStudyPlanClick(StudyPlan plan);
        void onEditClick(StudyPlan plan);
        void onDeleteClick(StudyPlan plan);
        void onResetClick(StudyPlan plan);
    }

    public StudyPlanAdapter(Context context, List<StudyPlan> studyPlans, OnStudyPlanClickListener listener) {
        this.context = context;
        this.studyPlans = studyPlans != null ? studyPlans : new java.util.ArrayList<>();
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return studyPlans != null ? studyPlans.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        return (studyPlans != null && position >= 0 && position < studyPlans.size())
            ? studyPlans.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_study_plan, parent, false);
            holder = new ViewHolder();
            holder.tvPlanName = convertView.findViewById(R.id.tv_plan_name);
            holder.tvPlanDetails = convertView.findViewById(R.id.tv_plan_details);
            holder.tvPlanDate = convertView.findViewById(R.id.tv_plan_date);
            holder.btnEdit = convertView.findViewById(R.id.btn_edit);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            holder.btnReset = convertView.findViewById(R.id.btn_reset);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        StudyPlan plan = studyPlans.get(position);
        if (plan != null) {
            holder.tvPlanName.setText(plan.getPlanName());

            double progress = plan.getTargetQuestions() > 0
                ? (double) plan.getCompletedQuestions() / plan.getTargetQuestions() : 0;
            holder.tvPlanDetails.setText(String.format(Locale.getDefault(),
                "目标: %d题 | 已完成: %d题 | 进度: %.1f%%",
                plan.getTargetQuestions(), plan.getCompletedQuestions(),
                progress * 100));

            holder.tvPlanDate.setText(formatDateRange(plan.getStartDate(), plan.getEndDate()));

            holder.btnEdit.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEditClick(plan);
                }
            });

            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClick(plan);
                }
            });

            holder.btnReset.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onResetClick(plan);
                }
            });

            convertView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStudyPlanClick(plan);
                }
            });
        }

        return convertView;
    }

    private String formatDateRange(long startDate, long endDate) {
        String start = formatDate(startDate);
        String end = formatDate(endDate);
        if ("未设置".equals(start) && "未设置".equals(end)) {
            return "未设置日期";
        }
        if ("未设置".equals(end)) {
            return start;
        }
        return start + " - " + end;
    }

    private String formatDate(long timestamp) {
        if (timestamp <= 0) {
            return "未设置";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    static class ViewHolder {
        TextView tvPlanName;
        TextView tvPlanDetails;
        TextView tvPlanDate;
        MaterialButton btnEdit;
        MaterialButton btnDelete;
        MaterialButton btnReset;
    }

    public void updateData(List<StudyPlan> newList) {
        this.studyPlans = newList != null ? newList : new java.util.ArrayList<>();
        notifyDataSetChanged();
    }
}
