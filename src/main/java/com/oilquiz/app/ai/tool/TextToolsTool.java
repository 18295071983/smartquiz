package com.oilquiz.app.ai.tool;

import android.content.Context;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本处理工具：纯 Java 本地实现，零网络/零依赖。
 * 支持：JSON 格式化/校验、大小写转换、Base64 编解码、URL 编解码、
 * 正则提取、字数统计、去空白。
 *
 * 参数：
 * - action: 操作名（json_format/json_validate/upper/lower/base64_encode/base64_decode/
 *           url_encode/url_decode/regex_extract/count/trim）
 * - text: 要处理的文本（regex_extract 额外需要 pattern）
 */
public class TextToolsTool implements AITool {

    private static final String TAG = "TextToolsTool";

    public TextToolsTool() {
    }

    public TextToolsTool(Context context) {
    }

    @Override
    public String getName() {
        return "text_tools";
    }

    @Override
    public String getDescription() {
        return "文本处理工具（纯本地）：JSON 格式化/校验（json_format/json_validate）、"
                + "大小写转换（upper/lower）、Base64 编解码（base64_encode/base64_decode）、"
                + "URL 编解码（url_encode/url_decode）、正则提取（regex_extract，需 pattern 参数）、"
                + "字数统计（count）、去空白（trim）。"
                + "适合整理/校验/转换文本数据，无需联网。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作名：json_format / json_validate / upper / lower / base64_encode / base64_decode / url_encode / url_decode / regex_extract / count / trim");
        params.put("text", "要处理的文本内容");
        params.put("pattern", "正则表达式（仅 regex_extract 需要）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")).trim().toLowerCase() : "";
            Object textObj = parameters.get("text");
            String text = textObj != null ? String.valueOf(textObj) : "";

            if (action.isEmpty()) {
                return AIToolResult.fail("缺少参数: action");
            }

            switch (action) {
                case "json_format": {
                    org.json.JSONTokener tokener = new org.json.JSONTokener(text);
                    Object parsed = tokener.nextValue();
                    String formatted = parsed instanceof org.json.JSONObject
                            ? ((org.json.JSONObject) parsed).toString(2)
                            : parsed instanceof org.json.JSONArray
                                ? ((org.json.JSONArray) parsed).toString(2)
                                : String.valueOf(parsed);
                    return successResult(action, formatted);
                }
                case "json_validate": {
                    try {
                        Object parsed = new org.json.JSONTokener(text).nextValue();
                        if (parsed == null || org.json.JSONObject.NULL.equals(parsed)) {
                            return AIToolResult.fail("JSON 无效：空值或格式错误");
                        }
                        Map<String, Object> info = new HashMap<>();
                        info.put("valid", true);
                        info.put("type", parsed instanceof org.json.JSONArray ? "array" : "object");
                        return AIToolResult.success("JSON 合法（" + (parsed instanceof org.json.JSONArray ? "数组" : "对象") + "）", info);
                    } catch (Exception e) {
                        return AIToolResult.fail("JSON 无效: " + e.getMessage());
                    }
                }
                case "upper":
                    return successResult(action, text.toUpperCase(java.util.Locale.ROOT));
                case "lower":
                    return successResult(action, text.toLowerCase(java.util.Locale.ROOT));
                case "base64_encode": {
                    String encoded = Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return successResult(action, encoded);
                }
                case "base64_decode": {
                    try {
                        byte[] decoded = Base64.getDecoder().decode(text.trim());
                        return successResult(action, new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException e) {
                        return AIToolResult.fail("Base64 解码失败: " + e.getMessage());
                    }
                }
                case "url_encode":
                    return successResult(action, java.net.URLEncoder.encode(text, "UTF-8"));
                case "url_decode":
                    return successResult(action, java.net.URLDecoder.decode(text, "UTF-8"));
                case "regex_extract": {
                    String patternStr = parameters.get("pattern") != null
                            ? String.valueOf(parameters.get("pattern")) : "";
                    if (patternStr.isEmpty()) {
                        return AIToolResult.fail("regex_extract 需要 pattern 参数");
                    }
                    Pattern p = Pattern.compile(patternStr);
                    Matcher m = p.matcher(text);
                    java.util.List<String> matches = new java.util.ArrayList<>();
                    while (m.find()) {
                        matches.add(m.groupCount() > 0 ? m.group(1) : m.group());
                    }
                    String summary = matches.isEmpty()
                            ? "无匹配" : "匹配 " + matches.size() + " 处: " + matches.toString();
                    return AIToolResult.success(summary,
                            java.util.Collections.singletonMap("matches", matches));
                }
                case "count": {
                    int chars = text.length();
                    int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
                    return AIToolResult.success("字符数: " + chars + "，单词数: " + words,
                            new HashMap<String, Object>() {{
                                put("chars", chars);
                                put("words", words);
                            }});
                }
                case "trim":
                    return successResult(action, text.trim());
                default:
                    return AIToolResult.fail("未知 action: " + action);
            }
        } catch (Exception e) {
            return AIToolResult.fail("文本处理失败: " + e.getMessage());
        }
    }

    private AIToolResult successResult(String action, String result) {
        Map<String, Object> info = new HashMap<>();
        info.put("action", action);
        info.put("result", result);
        return AIToolResult.success(result, info);
    }
}
