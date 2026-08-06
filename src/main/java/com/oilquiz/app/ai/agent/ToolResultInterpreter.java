package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.engine.ALChat;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 工具结果解释器。
 *
 * 两条独立链路（UI 展示 与 LLM 推理 完全分离，互不影响）：
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ ① UI 展示链路 (formatForUi / interpretedMessage / addAIMessage 主气泡) │
 * │    仅做字符串拼接 / Spannable / TextView 渲染，几 KB~几十 KB 内存量级    │
 * │    与本地模型推理完全无关，不存在长度导致"模型崩溃"一说，不做截断保护     │
 * │    (用户要求：模板/主气泡不做任何长度截断，信息完整输出)                   │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ ② LLM 推理链路 (interpret / interpretOnline / interpretLocal)            │
 * │    - 在线 interpretOnline：直接完整 JSON 喂给远端模型，仅 >20万字符硬切    │
 * │    - 本地 interpretLocal：ALChat 本地推理，长 prompt 会撑爆上下文/OOM，    │
 * │      必须使用 MAX_SUMMARY_LEN + MAX_INPUT_LEN 双层截断做防御保护          │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * UI 展示与 LLM 输入二者相互独立：即使 formatForUi 输出 5 万字，也只是 UI 渲染慢一点，
 * 绝不可能让本地模型崩；只有 interpretLocal() 内部的 PROMPT 输入过长才会导致模型 OOM。
 */
public class ToolResultInterpreter {

    private static final String TAG = "ToolResultInterpreter";
    /** 在线解析超时时间（秒）：15s，防止工具解读卡死主线程外阻塞过久 */
    private static final long ONLINE_TIMEOUT_SECONDS = 15L;
    /** 工具解读在线模型输出上限：2048 tokens（允许完整详细的自然语言摘要，不截断内容） */
    private static final int ONLINE_INTERPRET_MAX_TOKENS = 2048;
    /** 工具解读本地模型输出上限：1024 tokens（本地模型上下文较小，保守放宽） */
    private static final int LOCAL_INTERPRET_MAX_TOKENS = 1024;
    /** 送给本地 LLM 的前置摘要最大长度：先用离线模板压缩，再送入模型
     *  ——仅用于 interpretLocal() 的模型推理输入保护，不影响 UI 主气泡/模板输出长度 */
    private static final int MAX_SUMMARY_LEN = 1200;
    /** 送给本地 LLM 的结果文本硬截断：绝对保证上下文不会超大导致模型 OOM/崩溃
     *  ——仅用于 interpretLocal() 的模型推理输入保护，不影响 UI 主气泡/模板输出长度 */
    private static final int MAX_INPUT_LEN = 1500;

