package com.oilquiz.app.util;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件解析工具类
 * 用于解析各种格式的文件内容
 */
public class FileParserUtil {
    private static final String TAG = "FileParserUtil";

    /**
     * 解析文本文件
     * @param file 文件对象
     * @return 文本内容
     */
    public static String parseTextFile(File file) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        } catch (IOException e) {
            Log.e(TAG, "解析文本文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析CSV文件
     * @param file CSV文件
     * @return 数据列表，每行是一个字符串数组
     */
    public static List<String[]> parseCsvFile(File file) {
        List<String[]> data = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                // 去除引号
                for (int i = 0; i < values.length; i++) {
                    values[i] = values[i].trim().replaceAll("^\"|\"$", "");
                }
                data.add(values);
            }
            return data;
        } catch (IOException e) {
            Log.e(TAG, "解析CSV文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析JSON文件为JSONObject
     * @param file JSON文件
     * @return JSONObject对象
     */
    public static JSONObject parseJsonFile(File file) {
        String content = parseTextFile(file);
        if (content != null) {
            try {
                return new JSONObject(content);
            } catch (JSONException e) {
                Log.e(TAG, "解析JSON对象失败: " + e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * 解析JSON文件为JSONArray
     * @param file JSON文件
     * @return JSONArray对象
     */
    public static JSONArray parseJsonArrayFile(File file) {
        String content = parseTextFile(file);
        if (content != null) {
            try {
                return new JSONArray(content);
            } catch (JSONException e) {
                Log.e(TAG, "解析JSON数组失败: " + e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * 解析JSON文件为Map
     * @param file JSON文件
     * @return Map对象
     */
    public static Map<String, Object> parseJsonToMap(File file) {
        JSONObject jsonObject = parseJsonFile(file);
        if (jsonObject != null) {
            return jsonObjectToMap(jsonObject);
        }
        return null;
    }

    /**
     * 将JSONObject转换为Map
     * @param jsonObject JSONObject对象
     * @return Map对象
     */
    private static Map<String, Object> jsonObjectToMap(JSONObject jsonObject) {
        Map<String, Object> map = new HashMap<>();
        try {
            JSONArray keys = jsonObject.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.getString(i);
                    Object value = jsonObject.get(key);
                    if (value instanceof JSONObject) {
                        map.put(key, jsonObjectToMap((JSONObject) value));
                    } else if (value instanceof JSONArray) {
                        map.put(key, jsonArrayToList((JSONArray) value));
                    } else {
                        map.put(key, value);
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "转换JSONObject到Map失败: " + e.getMessage(), e);
        }
        return map;
    }

    /**
     * 将JSONArray转换为List
     * @param jsonArray JSONArray对象
     * @return List对象
     */
    private static List<Object> jsonArrayToList(JSONArray jsonArray) {
        List<Object> list = new ArrayList<>();
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                Object value = jsonArray.get(i);
                if (value instanceof JSONObject) {
                    list.add(jsonObjectToMap((JSONObject) value));
                } else if (value instanceof JSONArray) {
                    list.add(jsonArrayToList((JSONArray) value));
                } else {
                    list.add(value);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "转换JSONArray到List失败: " + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 按行读取文件
     * @param file 文件对象
     * @return 行列表
     */
    public static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            Log.e(TAG, "按行读取文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取文件扩展名
     * @param file 文件对象
     * @return 扩展名（不带点）
     */
    public static String getFileExtension(File file) {
        String fileName = file.getName();
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 判断文件类型
     * @param file 文件对象
     * @return 文件类型枚举
     */
    public static FileType getFileType(File file) {
        String extension = getFileExtension(file);
        switch (extension) {
            case "txt":
                return FileType.TEXT;
            case "csv":
                return FileType.CSV;
            case "json":
                return FileType.JSON;
            case "xml":
                return FileType.XML;
            case "html":
            case "htm":
                return FileType.HTML;
            case "md":
                return FileType.MARKDOWN;
            default:
                return FileType.UNKNOWN;
        }
    }

    /**
     * 文件类型枚举
     */
    public enum FileType {
        TEXT,
        CSV,
        JSON,
        XML,
        HTML,
        MARKDOWN,
        UNKNOWN
    }
}
