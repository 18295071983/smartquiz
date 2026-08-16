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

    /** 组件块：```component:type\n{json}``` 或 ```component:type{json}```（容忍标记与 JSON 同行/无空格） */
    private static final Pattern COMPONENT_BLOCK = Pattern.compile(
            "```component:(\\w+)\\s*([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);

    private ComponentContentSplitter() {
    }

    /** 内容是否包含组件标记 */
    public static boolean containsComponent(String content) {
        return content != null && COMPONENT_BLOCK.matcher(content).find();
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

        Matcher matcher = COMPONENT_BLOCK.matcher(content);
        int lastEnd = 0;
        while (matcher.find()) {
            // 标记前的文本段
            if (matcher.start() > lastEnd) {
                String text = content.substring(lastEnd, matcher.start());
                if (!text.trim().isEmpty()) {
                    segments.add(Segment.text(text));
                }
            }
            // 组件段：标记内 JSON 直接作为 props（type 来自标记名）
            String type = matcher.group(1);
            String json = matcher.group(2).trim();
            org.json.JSONObject props = ComponentData.parseJsonObject(json);
            if (props == null) props = new org.json.JSONObject();
            segments.add(Segment.component(new ComponentData(type, props)));
            lastEnd = matcher.end();
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
