package com.oilquiz.app.ai.chat.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容流组件标记解析器。
 *
 * 模型/工具可在回复内容流中直接输出组件标记，组件将**插入到标记所在位置**（插入式渲染）：
 * <pre>
 * 以下是销量数据：
 *
 * ```component:chart
 * {"chartType":"bar","title":"销量","categories":["1月","2月"],"series":[{"name":"销量","data":[100,200]}]}
 * ```
 *
 * 更多说明...
 * </pre>
 *
 * 渲染层把内容切分为「文本段 / 组件段」交替序列：
 * - 文本段 → Markdown 渲染为 TextView
 * - 组件段 → ComponentRegistry 渲染为 View
 */
public class ComponentContentSplitter {

    /**
     * 组件块：```component:type\n{json}```、```component:type {json}```、```component:type{json}```。
     * 以闭合的 ``` 作为标记结束符（不能用第一个 } 结束——组件 JSON 内部嵌套对象含多个 }）。
     * 类型名支持字母/数字/下划线/连字符（Agent 可能输出 custom-panel 这类带连字符类型）。
     */
    private static final Pattern COMPONENT_BLOCK = Pattern.compile(
            "```component:([\\w-]+)\\s*([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);

    /** 无代码块变体：component:type {json}（模型漏写三反引号时兜底，JSON 需独立成段） */
    private static final Pattern COMPONENT_BLOCK_BARE = Pattern.compile(
            "(?m)^\\s*component:([\\w-]+)\\s*(\\{[\\s\\S]*?\\})\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 宽松块兜底：```component:xxx 开头的块（JSON 缺失/未闭合/格式错误也识别为组件段），
     *  匹配到下一个 ``` 或文本结束。防止 Agent 输出不规范标记时把组件源码当纯文本显示。 */
    private static final Pattern COMPONENT_BLOCK_LOOSE = Pattern.compile(
            "```component:([\\w-]+)\\s*([\\s\\S]*?)(?=```|$)",
            Pattern.CASE_INSENSITIVE);

    private ComponentContentSplitter() {
    }

    /** 内容是否包含组件标记（代码块、裸标记或未闭合的宽松标记） */
    public static boolean containsComponent(String content) {
        return content != null
                && (COMPONENT_BLOCK.matcher(content).find()
                    || COMPONENT_BLOCK_BARE.matcher(content).find()
                    || COMPONENT_BLOCK_LOOSE.matcher(content).find());
    }

    /**
     * 把内容切分为段序列。
     *
     * @param content 原始内容（可能含组件标记）
     * @return 文本段/组件段交替序列；content 为空返回空列表
     */
    public static List<Segment> split(String content) {
        List<Segment> segments = new ArrayList<>();
        if (content == null || content.isEmpty()) return segments;

        // 优先用代码块正则（以 ``` 闭合），其次用裸标记正则，最后用宽松兜底正则
        Matcher matcher = COMPONENT_BLOCK.matcher(content);
        boolean matched = false;
        int lastEnd = 0;
        while (matcher.find()) {
            matched = true;
            appendTextBefore(segments, content, lastEnd, matcher.start());
            addComponentSegment(segments, matcher.group(1), matcher.group(2));
            lastEnd = matcher.end();
        }
        if (!matched) {
            matcher = COMPONENT_BLOCK_BARE.matcher(content);
            while (matcher.find()) {
                matched = true;
                appendTextBefore(segments, content, lastEnd, matcher.start());
                // 裸标记 group(2) 已含完整 {json}，直接作为 props
                org.json.JSONObject props = ComponentData.parseJsonObject(matcher.group(2));
                if (props == null) props = new org.json.JSONObject();
                segments.add(Segment.component(new ComponentData(matcher.group(1), props)));
                lastEnd = matcher.end();
            }
        }
        if (!matched) {
            // 宽松兜底：```component:xxx 块未闭合/JSON缺失也识别为组件段（空 props），
            // 由通用组件兜底展示，避免把组件源码当纯文本显示
            Matcher loose = COMPONENT_BLOCK_LOOSE.matcher(content);
            while (loose.find()) {
                matched = true;
                appendTextBefore(segments, content, lastEnd, loose.start());
                String type = loose.group(1);
                String rawBody = loose.group(2);
                org.json.JSONObject props = ComponentData.parseJsonObject(
                        extractJsonBody(rawBody));
                if (props == null) {
                    props = htmlBodyFallback(rawBody);
                }
                if (props == null) props = new org.json.JSONObject();
                segments.add(Segment.component(new ComponentData(type, props)));
                lastEnd = loose.end();
            }
        }
        // 尾部文本段
        if (lastEnd < content.length()) {
            String text = content.substring(lastEnd);
            if (!text.trim().isEmpty()) {
                segments.add(Segment.text(text));
            }
        }
        // 无标记：整个内容作为文本段
        if (segments.isEmpty()) {
            segments.add(Segment.text(content));
        }
        return segments;
    }

    private static void appendTextBefore(List<Segment> segments, String content, int lastEnd, int start) {
        if (start > lastEnd) {
            String text = content.substring(lastEnd, start);
            if (!text.trim().isEmpty()) {
                segments.add(Segment.text(text));
            }
        }
    }

    /**
     * 从标记内容（group 捕获的 {json} 原始文本）解析组件 props。
     * 用大括号配对找到完整 JSON 主体，兼容多行、内部嵌套。
     * JSON 解析失败但内容本身像 HTML 时（Agent 直接把 HTML 写在组件 body，
     * 未包成 {"html": ...} JSON 对象），自动包装为 {"html": body} 供 html 组件渲染。
     */
    private static void addComponentSegment(List<Segment> segments, String type, String rawBody) {
        String body = rawBody != null ? rawBody.trim() : "";
        // 提取完整 {json} 主体（大括号配对，容忍开头说明文字）
        String json = extractJsonBody(body);
        org.json.JSONObject props = ComponentData.parseJsonObject(json);
        if (props == null) {
            props = htmlBodyFallback(body);
        }
        if (props == null) props = new org.json.JSONObject();
        segments.add(Segment.component(new ComponentData(type, props)));
    }

    /**
     * HTML body 兜底：内容不是 JSON 对象但看起来是 HTML（含标签/文档声明），
     * 包装为 {"html": body}。返回 null 表示无法识别为 HTML。
     */
    private static org.json.JSONObject htmlBodyFallback(String body) {
        if (body == null || body.isEmpty()) return null;
        String t = body.trim();
        boolean looksHtml = t.startsWith("<!DOCTYPE") || t.startsWith("<html")
                || t.startsWith("<div") || t.startsWith("<h1") || t.startsWith("<h2")
                || t.startsWith("<h3") || t.startsWith("<p") || t.startsWith("<ul")
                || t.startsWith("<ol") || t.startsWith("<table") || t.startsWith("<span")
                || t.startsWith("<section") || t.startsWith("<style") || t.startsWith("<script")
                || t.contains("</") || t.matches("(?s)<\\s*[a-zA-Z]+[^>]*>.*");
        if (!looksHtml) return null;
        org.json.JSONObject props = new org.json.JSONObject();
        try {
            props.put("html", t);
            return props;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从任意文本中提取第一个平衡的 {…} JSON 主体。
     * 以第一个 { 开始，按大括号深度找到配对的最后一个 }。
     *
     * @param text 可能包含说明文字 + JSON 的文本
     * @return 完整 JSON 主体；找不到返回原文本
     */
    private static String extractJsonBody(String text) {
        if (text == null || text.isEmpty()) return text;
        int start = text.indexOf('{');
        if (start < 0) return text;
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
            } else {
                if (c == '"' || c == '\'') {
                    inString = true;
                    quote = c;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }
        return text.substring(start);
    }

    /**
     * 内容段：文本段或组件段。
     */
    public static class Segment {
        public final boolean isComponent;
        public final String text;
        public final ComponentData component;

        private Segment(boolean isComponent, String text, ComponentData component) {
            this.isComponent = isComponent;
            this.text = text;
            this.component = component;
        }

        public static Segment text(String text) {
            return new Segment(false, text, null);
        }

        public static Segment component(ComponentData component) {
            return new Segment(true, null, component);
        }
    }
}
