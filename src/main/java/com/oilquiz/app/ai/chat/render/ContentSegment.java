package com.oilquiz.app.ai.chat.render;

/**
 * 内容片段：原始内容经 {@link ContentTypeDetector} 切分后的最小渲染单元。
 *
 * 每个片段包含一段文本和对应的 {@link ContentType}，
 * 由 {@link RenderExecutor} 分发给匹配的 {@link ContentRenderer} 处理。
 */
public class ContentSegment {

    /** 片段类型 */
    public final ContentType type;
    /** 片段原始文本 */
    public final String text;
    /** 片段在原始内容中的起始偏移（用于调试） */
    public final int startOffset;

    public ContentSegment(ContentType type, String text, int startOffset) {
        this.type = type;
        this.text = text;
        this.startOffset = startOffset;
    }

    @Override
    public String toString() {
        return "ContentSegment{" + type + " @" + startOffset + " len=" + (text != null ? text.length() : 0) + "}";
    }
}
