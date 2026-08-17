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

    /** 组件块：```component:type\n{json}```、```component:type {json}```、```component:type{json}```
     *  兼容多行 JSON、同行 JSON、JSON 前带说明文字。 */
    private static final Pattern COMPONENT_BLOCK = Pattern.compile(
            "```component:(\\w+)\\s*\\{([\\s\\S]*?)\\}```",
            Pattern.CASE_INSENSITIVE);

    /** 无代码块变体：component:type {json} 或 component:type\n{json}（模型漏写三反引号时兜底） */
    private static final Pattern COMPONENT_BLOCK_BARE = Pattern.compile(
            "(?m)^\\s*component:(\\w+)\\s*\\{([\\s\\S]*?)\\}\\s*$",
            Pattern.CASE_INSENSITIVE);

    private ComponentContentSplitter() {
    }

    /** 内容是否包含组件标记（代码块或裸标记） */
    public static boolean containsComponent(String content) {
        return content != null
                && (COMPONENT_BLOCK.matcher(content).find() || COMPONENT_BLOCK_BARE.matcher(content).find());
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

        // 优先用代码块正则，其次用裸标记正则（两者覆盖所有已识别格式）
        Matcher matcher = COMPONENT_BLOCK.matcher(content);
        boolean matched = false;
        int lastEnd = 0;
        while (matcher.find()) {
            matched = true;
            if (matcher.start() > lastEnd) {
                String text = content.substring(lastEnd, matcher.start());
                if (!text.trim().isEmpty()) {
                    segments.add(Segment.text(text));
                }
            }
            addComponentSegment(segments, matcher.group(1), matcher.group(2));
            lastEnd = matcher.end();
        }
        if (!matched) {
            // 无代码块：尝试裸标记（component:xxx {json} 独立成段）
            matcher = COMPONENT_BLOCK_BARE.matcher(content);
            while (matcher.find()) {
                matched = true;
                if (matcher.start() > lastEnd) {
                    String text = content.substring(lastEnd, matcher.start());
                    if (!text.trim().isEmpty()) {
                        segments.add(Segment.text(text));
                    }
                }
                addComponentSegment(segments, matcher.group(1), matcher.group(2));
                lastEnd = matcher.end();
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

    private static void addComponentSegment(List<Segment> segments, String type, String jsonBody) {
        String json = "{" + jsonBody + "}";
        org.json.JSONObject props = ComponentData.parseJsonObject(json);
        if (props == null) props = new org.json.JSONObject();
        segments.add(Segment.component(new ComponentData(type, props)));
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
