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

            // 2. 单引号 → 双引号（容错：模型常输出单引号）
            cleaned = cleaned.replace('\'', '"');

            // 3. 属性名补引号：{key:value 或 ,key:value → {"key":value
            cleaned = cleaned.replaceAll("([{, ])([A-Za-z_][A-Za-z0-9_]*)(\\s*:)", "$1\"$2\"$3");

            return new JSONObject(cleaned);
        } catch (Exception e) {
            return null;
        }
    }
}
