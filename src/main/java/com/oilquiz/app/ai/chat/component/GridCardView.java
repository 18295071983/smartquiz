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
 * 宫格卡片组件：图标网格（应用列表/分类/快捷入口），支持点击动作提示。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "已安装应用",
 *   "columns": 3,                        // 可选，默认 3
 *   "items": [
 *     {"icon":"📱","label":"微信","value":"com.tencent.mm"},
 *     ...
 *   ]
 * }
 * </pre>
 */
public class GridCardView implements ChatComponent {

    @Override
    public String getType() {
        return "grid_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("items") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String title = p.optString("title", "");
        int columns = Math.max(2, Math.min(6, p.optInt("columns", 3)));
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

        if (items != null && items.length() > 0) {
            LinearLayout currentRow = null;
            for (int i = 0; i < items.length(); i++) {
                if (i % columns == 0) {
                    currentRow = new LinearLayout(context);
                    currentRow.setOrientation(LinearLayout.HORIZONTAL);
                    card.addView(currentRow, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                JSONObject item = items.optJSONObject(i);
                if (item == null || currentRow == null) continue;

                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(0, dp(context, 4), 0, dp(context, 4));
                cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                String icon = item.optString("icon", "•");
                TextView iconTv = new TextView(context);
                iconTv.setText(icon);
                iconTv.setTextSize(22);
                iconTv.setGravity(Gravity.CENTER);
                cell.addView(iconTv);

                String label = item.optString("label", "");
                if (!TextUtils.isEmpty(label)) {
                    TextView labelTv = new TextView(context);
                    labelTv.setText(label);
                    labelTv.setTextSize(10);
                    labelTv.setTextColor(ComponentColors.textSecondary(context));
                    labelTv.setGravity(Gravity.CENTER);
                    labelTv.setMaxLines(1);
                    labelTv.setEllipsize(TextUtils.TruncateAt.END);
                    cell.addView(labelTv);
                }

                currentRow.addView(cell);
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
