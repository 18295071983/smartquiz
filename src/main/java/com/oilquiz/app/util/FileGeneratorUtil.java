package com.oilquiz.app.util;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map;

/**
 * 文件生成工具类
 * 用于生成各种格式的文件
 */
public class FileGeneratorUtil {
    private static final String TAG = "FileGeneratorUtil";

    /**
     * 生成文本文件
     * @param file 文件对象
     * @param content 文本内容
     * @return 是否成功
     */
    public static boolean generateTextFile(File file, String content) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), "UTF-8")) {
            writer.write(content);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "生成文本文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成CSV文件
     * @param file 文件对象
     * @param data 数据列表，每行是一个字符串数组
     * @return 是否成功
     */
    public static boolean generateCsvFile(File file, List<String[]> data) {
        StringBuilder content = new StringBuilder();
        for (String[] row : data) {
            for (int i = 0; i < row.length; i++) {
                String value = row[i];
                // 如果包含逗号或引号，需要用引号括起来
                if (value.contains(",") || value.contains("\"")) {
                    value = "\"" + value.replace("\"", "\"\"") + "\"";
                }
                content.append(value);
                if (i < row.length - 1) {
                    content.append(",");
                }
            }
            content.append("\n");
        }
        return generateTextFile(file, content.toString());
    }

    /**
     * 生成JSON文件（从JSONObject）
     * @param file 文件对象
     * @param jsonObject JSONObject对象
     * @param prettyPrint 是否美化输出
     * @return 是否成功
     */
    public static boolean generateJsonFile(File file, JSONObject jsonObject, boolean prettyPrint) {
        try {
            String content = prettyPrint ? jsonObject.toString(2) : jsonObject.toString();
            return generateTextFile(file, content);
        } catch (JSONException e) {
            Log.e(TAG, "生成JSON文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成JSON文件（从JSONArray）
     * @param file 文件对象
     * @param jsonArray JSONArray对象
     * @param prettyPrint 是否美化输出
     * @return 是否成功
     */
    public static boolean generateJsonFile(File file, JSONArray jsonArray, boolean prettyPrint) {
        try {
            String content = prettyPrint ? jsonArray.toString(2) : jsonArray.toString();
            return generateTextFile(file, content);
        } catch (JSONException e) {
            Log.e(TAG, "生成JSON文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成JSON文件（从Map）
     * @param file 文件对象
     * @param map Map对象
     * @param prettyPrint 是否美化输出
     * @return 是否成功
     */
    public static boolean generateJsonFile(File file, Map<String, Object> map, boolean prettyPrint) {
        JSONObject jsonObject = mapToJsonObject(map);
        if (jsonObject != null) {
            return generateJsonFile(file, jsonObject, prettyPrint);
        }
        return false;
    }

    /**
     * 将Map转换为JSONObject
     * @param map Map对象
     * @return JSONObject对象
     */
    private static JSONObject mapToJsonObject(Map<String, Object> map) {
        JSONObject jsonObject = new JSONObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            try {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Map) {
                    jsonObject.put(key, mapToJsonObject((Map<String, Object>) value));
                } else if (value instanceof List) {
                    jsonObject.put(key, listToJsonArray((List<Object>) value));
                } else {
                    jsonObject.put(key, value);
                }
            } catch (Exception e) {
                Log.e(TAG, "转换Map到JSONObject失败: " + e.getMessage(), e);
            }
        }
        return jsonObject;
    }

    /**
     * 将List转换为JSONArray
     * @param list List对象
     * @return JSONArray对象
     */
    private static JSONArray listToJsonArray(List<Object> list) {
        JSONArray jsonArray = new JSONArray();
        for (Object value : list) {
            try {
                if (value instanceof Map) {
                    jsonArray.put(mapToJsonObject((Map<String, Object>) value));
                } else if (value instanceof List) {
                    jsonArray.put(listToJsonArray((List<Object>) value));
                } else {
                    jsonArray.put(value);
                }
            } catch (Exception e) {
                Log.e(TAG, "转换List到JSONArray失败: " + e.getMessage(), e);
            }
        }
        return jsonArray;
    }

    /**
     * 生成HTML文件
     * @param file 文件对象
     * @param title 页面标题
     * @param body 页面主体内容
     * @return 是否成功
     */
    public static boolean generateHtmlFile(File file, String title, String body) {
        StringBuilder content = new StringBuilder();
        content.append("<!DOCTYPE html>\n");
        content.append("<html>\n");
        content.append("<head>\n");
        content.append("    <meta charset=\"UTF-8\">\n");
        content.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        content.append("    <title>").append(title).append("</title>\n");
        content.append("    <style>\n");
        content.append("        body { font-family: Arial, sans-serif; margin: 20px; line-height: 1.6; }\n");
        content.append("        h1, h2, h3 { color: #333; }\n");
        content.append("        table { border-collapse: collapse; width: 100%; margin: 20px 0; }\n");
        content.append("        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n");
        content.append("        th { background-color: #f2f2f2; }\n");
        content.append("    </style>\n");
        content.append("</head>\n");
        content.append("<body>\n");
        content.append(body);
        content.append("</body>\n");
        content.append("</html>");
        return generateTextFile(file, content.toString());
    }

    /**
     * 生成简单的Markdown文件
     * @param file 文件对象
     * @param title 标题
     * @param content 内容
     * @return 是否成功
     */
    public static boolean generateMarkdownFile(File file, String title, String content) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(title).append("\n\n");
        markdown.append(content);
        return generateTextFile(file, markdown.toString());
    }

    /**
     * 生成包含表格的CSV文件
     * @param file 文件对象
     * @param headers 表头
     * @param rows 数据行
     * @return 是否成功
     */
    public static boolean generateTableCsvFile(File file, String[] headers, List<String[]> rows) {
        StringBuilder content = new StringBuilder();
        // 添加表头
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i];
            if (header.contains(",") || header.contains("\"")) {
                header = "\"" + header.replace("\"", "\"\"") + "\"";
            }
            content.append(header);
            if (i < headers.length - 1) {
                content.append(",");
            }
        }
        content.append("\n");
        // 添加数据行
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                String value = row[i];
                if (value.contains(",") || value.contains("\"")) {
                    value = "\"" + value.replace("\"", "\"\"") + "\"";
                }
                content.append(value);
                if (i < row.length - 1) {
                    content.append(",");
                }
            }
            content.append("\n");
        }
        return generateTextFile(file, content.toString());
    }

    /**
     * 生成包含表格的HTML文件
     * @param file 文件对象
     * @param title 页面标题
     * @param headers 表头
     * @param rows 数据行
     * @return 是否成功
     */
    public static boolean generateTableHtmlFile(File file, String title, String[] headers, List<String[]> rows) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(title).append("</h1>\n");
        body.append("<table>\n");
        // 添加表头
        body.append("  <tr>\n");
        for (String header : headers) {
            body.append("    <th>").append(header).append("</th>\n");
        }
        body.append("  </tr>\n");
        // 添加数据行
        for (String[] row : rows) {
            body.append("  <tr>\n");
            for (String cell : row) {
                body.append("    <td>").append(cell).append("</td>\n");
            }
            body.append("  </tr>\n");
        }
        body.append("</table>\n");
        return generateHtmlFile(file, title, body.toString());
    }

    /**
     * 追加内容到文件
     * @param file 文件对象
     * @param content 要追加的内容
     * @return 是否成功
     */
    public static boolean appendToFile(File file, String content) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, true), "UTF-8")) {
            writer.write(content);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "追加内容到文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 追加行到文件
     * @param file 文件对象
     * @param line 要追加的行
     * @return 是否成功
     */
    public static boolean appendLineToFile(File file, String line) {
        return appendToFile(file, line + "\n");
    }
}
