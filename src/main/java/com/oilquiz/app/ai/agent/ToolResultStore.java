package com.oilquiz.app.ai.agent;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具执行结果存储。
 *
 * 保存工具执行的参数与结果，支持跨步骤引用。聚合流程（{@link CompositeGuideFlow}）中
 * 后续步骤可通过 paramRefs 引用前序步骤的结果（如 "$prev.city"），由
 * {@link #resolveRef(String)} 解析为具体值。
 *
 * 设计要点：
 * - 使用静态列表全局保存，所有方法 synchronized 保证线程安全
 * - {@link ToolExecution#resultJson} 缓存结果的 JSON 字符串，便于引用解析
 * - resolveRef 支持 $prev.* / $stepN.* 两种引用形式及普通字符串直返
 */
public class ToolResultStore {

    private static final String TAG = "ToolResultStore";
    private static final Gson GSON = new Gson();
    /** 匹配 URL 的正则（用于提取首个链接） */
    private static final Pattern URL_PATTERN =
            Pattern.compile("(https?://[^\\s\"'<>]+)");

    private static final List<ToolExecution> executions = Collections.synchronizedList(new ArrayList<>());

    private ToolResultStore() {}

    /**
     * 保存一次工具执行结果。
     *
     * @param toolName  工具名
     * @param params    执行参数
     * @param result    原始结果
     * @param stepIndex 在聚合流程中的步骤索引
     */
    public static synchronized void save(String toolName, Map<String, Object> params,
                                         Object result, int stepIndex) {
        ToolExecution exec = new ToolExecution();
        exec.toolName = toolName;
        exec.params = params;
        exec.result = result;
        exec.resultJson = toJsonString(result);
        exec.timestamp = System.currentTimeMillis();
        exec.stepIndex = stepIndex;
        executions.add(exec);
        Log.d(TAG, "保存工具结果: " + toolName + ", step=" + stepIndex
                + ", 总数=" + executions.size());
    }

    /**
     * 获取最近一次工具执行。
     *
     * @return 最近一次执行；无记录时返回 null
     */
    public static synchronized ToolExecution getLast() {
        if (executions.isEmpty()) {
            return null;
        }
        return executions.get(executions.size() - 1);
    }

    /**
     * 按步骤索引获取工具执行。
     *
     * @param stepIndex 步骤索引
     * @return 对应执行；不存在时返回 null
     */
    public static synchronized ToolExecution getByIndex(int stepIndex) {
        for (ToolExecution exec : executions) {
            if (exec.stepIndex == stepIndex) {
                return exec;
            }
        }
        return null;
    }

    /**
     * 解析参数引用。
     *
     * 支持的引用形式：
     * <ul>
     *   <li>$prev.city —— 取最近一次结果的 city 字段</li>
     *   <li>$prev.result —— 取最近一次的原始结果</li>
     *   <li>$prev.firstUrl —— 取最近一次结果中的第一个 URL</li>
     *   <li>$prev.lat / $prev.lon —— 取最近一次参数中的 lat / lon</li>
     *   <li>$stepN.xxx —— 取第 N 步（索引 N-1）结果的 xxx 字段</li>
     *   <li>普通字符串 —— 原样返回</li>
     * </ul>
     *
     * @param ref 引用表达式
     * @return 解析后的字符串值；无法解析时返回 null
     */
    public static synchronized String resolveRef(String ref) {
        if (ref == null || ref.isEmpty()) {
            return ref;
        }
        // 普通字符串直接返回
        if (!ref.startsWith("$")) {
            return ref;
        }
        try {
            if (ref.startsWith("$prev.")) {
                String field = ref.substring("$prev.".length());
                ToolExecution last = getLast();
                if (last == null) {
                    Log.w(TAG, "resolveRef 无前序结果: " + ref);
                    return null;
                }
                return resolvePrevField(last, field);
            }
            if (ref.startsWith("$step")) {
                // 形如 $step1.city
                int dot = ref.indexOf('.');
                if (dot < 0) {
                    return null;
                }
                String stepPart = ref.substring(0, dot);   // $step1
                String field = ref.substring(dot + 1);      // city
                int stepNum;
                try {
                    stepNum = Integer.parseInt(stepPart.substring("$step".length()));
                } catch (NumberFormatException e) {
                    return null;
                }
                ToolExecution exec = getByIndex(stepNum - 1);
                if (exec == null) {
                    Log.w(TAG, "resolveRef 无对应步骤结果: " + ref);
                    return null;
                }
                return resolvePrevField(exec, field);
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveRef 解析异常: " + ref + ", " + e.getMessage(), e);
        }
        return null;
    }

    /** 解析 $prev.* / $stepN.* 的字段值 */
    private static String resolvePrevField(ToolExecution exec, String field) {
        if ("result".equals(field)) {
            // 取原始结果的字符串形式
            return exec.result == null ? null : exec.result.toString();
        }
        if ("lat".equals(field) || "lon".equals(field)) {
            // 从参数中取坐标
            return getFromParams(exec.params, field);
        }
        if ("city".equals(field)) {
            // 优先从结果提取 city，其次从参数取
            String city = extractField(exec.result, "city");
            if (city == null || city.isEmpty()) {
                city = getFromParams(exec.params, "city");
            }
            return city;
        }
        if ("firstUrl".equals(field)) {
            return extractFirstUrl(exec.result);
        }
        // 通用字段提取
        return extractField(exec.result, field);
    }

    /** 从参数 Map 中取字符串值 */
    private static String getFromParams(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * 从结果对象中提取指定字段。
     * 支持结果为 Map（取 key）或 JSON 字符串（解析后取 key，支持嵌套点分路径）。
     *
     * @param result 结果对象
     * @param field  字段名（支持 "now.temp" 形式的点分路径）
     * @return 字段字符串值；不存在时返回 null
     */
    private static String extractField(Object result, String field) {
        JsonObject obj = toJsonObject(result);
        if (obj == null) {
            return null;
        }
        // 支持点分路径
        String[] parts = field.split("\\.");
        JsonElement current = obj;
        for (String part : parts) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return jsonElementToString(current);
    }

    /** 将结果对象转为 JsonObject */
    private static JsonObject toJsonObject(Object result) {
        if (result == null) {
            return null;
        }
        try {
            if (result instanceof Map) {
                String json = GSON.toJson(result, Map.class);
                return JsonParser.parseString(json).getAsJsonObject();
            }
            if (result instanceof String) {
                String s = ((String) result).trim();
                if (s.isEmpty()) {
                    return null;
                }
                JsonElement el = JsonParser.parseString(s);
                if (el.isJsonObject()) {
                    return el.getAsJsonObject();
                }
                return null;
            }
            // 其他对象用 Gson 转换
            String json = GSON.toJson(result);
            JsonElement el = JsonParser.parseString(json);
            if (el.isJsonObject()) {
                return el.getAsJsonObject();
            }
        } catch (Exception e) {
            Log.w(TAG, "转JsonObject失败: " + e.getMessage());
        }
        return null;
    }

    /** 将 JsonElement 转为字符串（基础类型取值，对象/数组取 toString） */
    private static String jsonElementToString(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) {
                return p.getAsString();
            }
            if (p.isBoolean()) {
                return String.valueOf(p.getAsBoolean());
            }
            return p.getAsString();
        }
        return el.toString();
    }

    /**
     * 从结果中提取第一个 URL。
     * 依次尝试：结果数组中首项的 url/link 字段、结果对象的 url/link 字段、
     * 整个结果文本中正则匹配的首个 URL。
     *
     * @param result 结果对象
     * @return 首个 URL；不存在时返回 null
     */
    private static String extractFirstUrl(Object result) {
        if (result == null) {
            return null;
        }
        try {
            // 尝试解析为 JSON 结构
            JsonElement root;
            if (result instanceof String) {
                root = JsonParser.parseString(((String) result).trim());
            } else {
                root = JsonParser.parseString(GSON.toJson(result));
            }
            // 若为数组，取首项的 url/link 字段
            if (root.isJsonArray()) {
                JsonArray arr = root.getAsJsonArray();
                if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                    String url = urlFromObject(arr.get(0).getAsJsonObject());
                    if (url != null) {
                        return url;
                    }
                }
            }
            // 若为对象，查找常见结果数组字段
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                String url = urlFromObject(obj);
                if (url != null) {
                    return url;
                }
                for (String key : new String[]{"results", "data", "items", "list"}) {
                    if (obj.has(key) && obj.get(key).isJsonArray()) {
                        JsonArray arr = obj.getAsJsonArray(key);
                        if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                            url = urlFromObject(arr.get(0).getAsJsonObject());
                            if (url != null) {
                                return url;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "解析JSON提取URL失败: " + e.getMessage());
        }
        // 降级：从文本中正则匹配首个 URL
        String text = result.toString();
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /** 从 JsonObject 中尝试取 url/link 字段 */
    private static String urlFromObject(JsonObject obj) {
        for (String key : new String[]{"url", "link", "href", "source"}) {
            if (obj.has(key)) {
                JsonElement el = obj.get(key);
                if (el != null && !el.isJsonNull() && el.isJsonPrimitive()) {
                    String v = el.getAsString();
                    if (v != null && !v.isEmpty()) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    /** 将结果对象转为 JSON 字符串缓存 */
    private static String toJsonString(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof String) {
            return (String) result;
        }
        try {
            return GSON.toJson(result);
        } catch (Exception e) {
            Log.w(TAG, "结果转JSON失败: " + e.getMessage());
            return String.valueOf(result);
        }
    }

    /**
     * 清空所有执行记录。
     */
    public static synchronized void clear() {
        executions.clear();
        Log.d(TAG, "已清空所有工具执行记录");
    }

    /**
     * 获取全部执行记录（只读副本）。
     *
     * @return 执行记录列表的副本
     */
    public static synchronized List<ToolExecution> getAll() {
        return new ArrayList<>(executions);
    }

    // ==================== 执行记录数据模型 ====================

    /**
     * 单次工具执行记录。
     */
    public static class ToolExecution {
        /** 工具名 */
        public String toolName;
        /** 执行参数 */
        public Map<String, Object> params;
        /** 原始结果 */
        public Object result;
        /** 结果的 JSON 字符串 */
        public String resultJson;
        /** 执行时间戳 */
        public long timestamp;
        /** 在聚合流程中的步骤索引 */
        public int stepIndex;

        public ToolExecution() {}
    }
}
