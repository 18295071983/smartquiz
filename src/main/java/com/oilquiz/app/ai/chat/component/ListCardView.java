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
 * 列表卡片组件：图标 + 标题 + 描述 的条目列表（搜索结果/文件列表/条目展示）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "搜索结果",
 *   "items": [
 *     {"icon":"🔍","title":"标题","description":"描述","value":"右侧值(可选)"},
 *     ...
 *   ]
 * }
 * </pre>
 */
public class ListCardView implements ChatComponent {

    @Override
    public String getType() {
        return "list_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("items") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
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
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(context, 4), 0, dp(context, 4));

                // 图标
                String icon = item.optString("icon", "");
                if (!TextUtils.isEmpty(icon)) {
                    TextView iconTv = new TextView(context);
                    iconTv.setText(icon);
                    iconTv.setTextSize(16);
                    iconTv.setPadding(0, 0, dp(context, 8), 0);
                    row.addView(iconTv);
                }

                // 中列：标题 + 描述
                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                String itemTitle = item.optString("title", "");
                if (!TextUtils.isEmpty(itemTitle)) {
                    TextView titleTv = new TextView(context);
                    titleTv.setText(itemTitle);
                    titleTv.setTextSize(13);
                    titleTv.setTextColor(ComponentColors.textPrimary(context));
                    textCol.addView(titleTv);
                }

                String description = item.optString("description", "");
                if (!TextUtils.isEmpty(description)) {
                    TextView descTv = new TextView(context);
                    descTv.setText(description);
                    descTv.setTextSize(11);
                    descTv.setTextColor(ComponentColors.textSecondary(context));
                    descTv.setMaxLines(2);
                    descTv.setEllipsize(TextUtils.TruncateAt.END);
                    textCol.addView(descTv);
                }

                row.addView(textCol);

                // 右侧值（可选）
                String value = item.optString("value", "");
                if (!TextUtils.isEmpty(value)) {
                    TextView valueTv = new TextView(context);
                    valueTv.setText(value);
                    valueTv.setTextSize(12);
                    valueTv.setTextColor(ComponentColors.accent(context));
                    valueTv.setPadding(dp(context, 8), 0, 0, 0);
                    row.addView(valueTv);
                }

                card.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                // 条目间分隔线
                if (i < items.length() - 1) {
                    View divider = new View(context);
                    divider.setBackgroundColor(ComponentColors.border(context));
                    card.addView(divider, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1));
                }
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
