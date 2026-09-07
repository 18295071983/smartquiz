package com.oilquiz.app.ai.importing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 题库 AI 导入 Schema 字典（v3 极简版）。
 * <p>
 * 仅提供极简抽取 Schema 和系统提示词。v3 移除所有 v1 旧 Schema（全字段抽取、结构探测），
 * 仅保留 8 字段极简模式，结合 NoThinking 方法论进一步缩减 prompt token。
 * <p>
 * 纯数据/工具类，无 Android 依赖，无 Context，可独立测试。
 */
public class QuestionSchemaDictionary {

    // ======================== 标准题型（全系统唯一权威口径） ========================
    // 与 com.oilquiz.app.model.Question 的 TYPE_* 常量保持一致；
    // 入库 questionType 统一为以下 5 种带"题"字格式，其他任何写法（英文/缩写/扩展题型）在
    // normalizeQuestionType 收口处映射到本组常量。

    /** 单选题 */
    public static final String TYPE_SINGLE = "单选题";
    /** 多选题 */
    public static final String TYPE_MULTIPLE = "多选题";
    /** 判断题 */
    public static final String TYPE_TRUE_FALSE = "判断题";
    /** 填空题 */
    public static final String TYPE_FILL = "填空题";
    /** 简答题（含问答/案例分析/计算/综合等主观题） */
    public static final String TYPE_SHORT_ANSWER = "简答题";
    /** 未分类（推断兜底值，非标准题型，入库前应被上层忽略或补填） */
    public static final String TYPE_UNCLASSIFIED = "未分类";

    /** 标准题型枚举值（schema enum 用） */
    private static final String[] QUESTION_TYPES = {
            TYPE_SINGLE, TYPE_MULTIPLE, TYPE_TRUE_FALSE, TYPE_FILL, TYPE_SHORT_ANSWER
    };

    /**
     * 题型归一化收口：任意写法 → 标准 5 种之一；无法识别返回 null（视为未分类）。
     * <p>
     * 覆盖中文全称/简称、英文及缩写（single/multiple/judge/fill/short、sc/mc/tf 等），
     * 并将扩展主观题型（案例分析/论述/计算/综合/问答）归并为"简答题"。
     * 空值/无法识别返回 null，由调用方决定是置"未分类"还是置空不填充。
     */
    public static String normalizeQuestionType(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String lc = t.toLowerCase(java.util.Locale.ROOT);

        if (t.contains("多选") || lc.contains("multiple") || lc.matches(".*\\bmc\\b.*")) {
            return TYPE_MULTIPLE;
        }
        if (t.contains("单选") || t.contains("单项选择") || lc.contains("single")
                || lc.matches(".*\\bsc\\b.*")) {
            return TYPE_SINGLE;
        }
        if (t.contains("判断") || t.contains("对错") || t.contains("是非")
                || lc.contains("truefalse") || lc.contains("true/false")
                || lc.contains("judge") || lc.contains("tf")) {
            return TYPE_TRUE_FALSE;
        }
        if (t.contains("填空") || t.contains("完形") || lc.contains("fill") || lc.contains("blank")) {
            return TYPE_FILL;
        }
        if (t.contains("简答") || t.contains("问答") || t.contains("主观")
                || t.contains("案例") || t.contains("论述") || t.contains("计算") || t.contains("综合")
                || lc.contains("short") || lc.contains("answer") || lc.contains("saq")) {
            return TYPE_SHORT_ANSWER;
        }
        // 泛指"选择题"按单选处理
        if (t.contains("选择") || lc.contains("choice")) {
            return TYPE_SINGLE;
        }
        return null;
    }

    private QuestionSchemaDictionary() {
        // 工具类，禁止实例化
    }

    // ===== 属性构造辅助方法 =====

