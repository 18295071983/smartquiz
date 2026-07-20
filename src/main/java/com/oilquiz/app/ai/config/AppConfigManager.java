package com.oilquiz.app.ai.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.util.AILogger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AppConfigManager - 统一配置文件管理器
 * 
 * 功能：
 * 1. 统一配置存储 - 集中管理所有配置数据
 * 2. 数据校验 - 验证配置数据的完整性和有效性
 * 3. 异常恢复 - 配置损坏时自动恢复默认值
 * 4. 版本控制 - 配置版本管理和迁移
 * 5. 持久化 - 安全的配置保存和加载
 */
public class AppConfigManager {

    private static final String TAG = "AppConfigManager";
    private static final String CONFIG_DIR = "app_config";
    private static final String CONFIG_FILE = "config.json";
    private static final String BACKUP_DIR = "config_backup";
    private static final int CURRENT_VERSION = 1;

    // 单例
    private static volatile AppConfigManager INSTANCE;
    private final Context context;
    private final ExecutorService executor;
    private final File configDir;
    private final File backupDir;

    // 配置数据
    private final ConcurrentHashMap<String, Object> configData = new ConcurrentHashMap<>();
    private final AtomicBoolean isLoaded = new AtomicBoolean(false);
    private final AtomicBoolean isSaving = new AtomicBoolean(false);

    // 默认配置
    private static final JSONObject DEFAULT_CONFIG;

    static {
        DEFAULT_CONFIG = new JSONObject();
        try {
            // 模型配置（使用安全的默认值）
            DEFAULT_CONFIG.put("model", new JSONObject()
                .put("defaultModel", "")
                .put("autoLoadLastModel", true)
                .put("maxMemoryMB", 2048)
                .put("useMmap", true)
                .put("gpuLayers", 20)  // 最大 30 层
                .put("threadCount", 3)  // 安全的线程数
                .put("batchSize", 128));  // 安全的批处理大小

            // UI配置
            DEFAULT_CONFIG.put("ui", new JSONObject()
                .put("theme", "default")
                .put("fontSize", 14)
                .put("showTimestamp", true)
                .put("enableAnimation", true));

            // AI服务配置
            DEFAULT_CONFIG.put("ai", new JSONObject()
                .put("maxTokens", 2048)
                .put("temperature", 0.7f)
                .put("topP", 0.9f)
                .put("enableAgent", false)
                .put("enableTools", true));

            // 缓存配置
            DEFAULT_CONFIG.put("cache", new JSONObject()
                .put("enableCache", true)
                .put("maxCacheSizeMB", 500)
                .put("cacheExpiryDays", 7)
                .put("autoCleanCache", true));

            // 恢复配置
            DEFAULT_CONFIG.put("recovery", new JSONObject()
                .put("enableAutoRecovery", true)
                .put("maxRecoveryAttempts", 3)
                .put("recoveryCooldownMs", 30000));

        } catch (JSONException e) {
            AILogger.e(TAG, "Failed to create default config", e);
        }
    }

    private AppConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.configDir = new File(context.getFilesDir(), CONFIG_DIR);
        this.backupDir = new File(context.getFilesDir(), BACKUP_DIR);

