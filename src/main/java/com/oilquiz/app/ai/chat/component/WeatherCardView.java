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
 * 天气卡片组件：城市 + 实时温度/天气 + 湿度风力 + 多日预报。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "city": "北京",
 *   "temp": "26℃",
 *   "text": "多云",
 *   "icon": "⛅",
 *   "humidity": "60%",
 *   "windDir": "东南风",
 *   "windScale": "3级",
 *   "forecast": [
 *     {"date":"周一","text":"晴","tempMin":"18℃","tempMax":"28℃"}
 *   ]
 * }
 * </pre>
 */
public class WeatherCardView implements ChatComponent {

    @Override
    public String getType() {
        return "weather_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !TextUtils.isEmpty(data.props.optString("temp", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String city = p.optString("city", "");
        String temp = p.optString("temp", "");
        String text = p.optString("text", "");
        String icon = p.optString("icon", "");
        String humidity = p.optString("humidity", "");
        String windDir = p.optString("windDir", "");
        String windScale = p.optString("windScale", "");
        JSONArray forecast = p.optJSONArray("forecast");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 第一行：城市 + 温度 + 描述
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        if (!TextUtils.isEmpty(city)) {
            TextView cityTv = new TextView(context);
            cityTv.setText(city);
            cityTv.setTextSize(15);
            cityTv.setTextColor(ComponentColors.textPrimary(context));
            cityTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            topRow.addView(cityTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        if (!TextUtils.isEmpty(icon)) {
            TextView iconTv = new TextView(context);
            iconTv.setText(icon);
            iconTv.setTextSize(30);
            iconTv.setPadding(dp(context, 10), 0, 0, 0);
            topRow.addView(iconTv);
        }

        if (!TextUtils.isEmpty(temp)) {
            TextView tempTv = new TextView(context);
            tempTv.setText(temp);
            tempTv.setTextSize(32);
            tempTv.setTextColor(ComponentColors.textPrimary(context));
            tempTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tempTv.setPadding(dp(context, 10), 0, 0, 0);
            topRow.addView(tempTv);
        }

        if (!TextUtils.isEmpty(text)) {
            TextView textTv = new TextView(context);
            textTv.setText(text);
            textTv.setTextSize(14);
            textTv.setTextColor(ComponentColors.textSecondary(context));
            textTv.setPadding(dp(context, 10), 0, 0, 0);
            topRow.addView(textTv);
        }
        // 顶行撑满卡片宽度
        card.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 第二行：湿度 / 风
        StringBuilder sub = new StringBuilder();
        if (!TextUtils.isEmpty(humidity)) sub.append("💧 湿度 ").append(humidity);
        if (!TextUtils.isEmpty(windDir) || !TextUtils.isEmpty(windScale)) {
            if (sub.length() > 0) sub.append("    ");
            sub.append("🍃 ").append(windDir).append(windScale);
        }
        if (sub.length() > 0) {
            TextView subTv = new TextView(context);
            subTv.setText(sub.toString());
            subTv.setTextSize(12);
            subTv.setTextColor(ComponentColors.textTertiary(context));
            subTv.setPadding(0, dp(context, 2), 0, dp(context, 6));
            card.addView(subTv);
        }

        // 预报行
        if (forecast != null && forecast.length() > 0) {
            LinearLayout forecastRow = new LinearLayout(context);
            forecastRow.setOrientation(LinearLayout.HORIZONTAL);
            int count = Math.min(forecast.length(), 5);
            for (int i = 0; i < count; i++) {
                JSONObject day = forecast.optJSONObject(i);
                if (day == null) continue;
                LinearLayout dayCol = new LinearLayout(context);
                dayCol.setOrientation(LinearLayout.VERTICAL);
                dayCol.setGravity(Gravity.CENTER);
                dayCol.setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6));

                TextView dateTv = new TextView(context);
                dateTv.setText(day.optString("date", ""));
                dateTv.setTextSize(11);
                dateTv.setTextColor(ComponentColors.textTertiary(context));
                dayCol.addView(dateTv);

                TextView dayIconTv = new TextView(context);
                dayIconTv.setText(day.optString("icon", "☁"));
                dayIconTv.setTextSize(18);
                dayIconTv.setPadding(0, dp(context, 2), 0, dp(context, 2));
                dayCol.addView(dayIconTv);

                TextView textTv = new TextView(context);
                textTv.setText(day.optString("text", ""));
                textTv.setTextSize(10);
                textTv.setTextColor(ComponentColors.textSecondary(context));
                textTv.setSingleLine(true);
                dayCol.addView(textTv);

                TextView tempTv = new TextView(context);
                tempTv.setText(day.optString("tempMin", "") + "~" + day.optString("tempMax", ""));
                tempTv.setTextSize(10);
                tempTv.setTextColor(ComponentColors.textPrimary(context));
                tempTv.setSingleLine(true);
                dayCol.addView(tempTv);

                forecastRow.addView(dayCol, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            // 预报行撑满卡片宽度，各列等宽均分
            card.addView(forecastRow, new LinearLayout.LayoutParams(
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
