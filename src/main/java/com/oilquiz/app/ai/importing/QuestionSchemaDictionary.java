package com.oilquiz.app.ai.importing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 题库 AI 导入 Schema 字典类。
 * <p>
 * 提供 LLM 抽取题目的 JSON Schema、结构探测 Schema、系统提示词以及字段别名映射。
 * 字段对齐数据库 v20 全字段（见 {@code com.oilquiz.app.model.Question}）。
 * <p>
 * 纯数据/工具类，无 Android 依赖，无 Context，可独立测试。
 */
public class QuestionSchemaDictionary {

    /** 题目类型枚举值 */
    private static final String[] QUESTION_TYPES = {"单选", "多选", "判断", "填空", "简答"};

    /** 难度枚举值（1~5） */
    private static final int[] DIFFICULTIES = {1, 2, 3, 4, 5};

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

    private static JSONObject intProp(String description) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "integer");
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

    private static JSONObject enumIntProp(String description, int[] enumValues) throws JSONException {
        JSONObject o = intProp(description);
        JSONArray arr = new JSONArray();
        for (int v : enumValues) {
            arr.put(v);
        }
        o.put("enum", arr);
        return o;
    }

    /**
     * 构建单个题目字段属性对象（items.properties）。
     */
    private static JSONObject buildItemProperties() throws JSONException {
        JSONObject p = new JSONObject();

        // 题目内容
        p.put("questionText", stringProp("题目内容/题干，保留原文，不要改写或翻译。"));

        // 选项 A~D
        p.put("optionA", stringProp("选项A内容。无则留空字符串。"));
        p.put("optionB", stringProp("选项B内容。无则留空字符串。"));
        p.put("optionC", stringProp("选项C内容。无则留空字符串。"));
        p.put("optionD", stringProp("选项D内容。无则留空字符串。"));

        // 额外选项 E/F/G...，平铺 object，仅 1 层
        JSONObject extraOptionsProp = new JSONObject();
        extraOptionsProp.put("type", "object");
        extraOptionsProp.put("description",
                "额外选项 E/F/G...，键为选项字母，值为选项内容。仅一层嵌套，不要深层嵌套。"
                        + "例如 {\"E\":\"...\",\"F\":\"...\"}。无则留空对象 {}。A~D 不要放进本字段。");
        p.put("extraOptions", extraOptionsProp);

        // 正确答案
        p.put("correctAnswer", stringProp(
                "正确答案。单选填选项字母如 \"A\"；多选填字母组合如 \"ABD\"；"
                        + "判断填 \"对\"/\"错\" 或 \"正确\"/\"错误\"；填空/简答填完整答案文本。"));

        // 题目类型（enum）
        p.put("questionType", enumStringProp("题目类型。", QUESTION_TYPES));

        // 分类
        p.put("category", stringProp("分类/科目/学科。"));

        // 子分类
        p.put("subCategory", stringProp("子分类/章节/小节。"));

        // 难度（enum 1~5）
        p.put("difficulty", enumIntProp("难度，1 最易，5 最难。", DIFFICULTIES));

        // 分值
        p.put("points", intProp("题目分值，默认 0。"));

        // 时限（秒）
        p.put("timeLimit", intProp("答题时限（秒），0 表示不限时。"));

        // 提示
        p.put("hint", stringProp("答题提示。无则留空字符串。"));

        // 简要解析
        p.put("explanation", stringProp("简要解析/说明。"));

        // 详细解析
        p.put("analysis", stringProp("详细解析/深度分析/详解。"));

        // 知识点
        p.put("knowledgePoint", stringProp("知识点/考点。"));

        // 标签（逗号分隔）
        p.put("tags", stringProp("标签/关键词，多个用英文逗号分隔。"));

        // 作者
        p.put("author", stringProp("作者/出题人。"));

        // 备注
        p.put("comment", stringProp("备注。"));

        // 置信度(0~1):抽取过程门控用,依据 byaiteam 单条 confidence
        JSONObject confidenceProp = new JSONObject();
        confidenceProp.put("type", "number");
        confidenceProp.put("minimum", 0);
        confidenceProp.put("maximum", 1);
        confidenceProp.put("description", "本次抽取的置信度(0~1),不确定给较低值。");
        p.put("confidence", confidenceProp);

        return p;
    }

    /**
     * 返回扁平 JSON Schema（供 LLM 结构化输出用）。
     * <p>
     * 嵌套层级 ≤2：questions 数组 → items 对象 → extraOptions 平铺 object。
     * 结构形如：
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "questions": {
     *       "type": "array",
     *       "items": {
     *         "type": "object",
     *         "properties": { ...各字段,带 description 和 enum... },
     *         "required": ["questionText", "correctAnswer"]
     *       }
     *     }
     *   },
     *   "required": ["questions"]
     * }
     * </pre>
     */
    public static JSONObject getExtractionSchema() throws JSONException {
        // 单个题目 items 对象
        JSONObject item = new JSONObject();
        item.put("type", "object");
        item.put("properties", buildItemProperties());
        // 防幻觉:禁止额外字段(依据 Promptise "additionalProperties:false")
        item.put("additionalProperties", false);
        JSONArray requiredItem = new JSONArray();
        requiredItem.put("questionText");
        requiredItem.put("correctAnswer");
        // confidence 必填(依据 byaiteam:单条 confidence 门控)
        requiredItem.put("confidence");
        item.put("required", requiredItem);

        // questions 数组
        JSONObject questions = new JSONObject();
        questions.put("type", "array");
        questions.put("description", "抽取到的题目列表，保持原文顺序。");
        questions.put("items", item);

        // 顶层
        JSONObject properties = new JSONObject();
        properties.put("questions", questions);

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        JSONArray required = new JSONArray();
        required.put("questions");
        schema.put("required", required);
        return schema;
    }

    /**
     * 阶段 2 结构探测用 schema。
     * <p>
     * 输出形如：
     * <pre>
     * {"detected_format":"...","question_type_guess":"...","field_hints":{...},"confidence":0.92}
     * </pre>
     */
    public static JSONObject getDetectionSchema() throws JSONException {
        JSONObject properties = new JSONObject();
        properties.put("detected_format", stringProp(
                "探测到的题目文档格式，如 \"单选多选混排\"、\"纯文本问答\"、\"Markdown 列表\" 等。"));
        properties.put("question_type_guess", enumStringProp("猜测的主要题目类型。", QUESTION_TYPES));

        JSONObject fieldHints = new JSONObject();
        fieldHints.put("type", "object");
        fieldHints.put("description",
                "字段映射提示，键为 schema 字段名，值为原文中对应字段的关键词或位置提示。");
        properties.put("field_hints", fieldHints);

        JSONObject confidence = new JSONObject();
        confidence.put("type", "number");
        confidence.put("description", "探测置信度，0~1 之间。");
        properties.put("confidence", confidence);

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        JSONArray required = new JSONArray();
        required.put("detected_format");
        required.put("confidence");
        schema.put("required", required);
        return schema;
    }

    /**
     * 抽取阶段中文系统提示词。
     * <p>
     * 要求模型严格输出 JSON、不编造、保留原文、对齐 schema。
     */
    public static String getExtractionSystemPrompt() {
        return "你是一个题库抽取助手。你的任务是从用户提供的题目文本中抽取题目，"
                + "并严格按照指定 JSON Schema 输出。\n"
                + "要求：\n"
                + "1. 必须输出合法 JSON，不要输出任何解释性文字、Markdown 代码块标记或前后缀。\n"
                + "2. 严格对齐 schema 字段名，不要自创字段，不要遗漏 questions 数组结构。\n"
                + "3. 保留原文，不要翻译、改写、补全或编造题目内容、选项与答案。\n"
                + "4. questionText 与 correctAnswer 为必填；其余字段如原文未给出则留空字符串或 0，不要臆测。\n"
                + "5. extraOptions 仅用于 E/F/G... 等额外选项，仅一层嵌套；A~D 必须放进 optionA~optionD，不要放进 extraOptions。\n"
                + "6. questionType 必须取自枚举：单选、多选、判断、填空、简答。\n"
                + "7. difficulty 必须取自枚举：1、2、3、4、5。\n"
                + "8. tags 多个标签用英文逗号分隔。\n"
                + "9. 一道题输出一个 items 对象，多道题输出多个，保持原顺序。\n"
                // ===== 防幻觉规则(依据 Promptise "Use null when unsure" + additionalProperties:false)=====
                + "10. 如果某个字段在文本中没有明确内容，必须填 null，绝对不要编造或猜测。例如未出现解析则 explanation 填 null。\n"
                + "11. 只输出 schema 定义的字段，不要添加任何额外字段。\n"
                // ===== 单条 confidence(依据 byaiteam:单条 confidence + 低置信度复核)=====
                + "12. 每道题必须给出 confidence(0~1)，表示你对本次抽取的置信度，不确定则给较低值。";
    }

    /**
     * 结构探测阶段中文系统提示词。
     */
    public static String getDetectionSystemPrompt() {
        return "你是一个题库结构探测助手。你的任务是分析用户提供的题目文本，判断其格式与字段分布，"
                + "并按照指定 JSON Schema 输出探测结果。\n"
                + "要求：\n"
                + "1. 必须输出合法 JSON，不要输出任何解释性文字或 Markdown 代码块标记。\n"
                + "2. detected_format 描述文档整体格式与题型分布。\n"
                + "3. question_type_guess 必须取自枚举：单选、多选、判断、填空、简答；如混合则以主要题型为准。\n"
                + "4. field_hints 给出 schema 字段在原文中的关键词或位置线索，帮助后续抽取阶段定位。\n"
                + "5. confidence 给出 0~1 的置信度，越接近 1 表示判断越确定。\n"
                + "6. 不要编造原文中不存在的字段线索。";
    }

    /**
     * 返回字段→别名提示映射，供 UI 展示。
     */
    public static Map<String, String> getAliasMap() {
        Map<String, String> map = new HashMap<>();
        map.put("questionText", "题干/题目/问题/Question");
        map.put("optionA", "选项A/A");
        map.put("optionB", "选项B/B");
        map.put("optionC", "选项C/C");
        map.put("optionD", "选项D/D");
        map.put("extraOptions", "额外选项 E/F/G...");
        map.put("correctAnswer", "答案/正确答案/Answer");
        map.put("questionType", "题型/题目类型");
        map.put("category", "科目/学科/Subject");
        map.put("subCategory", "章节/小节");
        map.put("difficulty", "难度");
        map.put("points", "分值");
        map.put("timeLimit", "时限(秒)");
        map.put("hint", "提示");
        map.put("explanation", "解析/说明");
        map.put("analysis", "深度分析/详解");
        map.put("knowledgePoint", "考点");
        map.put("tags", "关键词");
        map.put("author", "出题人");
        map.put("comment", "备注");
        return map;
    }
}
