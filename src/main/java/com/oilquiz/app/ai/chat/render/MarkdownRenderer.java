package com.oilquiz.app.ai.chat.render;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.Html;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 增强型 Markdown 渲染器
 * 
 * 支持的渲染类型：
 * 1. 标准 Markdown - 标题、粗体、斜体、代码等
 * 2. 代码高亮 - 支持多语言语法高亮
 * 3. 数学公式 - LaTeX 语法渲染
 * 4. 思维导图 - Mermaid 格式
 * 5. 表格 - Markdown 表格
 * 6. 任务列表 - [ ] / [x]
 */
public class MarkdownRenderer {

    // ==================== 颜色定义 ====================
    private static final int COLOR_CODE_BG = 0xFFF5F5F5;
    private static final int COLOR_HEADER = 0xFF1A1A1A;
    private static final int COLOR_LINK = 0xFF1976D2;
    private static final int COLOR_QUOTE_BG = 0xFFF0F0F0;
    private static final int COLOR_BLOCKQUOTE_BORDER = 0xFFCCCCCC;
    private static final int COLOR_HR = 0xFFE0E0E0;

    // 代码高亮颜色
    private static final int COLOR_CODE_KEYWORD = 0xFF0000FF;      // 蓝色
    private static final int COLOR_CODE_STRING = 0xFF008000;      // 绿色
    private static final int COLOR_CODE_COMMENT = 0xFF808080;     // 灰色
    private static final int COLOR_CODE_NUMBER = 0xFFFF8C00;      // 橙色
    private static final int COLOR_CODE_FUNCTION = 0xFF6A5ACD;    // 紫色
    private static final int COLOR_CODE_DEFAULT = 0xFF333333;     // 深灰

    // 数学公式颜色
    private static final int COLOR_MATH = 0xFF2C3E50;

    // ==================== 匹配模式 ====================
    private static final Pattern BLOCK_CODE_PATTERN = Pattern.compile(
        "```(\\w*)\\n?([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile(
        "`([^`]+)`");
    private static final Pattern HEADER_PATTERN = Pattern.compile(
        "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern BOLD_PATTERN = Pattern.compile(
        "\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile(
        "(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern BOLD_ITALIC_PATTERN = Pattern.compile(
        "\\*\\*\\*(.+?)\\*\\*\\*");
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile(
        "~~(.+?)~~");
    private static final Pattern LINK_PATTERN = Pattern.compile(
        "\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern IMAGE_PATTERN = Pattern.compile(
        "!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile(
        "^[\\-\\*\\+]\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile(
        "^(\\d+)\\.\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern QUOTE_PATTERN = Pattern.compile(
        "^>\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern HORIZONTAL_RULE_PATTERN = Pattern.compile(
        "^(-{3,}|\\*{3,}|_{3,})$", Pattern.MULTILINE);
    private static final Pattern TASK_ITEM_PATTERN = Pattern.compile(
        "^\\[([ xX])\\]\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern TABLE_PATTERN = Pattern.compile(
        "^\\|(.+)\\|$", Pattern.MULTILINE);
    private static final Pattern TABLE_ROW_SEPARATOR = Pattern.compile(
        "^\\|[-:\\s]+\\|$", Pattern.MULTILINE);

    // 数学公式
    private static final Pattern MATH_BLOCK_PATTERN = Pattern.compile(
        "```math\\n?([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern MATH_INLINE_PATTERN = Pattern.compile(
        "\\$([^$\\n]+)\\$");
    private static final Pattern MATH_DISPLAY_PATTERN = Pattern.compile(
        "\\$\\$([^$]+)\\$\\$");

    // 思维导图 / Mermaid
    private static final Pattern MERMAID_PATTERN = Pattern.compile(
        "```mermaid\\n?([\\s\\S]*?)```", Pattern.MULTILINE);

    // ==================== 渲染入口 ====================
    
