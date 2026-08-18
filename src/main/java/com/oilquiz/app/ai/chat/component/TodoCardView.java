package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 待办清单卡片组件：任务列表带完成状态（done/todo）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "今日计划",
 *   "items": [
 *     {"text":"完成报告","done":true},
 *     {"text":"回复邮件","done":false}
 *   ]
 * }
 * </pre>
 */
public class TodoCardView implements ChatComponent {

    @Override
    public String getType() {
        return "todo_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("items") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String title = p.optString("title", "");
        JSONArray items = p.optJSONArray("items");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(13);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        if (items != null) {
            int doneCount = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                boolean done = item.optBoolean("done", false);
                if (done) doneCount++;

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(context, 4), 0, dp(context, 4));

                TextView checkTv = new TextView(context);
                checkTv.setText(done ? "✅" : "⬜");
                checkTv.setTextSize(13);
                checkTv.setPadding(0, 0, dp(context, 8), 0);
                row.addView(checkTv);

                String text = item.optString("text", "");
                TextView textTv = new TextView(context);
                textTv.setText(TextUtils.isEmpty(text) ? "任务" : text);
                textTv.setTextSize(13);
                textTv.setTextColor(done
                        ? ComponentColors.textTertiary(context)
                        : ComponentColors.textPrimary(context));
                if (done) {
                    textTv.setPaintFlags(textTv.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                }
                row.addView(textTv, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                card.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            // 底部进度摘要
            if (items.length() > 0) {
                TextView summaryTv = new TextView(context);
                summaryTv.setText("完成 " + doneCount + " / " + items.length());
                summaryTv.setTextSize(10);
                summaryTv.setTextColor(ComponentColors.textTertiary(context));
                summaryTv.setPadding(0, dp(context, 4), 0, 0);
                card.addView(summaryTv);
            }
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
