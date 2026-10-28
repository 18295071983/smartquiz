package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Tool;

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
@Tool(value = "text_tools", category = "utility")
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
                + "URL 编解码（url_encode/url_decode）、正则提取（regex_extract，需 pattern 参数；"
                + "返回 matches=完整匹配与 groups=每条匹配的捕获组数组，可用 group_index 只要某一组）、"
                + "字数统计（count）、去空白（trim）。"
                + "适合整理/校验/转换文本数据，无需联网。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作名：json_format / json_validate / upper / lower / base64_encode / base64_decode / url_encode / url_decode / regex_extract / count / trim");
        params.put("text", "要处理的文本内容");
        params.put("pattern", "正则表达式（仅 regex_extract 需要）");
        params.put("group_index", "regex_extract 可选：只要第几个组（0=完整匹配，1..n=捕获组）；不传则同时给 matches 与 groups");
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
                    // group_index（可选）：只取某一个捕获组；不传时返回完整匹配 + groups。
                    // 此前无条件只返回 group(1)，多组正则（如 (\d{4})-(\d{2})-(\d{2})、
                    // "([A-Z]-\d+[A-Z]?)\s*振动([\d.]+)"）会把月/日、把数值整列丢掉——
                    // 位号与数值成对提取时配对关系直接断掉（2026-10-29 真机实测）。
                    Integer groupIndex = null;
                    Object gi = parameters.get("group_index");
                    if (gi != null && !String.valueOf(gi).trim().isEmpty()) {
                        try {
                            groupIndex = Integer.parseInt(String.valueOf(gi).trim());
                        } catch (NumberFormatException e) {
                            return AIToolResult.fail("group_index 必须是整数（0=完整匹配，1..n=第几个捕获组）");
                        }
                    }
                    java.util.List<String> matches = new java.util.ArrayList<>();
                    java.util.List<java.util.List<String>> groups = new java.util.ArrayList<>();
                    int maxGroup = p.matcher("").groupCount();
                    if (groupIndex != null && (groupIndex < 0 || groupIndex > maxGroup)) {
                        return AIToolResult.fail("group_index 越界: " + groupIndex
                                + "（该正则的组号范围 0.." + maxGroup + "，0=完整匹配）");
                    }
                    while (m.find()) {
                        if (groupIndex != null) {
                            matches.add(m.group(groupIndex) != null ? m.group(groupIndex) : "");
                        } else {
                            matches.add(m.group());
                        }
                        if (maxGroup > 0) {
                            java.util.List<String> per = new java.util.ArrayList<>();
                            for (int g = 1; g <= maxGroup; g++) {
                                per.add(m.group(g) != null ? m.group(g) : "");
                            }
                            groups.add(per);
                        }
                    }
                    String summary = matches.isEmpty()
                            ? "无匹配" : "匹配 " + matches.size() + " 处: " + matches.toString();
                    final java.util.List<String> mOut = matches;
                    final java.util.List<java.util.List<String>> gOut = groups;
                    Map<String, Object> info = new HashMap<String, Object>() {{
                        put("matches", mOut);
                    }};
                    if (!gOut.isEmpty()) {
                        info.put("groups", gOut);   // 每个匹配的捕获组数组，顺序同 matches
                        info.put("group_count", maxGroup);
                    }
                    return AIToolResult.success(summary, info);
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
