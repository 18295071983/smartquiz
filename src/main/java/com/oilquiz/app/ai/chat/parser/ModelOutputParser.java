package com.oilquiz.app.ai.chat.parser;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ModelOutputParser - 模型输出解析器
 * 
 * 功能：
 * 1. 解析模型输出的结构化标记
 * 2. 识别不同类型的内容（文本、思考、工具调用、结构化数据）
 * 3. 提取 JSON 数据
 * 4. 处理流式输出的边界情况
 */
public class ModelOutputParser {

    private static final String TAG = "ModelOutputParser";

    // 输出类型
    public enum OutputType {
        TEXT,            // 普通文本
        THINKING_START,  // 思考开始
        THINKING_END,    // 思考结束
        THINKING_CONTENT,// 思考内容
        TOOL_CALL,       // 工具调用
        STRUCTURED_DATA, // 结构化数据
        ERROR,           // 错误信息
        STREAM_COMPLETE  // 流式完成
    }

    // 标记定义
    private static final String THINK_START标记 = "<think>";
    private static final String THINK_END标记 = "</think>";
    private static final String TOOL_CALL_START标记 = "<tool_call>";
    private static final String TOOL_CALL_END标记 = "</tool_call>";
    private static final String DATA_START标记 = "<数据类型=\"";
    private static final String DATA_END标记 = "</数据>";
    private static final String ERROR标记 = "<错误>";
    private static final String ERROR_END标记 = "</错误>";

    /**
     * 解析结果
     */
    public static class ParsedOutput {
        public OutputType type;
        public String content;
        public String dataType;
        public JSONObject jsonData;
        public boolean isComplete;

        public ParsedOutput(OutputType type, String content) {
            this.type = type;
            this.content = content;
            this.isComplete = true;
        }

        public ParsedOutput(OutputType type, String content, String dataType) {
            this(type, content);
            this.dataType = dataType;
        }

        public ParsedOutput(OutputType type, String content, String dataType, JSONObject jsonData) {
            this(type, content, dataType);
            this.jsonData = jsonData;
        }
    }

    /**
     * 解析 token
     */
    public static ParsedOutput parse(String token) {
        if (token == null || token.isEmpty()) {
            return new ParsedOutput(OutputType.TEXT, "");
        }

        // 检查思考标签
        if (token.contains(THINK_START标记)) {
            return new ParsedOutput(OutputType.THINKING_START, extractContentAfterTag(token, THINK_START标记));
        }
        if (token.contains(THINK_END标记)) {
            return new ParsedOutput(OutputType.THINKING_END, extractContentBeforeTag(token, THINK_END标记));
        }

        // 检查工具调用标签
        if (token.contains(TOOL_CALL_START标记)) {
            String jsonContent = extractContentBetweenTags(token, TOOL_CALL_START标记, TOOL_CALL_END标记);
            if (jsonContent != null) {
                try {
                    JSONObject jsonData = new JSONObject(jsonContent);
                    return new ParsedOutput(OutputType.TOOL_CALL, jsonContent, null, jsonData);
                } catch (JSONException e) {
                    return new ParsedOutput(OutputType.TOOL_CALL, jsonContent);
                }
            }
        }

        // 检查结构化数据标签
        if (token.contains(DATA_START标记)) {
            String dataType = extractDataType(token);
            String jsonContent = extractContentBetweenTags(token, DATA_START标记, DATA_END标记);
            if (jsonContent != null) {
                try {
                    JSONObject jsonData = new JSONObject(jsonContent);
                    return new ParsedOutput(OutputType.STRUCTURED_DATA, jsonContent, dataType, jsonData);
                } catch (JSONException e) {
                    return new ParsedOutput(OutputType.STRUCTURED_DATA, jsonContent, dataType);
                }
            }
        }

        // 检查错误标签
        if (token.contains(ERROR标记)) {
            String errorContent = extractContentBetweenTags(token, ERROR标记, ERROR_END标记);
            return new ParsedOutput(OutputType.ERROR, errorContent != null ? errorContent : token);
        }

        // 默认为普通文本
        return new ParsedOutput(OutputType.TEXT, token);
    }

    /**
     * 批量解析 token
     */
    public static java.util.List<ParsedOutput> parseBatch(java.util.List<String> tokens) {
        java.util.List<ParsedOutput> results = new java.util.ArrayList<>();
        for (String token : tokens) {
            results.add(parse(token));
        }
        return results;
    }

    /**
     * 解析完整响应
     */
    public static java.util.List<ParsedOutput> parseFullResponse(String response) {
        java.util.List<ParsedOutput> results = new java.util.ArrayList<>();
        if (response == null || response.isEmpty()) {
            return results;
        }

        // 按标记分割
        String[] parts = response.split("(?=(<思考开始>|<工具调用>|<数据类型|<错误>))");
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                results.add(parse(part));
            }
        }

        return results;
    }

    // ========== 辅助方法 ==========

    private static String extractContentAfterTag(String text, String tag) {
        int index = text.indexOf(tag);
        if (index >= 0) {
            return text.substring(index + tag.length());
        }
        return text;
    }

    private static String extractContentBeforeTag(String text, String tag) {
        int index = text.indexOf(tag);
        if (index >= 0) {
            return text.substring(0, index);
        }
        return text;
    }

    private static String extractContentBetweenTags(String text, String startTag, String endTag) {
        int startIndex = text.indexOf(startTag);
        int endIndex = text.indexOf(endTag);

        if (startIndex >= 0 && endIndex > startIndex) {
            return text.substring(startIndex + startTag.length(), endIndex);
        } else if (startIndex >= 0) {
            // 没有结束标签，返回开始标签后的内容
            return text.substring(startIndex + startTag.length());
        }
        return null;
    }

    private static String extractDataType(String text) {
        int startIndex = text.indexOf(DATA_START标记);
        int endIndex = text.indexOf("\"", startIndex + DATA_START标记.length());

        if (startIndex >= 0 && endIndex > startIndex) {
            return text.substring(startIndex + DATA_START标记.length(), endIndex);
        }
        return "unknown";
    }

    /**
     * 检查是否包含结构化标记
     */
    public static boolean hasStructuredMarkers(String text) {
        if (text == null) return false;
        return text.contains(THINK_START标记) ||
               text.contains(THINK_END标记) ||
               text.contains(TOOL_CALL_START标记) ||
               text.contains(TOOL_CALL_END标记) ||
               text.contains(DATA_START标记) ||
               text.contains(ERROR标记);
    }

    /**
     * 移除结构化标记，返回纯文本
     */
    public static String removeStructuredMarkers(String text) {
        if (text == null) return "";
        return text
            .replaceAll("<思考开始>", "")
            .replaceAll("</思考开始>", "")
            .replaceAll("<工具调用>", "")
            .replaceAll("</工具调用>", "")
            .replaceAll("<数据类型=\"[^\"]*\">", "")
            .replaceAll("</数据>", "")
            .replaceAll("<错误>", "")
            .replaceAll("</错误>", "")
            .trim();
    }
}
