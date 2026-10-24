package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.FileChangeDiff;
import com.oilquiz.app.util.QWeatherIconMapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具结果 → 内置聊天卡片 的结构化桥接层（与 {@link ToolResultInterpreter} 并列，同属"UI 展示链路"）。
 *
 * <p>分工：{@link ToolResultInterpreter#formatForUi} 负责把工具结果压成人读**文本**；
 * 本类负责把同一份结果映射成项目已有的**原生卡片组件**（{@code ComponentData}），
 * 由 {@code AIToolManager.executeTool} 交给 {@code ComponentCollector} 挂进 AI 消息气泡。
 * 二者互不依赖：本类不产出任何文本，也不改变模型可见的工具结果。</p>
 *
 * <p>设计约束（2026-10-13）：
 * <ul>
 *   <li><b>只复用不新建</b>：全部映射目标都是 {@code ComponentRegistry.registerBuiltin()}
 *       已注册的内置类型（weather_card / table_card / list_card / info_card / todo_card /
 *       image_grid / file_card / file_list / video / audio / link_card / progress_card /
 *       metric_card / agenda / diff 等），不新增渲染器。</li>
 *   <li><b>只读工具已有结构</b>：工具的 result 多数本身就是 Map（见各类注释），
 *       本类按既有键名取值；对已被压成 String 的子结构（天气逐日/逐小时/预警）按既有
 *       行格式解析，<b>不改动工具输出</b>，保证模型侧文本与缓存前缀稳定。</li>
 *   <li><b>绝不抛异常影响执行</b>：任何解析失败都返回空列表，工具结果照常返回。</li>
 *   <li><b>可回退</b>：开关 {@code ai_chat_prefs.tool_auto_ui_bridge}（默认开）；
 *       内部预检/环境预取等"非模型主调用"用 {@link #suppress()} 包一层，避免挂重复卡片。</li>
 *   <li><b>只管"挂什么卡"，不管"怎么显示"</b>（2026-10-20 拆分）：本类产出的卡片默认被
 *       {@code ChatAdapter} 整卡摊开，长表格/图集会把正文顶出屏幕。展示档位（整卡 / 一行摘要 /
 *       不挂载）统一由
 *       {@link com.oilquiz.app.ai.chat.component.ComponentDisplay} 决定——
 *       模型可用 {@code display} 声明，用户默认档在 {@code tool_result_display_default}，
 *       本类的名单与 dispatch 只负责选卡片类型，不要再往里加显示策略。</li>
 * </ul>
 * </p>
 */
public final class ToolResultCardBinder {

    private static final String TAG = "ToolResultCardBinder";

    /** 试验开关：与 web_markdown_render 同库同风格，关闭后完全回到纯文本展示 */
    public static final String PREFS_NAME = "ai_chat_prefs";
    public static final String PREF_KEY_ENABLED = "tool_auto_ui_bridge";

    /** 一次调用最多挂几张卡（如 web_render = 链接卡 + 截图），防止长列表刷屏 */
    private static final int MAX_CARDS_PER_CALL = 2;
    /** 表格卡片最多渲染行数 */
    private static final int MAX_TABLE_ROWS = 50;
    /** 列表卡片最多渲染条目 */
    private static final int MAX_LIST_ITEMS = 12;

    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif"));
    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "3gp", "ts"));
    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "wav", "aac", "ogg", "amr", "flac", "opus"));

    /** 产物路径通用探测：绝对路径或工作区相对路径 + 已知扩展名 */
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "(?:/storage/emulated/0/|/data/data/|/sdcard/|[A-Za-z]:\\\\|files/|tmp/)"
                    + "[^\\s'\"`,;()（）\\[\\]{}<>]*?"
                    + "\\.(png|jpe?g|webp|gif|bmp|mp4|mkv|mov|webm|m4a|mp3|wav|flac|ogg|apk)");

    /** 这些工具自己已经 withComponent（或本就产出组件），桥接层不再插手 */
    private static final Set<String> SELF_COMPONENT_TOOLS = new HashSet<>(Arrays.asList(
            "ui_component", "ui_component_plugin", "network_search", "file_reader",
            "image_gen", "dashscope_media", "video_to_player", "layout_editor",
            "control_lookup"));

    /** 纯文本/控制面工具：结构化价值低，挂卡片只会造成噪音 */
    private static final Set<String> SKIP_TOOLS = new HashSet<>(Arrays.asList(
            "time_date", "calculator", "unit_converter", "text_tools", "tool_registry",
            "get_models_profile", "update_models_profile", "create_dynamic_tool",
            "ai_create_tool", "permission_manager", "app_operation", "voice_input",
            "system_connect", "remote_dsh", "pip_install", "workspace",
            "import_start", "import_cancel", "import_decide"));

    /**
     * 没有专属 builder 时，允许做"产物路径探测"的工具白名单。
     * 关键：不能对所有工具开放——database/excel 的结果里可能出现任意文件名文本，
     * 全局探测会给查询结果挂上无意义的文件卡。
     */
    private static final Set<String> ARTIFACT_SCAN_TOOLS = new HashSet<>(Arrays.asList(
            "python_execute", "python_file_ops", "python_analyze_data", "python_calculate",
            "js_execute", "system_resource", "file_generator", "file_analyzer",
            "export_apk", "media_toolkit", "speech_synthesis", "douyin_downloader",
            "screen_capture", "screen_watch", "python_chart"));

    // ==================== 开关与抑制作用域 ====================

    /** 抑制作用域：内部预检/环境预取等非模型主调用用它包住，避免挂出多余卡片 */
    public interface BridgeScope {
        void close();
    }

    private static final ThreadLocal<int[]> SUPPRESS_COUNT = new ThreadLocal<int[]>();

    public static BridgeScope suppress() {
        int[] c = SUPPRESS_COUNT.get();
        if (c == null) {
            c = new int[]{0};
            SUPPRESS_COUNT.set(c);
        }
        c[0]++;
        return new BridgeScope() {
            @Override
            public void close() {
                int[] cur = SUPPRESS_COUNT.get();
                if (cur != null && cur[0] > 0) {
                    cur[0]--;
                }
            }
        };
    }

    public static boolean isSuppressed() {
        int[] c = SUPPRESS_COUNT.get();
        return c != null && c[0] > 0;
    }

    /**
     * 在"抑制自动卡片"的作用域内执行一次调用（同线程生效）。
     * 给内部预检 / 环境预取 / 插件回调桥接这类不该挂卡片的工具调用用，
     * 一行包住即可，不必改动原有的大 try/finally 结构。
     */
    public static <T> T callQuietly(java.util.concurrent.Callable<T> call) throws Exception {
        BridgeScope scope = suppress();
        try {
            return call.call();
        } finally {
            scope.close();
        }
    }

    /** {@link #callQuietly} 的不抛异常版本：内部调用出错时返回 fallback，绝不冒泡影响主流程 */
    public static <T> T callQuietlySafe(java.util.concurrent.Callable<T> call, T fallback) {
        BridgeScope scope = suppress();
        try {
            return call.call();
        } catch (Throwable t) {
            Log.w(TAG, "quiet call failed: " + t.getMessage());
            return fallback;
        } finally {
            scope.close();
        }
    }

    /** 开关：默认开；读不到（异常/Context 为空）时按开处理，保持与既有工具桥接一致的行为 */
    public static boolean isEnabled(Context context) {
        if (context == null) {
            return true;
        }
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 写开关（设置页/调试用） */
    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_KEY_ENABLED, enabled).apply();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 入口 ====================

    /**
     * 把一次工具结果映射成内置卡片。
     *
     * @param toolName 已解析（alias 归一后）的工具名
     * @param result   工具返回结果
     * @param params   调用入参：多个工具（database/excel/ocr 等）把 result 做成 Map、
     *                 而把 action 留在入参里，需要它才能选对卡片
     * @return 卡片列表（可能为空）；永不返回 null，永不抛异常
     */
    public static List<ComponentData> build(Context context, String toolName,
                                             AIToolResult result, Map<String, Object> params) {
        if (isSuppressed() || result == null || !result.isSuccess()
                || result.getComponent() != null || toolName == null) {
            return Collections.emptyList();
        }
        if (SELF_COMPONENT_TOOLS.contains(toolName) || SKIP_TOOLS.contains(toolName)) {
            return Collections.emptyList();
        }
        try {
            List<ComponentData> cards = dispatch(context, toolName, result, params);
            if (!cards.isEmpty()) {
                return cards;
            }
            // 专属映射没命中：白名单工具再试一次"产物路径探测"（覆盖 python_execute 等自由输出）
            if (ARTIFACT_SCAN_TOOLS.contains(toolName)) {
                return scanArtifacts(context, textOf(result));
            }
            return Collections.emptyList();
        } catch (Throwable t) {
            // 桥接失败绝不影响工具结果本身
            Log.w(TAG, "bind card failed for " + toolName + ": " + t.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<ComponentData> dispatch(Context context, String toolName,
                                                 AIToolResult result, Map<String, Object> params)
            throws Exception {
        switch (toolName) {
            case "ai_weather":
                return wrap(weatherCard(result));
            case "location":
                return wrap(locationCard(result));
            case "database":
                return databaseCards(result, params);
            case "excel_tool":
                return excelCards(result, params);
            case "task":
                return wrap(taskCard(result));
            case "reminder":
                return wrap(reminderCard(result));
            case "memory":
                return wrap(memoryCard(result));
            case "chat_history":
                return wrap(chatHistoryCard(result));
            case "knowledge_base":
                return wrap(knowledgeCard(result));
            case "web_render":
                return webRenderCards(result, params);
            case "webpage_reader":
                return wrap(webpageReaderCard(result));
            case "smart_research":
                return wrap(smartResearchCard(result));
            case "import_status":
                return wrap(importStatusCard(result));
            case "import_list_files":
                return wrap(importFilesCard(result));
            case "media_toolkit":
                return mediaToolkitCards(result);
            case "speech_synthesis":
                return wrap(speechCard(result));
            case "douyin_downloader":
                return douyinCards(context, result);
            case "file_generator":
                return fileGeneratorCards(context, result);
            case "python_file_ops":
                return pythonFileOpsCards(result);
            case "file_analyzer":
                return wrap(fileAnalyzerCard(result));
            case "export_apk":
                return wrap(exportApkCard(result));
            case "ocr_recognize":
                return wrap(ocrCard(context, result, params));
            case "python_chart":
                return wrap(pythonChartCard(result));
            case "screen_capture":
            case "screen_watch":
                return wrap(screenCard(result));
            case "app_operation":
                return wrap(appCard(result));
            default:
                return Collections.emptyList();
        }
    }

    private static List<ComponentData> wrap(ComponentData data) {
        if (data == null) {
            return Collections.emptyList();
        }
        List<ComponentData> out = new ArrayList<>(1);
        out.add(data);
        return out;
    }

    // ==================== 天气 ====================

    /**
     * ai_weather：result 是 Map，实时字段带单位（temperature="26°C"、humidity="60%"），
     * 逐日/逐小时/预警在工具侧已被压成换行 String，这里按既有行格式还原成卡片子结构，
     * 不改工具输出。
     */
    private static ComponentData weatherCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null || "error".equalsIgnoreCase(str(m.get("status")))) {
            return null;
        }

        String city = str(m.get("city"));
        String temp = str(m.get("temperature"));
        String text = str(m.get("weather"));

        if (!temp.isEmpty()) {
            // 有实时温度 → 标准天气卡
            JSONObject p = new JSONObject();
            p.put("city", city);
            p.put("temp", temp);
            p.put("text", text);
            p.put("icon", QWeatherIconMapper.getEmojiIcon(str(m.get("icon"))));
            p.put("humidity", str(m.get("humidity")));
            String windDir = str(m.get("wind_direction"));
            String windSpeed = str(m.get("wind_speed"));
            p.put("windDir", windDir);
            p.put("windScale", windSpeed.isEmpty() ? "" : " " + windSpeed);
            JSONArray forecast = parseForecastLines(str(m.get("forecast")));
            if (forecast.length() > 0) {
                p.put("forecast", forecast);
            }
            return ComponentData.of("weather_card", p);
        }

        // 以下分支没有实时温度，天气卡 canRender 不通过 → 换等价的结构卡
        JSONArray forecast = parseForecastLines(str(m.get("forecast")));
        if (forecast.length() > 0) {
            JSONArray headers = new JSONArray();
            headers.put("日期").put("天气").put("最低").put("最高");
            JSONArray rows = new JSONArray();
            for (int i = 0; i < forecast.length(); i++) {
                JSONObject d = forecast.optJSONObject(i);
                if (d == null) continue;
                rows.put(new JSONArray()
                        .put(d.optString("date", ""))
                        .put(d.optString("text", ""))
                        .put(d.optString("tempMin", ""))
                        .put(d.optString("tempMax", "")));
            }
            return tableCard("天气预报" + (city.isEmpty() ? "" : " · " + city), headers, rows);
        }

        JSONArray hourly = parseKVLines(str(m.get("hourly")));
        if (hourly != null) {
            return listCard("逐小时天气" + (city.isEmpty() ? "" : " · " + city), hourly);
        }

        String aqi = str(m.get("aqi"));
        if (!aqi.isEmpty()) {
            JSONArray items = new JSONArray();
            items.put(kv("AQI", aqi));
            putIfNotEmpty(items, "等级", str(m.get("air_level")));
            putIfNotEmpty(items, "首要污染物", str(m.get("primary_pollutant")));
            putIfNotEmpty(items, "PM2.5", str(m.get("pm2.5")));
            putIfNotEmpty(items, "PM10", str(m.get("pm10")));
            putIfNotEmpty(items, "O3", str(m.get("o3")));
            putIfNotEmpty(items, "NO2", str(m.get("no2")));
            putIfNotEmpty(items, "SO2", str(m.get("so2")));
            putIfNotEmpty(items, "CO", str(m.get("co")));
            return infoCard("空气质量" + (city.isEmpty() ? "" : " · " + city), items);
        }

        JSONArray alerts = parseBulletLines(str(m.get("alerts")), "⚠️");
        if (alerts != null) {
            return listCard("气象预警", alerts);
        }

        JSONArray indices = parseKVColonLines(str(m.get("life_indices")), "📍");
        if (indices != null) {
            return listCard("生活指数", indices);
        }
        return null;
    }

    /** "2026-10-13: 晴 18°C~28°C" → weather_card.forecast 子项 */
    private static JSONArray parseForecastLines(String block) throws Exception {
        JSONArray out = new JSONArray();
        if (block == null || block.isEmpty()) {
            return out;
        }
        for (String line : block.split("\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            int colon = l.indexOf(": ");
            if (colon <= 0) continue;
            String date = l.substring(0, colon).trim();
            String rest = l.substring(colon + 2).trim();
            String[] parts = rest.split("\\s+");
            String text = parts.length > 0 ? parts[0] : "";
            String tempMin = "";
            String tempMax = "";
            int tilde = rest.indexOf('~');
            if (tilde > 0) {
                String head = rest.substring(0, tilde).trim();
                String tail = rest.substring(tilde + 1).trim();
                tempMin = head.substring(head.lastIndexOf(' ') + 1).trim();
                int tailSpace = tail.indexOf(' ');
                tempMax = tailSpace > 0 ? tail.substring(0, tailSpace).trim() : tail;
            }
            JSONObject day = new JSONObject();
            day.put("date", shortenDate(date));
            day.put("text", text);
            day.put("icon", emojiByText(text));
            day.put("tempMin", tempMin);
            day.put("tempMax", tempMax);
            out.put(day);
            if (out.length() >= 5) break; // WeatherCardView 也只画前 5 天
        }
        return out;
    }

    private static String shortenDate(String d) {
        if (d == null) return "";
        // 2026-10-13 → 10-13
        return d.length() == 10 && d.charAt(4) == '-' ? d.substring(5) : d;
    }

    /**
     * 现象文本 → emoji。工具只给了文字（forecast 里没有 icon 码），
     * 实时图标继续复用 {@link QWeatherIconMapper#getEmojiIcon(String)}。
     */
    private static String emojiByText(String text) {
        if (text == null || text.isEmpty()) return "☁️";
        if (text.contains("雷")) return "⛈️";
        if (text.contains("雪") || text.contains("冰雹")) return "🌨️";
        if (text.contains("雨")) return "🌧️";
        if (text.contains("雾") || text.contains("霾") || text.contains("沙尘")) return "😷";
        if (text.contains("晴")) return "☀️";
        if (text.contains("阴")) return "☁️";
        if (text.contains("云")) return "⛅";
        if (text.contains("风")) return "🍃";
        return "☁️";
    }

    // ==================== 定位 ====================

    private static ComponentData locationCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null || !"success".equalsIgnoreCase(str(m.get("status")))) {
            return null;
        }
        String address = str(m.get("address"));
        String city = str(m.get("city"));
        String lat = str(m.get("latitude"));
        String lng = str(m.get("longitude"));
        if (address.isEmpty() && city.isEmpty() && lat.isEmpty() && lng.isEmpty()) {
            return null;
        }
        // 2026-10-23：从 info_card 换成 location 卡——地址+坐标结构化，并带"在地图中打开/复制坐标"
        JSONObject p = new JSONObject();
        p.put("title", "当前位置");
        if (!address.isEmpty()) p.put("address", address);
        if (!city.isEmpty()) p.put("city", city);
        if (!lat.isEmpty()) p.put("latitude", lat);
        if (!lng.isEmpty()) p.put("longitude", lng);
        String accuracy = str(m.get("accuracy"));
        if (!accuracy.isEmpty()) p.put("accuracy", formatDouble(accuracy));
        String provider = str(m.get("provider"));
        if (!provider.isEmpty()) p.put("provider", provider);
        return ComponentData.of("location", p);
    }

    // ==================== 表格 → 自动图表（2026-10-23） ====================

    /** 表格类结果：原表格卡 + 能识别出"分类列 + 数值列"时加一张图表卡（见 ChartDataExtractor 的保守规则） */
    private static List<ComponentData> excelCards(AIToolResult result, Map<String, Object> params)
            throws Exception {
        List<ComponentData> out = new ArrayList<>();
        ComponentData table = excelCard(result, params);
        if (table != null) out.add(table);
        Map<String, Object> m = asMap(result.getResult());
        if (m != null && out.size() < MAX_CARDS_PER_CALL) {
            JSONObject chart = com.oilquiz.app.ai.chat.component.ChartDataExtractor
                    .fromTable(asList(m.get("headers")), asList(m.get("rows")));
            if (chart != null) out.add(ComponentData.of("chart", chart));
        }
        return out;
    }

    private static List<ComponentData> databaseCards(AIToolResult result, Map<String, Object> params)
            throws Exception {
        List<ComponentData> out = new ArrayList<>();
        ComponentData card = databaseCard(result, params);
        if (card != null) out.add(card);
        Map<String, Object> m = asMap(result.getResult());
        if (m != null && out.size() < MAX_CARDS_PER_CALL) {
            JSONObject chart = com.oilquiz.app.ai.chat.component.ChartDataExtractor
                    .fromTable(asList(m.get("columns")), asList(m.get("rows")));
            if (chart != null) out.add(ComponentData.of("chart", chart));
        }
        return out;
    }

    // ==================== 数据库 ====================

    /** database：result 多为 Map；execute_sql 已是 columns+rows，直接进表格卡 */
    private static ComponentData databaseCard(AIToolResult result, Map<String, Object> params)
            throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        String action = firstNonEmpty(str(m.get("action")), str(mapGet(params, "action")));

        List<?> columns = asList(m.get("columns"));
        List<?> rows = asList(m.get("rows"));
        if (columns != null && !columns.isEmpty() && rows != null) {
            JSONArray headers = new JSONArray();
            for (Object c : columns) {
                headers.put(String.valueOf(c));
            }
            return tableCard("查询结果 · " + rows.size() + " 行", headers,
                    rowsOfMaps(rows, headers, null));
        }

        List<?> tables = asList(m.get("tables"));
        if (tables != null && !tables.isEmpty()) {
            JSONArray items = new JSONArray();
            for (Object t : tables) {
                items.put(item("🗄️", String.valueOf(t), null, null));
            }
            return listCard("数据表 · " + tables.size() + " 张", items);
        }

        List<?> questions = asList(m.get("questions"));
        if (questions != null && !questions.isEmpty()) {
            JSONArray items = new JSONArray();
            for (Object q : questions) {
                Map<String, Object> qm = asMap(q);
                if (qm == null) continue;
                String stem = str(qm.get("questionText"));
                String opt = optionsSummary(qm);
                items.put(item("📝", truncate(stem, 60),
                        joinNonEmpty(" · ", str(qm.get("category")), str(qm.get("difficulty")),
                                str(qm.get("questionType"))),
                        firstNonEmpty(str(qm.get("correctAnswer")), opt)));
                if (items.length() >= MAX_LIST_ITEMS) break;
            }
            return listCard("题目 · " + questions.size() + " 条", items);
        }

        // 表结构：columns 是 List<Map{cid,name,type,notnull,default_value,pk}>
        if ("get_table_schema".equals(action) && columns != null) {
            JSONArray headers = new JSONArray();
            headers.put("列名").put("类型").put("非空").put("默认值").put("主键");
            JSONArray rowsArr = new JSONArray();
            for (Object c : columns) {
                Map<String, Object> cm = asMap(c);
                if (cm == null) continue;
                rowsArr.put(new JSONArray()
                        .put(str(cm.get("name")))
                        .put(str(cm.get("type")))
                        .put("1".equals(str(cm.get("notnull"))) ? "是" : "否")
                        .put(str(cm.get("default_value")))
                        .put("1".equals(str(cm.get("pk"))) ? "是" : "否"));
            }
            return tableCard("表结构 · " + str(m.get("table_name")), headers, rowsArr);
        }

        if (m.containsKey("totalQuestions")) {
            JSONArray metrics = new JSONArray();
            metrics.put(metric("总题数", str(m.get("totalQuestions")), "accent"));
            metrics.put(metric("简单", str(m.get("easyQuestions")), "success"));
            metrics.put(metric("中等", str(m.get("mediumQuestions")), "warning"));
            metrics.put(metric("困难", str(m.get("hardQuestions")), "error"));
            return ComponentData.of("metric_card", new JSONObject()
                    .put("title", "题库统计").put("metrics", metrics));
        }
        return null;
    }

    private static String optionsSummary(Map<String, Object> qm) {
        StringBuilder sb = new StringBuilder();
        String[] keys = {"optionA", "optionB", "optionC", "optionD", "optionE", "optionF"};
        char label = 'A';
        for (String k : keys) {
            String v = str(qm.get(k));
            if (v.isEmpty()) {
                label++;
                continue;
            }
            if (sb.length() > 0) sb.append(" ");
            sb.append(label).append('.').append(truncate(v, 12));
            label++;
        }
        return sb.toString();
    }

    // ==================== Excel ====================

    private static ComponentData excelCard(AIToolResult result, Map<String, Object> params)
            throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        List<?> headers = asList(m.get("headers"));
        List<?> rows = asList(m.get("rows"));
        if (headers != null && !headers.isEmpty() && rows != null) {
            JSONArray h = new JSONArray();
            for (Object c : headers) h.put(String.valueOf(c));
            String title = "工作表 · " + str(m.get("fileName"))
                    + (str(m.get("sheet")).isEmpty() ? "" : " · " + str(m.get("sheet")));
            Object match = m.get("matchCount");
            if (match != null) {
                title += "（命中 " + rows.size() + "/" + match + "）";
            }
            return tableCard(title, h, rowsOfMaps(rows, h, "row"));
        }

        List<?> sheets = asList(m.get("sheets"));
        if (sheets != null && !sheets.isEmpty()) {
            JSONArray items = new JSONArray();
            for (Object s : sheets) {
                Map<String, Object> sm = asMap(s);
                if (sm == null) continue;
                items.put(item("📄", str(sm.get("name")),
                        str(sm.get("rows")) + " 行 × " + str(sm.get("columns")) + " 列", null));
            }
            return listCard("工作表 · " + str(m.get("fileName")), items);
        }

        String value = str(m.get("value"));
        if (!value.isEmpty()) {
            JSONArray items = new JSONArray();
            items.put(kv("单元格", firstNonEmpty(str(m.get("cell")),
                    str(m.get("row")) + "!" + str(m.get("column")))));
            items.put(kv("内容", truncate(value, 200)));
            return infoCard(str(m.get("fileName")), items);
        }
        return null;
    }

    // ==================== 任务 / 提醒 / 记忆 ====================

    private static ComponentData taskCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        List<?> tasks = asList(m.get("tasks"));
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        JSONArray items = new JSONArray();
        for (Object t : tasks) {
            Map<String, Object> tm = asMap(t);
            if (tm == null) continue;
            String status = str(tm.get("status"));
            boolean done = "completed".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status);
            String text = str(tm.get("description"));
            String progress = str(tm.get("progress"));
            if (!done && !progress.isEmpty() && !"0".equals(progress)) {
                text += "（" + progress + "%）";
            } else if (!done && !status.isEmpty()) {
                text += "（" + statusLabel(status) + "）";
            }
            items.put(new JSONObject().put("done", done).put("text", text));
            if (items.length() >= MAX_TABLE_ROWS) break;
        }
        JSONObject p = new JSONObject();
        p.put("title", "任务清单 · 进行中 " + intOrZero(m.get("activeCount")) + "/" + tasks.size());
        p.put("items", items);
        return ComponentData.of("todo_card", p);
    }

    private static String statusLabel(String status) {
        if ("in_progress".equalsIgnoreCase(status)) return "进行中";
        if ("completed".equalsIgnoreCase(status)) return "已完成";
        if ("failed".equalsIgnoreCase(status)) return "失败";
        if ("todo".equalsIgnoreCase(status)) return "待办";
        return status;
    }

    private static ComponentData reminderCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        // 2026-10-24：reminder 在 additionalInfo 里附带现成的日程条目（UI-only），
        // 优先出 agenda；拿不到时才退回原来的 list_card。
        ComponentData agenda = agendaCard(asMap(result.getAdditionalInfo()));
        if (agenda != null) {
            return agenda;
        }
        List<?> reminders = asList(m.get("reminders"));
        if (reminders == null || reminders.isEmpty()) {
            return null;
        }
        JSONArray items = new JSONArray();
        for (Object r : reminders) {
            Map<String, Object> rm = asMap(r);
            if (rm == null) continue;
            items.put(item("⏰", str(rm.get("content")),
                    str(rm.get("fireAt")) + repeatSuffix(str(rm.get("repeat"))),
                    str(rm.get("id"))));
            if (items.length() >= MAX_LIST_ITEMS) break;
        }
        return listCard("提醒 · " + reminders.size() + " 条", items);
    }

    private static String repeatSuffix(String repeat) {
        if (repeat == null || repeat.isEmpty() || "none".equalsIgnoreCase(repeat)) return "";
        if ("daily".equalsIgnoreCase(repeat)) return " · 每日重复";
        if ("weekly".equalsIgnoreCase(repeat)) return " · 每周重复";
        return " · " + repeat;
    }

    /** additionalInfo.agenda_items → agenda 卡；条目形状与 {@code AgendaCardView} 文档一致 */
    private static ComponentData agendaCard(Map<String, Object> info) throws Exception {
        if (info == null) {
            return null;
        }
        List<?> source = asList(info.get("agenda_items"));
        if (source == null || source.isEmpty()) {
            return null;
        }
        JSONArray items = new JSONArray();
        for (Object o : source) {
            Map<String, Object> im = asMap(o);
            if (im == null) continue;
            JSONObject item = new JSONObject();
            putIfNotEmpty(item, "date", str(im.get("date")));
            putIfNotEmpty(item, "time", str(im.get("time")));
            putIfNotEmpty(item, "title", str(im.get("title")));
            putIfNotEmpty(item, "desc", str(im.get("desc")));
            putIfNotEmpty(item, "status", str(im.get("status")));
            if (item.length() > 0) {
                items.put(item);
            }
            if (items.length() >= MAX_LIST_ITEMS) break;
        }
        if (items.length() == 0) {
            return null;
        }
        JSONObject props = new JSONObject();
        props.put("title", firstNonEmpty(str(info.get("agenda_title")), "日程"));
        props.put("items", items);
        return ComponentData.of("agenda", props);
    }

    private static ComponentData memoryCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        List<?> memories = asList(m.get("memories"));
        if (memories != null && !memories.isEmpty()) {
            JSONArray items = new JSONArray();
            for (Object me : memories) {
                Map<String, Object> mm = asMap(me);
                if (mm == null) continue;
                items.put(item("🧠", str(mm.get("key")), truncate(str(mm.get("value")), 120),
                        str(mm.get("category"))));
                if (items.length() >= MAX_LIST_ITEMS) break;
            }
            return listCard("记忆 · " + memories.size() + " 条", items);
        }
        if (Boolean.TRUE.equals(m.get("found")) || "true".equalsIgnoreCase(str(m.get("found")))) {
            JSONArray items = new JSONArray();
            items.put(kv("键", str(m.get("key"))));
            items.put(kv("内容", truncate(str(m.get("value")), 400)));
            putIfNotEmpty(items, "分类", str(m.get("category")));
            return infoCard("记忆命中", items);
        }
        return null;
    }

    // ==================== 对话历史 / 知识库 ====================

    /** ChatHistoryTool 的行格式固定："[序号] 角色: 内容"，内容自身可能含换行 → 续行并入上一条 */
    private static final Pattern CHAT_LINE = Pattern.compile(
            "^\\[(\\d+)\\]\\s*(用户|AI|助手|工具|系统|Agent):\\s*(.*)$");

    private static ComponentData chatHistoryCard(AIToolResult result) throws Exception {
        Object r = result.getResult();
        if (!(r instanceof String)) {
            return null;
        }
        JSONArray items = new JSONArray();
        String seq = null;
        String role = null;
        StringBuilder body = new StringBuilder();
        for (String line : ((String) r).split("\\n")) {
            Matcher mt = CHAT_LINE.matcher(line.trim());
            if (mt.matches()) {
                if (role != null) {
                    items.put(historyItem(seq, role, body.toString()));
                }
                seq = mt.group(1);
                role = mt.group(2);
                body.setLength(0);
                body.append(mt.group(3));
            } else if (role != null && !line.trim().isEmpty()) {
                body.append(' ').append(line.trim());
            }
            if (items.length() >= MAX_LIST_ITEMS) break;
        }
        if (role != null && items.length() < MAX_LIST_ITEMS) {
            items.put(historyItem(seq, role, body.toString()));
        }
        if (items.length() == 0) {
            return null;
        }
        return listCard("对话历史 · 最近 " + items.length() + " 条", items);
    }

    private static JSONObject historyItem(String seq, String role, String body) throws Exception {
        String icon = "用户".equals(role) ? "🙎" : "工具".equals(role) ? "🔧"
                : "系统".equals(role) ? "⚙️" : "🤖";
        return item(icon, role + " #" + seq, truncate(body, 160), null);
    }

    /**
     * knowledge_base：search 的 result 是条目 JSON 数组（id/title/category/keywords/content/score），
     * stats/其它 是 JSON 对象 → 分别进列表卡与信息卡。
     */
    private static ComponentData knowledgeCard(AIToolResult result) throws Exception {
        Object r = result.getResult();
        if (!(r instanceof String)) {
            return null;
        }
        String s = ((String) r).trim();
        if (s.startsWith("[")) {
            JSONArray arr = new JSONArray(s);
            JSONArray items = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String score = o.has("score") ? o.optString("score", "") : "";
                items.put(item("📚", firstNonEmpty(o.optString("title", ""),
                                truncate(o.optString("content", ""), 40)),
                        truncate(o.optString("content", ""), 140),
                        joinNonEmpty(" · ", o.optString("category", ""),
                                score.isEmpty() ? "" : "匹配 " + score)));
                if (items.length() >= MAX_LIST_ITEMS) break;
            }
            return items.length() > 0 ? listCard("知识库命中 · " + arr.length() + " 条", items) : null;
        }
        if (s.startsWith("{")) {
            JSONObject o = ComponentData.parseJsonObject(s);
            if (o == null) {
                return null;
            }
            JSONArray items = new JSONArray();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String key = it.next();
                Object v = o.opt(key);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    continue; // 嵌套结构交给文本展示
                }
                String text = String.valueOf(v);
                if (text.isEmpty() || "message".equals(key) || "error".equals(key)) {
                    continue;
                }
                items.put(kv(zhKey(key), truncate(text, 160)));
                if (items.length() >= MAX_LIST_ITEMS) break;
            }
            return items.length() > 0 ? infoCard("知识库", items) : null;
        }
        return null;
    }

    /** JSON 键名 → 中文标签（认不出来的原样返回，不做有损猜测） */
    private static String zhKey(String key) {
        if (key == null || key.isEmpty()) return "";
        switch (key) {
            case "total": return "总条数";
            case "count": return "条数";
            case "hits": return "命中数";
            case "categories": return "分类数";
            case "keywords": return "关键词";
            case "category": return "分类";
            case "with_content": return "含正文";
            case "updated_at": return "更新时间";
            case "id": return "编号";
            case "title": return "标题";
            case "status": return "状态";
            default: return key;
        }
    }

    // ==================== 网页类 ====================

    private static List<ComponentData> webRenderCards(AIToolResult result, Map<String, Object> params)
            throws Exception {
        List<ComponentData> out = new ArrayList<>(2);
        Map<String, Object> info = asMap(result.getAdditionalInfo());
        String url = info != null ? str(info.get("url")) : "";
        String title = info != null ? str(info.get("title")) : "";
        String text = info != null ? str(info.get("text")) : "";
        String shot = info != null ? str(info.get("screenshot")) : "";
        if (url.isEmpty() && params != null) {
            url = str(mapGet(params, "url"));
        }
        if (!url.isEmpty()) {
            JSONObject link = new JSONObject();
            link.put("url", url);
            link.put("title", firstNonEmpty(title, url));
            link.put("description", truncate(text, 160));
            out.add(ComponentData.of("link_card", link));
        }
        if (!shot.isEmpty()) {
            JSONObject grid = new JSONObject();
            grid.put("columns", 1);
            grid.put("images", new JSONArray().put(shot));
            out.add(ComponentData.of("image_grid", grid));
        }
        return out.size() > MAX_CARDS_PER_CALL ? out.subList(0, MAX_CARDS_PER_CALL) : out;
    }

    private static ComponentData webpageReaderCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        // read / extract：单页 → 链接卡
        Map<String, Object> content = asMap(m.get("content"));
        Map<String, Object> extracted = asMap(m.get("extracted"));
        Map<String, Object> page = content != null ? content : extracted;
        if (page != null) {
            String url = str(page.get("url")).isEmpty() ? str(m.get("url")) : str(page.get("url"));
            if (url.isEmpty()) {
                return null;
            }
            return ComponentData.of("link_card", new JSONObject()
                    .put("url", url)
                    .put("title", firstNonEmpty(str(page.get("title")), url))
                    .put("description", truncate(firstNonEmpty(str(page.get("summary")),
                            str(page.get("description"))), 200)));
        }
        // read_multiple / follow_links：多页 → 列表卡
        List<?> results = asList(m.get("results"));
        if (results != null && !results.isEmpty()) {
            JSONArray items = new JSONArray();
            for (Object r : results) {
                Map<String, Object> rm = asMap(r);
                if (rm == null) continue;
                Map<String, Object> rc = asMap(rm.get("content"));
                String title = rc != null ? str(rc.get("title")) : str(rm.get("title"));
                String summary = rc != null ? str(rc.get("summary")) : str(rm.get("summary"));
                items.put(item("🔗", firstNonEmpty(title, str(rm.get("url"))),
                        truncate(summary, 120), null, str(rm.get("url"))));
                if (items.length() >= MAX_LIST_ITEMS) break;
            }
            return listCard("网页读取 · " + results.size() + " 页", items);
        }
        String summary = str(m.get("summary"));
        if (!summary.isEmpty() && !str(m.get("url")).isEmpty()) {
            return ComponentData.of("link_card", new JSONObject()
                    .put("url", str(m.get("url")))
                    .put("title", firstNonEmpty(str(m.get("title")), str(m.get("url"))))
                    .put("description", truncate(summary, 200)));
        }
        return null;
    }

    private static ComponentData smartResearchCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        List<?> sources = asList(m.get("detailedContents"));
        if (sources == null) {
            sources = asList(m.get("searchResults"));
        }
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        JSONArray items = new JSONArray();
        for (Object s : sources) {
            Map<String, Object> sm = asMap(s);
            if (sm == null) {
                items.put(item("🔍", truncate(String.valueOf(s), 80), null, null));
                continue;
            }
            items.put(item("🔍", firstNonEmpty(str(sm.get("title")), str(sm.get("url"))),
                    truncate(firstNonEmpty(str(sm.get("summary")), str(sm.get("category"))), 120),
                    null, str(sm.get("url"))));
            if (items.length() >= MAX_LIST_ITEMS) break;
        }
        return listCard("研究来源 · " + sources.size() + " 条", items);
    }

    // ==================== 导入流程 ====================

    private static ComponentData importStatusCard(AIToolResult result) throws Exception {
        JSONObject o = jsonOf(result.getResult());
        if (o == null || !o.has("status")) {
            return null;
        }
        long current = o.optLong("current", 0);
        long total = o.optLong("total", 0);
        int progress = total > 0 ? (int) Math.max(0, Math.min(100, current * 100 / total))
                : ("DONE".equalsIgnoreCase(o.optString("status", "")) ? 100 : 0);
        StringBuilder desc = new StringBuilder();
        String message = o.optString("message", "");
        if (!message.isEmpty()) {
            desc.append(message);
        }
        if (o.has("imported") || o.has("duplicated") || o.has("failed")) {
            if (desc.length() > 0) desc.append("　");
            desc.append("导入 ").append(o.optInt("imported", 0))
                    .append(" · 重复 ").append(o.optInt("duplicated", 0))
                    .append(" · 失败 ").append(o.optInt("failed", 0));
        }
        JSONObject p = new JSONObject();
        p.put("title", "题库导入 · " + o.optString("status", "RUNNING"));
        p.put("progress", progress);
        p.put("description", desc.toString());
        p.put("status", o.optString("status", "RUNNING").toLowerCase());
        return ComponentData.of("progress_card", p);
    }

    private static ComponentData importFilesCard(AIToolResult result) throws Exception {
        JSONObject o = jsonOf(result.getResult());
        JSONArray files = o != null ? o.optJSONArray("files") : null;
        if (files == null || files.length() == 0) {
            return null;
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < files.length() && i < MAX_LIST_ITEMS; i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            arr.put(new JSONObject()
                    .put("name", f.optString("name", ""))
                    .put("path", f.optString("path", ""))
                    .put("size", f.optString("size", ""))
                    .put("type", f.optString("type", "file")));
        }
        JSONObject p = new JSONObject();
        p.put("title", "待导入文件 · " + files.length() + " 个");
        p.put("files", arr);
        return ComponentData.of("file_list", p);
    }

    // ==================== 媒体 / 语音 / 下载 ====================

    /**
     * media_toolkit 的 result 是 JSON 字符串：单产物用 file 键，多帧/批处理用 files 数组。
     * 按扩展名分流到原生卡：图片→image_grid、视频→video(ExoPlayer)、音频→audio(ExoPlayer)、
     * 其他→file_card/file_list；不再把所有多文件都塞进 image_grid。
     */
    static List<ComponentData> mediaToolkitCards(AIToolResult result) throws Exception {
        JSONObject o = jsonOf(result.getResult());
        if (o == null) return Collections.emptyList();
        List<String> images = new ArrayList<>();
        List<String> videos = new ArrayList<>();
        List<String> audios = new ArrayList<>();
        List<String> others = new ArrayList<>();
        JSONArray files = o.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length() && i < 12; i++) {
                Object raw = files.opt(i);
                String path = "";
                if (raw instanceof JSONObject) {
                    path = ((JSONObject) raw).optString("file", "");
                } else if (raw instanceof String) {
                    path = (String) raw;
                }
                if (path.isEmpty()) continue;
                String ext = extensionOf(path);
                if (IMAGE_EXT.contains(ext)) images.add(path);
                else if (VIDEO_EXT.contains(ext)) videos.add(path);
                else if (AUDIO_EXT.contains(ext)) audios.add(path);
                else others.add(path);
            }
        }
        String single = o.optString("file", "");
        if (single.isEmpty() && files != null && files.length() == 1) {
            JSONObject f = files.optJSONObject(0);
            single = f != null ? f.optString("file", "") : "";
        }
        if (images.isEmpty() && videos.isEmpty() && audios.isEmpty() && others.isEmpty()
                && !single.isEmpty()) {
            ComponentData one = mediaCard(single, o.optString("kind", ""));
            return one == null ? Collections.emptyList() : Collections.singletonList(one);
        }

        List<ComponentData> out = new ArrayList<>();
        if (!images.isEmpty()) {
            JSONArray urls = new JSONArray();
            for (String p : images) {
                urls.put(p);
                if (urls.length() >= 6) break;
            }
            out.add(ComponentData.of("image_grid", new JSONObject()
                    .put("columns", Math.min(3, urls.length())).put("images", urls)));
        }
        for (String p : videos) {
            if (out.size() >= MAX_CARDS_PER_CALL) break;
            out.add(ComponentData.of("video", new JSONObject()
                    .put("url", p).put("title", fileNameOf(p))));
        }
        if (audios.size() == 1 && out.size() < MAX_CARDS_PER_CALL) {
            String p = audios.get(0);
            out.add(ComponentData.of("audio", new JSONObject()
                    .put("url", p).put("title", fileNameOf(p))));
        } else if (audios.size() > 1 && out.size() < MAX_CARDS_PER_CALL) {
            // 多个音频不再堆 N 张独立 audio 卡：同一批产物交给播放列表卡（队列 + 上下首），
            // 播放器是进程级单例，队列语义由 AudioPlaybackManager.playQueue 提供
            JSONArray arr = new JSONArray();
            for (String p : audios) {
                if (arr.length() >= 30) break;
                arr.put(new JSONObject().put("name", fileNameOf(p)).put("url", p)
                        .put("size", sizeText(p)));
            }
            out.add(ComponentData.of("playlist", new JSONObject()
                    .put("title", "播放列表 · " + audios.size() + " 首").put("items", arr)));
        }
        if (!others.isEmpty() && out.size() < MAX_CARDS_PER_CALL) {
            if (others.size() == 1) {
                ComponentData card = mediaCard(others.get(0), "");
                if (card != null) out.add(card);
            } else {
                JSONArray arr = new JSONArray();
                for (String p : others) {
                    arr.put(new JSONObject().put("name", fileNameOf(p)).put("path", p)
                            .put("size", sizeText(p)).put("type", extensionOf(p)));
                }
                out.add(ComponentData.of("file_list", new JSONObject()
                        .put("title", "媒体产物 · " + others.size() + " 个").put("files", arr)));
            }
        }
        return out.size() > MAX_CARDS_PER_CALL ? out.subList(0, MAX_CARDS_PER_CALL) : out;
    }

    private static ComponentData speechCard(AIToolResult result) throws Exception {
        Map<String, Object> info = asMap(result.getAdditionalInfo());
        String path = info != null ? str(info.get("file_path")) : "";
        return path.isEmpty() ? null : mediaCard(path, "audio");
    }

    private static List<ComponentData> douyinCards(Context context, AIToolResult result)
            throws Exception {
        List<ComponentData> out = new ArrayList<>(2);
        Map<String, Object> info = asMap(result.getAdditionalInfo());
        if (info == null) {
            return out;
        }
        Map<String, Object> parsed = asMap(info.get("parsed"));
        if (parsed == null) {
            return out;
        }
        // 官方内核：download.dir + files[{type,file,size}]
        Map<String, Object> download = asMap(parsed.get("download"));
        if (download != null) {
            String dir = str(download.get("dir"));
            List<?> files = asList(download.get("files"));
            if (files != null) {
                for (Object f : files) {
                    Map<String, Object> fm = asMap(f);
                    if (fm == null) continue;
                    String path = joinPath(dir, str(fm.get("file")));
                    ComponentData card = mediaCard(path, "");
                    if (card != null) {
                        out.add(card);
                        if (out.size() >= MAX_CARDS_PER_CALL) {
                            return out;
                        }
                    }
                }
            }
        }
        // 第三方通道：files 是 dict（video/bgm/cover/images），值为工作区相对路径
        Map<String, Object> files = asMap(info.get("files"));
        if (files != null && out.isEmpty()) {
            String video = str(files.get("video"));
            if (!video.isEmpty()) {
                ComponentData card = mediaCard(resolvePath(context, video), "video");
                if (card != null) out.add(card);
            }
            List<?> images = asList(files.get("images"));
            if (images != null && !images.isEmpty() && out.size() < MAX_CARDS_PER_CALL) {
                JSONArray urls = new JSONArray();
                for (Object im : images) {
                    String p = resolvePath(context, str(im));
                    if (!p.isEmpty()) urls.put(p);
                    if (urls.length() >= 6) break;
                }
                if (urls.length() > 0) {
                    out.add(ComponentData.of("image_grid", new JSONObject()
                            .put("columns", 2).put("images", urls)));
                }
            }
        }
        return out;
    }

    /** 按扩展名/提示词挑卡片：image_grid / video / audio / file_card */
    private static ComponentData mediaCard(String path, String kindHint) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String p = path.trim();
        String ext = extensionOf(p);
        String kind = kindHint == null ? "" : kindHint.toLowerCase();
        String name = fileNameOf(p);
        String size = sizeText(p);

        if (IMAGE_EXT.contains(ext) || "image".equals(kind) || "img".equals(kind)) {
            return ComponentData.of("image_grid", new JSONObject()
                    .put("columns", 1).put("images", new JSONArray().put(p)));
        }
        if (VIDEO_EXT.contains(ext) || "video".equals(kind)) {
            return ComponentData.of("video", new JSONObject().put("url", p).put("title", name));
        }
        if (AUDIO_EXT.contains(ext) || "audio".equals(kind)) {
            return ComponentData.of("audio", new JSONObject().put("url", p).put("title", name));
        }
        if (name.isEmpty()) {
            return null;
        }
        return ComponentData.of("file_card", new JSONObject()
                .put("name", name).put("path", p)
                .put("size", size).put("type", ext));
    }

    // ==================== 文件类 ====================

    /** file_generator：文件卡 + （覆盖/追加时）变更对比卡，最多 2 张 */
    private static List<ComponentData> fileGeneratorCards(Context context, AIToolResult result)
            throws Exception {
        List<ComponentData> out = new ArrayList<>(2);
        ComponentData file = fileGeneratorCard(context, result);
        if (file != null) out.add(file);
        ComponentData diff = diffCard(result);
        if (diff != null && out.size() < MAX_CARDS_PER_CALL) out.add(diff);
        return out;
    }

    /** python_file_ops：结果本身是 stdout 文本，这里只补 UI 侧的 diff 卡 */
    private static List<ComponentData> pythonFileOpsCards(AIToolResult result) throws Exception {
        ComponentData diff = diffCard(result);
        return diff == null ? Collections.emptyList() : Collections.singletonList(diff);
    }

    /** additionalInfo.diff（FileChangeDiff 写入的 before/after）→ diff 卡 */
    private static ComponentData diffCard(AIToolResult result) throws Exception {
        Map<String, Object> info = asMap(result.getAdditionalInfo());
        if (info == null) return null;
        Map<String, Object> diff = asMap(info.get(FileChangeDiff.INFO_KEY));
        if (diff == null) return null;
        String before = str(diff.get("before"));
        String after = str(diff.get("after"));
        if (before.equals(after)) return null;
        JSONObject p = new JSONObject();
        p.put("title", firstNonEmpty(str(diff.get("title")), "文件变更"));
        putIfNotEmpty(p, "path", str(diff.get("path")));
        p.put("before", before);
        p.put("after", after);
        return ComponentData.of("diff", p);
    }

    private static ComponentData fileGeneratorCard(Context context, AIToolResult result)
            throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null) {
            return null;
        }
        String path = str(m.get("filePath"));
        if (path.isEmpty()) {
            return null;
        }
        JSONObject p = new JSONObject();
        p.put("name", fileNameOf(path));
        p.put("path", resolvePath(context, path));
        p.put("type", extensionOf(path));
        p.put("size", sizeText(path));
        String uri = str(m.get("contentUri"));
        if (!uri.isEmpty()) {
            p.put("uri", uri);
        }
        return ComponentData.of("file_card", p);
    }

    private static ComponentData fileAnalyzerCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        Map<String, Object> analysis = m != null ? asMap(m.get("analysis")) : null;
        if (analysis == null) {
            return null;
        }
        Map<String, Object> fileInfo = asMap(analysis.get("fileInfo"));
        if (fileInfo != null && !str(fileInfo.get("path")).isEmpty()) {
            JSONObject p = new JSONObject();
            p.put("name", firstNonEmpty(str(fileInfo.get("name")),
                    fileNameOf(str(fileInfo.get("path")))));
            p.put("path", str(fileInfo.get("path")));
            p.put("type", str(fileInfo.get("extension")));
            p.put("size", bytesText(analysisSizeBytes(fileInfo)));
            return ComponentData.of("file_card", p);
        }
        // 目录模式：files 列表
        List<?> filess = asList(analysis.get("files"));
        if (filess != null && !filess.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Object f : filess) {
                Map<String, Object> fm = asMap(f);
                if (fm == null) continue;
                arr.put(new JSONObject()
                        .put("name", str(fm.get("name")))
                        .put("path", str(fm.get("path")))
                        .put("size", bytesText(toLong(fm.get("size"))))
                        .put("type", Boolean.TRUE.equals(fm.get("isFile"))
                                || "true".equalsIgnoreCase(str(fm.get("isFile")))
                                ? "file" : "dir"));
                if (arr.length() >= MAX_LIST_ITEMS) break;
            }
            JSONObject p = new JSONObject();
            p.put("title", "目录内容 · " + filess.size() + " 项");
            p.put("files", arr);
            return ComponentData.of("file_list", p);
        }
        return null;
    }

    private static long analysisSizeBytes(Map<String, Object> fileInfo) {
        Object size = fileInfo.get("size");
        if (size instanceof Number) {
            return ((Number) size).longValue();
        }
        String s = str(size);
        // "1.2 MB" 之类文本已可直接展示，交给 sizeText 处理
        return s.matches("\\d+") ? Long.parseLong(s) : -1;
    }

    private static ComponentData exportApkCard(AIToolResult result) throws Exception {
        Map<String, Object> info = asMap(result.getAdditionalInfo());
        String path = info != null ? str(info.get("apk_path")) : "";
        if (path.isEmpty()) {
            return null;
        }
        JSONObject p = new JSONObject();
        p.put("name", firstNonEmpty(info != null ? str(info.get("apk_name")) : "",
                fileNameOf(path)));
        p.put("path", path);
        p.put("type", "apk");
        p.put("size", info != null && !str(info.get("apk_size_mb")).isEmpty()
                ? str(info.get("apk_size_mb")) + " MB" : sizeText(path));
        return ComponentData.of("file_card", p);
    }

    private static ComponentData ocrCard(Context context, AIToolResult result,
                                         Map<String, Object> params) throws Exception {
        Map<String, Object> p = asMap(result.getAdditionalInfo());
        if (p == null) {
            p = params;
        }
        String image = p != null ? firstNonEmpty(str(mapGet(p, "image_path")),
                str(mapGet(p, "file_path"))) : "";
        if (image.isEmpty()) {
            return null;
        }
        String resolved = resolvePath(context, image);
        String ext = extensionOf(resolved);
        if (!IMAGE_EXT.contains(ext)) {
            return null; // PDF/文档类不适合图卡，保持原本文本展示
        }
        return ComponentData.of("image_grid", new JSONObject()
                .put("columns", 1).put("images", new JSONArray().put(resolved)));
    }

    private static ComponentData pythonChartCard(AIToolResult result) throws Exception {
        // render=card：图表数据走 additionalInfo（不占模型上下文），这里出原生图表卡
        Map<String, Object> info = asMap(result.getAdditionalInfo());
        Object chartObj = info != null ? info.get("chart") : null;
        if (chartObj == null) {
            Map<String, Object> m = asMap(result.getResult());
            chartObj = m != null ? m.get("chart") : null;
        }
        if (chartObj != null) {
            JSONObject chart = chartObj instanceof JSONObject ? (JSONObject) chartObj
                    : new JSONObject(String.valueOf(chartObj));
            if (chart.optString("title", "").isEmpty()) chart.put("title", "数据图表");
            return ComponentData.of("chart", chart);
        }
        String stdout = info != null ? str(info.get("stdout")) : textOf(result);
        Matcher mt = Pattern.compile("图片已保存:\\s*(\\S+)").matcher(stdout);
        if (mt.find()) {
            return ComponentData.of("image_grid", new JSONObject()
                    .put("columns", 1).put("images", new JSONArray().put(mt.group(1))));
        }
        return null;
    }

    /** screen_capture / screen_watch：info.path 单帧，info.frames 多帧（绝对路径 List<String>） */
    private static ComponentData screenCard(AIToolResult result) throws Exception {
        Map<String, Object> info = asMap(result.getAdditionalInfo());
        if (info == null) {
            return null;
        }
        List<?> frames = asList(info.get("frames"));
        if (frames != null && frames.size() > 1) {
            JSONArray urls = new JSONArray();
            for (Object f : frames) {
                String p = str(f);
                if (!p.isEmpty()) urls.put(p);
                if (urls.length() >= 4) break;
            }
            if (urls.length() > 0) {
                return ComponentData.of("image_grid", new JSONObject()
                        .put("columns", Math.min(2, urls.length())).put("images", urls));
            }
        }
        String single = firstNonEmpty(str(info.get("path")),
                frames != null && !frames.isEmpty() ? str(frames.get(0)) : "");
        if (single.isEmpty()) {
            return null;
        }
        return ComponentData.of("image_grid", new JSONObject()
                .put("columns", 1).put("images", new JSONArray().put(single)));
    }

    /** app_operation 导航成功：给一张原生 app_card，可再次进入。 */
    static ComponentData appCard(AIToolResult result) throws Exception {
        Map<String, Object> m = asMap(result.getResult());
        if (m == null || !"success".equalsIgnoreCase(str(m.get("status")))) return null;
        String target = str(m.get("target"));
        if (target.isEmpty()) return null;
        String page = str(m.get("page"));
        if (page.isEmpty()) {
            int dot = target.lastIndexOf('.');
            page = dot >= 0 ? target.substring(dot + 1) : target;
        }
        JSONObject p = new JSONObject();
        p.put("title", page);
        p.put("message", str(m.get("message")));
        p.put("target", target);
        p.put("page", page);
        return ComponentData.of("app_card", p);
    }

    // ==================== 产物路径探测（动态工具兜底） ====================

    /**
     * 从结果文本里找产物文件路径。只给 {@link #ARTIFACT_SCAN_TOOLS} 白名单用：
     * 查询类工具的结果里出现文件名往往是数据本身，不是产物，探了会挂错卡。
     */
    private static List<ComponentData> scanArtifacts(Context context, String text) throws Exception {
        if (text == null || text.isEmpty() || text.length() > 40000) {
            return Collections.emptyList();
        }
        Set<String> paths = new LinkedHashSet<>();
        Matcher mt = ARTIFACT_PATH.matcher(text);
        while (mt.find() && paths.size() < 6) {
            String p = mt.group();
            int cut = p.length();
            while (cut > 0 && isTrailing(p.charAt(cut - 1))) {
                cut--;
            }
            p = p.substring(0, cut);
            if (!p.contains("*") && !p.contains("<")) {
                paths.add(p);
            }
        }
        if (paths.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> imgs = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String p : paths) {
            String resolved = resolvePath(context, p);
            String ext = extensionOf(resolved);
            if (IMAGE_EXT.contains(ext)) {
                imgs.add(resolved);
            } else {
                others.add(resolved);
            }
        }
        List<ComponentData> out = new ArrayList<>(2);
        if (!imgs.isEmpty()) {
            JSONArray urls = new JSONArray();
            for (String s : imgs) urls.put(s);
            out.add(ComponentData.of("image_grid", new JSONObject()
                    .put("columns", imgs.size() > 1 ? 2 : 1).put("images", urls)));
        }
        if (!others.isEmpty() && out.size() < MAX_CARDS_PER_CALL) {
            if (others.size() == 1) {
                ComponentData card = mediaCard(others.get(0), "");
                if (card != null) out.add(card);
            } else {
                JSONArray arr = new JSONArray();
                for (String s : others) {
                    arr.put(new JSONObject().put("name", fileNameOf(s)).put("path", s)
                            .put("size", sizeText(s)).put("type", extensionOf(s)));
                }
                out.add(ComponentData.of("file_list", new JSONObject()
                        .put("title", "生成文件 · " + others.size() + " 个").put("files", arr)));
            }
        }
        return out;
    }

    private static boolean isTrailing(char c) {
        return c == '.' || c == '，' || c == '。' || c == ',' || c == '、' || c == ':' || c == '：';
    }

    // ==================== 通用构件 ====================

    private static ComponentData tableCard(String title, JSONArray headers, JSONArray rows)
            throws Exception {
        if (headers.length() == 0 || rows.length() == 0) {
            return null; // TableCardView.canRender 要求 headers/rows 均存在且 headers 非空
        }
        JSONObject p = new JSONObject();
        p.put("title", title);
        p.put("headers", headers);
        p.put("rows", rows);
        return ComponentData.of("table_card", p);
    }

    private static ComponentData listCard(String title, JSONArray items) throws Exception {
        if (items.length() == 0) {
            return null;
        }
        return ComponentData.of("list_card", new JSONObject().put("title", title).put("items", items));
    }

    private static ComponentData infoCard(String title, JSONArray items) throws Exception {
        if (items.length() == 0) {
            return null;
        }
        return ComponentData.of("info_card", new JSONObject().put("title", title).put("items", items));
    }

    private static JSONObject kv(String label, String value) throws Exception {
        return new JSONObject().put("label", label).put("value", value == null ? "" : value);
    }

    private static void putIfNotEmpty(JSONArray items, String label, String value) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
            items.put(kv(label, value));
        }
    }

    private static void putIfNotEmpty(JSONObject obj, String key, String value) throws Exception {
        if (value != null && !value.trim().isEmpty()) {
            obj.put(key, value);
        }
    }

    private static JSONObject item(String icon, String title, String description, String value)
            throws Exception {
        return item(icon, title, description, value, null);
    }

    private static JSONObject item(String icon, String title, String description, String value,
                                   String url) throws Exception {
        JSONObject o = new JSONObject();
        o.put("icon", icon);
        o.put("title", title == null ? "" : title);
        if (description != null && !description.isEmpty()) o.put("description", description);
        if (value != null && !value.isEmpty()) o.put("value", value);
        if (url != null && url.startsWith("http")) o.put("url", url);
        return o;
    }

    private static JSONObject metric(String label, String value, String color) throws Exception {
        JSONObject o = new JSONObject();
        o.put("label", label);
        o.put("value", value == null || value.isEmpty() ? "-" : value);
        o.put("color", color);
        return o;
    }

    /** rows 元素是 List<Map>（key=列名）→ JSONArray 的数组行 */
    private static JSONArray rowsOfMaps(List<?> rows, JSONArray headers, String extraColKey)
            throws Exception {
        JSONArray out = new JSONArray();
        int headerCount = headers.length();
        String[] names = new String[headerCount];
        for (int i = 0; i < headerCount; i++) {
            names[i] = headers.optString(i, "");
        }
        for (Object r : rows) {
            Map<String, Object> rm = asMap(r);
            JSONArray row = new JSONArray();
            if (rm == null) {
                List<?> rl = asList(r);
                if (rl != null) {
                    for (Object v : rl) row.put(display(v));
                } else {
                    row.put(String.valueOf(r));
                }
            } else {
                for (int i = 0; i < headerCount; i++) {
                    Object v = rm.get(names[i]);
                    if (v == null && "row".equals(names[i]) && extraColKey != null) {
                        v = rm.get(extraColKey);
                    }
                    row.put(display(v));
                }
            }
            out.put(row);
            if (out.length() >= MAX_TABLE_ROWS) break;
        }
        return out;
    }

    private static String display(Object v) {
        if (v == null) return "";
        if (v instanceof Double) {
            double d = (Double) v;
            return d == Math.floor(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d) : String.valueOf(d);
        }
        return truncate(String.valueOf(v), 120);
    }

    // ==================== 文本解析小工具 ====================

    /** "a: b\nc: d" → list items（title=键，value=值） */
    private static JSONArray parseKVLines(String block) throws Exception {
        if (block == null || block.trim().isEmpty()) return null;
        JSONArray items = new JSONArray();
        for (String line : block.split("\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            int c = l.indexOf(": ");
            if (c > 0) {
                items.put(item("🕐", l.substring(0, c).trim(), l.substring(c + 2).trim(), null));
            } else {
                items.put(item("🕐", l, null, null));
            }
            if (items.length() >= MAX_LIST_ITEMS) break;
        }
        return items.length() > 0 ? items : null;
    }

    /** "[level] title: text" → list items */
    private static JSONArray parseBulletLines(String block, String icon) throws Exception {
        if (block == null || block.trim().isEmpty()) return null;
        JSONArray items = new JSONArray();
        for (String line : block.split("\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            int c = l.indexOf(": ");
            if (c > 0) {
                items.put(item(icon, truncate(l.substring(0, c), 40), l.substring(c + 2).trim(), null));
            } else {
                items.put(item(icon, truncate(l, 60), null, null));
            }
            if (items.length() >= MAX_LIST_ITEMS) break;
        }
        return items.length() > 0 ? items : null;
    }

    /** "name(level): text" → list items */
    private static JSONArray parseKVColonLines(String block, String icon) throws Exception {
        return parseBulletLines(block, icon);
    }

    // ==================== 路径/类型辅助 ====================

    private static String textOf(AIToolResult result) {
        Object r = result.getResult();
        if (r instanceof String) {
            return (String) r;
        }
        if (r instanceof Map) {
            Object t = ((Map<?, ?>) r).get("formatted_result");
            if (t instanceof String) return (String) t;
        }
        return r != null ? String.valueOf(r) : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return null;
    }

    private static List<?> asList(Object o) {
        if (o instanceof List) {
            return (List<?>) o;
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            List<Object> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) out.add(a.opt(i));
            return out;
        }
        return null;
    }

    private static Object mapGet(Map<String, Object> map, String key) {
        return map != null ? map.get(key) : null;
    }

    private static JSONObject jsonOf(Object o) {
        if (o instanceof JSONObject) {
            return (JSONObject) o;
        }
        if (o instanceof String) {
            return ComponentData.parseJsonObject((String) o);
        }
        Map<String, Object> m = asMap(o);
        if (m == null) {
            return null;
        }
        // 手工转换：不依赖 org.json 的 JSONObject(Map) 构造器（各平台签名不一致）
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey() == null) continue;
            try {
                out.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
            } catch (Throwable ignored) {
                // 单个键不可序列化就跳过
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intOrZero(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(str(o));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String firstNonEmpty(String... vs) {
        if (vs != null) {
            for (String v : vs) {
                if (v != null && !v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        }
        return "";
    }

    private static String joinNonEmpty(String sep, String... vs) {
        StringBuilder sb = new StringBuilder();
        for (String v : vs) {
            if (v == null || v.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(v.trim());
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String formatDouble(String v) {
        try {
            double d = Double.parseDouble(v);
            return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
        } catch (Exception e) {
            return v;
        }
    }

    private static String extensionOf(String path) {
        if (path == null) return "";
        String p = stripScheme(path);
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        int dot = p.lastIndexOf('.');
        if (dot <= slash || dot < 0 || dot >= p.length() - 1) {
            return "";
        }
        String ext = p.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]{1,5}") ? ext : "";
    }

    private static String fileNameOf(String path) {
        if (path == null) return "";
        String p = stripScheme(path);
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String stripScheme(String path) {
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring(7);
        if (p.startsWith("content://")) return p;
        return p;
    }

    private static String joinPath(String dir, String file) {
        if (dir == null || dir.isEmpty()) return file;
        if (file == null || file.isEmpty()) return dir;
        return dir.endsWith("/") || dir.endsWith("\\") ? dir + file : dir + "/" + file;
    }

    /** 相对路径/裸文件名 → 绝对（工作区解析）；解析不到就原样返回，让卡片自己兜底 */
    private static String resolvePath(Context context, String pathOrName) {
        if (pathOrName == null) return "";
        String p = pathOrName.trim();
        if (p.isEmpty() || p.startsWith("http") || p.startsWith("content://")) return p;
        try {
            File direct = new File(p.startsWith("file://") ? p.substring(7) : p);
            if (direct.isAbsolute() && direct.exists()) {
                return direct.getAbsolutePath();
            }
            if (context != null) {
                File resolved = AgentWorkspace.getInstance(context).resolveExistingFile(p);
                if (resolved != null && resolved.exists()) {
                    return resolved.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    /** 已存在文件的真实大小；取不到返回空串（FileCardView 允许 size 为空） */
    private static String sizeText(String path) {
        try {
            if (path == null || path.startsWith("http") || path.startsWith("content://")) {
                return "";
            }
            File f = new File(path.startsWith("file://") ? path.substring(7) : path);
            if (f.isFile() && f.length() > 0) {
                return bytesText(f.length());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String bytesText(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
