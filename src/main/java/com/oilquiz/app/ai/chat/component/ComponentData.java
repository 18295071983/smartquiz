package com.oilquiz.app.ai.chat.component;

import org.json.JSONObject;

/**
 * 结构化 UI 组件数据。
 *
 * 由工具（工具侧桥接）或模型（component 代码块）生成，对话界面通过
 * {@link ComponentRegistry} 匹配对应的 {@link ChatComponent} 渲染成 View。
 *
 * 示例（chart 组件）：
 * <pre>
 * {"type":"chart","props":{
 *     "chartType":"bar",
 *     "title":"每月销量",
 *     "categories":["1月","2月","3月"],
 *     "series":[{"name":"销量","data":[120,200,150]}]
 * }}
 * </pre>
 */
public class ComponentData {

    /** 组件 ID（2026-09-14）：由 ChatIdDispatcher 发放 COMPONENT 子 id（T1-C1、T1-C2…），
     *  适配器按 id 定位/更新/移除单个组件；Gson 持久化（会话恢复后 id 保留） */
    public String id;

    /** 组件类型标识，如 "chart"、"info_card"、"file_card"、"image_grid"、"weather_card"、"todo_card" */
    public String type;

    /** 组件属性（JSON 对象） */
    public JSONObject props;

    public ComponentData() {
    }

    public ComponentData(String type, JSONObject props) {
        this.type = type;
        this.props = props;
    }

    public String getType() {
        return type;
    }

    public JSONObject getProps() {
        return props;
    }

    /** 便捷工厂：创建组件数据 */
    public static ComponentData of(String type, JSONObject props) {
        return new ComponentData(type, props);
    }

    /**
     * 序列化为可持久化结构（Gson 无法直接序列化 org.json.JSONObject，
     * 由 ChatHistoryManager 的 TypeAdapter 以 Gson JsonObject 结构持久化，
     * 此处保留便利方法供兼容）。
     */
    public String toPersistableJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", type);
            obj.put("props", props != null ? props : new JSONObject());
            return obj.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从持久化 JSON 字符串还原（{@link #toPersistableJson()} 的逆操作）。
     *  优先用 Gson 解析（正确处理转义），失败再走容错解析。 */
    public static ComponentData fromPersistableJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
            if (el != null && el.isJsonObject()) {
                com.google.gson.JsonObject obj = el.getAsJsonObject();
                String type = obj.has("type") && !obj.get("type").isJsonNull()
                        ? obj.get("type").getAsString() : null;
                if (type == null || type.isEmpty()) return null;
                org.json.JSONObject props = gsonObjToOrgJson(obj.get("props"));
                return new ComponentData(type, props != null ? props : new JSONObject());
            }
        } catch (Exception ignored) {
        }
        return fromJson(json);
    }

    /** Gson JsonElement → org.json 值（递归，兼容 JSONArray） */
    private static Object gsonElementToOrgJson(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return org.json.JSONObject.NULL;
        if (el.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        if (el.isJsonArray()) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                arr.put(gsonElementToOrgJson(child));
            }
            return arr;
        }
        if (el.isJsonObject()) {
            org.json.JSONObject obj = new org.json.JSONObject();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : el.getAsJsonObject().entrySet()) {
                try {
                    obj.put(e.getKey(), gsonElementToOrgJson(e.getValue()));
                } catch (org.json.JSONException ignored) {}
            }
            return obj;
        }
        return null;
    }

    private static org.json.JSONObject gsonObjToOrgJson(com.google.gson.JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        Object converted = gsonElementToOrgJson(el);
        return converted instanceof org.json.JSONObject ? (org.json.JSONObject) converted : null;
    }

    /**
     * 从 JSON 字符串解析组件数据（容错解析）。
     *
     * 兼容模型输出常见的不规范格式：
     * - JSON 前后夹杂说明文字（自动提取第一个 { 到最后一个 } 之间的部分）
     * - 单引号代替双引号（自动转换为双引号）
     * - 属性名未加引号（自动补引号）
     *
     * @param json 形如 {"type":"chart","props":{...}} 的 JSON 字符串
     * @return 解析成功返回组件数据，失败返回 null
     */
    public static ComponentData fromJson(String json) {
        try {
            JSONObject obj = parseJsonObject(json);
            if (obj == null) return null;
            String type = obj.optString("type", null);
            if (type == null || type.isEmpty()) return null;
            JSONObject props = obj.optJSONObject("props");
            if (props == null) props = new JSONObject();
            return new ComponentData(type, props);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 容错解析 JSON 对象字符串。
     *
     * 用于组件标记（内容流中 ```component:xxx {props}``` 的 props 直接是 JSON 对象内容）：
     * 自动提取 { } 主体、单引号转双引号、未加引号的属性名补引号。
     *
     * @param json JSON 对象字符串（允许前后有杂文本）
     * @return 解析后的 JSONObject；无法解析返回 null
     */
    public static JSONObject parseJsonObject(String json) {
        try {
            if (json == null || json.trim().isEmpty()) return null;
            String cleaned = json.trim();

            // 1. 提取 JSON 对象主体（第一个 { 到最后一个 }，容忍前后杂文本）
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            cleaned = cleaned.substring(start, end + 1);

            // 2. 智能引号修复：扫描式处理（关键！不能全局 replace 单引号——会破坏
            //    html/content 等字符串值内部的单引号，如 <div style='color:red'>）。
            //    只把「字符串边界」统一为双引号，字符串内部原样保留；
            //    单引号字符串内部的未转义双引号自动转义。
            cleaned = fixQuotes(cleaned);

            // 3. 属性名补引号：{key:value 或 ,key:value → {"key":value
            //    仅匹配「冒号前紧跟字母/下划线」的未加引号键（已加引号的键不受影响）
            cleaned = cleaned.replaceAll("([{,]) *([A-Za-z_][A-Za-z0-9_]*)(\\s*:)", "$1\"$2\"$3");

            // 4. 直接构造；若含未转义控制字符则逐字符清理
            try {
                return new JSONObject(cleaned);
            } catch (Exception e) {
                // 清理 JSON 值中的非法控制字符（模型偶尔输出真实换行/制表符）
                StringBuilder sb = new StringBuilder(cleaned.length());
                for (int i = 0; i < cleaned.length(); i++) {
                    char c = cleaned.charAt(i);
                    if (c == '\n' || c == '\r' || c == '\t') {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
                }
                return new JSONObject(sb.toString());
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 扫描式引号修复：字符串边界统一为双引号，字符串内部内容原样保留。
     * - 字符串外的 ' 或 " 作为边界 → 输出双引号
     * - 单引号字符串内的未转义双引号 → 转义为 \"
     * - 双引号字符串内的单引号 → 原样保留（html 属性如 style='color:red'）
     * - 转义序列（\\ 与 \x）跳过
     */
    private static String fixQuotes(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        char quoteChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    sb.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    sb.append(c);
                    escaped = true;
                } else if (c == quoteChar) {
                    // 边界闭合 → 双引号
                    inString = false;
                    sb.append('"');
                } else if (c == '"' && quoteChar == '\'') {
                    // 单引号字符串内的双引号（未转义）→ 转义
                    sb.append("\\\"");
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '\'' || c == '"') {
                    inString = true;
                    quoteChar = c;
                    sb.append('"');
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
