package com.oilquiz.app.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * 语言管理：基于 AndroidX AppCompatDelegate.setApplicationLocales 的官方方案。
 * <p>
 * 修复说明（2026-09）：
 * <ul>
 *   <li>移除 Locale.setDefault + Resources.updateConfiguration（targetSdk 33+ 时
 *       Locale.setDefault 在语言变化时会抛 UnsupportedOperationException，是切换崩溃的根因）；</li>
 *   <li>接入 AppCompatDelegate.setApplicationLocales：API 33+ 走系统 LocaleManager
 *       （配合 res/xml/locales_config.xml），低版本由 AppCompat 内部触发 Activity 重建；</li>
 *   <li>applyLanguage() 需在 Application.onCreate 中调用，恢复用户上次选择。</li>
 * </ul>
 */
public class LanguageManager {
    private static final String LANGUAGE_KEY = "language";
    private static final String DEFAULT_LANGUAGE = "zh";

    /** 存储代码（Android 资源目录风格）转 BCP-47 language tag，供 setApplicationLocales 使用 */
    private static String toLanguageTag(String languageCode) {
        if ("zh-rTW".equals(languageCode)) {
            return "zh-TW";
        }
        return languageCode;
    }

    public static void setLanguage(Context context, String languageCode) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().putString(LANGUAGE_KEY, languageCode).apply();
        applyLanguage(context);
    }

    public static String getLanguage(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString(LANGUAGE_KEY, DEFAULT_LANGUAGE);
    }

    /**
     * 应用当前语言。应在 Application.onCreate 中调用以恢复上次选择；
     * setLanguage 内部也会调用，使切换即时生效并触发 Activity 重建。
     */
    public static void applyLanguage(Context context) {
        String languageCode = getLanguage(context);
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(toLanguageTag(languageCode)));
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
