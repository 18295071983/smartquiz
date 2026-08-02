package com.oilquiz.app.ai.importing;

import com.oilquiz.app.model.Question;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则引擎题目解析器（v4：混合管道专用）。
 * <p>
 * 使用正则表达式 + 状态机从文本中提取结构化题目。
 * 支持格式：
 * <ul>
 *   <li>编号选择题（1. 题干 / A. 选项A / B. 选项B）</li>
 *   <li>判断题（含 √/×/T/F/对/错 标记）</li>
 *   <li>填空题（含 ___ 或 （  ） 标记）</li>
 *   <li>GIFT 格式（::Q1:: 题干 {=答案 ~干扰项}）</li>
 *   <li>Aiken 格式（ANSWER: A）</li>
 * </ul>
 * 纯 Java 工具类，无 Android 依赖。
 */
public class RuleBasedQuestionParser {

    // ======================== 状态机状态 ========================

    private enum ParseState {
        INIT,           // 初始状态
        READING_STEM,   // 读取题干
        READING_OPTIONS, // 读取选项
        READING_ANSWER, // 读取答案
        READING_EXPLANATION // 读取解析
    }

    // ======================== 正则模式 ========================

    /** 题目编号模式：数字/中文编号 + 分隔符 */
    private static final Pattern QUESTION_NUM = Pattern.compile(
            "^\\s*(\\d+|[一二三四五六七八九十]+)[\\.、．)]\\s*(.+)");

