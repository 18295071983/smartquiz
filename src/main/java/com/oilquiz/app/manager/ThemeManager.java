package com.oilquiz.app.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.oilquiz.app.R;

public class ThemeManager {

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_THEME = "current_theme";

    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_SYSTEM = 2;

    public void applyTheme(Context context) {
        int theme = getCurrentTheme(context);
        switch (theme) {
            case THEME_LIGHT:
                context.setTheme(R.style.Theme_SmartQuiz);
                break;
            case THEME_DARK:
                context.setTheme(R.style.Theme_SmartQuiz_Dark);
                break;
            case THEME_SYSTEM:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 使用系统主题
                    int currentNightMode = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                    if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                        context.setTheme(R.style.Theme_SmartQuiz_Dark);
                    } else {
                        context.setTheme(R.style.Theme_SmartQuiz);
                    }
                } else {
                    // 旧版本默认使用浅色主题
                    context.setTheme(R.style.Theme_SmartQuiz);
                }
                break;
        }
    }

    public int getCurrentTheme(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return preferences.getInt(KEY_THEME, THEME_SYSTEM);
    }

    public void setTheme(Context context, int theme) {
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(KEY_THEME, theme);
        editor.apply();
    }
}
