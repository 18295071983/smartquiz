package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 进度卡片组件：进度条 + 百分比 + 描述（任务执行/学习进度反馈）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "学习进度",
 *   "progress": 68,          // 0-100
 *   "description": "已完成 68/100 题",
 *   "status": "进行中"       // 可选
 * }
 * </pre>
 */
public class ProgressCardView implements ChatComponent {

    @Override
    public String getType() {
        return "progress_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.has("progress");
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String title = p.optString("title", "");
        int progress = Math.max(0, Math.min(100, p.optInt("progress", 0)));
        String description = p.optString("description", "");
        String status = p.optString("status", "");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 标题行：标题 + 状态 + 百分比
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(TextUtils.isEmpty(title) ? "进度" : title);
        titleTv.setTextSize(13);
        titleTv.setTextColor(ComponentColors.textPrimary(context));
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleRow.addView(titleTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(status)) {
            TextView statusTv = new TextView(context);
            statusTv.setText(status);
            statusTv.setTextSize(10);
            statusTv.setTextColor(ComponentColors.textTertiary(context));
            titleRow.addView(statusTv);
        }

        TextView pctTv = new TextView(context);
        pctTv.setText(progress + "%");
        pctTv.setTextSize(16);
        pctTv.setTextColor(ComponentColors.accent(context));
        pctTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pctTv.setPadding(dp(context, 8), 0, 0, 0);
        titleRow.addView(pctTv);
        card.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 进度条
        ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(progress);
        progressBar.setPadding(0, dp(context, 4), 0, 0);
        card.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 8)));

        // 描述
        if (!TextUtils.isEmpty(description)) {
            TextView descTv = new TextView(context);
            descTv.setText(description);
            descTv.setTextSize(11);
            descTv.setTextColor(ComponentColors.textSecondary(context));
            descTv.setPadding(0, dp(context, 4), 0, 0);
            card.addView(descTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        return card;
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
