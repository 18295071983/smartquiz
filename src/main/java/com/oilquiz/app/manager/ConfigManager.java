package com.oilquiz.app.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "app_config";
    private static final String KEY_QUIZ_MODES = "quiz_modes";
    private static final String KEY_QUESTION_COUNTS = "question_counts";
    private static final String KEY_QUESTION_TYPES = "question_types";
    private static final String KEY_QUESTION_ORDERS = "question_orders";
    private static final String KEY_EXPORT_FORMATS = "export_formats";
    private static final String KEY_APP_SETTINGS = "app_settings";

    private static ConfigManager instance;
    private SharedPreferences preferences;
    private Gson gson;

    private Map<String, Object> configCache;

    private ConfigManager(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
        configCache = new HashMap<>();
        loadDefaultConfig();
    }

    public static synchronized ConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigManager(context);
        }
        return instance;
    }

    private void loadDefaultConfig() {
        // 加载默认的测验模式
        if (!preferences.contains(KEY_QUIZ_MODES)) {
            List<Map<String, String>> quizModes = new ArrayList<>();
            quizModes.add(createModeItem("recite", "背诵模式"));
            quizModes.add(createModeItem("practice", "练习模式"));
            quizModes.add(createModeItem("exam", "考试模式"));
            quizModes.add(createModeItem("review", "复习模式"));
            quizModes.add(createModeItem("challenge", "挑战模式"));
            saveConfig(KEY_QUIZ_MODES, quizModes);
        }

        // 加载默认的题目数量选项
        if (!preferences.contains(KEY_QUESTION_COUNTS)) {
            List<String> questionCounts = new ArrayList<>();
            questionCounts.add("5题");
            questionCounts.add("10题");
            questionCounts.add("15题");
            questionCounts.add("20题");
            questionCounts.add("30题");
            questionCounts.add("50题");
            saveConfig(KEY_QUESTION_COUNTS, questionCounts);
        }

        // 加载默认的题目类型选项
        if (!preferences.contains(KEY_QUESTION_TYPES)) {
            List<String> questionTypes = new ArrayList<>();
            questionTypes.add("全部");
            questionTypes.add("单选题");
            questionTypes.add("多选题");
            questionTypes.add("判断题");
            questionTypes.add("填空题");
            questionTypes.add("简答题");
            saveConfig(KEY_QUESTION_TYPES, questionTypes);
        }

        // 加载默认的题目顺序选项
        if (!preferences.contains(KEY_QUESTION_ORDERS)) {
            List<String> questionOrders = new ArrayList<>();
            questionOrders.add("顺序");
            questionOrders.add("随机");
            saveConfig(KEY_QUESTION_ORDERS, questionOrders);
        }

        // 加载默认的导出格式
        if (!preferences.contains(KEY_EXPORT_FORMATS)) {
            List<Map<String, String>> exportFormats = new ArrayList<>();
            exportFormats.add(createExportFormatItem("EXCEL", "Excel"));
            exportFormats.add(createExportFormatItem("PDF", "PDF"));
            exportFormats.add(createExportFormatItem("JSON", "JSON"));
            exportFormats.add(createExportFormatItem("CSV", "CSV"));
            exportFormats.add(createExportFormatItem("HTML", "HTML"));
            exportFormats.add(createExportFormatItem("MARKDOWN", "Markdown"));
            exportFormats.add(createExportFormatItem("WORD", "Word"));
            exportFormats.add(createExportFormatItem("LONG_IMAGE", "长图片"));

            saveConfig(KEY_EXPORT_FORMATS, exportFormats);
        }
    }

    private Map<String, String> createModeItem(String value, String label) {
        Map<String, String> item = new HashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }

    private Map<String, String> createExportFormatItem(String value, String label) {
        Map<String, String> item = new HashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }

    private void saveConfig(String key, Object value) {
        String json = gson.toJson(value);
        preferences.edit().putString(key, json).apply();
        configCache.put(key, value);
    }

    private <T> T getConfig(String key, Type type, T defaultValue) {
        if (configCache.containsKey(key)) {
            return (T) configCache.get(key);
        }

        String json = preferences.getString(key, null);
        if (json != null) {
            try {
                T value = gson.fromJson(json, type);
                configCache.put(key, value);
                return value;
            } catch (Exception e) {
                Log.e(TAG, "Error parsing config: " + e.getMessage());
            }
        }
        return defaultValue;
    }

    // 获取测验模式列表
    public List<Map<String, String>> getQuizModes() {
        Type type = new TypeToken<List<Map<String, String>>>() {}.getType();
        return getConfig(KEY_QUIZ_MODES, type, new ArrayList<>());
    }

    // 获取题目数量选项列表
    public List<String> getQuestionCounts() {
        Type type = new TypeToken<List<String>>() {}.getType();
        return getConfig(KEY_QUESTION_COUNTS, type, new ArrayList<>());
    }

    // 获取题目类型选项列表
    public List<String> getQuestionTypes() {
        Type type = new TypeToken<List<String>>() {}.getType();
        return getConfig(KEY_QUESTION_TYPES, type, new ArrayList<>());
    }

    // 获取题目顺序选项列表
    public List<String> getQuestionOrders() {
        Type type = new TypeToken<List<String>>() {}.getType();
        return getConfig(KEY_QUESTION_ORDERS, type, new ArrayList<>());
    }

    // 获取导出格式列表
    public List<Map<String, String>> getExportFormats() {
        Type type = new TypeToken<List<Map<String, String>>>() {}.getType();
        List<Map<String, String>> formats = getConfig(KEY_EXPORT_FORMATS, type, new ArrayList<>());
        
        // 过滤掉APK格式
        List<Map<String, String>> filteredFormats = new ArrayList<>();
        for (Map<String, String> format : formats) {
            if (!"APK".equals(format.get("value"))) {
                filteredFormats.add(format);
            }
        }
        
        return filteredFormats;
    }

    // 更新配置
    public void updateConfig(String key, Object value) {
        saveConfig(key, value);
    }

    // 从服务器更新配置（模拟）
    public void updateConfigFromServer() {
        // 这里可以实现从服务器获取配置的逻辑
        // 目前只是模拟更新
        Log.d(TAG, "Updating config from server...");
        // 模拟网络请求延迟
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                // 模拟更新配置
                Log.d(TAG, "Config updated from server");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 清除缓存
    public void clearCache() {
        configCache.clear();
    }
}
