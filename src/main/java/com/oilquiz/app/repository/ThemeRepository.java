package com.oilquiz.app.repository;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.manager.ThemeManager;
import com.oilquiz.app.model.Theme;

import java.util.ArrayList;
import java.util.List;

public class ThemeRepository {

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_THEME = "current_theme";

    private SharedPreferences preferences;

    public ThemeRepository(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getCurrentTheme() {
        return preferences.getInt(KEY_THEME, ThemeManager.THEME_SYSTEM);
    }

    public void setCurrentTheme(int themeType) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(KEY_THEME, themeType);
        editor.apply();
    }

    public List<Theme> getThemes() {
        List<Theme> themes = new ArrayList<>();
        int currentTheme = getCurrentTheme();

        themes.add(new Theme(1, "浅色模式", ThemeManager.THEME_LIGHT, currentTheme == ThemeManager.THEME_LIGHT));
        themes.add(new Theme(2, "深色模式", ThemeManager.THEME_DARK, currentTheme == ThemeManager.THEME_DARK));
        themes.add(new Theme(3, "跟随系统", ThemeManager.THEME_SYSTEM, currentTheme == ThemeManager.THEME_SYSTEM));

        return themes;
    }
}
