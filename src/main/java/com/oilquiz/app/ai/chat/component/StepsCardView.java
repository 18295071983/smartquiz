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
 * 步骤流程卡片组件：多步骤状态展示（计划/流程/检查清单）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "执行流程",
 *   "steps": [
 *     {"title":"解析参数","description":"...","status":"done"},   // done/current/todo/failed
 *     ...
 *   ]
 * }
 * </pre>
 */
public class StepsCardView implements ChatComponent {

    @Override
    public String getType() {
        return "steps_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("steps") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String title = p.optString("title", "");
        JSONArray steps = p.optJSONArray("steps");

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

        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                String status = step.optString("status", "todo");

                // 每步一行：状态指示 + 标题 + 描述
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, dp(context, 3), 0, dp(context, 3));

                String indicator;
                int indicatorColor;
                switch (status) {
                    case "done":
                        indicator = "✅";
                        indicatorColor = ComponentColors.success(context);
                        break;
                    case "current":
                        indicator = "⏳";
                        indicatorColor = ComponentColors.accent(context);
                        break;
                    case "failed":
                        indicator = "❌";
                        indicatorColor = ComponentColors.error(context);
                        break;
                    default:
                        indicator = "○";
                        indicatorColor = ComponentColors.textTertiary(context);
                        break;
                }

                TextView indicatorTv = new TextView(context);
                indicatorTv.setText(indicator);
                indicatorTv.setTextSize(13);
                indicatorTv.setTextColor(indicatorColor);
                indicatorTv.setPadding(0, 0, dp(context, 8), 0);
                row.addView(indicatorTv);

                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                String stepTitle = step.optString("title", "步骤 " + (i + 1));
                TextView stepTitleTv = new TextView(context);
                stepTitleTv.setText(stepTitle);
                stepTitleTv.setTextSize(13);
                stepTitleTv.setTextColor(ComponentColors.textPrimary(context));
                if ("todo".equals(status)) {
                    stepTitleTv.setTextColor(ComponentColors.textSecondary(context));
                }
                textCol.addView(stepTitleTv);

                String description = step.optString("description", "");
                if (!TextUtils.isEmpty(description)) {
                    TextView descTv = new TextView(context);
                    descTv.setText(description);
                    descTv.setTextSize(11);
                    descTv.setTextColor(ComponentColors.textTertiary(context));
                    textCol.addView(descTv);
                }

                row.addView(textCol);
                card.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