    public static Spanned render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new SpannableStringBuilder("");
        }

        String processed = markdown;
        
        // 1. 处理特殊块（代码块、数学公式、Mermaid）- 优先处理
        processed = processMathBlocks(processed);
        processed = processMermaidBlocks(processed);
        processed = processCodeBlocks(processed);
        
        // 2. 处理行内元素
        processed = processHeaders(processed);
        processed = processLinks(processed);
        processed = processImages(processed);
        processed = processBoldItalic(processed);
        processed = processBold(processed);
        processed = processItalic(processed);
        processed = processStrikethrough(processed);
        processed = processInlineCode(processed);
        processed = processMathInline(processed);
        
        // 3. 处理结构元素
        processed = processHorizontalRules(processed);
        processed = processQuotes(processed);
        processed = processTaskLists(processed);
        processed = processLists(processed);
        processed = processTables(processed);

        return Html.fromHtml(processed, Html.FROM_HTML_MODE_LEGACY);
    }

    // ==================== 代码块处理 ====================
    
    private static String processCodeBlocks(String text) {
        Matcher matcher = BLOCK_CODE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String language = matcher.group(1);
            String code = matcher.group(2).trim();
            String highlighted = highlightCode(code, language);
            matcher.appendReplacement(sb, "<pre style='background:#F5F5F5;border-radius:8px;padding:12px;overflow-x:auto;'>" 
                + highlighted + "</pre>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 代码语法高亮
     */
    private static String highlightCode(String code, String language) {
        String escaped = escapeHtml(code);
        
        // 简单关键词高亮
        if (language.equals("java") || language.equals("kotlin") || language.isEmpty()) {
            escaped = highlightJava(escaped);
        } else if (language.equals("python")) {
            escaped = highlightPython(escaped);
        } else if (language.equals("javascript") || language.equals("js")) {
            escaped = highlightJavaScript(escaped);
        } else if (language.equals("html") || language.equals("xml")) {
            escaped = highlightHTML(escaped);
        } else if (language.equals("sql")) {
            escaped = highlightSQL(escaped);
        } else if (language.equals("shell") || language.equals("bash")) {
            escaped = highlightShell(escaped);
        }
        
        return escaped;
    }

    private static String highlightJava(String code) {
        // 关键词
        String[] keywords = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", 
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "String", "List", "Map", "Set"};
        
        for (String kw : keywords) {
            code = code.replaceAll("\\b(" + kw + ")\\b", 
                "<font color='#0000FF'><b>$1</b></font>");
        }
        
        // 字符串
        code = code.replaceAll("\"([^\"\\\\]|\\\\.)*\"", 
            "<font color='#008000'>\"$0\"</font>");
        code = code.replaceAll("'([^'\\\\]|\\\\.)'", 
            "<font color='#008000'>'$0'</font>");
        
        // 数字
        code = code.replaceAll("\\b(\\d+\\.?\\d*[fFdDlL]?)\\b", 
            "<font color='#FF8C00'>$1</font>");
        
        // 注释
        code = code.replaceAll("//.*", 
            "<font color='#808080'><i>$0</i></font>");
        code = code.replaceAll("/\\*[\\s\\S]*?\\*/", 
            "<font color='#808080'><i>$0</i></font>");
        
        return code;
    }

    private static String highlightPython(String code) {
        String[] keywords = {"and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or",
            "pass", "raise", "return", "try", "while", "with", "yield", "True", "False",
            "self", "print", "len", "range", "str", "int", "float", "list", "dict", "tuple"};
        
        for (String kw : keywords) {
            code = code.replaceAll("\\b(" + kw + ")\\b", 
                "<font color='#0000FF'><b>$1</b></font>");
        }
        
        // 字符串
        code = code.replaceAll("\"\"\"[\\s\\S]*?\"\"\"", 
            "<font color='#008000'>$0</font>");
        code = code.replaceAll("'''[\\s\\S]*?'''", 
            "<font color='#008000'>$0</font>");
        code = code.replaceAll("\"([^\"\\\\]|\\\\.)*\"", 
            "<font color='#008000'>\"$0\"</font>");
        code = code.replaceAll("'([^'\\\\]|\\\\.)*'", 
            "<font color='#008000'>'$0'</font>");
        
        // 数字
        code = code.replaceAll("\\b(\\d+\\.?\\d*)\\b", 
            "<font color='#FF8C00'>$1</font>");
        
        // 注释
        code = code.replaceAll("#.*", 
            "<font color='#808080'><i>$0</i></font>");
        
        return code;
    }

    private static String highlightJavaScript(String code) {
        String[] keywords = {"break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "finally", "for",
            "function", "if", "import", "in", "instanceof", "let", "new", "null", "return",
            "static", "super", "switch", "this", "throw", "true", "try", "typeof",
            "undefined", "var", "void", "while", "with", "yield", "async", "await", "of"};
        
        for (String kw : keywords) {
            code = code.replaceAll("\\b(" + kw + ")\\b", 
                "<font color='#0000FF'><b>$1</b></font>");
        }
        
        // 字符串
        code = code.replaceAll("`[^`]*`", 
            "<font color='#008000'>$0</font>");
        code = code.replaceAll("\"([^\"\\\\]|\\\\.)*\"", 
            "<font color='#008000'>\"$0\"</font>");
        
        // 数字
        code = code.replaceAll("\\b(\\d+\\.?\\d*)\\b", 
            "<font color='#FF8C00'>$1</font>");
        
        // 注释
        code = code.replaceAll("//.*", 
            "<font color='#808080'><i>$0</i></font>");
        
        return code;
    }

    private static String highlightHTML(String code) {
        // HTML 标签
        code = code.replaceAll("(&lt;/?)(\\w+)", "$1<font color='#0000FF'>$2</font>");
        code = code.replaceAll("(&lt;)(\\w+)(/?&gt;)", 
            "<font color='#0000FF'>$1$2</font>$3");
        
        // 属性
        code = code.replaceAll("(\\w+)(=)", 
            "<b>$1</b>$2");
        
        // 字符串值
        code = code.replaceAll("\"[^\"]*\"", 
            "<font color='#008000'>$0</font>");
        
        return code;
    }

    private static String highlightSQL(String code) {
        String[] keywords = {"SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE",
            "TABLE", "DROP", "ALTER", "INDEX", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
            "ON", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "ORDER",
            "BY", "GROUP", "HAVING", "LIMIT", "OFFSET", "AS", "DISTINCT", "COUNT", "SUM",
            "AVG", "MAX", "MIN", "UNION", "ALL", "EXISTS", "CASE", "WHEN", "THEN", "ELSE",
            "END", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "DEFAULT",
            "AUTO_INCREMENT", "VARCHAR", "INT", "INTEGER", "TEXT", "BLOB", "TIMESTAMP"};
        
        for (String kw : keywords) {
            code = code.replaceAll("\\b(" + kw + ")\\b", 
                "<font color='#0000FF'><b>$1</b></font>");
        }
        
        // 字符串
        code = code.replaceAll("'([^']*)'", 
            "<font color='#008000'>'$1'</font>");
        
        // 数字
        code = code.replaceAll("\\b(\\d+)\\b", 
            "<font color='#FF8C00'>$1</font>");
        
        // 注释
        code = code.replaceAll("--.*", 
            "<font color='#808080'><i>$0</i></font>");
        
        return code;
    }

    private static String highlightShell(String code) {
        String[] keywords = {"if", "then", "else", "elif", "fi", "case", "esac", "for", "while",
            "until", "do", "done", "in", "function", "return", "local", "export", "echo",
            "read", "exit", "test", "true", "false", "cd", "pwd", "ls", "cp", "mv", "rm",
            "mkdir", "cat", "grep", "sed", "awk", "chmod", "chown", "sudo", "apt", "yum",
            "npm", "pip", "git", "docker", "make", "cmake", "gcc", "g++", "javac", "java"};
        
        for (String kw : keywords) {
            code = code.replaceAll("\\b(" + kw + ")\\b", 
                "<font color='#0000FF'><b>$1</b></font>");
        }
        
        // 变量
        code = code.replaceAll("\\$[{]?(\\w+)[}]?", 
            "<font color='#6A5ACD'>$0</font>");
        
        // 字符串
        code = code.replaceAll("\"([^\"\\\\]|\\\\.)*\"", 
            "<font color='#008000'>\"$0\"</font>");
        
        // 注释
        code = code.replaceAll("#.*", 
            "<font color='#808080'><i>$0</i></font>");
        
        return code;
    }

    // ==================== 数学公式处理 ====================
    
    private static String processMathBlocks(String text) {
        Matcher matcher = MATH_BLOCK_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String formula = matcher.group(1).trim();
            String rendered = renderMathFormula(formula);
            matcher.appendReplacement(sb, "<div style='background:#FAFAFA;padding:12px;border-radius:8px;margin:8px 0;text-align:center;font-family:serif;font-size:18sp;color:#2C3E50;'>" 
                + rendered + "</div>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processMathInline(String text) {
        // 行内公式 $...$
        text = MATH_INLINE_PATTERN.matcher(text).replaceAll(
            "<span style='font-family:serif;color:#2C3E50;'>$0</span>");
        // 展示公式 $$...$$
        text = MATH_DISPLAY_PATTERN.matcher(text).replaceAll(
            "<div style='font-family:serif;text-align:center;color:#2C3E50;'>$0</div>");
        return text;
    }

    /**
     * 渲染 LaTeX 数学公式（简化版）
     * 完整渲染需要 KaTeX/MathJax WebView
     */
    private static String renderMathFormula(String formula) {
        // 简化处理，完整版应使用 WebView + KaTeX
        String rendered = escapeHtml(formula);
        
        // 上标
        rendered = rendered.replaceAll("\\^\\{([^}]+)\\}", "<sup>$1</sup>");
        rendered = rendered.replaceAll("\\^([a-zA-Z0-9])", "<sup>$1</sup>");
        
        // 下标
        rendered = rendered.replaceAll("_[{]([^}]+)[}]", "<sub>$1</sub>");
        rendered = rendered.replaceAll("_([a-zA-Z0-9])", "<sub>$1</sub>");
        
        // 分数
        rendered = rendered.replaceAll("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}", "($1)/($2)");
        
        // 根号
        rendered = rendered.replaceAll("\\\\sqrt\\{([^}]+)\\}", "√($1)");
        
        // 求和
        rendered = rendered.replaceAll("\\\\sum", "Σ");
        rendered = rendered.replaceAll("\\\\int", "∫");
        rendered = rendered.replaceAll("\\\\infty", "∞");
        rendered = rendered.replaceAll("\\\\pi", "π");
        
        // 希腊字母
        rendered = rendered.replaceAll("\\\\alpha", "α");
        rendered = rendered.replaceAll("\\\\beta", "β");
        rendered = rendered.replaceAll("\\\\gamma", "γ");
        rendered = rendered.replaceAll("\\\\delta", "δ");
        rendered = rendered.replaceAll("\\\\theta", "θ");
        rendered = rendered.replaceAll("\\\\lambda", "λ");
        rendered = rendered.replaceAll("\\\\mu", "μ");
        rendered = rendered.replaceAll("\\\\sigma", "σ");
        rendered = rendered.replaceAll("\\\\omega", "ω");
        
        return rendered;
    }

    // ==================== Mermaid 思维导图 ====================
    
    private static String processMermaidBlocks(String text) {
        Matcher matcher = MERMAID_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String mermaidCode = matcher.group(1).trim();
            String rendered = renderMermaid(mermaidCode);
            matcher.appendReplacement(sb, "<div style='background:#FAFAFA;padding:16px;border-radius:8px;margin:8px 0;border-left:4px solid #6A5ACD;'>"
                + "<div style='color:#666;font-size:12sp;margin-bottom:8px;'>📊 思维导图</div>"
                + "<pre style='color:#333;white-space:pre-wrap;'>" + escapeHtml(mermaidCode) + "</pre>"
                + rendered
                + "</div>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 渲染 Mermaid（简化版文本表示）
     * 完整渲染需要 Mermaid.js WebView
     */
    private static String renderMermaid(String mermaid) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-top:8px;padding:8px;background:#F8F8F8;border-radius:4px;'>");
        
        // 简化解析思维导图结构
        String[] lines = mermaid.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("mindmap")) continue;
            if (line.contains("Root") || line.contains("root")) {
                sb.append("<div style='font-weight:bold;margin:4px 0;'>• ");
            } else if (line.contains("--")) {
                String[] parts = line.split("--");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sb.append(" → ");
                    sb.append(parts[i].trim());
                }
            } else if (!line.isEmpty()) {
                sb.append("<div style='margin-left:16px;'>• ").append(line).append("</div>");
            }
        }
        
        sb.append("</div>");
        return sb.toString();
    }

    // ==================== 表格处理 ====================
    
    private static String processTables(String text) {
        String[] lines = text.split("\n");
        StringBuilder result = new StringBuilder();
        boolean inTable = false;
        int colCount = 0;
        
        for (String line : lines) {
            Matcher rowMatcher = TABLE_PATTERN.matcher(line);
            Matcher separatorMatcher = TABLE_ROW_SEPARATOR.matcher(line);
            
            if (separatorMatcher.matches()) {
                // 分隔行，记录列数
                String[] cells = line.split("\\|");
                colCount = cells.length - 2; // 去掉首尾空
                result.append("<tr>");
                for (int i = 1; i < cells.length - 1; i++) {
                    String cell = cells[i].trim();
                    boolean isCenter = cell.startsWith(":") && cell.endsWith(":");
                    boolean isRight = cell.endsWith(":");
                    String align = isCenter ? "center" : (isRight ? "right" : "left");
                    result.append("<th align='").append(align).append("' style='background:#F0F0F0;padding:8px;border:1px solid #DDD;'>")
                          .append(cell.replaceAll(":", "").trim())
                          .append("</th>");
                }
                result.append("</tr>");
                inTable = true;
            } else if (rowMatcher.matches()) {
                if (!inTable) {
                    result.append("<table style='width:100%;border-collapse:collapse;margin:8px 0;'>");
                    inTable = true;
                }
                result.append("<tr>");
                String[] cells = line.split("\\|");
                for (int i = 1; i < cells.length - 1; i++) {
                    result.append("<td style='padding:8px;border:1px solid #DDD;'>")
                          .append(cells[i].trim())
                          .append("</td>");
                }
                result.append("</tr>");
            } else {
                if (inTable) {
                    result.append("</table>");
                    inTable = false;
                }
                result.append(line).append("\n");
            }
        }
        
        if (inTable) {
            result.append("</table>");
        }
        
        return result.toString();
    }

    // ==================== 其他元素处理 ====================
    
    private static String processHeaders(String text) {
        Matcher matcher = HEADER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int level = matcher.group(1).length();
            String content = matcher.group(2);
            int fontSize = 24 - (level - 1) * 3;
            matcher.appendReplacement(sb, "<h" + level + " style='color:#1A1A1A;font-size:" + fontSize 
                + "px;font-weight:bold;margin-top:16px;margin-bottom:8px;'>" + content + "</h" + level + ">");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processLinks(String text) {
        Matcher matcher = LINK_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(1);
            String url = matcher.group(2);
            matcher.appendReplacement(sb, "<a href='" + url + "' style='color:#1976D2;text-decoration:underline;'>" + label + "</a>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processImages(String text) {
        Matcher matcher = IMAGE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String url = matcher.group(2);
            String display = alt.isEmpty() ? "[图片]" : "[图片: " + alt + "]";
            matcher.appendReplacement(sb, "<i><font color='#666666'>" + display + "</font></i>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processBoldItalic(String text) {
        Matcher matcher = BOLD_ITALIC_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "<b><i>" + matcher.group(1) + "</i></b>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processBold(String text) {
        Matcher matcher = BOLD_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "<b>" + matcher.group(1) + "</b>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processItalic(String text) {
        Matcher matcher = ITALIC_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "<i>" + matcher.group(1) + "</i>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processStrikethrough(String text) {
        Matcher matcher = STRIKETHROUGH_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "<strike>" + matcher.group(1) + "</strike>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processInlineCode(String text) {
        Matcher matcher = INLINE_CODE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "<code style='background:#F5F5F5;color:#D63384;padding:2px 4px;border-radius:3px;'>" + matcher.group(1) + "</code>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processHorizontalRules(String text) {
        Matcher matcher = HORIZONTAL_RULE_PATTERN.matcher(text);
        return matcher.replaceAll("<hr style='border:none;border-top:1px solid #E0E0E0;margin:16px 0;'>");
    }

    private static String processQuotes(String text) {
        Matcher matcher = QUOTE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "<blockquote style='border-left:3px solid #CCCCCC;margin:8px 0;padding-left:12px;color:#666666;'>" + matcher.group(1) + "</blockquote>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processTaskLists(String text) {
        Matcher matcher = TASK_ITEM_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String checked = matcher.group(1);
            String task = matcher.group(2);
            boolean isChecked = checked.equalsIgnoreCase("x");
            String checkbox = isChecked ? "☑" : "☐";
            String style = isChecked ? "text-decoration:line-through;color:#999;" : "";
            matcher.appendReplacement(sb, "<div style='margin:4px 0;'><span style='" + style + "'>" + checkbox + " " + task + "</span></div>");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String processLists(String text) {
        // 无序列表
        Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (ulMatcher.find()) {
            ulMatcher.appendReplacement(sb, "<br>• " + ulMatcher.group(1));
        }
        ulMatcher.appendTail(sb);
        text = sb.toString();

        // 有序列表
        Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(text);
        sb = new StringBuffer();
        while (olMatcher.find()) {
            olMatcher.appendReplacement(sb, "<br>" + olMatcher.group(1) + ". " + olMatcher.group(2));
        }
        olMatcher.appendTail(sb);
        return sb.toString();
    }

    // ==================== 工具方法 ====================
    
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * 获取纯文本（用于复制）
     */
    public static String toPlainText(String markdown) {
        if (markdown == null) return "";
        
        String text = markdown;
        // 移除代码块
        text = text.replaceAll("```[\\s\\S]*?```", "");
        text = text.replaceAll("`([^`]+)`", "$1");
        // 移除格式
        text = text.replaceAll("^#{1,6}\\s+", "");
        text = text.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "$1");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("\\*(.+?)\\*", "$1");
        text = text.replaceAll("~~(.+?)~~", "$1");
        // 处理链接
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1");
        // 处理引用
        text = text.replaceAll("(?m)^>\\s+", "  ");
        
        return text.trim();
    }
}
