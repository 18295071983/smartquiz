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
 * 信息卡片组件：标题 + 键值对列表。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "任务信息",
 *   "items": [{"label":"状态","value":"已完成"}, {"label":"耗时","value":"3.2s"}]
 * }
 * </pre>
 */
public class InfoCardView implements ChatComponent {

    @Override
    public String getType() {
        return "info_card";
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
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(context, 3), 0, dp(context, 3));

                TextView labelTv = new TextView(context);
                labelTv.setText(item.optString("label", ""));
                labelTv.setTextSize(13);
                labelTv.setTextColor(ComponentColors.textSecondary(context));
                LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f);
                labelTv.setLayoutParams(labelLp);
                labelTv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

                TextView valueTv = new TextView(context);
                valueTv.setText(item.optString("value", ""));
                valueTv.setTextSize(13);
                valueTv.setTextColor(ComponentColors.textPrimary(context));
                valueTv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.62f);
                valueTv.setLayoutParams(valueLp);

                row.addView(labelTv, labelLp);
                row.addView(valueTv, valueLp);
                // 行必须撑满卡片宽度，weight 分配才生效
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
