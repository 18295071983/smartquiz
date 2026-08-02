package com.oilquiz.app.ai.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 题库文本预处理器（v4：混合管道专用）。
 * <p>
 * 对原始文本进行规范化处理，消除非标准格式噪音，使后续规则解析和 AI 解析更精准。
 * 处理步骤：
 * <ol>
 *   <li>编码修复：替换 UTF-8 解码错误替换符 U+FFFD</li>
 *   <li>全角→半角：数字、英文字母、标点符号</li>
 *   <li>空白规范化：统一换行符，合并多余空行</li>
 *   <li>去噪：移除页眉页脚类文本、分页符、纯分隔线</li>
 *   <li>题目边界修复：修复被截断的题号</li>
 * </ol>
 * 纯 Java 工具类，无 Android 依赖。
 */
public class QuestionPreprocessor {

    /** 常见噪声行模式（页眉、页脚、页码、分隔线） */
    private static final Pattern[] NOISE_PATTERNS = {
            Pattern.compile("^\\s*第\\s*\\d+\\s*页\\s*(共\\s*\\d+\\s*页)?\\s*$"),
            Pattern.compile("^[-=*_]{4,}\\s*$"),
            Pattern.compile("^\\s*\\d+\\s*/\\s*\\d+\\s*$"),
            Pattern.compile("^\\s*Page\\s+\\d+\\s*(of\\s+\\d+)?\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*版权所有\\s*.*$"),
            Pattern.compile("^\\s*内部资料\\s*.*$"),
            Pattern.compile("^\\s*机密\\s*$"),
            Pattern.compile("^\\s*\\d{4}-\\d{2}-\\d{2}\\s*$"),
    };

    private QuestionPreprocessor() {}

    /**
     * 完整预处理：编码修复 → 全角半角 → 空白规范化 → 去噪 → 边界修复。
     * @param raw 原始文本
     * @return 规范化后的文本
     */
    public static String preprocess(String raw) {
        if (raw == null || raw.isEmpty()) return "";

        String result = raw;

        // 1. 编码修复：移除替换符 U+FFFD
        result = result.replace("\uFFFD", "");

        // 2. 全角→半角
        result = fullWidthToHalfWidth(result);

        // 3. 空白规范化
        result = normalizeWhitespace(result);

        // 4. 去噪
        result = removeNoiseLines(result);

        // 5. 边界修复
        result = repairQuestionBoundaries(result);

        return result;
    }

    /**
     * 轻量预处理：仅做全角半角和空白规范化，速度更快。
     * 用于已通过程序解析的文本。
     */
    public static String preprocessLight(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return normalizeWhitespace(fullWidthToHalfWidth(raw));
    }

    /**
     * 全角字符转半角。
     * 处理范围：全角字母、数字、标点符号、空格。
     */
    public static String fullWidthToHalfWidth(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int code = c;
            // 全角空格 U+3000 → 半角空格 U+0020
            if (code == 0x3000) {
                sb.append(' ');
            }
            // 全角标点/字母/数字 U+FF01~U+FF5E → U+0021~U+007E
            else if (code >= 0xFF01 && code <= 0xFF5E) {
                sb.append((char) (code - 0xFEE0));
            }
            // 全角左括号 U+FF08 → ( , 全角右括号 U+FF09 → )
            else if (code == 0xFF08) {
                sb.append('(');
            } else if (code == 0xFF09) {
                sb.append(')');
            }
            // 中文标点：逗号、句号等保留（中文题目需要）
            else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 空白规范化：统一换行符为 \n，合并 3 个以上连续空行为 2 个空行。
     */
    public static String normalizeWhitespace(String text) {
        if (text == null || text.isEmpty()) return text;
        // 统一换行符
        String result = text.replace("\r\n", "\n").replace("\r", "\n");
        // 合并 3+ 空行为 2 个空行
        result = result.replaceAll("\n{3,}", "\n\n");
        // 去除行首行尾空白
        result = result.trim();
        return result;
    }

    /**
     * 移除噪声行：页眉、页脚、页码、分隔线等。
     */
    public static String removeNoiseLines(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                sb.append("\n");
                continue;
            }
            boolean isNoise = false;
            for (Pattern p : NOISE_PATTERNS) {
                if (p.matcher(trimmed).matches()) {
                    isNoise = true;
                    break;
                }
            }
            if (!isNoise) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 修复题目边界：合并被换行截断的题号。
     * 例如 "1.\n题干内容" → "1.题干内容"
     */
    public static String repairQuestionBoundaries(String text) {
        if (text == null || text.isEmpty()) return text;
        // 修复：题号后紧跟换行
        text = text.replaceAll("(?m)^(\\d+)[\\.、．)]\\s*\\n", "$1. ");
        // 修复：中文题号后紧跟换行
        text = text.replaceAll("(?m)^(第[\\d一二三四五六七八九十百]+题)\\s*\\n", "$1 ");
        return text;
    }

    /**
     * 预处理为行列表（保留有意义的行）。
     * @param raw 原始文本
     * @return 有效行列表
     */
    public static List<String> preprocessToLines(String raw) {
        String text = preprocess(raw);
        if (text.isEmpty()) return new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            // 保留空行作为题目分隔符，但跳过过长的纯分隔线
            if (trimmed.isEmpty()) {
                lines.add("");
            } else if (trimmed.matches("^[-=*_]{20,}$")) {
                // 长分隔线，跳过
                continue;
            } else {
                lines.add(trimmed);
            }
        }
        return lines;
    }
}