        if (!configDir.exists()) configDir.mkdirs();
        if (!backupDir.exists()) backupDir.mkdirs();
    }

    public static AppConfigManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppConfigManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AppConfigManager(context);
                }
            }
        }
        return INSTANCE;
    }

    // ========== 配置加载和保存 ==========

    /**
     * 加载配置
     */
    public void loadConfig(ConfigCallback callback) {
        if (isLoaded.get()) {
            if (callback != null) callback.onLoaded(true, "配置已加载");
            return;
        }

        executor.execute(() -> {
            try {
                boolean success = doLoadConfig();
                isLoaded.set(success);
                if (callback != null) {
                    callback.onLoaded(success, success ? "配置加载成功" : "配置加载失败，使用默认值");
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Failed to load config", e);
                loadDefaultConfig();
                isLoaded.set(true);
                if (callback != null) {
                    callback.onLoaded(false, "配置加载异常，使用默认值: " + e.getMessage());
                }
            }
        });
    }

    private boolean doLoadConfig() {
        File configFile = new File(configDir, CONFIG_FILE);
        if (!configFile.exists()) {
            AILogger.i(TAG, "Config file not found, using defaults");
            loadDefaultConfig();
            return true;
        }

        try {
            // 读取配置文件
            String json = readFile(configFile);
            JSONObject savedConfig = new JSONObject(json);

            // 验证配置版本
            int savedVersion = savedConfig.optInt("version", 0);
            if (savedVersion < CURRENT_VERSION) {
                AILogger.i(TAG, "Config version mismatch: " + savedVersion + " vs " + CURRENT_VERSION);
                // 尝试迁移配置
                savedConfig = migrateConfig(savedConfig, savedVersion);
            }

            // 验证配置数据
            if (!validateConfig(savedConfig)) {
                AILogger.w(TAG, "Config validation failed, restoring from backup");
                return restoreFromBackup();
            }

            // 加载配置
            configData.clear();
            loadFromJson(savedConfig);
            AILogger.i(TAG, "Config loaded successfully");
            return true;

        } catch (JSONException e) {
            AILogger.e(TAG, "Invalid config JSON", e);
            return restoreFromBackup();
        } catch (IOException e) {
            AILogger.e(TAG, "Failed to read config file", e);
            return restoreFromBackup();
        }
    }

    /**
     * 保存配置
     */
    public void saveConfig(ConfigCallback callback) {
        if (isSaving.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    boolean success = doSaveConfig();
                    if (callback != null) {
                        callback.onSaved(success, success ? "配置保存成功" : "配置保存失败");
                    }
                } finally {
                    isSaving.set(false);
                }
            });
        }
    }

    private boolean doSaveConfig() {
        try {
            // 创建备份
            createBackup();

            // 生成配置JSON
            JSONObject configJson = toJson();
            configJson.put("version", CURRENT_VERSION);
            configJson.put("timestamp", System.currentTimeMillis());

            // 写入配置文件
            File configFile = new File(configDir, CONFIG_FILE);
            writeFile(configFile, configJson.toString());

            AILogger.i(TAG, "Config saved successfully");
            return true;

        } catch (Exception e) {
            AILogger.e(TAG, "Failed to save config", e);
            return false;
        }
    }

    // ========== 数据校验 ==========

    /**
     * 验证配置数据
     */
    private boolean validateConfig(JSONObject config) {
        if (config == null) return false;

        try {
            // 验证必需的配置节
            if (!config.has("model")) return false;
            if (!config.has("ui")) return false;
            if (!config.has("ai")) return false;

            // 验证模型配置
            JSONObject modelConfig = config.getJSONObject("model");
            if (modelConfig.optInt("maxMemoryMB", 0) < 512) return false;
            if (modelConfig.optInt("gpuLayers", 0) < 0) return false;
            if (modelConfig.optInt("threadCount", 0) < 1) return false;

            // 验证AI配置
            JSONObject aiConfig = config.getJSONObject("ai");
            float temp = (float) aiConfig.optDouble("temperature", 0.7);
            if (temp < 0 || temp > 2) return false;

            float topP = (float) aiConfig.optDouble("topP", 0.9);
            if (topP < 0 || topP > 1) return false;

            return true;

        } catch (JSONException e) {
            AILogger.w(TAG, "Config validation error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 修正配置数据
     */
    private JSONObject correctConfig(JSONObject config) {
        if (config == null) {
            return getDefaultConfig();
        }

        try {
            // 修正模型配置
            if (!config.has("model")) {
                config.put("model", DEFAULT_CONFIG.getJSONObject("model"));
            } else {
                JSONObject model = config.getJSONObject("model");
                if (model.optInt("maxMemoryMB", 0) < 512) {
                    model.put("maxMemoryMB", 2048);
                }
                if (model.optInt("gpuLayers", 0) < 0) {
                    model.put("gpuLayers", 20);
                }
                if (model.optInt("threadCount", 0) < 1) {
                    model.put("threadCount", 4);
                }
            }

            // 修正AI配置
            if (!config.has("ai")) {
                config.put("ai", DEFAULT_CONFIG.getJSONObject("ai"));
            } else {
                JSONObject ai = config.getJSONObject("ai");
                double temp = ai.optDouble("temperature", 0.7);
                if (temp < 0 || temp > 2) ai.put("temperature", 0.7);

                double topP = ai.optDouble("topP", 0.9);
                if (topP < 0 || topP > 1) ai.put("topP", 0.9);
            }

            // 修正UI配置
            if (!config.has("ui")) {
                config.put("ui", DEFAULT_CONFIG.getJSONObject("ui"));
            }

            return config;

        } catch (JSONException e) {
            AILogger.e(TAG, "Failed to correct config", e);
            return getDefaultConfig();
        }
    }

    // ========== 异常恢复 ==========

    /**
     * 从备份恢复
     */
    private boolean restoreFromBackup() {
        File backupFile = new File(backupDir, CONFIG_FILE);
        if (!backupFile.exists()) {
            AILogger.i(TAG, "No backup found, using defaults");
            loadDefaultConfig();
            return true;
        }

        try {
            String json = readFile(backupFile);
            JSONObject backupConfig = new JSONObject(json);

            if (validateConfig(backupConfig)) {
                configData.clear();
                loadFromJson(backupConfig);
                AILogger.i(TAG, "Restored from backup");
                return true;
            } else {
                AILogger.w(TAG, "Backup also invalid, using defaults");
                loadDefaultConfig();
                return true;
            }

        } catch (Exception e) {
            AILogger.e(TAG, "Failed to restore from backup", e);
            loadDefaultConfig();
            return true;
        }
    }

    /**
     * 创建备份
     */
    private void createBackup() {
        File configFile = new File(configDir, CONFIG_FILE);
        File backupFile = new File(backupDir, CONFIG_FILE);

        if (configFile.exists()) {
            try {
                String content = readFile(configFile);
                writeFile(backupFile, content);
                AILogger.d(TAG, "Backup created");
            } catch (IOException e) {
                AILogger.w(TAG, "Failed to create backup: " + e.getMessage());
            }
        }
    }

    /**
     * 加载默认配置
     */
    private void loadDefaultConfig() {
        configData.clear();
        loadFromJson(DEFAULT_CONFIG);
    }

    // ========== 配置迁移 ==========

    /**
     * 迁移配置到新版本
     */
    private JSONObject migrateConfig(JSONObject oldConfig, int oldVersion) {
        try {
            JSONObject newConfig = correctConfig(oldConfig);
            newConfig.put("version", CURRENT_VERSION);

            // 执行版本迁移逻辑
            for (int v = oldVersion; v < CURRENT_VERSION; v++) {
                migrateToVersion(newConfig, v + 1);
            }

            return newConfig;

        } catch (JSONException e) {
            AILogger.e(TAG, "Config migration failed", e);
            return getDefaultConfig();
        }
    }

    private void migrateToVersion(JSONObject config, int version) throws JSONException {
        switch (version) {
            case 1:
                // 版本1迁移逻辑
                AILogger.i(TAG, "Migrating config to version " + version);
                break;
            // 添加更多版本迁移逻辑
        }
    }

    // ========== 配置访问 ==========

    /**
     * 获取配置值
     */
    public <T> T get(String key, T defaultValue) {
        Object value = configData.get(key);
        if (value == null) return defaultValue;

        try {
            @SuppressWarnings("unchecked")
            T result = (T) value;
            return result;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * 获取嵌套配置值
     */
    public <T> T get(String section, String key, T defaultValue) {
        Object sectionObj = configData.get(section);
        if (sectionObj instanceof JSONObject) {
            try {
                JSONObject json = (JSONObject) sectionObj;
                Object value = json.get(key);
                @SuppressWarnings("unchecked")
                T result = (T) value;
                return result;
            } catch (JSONException | ClassCastException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 设置配置值
     */
    public void set(String key, Object value) {
        configData.put(key, value);
    }

    /**
     * 设置嵌套配置值
     */
    public void set(String section, String key, Object value) {
        Object sectionObj = configData.get(section);
        if (sectionObj instanceof JSONObject) {
            try {
                JSONObject json = (JSONObject) sectionObj;
                json.put(key, value);
            } catch (JSONException e) {
                AILogger.w(TAG, "Failed to set config value: " + e.getMessage());
            }
        }
    }

    /**
     * 获取完整配置
     */
    public JSONObject getFullConfig() {
        return toJson();
    }

    /**
     * 获取默认配置
     */
    public static JSONObject getDefaultConfig() {
        try {
            return new JSONObject(DEFAULT_CONFIG.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // ========== 辅助方法 ==========

    private void loadFromJson(JSONObject json) {
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            try {
                String key = keys.next();
                configData.put(key, json.get(key));
            } catch (JSONException e) {
                AILogger.w(TAG, "Failed to load config key: " + e.getMessage());
            }
        }
    }

    private JSONObject toJson() {
        JSONObject json = new JSONObject();
        for (ConcurrentHashMap.Entry<String, Object> entry : configData.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (JSONException e) {
                AILogger.w(TAG, "Failed to serialize config key: " + entry.getKey());
            }
        }
        return json;
    }

    private String readFile(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        return new String(data);
    }

    private void writeFile(File file, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes());
        fos.close();
    }

    // ========== 状态查询 ==========

    public boolean isLoaded() {
        return isLoaded.get();
    }

    public boolean isSaving() {
        return isSaving.get();
    }

    // ========== 回调接口 ==========

    public interface ConfigCallback {
        default void onLoaded(boolean success, String message) {}
        default void onSaved(boolean success, String message) {}
    }
}
