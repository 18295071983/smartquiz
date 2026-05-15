package com.oilquiz.app.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.preference.PreferenceManager;

import java.util.Locale;

public class LanguageManager {
    private static final String LANGUAGE_KEY = "language";
    private static final String DEFAULT_LANGUAGE = "zh";

    public static void setLanguage(Context context, String languageCode) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().putString(LANGUAGE_KEY, languageCode).apply();
        updateResources(context, languageCode);
    }

    public static String getLanguage(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString(LANGUAGE_KEY, DEFAULT_LANGUAGE);
    }

    public static void updateResources(Context context, String languageCode) {
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            configuration.setLocale(locale);
        } else {
            configuration.locale = locale;
        }

        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    public static void applyLanguage(Context context) {
        String languageCode = getLanguage(context);
        updateResources(context, languageCode);
    }

    public static String[] getSupportedLanguages() {
        return new String[] { "zh", "en", "zh-rTW" };
    }

    public static String getLanguageName(Context context, String languageCode) {
        switch (languageCode) {
            case "zh":
                return "简体中文";
            case "en":
                return "English";
            case "zh-rTW":
                return "繁體中文";
            default:
                return "简体中文";
        }
    }
}
