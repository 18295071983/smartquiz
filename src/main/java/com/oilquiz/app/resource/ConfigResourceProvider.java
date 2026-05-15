package com.oilquiz.app.resource;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.Log;

import androidx.annotation.XmlRes;

import com.oilquiz.app.R;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 配置资源提供者
 * 通过系统资源接口动态获取服务框架配置
 */
public class ConfigResourceProvider {
    
    private static final String TAG = "ConfigResourceProvider";
    private static final String PREF_NAME = "app_config_resources";
    
    private static ConfigResourceProvider instance;
    private Context context;
    private SharedPreferences preferences;
    private Resources resources;
    
    // 配置缓存
    private Map<String, Object> configCache;
    
    // 配置变更监听器
    private ConfigChangeListener configChangeListener;
    
    public interface ConfigChangeListener {
        void onConfigChanged(String key, Object value);
    }
    
    private ConfigResourceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.resources = this.context.getResources();
        this.configCache = new HashMap<>();
        loadDefaultConfigs();
    }
    
    public static synchronized ConfigResourceProvider getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigResourceProvider(context);
        }
        return instance;
    }
    
    /**
     * 设置配置变更监听器
     */
    public void setConfigChangeListener(ConfigChangeListener listener) {
        this.configChangeListener = listener;
    }
    
    /**
     * 加载默认配置
     */
    private void loadDefaultConfigs() {
        // 从SharedPreferences加载配置
        Map<String, ?> allPrefs = preferences.getAll();
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            configCache.put(entry.getKey(), entry.getValue());
        }
        
        // 加载系统默认配置
        loadSystemDefaultConfigs();
    }
    
    /**
     * 加载系统默认配置
     */
    private void loadSystemDefaultConfigs() {
        // 应用基本配置
        setDefaultConfig("app_name", getStringResource(R.string.app_name) != null ? getStringResource(R.string.app_name) : "SmartQuiz");
        setDefaultConfig("app_version", getStringResource(R.string.app_version) != null ? getStringResource(R.string.app_version) : "1.0");
        setDefaultConfig("app_build", getStringResource(R.string.app_build) != null ? getStringResource(R.string.app_build) : "1");
        
        // 测验配置
        setDefaultConfig("default_quiz_mode", "practice");
        setDefaultConfig("default_question_count", 10);
        setDefaultConfig("default_time_limit", 0); // 0表示无限制
        setDefaultConfig("enable_auto_save", true);
        setDefaultConfig("enable_sound_effects", true);
        setDefaultConfig("enable_vibration", true);
        
        // 导入导出配置
        setDefaultConfig("default_export_format", "EXCEL");
        setDefaultConfig("default_import_format", "EXCEL");
        setDefaultConfig("max_import_file_size", 50 * 1024 * 1024); // 50MB
        setDefaultConfig("max_export_file_size", 100 * 1024 * 1024); // 100MB
        setDefaultConfig("enable_auto_backup", true);
        setDefaultConfig("backup_interval_days", 7);
        
        // WebView配置
        setDefaultConfig("webview_cache_enabled", true);
        setDefaultConfig("webview_javascript_enabled", true);
        setDefaultConfig("webview_zoom_enabled", true);
        setDefaultConfig("webview_dom_storage_enabled", true);
        
        // AI配置
        setDefaultConfig("ai_service_enabled", false);
        setDefaultConfig("ai_local_model_enabled", false);
        setDefaultConfig("ai_cloud_service_url", "");
        
        // 主题配置
        setDefaultConfig("theme_mode", "system"); // light, dark, system
        setDefaultConfig("theme_color", "blue");
        setDefaultConfig("font_size", "medium"); // small, medium, large
    }
    
    /**
     * 设置默认配置（仅在不存在时设置）
     */
    private void setDefaultConfig(String key, Object value) {
        if (!configCache.containsKey(key)) {
            configCache.put(key, value);
            saveToPreferences(key, value);
        }
    }
    
    /**
     * 获取字符串资源
     */
    private String getStringResource(int resId) {
        try {
            return resources.getString(resId);
        } catch (Exception e) {
            Log.w(TAG, "String resource not found: " + resId);
            return null;
        }
    }
    
    // ==================== 配置获取方法 ====================
    
    /**
     * 获取字符串配置
     */
    public String getString(String key, String defaultValue) {
        Object value = configCache.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return preferences.getString(key, defaultValue);
    }
    
    /**
     * 获取整数配置
     */
    public int getInt(String key, int defaultValue) {
        Object value = configCache.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return preferences.getInt(key, defaultValue);
    }
    
    /**
     * 获取长整数配置
     */
    public long getLong(String key, long defaultValue) {
        Object value = configCache.get(key);
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            return preferences.getLong(key, defaultValue);
        } catch (ClassCastException e) {
            // 如果存储的是Integer类型，尝试获取为int后转换
            int intValue = preferences.getInt(key, (int) defaultValue);
            return intValue;
        }
    }
    
    /**
     * 获取布尔配置
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = configCache.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return preferences.getBoolean(key, defaultValue);
    }
    
    /**
     * 获取浮点数配置
     */
    public float getFloat(String key, float defaultValue) {
        Object value = configCache.get(key);
        if (value instanceof Float) {
            return (Float) value;
        }
        return preferences.getFloat(key, defaultValue);
    }
    
    // ==================== 配置设置方法 ====================
    
    /**
     * 设置配置值
     */
    public void setConfig(String key, Object value) {
        configCache.put(key, value);
        saveToPreferences(key, value);
        
        if (configChangeListener != null) {
            configChangeListener.onConfigChanged(key, value);
        }
    }
    
    /**
     * 保存到SharedPreferences
     */
    private void saveToPreferences(String key, Object value) {
        SharedPreferences.Editor editor = preferences.edit();
        
        if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        }
        
        editor.apply();
    }
    
    /**
     * 从XML资源加载配置
     */
    public void loadConfigFromXml(@XmlRes int xmlResId) {
        try {
            XmlResourceParser parser = resources.getXml(xmlResId);
            String key = null;
            String value = null;
            
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if ("entry".equals(tagName)) {
                        key = parser.getAttributeValue(null, "key");
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    value = parser.getText();
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("entry".equals(parser.getName()) && key != null && value != null) {
                        setConfig(key, value);
                        key = null;
                        value = null;
                    }
                }
                eventType = parser.next();
            }
            parser.close();
        } catch (Exception e) {
            Log.e(TAG, "Error loading config from XML", e);
        }
    }
    
    /**
     * 清除所有配置
     */
    public void clearAllConfigs() {
        configCache.clear();
        preferences.edit().clear().apply();
    }
    
    /**
     * 移除配置
     */
    public void removeConfig(String key) {
        configCache.remove(key);
        preferences.edit().remove(key).apply();
    }
    
    /**
     * 获取所有配置
     */
    public Map<String, Object> getAllConfigs() {
        return new HashMap<>(configCache);
    }
    
    // ==================== 便捷方法 ====================
    
    /**
     * 获取应用名称
     */
    public String getAppName() {
        return getString("app_name", "SmartQuiz");
    }
    
    /**
     * 获取应用版本
     */
    public String getAppVersion() {
        return getString("app_version", "1.0");
    }
    
    /**
     * 获取默认测验模式
     */
    public String getDefaultQuizMode() {
        return getString("default_quiz_mode", "practice");
    }
    
    /**
     * 获取默认题目数量
     */
    public int getDefaultQuestionCount() {
        return getInt("default_question_count", 10);
    }
    
    /**
     * 是否启用音效
     */
    public boolean isSoundEffectsEnabled() {
        return getBoolean("enable_sound_effects", true);
    }
    
    /**
     * 是否启用自动备份
     */
    public boolean isAutoBackupEnabled() {
        return getBoolean("enable_auto_backup", true);
    }
    
    /**
     * 获取主题模式
     */
    public String getThemeMode() {
        return getString("theme_mode", "system");
    }
    
    /**
     * 获取导出格式
     */
    public String getDefaultExportFormat() {
        return getString("default_export_format", "EXCEL");
    }
    
    /**
     * 获取导入格式
     */
    public String getDefaultImportFormat() {
        return getString("default_import_format", "EXCEL");
    }
    
    /**
     * 获取最大导入文件大小
     */
    public long getMaxImportFileSize() {
        return getLong("max_import_file_size", 50 * 1024 * 1024);
    }
    
    /**
     * 获取最大导出文件大小
     */
    public long getMaxExportFileSize() {
        return getLong("max_export_file_size", 100 * 1024 * 1024);
    }
}
