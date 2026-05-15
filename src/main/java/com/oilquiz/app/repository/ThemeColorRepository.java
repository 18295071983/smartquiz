package com.oilquiz.app.repository;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.R;
import com.oilquiz.app.model.ThemeColor;

import java.util.ArrayList;
import java.util.List;

public class ThemeColorRepository {

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_THEME_COLOR = "current_theme_color";

    private SharedPreferences preferences;

    public ThemeColorRepository(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getCurrentThemeColor() {
        return preferences.getInt(KEY_THEME_COLOR, R.color.theme_blue);
    }

    public void setCurrentThemeColor(int colorRes) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(KEY_THEME_COLOR, colorRes);
        editor.putBoolean("use_custom_color", false); // 选择预设主题色时禁用自定义颜色
        editor.apply();
    }

    public List<ThemeColor> getThemeColors() {
        List<ThemeColor> themeColors = new ArrayList<>();
        int currentColor = getCurrentThemeColor();

        themeColors.add(new ThemeColor(1, "蓝色", R.color.theme_blue, currentColor == R.color.theme_blue));
        themeColors.add(new ThemeColor(2, "绿色", R.color.theme_green, currentColor == R.color.theme_green));
        themeColors.add(new ThemeColor(3, "紫色", R.color.theme_purple, currentColor == R.color.theme_purple));
        themeColors.add(new ThemeColor(4, "橙色", R.color.theme_orange, currentColor == R.color.theme_orange));
        themeColors.add(new ThemeColor(5, "粉色", R.color.theme_pink, currentColor == R.color.theme_pink));
        themeColors.add(new ThemeColor(6, "青色", R.color.theme_teal, currentColor == R.color.theme_teal));
        themeColors.add(new ThemeColor(7, "靛蓝", R.color.theme_indigo, currentColor == R.color.theme_indigo));
        
        // 检查是否有自定义颜色
        int customColor = preferences.getInt("custom_theme_color", -1);
        if (customColor != -1) {
            // 检查当前是否使用的是自定义颜色
            boolean isSelected = preferences.getBoolean("use_custom_color", false);
            themeColors.add(new ThemeColor(8, "自定义颜色", R.color.theme_blue, isSelected));
        }

        return themeColors;
    }
}
