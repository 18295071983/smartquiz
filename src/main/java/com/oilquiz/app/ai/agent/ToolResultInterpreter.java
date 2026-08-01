package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 工具结果解释器。
 *
 * 工具执行后调用 LLM 将原始结果解析为简短的自然语言摘要（1-2 句话）。
 * 优先使用在线模型解析；若在线不可用、调用失败或超时（10秒），则降级为离线模板解析。
 *
 * 该类只做结果摘要，不涉及工具选择，属于轻量 LLM 调用。
 *
 * 设计要点：
 * - 在线解析复用 {@link OnlineInferenceService}，关闭工具调用（enableTools=false）
 * - 在线调用在后台线程执行，结果通过主线程 Handler 回调
 * - 离线模板按工具名分支生成摘要，解析失败时返回结果的截断文本
 */
public class ToolResultInterpreter {

    private static final String TAG = "ToolResultInterpreter";
    /** 在线解析超时时间（秒） */
    private static final long ONLINE_TIMEOUT_SECONDS = 30L;
    /** 在线解析最大 token 数 */
    private static final int ONLINE_MAX_TOKENS = 1024;
    /** 摘要最大长度（离线模板兜底用，在线不截断） */
    private static final int MAX_SUMMARY_LEN = 2000;
    /** 输入结果转 prompt 的截断长度（基本不截断） */
    private static final int MAX_INPUT_LEN = 8000;

