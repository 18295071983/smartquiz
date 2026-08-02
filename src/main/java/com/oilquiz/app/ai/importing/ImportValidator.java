package com.oilquiz.app.ai.importing;

import com.oilquiz.app.model.Question;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题库 AI 导入校验器。
 *
 * 数据契约层:对 LLM 抽取的题目进行 JSON 语法修复 + 规则校验 + 语义一致性检查。
 * 纯 Java 工具类,不依赖 Android Context,不修改 Question 模型。
 *
 * 主要职责:
 * 1. 修复 LLM 输出 JSON 的常见失败模式(markdown 包裹、单引号、尾逗号、未闭合括号)。
 * 2. 从自由文本中抽取 JSON 子串。
 * 3. 单题规则校验(必填、范围、题型与选项/答案一致性)。
 * 4. 批量过滤有效题并收集错误。
 * 5. 计算字段填充覆盖率,供 UI 环形图展示。
 */
public class ImportValidator {

    /** markdown 代码块包裹正则(```json ... ``` 或 ``` ... ```) */
    private static final Pattern CODE_FENCE = Pattern.compile("(?s)```(?:json|JSON)?\\s*(.*?)```");

    /** 单个字母答案正则(A/B/C/D/E...) */
    private static final Pattern LETTER_ANSWER = Pattern.compile("^[A-Za-z]$");

    /** 判断题答案正则：对/错/正确/错误/A/B/T/F/TRUE/FALSE/√/×/✓/✗ */
    private static final Pattern TF_ANSWER = Pattern.compile(
            "^(对|错|正确|错误|[A-Za-z]|TRUE|FALSE|√|×|✓|✗)$",
            Pattern.CASE_INSENSITIVE);

    /** 字段覆盖率统计字段集合 */
    private static final String[] COVERAGE_FIELDS = {
            "questionText", "optionA", "optionB", "optionC", "optionD",
            "correctAnswer", "questionType", "category", "subCategory",
            "difficulty", "points", "timeLimit", "hint", "explanation",
            "analysis", "knowledgePoint", "tags", "author", "comment"
    };

    /**
     * 校验错误。
     */
    public static class ValidationError {
        /** 题目在批次中的下标(单题校验时为 -1) */
        public final int questionIndex;
        /** 出错字段名 */
        public final String field;
        /** 错误描述 */
        public final String message;

        public ValidationError(int questionIndex, String field, String message) {
            this.questionIndex = questionIndex;
            this.field = field;
            this.message = message;
        }

        @Override
        public String toString() {
            return "ValidationError{index=" + questionIndex
                    + ", field='" + field + "', message='" + message + "'}";
        }
    }

    // ======================== JSON 修复 ========================

    /**
     * 修复 LLM 输出的 JSON 文本。
     * 处理:markdown 包裹、前后自然语言解释、单引号、尾逗号、未闭合括号。
     * 返回修复后的字符串(可能仍非法,交由解析方处理)。
     */
    public static String repairJson(String raw) {
        if (raw == null) return "";
        String s = raw;

        // 1. 剥离 markdown 代码块包裹
        Matcher fence = CODE_FENCE.matcher(s);
        if (fence.find()) {
            s = fence.group(1);
        }

        // 2. 剥离前后多余自然语言(提取第一个 { 到最后一个 })
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }

        // 3. 修复单引号为双引号
        s = singleToDoubleQuotes(s);

        // 4. 移除尾逗号(,} -> }, ,] -> ])
        s = s.replaceAll(",\\s*}", "}");
        s = s.replaceAll(",\\s*]", "]");

        // 5. 修复未闭合的 } 和 ] (统计括号配对补齐)
        s = closeUnclosedBrackets(s);

