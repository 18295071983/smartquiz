package com.oilquiz.app.ai.importing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题库文件格式自动检测器（v4：混合管道专用）。
 * <p>
 * 对预处理后的文本进行格式指纹匹配，识别题库格式并返回置信度评分。
 * 检测层级：
 * <ol>
 *   <li>显式标记格式（GIFT {@code ::...::}、Aiken {@code ANSWER:}、Moodle XML）</li>
 *   <li>结构化格式（JSON、CSV、Excel Markdown 表格）</li>
 *   <li>半结构化格式（编号题、判断、填空混合）</li>
 *   <li>无结构格式（纯文本、段落式）</li>
 * </ol>
 * 纯 Java 工具类，无 Android 依赖。
 */
public class QuestionFormatDetector {

    // ======================== 格式枚举 ========================

    /** 检测到的题库文件格式 */
    public enum DetectedFormat {
        /** GIFT 格式：::Q1:: 题干 {=答案 ~干扰项} */
        GIFT,
        /** Aiken 格式：ANSWER: A */
        AIKEN,
        /** Moodle XML 格式：&lt;question type="..."&gt; */
        MOODLE_XML,
        /** 编号选择题：1. 题干 / A. 选项A / B. 选项B */
        NUMBERED_CHOICE,
        /** 判断题：含 √/×/T/F/对/错 标记 */
        TRUE_FALSE,
        /** 填空题：含 ___ 或 （  ） 标记 */
        FILL_BLANK,
        /** Excel Markdown 表格 */
        EXCEL_TABLE,
        /** JSON 格式 */
        JSON,
        /** CSV 格式 */
        CSV,
        /** 混合格式（同一文件含多种题型） */
        MIXED,
        /** 无法识别（需要 AI 兜底） */
        UNKNOWN
    }

    /** 检测结果 */
    public static class DetectionResult {
        public DetectedFormat format;
        public float confidence;          // 0~1 置信度
        public String description;        // 人类可读描述
        public Map<String, Float> details; // 各格式的详细评分
        public boolean needsAIFallback;   // 是否需要 AI 兜底

        public DetectionResult() {
            this.format = DetectedFormat.UNKNOWN;
            this.confidence = 0f;
            this.description = "无法识别格式";
            this.details = new LinkedHashMap<>();
            this.needsAIFallback = true;
        }
    }

    // ======================== 格式指纹正则 ========================

    // GIFT 格式特征
    private static final Pattern GIFT_QUESTION = Pattern.compile("::[^:]+::");
    private static final Pattern GIFT_ANSWER = Pattern.compile("\\{[=~][^}]*\\}");
    private static final Pattern GIFT_TRUE_FALSE = Pattern.compile("\\{[TF]|TRUE|FALSE\\}");

    // Aiken 格式特征
    private static final Pattern AIKEN_ANSWER = Pattern.compile("^ANSWER:\\s*[A-Za-z]$", Pattern.MULTILINE);
    private static final Pattern AIKEN_OPTION = Pattern.compile("^[A-Z][\\.\\)]\\s", Pattern.MULTILINE);

    // Moodle XML 格式特征
    private static final Pattern MOODLE_XML_TAG = Pattern.compile("<question\\s+type=");

    // 编号选择题特征
    private static final Pattern NUMBERED_QUESTION = Pattern.compile(
            "^\\s*(\\d+|[一二三四五六七八九十]+)[\\.、．)] ", Pattern.MULTILINE);
    private static final Pattern CHOICE_OPTION = Pattern.compile(
            "^\\s*[A-H][\\.、．)] ", Pattern.MULTILINE);
    private static final Pattern CHOICE_OPTION_PAREN = Pattern.compile(
            "^\\s*[（(]\\s*[A-Ha-h]\\s*[）)]", Pattern.MULTILINE);

    // 判断题特征
    private static final Pattern TF_MARK = Pattern.compile(
            "[（(]\\s*[√×✓✗TF对错是非正确错误]\\s*[）)]");
    private static final Pattern TF_ANSWER_LINE = Pattern.compile(
            "^\\s*(答案|正确选项|参考答案)[：:]\\s*[√×✓✗TF对错是非正确错误]",
            Pattern.MULTILINE);

    // 填空题特征
    private static final Pattern FILL_BLANK = Pattern.compile(
            "[（(]\\s*_{2,}\\s*[）)]|_{3,}|【\\s*】");