    private static final Gson GSON = new Gson();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ToolResultInterpreter");
        t.setDaemon(true);
        return t;
    });

    private ToolResultInterpreter() {}

    /**
     * 解释工具执行结果。
     *
     * @param ctx      上下文
     * @param toolName 工具名
     * @param result   原始结果
     * @param callback 解释回调
     */
    public static void interpret(Context ctx, String toolName, Object result, InterpretCallback callback) {
        if (callback == null) {
            return;
        }
        executor.execute(() -> {
            String summary = null;
            try {
                // 先尝试在线模型解析
                summary = interpretOnline(ctx, toolName, result);
            } catch (Exception e) {
                Log.w(TAG, "在线解析失败,降级模板: " + e.getMessage());
            }
            // 在线不可用或失败，使用离线模板
            if (summary == null || summary.trim().isEmpty()) {
                summary = templateInterpret(toolName, result);
            }
            final String finalSummary = summary;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    callback.onInterpreted(finalSummary);
                } catch (Exception e) {
                    Log.e(TAG, "解释回调异常: " + e.getMessage(), e);
                }
            });
        });
    }

    /**
     * 在线模型解析。
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
        String prompt = buildPrompt(toolName, result);
        // 关闭工具调用，纯文本摘要
        String summary = service.generateAsync(prompt, config, new ArrayList<ChatMessage>(),
                ONLINE_MAX_TOKENS, false)
                .get(ONLINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (summary == null) {
            return null;
        }
        summary = summary.trim();
        return summary.isEmpty() ? null : summary;
    }

    /** 构建在线解析 prompt */
    private static String buildPrompt(String toolName, Object result) {
        String resultStr;
        if (result == null) {
            resultStr = "无结果";
        } else if (result instanceof String) {
            resultStr = (String) result;
        } else {
            try {
                resultStr = GSON.toJson(result);
            } catch (Exception e) {
                resultStr = String.valueOf(result);
            }
        }
        if (resultStr.length() > MAX_INPUT_LEN) {
            resultStr = resultStr.substring(0, MAX_INPUT_LEN) + "...";
        }
        return "你是智能助手。我刚执行了「" + toolName + "」工具，得到了以下结果数据。\n"
                + "请根据结果用自然语言给用户一个详细、有用的回答，就像你在和用户对话一样。\n"
                + "要求：\n"
                + "1. 直接说人话，不要提「工具」「结果」「数据」这些词\n"
                + "2. 提取关键信息，组织成通顺的中文回答\n"
                + "3. 如果是天气，告诉用户今天天气怎么样、温度多少、要不要带伞等实用建议\n"
                + "4. 如果是搜索，告诉用户搜到了什么、重点信息是什么\n"
                + "5. 如果是数据库查询，告诉用户查到了几道题、主要内容是什么\n"
                + "6. 如果是定位，告诉用户当前位置在哪\n"
                + "7. 如果是翻译，直接给出翻译结果\n"
                + "8. 回答要有温度，像朋友聊天一样\n\n"
                + "结果数据：\n" + resultStr;
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
                return truncate(String.valueOf(result));
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
                default:
                    return truncate(String.valueOf(result));
            }
        } catch (Exception e) {
            Log.w(TAG, "模板解析异常: " + e.getMessage());
            return truncate(String.valueOf(result));
        }
    }

    /** 天气模板：提取城市/温度/天气描述 → "北京今天晴,25°C" */
    private static String weatherTemplate(Object result, String toolName) {
        JsonObject obj = toJsonObject(result);
        if (obj == null) {
            return truncate(String.valueOf(result));
        }
        // 城市：优先顶层，其次 location 对象
        String city = firstStr(obj, "city", "cityName", "name");
        if (city == null && obj.has("location") && obj.get("location").isJsonObject()) {
            city = firstStr(obj.getAsJsonObject("location"), "city", "name");
        }
        // 温度与天气描述：优先 now 对象，其次顶层
        String temp = null;
        String text = null;
        if (obj.has("now") && obj.get("now").isJsonObject()) {
            JsonObject now = obj.getAsJsonObject("now");
            temp = firstStr(now, "temp", "temperature");
            text = firstStr(now, "text", "desc", "description");
        }
        if (temp == null) {
            temp = firstStr(obj, "temp", "temperature");
        }
        if (text == null) {
            text = firstStr(obj, "text", "desc", "description");
        }

        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isEmpty()) {
            sb.append(city).append("今天");
        } else {
            sb.append("今天");
        }
        if (text != null && !text.isEmpty()) {
            sb.append(text);
        }
        if (temp != null && !temp.isEmpty()) {
            sb.append(",").append(temp).append("°C");
        }
        if (sb.length() == 0 || sb.toString().equals("今天")) {
            return truncate(String.valueOf(result));
        }
        return sb.toString();
    }

    /** 联网搜索模板：提取结果数量和前几条标题 */
    private static String networkSearchTemplate(Object result) {
        Integer count = countResults(result);
        StringBuilder sb = new StringBuilder();
        if (count != null) {
            sb.append("搜到了").append(count).append("条结果");
        }
        // 尝试提取前3条标题
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            for (String key : new String[]{"results", "data", "items", "list", "webpages"}) {
                if (obj.has(key) && obj.get(key).isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray(key);
                    int limit = Math.min(arr.size(), 3);
                    if (limit > 0) {
                        sb.append("，重点有：\n");
                        for (int i = 0; i < limit; i++) {
                            JsonObject item = arr.get(i).getAsJsonObject();
                            String title = firstStr(item, "title", "name", "snippet");
                            if (title != null) {
                                sb.append("• ").append(title.length() > 60 ? title.substring(0, 60) + "..." : title).append("\n");
                            }
                        }
                    }
                    break;
                }
            }
        }
        if (sb.length() > 0) {
            return sb.toString().trim();
        }
        return truncate(String.valueOf(result));
    }

    /** 数据库模板：提取题目数量和前几道题的内容 */
    private static String databaseTemplate(Object result) {
        Integer count = countResults(result);
        StringBuilder sb = new StringBuilder();
        if (count != null) {
            sb.append("查到了").append(count).append("道相关题目");
        } else {
            JsonObject obj = toJsonObject(result);
            if (obj != null) {
                String c = firstStr(obj, "count", "total", "questionCount");
                if (c != null) {
                    sb.append("查到了").append(c).append("道相关题目");
                    count = -1;
                }
            }
        }
        // 尝试提取前3道题的内容
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            for (String key : new String[]{"results", "data", "items", "list", "questions"}) {
                if (obj.has(key) && obj.get(key).isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray(key);
                    int limit = Math.min(arr.size(), 3);
                    if (limit > 0) {
                        sb.append("，包括：\n");
                        for (int i = 0; i < limit; i++) {
                            JsonObject item = arr.get(i).getAsJsonObject();
                            String question = firstStr(item, "question", "title", "content", "text");
                            if (question != null) {
                                sb.append("• ").append(question.length() > 80 ? question.substring(0, 80) + "..." : question).append("\n");
                            }
                        }
                    }
                    break;
                }
            }
        }
        if (sb.length() > 0) {
            return sb.toString().trim();
        }
        return truncate(String.valueOf(result));
    }

    /** 定位模板：提取城市 → "当前位置:北京" */
    private static String locationTemplate(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            String city = firstStr(obj, "city", "cityName", "name", "locality");
            if (city != null && !city.isEmpty()) {
                return "当前位置:" + city;
            }
        }
        return truncate(String.valueOf(result));
    }

    /** 翻译模板：返回翻译结果前100字 */
    private static String translationTemplate(Object result) {
        if (result == null) {
            return "翻译完成";
        }
        String text;
        if (result instanceof String) {
            text = (String) result;
        } else {
            JsonObject obj = toJsonObject(result);
            if (obj != null) {
                String t = firstStr(obj, "result", "translation", "text", "translatedText");
                text = t != null ? t : String.valueOf(result);
            } else {
                text = String.valueOf(result);
            }
        }
        return truncate(text);
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
        return s.substring(0, MAX_SUMMARY_LEN) + "...";
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
