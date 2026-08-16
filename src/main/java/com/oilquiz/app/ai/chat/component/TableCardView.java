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
 * 结构化表格卡片组件：表头 + 数据行，列宽均分、边框网格。
 * 比 Markdown 表格更适合结构化数据（列对齐可控）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "数据表",
 *   "headers": ["城市","气温","天气"],
 *   "rows": [["北京","26℃","晴"], ["上海","29℃","多云"]],
 *   "align": "left" | "center"
 * }
 * </pre>
 */
public class TableCardView implements ChatComponent {

    @Override
    public String getType() {
        return "table_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && data.props.optJSONArray("headers") != null
                && data.props.optJSONArray("rows") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String title = p.optString("title", "");
        String align = p.optString("align", "left");
        JSONArray headers = p.optJSONArray("headers");
        JSONArray rows = p.optJSONArray("rows");
        int colCount = headers != null ? headers.length() : 0;
        if (colCount <= 0) return null;

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        card.setBackground(cardBackground(context));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setPadding(dp(context, 4), 0, 0, dp(context, 6));
            card.addView(titleTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // 表头行
        card.addView(buildRow(context, headers, true, align));

        // 数据行
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null) continue;
                card.addView(buildRow(context, row, false, align));
            }
        }

        return card;
    }

    /** 构建一行（表头高亮） */
    private View buildRow(Context context, JSONArray cells, boolean header, String align) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (header) {
            row.setBackgroundColor(0x1A4C8DFF);
        } else {
            row.setBackgroundColor(ComponentColors.background(context));
        }
        int gravity = "center".equals(align) ? Gravity.CENTER : Gravity.START;

        for (int i = 0; i < cells.length(); i++) {
            String text = cells.optString(i, "");
            TextView cell = new TextView(context);
            cell.setText(text);
            cell.setTextSize(header ? 12 : 12);
            cell.setTextColor(header ? 0xFF1F2937 : ComponentColors.textPrimary(context));
            if (header) cell.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            cell.setGravity(gravity | Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5));
            cell.setBackground(borderBackground(context, i));
            row.addView(cell, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    /** 单元格边框（左/右分隔线） */
    private static android.graphics.drawable.Drawable borderBackground(Context context, int index) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
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