    private static JSONObject stringProp(String description) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "string");
        o.put("description", description);
        return o;
    }

    private static JSONObject enumStringProp(String description, String[] enumValues) throws JSONException {
        JSONObject o = stringProp(description);
        JSONArray arr = new JSONArray();
        for (String v : enumValues) {
            arr.put(v);
        }
        o.put("enum", arr);
        return o;
    }

    // ======================== 极简抽取模式（v3：NoThinking） ========================

    /**
     * 极简抽取 Schema：仅 8 个核心字段。
     * 大幅缩减字段数量以降低每 chunk 的 prompt token 消耗，
     * 其余字段（difficulty/points/tags 等）入库时填充默认值。
     */
    public static JSONObject getMinimalExtractionSchema() throws JSONException {
        JSONObject p = new JSONObject();

        p.put("questionText", stringProp("题干原文"));
        p.put("optionA", stringProp("选项A"));
        p.put("optionB", stringProp("选项B"));
        p.put("optionC", stringProp("选项C"));
        p.put("optionD", stringProp("选项D"));
        p.put("correctAnswer", stringProp("正确答案"));
        p.put("questionType", enumStringProp("题型", QUESTION_TYPES));
        p.put("category", stringProp("分类/科目"));

        JSONObject item = new JSONObject();
        item.put("type", "object");
        item.put("properties", p);
        JSONArray req = new JSONArray();
        req.put("questionText");
        req.put("correctAnswer");
        req.put("questionType");
        item.put("required", req);

        JSONObject questions = new JSONObject();
        questions.put("type", "array");
        questions.put("description", "题目列表");
        questions.put("items", item);

        JSONObject props = new JSONObject();
        props.put("questions", questions);

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", props);
        JSONArray topReq = new JSONArray();
        topReq.put("questions");
        schema.put("required", topReq);
        return schema;
    }

    /**
     * Agent 级抽取系统提示词（v5：Few-shot + 强格式约束 + 自纠指令）。
     * <p>
     * v5 改进：
     * - 加入 few-shot 示例，显著提升格式遵循率
     * - 明确字段语义和取值规范
     * - 内置自纠指令：输出前自检 JSON 合法性
     * - 兼容在线/本地模型（在线模型理解力强，本地模型靠示例对齐）
     */
    public static String getMinimalExtractionPrompt() {
        return "你是题库结构化提取专家。从给定的文本中提取所有题目，输出标准JSON。\n\n"
                + "## 输出格式（严格遵守）\n"
                + "只输出以下JSON结构，禁止输出任何其他文字、解释、markdown标记：\n"
                + "{\"questions\":[{\"questionText\":\"题干\",\"optionA\":\"\",\"optionB\":\"\",\"optionC\":\"\",\"optionD\":\"\","
                + "\"optionE\":\"\",\"optionF\":\"\",\"optionG\":\"\",\"optionH\":\"\",\"optionI\":\"\",\"optionJ\":\"\",\"optionK\":\"\",\"optionL\":\"\","
                + "\"blankAnswer1\":\"\",\"blankAnswer2\":\"\",\"blankAnswer3\":\"\",\"blankAnswer4\":\"\",\"blankAnswer5\":\"\","
                + "\"blankAnswer6\":\"\",\"blankAnswer7\":\"\",\"blankAnswer8\":\"\",\"blankAnswer9\":\"\",\"blankAnswer10\":\"\","
                + "\"blankAnswer11\":\"\",\"blankAnswer12\":\"\","
                + "\"correctAnswer\":\"\",\"questionType\":\"\",\"category\":\"\",\"explanation\":\"\",\"knowledgePoint\":\"\",\"difficulty\":\"\"}]}\n\n"
                + "## 字段规范\n"
                + "1. questionText: 题干原文，保留原始文字，不翻译不改写。填空题的空位置可用 ___ 或 ___N___ 标记\n"
                + "2. optionA~L: 选择题选项内容（A~L共12列支持）。无选项的题填\"\"\n"
                + "3. blankAnswer1~12: 填空题的标准答案（对应空1~空12）。无填空填\"\"\n"
                + "4. correctAnswer: 最终正确答案。单选填字母(A/B/C/D...)，判断填(对/错)，多选按字母升序填(ABC/ABD...)，填空/简答/问答填答案文本，案例分析填要点\n"
                + "5. questionType: 题型。优先使用文本开头 DEFAULT_QUESTION_TYPE 提示的统一默认值（若存在）；否则自行判断。取值仅能是以下之一：单选题、多选题、判断题、填空题、简答题（问答题/案例分析/计算/综合等主观题均归为简答题）\n"
                + "6. category: 分类或科目，原文无则填\"通用\"\n"
                + "7. explanation/knowledgePoint/difficulty: 解析、知识点、难度（可选，空填\"\"，难度取 简单/中等/困难）\n\n"
                + "## 提取规则\n"
                + "- 保留原文，不编造不补全\n"
                + "- 一道题一个对象，不要合并\n"
                + "- 没有题目时输出 {\"questions\":[]}\n"
                + "- 输出前自检：确保是合法JSON，所有字符串已正确转义\n"
                + "- 若文本首部出现 \"DEFAULT_QUESTION_TYPE=XXX\" 或 \"SHEET_META...questionType=XXX\" 提示：所有题的 questionType 字段优先统一填这个默认值（不要随意改为其他值），除非某道题明确说明题型不同\n\n"
                + "## 示例\n"
                + "输入：1. 下列哪项是石油的主要成分？\\nA. 烷烃 B. 烯烃 C. 炔烃 D. 芳烃\\n答案: A\n"
                + "输出：{\"questions\":[{\"questionText\":\"下列哪项是石油的主要成分？\","
                + "\"optionA\":\"烷烃\",\"optionB\":\"烯烃\",\"optionC\":\"炔烃\",\"optionD\":\"芳烃\",\"correctAnswer\":\"A\",\"questionType\":\"单选题\",\"category\":\"通用\"}]}\n\n"
                + "输入：地球是太阳系中最大的行星。 答案：错\n"
                + "输出：{\"questions\":[{\"questionText\":\"地球是太阳系中最大的行星。\",\"correctAnswer\":\"错\",\"questionType\":\"判断题\",\"category\":\"通用\"}]}";
    }

    /**
     * 构建自纠重试 prompt（当首次输出校验失败时使用）。
     *
     * @param previousOutput AI 上次的原始输出
     * @param errors         校验错误列表
     * @return 修正 prompt
     */
    public static String getCorrectionPrompt(String previousOutput, java.util.List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("你上次的输出存在以下问题，请做【最小编辑修复】后重新输出完整的JSON：\n\n");
        sb.append("## 上次输出\n").append(previousOutput).append("\n\n");
        sb.append("## 错误列表\n");
        if (errors != null && !errors.isEmpty()) {
            for (int i = 0; i < errors.size(); i++) {
                sb.append((i + 1)).append(". ").append(errors.get(i)).append("\n");
            }
        } else {
            sb.append("输出格式不合法或无法解析为JSON\n");
        }
        sb.append("\n## 修复要求\n");
        sb.append("1. 只修正上述错误项，其余题目和字段内容必须逐字保持不变（禁止重新生成、改写或增删未出错的题目）\n");
        sb.append("2. 重新输出完整的 {\"questions\":[...]} JSON，包含全部题目（含未修改的）\n");
        sb.append("3. 只输出JSON，不要输出任何其他文字。确保字符串已正确转义、JSON合法。");
        return sb.toString();
    }

    /**
     * v7: 抽样质检 Schema — AI 对规则解析结果做单题一致性核验。
     */
    public static JSONObject getQaCheckSchema() throws JSONException {
        JSONObject p = new JSONObject();
        p.put("verdict", enumStringProp("核验结论", new String[]{"pass", "fail"}));
        p.put("reason", stringProp("结论理由，pass时填空字符串"));

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", p);
        JSONArray req = new JSONArray();
        req.put("verdict");
        schema.put("required", req);
        return schema;
    }

    /**
     * v7: 抽样质检 prompt — 校验一道规则解析出的题目字段是否自洽。
     */
    public static String getQaCheckPrompt(org.json.JSONObject questionJson) {
        return "你是题库数据质检员。请核验下面这道从表格中解析出的题目是否自洽：\n"
                + questionJson.toString() + "\n\n"
                + "核验要点：\n"
                + "1. questionText 非空且像一个完整的题目（不是表头、序号或无关文字）\n"
                + "2. correctAnswer 非空；若为字母答案(如A/B/AB)，对应选项字段应非空\n"
                + "3. 选项内容与题干主题相关，不存在选项与答案张冠李戴\n\n"
                + "只输出JSON：{\"verdict\":\"pass\"或\"fail\",\"reason\":\"理由\"}";
    }
}