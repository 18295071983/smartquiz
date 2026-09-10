package com.oilquiz.app.ai.model;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线模型配置表 —— 以「模型名」为唯一匹配键的模型能力配置。
 *
 * 数据来源：官方 API 文档查证的 JSON 数据表（assets/models_profile.json），
 * 不再散落硬编码。每条记录含：匹配键 / 上下文窗口 / 服务商 / 官方文档来源 URL / 查证日期。
 *
 * 可更新：Agent 可调用 {@link #updateFromJson(Context, String)} 把新表写入
 * filesDir/models_profile.json（优先于 assets 内置表加载），重启或下次调用即生效，
 * 无需改代码重新发版。
 *
 * 匹配优先级：
 *   1. apiUrl + modelName 双命中（条目带 apiUrl 时）
 *   2. 仅 modelName 关键词命中
 *   3. 未知 → 默认 128K
 */
public final class OnlineModelProfile {

    private static final String TAG = "OnlineModelProfile";
    /** 内置数据表（assets，随 APK 发布） */
    private static final String ASSET_FILE = "models_profile.json";
    /** 外部覆盖数据表（filesDir，Agent 更新后写入，优先加载） */
    private static final String OVERRIDE_FILE = "models_profile.json";
    private static final String PREFS_NAME = "models_profile_update";
    private static final String KEY_JSON = "profile_json"; // 内存持久化缓存（跨进程读取无需等文件）
    /** 未知模型默认窗口：128K（当前主流模型普遍 ≥128K；用户可让 Agent 用 update_models_profile 补录精确值） */
    private static final int DEFAULT_UNKNOWN_WINDOW = 128000;

    private OnlineModelProfile() {
    }

    /** 匹配结果：上下文窗口 + 服务商显示名 */
    public static final class ModelProfile {
        public final int contextWindow;
        public final String provider;

        ModelProfile(int contextWindow, String provider) {
            this.contextWindow = contextWindow;
            this.provider = provider;
        }
    }

    /** 模型配置条目（JSON 数据表的一行） */
    public static final class Entry {
        public String match;           // 匹配键（模型名关键词，小写）
        public String apiUrl;          // 可选：API 地址关键词（小写，如 "deepseek.com" / "dashscope"）。
                                       // 有值时该条目仅在该端点下生效（同一模型名不同端点可不同窗口，
                                       // 如聚合商限窗场景）；无值则全局按模型名匹配。
        public int contextWindow;      // 上下文窗口（tokens）
        public String provider;        // 服务商显示名
        public String source;          // 官方文档来源 URL（可空）
        public String date;            // 查证日期（YYYY-MM-DD，可空）

        public Entry() {
        }

        public Entry(String match, int contextWindow, String provider) {
            this.match = match;
            this.contextWindow = contextWindow;
            this.provider = provider;
        }
    }

    /** 数据表容器（JSON 顶层结构） */
    public static final class ProfileTable {
        public String version;         // 表版本（如 "2026-08-21"）
        public String updated;         // 更新时间（ISO 时间戳，可空）
        public String note;            // 说明（可空）
        public List<Entry> models;     // 条目列表（顺序 = 匹配优先级）

        public ProfileTable() {
            this.models = new ArrayList<>();
        }
    }

    // ============ 加载与匹配 ============

    /** 当前生效的数据表（覆盖文件优先，其次 assets 内置），首次访问时懒加载 */
    private static volatile ProfileTable table;

    /** 加载数据表：外部覆盖文件（filesDir）优先，否则 assets 内置表 */
    private static ProfileTable loadTable(Context context) {
        ProfileTable t = null;
        if (context != null) {
            // 1. 外部覆盖文件（Agent 更新写入）
            File override = new File(context.getFilesDir(), OVERRIDE_FILE);
            if (override.exists()) {
                t = parseJson(readFile(override));
                if (t != null) {
                    AILogger.i(TAG, "Loaded override profile table: " + override.getAbsolutePath()
                            + " (version=" + t.version + ", entries=" + t.models.size() + ")");
                } else {
                    AILogger.w(TAG, "Override profile table invalid, falling back to assets");
                }
            }
            // 2. assets 内置表
            if (t == null) {
                t = parseJson(readAsset(context, ASSET_FILE));
                if (t != null) {
                    AILogger.i(TAG, "Loaded assets profile table (version=" + t.version
                            + ", entries=" + t.models.size() + ")");
                }
            }
        }
        if (t == null || t.models == null || t.models.isEmpty()) {
            // 兜底：内置几条常见模型，保证任何情况下都有可用窗口
            t = fallbackTable();
        }
        return t;
    }

    /** 兜底表（仅在数据文件都缺失时使用，极少数情况） */
    private static ProfileTable fallbackTable() {
        ProfileTable t = new ProfileTable();
        t.version = "fallback";
        t.models.add(new Entry("deepseek", 1000000, "DeepSeek"));
        t.models.add(new Entry("gpt-4", 8192, "OpenAI"));
        t.models.add(new Entry("qwen", 131072, "阿里云Qwen"));
        t.models.add(new Entry("claude", 200000, "Anthropic"));
        t.models.add(new Entry("gemini", 1048576, "Google"));
        return t;
    }

    /** 解析 JSON 为数据表；格式非法返回 null（调用方回退） */
    private static ProfileTable parseJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            ProfileTable t = new ProfileTable();
            t.version = root.has("version") ? root.get("version").getAsString() : null;
            t.updated = root.has("updated") ? root.get("updated").getAsString() : null;
            t.note = root.has("note") ? root.get("note").getAsString() : null;
            if (root.has("models") && root.get("models").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("models");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    Entry e = new Entry();
                    e.match = o.has("match") ? o.get("match").getAsString().toLowerCase() : null;
                    e.apiUrl = o.has("apiUrl") ? o.get("apiUrl").getAsString().toLowerCase() : null;
                    e.contextWindow = o.has("contextWindow") ? o.get("contextWindow").getAsInt() : 0;
                    e.provider = o.has("provider") ? o.get("provider").getAsString() : null;
                    e.source = o.has("source") ? o.get("source").getAsString() : null;
                    e.date = o.has("date") ? o.get("date").getAsString() : null;
                    if (e.match != null && !e.match.isEmpty() && e.contextWindow > 0) {
                        t.models.add(e);
                    }
                }
            }
            if (t.models.isEmpty()) return null;
            return t;
        } catch (Exception e) {
            AILogger.e(TAG, "Parse profile table failed: " + e.getMessage());
            return null;
        }
    }

    private static String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            AILogger.w(TAG, "Read override file failed: " + e.getMessage());
            return null;
        }
    }

    private static String readAsset(Context context, String fileName) {
        try (InputStream is = context.getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            AILogger.w(TAG, "Read assets profile table failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 按模型名匹配配置。
     * @param apiUrl  API 地址（仅用于服务商标注，不参与窗口判断）
     * @param modelName 模型名
     * @return 匹配结果；未知模型返回保守默认（32K）
     */
    public static ModelProfile match(String apiUrl, String modelName) {
        return match(appContext(), apiUrl, modelName);
    }

    /** 全局 Application Context 兜底（无显式 context 时用于加载数据表） */
    private static android.content.Context appContext() {
        try {
            com.oilquiz.app.SmartQuizApplication app = com.oilquiz.app.SmartQuizApplication.getInstance();
            if (app != null) return app.getApplicationContext();
            com.oilquiz.app.App legacy = com.oilquiz.app.App.getInstance();
            return legacy != null ? legacy.getApplicationContext() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按模型名匹配配置（指定上下文，用于首次加载/更新后显式刷新）。
     * 匹配优先级（双维度）：
     *   1. apiUrl + modelName 都命中（条目带 apiUrl 时，如聚合商限窗场景）
     *   2. 仅 modelName 命中（条目无 apiUrl 的全局兜底）
     *   3. 未知 → 保守 32K
     * @param context 上下文（null 时用内存中已加载的表；未加载则回退默认）
     * @param apiUrl  API 地址（用于端点维度和服务商标注）
     * @param modelName 模型名
     * @return 匹配结果；未知模型返回保守默认（32K）
     */
    public static ModelProfile match(Context context, String apiUrl, String modelName) {
        ProfileTable t = table;
        if (t == null) {
            t = loadTable(context);
            table = t;
        }
        String m = modelName != null ? modelName.toLowerCase() : "";
        String url = apiUrl != null ? apiUrl.toLowerCase() : "";
        if (m.isEmpty()) {
            return new ModelProfile(DEFAULT_UNKNOWN_WINDOW, providerName(apiUrl));
        }
        // 第一遍：apiUrl + modelName 双命中（条目 apiUrl 是端点关键词，URL 包含即命中）
        if (!url.isEmpty()) {
            for (Entry e : t.models) {
                if (e.apiUrl != null && !e.apiUrl.isEmpty()
                        && url.contains(e.apiUrl) && m.contains(e.match)) {
                    return new ModelProfile(e.contextWindow,
                            e.provider != null ? e.provider : providerName(apiUrl));
                }
            }
        }
        // 第二遍：仅 modelName 命中（全局条目；或端点条目但 URL 不匹配时跳过）
        for (Entry e : t.models) {
            if (e.apiUrl != null && !e.apiUrl.isEmpty()) {
                continue; // 带端点的条目只在第一遍匹配
            }
            if (m.contains(e.match)) {
                return new ModelProfile(e.contextWindow,
                        e.provider != null ? e.provider : providerName(apiUrl));
            }
        }
        // 未知模型：默认 128K（当前主流模型普遍 ≥128K；用户可让 Agent 用 update_models_profile 补录精确值）
        return new ModelProfile(DEFAULT_UNKNOWN_WINDOW, providerName(apiUrl));
    }

    // ============ 更新入口（供 Agent 更新数据表） ============

    /**
     * 用新的 JSON 数据表更新配置（Agent 调用）。
     * 校验通过后写入 filesDir/models_profile.json（覆盖 assets 内置表），并刷新内存缓存。
     *
     * @param context 上下文
     * @param json    新数据表 JSON（结构同 assets/models_profile.json）
     * @return true=更新成功；false=格式非法（未做任何修改）
     */
    public static boolean updateFromJson(Context context, String json) {
        ProfileTable t = parseJson(json);
        if (t == null || t.models == null || t.models.isEmpty()) {
            AILogger.w(TAG, "updateFromJson rejected: invalid JSON or empty models");
            return false;
        }
        try {
            File override = new File(context.getFilesDir(), OVERRIDE_FILE);
            try (FileOutputStream fos = new FileOutputStream(override);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            // 持久化到 SharedPreferences（供跨进程/重启快速读取，与文件双保险）
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, json).apply();
            table = t; // 立即生效
            AILogger.i(TAG, "updateFromJson success: version=" + t.version
                    + ", entries=" + t.models.size() + ", file=" + override.getAbsolutePath());
            return true;
        } catch (Exception e) {
            AILogger.e(TAG, "updateFromJson write failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取当前生效数据表的版本号（供 UI/Agent 展示）。
     * @param context 上下文（null 时仅读内存）
     */
    public static String getTableVersion(Context context) {
        ProfileTable t = table;
        if (t == null && context != null) {
            t = loadTable(context);
            table = t;
        }
        return t != null && t.version != null ? t.version : null;
    }

    /**
     * 获取当前生效数据表的条目数。
     */
    public static int getTableSize(Context context) {
        ProfileTable t = table;
        if (t == null && context != null) {
            t = loadTable(context);
            table = t;
        }
        return t != null && t.models != null ? t.models.size() : 0;
    }

    /**
     * 获取当前生效数据表的 JSON（供备份/展示/导出）。
     */
    public static String getTableJson(Context context) {
        ProfileTable t = table;
        if (t == null && context != null) {
            t = loadTable(context);
            table = t;
        }
        return t != null ? new Gson().toJson(t) : null;
    }

    /** 从 API 地址推断服务商显示名（仅展示用），委托 ProviderConfigManager 配置表（urlKeywords 驱动） */
    private static String providerName(String apiUrl) {
        return ProviderConfigManager.get().providerName(apiUrl);
    }
}
