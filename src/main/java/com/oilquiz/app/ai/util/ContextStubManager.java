package com.oilquiz.app.ai.util;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class ContextStubManager {
    private static final String TAG = "ContextStubManager";
    
    private static final Map<String, String> STUB_VALUES = new HashMap<>();
    
    static {
        STUB_VALUES.put("userName", "用户");
        STUB_VALUES.put("appName", "答题宝");
        STUB_VALUES.put("version", "2.0");
        STUB_VALUES.put("currentDate", "");
        STUB_VALUES.put("currentTime", "");
        STUB_VALUES.put("deviceModel", "Android设备");
        STUB_VALUES.put("location", "");
        STUB_VALUES.put("weather", "");
        STUB_VALUES.put("userLevel", "普通用户");
        STUB_VALUES.put("studyProgress", "0%");
        STUB_VALUES.put("correctRate", "0%");
        STUB_VALUES.put("totalQuestions", "0");
        STUB_VALUES.put("correctQuestions", "0");
        STUB_VALUES.put("wrongQuestions", "0");
        STUB_VALUES.put("learningTime", "0分钟");
        STUB_VALUES.put("currentSubject", "");
        STUB_VALUES.put("currentChapter", "");
        STUB_VALUES.put("difficulty", "普通");
        STUB_VALUES.put("examMode", "关闭");
        STUB_VALUES.put("practiceMode", "标准模式");
        STUB_VALUES.put("aiMode", "标准");
        STUB_VALUES.put("language", "中文");
        STUB_VALUES.put("theme", "默认");
        STUB_VALUES.put("fontSize", "正常");
        STUB_VALUES.put("ttsEnabled", "关闭");
        STUB_VALUES.put("ttsVoice", "默认");
        STUB_VALUES.put("ttsSpeed", "正常");
        STUB_VALUES.put("notificationEnabled", "开启");
        STUB_VALUES.put("autoSaveEnabled", "开启");
        STUB_VALUES.put("cloudSyncEnabled", "关闭");
        STUB_VALUES.put("lastSyncTime", "从未同步");
        STUB_VALUES.put("backupEnabled", "开启");
        STUB_VALUES.put("lastBackupTime", "从未备份");
        STUB_VALUES.put("cacheSize", "0MB");
        STUB_VALUES.put("storageUsed", "0MB");
        STUB_VALUES.put("storageTotal", "0MB");
        STUB_VALUES.put("batteryLevel", "100%");
        STUB_VALUES.put("networkStatus", "已连接");
        STUB_VALUES.put("networkType", "WiFi");
    }
    
    public static String getOrDefault(String key, String defaultValue) {
        if (key == null) {
            Log.w(TAG, "ContextStubManager: null key requested");
            return defaultValue != null ? defaultValue : "";
        }
        
        String value = STUB_VALUES.get(key);
        if (value == null) {
            Log.w(TAG, "ContextStubManager: missing stub value for key '" + key + "', using default");
            return defaultValue != null ? defaultValue : "";
        }
        return value;
    }
    
    public static String getOrEmpty(String key) {
        return getOrDefault(key, "");
    }
    
    public static String getOrDefaultWithWarning(String key, String defaultValue, String warningMessage) {
        if (!STUB_VALUES.containsKey(key)) {
            Log.w(TAG, "ContextStubManager: " + warningMessage);
        }
        return getOrDefault(key, defaultValue);
    }
    
    public static boolean hasStub(String key) {
        return key != null && STUB_VALUES.containsKey(key);
    }
    
    public static void registerStub(String key, String value) {
        if (key != null && value != null) {
            STUB_VALUES.put(key, value);
        }
    }
    
    public static void registerStubs(Map<String, String> stubs) {
        if (stubs != null) {
            STUB_VALUES.putAll(stubs);
        }
    }
    
    public static Map<String, String> getAllStubs() {
        return new HashMap<>(STUB_VALUES);
    }
}