        return s;
    }

    /**
     * 将 JSON 中作为字符串定界符的单引号转为双引号。
     * 使用状态机扫描,避免破坏字符串内的撇号。
     */
    private static String singleToDoubleQuotes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inDouble = false; // 是否在双引号内
        boolean inSingle = false; // 是否在单引号内
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                out.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                escape = true;
                continue;
            }
            if (inDouble) {
                out.append(c);
                if (c == '"') inDouble = false;
                continue;
            }
            if (inSingle) {
                if (c == '\'') {
                    out.append('"');
                    inSingle = false;
                } else {
                    out.append(c);
                }
                continue;
            }
            // 引号外
            if (c == '"') {
                out.append(c);
                inDouble = true;
            } else if (c == '\'') {
                out.append('"');
                inSingle = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 统计未闭合的 { 和 [,在末尾补齐对应的 } 和 ]。
     * 仅补齐未闭合,不处理过闭合(负数);字符串内的括号已尽量跳过。
     */
    private static String closeUnclosedBrackets(String s) {
        int brace = 0;   // {} 配对计数
        int bracket = 0; // [] 配对计数
        boolean inStr = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inStr = !inStr;
                continue;
            }
            if (inStr) continue;
            if (c == '{') brace++;
            else if (c == '}') brace--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
        }
        StringBuilder sb = new StringBuilder(s);
        while (brace > 0) {
            sb.append('}');
            brace--;
        }
        while (bracket > 0) {
            sb.append(']');
            bracket--;
        }
        return sb.toString();
    }

    // ======================== JSON 抽取 ========================

    /**
     * 从自由文本中提取 JSON 子串。
     * 先调用 repairJson,再用正则定位第一个 { 到匹配的最后一个 }。
     */
    public static String extractJson(String raw) {
        if (raw == null) return "";
        String repaired = repairJson(raw);
        int start = repaired.indexOf('{');
        int end = repaired.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return repaired.substring(start, end + 1);
        }
        return repaired;
    }

    // ======================== 三层 JSON 解析防线（v3） ========================

    /**
     * 三层 JSON 解析防线：逐层尝试解析 LLM 输出，任一成功即返回。
     * <p>
     * 防线 1：直接 JSON 解析（最快，成功率 ~80%）
     * 防线 2：提取 JSON 片段后解析（处理 markdown 包裹、前后说明文字）
     * 防线 3：修复常见错误后解析（单引号、尾逗号、未闭合括号）
     * <p>
     * 三层均失败返回 null，不抛异常。
     *
     * @param raw LLM 原始输出文本
     * @return 解析后的 JSONObject，失败返回 null
     */
    public static JSONObject parseStructuredOutput(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;

        // 防线 1：直接解析
        try {
            return new JSONObject(raw.trim());
        } catch (JSONException ignored) {
            // 进入防线 2
        }

        // 防线 2：提取 JSON 片段后解析
        String extracted = extractJson(raw);
        if (!extracted.isEmpty()) {
            try {
                return new JSONObject(extracted);
            } catch (JSONException ignored) {
                // 进入防线 3
            }
        }

        // 防线 3：修复后解析
        String repaired = repairJson(raw);
        if (!repaired.isEmpty()) {
            try {
                return new JSONObject(repaired);
            } catch (JSONException ignored) {
                // 全部失败
            }
        }

        return null;
    }

    // ======================== 题目校验 ========================

    /**
     * 单题规则校验,返回错误列表(空列表表示通过)。
     */
    public static List<ValidationError> validateQuestion(Question q) {
        return validateQuestion(q, -1);
    }

    private static List<ValidationError> validateQuestion(Question q, int index) {
        List<ValidationError> errors = new ArrayList<>();
        if (q == null) {
            errors.add(new ValidationError(index, "question", "题目对象为空"));
            return errors;
        }

        // 必填:questionText 非空且非纯空白
        String qt = q.getQuestionText();
        if (qt == null || qt.trim().isEmpty()) {
            errors.add(new ValidationError(index, "questionText", "题干为空"));
        }

        // 必填:correctAnswer 非空
        String ca = q.getCorrectAnswer();
        if (ca == null || ca.trim().isEmpty()) {
            errors.add(new ValidationError(index, "correctAnswer", "正确答案为空"));
        }

        // 题型限制:必填,且必须是 5 种合法题型之一(单选/多选/判断/填空/简答)
        String rawType = q.getQuestionType();
        if (rawType == null || rawType.trim().isEmpty()) {
            errors.add(new ValidationError(index, "questionType", "题型为空"));
        } else {
            String type = normalizeType(rawType);
            if (type == null) {
                errors.add(new ValidationError(index, "questionType",
                        "题型非法,应为 单选/多选/判断/填空/简答 之一,实际为: " + rawType));
            }
        }

        // difficulty 范围:0 视为未设置可放过,负数或 >5 报错
        int diff = q.getDifficulty();
        if (diff < 0 || diff > 5) {
            errors.add(new ValidationError(index, "difficulty", "难度 " + diff + " 越界,应为 0~5"));
        }

        // points 非负
        if (q.getPoints() < 0) {
            errors.add(new ValidationError(index, "points", "分值为负"));
        }
        // timeLimit 非负
        if (q.getTimeLimit() < 0) {
            errors.add(new ValidationError(index, "timeLimit", "时限为负"));
        }

        // 题型与选项数一致性:单选/多选至少 2 个非空选项;判断题可无选项
        String type = normalizeType(q.getQuestionType());
        int optCount = countNonEmptyOptions(q);
        if (isChoiceType(type) && optCount < 2) {
            errors.add(new ValidationError(index, "options", "选择题非空选项数不足(应≥2)"));
        }

        // correctAnswer 与选项一致性
        if (ca != null && !ca.trim().isEmpty()) {
            validateAnswerConsistency(type, ca, index, errors);
        }

        return errors;
    }

    /**
     * 校验答案与题型的一致性。
     * 单选:应为单个字母;判断:可为 对/错/A/B/T/F/√/× 等;多选:可为多个字母(如 ABC 或 A,B,C);填空/简答:不校验。
     */
    private static void validateAnswerConsistency(String type, String ca, int index, List<ValidationError> errors) {
        if (type == null) return;
        switch (type) {
            case "single": {
                // 单选:correctAnswer 应为单个字母
                String trimmed = ca.trim();
                if (!LETTER_ANSWER.matcher(trimmed).matches()) {
                    errors.add(new ValidationError(index, "correctAnswer",
                            "单选题答案应为单个字母,实际为: " + ca));
                }
                break;
            }
            case "truefalse": {
                // 判断:correctAnswer 可为 对/错/正确/错误/A/B/T/F/TRUE/FALSE/√/×/✓/✗
                String trimmed = ca.trim();
                if (!TF_ANSWER.matcher(trimmed).matches()) {
                    errors.add(new ValidationError(index, "correctAnswer",
                            "判断题答案应为 对/错/A/B/T/F 等,实际为: " + ca));
                }
                break;
            }
            case "multiple": {
                // 多选:correctAnswer 可为多个字母(如 "ABC","A,B,C")
                String cleaned = ca.replace(",", "").replace("，", "").replace(" ", "");
                if (cleaned.isEmpty()) {
                    errors.add(new ValidationError(index, "correctAnswer", "多选题答案为空"));
                } else {
                    for (int i = 0; i < cleaned.length(); i++) {
                        char ch = cleaned.charAt(i);
                        if (!Character.isLetter(ch)) {
                            errors.add(new ValidationError(index, "correctAnswer",
                                    "多选题答案含非法字符: " + ch));
                            break;
                        }
                    }
                }
                break;
            }
            // fill / shortanswer / null: 不校验选项
            default:
                break;
        }
    }

    // ======================== 批量校验 ========================

    /**
     * 过滤有效题,收集无效题错误。
     *
     * @param questions 待校验题目列表
     * @param outErrors 输出参数,收集所有无效题错误(可为 null)
     * @return 有效题列表
     */
    public static List<Question> filterValid(List<Question> questions, List<ValidationError> outErrors) {
        List<Question> valid = new ArrayList<>();
        if (questions == null) return valid;
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            List<ValidationError> errs = validateQuestion(q, i);
            if (errs.isEmpty()) {
                valid.add(q);
            } else if (outErrors != null) {
                outErrors.addAll(errs);
            }
        }
        return valid;
    }

    // ======================== 字段覆盖率 ========================

    /**
     * 计算各字段填充比例(0~1),用于 UI 环形图。
     * 覆盖字段:questionText/optionA~D/correctAnswer/questionType/category/subCategory/
     * difficulty/points/timeLimit/hint/explanation/analysis/knowledgePoint/tags/author/comment。
     */
    public static Map<String, Float> computeFieldCoverage(List<Question> questions) {
        Map<String, Float> coverage = new LinkedHashMap<>();
        if (questions == null || questions.isEmpty()) {
            for (String f : COVERAGE_FIELDS) coverage.put(f, 0f);
            return coverage;
        }
        int total = questions.size();
        Map<String, Integer> counts = new HashMap<>();
        for (String f : COVERAGE_FIELDS) counts.put(f, 0);

        for (Question q : questions) {
            if (isFilled(q.getQuestionText())) counts.merge("questionText", 1, Integer::sum);
            if (isFilled(q.getOptionA())) counts.merge("optionA", 1, Integer::sum);
            if (isFilled(q.getOptionB())) counts.merge("optionB", 1, Integer::sum);
            if (isFilled(q.getOptionC())) counts.merge("optionC", 1, Integer::sum);
            if (isFilled(q.getOptionD())) counts.merge("optionD", 1, Integer::sum);
            if (isFilled(q.getCorrectAnswer())) counts.merge("correctAnswer", 1, Integer::sum);
            if (isFilled(q.getQuestionType())) counts.merge("questionType", 1, Integer::sum);
            if (isFilled(q.getCategory())) counts.merge("category", 1, Integer::sum);
            if (isFilled(q.getSubCategory())) counts.merge("subCategory", 1, Integer::sum);
            if (q.getDifficulty() != 0) counts.merge("difficulty", 1, Integer::sum);
            if (q.getPoints() != 0) counts.merge("points", 1, Integer::sum);
            if (q.getTimeLimit() != 0) counts.merge("timeLimit", 1, Integer::sum);
            if (isFilled(q.getHint())) counts.merge("hint", 1, Integer::sum);
            if (isFilled(q.getExplanation())) counts.merge("explanation", 1, Integer::sum);
            if (isFilled(q.getAnalysis())) counts.merge("analysis", 1, Integer::sum);
            if (isFilled(q.getKnowledgePoint())) counts.merge("knowledgePoint", 1, Integer::sum);
            if (isFilled(q.getTags())) counts.merge("tags", 1, Integer::sum);
            if (isFilled(q.getAuthor())) counts.merge("author", 1, Integer::sum);
            if (isFilled(q.getComment())) counts.merge("comment", 1, Integer::sum);
        }

        for (String f : COVERAGE_FIELDS) {
            coverage.put(f, counts.get(f) * 1f / total);
        }
        return coverage;
    }

    // ======================== 辅助方法 ========================

    /** 字符串非空且非纯空白 */
    private static boolean isFilled(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 统计非空选项数(A~D + extraOptions 中的 E/F/G...) */
    private static int countNonEmptyOptions(Question q) {
        int n = 0;
        if (isFilled(q.getOptionA())) n++;
        if (isFilled(q.getOptionB())) n++;
        if (isFilled(q.getOptionC())) n++;
        if (isFilled(q.getOptionD())) n++;
        String extra = q.getExtraOptions();
        if (isFilled(extra)) {
            try {
                JSONObject jo = new JSONObject(extra);
                Iterator<String> keys = jo.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = jo.optString(k, "");
                    if (isFilled(v)) n++;
                }
            } catch (JSONException ignore) {
                // extraOptions 解析失败,忽略
            }
        }
        return n;
    }

    /** 是否为选择题类型(单选/多选) */
    private static boolean isChoiceType(String type) {
        return "single".equals(type) || "multiple".equals(type);
    }

    /**
     * 将 questionType 归一化为内部标识。
     * 兼容中文(单选/多选/判断/填空/简答)及常见英文(single/multiple/truefalse/fill/short)。
     * 无法识别时返回 null(按通用规则宽松处理,不强制报错)。
     */
    private static String normalizeType(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase();
        if (t.isEmpty()) return null;
        if (t.contains("单选") || t.contains("single")) return "single";
        if (t.contains("多选") || t.contains("multiple")) return "multiple";
        if (t.contains("判断") || t.contains("truefalse") || t.contains("true/false") || t.contains("judge")) {
            return "truefalse";
        }
        if (t.contains("填空") || t.contains("fill")) return "fill";
        if (t.contains("简答") || t.contains("short")) return "shortanswer";
        // 泛指"选择题"按单选处理
        if (t.contains("选择")) return "single";
        return null;
    }
}
