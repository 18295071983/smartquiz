package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.oilquiz.app.ai.chat.component.ComponentColors;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简语法着色（2026-11-18，工具详情卡用）。
 *
 * <p>没有引高亮库：工具详情只需要"关键字/字符串/注释/数字"四类着色，量级小、语言少
 * （shell / python / java / kotlin / json / 通用）。真要做完整解析器再换库，这里先解决
 * "一整块同色代码没法快速扫读"的问题。</p>
 *
 * <p>不改变文本内容，只叠加 {@link ForegroundColorSpan}；颜色走主题 token。</p>
 */
public final class CodeHighlighter {

    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`");
    private static final Pattern COMMENT = Pattern.compile("(?m)(//[^\\n]*|#[^\\n]*|/\\*.*?\\*/)");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final java.util.Set<String> KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "public", "private", "protected", "class", "interface", "enum", "void", "int", "long",
            "double", "float", "boolean", "char", "if", "else", "for", "while", "return", "new",
            "static", "final", "import", "package", "try", "catch", "finally", "throw", "throws",
            "def", "lambda", "None", "True", "False", "elif", "except", "with", "as", "pass",
            "val", "var", "fun", "when", "object", "data", "null", "true", "false",
            "function", "const", "let", "export", "await", "async", "typeof"));

    private CodeHighlighter() {
    }

    /** 按语言给代码加四类 Span；语言不认识就返回原串。 */
    public static CharSequence highlight(Context ctx, String code, String lang) {
        if (ctx == null || code == null || code.isEmpty()) {
            return code;
        }
        String l = lang == null ? "" : lang.toLowerCase(java.util.Locale.US);
        boolean hashComment = l.contains("python") || l.contains("sh") || l.contains("shell")
                || l.contains("bash") || l.contains("py") || l.contains("yaml");
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        int stringColor = ComponentColors.success(ctx);
        int commentColor = ComponentColors.textTertiary(ctx);
        int numberColor = ComponentColors.warning(ctx);
        int keywordColor = ComponentColors.accent(ctx);

        Matcher m = STRING.matcher(code);
        while (m.find()) {
            sb.setSpan(new ForegroundColorSpan(stringColor), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Pattern comment = hashComment
                ? Pattern.compile("(?m)(#[^\\n]*|//[^\\n]*)")
                : COMMENT;
        m = comment.matcher(code);
        while (m.find()) {
            sb.setSpan(new ForegroundColorSpan(commentColor), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        m = NUMBER.matcher(code);
        while (m.find()) {
            sb.setSpan(new ForegroundColorSpan(numberColor), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // HTML/JSX/XML：先给标签名上色（只匹配 <tag / </tag，不含引号内容，避免盖掉字符串色）
        boolean markup = l.contains("html") || l.contains("xml") || l.contains("jsx")
                || l.contains("svg") || l.contains("vue");
        if (markup) {
            m = Pattern.compile("</?[A-Za-z][A-Za-z0-9:-]*").matcher(code);
            while (m.find()) {
                if (!isCovered(sb, m.start())) {
                    sb.setSpan(new ForegroundColorSpan(keywordColor), m.start(), m.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        // 关键字：按词扫，避免在字符串/注释里着色（简单跳过已被前两类覆盖的区间）
        java.util.regex.Matcher word = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(code);
        while (word.find()) {
            if (!KEYWORDS.contains(word.group())) {
                continue;
            }
            if (isCovered(sb, word.start())) {
                continue;
            }
            sb.setSpan(new ForegroundColorSpan(keywordColor), word.start(), word.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static boolean isCovered(SpannableStringBuilder sb, int offset) {
        ForegroundColorSpan[] spans = sb.getSpans(offset, offset + 1, ForegroundColorSpan.class);
        return spans != null && spans.length > 0;
    }
}
