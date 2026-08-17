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
 * 指标卡片组件：一行多个"大数字 + 标签"（token 数/耗时/成功率/数量等）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "执行统计",                    // 可选
 *   "metrics": [
 *     {"label":"Token","value":"1.2k"},
 *     {"label":"耗时","value":"3.2s","color":"accent"}
 *   ]
 * }
 * </pre>
 * color 可选：accent / success / warning / error / primary。
 */
public class MetricCardView implements ChatComponent {

    @Override
    public String getType() {
        return "metric_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("metrics") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String title = p.optString("title", "");
        JSONArray metrics = p.optJSONArray("metrics");

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

        if (metrics != null && metrics.length() > 0) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            for (int i = 0; i < metrics.length(); i++) {
                JSONObject m = metrics.optJSONObject(i);
                if (m == null) continue;

                LinearLayout col = new LinearLayout(context);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                String value = m.optString("value", "");
                TextView valueTv = new TextView(context);
                valueTv.setText(TextUtils.isEmpty(value) ? "-" : value);
                valueTv.setTextSize(20);
                valueTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                valueTv.setTextColor(resolveColor(context, m.optString("color", "")));
                col.addView(valueTv);

                String label = m.optString("label", "");
                if (!TextUtils.isEmpty(label)) {
                    TextView labelTv = new TextView(context);
                    labelTv.setText(label);
                    labelTv.setTextSize(10);
                    labelTv.setTextColor(ComponentColors.textSecondary(context));
                    col.addView(labelTv);
                }

                row.addView(col);
            }
            card.addView(row);
        }
        return card;
    }

    private int resolveColor(Context context, String color) {
        if (color == null) return ComponentColors.textPrimary(context);
        switch (color) {
            case "accent": return ComponentColors.accent(context);
            case "success": return ComponentColors.success(context);
            case "warning": return ComponentColors.warning(context);
            case "error": return ComponentColors.error(context);
            case "primary": return ComponentColors.textPrimary(context);
            case "secondary": return ComponentColors.textSecondary(context);
            default: return ComponentColors.textPrimary(context);
        }
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