    /** 选项模式：A/B/C/D + 分隔符 */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^\\s*([A-H])[\\.、．)]\\s*(.+)");

    /** 括号选项模式：(A)/(B) */
    private static final Pattern OPTION_PAREN = Pattern.compile(
            "^\\s*[（(]\\s*([A-Ha-h])\\s*[）)]\\s*(.+)");

    /** 答案标记模式 */
    private static final Pattern ANSWER_LINE = Pattern.compile(
            "^\\s*(答案|正确答案|参考答案|正确选项)[：:]\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    /** 解析标记模式 */
    private static final Pattern EXPLANATION_LINE = Pattern.compile(
            "^\\s*(解析|详解|说明|分析|解答)[：:]\\s*(.*)",
            Pattern.CASE_INSENSITIVE);

    /** Aiken ANSWER 模式 */
    private static final Pattern AIKEN_ANSWER = Pattern.compile(
            "^ANSWER:\\s*([A-Za-z])$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** GIFT 题干模式 */
    private static final Pattern GIFT_STEM = Pattern.compile("::([^:]+)::\\s*(.+)");

    /** GIFT 答案模式 */
    private static final Pattern GIFT_ANSWER = Pattern.compile("\\{(.+?)\\}");

    /** 判断题标记模式 */
    private static final Pattern TF_MARK = Pattern.compile(
            "([√×✓✗TF对错是非正确错误])");

    /** 填空题标记模式 */
    private static final Pattern FILL_MARK = Pattern.compile(
            "(_{3,}|【\\s*】|（\\s*）)");

    /** 多选答案模式：答案行包含多个字母（A,B,C / ABC / A、B、C） */
    private static final Pattern MULTI_ANSWER_LINE = Pattern.compile(
            "^\\s*(答案|正确答案|参考答案|正确选项)[：:]\\s*([A-Ha-h][A-Ha-h,，、\\s]*[A-Ha-h])\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** GIFT 百分比权重前缀：%100% 或 %-100% 或 %50.5% */
    private static final Pattern GIFT_WEIGHT = Pattern.compile("^%-?[\\d.]+%");

    /** GIFT 反馈标记：# 后跟反馈文本 */
    private static final Pattern GIFT_FEEDBACK = Pattern.compile("#([^#]*)$");

    /** GIFT 数字题答案：{#5} 或 {#5.2:0.1} 或 {#3.141..3.142} */
    private static final Pattern GIFT_NUMERIC = Pattern.compile("^#(.+)");

    /** GIFT 匹配题答案：=item -> match */
    private static final Pattern GIFT_MATCH = Pattern.compile("^=(.+?)\\s*->\\s*(.+)");

    /** GIFT 注释行：// 开头 */
    private static final Pattern GIFT_COMMENT = Pattern.compile("^//.*$",
            Pattern.MULTILINE);

    /** GIFT Markdown 格式标记 */
    private static final Pattern GIFT_MARKDOWN = Pattern.compile("^\\[markdown\\]", Pattern.CASE_INSENSITIVE);

    /** GIFT 分类标记：$CATEGORY: xxx */
    private static final Pattern GIFT_CATEGORY = Pattern.compile("^\\$CATEGORY:\\s*(.+)",
            Pattern.MULTILINE);

    /** 组合题/材料题标记 */
    private static final Pattern COMPOSITE_MARK = Pattern.compile(
            "^\\s*(材料|共用题干|阅读理解|案例|背景材料|共用备选答案)[：:]\\s*(.*)",
            Pattern.CASE_INSENSITIVE);

    /** 多选关键词 */
    private static final Pattern MULTI_CHOICE_KEYWORD = Pattern.compile(
            "(多选|不定项|至少选|选出.*?项|有两个以上|有两个或两个以上)");

    // ======================== 解析入口 ========================

    /**
     * 规则解析入口：根据检测到的格式选择解析策略。
     * @param text 预处理后的文本
     * @param format 检测到的格式
     * @return 解析出的题目列表
     */
    public static ParseResult parse(String text, QuestionFormatDetector.DetectedFormat format) {
        if (text == null || text.trim().isEmpty()) {
            return new ParseResult();
        }

        switch (format) {
            case GIFT:
                return parseGiftFormat(text);
            case AIKEN:
                return parseAikenFormat(text);
            case EXCEL_TABLE:
                return parseExcelTable(text);
            case TRUE_FALSE:
                return parseTrueFalseQuestions(text);
            case FILL_BLANK:
                return parseFillBlankQuestions(text);
            case NUMBERED_CHOICE:
            case MIXED:
            default:
                return parseNumberedQuestions(text);
        }
    }

    /**
     * 通用规则解析：自动检测格式并解析。
     * @param text 预处理后的文本
     * @return 解析结果
     */
    public static ParseResult parse(String text) {
        QuestionFormatDetector.DetectionResult detection = QuestionFormatDetector.detect(text);
        return parse(text, detection.format);
    }

    // ======================== 编号题目解析 ========================

    /**
     * 解析编号题目（选择题、多选、判断题、填空题混合）。
     * 支持多选答案标准化、解析跨多行、组合题材料前缀。
     */
    private static ParseResult parseNumberedQuestions(String text) {
        ParseResult result = new ParseResult();
        List<String> lines = QuestionPreprocessor.preprocessToLines(text);

        ParseState state = ParseState.INIT;
        StringBuilder currentStem = new StringBuilder();
        List<String> currentOptions = new ArrayList<>();
        String currentAnswer = "";
        StringBuilder currentExplanation = new StringBuilder();
        String currentType = "";
        String currentMaterial = "";  // 组合题共用材料

        for (String line : lines) {
            // 检查组合题/材料标记
            Matcher compMatcher = COMPOSITE_MARK.matcher(line);
            if (compMatcher.matches()) {
                currentMaterial = compMatcher.group(2).trim();
                continue;
            }

            // 检查是否是新题号
            Matcher numMatcher = QUESTION_NUM.matcher(line);
            if (numMatcher.matches()) {
                // 保存上一题
                if (currentStem.length() > 0) {
                    Question q = buildQuestion(
                            currentStem.toString(),
                            currentOptions,
                            currentAnswer,
                            currentExplanation.toString(),
                            currentType
                    );
                    if (q != null) {
                        // 附加组合题材料前缀
                        if (!currentMaterial.isEmpty()) {
                            q.setQuestionText("【材料】" + currentMaterial + "\n" + q.getQuestionText());
                        }
                        result.questions.add(q);
                        result.ruleParsed++;
                    }
                }

                // 重置状态
                String stemText = numMatcher.group(2);
                currentStem = new StringBuilder(stemText);
                currentOptions.clear();
                currentAnswer = "";
                currentExplanation = new StringBuilder();
                currentType = inferType(stemText);
                // 多选关键词检测
                if (isMultiChoiceStem(stemText)) {
                    currentType = "多选";
                }
                state = ParseState.READING_STEM;
                continue;
            }

            // 检查是否是选项行
            Matcher optMatcher = OPTION_LINE.matcher(line);
            if (!optMatcher.matches()) {
                optMatcher = OPTION_PAREN.matcher(line);
            }
            if (optMatcher.matches() && state != ParseState.INIT) {
                String optLabel = optMatcher.group(1).toUpperCase();
                currentOptions.add(optLabel + ". " + optMatcher.group(2));
                state = ParseState.READING_OPTIONS;
                if (currentType.isEmpty() || currentType.equals("简答")) {
                    currentType = "单选";
                }
                continue;
            }

            // 检查是否是答案行（含多选答案检测）
            Matcher ansMatcher = ANSWER_LINE.matcher(line);
            if (ansMatcher.matches() && state != ParseState.INIT) {
                currentAnswer = normalizeMultiAnswer(ansMatcher.group(2).trim());
                // 多选答案检测：标准化后长度 > 1 → 多选
                if (currentAnswer.length() > 1 && currentAnswer.matches("[A-H]+")) {
                    currentType = "多选";
                }
                state = ParseState.READING_ANSWER;
                continue;
            }

            // 检查是否是解析行
            Matcher expMatcher = EXPLANATION_LINE.matcher(line);
            if (expMatcher.matches() && state != ParseState.INIT) {
                currentExplanation = new StringBuilder(expMatcher.group(2));
                state = ParseState.READING_EXPLANATION;
                continue;
            }

            // 解析跨多行累积
            if (state == ParseState.READING_EXPLANATION && !line.isEmpty()) {
                if (currentExplanation.length() > 0) currentExplanation.append(" ");
                currentExplanation.append(line);
                continue;
            }

            // 继续累积题干内容
            if (state == ParseState.READING_STEM && !line.isEmpty()) {
                if (currentStem.length() > 0) currentStem.append(" ");
                currentStem.append(line);
            }
        }

        // 保存最后一题
        if (currentStem.length() > 0) {
            Question q = buildQuestion(
                    currentStem.toString(),
                    currentOptions,
                    currentAnswer,
                    currentExplanation.toString(),
                    currentType
            );
            if (q != null) {
                if (!currentMaterial.isEmpty()) {
                    q.setQuestionText("【材料】" + currentMaterial + "\n" + q.getQuestionText());
                }
                result.questions.add(q);
                result.ruleParsed++;
            }
        }

        return result;
    }

    // ======================== GIFT 格式解析 ========================

    /**
     * 解析 GIFT 格式（完整规范）。
     * 支持：选择题/多选题/判断题/填空题/匹配题/数字题/简答题/论述题
     * 特性：转义字符、百分比权重、反馈、注释、嵌套花括号、Markdown、分类
     */
    private static ParseResult parseGiftFormat(String text) {
        ParseResult result = new ParseResult();

        // 移除注释行
        String cleaned = GIFT_COMMENT.matcher(text).replaceAll("");
        // 提取分类
        Matcher catMatcher = GIFT_CATEGORY.matcher(cleaned);
        String category = "";
        if (catMatcher.find()) {
            category = catMatcher.group(1).trim();
        }

        // 按空行分割题目块
        String[] questionBlocks = cleaned.split("\n\\s*\n");

        for (String block : questionBlocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;

            // 提取题目名称（::title::）
            String questionName = "";
            String content = trimmed;
            if (trimmed.startsWith("::")) {
                int endName = trimmed.indexOf("::", 2);
                if (endName > 0) {
                    questionName = trimmed.substring(2, endName).trim();
                    content = trimmed.substring(endName + 2).trim();
                }
            }

            // 移除 Markdown 格式标记
            content = GIFT_MARKDOWN.matcher(content).replaceFirst("").trim();
            if (content.isEmpty()) continue;

            // 用栈匹配花括号（支持嵌套）
            int braceStart = content.indexOf('{');
            String answerContent = "";
            String stem = content;
            String feedback = "";

            if (braceStart >= 0) {
                int braceEnd = findMatchingBrace(content, braceStart);
                if (braceEnd > braceStart) {
                    answerContent = content.substring(braceStart + 1, braceEnd);
                    stem = content.substring(0, braceStart).trim();
                    // 提取花括号后的通用反馈（####general feedback）
                    if (braceEnd + 1 < content.length()) {
                        String afterBrace = content.substring(braceEnd + 1).trim();
                        if (afterBrace.startsWith("####")) {
                            feedback = unescapeGift(afterBrace.substring(4).trim());
                        }
                    }
                }
            }

            // 还原转义字符
            stem = unescapeGift(stem);
            if (stem.isEmpty()) continue;

            // 解析答案
            Question q = parseGiftAnswer(stem, answerContent, feedback, category);
            if (q != null) {
                if (!questionName.isEmpty()) {
                    q.setQuestionText("[" + questionName + "] " + q.getQuestionText());
                }
                result.questions.add(q);
                result.ruleParsed++;
            }
        }

        return result;
    }

    /**
     * 解析 GIFT 答案块内容，构建 Question。
     */
    private static Question parseGiftAnswer(String stem, String answerContent,
                                            String generalFeedback, String category) {
        // 空答案 → 简答/论述题
        if (answerContent == null || answerContent.trim().isEmpty()) {
            Question q = new Question();
            q.setQuestionText(stem);
            q.setQuestionType("简答");
            q.setCorrectAnswer("");
            q.setExplanation(generalFeedback);
            if (!category.isEmpty()) q.setCategory(category);
            return q;
        }

        String trimmed = answerContent.trim();

        // 数字题：{#5} 或 {#5.2:0.1} 或 {#3.141..3.142}
        if (trimmed.startsWith("#")) {
            Question q = new Question();
            q.setQuestionText(stem);
            q.setQuestionType("填空");
            // 提取数字答案
            String numContent = trimmed.substring(1).trim();
            // 简单提取第一个数字
            Matcher numM = Pattern.compile("([\\d.]+)").matcher(numContent);
            if (numM.find()) {
                q.setCorrectAnswer(numM.group(1));
            }
            q.setExplanation(generalFeedback);
            if (!category.isEmpty()) q.setCategory(category);
            return q;
        }

        // 判断题：{T} {F} {TRUE} {FALSE}
        if (trimmed.equals("T") || trimmed.equals("F")
                || trimmed.equals("TRUE") || trimmed.equals("FALSE")) {
            Question q = new Question();
            q.setQuestionText(stem);
            q.setQuestionType("判断");
            q.setCorrectAnswer(trimmed.equals("T") || trimmed.equals("TRUE") ? "对" : "错");
            q.setExplanation(generalFeedback);
            if (!category.isEmpty()) q.setCategory(category);
            return q;
        }

        // 匹配题：=item -> match
        Matcher matchTest = GIFT_MATCH.matcher(trimmed);
        if (matchTest.find() && trimmed.contains("->")) {
            Question q = new Question();
            q.setQuestionText(stem);
            q.setQuestionType("匹配");
            StringBuilder answer = new StringBuilder();
            String[] parts = trimmed.split("(?==)");
            for (String part : parts) {
                Matcher mm = GIFT_MATCH.matcher(part.trim());
                if (mm.matches()) {
                    if (answer.length() > 0) answer.append("; ");
                    answer.append(unescapeGift(mm.group(1).trim()))
                          .append(" -> ")
                          .append(unescapeGift(mm.group(2).trim()));
                }
            }
            q.setCorrectAnswer(answer.toString());
            q.setExplanation(generalFeedback);
            if (!category.isEmpty()) q.setCategory(category);
            return q;
        }

        // 选择题/填空题：含 = 或 ~
        if (trimmed.contains("=") || trimmed.contains("~")) {
            List<String> options = new ArrayList<>();
            StringBuilder correctAns = new StringBuilder();
            StringBuilder optionFeedback = new StringBuilder();
            int correctCount = 0;

            // 按分隔符拆分（保留前缀）
            String[] parts = trimmed.split("(?=[=~])");
            for (String part : parts) {
                if (part.isEmpty()) continue;

                char prefix = part.charAt(0);
                String rest = part.substring(1);

                // 剥离百分比权重
                rest = stripGiftWeight(rest);

                // 提取选项反馈（# 后内容）
                String optFeedback = "";
                Matcher fbMatcher = GIFT_FEEDBACK.matcher(rest);
                if (fbMatcher.find()) {
                    optFeedback = unescapeGift(fbMatcher.group(1).trim());
                    rest = rest.substring(0, fbMatcher.start()).trim();
                }

                // 还原转义
                String optText = unescapeGift(rest.trim());
                if (optText.isEmpty()) continue;

                char label = (char) ('A' + options.size());
                options.add(label + ". " + optText);

                if (prefix == '=') {
                    // 正确答案
                    correctAns.append(label);
                    correctCount++;
                    if (!optFeedback.isEmpty()) {
                        if (optionFeedback.length() > 0) optionFeedback.append(" ");
                        optionFeedback.append(label).append(": ").append(optFeedback);
                    }
                } else if (prefix == '~') {
                    // 干扰项
                    if (!optFeedback.isEmpty()) {
                        if (optionFeedback.length() > 0) optionFeedback.append(" ");
                        optionFeedback.append(label).append(": ").append(optFeedback);
                    }
                }
            }

            // 填空题检测：无干扰项（只有 = 开头的答案，且无选项上下文）
            if (correctCount >= 1 && options.size() == correctCount && correctCount <= 5) {
                // 可能是填空题（多个可接受答案）或多选题
                // 如果选项文本简短（≤20字）且数量≤5，视为多选
                // 否则视为填空题（多个可接受答案）
                boolean isFillBlank = true;
                for (String opt : options) {
                    String optText = opt.substring(3); // 去掉 "X. " 前缀
                    if (optText.length() > 20) {
                        isFillBlank = false;
                        break;
                    }
                }
                if (isFillBlank && correctCount <= 3) {
                    // 填空题
                    Question q = new Question();
                    q.setQuestionText(stem);
                    q.setQuestionType("填空");
                    // 多个可接受答案用 | 分隔
                    StringBuilder fillAnswer = new StringBuilder();
                    for (String opt : options) {
                        if (fillAnswer.length() > 0) fillAnswer.append("|");
                        fillAnswer.append(opt.substring(3));
                    }
                    q.setCorrectAnswer(fillAnswer.toString());
                    String fullFeedback = generalFeedback;
                    if (optionFeedback.length() > 0) {
                        fullFeedback = (fullFeedback.isEmpty() ? "" : fullFeedback + " ") + optionFeedback;
                    }
                    q.setExplanation(fullFeedback);
                    if (!category.isEmpty()) q.setCategory(category);
                    return q;
                }
            }

            // 选择题
            String type = correctCount > 1 ? "多选" : "单选";
            String fullFeedback = generalFeedback;
            if (optionFeedback.length() > 0) {
                fullFeedback = (fullFeedback.isEmpty() ? "" : fullFeedback + " ") + optionFeedback;
            }
            return buildQuestion(stem, options, correctAns.toString(), fullFeedback, type);
        }

        // 无标记答案 → 简答题
        Question q = new Question();
        q.setQuestionText(stem);
        q.setQuestionType("简答");
        q.setCorrectAnswer(unescapeGift(trimmed));
        q.setExplanation(generalFeedback);
        if (!category.isEmpty()) q.setCategory(category);
        return q;
    }

    // ======================== Aiken 格式解析 ========================

    /**
     * 解析 Aiken 格式：题干 / A. 选项 / B. 选项 / ANSWER: A
     * 支持多选答案（ANSWER: AB）。
     */
    private static ParseResult parseAikenFormat(String text) {
        ParseResult result = new ParseResult();
        // 扩展正则：支持多字母答案 ANSWER: ABC
        Pattern aikenMultiAnswer = Pattern.compile(
                "^ANSWER:\\s*([A-Ha-h]+)\\s*$", Pattern.MULTILINE);

        String[] blocks = text.split("(?=^ANSWER:)", Pattern.MULTILINE);

        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("ANSWER:")) continue;

            // 提取答案（支持多字母）
            Matcher ansMatcher = aikenMultiAnswer.matcher(trimmed);
            String answer = "";
            if (ansMatcher.find()) {
                answer = normalizeMultiAnswer(ansMatcher.group(1));
            }

            // 提取题干和选项
            String[] lines = trimmed.split("\n");
            StringBuilder stem = new StringBuilder();
            List<String> options = new ArrayList<>();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.matches("^ANSWER:.*")) continue;

                Matcher optMatcher = Pattern.compile("^([A-Z])[\\.\\)]\\s+(.+)").matcher(line);
                if (optMatcher.matches()) {
                    options.add(optMatcher.group(1) + ". " + optMatcher.group(2));
                } else if (stem.length() == 0) {
                    stem.append(line);
                } else {
                    stem.append(" ").append(line);
                }
            }

            if (stem.length() > 0) {
                // 多选答案检测
                String type = (answer.length() > 1 && answer.matches("[A-H]+")) ? "多选" : "单选";
                Question q = buildQuestion(stem.toString(), options, answer, "", type);
                if (q != null) {
                    result.questions.add(q);
                    result.ruleParsed++;
                }
            }
        }

        return result;
    }

    // ======================== Excel 表格解析 ========================

    /**
     * 解析 Excel 表格（Markdown 表格或 Tab 分隔）。
     * 支持表头自动识别：题型/题目内容/可选项/答案/难度/分数/解析。
     * 选项和答案支持 ; 分隔（如 "A;B;C"）。
     */
    private static ParseResult parseExcelTable(String text) {
        ParseResult result = new ParseResult();
        if (text == null || text.trim().isEmpty()) return result;

        // 检测格式：Markdown 表格 or Tab 分隔
        boolean isMarkdownTable = text.contains("|") && text.contains("---|");
        List<String[]> rows;

        if (isMarkdownTable) {
            rows = parseMarkdownTableRows(text);
        } else {
            rows = parseTabTableRows(text);
        }

        if (rows.size() < 2) return result;  // 至少需要表头 + 1 行数据

        // 第一行是表头，识别各列索引(按 Excel 模板规范)
        String[] headers = rows.get(0);
        int keywordCol = findColumn(headers, "关键字", "keyword");           // A列 → category
        int typeCol = findColumn(headers, "题型", "类型", "questiontype", "type");  // B列 → questionType
        int difficultyCol = findColumn(headers, "难度", "difficulty");       // C列 → difficulty
        int scoreCol = findColumn(headers, "分数", "分值", "score");         // D列 → points
        int stemCol = findColumn(headers, "题目内容", "题干", "题目", "question", "stem", "内容");  // E列
        int optionsCol = findColumn(headers, "可选项", "选项", "options");    // F列 → optionA-D + extraOptions
        int answerCol = findColumn(headers, "答案", "正确答案", "answer", "正确选项");  // G列 → correctAnswer
        int parentRowCol = findColumn(headers, "父题行号", "父题");           // H列 → 关联信息
        int relatedCol = findColumn(headers, "关联题目", "关联");             // I列 → relatedQuestion
        int explanationCol = findColumn(headers, "解析", "explanation", "分析");  // 解析列
        int commentCol = findColumn(headers, "说明", "备注", "comment");      // 说明列
        int authorCol = findColumn(headers, "作者用户名", "作者", "author");   // 作者列
        int authorNameCol = findColumn(headers, "作者姓名");                   // 作者姓名列

        // 如果找不到关键列，尝试用列位置推断(基于模板规范: A=关键字 B=题型 C=难度 D=分数 E=题目内容 F=可选项 G=答案)
        if (keywordCol < 0) keywordCol = 0;  // A 列
        if (typeCol < 0) typeCol = 1;  // B 列
        if (difficultyCol < 0) difficultyCol = 2;  // C 列
        if (scoreCol < 0) scoreCol = 3;  // D 列
        if (stemCol < 0) stemCol = 4;  // E 列
        if (optionsCol < 0) optionsCol = 5;  // F 列
        if (answerCol < 0) answerCol = 6;  // G 列

        // 遍历数据行
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row == null) continue;

            String stem = getCell(row, stemCol);
            if (stem == null || stem.trim().isEmpty()) continue;
            stem = stem.trim();

            String keyword = getCell(row, keywordCol);
            String type = getCell(row, typeCol);
            String optionsStr = getCell(row, optionsCol);
            String answer = getCell(row, answerCol);
            String difficulty = getCell(row, difficultyCol);
            String score = getCell(row, scoreCol);
            String explanation = getCell(row, explanationCol);
            String comment = getCell(row, commentCol);
            String author = getCell(row, authorCol);
            String authorName = getCell(row, authorNameCol);
            String related = getCell(row, relatedCol);
            String parentRow = getCell(row, parentRowCol);

            // 标准化题型(空题型返回空字符串,后续根据选项推断)
            type = normalizeQuestionType(type);

            // 解析选项(分号分隔 或 空格分隔) + 去掉选项自带字母前缀
            List<String> options = parseExcelOptions(optionsStr);

            // 题型为空时根据上下文推断(组合题子题/无选项 → 简答; 有选项 → 单选)
            if (type.isEmpty()) {
                if (!options.isEmpty()) {
                    // 有选项 → 选择题(暂定单选,后续根据答案检测多选)
                    type = "单选";
                } else {
                    // 无选项:组合题子题(关键字为空)或简答/问答题
                    type = "简答";
                }
            }

            // 标准化答案
            if (answer != null && !answer.trim().isEmpty()) {
                answer = answer.trim();
                // 仅对选择题做多选答案检测（填空/简答的答案含 ; 不代表多选）
                if ("单选".equals(type) || "多选".equals(type)) {
                    if (answer.contains(";") || answer.contains("；")) {
                        String normalized = normalizeMultiAnswer(
                                answer.replace(";", ",").replace("；", ","));
                        // 只有标准化后是纯字母才视为多选答案
                        if (normalized.matches("[A-H]+")) {
                            answer = normalized;
                            if (answer.length() > 1) type = "多选";
                        }
                        // 否则保持原始答案（可能是填空题的多个空用 ; 分隔）
                    } else if (answer.length() > 1 && answer.matches("[A-Ha-h]+")) {
                        answer = normalizeMultiAnswer(answer);
                        if (answer.length() > 1) type = "多选";
                    }
                }
                // 判断题答案标准化(按模板规范:字母答案保持不变,非字母转为字母)
                if ("判断".equals(type)) {
                    answer = normalizeTfAnswer(answer);
                }
            } else {
                answer = "";
            }

            Question q = buildQuestion(stem, options, answer,
                    explanation != null ? explanation.trim() : "", type);
            if (q != null) {
                // ===== 数据库字段映射(Excel 列 → Question 字段) =====

                // 关键字 → category(分类)
                if (keyword != null && !keyword.trim().isEmpty()) {
                    q.setCategory(keyword.trim());
                }

                // 难度 → difficulty(文本→数字映射)
                if (difficulty != null && !difficulty.trim().isEmpty()) {
                    int diff = parseDifficulty(difficulty.trim());
                    if (diff > 0) q.setDifficulty(diff);
                }

                // 分数 → points
                if (score != null && !score.trim().isEmpty()) {
                    int pts = parseScore(score.trim());
                    if (pts > 0) q.setPoints(pts);
                }

                // 作者 → author(优先用户名,无则用姓名)
                String authorVal = (author != null && !author.trim().isEmpty())
                        ? author.trim()
                        : (authorName != null ? authorName.trim() : "");
                if (!authorVal.isEmpty()) {
                    q.setAuthor(authorVal);
                }

                // 说明 → comment
                if (comment != null && !comment.trim().isEmpty()) {
                    q.setComment(comment.trim());
                }

                // 关联题目 → relatedQuestion
                if (related != null && !related.trim().isEmpty()) {
                    q.setRelatedQuestion(related.trim());
                }

                // 父题行号 → 组合题子题标记(存入 comment 前缀)
                if (parentRow != null && !parentRow.trim().isEmpty()) {
                    String marker = "[父题行:" + parentRow.trim() + "] ";
                    String existingComment = q.getComment();
                    q.setComment(marker + (existingComment != null ? existingComment : ""));
                }

                result.questions.add(q);
                result.ruleParsed++;
            }
        }

        return result;
    }

    /** 解析 Markdown 表格行为行数组 */
    private static List<String[]> parseMarkdownTableRows(String text) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) continue;
            // 跳过分隔行 |---|---|
            if (trimmed.matches("^\\|[-\\s|:]+\\|?$")) continue;
            // 拆分单元格
            String content = trimmed;
            if (content.startsWith("|")) content = content.substring(1);
            if (content.endsWith("|")) content = content.substring(0, content.length() - 1);
            String[] cells = content.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim();
                // 还原转义的管道符
                cells[i] = cells[i].replace("\\|", "|");
            }
            rows.add(cells);
        }
        return rows;
    }

    /** 解析 Tab 分隔表格行为行数组，列数不足的行合并到上一行 */
    private static List<String[]> parseTabTableRows(String text) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = text.split("\n");
        int expectedColCount = -1;

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] cells = line.split("\t", -1);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim();
            }

            // 第一行确定期望列数
            if (expectedColCount < 0) {
                expectedColCount = cells.length;
            }

            // 列数不足且已有上一行：合并到上一行（处理换行符漏网的情况）
            if (cells.length < expectedColCount && !rows.isEmpty()) {
                String[] lastRow = rows.get(rows.size() - 1);
                // 将内容追加到上一行最后一个单元格
                int lastIdx = lastRow.length - 1;
                lastRow[lastIdx] = lastRow[lastIdx] + " " + cells[0];
                continue;
            }

            rows.add(cells);
        }
        return rows;
    }

    /** 在表头中查找匹配列的索引 */
    private static int findColumn(String[] headers, String... keywords) {
        if (headers == null) return -1;
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null) continue;
            String h = headers[i].trim().toLowerCase();
            for (String kw : keywords) {
                if (h.contains(kw.toLowerCase())) return i;
            }
        }
        return -1;
    }

    /** 安全获取单元格值 */
    private static String getCell(String[] row, int col) {
        if (row == null || col < 0 || col >= row.length) return "";
        return row[col] != null ? row[col] : "";
    }

    /**
     * 标准化题型名称为 5 种合法值之一(单选/多选/判断/填空/简答)。
     * 空题型或无法识别的返回空字符串,由调用方根据上下文推断。
     * "匹配"/"论述"/"case" 等非标准题型统一降级为"简答"。
     */
    private static String normalizeQuestionType(String type) {
        if (type == null || type.trim().isEmpty()) return "";
        String t = type.trim();
        if (t.contains("单选") || t.contains("single")) return "单选";
        if (t.contains("多选") || t.contains("multiple")) return "多选";
        if (t.contains("判断") || t.contains("true") || t.contains("false")
                || t.equalsIgnoreCase("tf")) return "判断";
        if (t.contains("填空") || t.contains("fill")) return "填空";
        if (t.contains("简答") || t.contains("essay") || t.contains("问答")) return "简答";
        // 非标准题型(匹配/论述/case 等)统一降级为简答
        if (t.contains("匹配") || t.contains("match") || t.contains("论述")
                || t.contains("case") || t.contains("材料")) {
            return "简答";
        }
        // 其他无法识别的返回空字符串,由推断逻辑处理
        return "";
    }

    /**
     * 标准化判断题答案为字母格式(符合模板规范:答案为A-Z的一个字母)。
     * 按惯例:选项"对;错" → A=对, B=错。
     * 字母答案保持不变;非字母(对/错/√/×/T/F等)转为对应字母。
     */
    private static String normalizeTfAnswer(String answer) {
        if (answer == null || answer.isEmpty()) return "";
        String a = answer.trim().toUpperCase();
        // 已是字母,直接返回
        if (a.length() == 1 && a.charAt(0) >= 'A' && a.charAt(0) <= 'Z') {
            return a;
        }
        // 非字母格式转为字母(A=对/正确, B=错/错误)
        if (a.equals("对") || a.equals("正确")
                || a.equals("T") || a.equals("TRUE") || a.equals("√") || a.equals("✓")) {
            return "A";
        }
        if (a.equals("错") || a.equals("错误")
                || a.equals("F") || a.equals("FALSE") || a.equals("×") || a.equals("✗")) {
            return "B";
        }
        return a;
    }

    /** 将难度文本映射为数字（1=易, 2=较易, 3=中, 4=较难, 5=难） */
    private static int parseDifficulty(String difficulty) {
        if (difficulty == null || difficulty.isEmpty()) return 0;
        String d = difficulty.trim();
        // 纯数字直接返回
        if (d.matches("\\d+")) {
            int val = Integer.parseInt(d);
            return (val >= 1 && val <= 5) ? val : 0;
        }
        // 中文难度映射
        if (d.contains("简单") || d.equals("易")) return 1;
        if (d.contains("较易") || d.contains("偏易")) return 2;
        if (d.contains("中等") || d.equals("中")) return 3;
        if (d.contains("较难") || d.contains("偏难")) return 4;
        if (d.contains("困难") || d.equals("难")) return 5;
        // 英文难度映射
        String lower = d.toLowerCase();
        if (lower.contains("easy") || lower.contains("simple")) return 1;
        if (lower.contains("medium") || lower.contains("normal")) return 3;
        if (lower.contains("hard") || lower.contains("difficult")) return 5;
        return 0;
    }

    /** 将分数字符串解析为整数值 */
    private static int parseScore(String score) {
        if (score == null || score.isEmpty()) return 0;
        String s = score.trim();
        // 纯数字
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        // 带小数的取整
        if (s.matches("\\d+\\.\\d+")) {
            return (int) Double.parseDouble(s);
        }
        return 0;
    }

    /** 去掉选项文本自带的字母前缀(a. / A、 / (A) / （A）/ A． 等) */
    private static final Pattern OPTION_PREFIX_STRIP = Pattern.compile(
            "^\\s*(?:[（(]\\s*[A-Ha-h]\\s*[）)]|[A-Ha-h])\\s*[\\.、．]\\s*");

    private static String stripOptionLabelPrefix(String text) {
        if (text == null) return "";
        Matcher m = OPTION_PREFIX_STRIP.matcher(text);
        if (m.find()) {
            return text.substring(m.end()).trim();
        }
        return text.trim();
    }

    /**
     * 解析 Excel "可选项" 列文本为选项列表。
     * 支持两种分隔方式:
     *   1. 分号分隔(模板规范): "a.选项1;b.选项2;c.选项3"
     *   2. 空格分隔(常见手写): "A.1  B.3  C.5  D.2"
     *
     * 对每个选项:
     *   - 去掉自带字母前缀(a./A、/(A)/A．等)
     *   - 按顺序重新分配标签 A,B,C,D,E,F,G,H
     *
     * 返回格式: ["A. 选项1", "B. 选项2", ...]
     */
    private static List<String> parseExcelOptions(String optionsStr) {
        List<String> result = new ArrayList<>();
        if (optionsStr == null || optionsStr.trim().isEmpty()) {
            return result;
        }

        String raw = optionsStr.trim();
        List<String> rawParts;

        // 判断分隔方式:有分号 → 分号分隔;无分号 → 按 A/B/C/D 标记拆分
        boolean hasSemicolon = raw.contains(";") || raw.contains("；");

        if (hasSemicolon) {
            // 方式1:分号分隔
            String[] arr = raw.split("[;；]");
            rawParts = new ArrayList<>(arr.length);
            for (String s : arr) {
                String t = s.trim();
                if (!t.isEmpty()) rawParts.add(t);
            }
        } else {
            // 方式2:按 A-H 字母标记拆分(处理 "A.1  B.3  C.5  D.2" 格式)
            rawParts = splitOptionsByLetterMarkers(raw);
        }

        // 去前缀 + 重新分配标签
        for (int j = 0; j < rawParts.size(); j++) {
            String text = stripOptionLabelPrefix(rawParts.get(j));
            if (!text.isEmpty()) {
                char label = (char) ('A' + j);
                result.add(label + ". " + text);
            }
        }

        return result;
    }

    /**
     * 按 A-H 字母标记拆分手写选项文本。
     * 例: "A.1  B.3  C.5  D.2" → ["A.1", "B.3", "C.5", "D.2"]
     */
    private static List<String> splitOptionsByLetterMarkers(String raw) {
        List<String> parts = new ArrayList<>();
        // 匹配 A./A、/A．/(A)/（A）开头的位置
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:^|\\s+)(?=[（(]?\\s*[A-Ha-h]\\s*[）)]?\\s*[\\.、．])");
        String[] arr = p.split(raw);
        for (String s : arr) {
            String t = s.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        // 如果拆分失败(没有字母标记),整体作为单个选项
        if (parts.isEmpty() && !raw.trim().isEmpty()) {
            parts.add(raw.trim());
        }
        return parts;
    }

    // ======================== 判断题解析 ========================

    /**
     * 解析判断题。
     */
    private static ParseResult parseTrueFalseQuestions(String text) {
        ParseResult result = new ParseResult();
        List<String> lines = QuestionPreprocessor.preprocessToLines(text);

        for (String line : lines) {
            // 检查是否是判断题题目
            Matcher numMatcher = QUESTION_NUM.matcher(line);
            String stem = line;

            if (numMatcher.matches()) {
                stem = numMatcher.group(2);
            }

            // 检查是否包含判断标记
            Matcher tfMatcher = TF_MARK.matcher(stem);
            if (tfMatcher.find()) {
                String mark = tfMatcher.group(1);
                String answer = convertTfMark(mark);
                String cleanStem = stem.replaceAll("[（(]\\s*[√×✓✗TF对错是非正确错误]\\s*[）)]", "____").trim();

                if (!cleanStem.isEmpty()) {
                    Question q = new Question();
                    q.setQuestionText(cleanStem);
                    q.setQuestionType("判断");
                    q.setCorrectAnswer(answer);
                    result.questions.add(q);
                    result.ruleParsed++;
                }
            }
        }

        return result;
    }

    // ======================== 填空题解析 ========================

    /**
     * 解析填空题。
     */
    private static ParseResult parseFillBlankQuestions(String text) {
        ParseResult result = new ParseResult();
        List<String> lines = QuestionPreprocessor.preprocessToLines(text);

        StringBuilder currentStem = new StringBuilder();
        String currentAnswer = "";

        for (String line : lines) {
            // 检查是否是新题号
            Matcher numMatcher = QUESTION_NUM.matcher(line);
            if (numMatcher.matches()) {
                // 保存上一题
                if (currentStem.length() > 0) {
                    Question q = new Question();
                    q.setQuestionText(currentStem.toString());
                    q.setQuestionType("填空");
                    q.setCorrectAnswer(currentAnswer);
                    result.questions.add(q);
                    result.ruleParsed++;
                }

                currentStem = new StringBuilder(numMatcher.group(2));
                currentAnswer = "";
                continue;
            }

            // 检查是否是答案行
            Matcher ansMatcher = ANSWER_LINE.matcher(line);
            if (ansMatcher.matches()) {
                currentAnswer = ansMatcher.group(2).trim();
                continue;
            }

            // 累积题干
            if (!line.isEmpty()) {
                if (currentStem.length() > 0) currentStem.append(" ");
                currentStem.append(line);
            }
        }

        // 保存最后一题
        if (currentStem.length() > 0) {
            Question q = new Question();
            q.setQuestionText(currentStem.toString());
            q.setQuestionType("填空");
            q.setCorrectAnswer(currentAnswer);
            result.questions.add(q);
            result.ruleParsed++;
        }

        return result;
    }

    // ======================== 辅助方法 ========================

    /**
     * 构建 Question 对象。
     */
    private static Question buildQuestion(String stem, List<String> options,
                                          String answer, String explanation, String type) {
        if (stem == null || stem.trim().isEmpty()) return null;

        Question q = new Question();
        q.setQuestionText(stem.trim());
        q.setQuestionType(type.isEmpty() ? inferType(stem) : type);
        q.setCorrectAnswer(answer != null ? answer.trim() : "");
        q.setExplanation(explanation != null ? explanation : "");

        // 解析选项(A-D直接设字段, E-H累积到 extraOptions JSON)
        if (options != null && options.size() >= 2) {
            JSONObject extraOpts = null;  // 累积 E/F/G/H 选项
            for (String opt : options) {
                Matcher m = Pattern.compile("^([A-H])[\\.\\、]\\s*(.+)").matcher(opt);
                if (m.matches()) {
                    String label = m.group(1);
                    String text = m.group(2);
                    switch (label) {
                        case "A": q.setOptionA(text); break;
                        case "B": q.setOptionB(text); break;
                        case "C": q.setOptionC(text); break;
                        case "D": q.setOptionD(text); break;
                        default:
                            // E/F/G/H 存入 extraOptions JSON
                            if (extraOpts == null) extraOpts = new JSONObject();
                            try {
                                extraOpts.put(label, text);
                            } catch (JSONException ignored) {}
                            break;
                    }
                }
            }
            // 设置额外选项(JSON 格式: {"E":"...","F":"..."})
            if (extraOpts != null && extraOpts.length() > 0) {
                q.setExtraOptions(extraOpts.toString());
            }

            // 如果是选择题但没有选项，改为简答
            if ((type.equals("单选") || type.equals("多选"))
                    && q.getOptionA().isEmpty() && q.getOptionB().isEmpty()) {
                q.setQuestionType("简答");
            }
            // 多选题答案标准化
            if (type.equals("多选") && !answer.isEmpty()) {
                q.setCorrectAnswer(normalizeMultiAnswer(answer));
            }
        }

        // 默认值
        q.setCategory("");
        q.setDifficulty(0);
        q.setPoints(0);
        q.setTimeLimit(0);

        return q;
    }

    /**
     * 推断题目类型。
     * 优先检测多选关键词，再检测选项/判断/填空标记。
     */
    private static String inferType(String text) {
        if (text == null || text.isEmpty()) return "简答";

        // 优先检测多选关键词
        if (isMultiChoiceStem(text)) {
            return "多选";
        }

        // 检查选项模式
        if (OPTION_LINE.matcher(text).find() || OPTION_PAREN.matcher(text).find()) {
            return "单选";
        }

        // 检查判断标记
        if (TF_MARK.matcher(text).find()) {
            return "判断";
        }

        // 检查填空标记
        if (FILL_MARK.matcher(text).find()) {
            return "填空";
        }

        return "简答";
    }

    /**
     * 转换判断题标记为答案。
     */
    private static String convertTfMark(String mark) {
        if (mark == null) return "";
        switch (mark.trim()) {
            case "√": case "✓": case "对": case "正确": case "T": case "TRUE":
                return "对";
            case "×": case "✗": case "错": case "错误": case "F": case "FALSE":
                return "错";
            default:
                return mark;
        }
    }

    /**
     * 标准化多选答案：将 "A,B,C" / "A B C" / "A、B、C" / "abc" 统一为 "ABC"（排序去重）。
     * 单选答案 "A" 保持不变。
     */
    private static String normalizeMultiAnswer(String answer) {
        if (answer == null || answer.isEmpty()) return "";
        String trimmed = answer.trim().toUpperCase();
        // 移除常见分隔符
        String cleaned = trimmed.replaceAll("[,，、\\s]+", "");
        // 验证是否为纯字母组合
        if (cleaned.matches("[A-H]+")) {
            // 排序去重
            char[] chars = cleaned.toCharArray();
            Arrays.sort(chars);
            StringBuilder sb = new StringBuilder();
            char prev = 0;
            for (char c : chars) {
                if (c != prev) {
                    sb.append(c);
                    prev = c;
                }
            }
            return sb.toString();
        }
        // 非纯字母答案，返回清理后的原始值
        return trimmed;
    }

    /**
     * 剥离 GIFT 百分比权重前缀（如 "%100%答案" → "答案"）。
     */
    private static String stripGiftWeight(String text) {
        if (text == null || text.isEmpty()) return "";
        return GIFT_WEIGHT.matcher(text).replaceFirst("");
    }

    /**
     * 检测题干是否含多选关键词。
     */
    private static boolean isMultiChoiceStem(String stem) {
        if (stem == null || stem.isEmpty()) return false;
        return MULTI_CHOICE_KEYWORD.matcher(stem).find();
    }

    /**
     * 还原 GIFT 转义字符：\~ → ~, \= → =, \# → #, \{ → {, \} → }, \: → :, \n → 换行
     */
    private static String unescapeGift(String text) {
        if (text == null || text.isEmpty()) return "";
        // 先保护 \n（换行符）
        String result = text.replace("\\n", "\n");
        // 还原转义字符
        result = result.replace("\\~", "~")
                       .replace("\\=", "=")
                       .replace("\\#", "#")
                       .replace("\\{", "{")
                       .replace("\\}", "}")
                       .replace("\\:", ":")
                       .replace("\\\\", "\\");
        return result;
    }

    /**
     * 用栈匹配花括号，支持嵌套（GIFT 答案块内可能含嵌套花括号）。
     * @param text 原始文本
     * @param start 起始搜索位置（指向 '{'）
     * @return 闭合花括号的位置，-1 表示未找到
     */
    private static int findMatchingBrace(String text, int start) {
        if (start < 0 || start >= text.length() || text.charAt(start) != '{') return -1;
        int depth = 1;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i++; // 跳过转义字符
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ======================== 结果类 ========================

    /**
     * 规则解析结果。
     */
    public static class ParseResult {
        public List<Question> questions = new ArrayList<>();
        public int ruleParsed = 0;       // 规则解析的题目数
        public int aiParsed = 0;         // AI 解析的题目数（供外部设置）
        public List<String> errors = new ArrayList<>();

        public int total() {
            return questions.size();
        }

        public boolean isEmpty() {
            return questions.isEmpty();
        }
    }
}