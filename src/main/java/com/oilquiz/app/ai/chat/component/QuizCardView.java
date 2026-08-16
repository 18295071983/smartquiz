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
 * 题目卡片组件：题干 + 选项 + 答案/解析（答题宝核心场景）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "type": "single" | "multiple" | "judge",
 *   "question": "题干",
 *   "options": ["A. 选项1", "B. 选项2"],
 *   "answer": "A",
 *   "analysis": "解析文本（可选）"
 * }
 * </pre>
 */
public class QuizCardView implements ChatComponent {

    @Override
    public String getType() {
        return "quiz_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !TextUtils.isEmpty(data.props.optString("question", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String question = p.optString("question", "");
        String answer = p.optString("answer", "");
        String analysis = p.optString("analysis", "");
        JSONArray options = p.optJSONArray("options");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 题型标签
        String qType = p.optString("type", "");
        String typeLabel = "";
        if ("multiple".equals(qType)) typeLabel = "【多选】";
        else if ("judge".equals(qType)) typeLabel = "【判断】";
        else typeLabel = "【单选】";

        // 题干
        TextView questionTv = new TextView(context);
        questionTv.setText(typeLabel + question);
        questionTv.setTextSize(14);
        questionTv.setTextColor(ComponentColors.textPrimary(context));
        questionTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        questionTv.setLineSpacing(0, 1.25f);
        questionTv.setPadding(0, 0, 0, dp(context, 6));
        card.addView(questionTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 选项
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                String option = options.optString(i, "");
                if (option.isEmpty()) continue;
                TextView optTv = new TextView(context);
                optTv.setText(option);
                optTv.setTextSize(13);
                optTv.setTextColor(ComponentColors.textPrimary(context));
                optTv.setPadding(dp(context, 4), dp(context, 3), dp(context, 4), dp(context, 3));
                card.addView(optTv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        // 答案
        if (!TextUtils.isEmpty(answer)) {
            TextView answerTv = new TextView(context);
            answerTv.setText("✓ 答案：" + answer);
            answerTv.setTextSize(13);
            answerTv.setTextColor(ComponentColors.accent(context));
            answerTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            answerTv.setPadding(0, dp(context, 4), 0, 0);
            card.addView(answerTv);
        }

        // 解析
        if (!TextUtils.isEmpty(analysis)) {
            TextView analysisTv = new TextView(context);
            analysisTv.setText("💡 " + analysis);
            analysisTv.setTextSize(12);
            analysisTv.setTextColor(ComponentColors.textSecondary(context));
            analysisTv.setLineSpacing(0, 1.2f);
            analysisTv.setPadding(0, dp(context, 4), 0, 0);
            card.addView(analysisTv, new LinearLayout.LayoutParams(
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
