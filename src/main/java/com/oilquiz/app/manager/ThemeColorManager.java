package com.oilquiz.app.manager;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.R;

public class ThemeColorManager {

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_THEME_COLOR = "current_theme_color";

    public int getCurrentThemeColor(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return preferences.getInt(KEY_THEME_COLOR, R.color.theme_blue);
    }

    public int getCurrentThemeColorValue(Context context) {
        // 首先检查是否启用了自定义颜色
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean useCustomColor = preferences.getBoolean("use_custom_color", false);
        
        if (useCustomColor) {
            int customColor = preferences.getInt("custom_theme_color", -1);
            if (customColor != -1) {
                return customColor;
            }
        }
        
        // 如果没有自定义颜色，使用默认的主题色资源
        int colorRes = getCurrentThemeColor(context);
        return context.getResources().getColor(colorRes);
    }

    public void setThemeColor(Context context, int colorRes) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(KEY_THEME_COLOR, colorRes);
        editor.apply();
    }
}