    private static final Gson GSON = new Gson();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ToolResultInterpreter");
        t.setDaemon(true);
        return t;
    });
    /** 本地 LLM 实例（懒加载，避免无本地模型时浪费内存） */
    private static volatile ALChat localChatInstance;

    private ToolResultInterpreter() {}

    /** 获取本地 LLM 实例（懒加载 + 多线程安全 DCL） */
    private static ALChat getLocalChat() {
        if (localChatInstance == null) {
            synchronized (ToolResultInterpreter.class) {
                if (localChatInstance == null) {
                    try {
                        localChatInstance = new ALChat();
                    } catch (Throwable t) {
                        Log.e(TAG, "初始化本地 LLM 失败: " + t.getMessage());
                    }
                }
            }
        }
        return localChatInstance;
    }

    /**
     * UI 展示直接调用：将工具结果格式化为易读的自然语言文本（同步、不调用 LLM、纯模板）。
     * 相比 String.valueOf(Map) 会输出如 {a=1, b=[c,d]} 的丑陋 Java 字符串，该方法：
     * 1) 对已知工具按模板生成简洁摘要；
     * 2) 未知工具返回美化 JSON（Gson 带缩进）；
     * 3) 纯文本结果直接返回。
     */
    public static String formatForUi(String toolName, Object result) {
        try {
            return templateInterpret(toolName, result);
        } catch (Exception e) {
            Log.w(TAG, "formatForUi 异常: " + e.getMessage());
            return fallbackPretty(result);
        }
    }

    /**
     * 解释工具执行结果：
     * 1) 优先 在线模型 —— 直接传完整原始结果（完整 JSON / 全文本，不做模板预处理，不截断）
     * 2) 在线不可用 / 失败 / 返回空 —— 回退 本地模型（ALChat）—— 传模板摘要 + 严格截断，防 OOM
     * 3) 本地也失败 —— callback 返回 null，让调用方用 formatForUi 模板展示
     *
     * @param ctx      上下文
     * @param toolName 工具名
     * @param result   原始结果
     * @param callback 解释回调；无可用 LLM 时回调 summary=null
     */
    public static void interpret(Context ctx, String toolName, Object result, InterpretCallback callback) {
        if (callback == null) {
            return;
        }
        executor.execute(() -> {
            String summary = null;
            // Step 1: 在线模型（完整原始结果，不做任何模板 / 截断预处理）
            try {
                summary = interpretOnline(ctx, toolName, result);
            } catch (Throwable t) {
                Log.w(TAG, "在线 LLM 解读失败: " + t.getMessage());
            }
            // Step 2: 在线无结果 → 回退本地模型（走模板摘要 + 截断，防本地 OOM）
            if (summary == null || summary.trim().isEmpty()) {
                try {
                    summary = interpretLocal(toolName, result);
                } catch (Throwable t) {
                    Log.w(TAG, "本地 LLM 解读失败: " + t.getMessage());
                }
            }
            final String finalSummary = (summary != null && !summary.trim().isEmpty()) ? summary : null;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    callback.onInterpreted(finalSummary);
                } catch (Exception e) {
                    Log.e(TAG, "解释回调异常: " + e.getMessage(), e);
                }
            });
        });
    }

    /** 是否有可用的解读模型：在线或本地任一可用即返回 true */
    public static boolean isAnyModelAvailable(Context ctx) {
        try {
            if (ctx != null && OnlineInferenceService.getInstance(ctx).isOnlineModelAvailable()) {
                return true;
            }
        } catch (Throwable ignore) { }
        try {
            ALChat lc = getLocalChat();
            if (lc != null && lc.isInitialized()) {
                return true;
            }
        } catch (Throwable ignore) { }
        return false;
    }

    /**
     * 在线模型解析：直接把完整原始结果转 JSON 字符串，不走模板、不做摘要、不截断，保证功能完整性。
     * 仅当结果真的异常大（>20 万字符）时才做硬截断以防接口本身拒绝，但默认不截断。
     *
     * @return 解析摘要；不可用或失败返回 null
     */
    private static String interpretOnline(Context ctx, String toolName, Object result) throws Exception {
        if (ctx == null) {
            return null;
        }
        OnlineInferenceService service = OnlineInferenceService.getInstance(ctx);
        if (!service.isOnlineModelAvailable()) {
            return null;
        }
        OnlineModelManager.OnlineModelConfig config = service.getActiveConfig();
        if (config == null) {
            return null;
        }
        String prompt = buildPromptOnline(toolName, result);
        // 关闭工具调用，纯文本摘要；使用 2048 tokens 上限避免输出截断
        String summary = service.generateAsync(prompt, config, new ArrayList<ChatMessage>(),
                ONLINE_INTERPRET_MAX_TOKENS, false)
                .get(ONLINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (summary == null) {
            return null;
        }
        summary = cleanModelOutput(summary);
        return summary;
    }

    /**
     * 本地 LLM 解析（ALChat）：因为本地模型上下文小、长结果极易 OOM/崩溃，
     * 所以这里仍然使用「模板摘要 + 严格截断」的策略，保证稳定。
     * 若本地未加载 / 调用失败 / 抛异常，返回 null。
     */
    private static String interpretLocal(String toolName, Object result) {
        try {
            ALChat lc = getLocalChat();
            if (lc == null || !lc.isInitialized()) return null;
            String prompt = buildPromptLocal(toolName, result);
            // maxTokens=LOCAL_INTERPRET_MAX_TOKENS(1024), temp=0.1(保守), topP=0.9, repeatPenalty=40
            String output = lc.sendMessage(prompt, LOCAL_INTERPRET_MAX_TOKENS, 0.1f, 0.9f, 40);
            if (output == null) return null;
            output = cleanModelOutput(output);
            return output;
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "本地 LLM OOM: " + oom.getMessage());
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "本地 LLM 解析异常: " + t.getMessage());
            return null;
        }
    }

    /** 在线模型输出最大 token 数（实际使用 ONLINE_INTERPRET_MAX_TOKENS=2048，此常量保留作兼容） */
    private static final int INTERPRET_OUTPUT_MAX = 2048;
    /** 在线模型：硬截断上限（20万字符，仅防超大输入导致接口自身报错；正常 Excel/搜索结果几 MB 内不会到） */
    private static final int ONLINE_RAW_MAX_LEN = 200_000;

    /**
     * 【在线模型用】构建 prompt：直接使用完整原始 JSON / 文本，不做模板、不做摘要、不截断，保证信息完整性。
     * 用户明确要求在线模型功能不被前置摘要削弱。
     */
    private static String buildPromptOnline(String toolName, Object result) {
        String raw;
        try {
            if (result == null) raw = "无";
            else if (result instanceof String) raw = (String) result;
            else raw = new GsonBuilder().serializeNulls().create().toJson(result);
        } catch (Throwable t) {
            raw = String.valueOf(result);
        }
        if (raw != null && raw.length() > ONLINE_RAW_MAX_LEN) {
            raw = safeTruncate(raw, ONLINE_RAW_MAX_LEN) + "\n...（结果过长已被硬截断，原长度 " + raw.length() + " 字符）";
        }
        // 清理孤立代理项等非标准字符，防止模型输入异常导致崩溃
        if (raw != null) raw = sanitize(raw);
        return "你是工具结果解读助手。用户调用了工具【" + toolName + "】，以下是该工具的完整原始执行结果：\n"
                + "===== 工具结果开始 =====\n"
                + raw
                + "\n===== 工具结果结束 =====\n\n"
                + "请你基于上述完整结果，用自然流畅的中文给用户一份完整详细的解读或总结。"
                + "不要提及「工具」「执行结果」等内部术语；可以分段落、使用 emoji 辅助阅读；"
                + "把用户关心的所有关键信息（天气含逐时/预报/预警/指数，搜索含多条结果+来源，翻译含完整译文等）都覆盖到，"
                + "不要为了简短而省略任何重要内容；长度不限，讲清楚为止。";
    }

    /**
     * 【本地模型用】构建 prompt：先用离线模板压成结构化摘要，再做 MAX_INPUT_LEN 硬截断（双重保险）。
     * 本地模型上下文小，必须严格防止 OOM/崩溃。
     */
    private static String buildPromptLocal(String toolName, Object result) {
        String preSummary;
        try {
            preSummary = templateInterpret(toolName, result);
        } catch (Throwable t) {
            preSummary = safeResultString(result);
        }
        if (preSummary == null) preSummary = "";
        preSummary = sanitize(preSummary);
        if (preSummary.length() > MAX_SUMMARY_LEN) {
            preSummary = safeTruncate(preSummary, MAX_SUMMARY_LEN) + "...(已截断)";
        }
        if (preSummary.length() > MAX_INPUT_LEN) {
            preSummary = safeTruncate(preSummary, MAX_INPUT_LEN) + "...";
        }
        return "你是助手。工具[" + toolName + "]执行结果摘要：\n"
                + preSummary
                + "\n\n请用自然中文给用户整理一份清晰的结论。不要提「工具」「执行结果」等字样；"
                + "如果是天气就覆盖当前+预报+指数+预警；是搜索就列出结果+来源链接；"
                + "是翻译就完整输出译文；其他同理，不要遗漏关键信息。长度不限，信息完整优先。";
    }

    /** 安全转字符串：绝对避免抛异常，同时清理非标准字符 */
    private static String safeResultString(Object result) {
        try {
            if (result == null) return "无";
            if (result instanceof String) {
                String s = sanitize((String) result);
                return s.length() > MAX_INPUT_LEN ? safeTruncate(s, MAX_INPUT_LEN) + "..." : s;
            }
            String json = sanitize(GSON.toJson(result));
            return json.length() > MAX_INPUT_LEN ? safeTruncate(json, MAX_INPUT_LEN) + "..." : json;
        } catch (Throwable t) {
            return "执行完成";
        }
    }

    /**
     * 离线模板解析：根据工具名用预设模板生成摘要。
     *
     * @param toolName 工具名
     * @param result   原始结果
     * @return 摘要字符串
     */
    private static String templateInterpret(String toolName, Object result) {
        try {
            if (toolName == null) {
                return fallbackPretty(result);
            }
            switch (toolName) {
                case "ai_weather":
                case "app_toolkit":
                    return weatherTemplate(result, toolName);
                case "network_search":
                    return networkSearchTemplate(result);
                case "database":
                    return databaseTemplate(result);
                case "location":
                    return locationTemplate(result);
                case "translation":
                    return translationTemplate(result);
                case "file":
                    return fileToolTemplate(result);
                case "app_operation":
                    return appOperationTemplate(result);
                case "file_reader":
                    return fileReaderTemplate(result);
                case "file_analyzer":
                    return fileAnalyzerTemplate(result);
                case "file_generator":
                    return fileGeneratorTemplate(result);
                case "webpage_reader":
                    return webpageReaderTemplate(result);
                case "smart_research":
                    return smartResearchTemplate(result);
                case "system_resource":
                    return systemResourceTemplate(result);
                case "permission_manager":
                    return permissionManagerTemplate(result);
                case "python_calculate":
                    return pythonCalculateTemplate(result);
                default:
                    return fallbackPretty(result);
            }
        } catch (Exception e) {
            Log.w(TAG, "模板解析异常: " + e.getMessage());
            return fallbackPretty(result);
        }
    }

    // ========================= 专家级工具结果模板 =========================
    // 设计原则：
    //  1) 字段严格对齐每个工具实际 AIToolResult.result 的真实结构（已实测）
    //  2) 先给出一句话自然结论（人话），再按 2~3 行给出关键指标
    //  3) 天气、搜索、翻译等给出实用建议，不是只堆数字
    //  4) 模板不做长度截断，完整信息全部展示（本地 LLM 保护截断仅存在于 buildPromptLocal 路径）
    // ====================================================================

    /** 天气模板：严格对齐 ai_weather SDK 结果（parseWeatherResultToMap 输出的 Map 结构），信息完整不截断 */
    private static String weatherTemplate(Object result, String toolName) {
        JsonObject outer = toJsonObject(result);
        String formattedResult = null;
        if (outer == null) {
            // 纯文本结果（SDK formatted 或 HTTP 解析过的文本）
            String raw = result == null ? "" : String.valueOf(result);
            if (raw.isEmpty()) return "（无天气结果）";
            formattedResult = raw;
            outer = new JsonObject();
            outer.addProperty("formatted_result", raw);
        } else {
            formattedResult = strDeep(outer, "formatted_result");
        }

        // 真正的天气数据可能在 data 子对象里（app_toolkit 场景）
        JsonObject w = outer;
        if (outer.has("data") && outer.get("data").isJsonObject()) {
            JsonObject d = outer.getAsJsonObject("data");
            if (d.has("city") || d.has("weather") || d.has("temperature") || d.has("forecast") || d.has("life_indices")) w = d;
        }

        String status = strDeep(outer, "status");
        String err    = strDeep(outer, "error", "error_message", "message");
        String type   = strDeep(outer, "type", "action"); // current/forecast/hourly/air_quality/alerts/indices/all

        // 出错直接返回错误
        if ("error".equalsIgnoreCase(status) && err != null && !err.isEmpty()) {
            StringBuilder eb = new StringBuilder("⚠️ 天气查询失败：").append(err);
            if (formattedResult != null && !formattedResult.isEmpty()) eb.append("\n").append(formattedResult);
            return eb.toString();
        }

        StringBuilder sb = new StringBuilder();

        // ============ 一句话结论（城市+天气+温度） ============
        String city = strDeep(w, "city", "cityName", "name");
        String weather = strDeep(w, "weather", "text");
        String temp    = strDeep(w, "temperature", "temp");
        if (city != null || weather != null || temp != null) {
            if (city != null) sb.append("📍 ").append(city).append("  ");
            if (weather != null) sb.append(weather).append("  ");
            if (temp != null) sb.append(temp.endsWith("°C") || temp.endsWith("°") ? temp : temp + "°C");
            sb.append("\n");
        } else if (formattedResult != null && !formattedResult.isEmpty()) {
            // 没有解析化字段但有 formatted_result：直接用它作顶部展示
            sb.append(formattedResult).append("\n");
        }

        // ============ 关键指标 ============
        String feels   = strDeep(w, "feels_like", "feelsLike");
        String humid   = strDeep(w, "humidity");
        String windD   = strDeep(w, "wind_direction", "windDir");
        String windS   = strDeep(w, "wind_speed", "windScale", "windSpeed");
        String vis     = strDeep(w, "visibility", "vis");
        String press   = strDeep(w, "pressure");
        String obsTime = strDeep(w, "observation_time", "obsTime");
        String sunrise = strDeep(w, "sunrise");
        String sunset  = strDeep(w, "sunset");
        String aqi     = strDeep(w, "aqi");
        String airLv   = strDeep(w, "air_level", "airLevel");
        String primary = strDeep(w, "primary_pollutant", "primary");
        String pm25    = strDeep(w, "pm2.5", "pm2p5");
        String pm10    = strDeep(w, "pm10");
        String no2     = strDeep(w, "no2");
        String so2     = strDeep(w, "so2");
        String co      = strDeep(w, "co");
        String o3      = strDeep(w, "o3");

        if (feels != null || humid != null || windD != null || windS != null || vis != null || press != null) {
            if (feels != null) sb.append("🌡 体感 ").append(feels).append("  ");
            if (humid != null) sb.append("💧 湿度 ").append(humid).append("  ");
            if (windD != null || windS != null) { sb.append("\n🌬 "); if (windD != null) sb.append(windD).append("  "); if (windS != null) sb.append(windS); }
            if (vis != null) sb.append("  👁 能见 ").append(vis);
            if (press != null) sb.append("\n🎈 气压 ").append(press);
            sb.append("\n");
        }
        if (sunrise != null || sunset != null) {
            sb.append("🌅 ");
            if (sunrise != null) sb.append("日出 ").append(sunrise).append("  ");
            if (sunset  != null) sb.append("日落 ").append(sunset);
            sb.append("\n");
        }
        if (obsTime != null) {
            String t = obsTime.length() > 16 ? obsTime.substring(11, 16) : obsTime;
            sb.append("⏱ 观测 ").append(t).append("  ");
            if (type != null) sb.append("类型：").append(type);
            sb.append("\n");
        }

        // ============ 空气质量 ============
        if (aqi != null || pm25 != null || pm10 != null || no2 != null || so2 != null || co != null || o3 != null) {
            sb.append("\n🌫 空气质量：");
            if (aqi != null) {
                sb.append("AQI ").append(aqi);
                if (airLv != null) sb.append("（").append(airLv).append("）");
                try {
                    int a = Integer.parseInt(aqi);
                    if (a >= 300) sb.append(" · 严重污染，尽量避免外出");
                    else if (a >= 200) sb.append(" · 重度污染，外出戴口罩");
                    else if (a >= 150) sb.append(" · 中度污染，敏感人群减少外出");
                    else if (a >= 100) sb.append(" · 轻度污染，正常活动即可");
                    else if (a >= 50)  sb.append(" · 良，空气可接受");
                    else sb.append(" · 优，空气清新适合户外活动");
                } catch (Exception ignored) {}
            }
            if (primary != null && !"NA".equalsIgnoreCase(primary) && !primary.isEmpty()) sb.append("\n    首要污染物：").append(primary);
            if (pm25 != null) sb.append("  PM2.5=").append(pm25);
            if (pm10 != null) sb.append("  PM10=").append(pm10);
            if (no2  != null) sb.append("  NO₂=").append(no2);
            if (so2  != null) sb.append("  SO₂=").append(so2);
            if (co   != null) sb.append("  CO=").append(co);
            if (o3   != null) sb.append("  O₃=").append(o3);
            sb.append("\n");
        }

        // ============ 未来 7 天预报（全部展示，不截断） ============
        String forecast = strDeep(w, "forecast");
        if (forecast != null && !forecast.trim().isEmpty()) {
            sb.append("\n📅 未来天气预报：\n").append(forecast).append("\n");
        }

        // ============ 24 小时逐时（全部展示，不截断） ============
        String hourly = strDeep(w, "hourly");
        if (hourly != null && !hourly.trim().isEmpty()) {
            sb.append("\n⏰ 逐时预报：\n").append(hourly).append("\n");
        }

        // ============ 分钟级降水（全部展示，不截断） ============
        String minutely = strDeep(w, "minutely");
        if (minutely != null && !minutely.trim().isEmpty()) {
            sb.append("\n💧 分钟级降水：\n").append(minutely).append("\n");
        }

        // ============ 生活指数（全部展示，不截断） ============
        String indices = strDeep(w, "life_indices", "indices");
        if (indices != null && !indices.trim().isEmpty()) {
            sb.append("\n💡 生活指数建议：\n").append(indices).append("\n");
        }

        // ============ 气象预警（全部展示，不截断） ============
        String alerts  = strDeep(w, "alerts", "warning");
        if (alerts != null && !alerts.trim().isEmpty()) {
            int n = alerts.split("\n").length;
            sb.append("\n🚨 气象预警（共").append(n).append("条）：\n").append(alerts).append("\n");
        }

        // ============ 基于天气/温度的实用建议 ============
        if (weather != null) {
            String wl = weather.toLowerCase();
            if (wl.contains("雨") || wl.contains("雪")) sb.append("\n☂️ 建议出门带伞，路上注意防滑");
            else if (wl.contains("雷") || wl.contains("暴")) sb.append("\n🌩 有强对流天气，尽量减少外出并关好门窗");
            else if (wl.contains("霾") || wl.contains("沙") || wl.contains("尘")) sb.append("\n😷 空气较差，外出建议佩戴口罩");
            else if (wl.contains("雾")) sb.append("\n🚗 有雾，开车注意保持安全车距");
            else if (wl.contains("晴")) sb.append("\n🧴 紫外线较强，外出注意防晒");
        }
        if (temp != null) {
            try {
                int t = Integer.parseInt(temp.replaceAll("[^0-9\\-]", "").split("-")[0]);
                if (t <= 5) sb.append("\n🧥 温度较低，外出注意防寒保暖");
                else if (t >= 33) sb.append("\n🥵 天气炎热，注意防暑降温、多补水");
                else if (t >= 28) sb.append("\n🌡 气温较高，建议穿着轻薄透气衣物");
                else if (t <= 12) sb.append("\n🧣 天气偏凉，可加件外套");
            } catch (Exception ignored) {}
        }
        // 预警存在时单独再加一条安全建议
        if (alerts != null && !alerts.trim().isEmpty()) sb.append("\n⚠️ 有气象预警发布，请密切留意天气变化，必要时减少外出");
        // 空气质量>150 再加一条
        if (aqi != null) { try { int a = Integer.parseInt(aqi); if (a >= 150) sb.append("\n🏠 空气污染较重，建议关闭门窗，室内开启净化器"); } catch (Exception ignored) {} }

        if (sb.length() == 0) {
            // 啥都没有：返回 formatted 或 fallback
            if (formattedResult != null && !formattedResult.isEmpty()) return formattedResult;
            return fallbackPretty(result);
        }
        return sb.toString().trim();
    }

    /** 联网搜索模板：对齐 network_search (Metaso API) 字段，信息完整不截断 */
    private static String networkSearchTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "搜索完成" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);

        // --- 秘塔问答 (ask_metaso) 优先：有 answer 直接展示 ---
        String answer = strDeep(obj, "answer", "text");
        String question = strDeep(obj, "question");
        if (answer != null && !answer.trim().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (question != null) sb.append("❓ ").append(question).append("\n");
            sb.append("💡 ").append(answer);
            // 引用来源（前3条，URL不截断、标题单行化保留完整）
            for (String k : new String[]{"references", "sources"}) {
                if (obj.has(k) && obj.get(k).isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray(k);
                    int n = Math.min(arr.size(), 3);
                    if (n > 0) sb.append("\n\n📚 参考来源：\n");
                    for (int i = 0; i < n; i++) {
                        JsonElement e = arr.get(i);
                        if (e.isJsonObject()) {
                            JsonObject r = e.getAsJsonObject();
                            String t = strDeep(r, "title", "name");
                            String u = strDeep(r, "url", "link", "href");
                            sb.append(i + 1).append(". ");
                            if (t != null) sb.append(truncateLines(t, Integer.MAX_VALUE));
                            if (u != null) sb.append(t != null ? "  →  " : "").append(u);
                            sb.append("\n");
                        }
                    }
                    break;
                }
            }
            return sb.toString().trim();
        }

        // --- 普通搜索：query + count + 5 条结果 ---
        String query = strDeep(obj, "query", "keyword", "q");
        String count = strDeep(obj, "count", "total");
        String engine = strDeep(obj, "engine");

        StringBuilder sb = new StringBuilder();
        sb.append("🔎 搜索");
        if (query != null) sb.append("「").append(query).append("」");
        if (count != null) sb.append(" · ").append(count).append("条结果");
        if (engine != null) sb.append(" （来源：").append(engine).append("）");
        sb.append("\n\n");

        boolean found = false;
        for (String key : new String[]{"results", "data", "items", "list", "webpages"}) {
            if (obj.has(key) && obj.get(key).isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray(key);
                int n = Math.min(arr.size(), 5);
                for (int i = 0; i < n; i++) {
                    JsonElement e = arr.get(i);
                    if (!e.isJsonObject()) continue;
                    JsonObject it = e.getAsJsonObject();
                    String title = strDeep(it, "title", "name", "subject");
                    String snippet = strDeep(it, "snippet", "description", "summary", "content", "abstract");
                    String url = strDeep(it, "url", "link", "href", "linkUrl");
                    String src = strDeep(it, "source", "siteName", "domain");
                    String date = strDeep(it, "date", "publishTime", "publishDate", "time");
                    if (title == null && snippet == null && url == null) continue;
                    found = true;
                    sb.append(i + 1).append(". ");
                    if (title != null) sb.append("**").append(title).append("**");
                    if (src != null || date != null) {
                        sb.append("  _(");
                        if (src != null) sb.append(src);
                        if (src != null && date != null) sb.append(" · ");
                        if (date != null) {
                            String d = date.length() > 10 ? date.substring(0, 10) : date;
                            sb.append(d);
                        }
                        sb.append(")_");
                    }
                    sb.append("\n");
                    if (snippet != null) sb.append(snippet).append("\n");
                    if (url != null) sb.append("🔗 ").append(url).append("\n");
                    sb.append("\n");
                }
                break;
            }
        }
        if (!found) {
            String err = strDeep(obj, "error", "message", "msg");
            if (err != null) return "⚠️ 搜索失败：" + err;
        }
        return sb.toString().trim();
    }

    /** 数据库模板：对齐 database (题库查询) 结构，信息完整不截断 */
    private static String databaseTemplate(Object result) {
        if (result instanceof String) return (String) result;
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);

        String action   = strDeep(obj, "action");
        String keyword  = strDeep(obj, "keyword");
        String count    = strDeep(obj, "count", "total", "totalQuestions");
        String msg      = strDeep(obj, "message");
        String status   = strDeep(obj, "status");
        String easy     = strDeep(obj, "easyQuestions");
        String medium   = strDeep(obj, "mediumQuestions");
        String hard     = strDeep(obj, "hardQuestions");
        String types    = strDeep(obj, "questionTypes", "types");
        String cats     = strDeep(obj, "categories");
        String ver      = strDeep(obj, "version", "database_version");

        StringBuilder sb = new StringBuilder();
        if (msg != null && msg.contains("成功")) {
            sb.append("✅ ").append(msg);
        } else if ("failed".equalsIgnoreCase(status)) {
            sb.append("❌ ");
            if (msg != null) sb.append(msg);
            else sb.append("操作失败");
            String err = strDeep(obj, "error");
            if (err != null) sb.append("：").append(err);
            return sb.toString();
        } else if (keyword != null && count != null) {
            sb.append("📚 关键词「").append(keyword).append("」命中 ").append(count).append(" 道题");
        } else if (count != null) {
            if (ver != null) sb.append("📊 当前题库 v").append(ver).append(" · 共 ").append(count).append(" 道题");
            else sb.append("📊 题库共 ").append(count).append(" 道题目");
        } else if (ver != null) {
            sb.append("📊 数据库版本：").append(ver);
        } else if (msg != null) {
            sb.append(msg);
        } else {
            sb.append("✅ 操作成功");
        }
        if (easy != null || medium != null || hard != null) {
            sb.append("\n\n📈 难度分布：");
            int total = 0;
            int[] nums = new int[3];
            try { nums[0] = Integer.parseInt(easy); total += nums[0]; } catch (Exception ignored) {}
            try { nums[1] = Integer.parseInt(medium); total += nums[1]; } catch (Exception ignored) {}
            try { nums[2] = Integer.parseInt(hard); total += nums[2]; } catch (Exception ignored) {}
            if (easy != null)   sb.append("\n• 简单 ").append(easy).append(total > 0 ? " (" + pct(nums[0], total) + ")" : "");
            if (medium != null) sb.append("\n• 中等 ").append(medium).append(total > 0 ? " (" + pct(nums[1], total) + ")" : "");
            if (hard != null)   sb.append("\n• 困难 ").append(hard).append(total > 0 ? " (" + pct(nums[2], total) + ")" : "");
        }
        if (types != null) sb.append("\n📝 题型：").append(types);
        if (cats != null) {
            String[] ls = cats.split(", |\\n|,");
            sb.append("\n🗂 分类数 ").append(ls.length).append("：");
            for (int i = 0; i < ls.length; i++) {
                if (i > 0) sb.append("、");
                sb.append(ls[i].trim());
            }
        }
        // 命中题目预览（前3道，内容不截断）
        for (String k : new String[]{"questions", "results", "items", "list", "data"}) {
            if (obj.has(k) && obj.get(k).isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray(k);
                int n = Math.min(arr.size(), 3);
                if (n > 0) sb.append("\n\n📋 预览前").append(n).append("题：\n");
                for (int i = 0; i < n; i++) {
                    JsonElement e = arr.get(i);
                    if (!e.isJsonObject()) continue;
                    JsonObject q = e.getAsJsonObject();
                    String qid = strDeep(q, "id", "question_id", "_id");
                    String qt = strDeep(q, "questionText", "question", "title", "content", "text");
                    String ans = strDeep(q, "correctAnswer", "answer", "correct_option", "rightAnswer");
                    sb.append(i + 1).append(". ");
                    if (qid != null) sb.append("[#").append(qid).append("] ");
                    if (qt != null) sb.append(qt);
                    if (ans != null) sb.append("\n   ✅ 答案：").append(ans);
                    sb.append("\n");
                }
                break;
            }
        }
        // 单题详情
        if (obj.has("question") && obj.get("question").isJsonObject()) {
            JsonObject q = obj.getAsJsonObject("question");
            String qt = strDeep(q, "questionText", "question", "content");
            String ans = strDeep(q, "correctAnswer", "answer");
            String exp = strDeep(q, "explanation", "analysis");
            if (qt != null) sb.append("\n\n📝 题目：").append(qt);
            if (ans != null) sb.append("\n✅ 答案：").append(ans);
            if (exp != null) sb.append("\n💡 解析：").append(exp);
        }
        return sb.toString().trim();
    }

    /** 定位模板：对齐 location 结构 status/latitude/longitude/accuracy/city/provider/address，完整不截断 */
    private static String locationTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "（无定位结果）" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);

        String err = strDeep(obj, "error", "message");
        String permDenied = strDeep(obj, "permission_required");
        if ("error".equalsIgnoreCase(strDeep(obj, "status")) || err != null) {
            StringBuilder sb = new StringBuilder("⚠️ ");
            if (err != null) sb.append(err);
            else sb.append("定位失败");
            if ("true".equals(permDenied)) sb.append("（请先授予位置权限）");
            String loc = strDeep(obj, "location_service_disabled");
            if ("true".equals(loc)) sb.append("（请先在系统设置开启位置服务）");
            return sb.toString();
        }
        String city = strDeep(obj, "city", "cityName", "name");
        String addr = strDeep(obj, "address", "addr", "fullAddress");
        String lat = strDeep(obj, "latitude", "lat");
        String lon = strDeep(obj, "longitude", "lng", "lon");
        String acc = strDeep(obj, "accuracy");
        String prov = strDeep(obj, "province", "state");
        String dis  = strDeep(obj, "district");
        String street = strDeep(obj, "street");

        StringBuilder sb = new StringBuilder();
        // 一句话结论
        if (city != null || addr != null) {
            sb.append("📍 当前位置：");
            if (addr != null) sb.append(addr);
            else {
                if (prov != null && !prov.equals(city)) sb.append(prov);
                if (city != null) sb.append(city);
                if (dis != null) sb.append(dis);
                if (street != null) sb.append(street);
            }
        } else if (lat != null && lon != null) {
            sb.append("📍 已获取坐标：");
        } else {
            return fallbackPretty(result);
        }
        // 坐标 + 精度
        if (lat != null && lon != null) {
            sb.append("\n🧭 坐标：").append(lat).append(", ").append(lon);
            if (acc != null) {
                try {
                    float a = Float.parseFloat(acc);
                    if (a > 500) sb.append("  精度 ").append(a).append("m（较低，建议室外开阔地再试）");
                    else if (a > 100) sb.append("  精度 ").append(a).append("m（一般）");
                    else sb.append("  精度 ").append(a).append("m（良好）");
                } catch (Exception ignored) {
                    sb.append("  精度 ").append(acc).append("m");
                }
            }
        }
        return sb.toString();
    }

    /** 翻译模板：对齐 translation 结构，内容完整不截断 */
    private static String translationTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "翻译完成" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);

        String status = strDeep(obj, "status");
        String err = strDeep(obj, "error", "message");
        if ("error".equalsIgnoreCase(status) || err != null) {
            StringBuilder sb = new StringBuilder("❌ 翻译失败");
            if (err != null) sb.append("：").append(err);
            else {
                String m = strDeep(obj, "msg");
                if (m != null) sb.append("：").append(m);
            }
            return sb.toString();
        }
        String orig = strDeep(obj, "original_text", "source_text", "source", "text");
        String dest = strDeep(obj, "target_language", "targetLang", "to");
        String trans = strDeep(obj, "translated_text", "translation", "result", "translated");
        String engine = strDeep(obj, "engine");
        String detect = strDeep(obj, "detected_language", "source_language");

        StringBuilder sb = new StringBuilder();
        if (detect != null) sb.append("🔍 检测语言：").append(detect).append("  ");
        if (dest != null) sb.append("→ ").append(dest);
        if (engine != null) sb.append("  （模型：").append(engine).append("）");
        if (orig != null) {
            sb.append("\n\n📝 原文：\n").append(orig);
        }
        if (trans != null && !trans.isEmpty()) {
            sb.append("\n\n✅ 译文：\n").append(trans);
        }
        if (trans == null || trans.isEmpty()) {
            if (orig != null) return sb.toString() + "\n（暂未返回译文内容）";
            return fallbackPretty(result);
        }
        return sb.toString().trim();
    }

    // ---------- file 系列 / app 操作 / 网页 / 调研 / 系统 等模板（简短版） ----------

    /** file 工具：文件信息/读取/列目录（内容完整不截断） */
    private static String fileToolTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "（无文件结果）" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder();
        String path = strDeep(obj, "file_path", "path", "file", "name");
        String size = strDeep(obj, "size", "length", "file_size");
        String type = strDeep(obj, "type", "mime", "mime_type", "extension");
        String content = strDeep(obj, "content", "data", "text", "body");
        String count = strDeep(obj, "count", "total", "files_count", "num_files");

        if (count != null) {
            sb.append("📁 共 ").append(count).append(" 个文件");
            String folder = strDeep(obj, "directory", "folder", "dir", "path");
            if (folder != null) sb.append("  位于 ").append(folder);
            for (String k : new String[]{"files", "items", "list", "entries"}) {
                if (obj.has(k) && obj.get(k).isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray(k);
                    int n = Math.min(arr.size(), 5);
                    if (n > 0) sb.append("，包括：\n");
                    for (int i = 0; i < n; i++) {
                        try {
                            JsonElement e = arr.get(i);
                            String fn = null;
                            if (e.isJsonPrimitive()) fn = e.getAsString();
                            else if (e.isJsonObject()) fn = strDeep(e.getAsJsonObject(), "name", "filename", "file");
                            if (fn != null) sb.append("• ").append(truncateLines(fn, Integer.MAX_VALUE)).append("\n");
                        } catch (Exception ignored) {}
                    }
                    break;
                }
            }
        } else if (content != null) {
            if (path != null) sb.append("📄 ").append(path).append(" 内容：\n");
            sb.append(content);
        } else {
            if (path != null) sb.append("📄 ").append(path);
            if (size != null) sb.append(" · 大小 ").append(size);
            if (type != null) sb.append(" · 类型 ").append(type);
            if (sb.length() == 0) return fallbackPretty(result);
        }
        return sb.toString().trim();
    }

    /** app_operation 应用操作（完整不截断） */
    private static String appOperationTemplate(Object result) {
        if (result instanceof String) return (String) result;
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder();
        String status = strDeep(obj, "status", "code");
        String msg = strDeep(obj, "message", "msg", "info");
        String target = strDeep(obj, "target", "page", "setting", "destination");
        String count  = strDeep(obj, "count");
        String pages  = strDeep(obj, "pages", "available_pages");
        String appNm  = strDeep(obj, "app_name", "app");
        String ver    = strDeep(obj, "version", "ver");
        String pkg    = strDeep(obj, "package_name", "pkg");

        if (msg != null && !msg.isEmpty()) sb.append("✅ ").append(msg);
        else if ("success".equalsIgnoreCase(status) || status != null) sb.append("✅ 执行成功");
        if (target != null) sb.append("\n📍 目标：").append(target);
        if (appNm != null)  sb.append("\n📱 应用：").append(appNm);
        if (ver != null)    sb.append("  v").append(ver);
        if (pkg != null)    sb.append("\n📦 包名：").append(pkg);
        if (count != null && pages == null) sb.append("\n🔢 数量：").append(count);
        if (pages != null) {
            sb.append("\n📄 可用页面：").append(pages);
        }
        return sb.length() > 0 ? sb.toString().trim() : fallbackPretty(result);
    }

    /** file_reader 文件阅读（内容完整不截断） */
    private static String fileReaderTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "（无文件内容）" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder();
        String path = strDeep(obj, "file_path", "path", "file", "name");
        String content = strDeep(obj, "content", "text", "data", "body");
        String lines = strDeep(obj, "total_lines", "lines_count", "lines");
        String encoding = strDeep(obj, "encoding");
        String matches = strDeep(obj, "matches_count", "matches", "hit_count");
        String keyword = strDeep(obj, "keyword");

        if (path != null) { sb.append("📄 ").append(path); if (encoding != null) sb.append("  [").append(encoding).append("]"); }
        if (lines != null) sb.append("\n🔢 共 ").append(lines).append(" 行");
        if (keyword != null && matches != null) sb.append("\n🔍 关键词\"").append(keyword).append("\" 命中 ").append(matches).append(" 处");

        if (content != null) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(content);
        }
        if (keyword != null) {
            for (String k : new String[]{"results", "matches", "hits", "items"}) {
                if (obj.has(k) && obj.get(k).isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray(k);
                    int n = Math.min(arr.size(), 5);
                    if (n > 0) sb.append("\n\n命中前").append(n).append("条：\n");
                    for (int i = 0; i < n; i++) {
                        String line = null;
                        JsonElement e = arr.get(i);
                        if (e.isJsonPrimitive()) line = e.getAsString();
                        else if (e.isJsonObject()) line = strDeep(e.getAsJsonObject(), "line", "text", "snippet", "content");
                        if (line != null) sb.append(i + 1).append(") ").append(line).append("\n");
                    }
                    break;
                }
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : fallbackPretty(result);
    }

    /** file_analyzer 文件分析（信息完整不截断） */
    private static String fileAnalyzerTemplate(Object result) {
        if (result instanceof String) return (String) result;
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder("📊 文件分析\n");
        String path = strDeep(obj, "file_path", "path", "file", "name");
        String type = strDeep(obj, "file_type", "type", "format");
        String size = strDeep(obj, "size", "file_size", "length");
        String lines = strDeep(obj, "lines", "total_lines");
        String words = strDeep(obj, "words", "words_count", "word_count");
        String chars = strDeep(obj, "chars", "characters", "char_count");
        String summary = strDeep(obj, "summary", "analysis_summary", "overview");
        String encoding = strDeep(obj, "encoding");
        String rows = strDeep(obj, "rows", "row_count", "num_rows");
        String cols = strDeep(obj, "columns", "cols", "col_count", "column_count");
        String sheet = strDeep(obj, "sheet", "sheet_name", "worksheet");

        if (path != null) sb.append("📄 文件：").append(path).append("\n");
        if (type != null) sb.append("🏷️ 类型：").append(type).append("\n");
        if (size != null) sb.append("📦 大小：").append(size).append("\n");
        if (encoding != null) sb.append("🔠 编码：").append(encoding).append("\n");
        if (sheet != null) sb.append("📑 工作表：").append(sheet).append("\n");
        if (rows != null) { sb.append("🔢 数据行：").append(rows); if (cols != null) sb.append(" × ").append(cols).append("列"); sb.append("\n"); }
        else {
            if (lines != null) sb.append("📏 行数：").append(lines).append("\n");
            if (words != null) sb.append("📝 词数：").append(words).append("\n");
            if (chars != null) sb.append("🔤 字符：").append(chars).append("\n");
        }
        if (summary != null) {
            sb.append("\n📝 摘要：\n").append(summary);
        } else {
            for (String k : new String[]{"details", "analysis", "stats", "structure", "insights"}) {
                if (obj.has(k) && !obj.get(k).isJsonNull()) {
                    sb.append("\n📋 ").append(k).append("：\n").append(fallbackPretty(obj.get(k)));
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    /** file_generator 文件生成 */
    private static String fileGeneratorTemplate(Object result) {
        if (result instanceof String) return (String) result;
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder();
        String status = strDeep(obj, "status");
        String msg = strDeep(obj, "message", "msg");
        String action = strDeep(obj, "action", "operation", "type");
        String path = strDeep(obj, "file_path", "file_name", "path", "output", "target");
        String source = strDeep(obj, "source_path", "source");
        String size = strDeep(obj, "size", "bytes_written", "file_size");
        String count = strDeep(obj, "count", "lines_written", "written_lines");
        String rows = strDeep(obj, "rows_written", "rows", "question_count");
        String contentUri = strDeep(obj, "contentUri", "content_uri", "uri");
        String openHint = strDeep(obj, "openHint", "open_hint");

        if ("success".equalsIgnoreCase(status) || msg != null) {
            sb.append("✅ ").append(msg != null ? msg : "生成/操作成功");
        } else sb.append("📁 操作结果");
        if (action != null) sb.append("  [").append(action).append("]");
        sb.append("\n");
        if (source != null) sb.append("🔗 来源：").append(source).append("\n");
        if (path != null) sb.append("💾 路径：").append(path).append("\n");
        if (size != null) sb.append("📦 大小：").append(size).append("\n");
        if (count != null) sb.append("📝 写入：").append(count).append(" 项\n");
        if (rows != null) sb.append("📊 数据：").append(rows).append(" 行\n");
        // 包含可点击链接，引导 LLM 在回复中使用 markdown 链接格式
        if (openHint != null) {
            sb.append("\n📎 ").append(openHint).append("\n");
            sb.append("请在回复中使用此 markdown 链接格式，让用户可以点击打开文件。\n");
        } else if (contentUri != null) {
            sb.append("\n📎 文件访问链接：").append(contentUri).append("\n");
            sb.append("请在回复中用 markdown 链接格式 [文件名](链接) 告知用户，让用户可以点击打开。\n");
        }
        return sb.toString().trim();
    }

    /** webpage_reader 网页阅读，内容完整不截断 */
    private static String webpageReaderTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "（无网页内容）" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder();
        String title = strDeep(obj, "title");
        String url = strDeep(obj, "url", "link");
        String summary = strDeep(obj, "summary", "abstract", "overview", "description");
        String content = strDeep(obj, "content", "text", "body");
        String pub = strDeep(obj, "published_at", "publish_time", "date", "time");
        String author = strDeep(obj, "author", "writer", "source");
        String pages = strDeep(obj, "pages_count", "count", "total_pages", "total", "successCount");

        if (title != null) sb.append("📰 ").append(title).append("\n");
        if (url != null) sb.append("🔗 ").append(url).append("\n");
        if (author != null || pub != null) {
            sb.append("✍️ ");
            if (author != null) sb.append(author).append("  ");
            if (pub != null) sb.append(pub);
            sb.append("\n");
        }
        if (pages != null) sb.append("🔢 读取 ").append(pages).append(" 页\n");
        if (summary != null) sb.append("\n📝 摘要：\n").append(summary).append("\n");
        else if (content != null) sb.append("\n📄 内容：\n").append(content).append("\n");

        for (String k : new String[]{"results", "links", "pages", "references"}) {
            if (obj.has(k) && obj.get(k).isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray(k);
                int n = Math.min(arr.size(), 5);
                if (n > 0) sb.append("\n🔎 参考链接：\n");
                for (int i = 0; i < n; i++) {
                    try {
                        JsonElement e = arr.get(i);
                        String lk = null, lt = null;
                        if (e.isJsonPrimitive()) lk = e.getAsString();
                        else if (e.isJsonObject()) { lk = strDeep(e.getAsJsonObject(), "url", "link", "href"); lt = strDeep(e.getAsJsonObject(), "title", "name", "text"); }
                        if (lt != null) sb.append(i + 1).append(". ").append(truncateLines(lt, Integer.MAX_VALUE));
                        if (lk != null) sb.append(lt != null ? " → " : i + 1 + ". ").append(truncateLines(lk, Integer.MAX_VALUE));
                        if (lt != null || lk != null) sb.append("\n");
                    } catch (Exception ignored) {}
                }
                break;
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : fallbackPretty(result);
    }

    /** smart_research 智能研究，内容完整不截断 */
    private static String smartResearchTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "（无研究结果）" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder("🔎 智能研究\n");
        String topic = strDeep(obj, "topic", "query", "subject", "keyword");
        String summary = strDeep(obj, "summary", "conclusion", "report", "overview", "result", "answer");
        String sources = strDeep(obj, "sources_count", "count", "total_references");
        String findings = strDeep(obj, "findings", "insights", "main_points");

        if (topic != null) sb.append("📚 主题：").append(truncateLines(topic, Integer.MAX_VALUE)).append("\n");
        if (sources != null) sb.append("🔖 参考来源：").append(sources).append(" 个\n");
        if (summary != null) sb.append("\n📝 摘要：\n").append(summary).append("\n");
        else if (findings != null) sb.append("\n🧠 主要发现：\n").append(findings);
        for (String k : new String[]{"references", "sources", "links", "results", "citations"}) {
            if (obj.has(k) && obj.get(k).isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray(k);
                int n = Math.min(arr.size(), 5);
                if (n > 0) sb.append("\n📖 参考资料：\n");
                for (int i = 0; i < n; i++) {
                    try {
                        JsonElement e = arr.get(i);
                        String t = null, u = null;
                        if (e.isJsonPrimitive()) u = e.getAsString();
                        else if (e.isJsonObject()) { t = strDeep(e.getAsJsonObject(), "title", "name"); u = strDeep(e.getAsJsonObject(), "url", "link", "href", "source"); }
                        if (t != null || u != null) {
                            sb.append(i + 1).append(". ");
                            if (t != null) sb.append(truncateLines(t, Integer.MAX_VALUE));
                            if (u != null) sb.append(t != null ? " → " : "").append(truncateLines(u, Integer.MAX_VALUE));
                            sb.append("\n");
                        }
                    } catch (Exception ignored) {}
                }
                break;
            }
        }
        return sb.toString().trim();
    }

    /** system_resource 系统资源，内容完整不截断 */
    private static String systemResourceTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "（无系统结果）" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder();
        String status = strDeep(obj, "status");
        String msg = strDeep(obj, "message", "msg", "info");
        String action = strDeep(obj, "action");
        String app = strDeep(obj, "app_name", "app", "package_name");
        String url = strDeep(obj, "url", "target");
        String phone = strDeep(obj, "phone_number", "phone", "to");
        String st = strDeep(obj, "sent", "sent_status", "call_status");

        if ("success".equalsIgnoreCase(status) || msg != null) sb.append("✅ ").append(msg != null ? msg : "操作成功");
        else sb.append("📱 操作结果");
        if (action != null) sb.append("  [").append(action).append("]");
        sb.append("\n");
        if (app != null) sb.append("📱 应用：").append(app).append("\n");
        if (url != null) sb.append("🔗 地址：").append(url).append("\n");
        if (phone != null) sb.append("📞 号码：").append(phone).append("\n");
        if (st != null) sb.append("🚀 状态：").append(st).append("\n");
        for (String k : new String[]{"apps", "items", "list", "installed_apps"}) {
            if (obj.has(k) && obj.get(k).isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray(k);
                sb.append("🔢 共 ").append(arr.size()).append(" 个已安装应用");
                int n = Math.min(arr.size(), 8);
                if (n > 0) sb.append("，前").append(n).append("个：\n");
                for (int i = 0; i < n; i++) {
                    try {
                        JsonElement e = arr.get(i);
                        String nm = null;
                        if (e.isJsonPrimitive()) nm = e.getAsString();
                        else if (e.isJsonObject()) nm = strDeep(e.getAsJsonObject(), "name", "app_name", "label", "package");
                        if (nm != null) sb.append("• ").append(truncateLines(nm, Integer.MAX_VALUE)).append("\n");
                    } catch (Exception ignored) {}
                }
                break;
            }
        }
        return sb.toString().trim();
    }

    /** permission_manager 权限管理，内容完整不截断 */
    private static String permissionManagerTemplate(Object result) {
        if (result instanceof String) {
            String s = ((String) result).trim();
            return s.isEmpty() ? "（无权限结果）" : s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        StringBuilder sb = new StringBuilder("🔐 权限操作\n");
        String status = strDeep(obj, "status");
        String msg = strDeep(obj, "message", "msg");
        String perm = strDeep(obj, "permission", "perm", "name");
        String granted = strDeep(obj, "granted", "is_granted", "allowed", "status_detail");
        String req = strDeep(obj, "can_request", "requestable", "should_show");
        String exp = strDeep(obj, "explanation", "reason", "explain", "description");

        if ("success".equalsIgnoreCase(status) || msg != null) sb.insert(0, "✅ " + (msg != null ? msg + "\n" : "操作成功\n"));
        if (perm != null) sb.append("🔑 权限：").append(perm).append("\n");
        if (granted != null) sb.append("✅ 已授予：").append(granted).append("\n");
        if (req != null) sb.append("🙋 可请求：").append(req).append("\n");
        if (exp != null) sb.append("ℹ️ 说明：").append(exp).append("\n");
        for (String k : new String[]{"permissions", "items", "list", "all"}) {
            if (obj.has(k) && obj.get(k).isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray(k);
                sb.append("\n🔢 共 ").append(arr.size()).append(" 项，前").append(Math.min(arr.size(), 8)).append("项：\n");
                int n = Math.min(arr.size(), 8);
                for (int i = 0; i < n; i++) {
                    try {
                        JsonElement e = arr.get(i);
                        String nm = null;
                        if (e.isJsonPrimitive()) nm = e.getAsString();
                        else if (e.isJsonObject()) nm = strDeep(e.getAsJsonObject(), "name", "permission", "perm");
                        if (nm != null) sb.append("• ").append(truncateLines(nm, Integer.MAX_VALUE)).append("\n");
                    } catch (Exception ignored) {}
                }
                break;
            }
        }
        return sb.toString().trim();
    }

    /** python_calculate 数学计算，内容完整不截断 */
    private static String pythonCalculateTemplate(Object result) {
        if (result instanceof Number) return "🧮 计算结果：" + result;
        if (result instanceof String) {
            String s = ((String) result).trim();
            if (s.isEmpty()) return "🧮 计算完成";
            try { Double.parseDouble(s); return "🧮 计算结果：" + s; } catch (Exception ignored) {}
            return s;
        }
        JsonObject obj = toJsonObject(result);
        if (obj == null) return fallbackPretty(result);
        String expr = strDeep(obj, "expression", "expr", "formula", "input");
        String res = strDeep(obj, "result", "value", "answer", "output");
        String err = strDeep(obj, "error", "err", "exception");
        StringBuilder sb = new StringBuilder();
        if (expr != null) sb.append("🧮 表达式：").append(expr).append("\n");
        if (err != null) sb.append("❌ 计算错误：").append(err);
        else if (res != null) sb.append("✅ 结果：").append(res);
        else return fallbackPretty(result);
        return sb.toString().trim();
    }

    /** 兜底：把结果对象格式化为易读 JSON / 字符串，而非丑陋的 Map.toString()。
     *  UI 展示用，不做长度截断（本地 LLM 保护截断由 buildPromptLocal 单独处理） */
    private static String fallbackPretty(Object result) {
        if (result == null) return "（无结果）";
        if (result instanceof String) {
            String s = ((String) result).trim();
            if (s.isEmpty()) return "执行完成";
            return s;
        }
        try {
            com.google.gson.Gson pretty = new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create();
            return pretty.toJson(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    /** 从 JsonObject 中按多个候选 key 取首个非空字符串值（兼容 now 子对象，向下查一层） */
    private static String strDeep(JsonObject obj, String... keys) {
        if (obj == null) return null;
        for (String key : keys) {
            if (obj.has(key)) {
                JsonElement el = obj.get(key);
                String v = toStr(el);
                if (v != null) return v;
            }
        }
        // 向下查 now / extracted / data（天气、网页提取常用）
        for (String sub : new String[]{"now", "extracted", "data"}) {
            if (obj.has(sub) && obj.get(sub).isJsonObject()) {
                String v = strDeep(obj.getAsJsonObject(sub), keys);
                if (v != null) return v;
            }
        }
        return null;
    }

    /** JsonElement 转字符串：空值返回 null，换行/空白规整 */
    private static String toStr(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            String v = p.isString() ? p.getAsString() : p.toString();
            if (v == null) return null;
            v = v.trim();
            return v.isEmpty() ? null : v;
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() == 0) return null;
            JsonElement e0 = arr.get(0);
            if (e0.isJsonPrimitive()) {
                String v = e0.getAsString();
                return v == null || v.trim().isEmpty() ? null : v.trim();
            }
            return null;
        }
        return null;
    }

    /** 百分比，避免除 0 */
    private static String pct(int n, int total) {
        if (total <= 0) return "-";
        int p = (int) Math.round(n * 100.0 / total);
        return p + "%";
    }

    /** 多行截断：取 maxLen 内字符，内部换行替换为空格 */
    private static String truncateLines(String s, int maxLen) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return s;
        s = s.replaceAll("[\\r\\n]+", " ");
        if (s.length() <= maxLen) return s;
        return safeTruncate(s, maxLen) + "...";
    }

    /** 统计结果数量：优先数组字段，其次整体数组 */
    private static Integer countResults(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            for (String key : new String[]{"results", "data", "items", "list", "questions"}) {
                if (obj.has(key) && obj.get(key).isJsonArray()) {
                    return obj.getAsJsonArray(key).size();
                }
            }
            // total/count 数值字段
            String c = firstStr(obj, "count", "total");
            if (c != null) {
                try {
                    return Integer.parseInt(c);
                } catch (NumberFormatException ignored) {}
            }
        }
        // 整体为数组
        try {
            JsonElement root;
            if (result instanceof String) {
                root = JsonParser.parseString(((String) result).trim());
            } else if (result != null) {
                root = JsonParser.parseString(GSON.toJson(result));
            } else {
                return null;
            }
            if (root.isJsonArray()) {
                return root.getAsJsonArray().size();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 JsonObject 中按多个候选 key 取首个非空字符串值 */
    private static String firstStr(JsonObject obj, String... keys) {
        if (obj == null) {
            return null;
        }
        for (String key : keys) {
            if (obj.has(key)) {
                JsonElement el = obj.get(key);
                if (el != null && !el.isJsonNull()) {
                    if (el.isJsonPrimitive()) {
                        JsonPrimitive p = el.getAsJsonPrimitive();
                        String v = p.isString() ? p.getAsString() : p.toString();
                        if (v != null && !v.isEmpty()) {
                            return v;
                        }
                    } else if (el.isJsonArray()) {
                        JsonArray arr = el.getAsJsonArray();
                        if (arr.size() > 0) {
                            return arr.get(0).toString();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 将结果对象转为 JsonObject */
    private static JsonObject toJsonObject(Object result) {
        if (result == null) {
            return null;
        }
        try {
            if (result instanceof String) {
                String s = ((String) result).trim();
                if (s.isEmpty()) {
                    return null;
                }
                JsonElement el = JsonParser.parseString(s);
                return el.isJsonObject() ? el.getAsJsonObject() : null;
            }
            String json = GSON.toJson(result);
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            Log.w(TAG, "转JsonObject失败: " + e.getMessage());
            return null;
        }
    }

    /** 截断字符串到最大长度 */
    private static String truncate(String s) {
        if (s == null) {
            return "执行完成";
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "执行完成";
        }
        if (s.length() <= MAX_SUMMARY_LEN) {
            return s;
        }
        return safeTruncate(s, MAX_SUMMARY_LEN) + "...";
    }

    /**
     * 安全截断：避免在代理对（surrogate pair）中间截断。
     * Java 字符串以 UTF-16 存储，emoji 等增补平面字符（如 🌡💧🚨⚠️ 等）由高/低代理项两个 char 组成。
     * 若 substring 正好切在高代理项之后，会产生孤立代理项，编码为 UTF-8 即非标准字符，
     * 会导致模型输入异常、输出乱码甚至崩溃。本方法在截断时检测并回退，保证不拆散代理对。
     */
    public static String safeTruncate(String s, int maxLen) {
        if (s == null || maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        int end = maxLen;
        // 截断点前一个字符是高代理项 → 代理对将被拆散，回退一位
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 清理字符串中的孤立代理项（lone surrogate）+ U+FFFD 替换字符 + 非法控制字符。
     * 工具结果或截断后的文本中可能混入孤立代理项（如被拆散的 emoji 残留），
     * 也可能含有 U+FFFD（编码失败产生的替换字符）或不可见控制字符，
     * 这些非法 Unicode 编码为 UTF-8 后会导致模型解析崩溃或输出乱码，必须清除。
     */
    public static String sanitize(String s) {
        if (s == null || s.isEmpty()) return s;
        // 快速检测：无孤立代理项、无U+FFFD、无非法控制字符直接返回原字符串
        boolean needClean = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++; // 完整代理对，跳过低代理项
                } else {
                    needClean = true; break;
                }
            } else if (Character.isLowSurrogate(c)) {
                needClean = true; break;
            } else if (c == '\uFFFD') {
                // U+FFFD：Unicode 替换字符，编码错误的标志
                needClean = true; break;
            } else if (isIllegalControl(c)) {
                // 非法控制字符
                needClean = true; break;
            }
        }
        if (!needClean) return s;
        // 逐字符清理
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                }
                // 孤立高代理项：丢弃
            } else if (Character.isLowSurrogate(c)) {
                // 孤立低代理项：丢弃
            } else if (c == '\uFFFD') {
                // U+FFFD：丢弃
            } else if (isIllegalControl(c)) {
                // 非法控制字符：丢弃
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 判断是否为非法控制字符（允许 \n \t \r 等常用空白） */
    public static boolean isIllegalControl(char c) {
        if (c == '\n' || c == '\t' || c == '\r') return false;
        // C0 控制字符 + DEL（除了常用空白）
        if (c < 0x20) return true;
        if (c == 0x7F) return true;
        // C1 控制字符（0x80-0x9F）
        if (c >= 0x80 && c <= 0x9F) return true;
        return false;
    }

    /**
     * 检测模型输出是否为乱码。
     * 典型乱码特征：
     *  - U+FFFD 替换字符密集出现（>3 次 / 千字）
     *  - 高频"锟斤拷"等经典编码错乱片段
     *  - 大量不可见控制字符
     *  - 非 ASCII 字符占比异常低（纯英文/数字/符号堆积，中文全部丢失）
     *
     * @return true 表示疑似乱码，应触发降级
     */
    public static boolean detectGarble(String s) {
        if (s == null || s.length() < 10) return false;
        int len = s.length();

        // 1) U+FFFD 密度检测：每千字超过 3 个替换字符视为异常
        int replacementCount = 0;
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) == '\uFFFD') replacementCount++;
        }
        if (replacementCount > 3 && replacementCount * 1000.0 / len > 3.0) {
            Log.w(TAG, "检测到疑似乱码：U+FFFD 密度过高 (" + replacementCount + "/" + len + ")");
            return true;
        }

        // 2) 经典乱码片段检测：锟斤拷（UTF-8 被 GBK 解码的典型特征）
        //    以及其他常见编码错乱片段（MSVC 调试填充等）
        if (s.contains("锟斤拷") || s.contains("烫烫烫") || s.contains("屯屯屯")) {
            Log.w(TAG, "检测到疑似乱码：经典编码错乱片段");
            return true;
        }

        // 3) 控制字符密度检测（不含 \n \t \r）
        int controlCount = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (isIllegalControl(c)) controlCount++;
        }
        if (controlCount > len * 0.05) {
            Log.w(TAG, "检测到疑似乱码：控制字符密度过高 (" + controlCount + "/" + len + ")");
            return true;
        }

        // 4) 中文全丢失检测：如果原 prompt 涉及中文但输出完全无中文，可能模型解码异常
        //    （仅当同时存在异常字符时触发，避免误判纯英文回复）
        int chineseCount = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) chineseCount++;
        }
        int chineseRatio = chineseCount * 100 / len;
        if (chineseRatio == 0 && replacementCount > 0) {
            Log.w(TAG, "检测到疑似乱码：中文全丢失且含替换字符");
            return true;
        }

        return false;
    }

    /**
     * 清理模型输出：sanitize + 乱码检测。
     * 模型输出可能包含：
     *  1) 编码异常导致的 U+FFFD、孤立代理项、控制字符
     *  2) 模型幻觉产生的乱码片段（锟斤拷等）
     *  3) 混合编码导致的中文丢失
     * 本方法先清理非法字符，再检测是否为乱码，若是则返回 null 让调用方降级。
     */
    public static String cleanModelOutput(String output) {
        if (output == null) return null;
        String cleaned = sanitize(output);
        if (cleaned == null || cleaned.trim().isEmpty()) return null;
        if (detectGarble(cleaned)) {
            Log.w(TAG, "模型输出检测为乱码，触发降级");
            return null;
        }
        return cleaned;
    }

    /**
     * 解释回调接口。
     */
    public interface InterpretCallback {
        /** 解析完成 */
        void onInterpreted(String summary);

        /** 解析出错 */
        void onError(String error);
    }
}