    // JSON 格式特征
    private static final Pattern JSON_STRUCTURE = Pattern.compile(
            "^\\s*[\\{\\[]");

    // CSV 格式特征
    private static final Pattern CSV_STRUCTURE = Pattern.compile(
            "^[^,]+,[^,]+,[^,]+");

    // Excel 表格特征（Markdown 表格）
    private static final Pattern MD_TABLE = Pattern.compile(
            "^\\|.*\\|\\s*$", Pattern.MULTILINE);
    private static final Pattern MD_TABLE_SEP = Pattern.compile(
            "^\\|\\s*[-:]+\\s*\\|", Pattern.MULTILINE);
    // Tab 分隔表格表头特征
    private static final Pattern TAB_TABLE_HEADER = Pattern.compile(
            "^(关键字|题型|难度|分数|题目内容|题干|可选项|选项|答案|正确答案|解析|说明)\\t",
            Pattern.MULTILINE);
    // Tab 分隔行特征（至少 3 列）
    private static final Pattern TAB_ROW = Pattern.compile(
            "^[^\\t]*\\t[^\\t]*\\t[^\\t]*$", Pattern.MULTILINE);

    // 答案解析特征
    private static final Pattern ANSWER_SECTION = Pattern.compile(
            "(答案|正确答案|参考答案|正确选项)[：:]\\s*[A-Za-z\\d]",
            Pattern.MULTILINE);
    private static final Pattern EXPLANATION_SECTION = Pattern.compile(
            "(解析|详解|说明|分析)[：:]",
            Pattern.MULTILINE);

    // 多选题特征：答案行包含多个字母
    private static final Pattern MULTI_ANSWER_SECTION = Pattern.compile(
            "^\\s*(答案|正确答案|参考答案|正确选项)[：:]\\s*[A-Ha-h][,，、\\s]*[A-Ha-h]",
            Pattern.MULTILINE);
    // 多选关键词
    private static final Pattern MULTI_KEYWORD = Pattern.compile(
            "(多选|不定项|至少选|有两个以上)");
    // 组合题/材料题特征
    private static final Pattern COMPOSITE_SECTION = Pattern.compile(
            "^\\s*(材料|共用题干|阅读理解|案例|背景材料)[：:]",
            Pattern.MULTILINE);

    private QuestionFormatDetector() {}

    // ======================== 检测入口 ========================

    /**
     * 检测文本的题库格式。
     * @param text 预处理后的文本
     * @return 检测结果
     */
    public static DetectionResult detect(String text) {
        return detect(text, null);
    }

    /**
     * 检测文本的题库格式（含文件名辅助判断）。
     * @param text 预处理后的文本
     * @param fileName 文件名（可为 null）
     * @return 检测结果
     */
    public static DetectionResult detect(String text, String fileName) {
        DetectionResult result = new DetectionResult();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        // 采样前 5000 字符做检测
        String sample = text.length() > 5000 ? text.substring(0, 5000) : text;

        // 统计各格式特征命中数
        Map<String, Float> scores = new LinkedHashMap<>();

        // 1. 显式格式
        float giftScore = scoreGift(sample);
        if (giftScore > 0) scores.put("GIFT", giftScore);

        float aikenScore = scoreAiken(sample);
        if (aikenScore > 0) scores.put("Aiken", aikenScore);

        float moodleScore = scoreMoodleXml(sample);
        if (moodleScore > 0) scores.put("Moodle XML", moodleScore);

        // 2. 结构化格式
        float jsonScore = scoreJson(sample, fileName);
        if (jsonScore > 0) scores.put("JSON", jsonScore);

        float csvScore = scoreCsv(sample, fileName);
        if (csvScore > 0) scores.put("CSV", csvScore);

        float tableScore = scoreExcelTable(sample);
        if (tableScore > 0) scores.put("Excel表格", tableScore);

        // 3. 半结构化格式
        float numberedScore = scoreNumberedChoice(sample);
        if (numberedScore > 0) scores.put("编号选择题", numberedScore);

        float tfScore = scoreTrueFalse(sample, numberedScore);
        if (tfScore > 0) scores.put("判断题", tfScore);

        float fillScore = scoreFillBlank(sample, numberedScore);
        if (fillScore > 0) scores.put("填空题", fillScore);

        // 4. 综合评分
        result.details = scores;

        if (scores.isEmpty()) {
            result.format = DetectedFormat.UNKNOWN;
            result.confidence = 0f;
            result.description = "无法识别格式，需 AI 解析";
            result.needsAIFallback = true;
            return result;
        }

        // 选择最高分格式
        String bestFormat = null;
        float bestScore = 0f;
        for (Map.Entry<String, Float> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                bestFormat = e.getKey();
            }
        }

