package com.oilquiz.app.ai.importing;

import android.util.Log;

import com.oilquiz.app.model.Question;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题库字段映射注册表。
 * <p>
 * 统一管理数据库字段名（Java属性名）与中文/英文/别名之间的对应关系，
 * 消除 ExcelUtil、RuleBasedQuestionParser、AIImportAgent 等各处的硬编码。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>{@link #resolve} — 将表头/JSON key 解析为标准字段名</li>
 *   <li>{@link #buildMappingFromHeaders} — 根据表头列表自动构建 列索引→标准字段 映射</li>
 *   <li>{@link #extractFromRow} — 按映射从行数据提取 Question</li>
 *   <li>{@link #extractFromJson} — 按映射从 JSON 对象提取 Question</li>
 *   <li>{@link #getAliases} — 获取标准字段的所有别名</li>
 * </ul>
 * 纯 Java 工具类，无 Android Context 依赖。
 */
public class FieldMappingRegistry {

    private static final String TAG = "FieldMappingRegistry";

    // ======================== 字段定义 ========================

    /** 字段定义 */
    public static class FieldDef {
        /** 标准字段名（与 Question Java 属性名一致） */
        public final String canonical;
        /** 中文显示名 */
        public final String displayName;
        /** 所有别名（中文+英文+常见变体），全部小写存储 */
        public final String[] aliases;
        /** 是否必填 */
        public final boolean required;

        public FieldDef(String canonical, String displayName, String[] aliases, boolean required) {
            this.canonical = canonical;
            this.displayName = displayName;
            this.aliases = aliases;
            this.required = required;
        }
    }

    /** 所有注册字段（保持插入顺序） */
    private static final List<FieldDef> FIELD_DEFS = new ArrayList<>();
    /** 标准字段名 → FieldDef */
    private static final Map<String, FieldDef> CANONICAL_MAP = new LinkedHashMap<>();
    /** 别名(小写) → 标准字段名 */
    private static final Map<String, String> ALIAS_MAP = new LinkedHashMap<>();

    static {
        // 核心字段（8个，与 QuestionSchemaDictionary 极简 Schema 一致）
        register("questionText", "题干",
                new String[]{"题目", "题干", "问题", "题目内容", "内容",
                        "question", "questiontext", "question_text", "stem", "content"},
                true);

        register("optionA", "选项A",
                new String[]{"选项A", "选项a", "选项甲",
                        "optionA", "option_a", "optiona", "a选项", "a"},
                false);

        register("optionB", "选项B",
                new String[]{"选项B", "选项b", "选项乙",
                        "optionB", "option_b", "optionb", "b选项", "b"},
                false);

        register("optionC", "选项C",
                new String[]{"选项C", "选项c", "选项丙",
                        "optionC", "option_c", "optionc", "c选项", "c"},
                false);

        register("optionD", "选项D",
                new String[]{"选项D", "选项d", "选项丁",
                        "optionD", "option_d", "optiond", "d选项", "d"},
                false);

        // —— 扩展选项 E~L（通过 setOptionByLetter 写入 extraOptions JSON）——
        final String[] cnExtraOrdinal = new String[]{"戊","己","庚","辛","壬","癸","子","丑"};
        char letter;
        int idx;
        for (letter = 'E', idx = 0; letter <= 'L'; letter++, idx++) {
            String letterStr = String.valueOf(letter);
            String letterLower = letterStr.toLowerCase();
            String cnExtra = (idx < cnExtraOrdinal.length) ? cnExtraOrdinal[idx] : "";
            String[] aliases;
            if (cnExtra != null && !cnExtra.isEmpty()) {
                aliases = new String[]{
                        "选项" + letterStr, "选项" + letterLower, "选项" + cnExtra,
                        "option" + letterStr, "option_" + letterLower, "option" + letterLower,
                        letterLower + "选项", letterStr + "选项",
                        letterStr, letterLower
                };
            } else {
                aliases = new String[]{
                        "选项" + letterStr, "选项" + letterLower,
                        "option" + letterStr, "option_" + letterLower, "option" + letterLower,
                        letterLower + "选项", letterStr + "选项",
                        letterStr, letterLower
                };
            }
            register("option" + letterStr, "选项" + letterStr, aliases, false);
        }

        register("correctAnswer", "正确答案",
                new String[]{"正确答案", "答案", "正确选项", "参考答案", "标准答案",
                        "correctAnswer", "correct_answer", "correctanswer",
                        "answer", "right_answer", "rightanswer"},
                true);

        // —— 填空题 空N 标准答案 blankAnswer1~12（合并写入 correctAnswer 分号分隔） ——
        final String[] cnNums = new String[]{"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"};
        for (int n = 1; n <= 12; n++) {
            String numStr = String.valueOf(n);
            String cnNum = (n < cnNums.length) ? cnNums[n] : "";
            String canon = "blankAnswer" + numStr;
            String displayName = "空" + numStr + "答案";
            String[] aliases;
            if (cnNum != null && !cnNum.isEmpty()) {
                aliases = new String[]{
                        "空" + numStr + "答案", "填空答案" + numStr,
                        "空" + numStr, "答案" + numStr, "填空" + numStr,
                        "第" + numStr + "空答案", "第" + numStr + "空",
                        "blankAnswer" + numStr, "blank_answer_" + numStr, "blank" + numStr,
                        "空" + cnNum + "答案", "答案" + cnNum, "空" + cnNum
                };
            } else {
                aliases = new String[]{
                        "空" + numStr + "答案", "填空答案" + numStr,
                        "空" + numStr, "答案" + numStr, "填空" + numStr,
                        "第" + numStr + "空答案", "第" + numStr + "空",
                        "blankAnswer" + numStr, "blank_answer_" + numStr, "blank" + numStr
                };
            }
            register(canon, displayName, aliases, false);
        }

        register("questionType", "题型",
                new String[]{"题型", "类型", "题目类型",
                        "questionType", "question_type", "questiontype",
                        "type", "qtype"},
                true);

        register("category", "分类",
                new String[]{"分类", "科目", "类别", "关键字", "知识点分类",
                        "category", "subject", "cat", "keyword"},
                false);

        // 扩展字段
        register("difficulty", "难度",
                new String[]{"难度", "难度等级", "difficulty", "level", "diff"},
                false);

        register("explanation", "解析",
                new String[]{"解析", "答案解析", "题目解析", "分析", "说明",
                        "explanation", "analysis", "reasoning", "exp"},
                false);

        register("hint", "提示",
                new String[]{"提示", "答案提示", "hint", "tips", "tip"},
                false);

        register("points", "分值",
                new String[]{"分值", "分数", "得分", "points", "score", "mark"},
                false);

        register("timeLimit", "时限",
                new String[]{"时限", "答题时限", "时间限制", "timeLimit", "time_limit", "timelimit", "timeout"},
                false);

        register("knowledgePoint", "知识点",
                new String[]{"知识点", "考点", "knowledgePoint", "knowledge_point", "knowledgepoint", "kp"},
                false);

        register("subCategory", "子分类",
                new String[]{"子分类", "子类别", "二级分类", "subCategory", "sub_category", "subcategory"},
                false);

        register("tags", "标签",
                new String[]{"标签", "标记", "tags", "tag", "label", "labels"},
                false);

        register("author", "作者",
                new String[]{"作者", "作者用户名", "作者姓名", "出题人", "author", "creator", "created_by"},
                false);

        register("comment", "备注",
                new String[]{"备注", "说明", "注释", "comment", "remark", "note", "memo"},
                false);

        register("extraOptions", "额外选项",
                new String[]{"额外选项", "扩展选项", "extraOptions", "extra_options", "extraoptions"},
                false);

        register("source", "来源",
                new String[]{"来源", "出处", "source", "origin"},
                false);
    }

    /** 注册一个字段 */
    private static void register(String canonical, String displayName, String[] aliases, boolean required) {
        FieldDef def = new FieldDef(canonical, displayName, aliases, required);
        FIELD_DEFS.add(def);
        CANONICAL_MAP.put(canonical, def);
        // 注册别名（包括标准名自身）
        ALIAS_MAP.put(canonical.toLowerCase(), canonical);
        if (displayName != null) {
            ALIAS_MAP.put(displayName.toLowerCase(), canonical);
        }
        if (aliases != null) {
            for (String alias : aliases) {
                ALIAS_MAP.put(alias.toLowerCase(), canonical);
            }
        }
    }

    // ======================== 公共 API ========================

    /**
     * 将表头名/JSON key 解析为标准字段名（三级匹配策略）。
     * <p>
     * 匹配顺序：
     * <ol>
     *   <li>精确匹配 — 别名表中直接查找</li>
     *   <li>模糊匹配 — 包含关系 + 编辑距离 ≤2</li>
     *   <li>返回 null — 交由 AI 动态识别</li>
     * </ol>
     *
     * @param rawName 原始名称（中文/英文/变体）
     * @return 标准字段名，无法识别返回 null
     */
    public static String resolve(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return null;
        String key = rawName.trim().toLowerCase();

        // 1. 精确匹配
        String canonical = ALIAS_MAP.get(key);
        if (canonical != null) return canonical;

        // 2. 模糊匹配：包含关系（表头包含某别名，或别名包含表头）
        canonical = fuzzyContainsMatch(key);
        if (canonical != null) return canonical;

        // 3. 模糊匹配：编辑距离 ≤2
        canonical = fuzzyEditDistanceMatch(key);
        if (canonical != null) return canonical;

        // 4. 无法识别，交由 AI 动态识别
        return null;
    }

    /**
     * 模糊匹配：包含关系。
     * 表头包含某别名（如"题干内容"包含"题干"），或别名包含表头（如"A"被"选项A"包含）。
     */
    private static String fuzzyContainsMatch(String key) {
        if (key == null || key.isEmpty()) return null;
        String bestMatch = null;
        int bestLen = 0; // 优先匹配最长的别名（更精确）
        for (Map.Entry<String, String> entry : ALIAS_MAP.entrySet()) {
            String alias = entry.getKey();
            if (alias.length() < 2) continue; // 跳过单字符别名（太容易误匹配）
            if (key.contains(alias) || alias.contains(key)) {
                if (alias.length() > bestLen) {
                    bestLen = alias.length();
                    bestMatch = entry.getValue();
                }
            }
        }
        return bestMatch;
    }

    /**
     * 模糊匹配：编辑距离 ≤2（处理拼写错误、简写变体）。
     */
    private static String fuzzyEditDistanceMatch(String key) {
        if (key == null || key.isEmpty() || key.length() < 3) return null;
        String bestMatch = null;
        int bestDist = 3; // 只接受距离 ≤2
        for (Map.Entry<String, String> entry : ALIAS_MAP.entrySet()) {
            String alias = entry.getKey();
            if (alias.length() < 3) continue; // 短别名不做编辑距离匹配
            int dist = editDistance(key, alias);
            if (dist < bestDist) {
                bestDist = dist;
                bestMatch = entry.getValue();
            }
        }
        return bestMatch;
    }

    /** 计算编辑距离（Levenshtein） */
    private static int editDistance(String a, String b) {
        int la = a.length(), lb = b.length();
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[lb];
    }

    /**
     * 获取标准字段的所有别名（含标准名自身和中文显示名）。
     */
    public static Set<String> getAliases(String canonical) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        FieldDef def = CANONICAL_MAP.get(canonical);
        if (def == null) return result;
        result.add(def.canonical);
        if (def.displayName != null) result.add(def.displayName);
        if (def.aliases != null) {
            for (String a : def.aliases) result.add(a);
        }
        return result;
    }

    /**
     * 获取所有已注册字段定义。
     */
    public static List<FieldDef> getAllFields() {
        return new ArrayList<>(FIELD_DEFS);
    }

    /**
     * 获取必填字段列表。
     */
    public static List<String> getRequiredFields() {
        List<String> required = new ArrayList<>();
        for (FieldDef def : FIELD_DEFS) {
            if (def.required) required.add(def.canonical);
        }
        return required;
    }

    // ======================== 表头映射构建 ========================

    /**
     * 根据表头列表自动构建 列索引→标准字段名 映射。
     * <p>
     * 遍历表头，用 {@link #resolve} 匹配标准字段名，
     * 第一个匹配的字段优先（重复列名只取第一个）。
     *
     * @param headers 表头列表
     * @return Map: 标准字段名 → 列索引
     */
    public static Map<String, Integer> buildMappingFromHeaders(List<String> headers) {
        Map<String, Integer> mapping = new LinkedHashMap<>();
        if (headers == null) return mapping;
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (header == null || header.trim().isEmpty()) continue;
            String canonical = resolve(header);
            if (canonical != null && !mapping.containsKey(canonical)) {
                mapping.put(canonical, i);
            }
        }
        Log.d(TAG, "表头映射构建完成: " + mapping);
        return mapping;
    }

    /**
     * 兼容旧接口：构建 中文字段名→列索引 的映射（供 ExcelUtil 旧代码过渡使用）。
     * 返回的 Map key 仍为中文显示名，但通过 resolve 解析。
     */
    public static Map<String, Integer> buildLegacyMappingFromHeaders(List<String> headers) {
        Map<String, Integer> mapping = new LinkedHashMap<>();
        if (headers == null) return mapping;
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (header == null || header.trim().isEmpty()) continue;
            String canonical = resolve(header);
            if (canonical != null) {
                FieldDef def = CANONICAL_MAP.get(canonical);
                String key = def != null ? def.displayName : canonical;
                if (!mapping.containsKey(key)) {
                    mapping.put(key, i);
                }
            }
        }
        return mapping;
    }

    // ======================== 数据提取 ========================

    /**
     * 按映射从行数据提取 Question。
     *
     * @param row     行数据（List<String>）
     * @param mapping 标准字段名 → 列索引
     * @return 填充好的 Question（未填的字段为默认值）
     */
    public static Question extractFromRow(List<String> row, Map<String, Integer> mapping) {
        Question q = new Question();
        if (row == null || mapping == null) return q;

        q.setQuestionText(getValue(row, mapping, "questionText"));
        // A~D
        q.setOptionA(getValue(row, mapping, "optionA"));
        q.setOptionB(getValue(row, mapping, "optionB"));
        q.setOptionC(getValue(row, mapping, "optionC"));
        q.setOptionD(getValue(row, mapping, "optionD"));
        // E~L（通过 setOptionByLetter 写入实体字段 A~D 或 extraOptions JSON）
        for (char c = 'E'; c <= 'L'; c++) {
            String canon = "option" + c;
            String v = getValue(row, mapping, canon);
            if (v != null && !v.isEmpty()) {
                q.setOptionByLetter(String.valueOf(c), v);
            }
        }
        // 正确答案 / 填空题多个空答案合并
        String correctAns = getValue(row, mapping, "correctAnswer");
        // 收集 blankAnswer1~12，若存在则按顺序拼接（分号分隔），和 correctAns 合并
        java.util.List<String> blankParts = new ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            String bv = getValue(row, mapping, "blankAnswer" + n);
            if (bv != null && !bv.trim().isEmpty()) blankParts.add(bv.trim());
        }
        if (!blankParts.isEmpty()) {
            String joined = String.join("；", blankParts);
            if (correctAns == null || correctAns.trim().isEmpty()) {
                correctAns = joined;
            } else if (!correctAns.contains(joined)) {
                correctAns = correctAns + "；" + joined;
            }
        }
        q.setCorrectAnswer(correctAns);
        q.setQuestionType(getValue(row, mapping, "questionType"));
        q.setCategory(getValue(row, mapping, "category"));
        q.setExplanation(getValue(row, mapping, "explanation"));
        q.setHint(getValue(row, mapping, "hint"));
        q.setKnowledgePoint(getValue(row, mapping, "knowledgePoint"));
        q.setSubCategory(getValue(row, mapping, "subCategory"));
        q.setTags(getValue(row, mapping, "tags"));
        q.setAuthor(getValue(row, mapping, "author"));
        q.setComment(getValue(row, mapping, "comment"));
        String eo = getValue(row, mapping, "extraOptions");
        if (eo != null && !eo.trim().isEmpty()) q.setExtraOptions(eo);
        q.setSource(getValue(row, mapping, "source"));

        // 数值字段
        String diffStr = getValue(row, mapping, "difficulty");
        q.setDifficulty(parseIntSafe(diffStr, 0));

        String pointsStr = getValue(row, mapping, "points");
        q.setPoints(parseIntSafe(pointsStr, 0));

        String timeStr = getValue(row, mapping, "timeLimit");
        q.setTimeLimit(parseIntSafe(timeStr, 0));

        // 默认值
        if (q.getSubCategory() == null) q.setSubCategory("");
        if (q.getHint() == null) q.setHint("");
        if (q.getExplanation() == null) q.setExplanation("");
        q.setAnalysis("");
        if (q.getKnowledgePoint() == null) q.setKnowledgePoint("");
        if (q.getTags() == null) q.setTags("");
        if (q.getAuthor() == null) q.setAuthor("");
        if (q.getComment() == null) q.setComment("");
        if (q.getExtraOptions() == null) q.setExtraOptions("");

        return q;
    }

    /**
     * 按映射从 JSON 对象提取 Question。
     * 尝试所有别名（标准名 + 中文显示名 + 英文别名），第一个非空即返回。
     *
     * @param jo JSON 对象
     * @return 填充好的 Question
     */
    public static Question extractFromJson(JSONObject jo) {
        Question q = new Question();
        if (jo == null) return q;

        q.setQuestionText(optStringMulti(jo, "questionText"));
        q.setOptionA(optStringMulti(jo, "optionA"));
        q.setOptionB(optStringMulti(jo, "optionB"));
        q.setOptionC(optStringMulti(jo, "optionC"));
        q.setOptionD(optStringMulti(jo, "optionD"));
        // E~L
        for (char c = 'E'; c <= 'L'; c++) {
            String canon = "option" + c;
            String v = optStringMulti(jo, canon);
            if (v != null && !v.isEmpty()) {
                q.setOptionByLetter(String.valueOf(c), v);
            }
        }
        // 正确答案 + blankAnswer1~12 合并
        String correctAns = optStringMulti(jo, "correctAnswer");
        java.util.List<String> blankParts = new ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            String bv = optStringMulti(jo, "blankAnswer" + n);
            if (bv != null && !bv.trim().isEmpty()) blankParts.add(bv.trim());
        }
        if (!blankParts.isEmpty()) {
            String joined = String.join("；", blankParts);
            if (correctAns == null || correctAns.trim().isEmpty()) {
                correctAns = joined;
            } else if (!correctAns.contains(joined)) {
                correctAns = correctAns + "；" + joined;
            }
        }
        q.setCorrectAnswer(correctAns);
        q.setQuestionType(optStringMulti(jo, "questionType"));
        q.setCategory(optStringMulti(jo, "category"));
        q.setExplanation(optStringMulti(jo, "explanation"));
        q.setHint(optStringMulti(jo, "hint"));
        q.setKnowledgePoint(optStringMulti(jo, "knowledgePoint"));
        q.setSubCategory(optStringMulti(jo, "subCategory"));
        q.setTags(optStringMulti(jo, "tags"));
        q.setAuthor(optStringMulti(jo, "author"));
        q.setComment(optStringMulti(jo, "comment"));
        String eo = optStringMulti(jo, "extraOptions");
        if (eo != null && !eo.trim().isEmpty()) q.setExtraOptions(eo);
        q.setSource(optStringMulti(jo, "source"));

        // 数值字段
        String diffStr = optStringMulti(jo, "difficulty");
        q.setDifficulty(parseDifficultySafe(diffStr));

        String pointsStr = optStringMulti(jo, "points");
        q.setPoints(parseIntSafe(pointsStr, 0));

        String timeStr = optStringMulti(jo, "timeLimit");
        q.setTimeLimit(parseIntSafe(timeStr, 0));

        // 默认值
        if (q.getSubCategory() == null) q.setSubCategory("");
        if (q.getHint() == null) q.setHint("");
        if (q.getExplanation() == null) q.setExplanation("");
        q.setAnalysis("");
        if (q.getKnowledgePoint() == null) q.setKnowledgePoint("");
        if (q.getTags() == null) q.setTags("");
        if (q.getAuthor() == null) q.setAuthor("");
        if (q.getComment() == null) q.setComment("");
        if (q.getExtraOptions() == null) q.setExtraOptions("");

        return q;
    }

    // ======================== 语义冲突检测 ========================

    /**
     * 检测表头列表中是否存在语义冲突。
     * <p>
     * 用于在映射前预警：当多个表头可能映射到同一字段时返回冲突信息。
     *
     * @param headers 表头列表
     * @return 冲突列表，空列表表示无冲突
     */
    public static List<String> detectMappingConflicts(List<String> headers) {
        List<String> conflicts = new ArrayList<>();
        if (headers == null || headers.size() < 2) return conflicts;

        // 按 canonical 分组
        Map<String, List<String>> fieldToHeaders = new LinkedHashMap<>();
        for (String h : headers) {
            if (h == null || h.trim().isEmpty()) continue;
            String canonical = resolve(h);
            if (canonical != null) {
                fieldToHeaders.computeIfAbsent(canonical, k -> new ArrayList<>()).add(h.trim());
            }
        }

        // 检测：同一 canonical 下有多个表头 → 冲突
        for (Map.Entry<String, List<String>> entry : fieldToHeaders.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(entry.getKey() + "(" + CANONICAL_MAP.get(entry.getKey()).displayName + ")"
                        + " ← 冲突表头: " + String.join(", ", entry.getValue()));
            }
        }

        // 检测：答案类表头与选项类表头混用（扩展到 N<=12）
        boolean hasAnswerSeries = headers.stream().anyMatch(h -> {
            if (h == null) return false;
            return h.matches(".*答案(?:[1-9]|10|11|12).*")
                    || h.matches(".*空(?:[1-9]|10|11|12)答案.*")
                    || h.matches(".*空(?:[一二三四五六七八九十]|十一|十二)答案.*");
        });
        boolean hasCorrectAnswer = headers.stream().anyMatch(h -> h != null &&
                (h.contains("正确答案") || h.contains("标准答案") || h.contains("参考答案")));
        boolean hasOptionSeries = headers.stream().anyMatch(h -> {
            if (h == null) return false;
            return h.matches(".*选项[1-9a-lA-L甲乙丙丁戊己庚辛壬癸子丑].*")
                    || h.matches(".*选项(?:[一二三四五六七八九十]|十一|十二).*");
        });
        boolean hasBlankAnswerSeries = headers.stream().anyMatch(h -> h != null && (
                h.matches(".*空(?:[1-9]|10|11|12).*") ||
                        h.matches(".*填空(?:[1-9]|10|11|12).*")));

        if (hasAnswerSeries && hasCorrectAnswer && !hasBlankAnswerSeries) {
            conflicts.add("答案N系列 与 正确答案 同时出现：答案1~12可能应映射为选项A~L，正确答案映射为 correctAnswer");
        }
        if (hasAnswerSeries && hasOptionSeries && !hasBlankAnswerSeries) {
            conflicts.add("答案N系列 与 选项N系列 同时出现：可能存在重复选项，请确认映射关系");
        }
        if (hasBlankAnswerSeries && hasCorrectAnswer) {
            conflicts.add("空N答案系列 与 正确答案 同时出现：空1~空12会合并拼接为 correctAnswer");
        }

        return conflicts;
    }

    // ======================== 辅助方法 ========================

    /**
     * 从行数据中按标准字段名取值（经过映射转换）。
     */
    private static String getValue(List<String> row, Map<String, Integer> mapping, String canonical) {
        Integer idx = mapping.get(canonical);
        if (idx == null || idx < 0 || idx >= row.size()) return "";
        String val = row.get(idx);
        return val != null ? val.trim() : "";
    }

    /**
     * 从 JSONObject 中按字段的所有别名依次尝试取值。
     * 候选 key 顺序：标准名 → 中文显示名 → 所有别名。
     */
    public static String optStringMulti(JSONObject jo, String canonical) {
        if (jo == null || canonical == null) return "";
        FieldDef def = CANONICAL_MAP.get(canonical);
        if (def == null) {
            // 未注册字段，直接用 canonical 名尝试
            return jo.optString(canonical, "");
        }

        // 1. 标准名
        if (jo.has(def.canonical)) {
            String v = jo.optString(def.canonical, "");
            if (!v.trim().isEmpty()) return v.trim();
        }

        // 2. 中文显示名
        if (def.displayName != null && jo.has(def.displayName)) {
            String v = jo.optString(def.displayName, "");
            if (!v.trim().isEmpty()) return v.trim();
        }

        // 3. 所有别名
        if (def.aliases != null) {
            for (String alias : def.aliases) {
                if (jo.has(alias)) {
                    String v = jo.optString(alias, "");
                    if (!v.trim().isEmpty()) return v.trim();
                }
            }
        }

        return "";
    }

    /** 安全解析整数 */
    private static int parseIntSafe(String s, int defaultVal) {
        if (s == null || s.trim().isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /** 解析难度值（支持数字和中文） */
    private static int parseDifficultySafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        String d = s.trim();
        try {
            return Integer.parseInt(d);
        } catch (NumberFormatException e) {
            // 中文难度映射
            switch (d) {
                case "简单":
                case "容易":
                case "初级":
                case "easy":
                    return 1;
                case "中等":
                case "一般":
                case "普通":
                case "medium":
                case "normal":
                    return 2;
                case "困难":
                case "难":
                case "高级":
                case "hard":
                case "difficult":
                    return 3;
                default:
                    return 0;
            }
        }
    }
}
