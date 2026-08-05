package com.oilquiz.app.ai.chat.render;

/**
 * 内容片段类型枚举。
 *
 * 由 {@link ContentTypeDetector} 对原始内容进行切分后，
 * 每个片段携带一个类型标签，用于匹配对应的 {@link ContentRenderer}。
 */
public enum ContentType {
    /** 标准 Markdown 文本（含代码块、表格、图片等） */
    MARKDOWN,
    /** 行内数学公式 $...$ */
    MATH_INLINE,
    /** 块级数学公式 $$...$$ */
    MATH_BLOCK,
    /** Mermaid 图表 ```mermaid ... ``` */
    MERMAID,
    /** 独立 HTML 块 */
    HTML_BLOCK,
    /** 纯文本（无法识别格式的降级处理） */
    PLAIN_TEXT,
}