        // 格式映射
        result.format = mapFormatName(bestFormat);
        result.confidence = Math.min(bestScore, 1.0f);

        // 判断是否需要 AI 兜底
        result.needsAIFallback = bestScore < 0.7f;

        // 描述
        StringBuilder desc = new StringBuilder();
        desc.append(bestFormat).append(" 格式");
        if (bestScore >= 0.85f) {
            desc.append(" (高置信度)");
        } else if (bestScore >= 0.7f) {
            desc.append(" (中置信度)");
        } else {
            desc.append(" (低置信度，建议 AI 复核)");
        }

        // 混合格式检测
        if (scores.size() >= 3 && bestScore < 0.85f) {
            result.format = DetectedFormat.MIXED;
            desc.insert(0, "混合格式: ");
            result.needsAIFallback = true;
        }

        result.description = desc.toString();
        return result;
    }

    /**
     * 快速判断文本是否适合规则解析（非 AI 兜底）。
     * @param result 检测结果
     * @return true 表示可以用规则解析
     */
    public static boolean canUseRuleParser(DetectionResult result) {
        if (result == null) return false;
        return result.confidence >= 0.7f && !result.needsAIFallback;
    }

    // ======================== 格式评分函数 ========================

    private static float scoreGift(String sample) {
        int questions = countMatches(GIFT_QUESTION, sample);
        int answers = countMatches(GIFT_ANSWER, sample);
        int tf = countMatches(GIFT_TRUE_FALSE, sample);
        // GIFT 注释行和分类标记也是重要特征
        int comments = countMatches(Pattern.compile("^//.*$", Pattern.MULTILINE), sample);
        int categories = countMatches(Pattern.compile("^\\$CATEGORY:", Pattern.MULTILINE), sample);
        // GIFT 匹配题特征
        int matches = countMatches(Pattern.compile("=.+?\\s*->\\s*.+"), sample);
        // GIFT 数字题特征
        int numerics = countMatches(Pattern.compile("\\{#"), sample);

        int totalFeatures = questions + answers + tf + comments + categories + matches + numerics;
        if (questions >= 2 && (answers >= 2 || tf >= 2 || matches >= 2 || numerics >= 2)) {
            return Math.min(1.0f, 0.75f + questions * 0.05f);
        }
        if (questions >= 1 || totalFeatures >= 3) {
            return 0.55f;
        }
        if (comments >= 2 || categories >= 1) {
            return 0.4f;
        }
        return 0f;
    }

    private static float scoreAiken(String sample) {
        int answerLines = countMatches(AIKEN_ANSWER, sample);
        int optionLines = countMatches(AIKEN_OPTION, sample);
        if (answerLines >= 2 && optionLines >= 4) {
            return Math.min(1.0f, 0.8f + answerLines * 0.05f);
        }
        if (answerLines >= 1 && optionLines >= 2) {
            return 0.5f;
        }
        return 0f;
    }

    private static float scoreMoodleXml(String sample) {
        int count = countMatches(MOODLE_XML_TAG, sample);
        if (count >= 2) return 0.95f;
        if (count >= 1) return 0.7f;
        return 0f;
    }

    private static float scoreJson(String sample, String fileName) {
        // 文件名优先
        if (fileName != null && fileName.toLowerCase().endsWith(".json")) {
            return 0.9f;
        }
        if (JSON_STRUCTURE.matcher(sample.trim()).find()) {
            return 0.6f;
        }
        return 0f;
    }

    private static float scoreCsv(String sample, String fileName) {
        // 文件名优先
        if (fileName != null && fileName.toLowerCase().endsWith(".csv")) {
            return 0.9f;
        }
        // 检查是否每行都有逗号分隔
        String[] lines = sample.split("\n");
        int csvLines = 0;
        for (String line : lines) {
            if (CSV_STRUCTURE.matcher(line.trim()).find()) {
                csvLines++;
            }
        }
        if (csvLines >= 3 && csvLines >= lines.length * 0.5f) {
            return 0.75f;
        }
        return 0f;
    }

    private static float scoreExcelTable(String sample) {
        // Markdown 表格检测
        int tableRows = countMatches(MD_TABLE, sample);
        int seps = countMatches(MD_TABLE_SEP, sample);
        if (tableRows >= 5 && seps >= 1) {
            return Math.min(1.0f, 0.75f + tableRows * 0.02f);
        }
        if (tableRows >= 2 && seps >= 1) {
            return 0.65f;
        }

        // Tab 分隔表格检测（FileContentExtractor 输出格式）
        int tabHeaders = countMatches(TAB_TABLE_HEADER, sample);
        int tabRows = countMatches(TAB_ROW, sample);
        if (tabHeaders >= 1 && tabRows >= 3) {
            return Math.min(0.95f, 0.78f + tabHeaders * 0.05f + Math.min(tabRows, 20) * 0.01f);
        }
        if (tabHeaders >= 1 && tabRows >= 1) {
            return 0.6f;
        }
        if (tabRows >= 5 && tabHeaders == 0) {
            return 0.4f;
        }

        return 0f;
    }

    private static float scoreNumberedChoice(String sample) {
        int numbered = countMatches(NUMBERED_QUESTION, sample);
        int options = countMatches(CHOICE_OPTION, sample)
                + countMatches(CHOICE_OPTION_PAREN, sample);
        int answerLines = countMatches(ANSWER_SECTION, sample);
        int multiAnswers = countMatches(MULTI_ANSWER_SECTION, sample);
        int multiKeywords = countMatches(MULTI_KEYWORD, sample);
        int compositeMarks = countMatches(COMPOSITE_SECTION, sample);

        if (numbered >= 3 && options >= numbered * 2) {
            float score = 0.7f + Math.min(numbered, 10) * 0.02f;
            if (answerLines >= 1) score += 0.1f;
            // 多选题特征加分
            if (multiAnswers >= 2 || multiKeywords >= 2) score += 0.05f;
            // 组合题特征加分
            if (compositeMarks >= 1) score += 0.03f;
            return Math.min(1.0f, score);
        }
        if (numbered >= 1 && options >= 2) {
            float score = 0.4f;
            if (multiAnswers >= 1 || multiKeywords >= 1) score += 0.1f;
            return Math.min(0.6f, score);
        }
        return 0f;
    }

    private static float scoreTrueFalse(String sample, float numberedScore) {
        int tfMarks = countMatches(TF_MARK, sample);
        int tfAnswerLines = countMatches(TF_ANSWER_LINE, sample);

        if (tfMarks >= 3) {
            return Math.min(1.0f, 0.7f + tfMarks * 0.03f);
        }
        if (tfMarks >= 1 && numberedScore < 0.3f) {
            return 0.4f;
        }
        if (tfAnswerLines >= 2) {
            return 0.6f;
        }
        return 0f;
    }

    private static float scoreFillBlank(String sample, float numberedScore) {
        int blanks = countMatches(FILL_BLANK, sample);
        if (blanks >= 3) {
            return Math.min(1.0f, 0.6f + blanks * 0.04f);
        }
        if (blanks >= 1 && numberedScore < 0.2f) {
            return 0.3f;
        }
        return 0f;
    }

    // ======================== 辅助方法 ========================

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) count++;
        return count;
    }

    private static DetectedFormat mapFormatName(String name) {
        if (name == null) return DetectedFormat.UNKNOWN;
        switch (name) {
            case "GIFT": return DetectedFormat.GIFT;
            case "Aiken": return DetectedFormat.AIKEN;
            case "Moodle XML": return DetectedFormat.MOODLE_XML;
            case "JSON": return DetectedFormat.JSON;
            case "CSV": return DetectedFormat.CSV;
            case "Excel表格": return DetectedFormat.EXCEL_TABLE;
            case "编号选择题": return DetectedFormat.NUMBERED_CHOICE;
            case "判断题": return DetectedFormat.TRUE_FALSE;
            case "填空题": return DetectedFormat.FILL_BLANK;
            default: return DetectedFormat.UNKNOWN;
        }
    }
}