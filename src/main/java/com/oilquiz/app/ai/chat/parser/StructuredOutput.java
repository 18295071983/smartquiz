package com.oilquiz.app.ai.chat.parser;

import org.json.JSONObject;

/**
 * StructuredOutput - 结构化输出数据类
 * 
 * 功能：
 * 1. 定义各种结构化输出的类型
 * 2. 提供数据转换方法
 * 3. 支持自定义数据类型
 */
public class StructuredOutput {

    /**
     * 输出类型枚举
     */
    public enum Type {
        WEATHER,      // 天气
        CODE,         // 代码
        TABLE,        // 表格
        LIST,         // 列表
        CHART,        // 图表
        LINK,         // 链接
        IMAGE,        // 图片
        FILE,         // 文件
        CUSTOM        // 自定义
    }

    private final Type type;
    private final JSONObject data;
    private final String rawJson;

    public StructuredOutput(Type type, JSONObject data) {
        this.type = type;
        this.data = data;
        this.rawJson = data != null ? data.toString() : "";
    }

    public Type getType() {
        return type;
    }

    public JSONObject getData() {
        return data;
    }

    public String getRawJson() {
        return rawJson;
    }

    /**
     * 从字符串创建
     */
    public static StructuredOutput fromString(String typeStr, String jsonStr) {
        Type type = parseType(typeStr);
        try {
            JSONObject data = new JSONObject(jsonStr);
            return new StructuredOutput(type, data);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 JSONObject 创建
     */
    public static StructuredOutput fromJson(String typeStr, JSONObject data) {
        Type type = parseType(typeStr);
        return new StructuredOutput(type, data);
    }

    /**
     * 解析类型字符串
     */
    private static Type parseType(String typeStr) {
        if (typeStr == null) return Type.CUSTOM;
        switch (typeStr.toLowerCase()) {
            case "天气": return Type.WEATHER;
            case "代码": return Type.CODE;
            case "表格": return Type.TABLE;
            case "列表": return Type.LIST;
            case "图表": return Type.CHART;
            case "链接": return Type.LINK;
            case "图片": return Type.IMAGE;
            case "文件": return Type.FILE;
            default: return Type.CUSTOM;
        }
    }

    // ========== 天气数据 ==========

    public static class WeatherData {
        public String city;
        public int temp;
        public String weather;
        public int humidity;
        public String wind;
        public String icon;

        public static WeatherData fromJson(JSONObject json) {
            WeatherData data = new WeatherData();
            data.city = json.optString("city", "");
            data.temp = json.optInt("temp", 0);
            data.weather = json.optString("weather", "");
            data.humidity = json.optInt("humidity", 0);
            data.wind = json.optString("wind", "");
            data.icon = json.optString("icon", "");
            return data;
        }
    }

    // ========== 代码数据 ==========

    public static class CodeData {
        public String language;
        public String code;
        public String explanation;

        public static CodeData fromJson(JSONObject json) {
            CodeData data = new CodeData();
            data.language = json.optString("language", "");
            data.code = json.optString("code", "");
            data.explanation = json.optString("explanation", "");
            return data;
        }
    }

    // ========== 表格数据 ==========

    public static class TableData {
        public String[] headers;
        public String[][] rows;

        public static TableData fromJson(JSONObject json) {
            TableData data = new TableData();
            try {
                org.json.JSONArray headersArray = json.optJSONArray("headers");
                if (headersArray != null) {
                    data.headers = new String[headersArray.length()];
                    for (int i = 0; i < headersArray.length(); i++) {
                        data.headers[i] = headersArray.getString(i);
                    }
                }
                org.json.JSONArray rowsArray = json.optJSONArray("rows");
                if (rowsArray != null) {
                    data.rows = new String[rowsArray.length()][];
                    for (int i = 0; i < rowsArray.length(); i++) {
                        org.json.JSONArray rowArray = rowsArray.getJSONArray(i);
                        data.rows[i] = new String[rowArray.length()];
                        for (int j = 0; j < rowArray.length(); j++) {
                            data.rows[i][j] = rowArray.getString(j);
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略解析错误
            }
            return data;
        }
    }

    // ========== 列表数据 ==========

    public static class ListData {
        public String title;
        public String[] items;

        public static ListData fromJson(JSONObject json) {
            ListData data = new ListData();
            data.title = json.optString("title", "");
            try {
                org.json.JSONArray itemsArray = json.optJSONArray("items");
                if (itemsArray != null) {
                    data.items = new String[itemsArray.length()];
                    for (int i = 0; i < itemsArray.length(); i++) {
                        data.items[i] = itemsArray.getString(i);
                    }
                }
            } catch (Exception e) {
                // 忽略解析错误
            }
            return data;
        }
    }

    // ========== 链接数据 ==========

    public static class LinkData {
        public String title;
        public String url;
        public String description;

        public static LinkData fromJson(JSONObject json) {
            LinkData data = new LinkData();
            data.title = json.optString("title", "");
            data.url = json.optString("url", "");
            data.description = json.optString("description", "");
            return data;
        }
    }
}
