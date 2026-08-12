package com.oilquiz.app.ai.usage;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences 工具类
 */
public class SharedPreferencesUtil {
    
    private static final String PREF_NAME = "ai_usage_config";
    
    public static void putInt(Context context, String key, int value) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .edit()
              .putInt(key, value)
              .apply();
    }
    
    public static int getInt(Context context, String key, int defaultValue) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .getInt(key, defaultValue);
    }
    
    public static void putLong(Context context, String key, long value) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .edit()
              .putLong(key, value)
              .apply();
    }
    
    public static long getLong(Context context, String key, long defaultValue) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .getLong(key, defaultValue);
    }
    
    public static void putString(Context context, String key, String value) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .edit()
              .putString(key, value)
              .apply();
    }
    
    public static String getString(Context context, String key, String defaultValue) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .getString(key, defaultValue);
    }
    
    public static void remove(Context context, String key) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .edit()
              .remove(key)
              .apply();
    }
    
    public static void clear(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .edit()
              .clear()
              .apply();
    }
}
