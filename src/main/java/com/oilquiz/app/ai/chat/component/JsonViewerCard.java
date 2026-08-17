package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * JSON 查看器组件：格式化展示工具返回的原始 JSON 数据（调试/原始结果）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "原始数据",     // 可选
 *   "data": {...},          // 任意 JSON 对象
 *   "maxHeight": 240        // 可选，dp；超长内容滚动上限
 * }
 * </pre>
 */
public class JsonViewerCard implements ChatComponent {

    @Override
    public String getType() {
        return "json_viewer";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && (data.props.has("data") || data.props.has("json"));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String title = p.optString("title", "");
        Object raw = p.has("data") ? p.opt("data") : p.opt("json");
        String jsonText = raw != null ? prettyPrint(raw) : "";

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(13);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        TextView jsonTv = new TextView(context);
        jsonTv.setText(jsonText);
        jsonTv.setTextSize(11);
        jsonTv.setTextColor(ComponentColors.textSecondary(context));
        jsonTv.setTypeface(Typeface.MONOSPACE);
        jsonTv.setLineSpacing(0, 1.2f);
        jsonTv.setTextIsSelectable(true);
        jsonTv.setBackgroundColor(0x0D000000);
        jsonTv.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));

        int maxHeight = p.optInt("maxHeight", 200);
        if (maxHeight > 0) {
            jsonTv.setMaxHeight(dp(context, maxHeight));
        }
        card.addView(jsonTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** 美化 JSON 文本（容错） */
    private static String prettyPrint(Object raw) {
        try {
            if (raw instanceof JSONObject) {
                return ((JSONObject) raw).toString(2);
            }
            if (raw instanceof org.json.JSONArray) {
                return ((org.json.JSONArray) raw).toString(2);
            }
            return String.valueOf(raw);
        } catch (Exception e) {
            return String.valueOf(raw);
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
