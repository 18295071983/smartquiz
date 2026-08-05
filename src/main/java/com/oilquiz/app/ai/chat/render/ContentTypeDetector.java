package com.oilquiz.app.ai.chat.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容类型检测器：分析原始内容并将其切分为多个 {@link ContentSegment}。
 *
 * 检测规则（按优先级）：
 * 1. Mermaid 代码块（```mermaid ... ```）
 * 2. 块级数学公式（$$ ... $$）
 * 3. 行内数学公式（$ ... $）
 * 4. 独立 HTML 块（<div>/<table>/<iframe> 等独占一行的 HTML）
 * 5. 其余内容统一标记为 MARKDOWN
 *
 * 设计要点：
 * - 使用正则进行块级切分，不破坏 Markdown 嵌套结构
 * - 数学公式检测避开代码块内部（```...``` 中的 $ 不被识别为公式）
 * - 性能：单次遍历，O(n) 时间复杂度
 */
public class ContentTypeDetector {

    /** Mermaid 代码块：```mermaid\n...\n``` */
    private static final Pattern MERMAID_PATTERN = Pattern.compile(
            "```mermaid\\s*\\n([\\s\\S]*?)```",
            Pattern.MULTILINE);

    /** 块级数学公式：$$ ... $$（跨行） */
    private static final Pattern MATH_BLOCK_PATTERN = Pattern.compile(
            "\\$\\$(.+?)\\$\\$",
            Pattern.DOTALL);

    /** 行内数学公式：$ ... $（不跨行，$ 后非空格，$ 前非空格） */
    private static final Pattern MATH_INLINE_PATTERN = Pattern.compile(
            "(?<![\\\\$])\\$([^$\\n]+?)\\$(?!\\$)");

    /** 独立 HTML 块：行首开始的 <div>/<table>/<iframe>/<details> 等 */
    private static final Pattern HTML_BLOCK_PATTERN = Pattern.compile(
            "(?m)^<(div|table|iframe|details|section|article|figure|blockquote)\\b[^>]*>[\\s\\S]*?</\\1>",
            Pattern.CASE_INSENSITIVE);

    /**
     * 将原始内容切分为多个片段。
     *
     * @param content 原始 Markdown/富文本内容
     * @return 片段列表（按原始顺序）
     */
    public static List<ContentSegment> detect(String content) {
        List<ContentSegment> segments = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            segments.add(new ContentSegment(ContentType.PLAIN_TEXT, "", 0));
            return segments;
        }

        // 第一轮：提取 Mermaid 块
        List<ContentSegment> afterMermaid = extractPattern(content, MERMAID_PATTERN, ContentType.MERMAID, ContentType.MARKDOWN);
        // 第二轮：提取块级数学公式
        List<ContentSegment> afterMathBlock = new ArrayList<>();
        for (ContentSegment seg : afterMermaid) {
            if (seg.type == ContentType.MARKDOWN) {
                afterMathBlock.addAll(extractPattern(seg.text, MATH_BLOCK_PATTERN, ContentType.MATH_BLOCK, ContentType.MARKDOWN, seg.startOffset));
            } else {
                afterMathBlock.add(seg);
            }
        }
        // 第三轮：提取行内数学公式
        List<ContentSegment> afterMathInline = new ArrayList<>();
        for (ContentSegment seg : afterMathBlock) {
            if (seg.type == ContentType.MARKDOWN) {
                afterMathInline.addAll(extractPattern(seg.text, MATH_INLINE_PATTERN, ContentType.MATH_INLINE, ContentType.MARKDOWN, seg.startOffset));
            } else {
                afterMathInline.add(seg);
            }
        }
        // 第四轮：提取 HTML 块
        List<ContentSegment> afterHtml = new ArrayList<>();
        for (ContentSegment seg : afterMathInline) {
            if (seg.type == ContentType.MARKDOWN) {
                afterHtml.addAll(extractPattern(seg.text, HTML_BLOCK_PATTERN, ContentType.HTML_BLOCK, ContentType.MARKDOWN, seg.startOffset));
            } else {
                afterHtml.add(seg);
            }
        }

        // 合并连续的 MARKDOWN 片段以减少渲染开销
        return mergeMarkdownSegments(afterHtml);
    }

    /**
     * 用正则从文本中提取匹配部分作为指定类型，不匹配部分作为 fallbackType。
     */
    private static List<ContentSegment> extractPattern(String text, Pattern pattern,
                                                        ContentType matchType, ContentType fallbackType) {
        return extractPattern(text, pattern, matchType, fallbackType, 0);
    }

    private static List<ContentSegment> extractPattern(String text, Pattern pattern,
                                                        ContentType matchType, ContentType fallbackType,
                                                        int baseOffset) {
        List<ContentSegment> segments = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            // 匹配前的普通文本
            if (matcher.start() > lastEnd) {
                String before = text.substring(lastEnd, matcher.start());
                if (!before.trim().isEmpty()) {
                    segments.add(new ContentSegment(fallbackType, before, baseOffset + lastEnd));
                }
            }
            // 匹配的内容（完整匹配或捕获组）
            String matchedText = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
            segments.add(new ContentSegment(matchType, matchedText, baseOffset + matcher.start()));
            lastEnd = matcher.end();
        }
        // 尾部普通文本
        if (lastEnd < text.length()) {
            String after = text.substring(lastEnd);
            if (!after.trim().isEmpty()) {
                segments.add(new ContentSegment(fallbackType, after, baseOffset + lastEnd));
            }
        }
        // 如果没有匹配，返回整个文本作为 fallbackType
        if (segments.isEmpty() && !text.trim().isEmpty()) {
            segments.add(new ContentSegment(fallbackType, text, baseOffset));
        }
        return segments;
    }

    /** 合并连续的 MARKDOWN 片段，减少渲染调用次数 */
    private static List<ContentSegment> mergeMarkdownSegments(List<ContentSegment> segments) {
        List<ContentSegment> merged = new ArrayList<>();
        StringBuilder markdownBuffer = new StringBuilder();
        int bufferStart = -1;

        for (ContentSegment seg : segments) {
            if (seg.type == ContentType.MARKDOWN || seg.type == ContentType.PLAIN_TEXT) {
                if (bufferStart < 0) bufferStart = seg.startOffset;
                markdownBuffer.append(seg.text);
            } else {
                // 先刷出缓冲的 Markdown
                if (markdownBuffer.length() > 0) {
                    merged.add(new ContentSegment(ContentType.MARKDOWN, markdownBuffer.toString(), bufferStart));
                    markdownBuffer.setLength(0);
                    bufferStart = -1;
                }
                merged.add(seg);
            }
        }
        // 刷出最后的缓冲
        if (markdownBuffer.length() > 0) {
            merged.add(new ContentSegment(ContentType.MARKDOWN, markdownBuffer.toString(), bufferStart));
        }
        return merged;
    }
}
