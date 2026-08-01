package com.oilquiz.app.ai.importing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 动态抽取 Schema 构建器。
 * <p>
 * 根据 {@link StructureProfile} 结构识别结果，动态裁剪
 * {@link QuestionSchemaDictionary#getExtractionSchema()} 产出的固定 Schema：
 * 只保留文件真实存在的字段、按检测到的题型定制 questionType 枚举，
 * 并生成对应的抽取提示词，从而提升 LLM 抽取精准度、避免编造 hint/tags 等字段。
 * <p>
 * 同时提供结构识别阶段的提示词与输出 Schema，以及将 LLM 输出 JSON 解析为
 * {@link StructureProfile} 的工具方法。
 * <p>
 * 纯工具类，无 Android 依赖，无状态，全部静态方法。
 */
public class DynamicSchemaBuilder {

    /** v20 全字段清单，presentFields 只能从中选择 */
    private static final List<String> ALL_FIELDS = Arrays.asList(
            "questionText", "optionA", "optionB", "optionC", "optionD", "extraOptions",
            "correctAnswer", "questionType", "category", "subCategory", "difficulty",
            "points", "timeLimit", "hint", "explanation", "analysis", "knowledgePoint",
            "tags", "author", "comment"
    );

    /** 题型全量枚举（与 QuestionSchemaDictionary 对齐） */
    private static final String[] ALL_QUESTION_TYPES = {"单选", "多选", "判断", "填空", "简答"};

    private DynamicSchemaBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 根据结构识别结果，构建动态抽取 Schema（只含文件真有字段）。
     * <p>
     * 以 {@link QuestionSchemaDictionary#getExtractionSchema()} 为基底：
     * <ul>
     *   <li>字段裁剪：items.properties 只保留 presentFields 里的字段 + 必填的 questionText/correctAnswer；
     *       presentFields 为空时全量保留（容错）。</li>
     *   <li>questionType enum 定制：detectedQuestionTypes 非空时只含这些题型；否则保持全量。</li>
     *   <li>required 只保留 questionText、correctAnswer（若 present 含这两个）。</li>
     * </ul>
     * 顶层结构与 getExtractionSchema 一致。
     *
     * @param profile 结构识别结果，可为 null（按全量回退）
     * @return 动态抽取 Schema
     */
    public static JSONObject buildExtractionSchema(StructureProfile profile) throws JSONException {
        // 以固定 schema 为基底
        JSONObject base = QuestionSchemaDictionary.getExtractionSchema();

        // 定位 items.properties：schema.properties.questions.items.properties
        JSONObject topProps = base.getJSONObject("properties");
        JSONObject questions = topProps.getJSONObject("questions");
        JSONObject item = questions.getJSONObject("items");
        JSONObject itemProps = item.getJSONObject("properties");

        // 决定是否裁剪字段
        boolean cropFields = profile != null
                && profile.presentFields != null
                && !profile.presentFields.isEmpty();

        if (cropFields) {
            // 只保留 presentFields 里的字段 + 必填的 questionText/correctAnswer
            JSONObject cropped = new JSONObject();
            for (String field : profile.presentFields) {
                if (itemProps.has(field)) {
                    cropped.put(field, itemProps.get(field));
                }
            }
            // 强制保留必填字段（即使 presentFields 漏列）
            if (!cropped.has("questionText") && itemProps.has("questionText")) {
                cropped.put("questionText", itemProps.get("questionText"));
            }
            if (!cropped.has("correctAnswer") && itemProps.has("correctAnswer")) {
                cropped.put("correctAnswer", itemProps.get("correctAnswer"));
            }
            // 强制保留 confidence(门控用,即使 presentFields 漏列;依据 byaiteam)
            if (!cropped.has("confidence") && itemProps.has("confidence")) {
                cropped.put("confidence", itemProps.get("confidence"));
            }
            item.put("properties", cropped);
        }

        // questionType enum 定制（仅当 questionType 仍在 properties 中）
        JSONObject curProps = item.getJSONObject("properties");
        if (profile != null
                && profile.detectedQuestionTypes != null
                && !profile.detectedQuestionTypes.isEmpty()
                && curProps.has("questionType")) {
            JSONObject qt = curProps.getJSONObject("questionType");
            JSONArray enumArr = new JSONArray();
            for (String t : profile.detectedQuestionTypes) {
                enumArr.put(t);
            }
            qt.put("enum", enumArr);
        }

        // required 只保留 questionText、correctAnswer（若 present 含这两个）
        JSONArray requiredItem = new JSONArray();
        boolean keepQuestionText = !cropFields || profile.presentFields.contains("questionText");
        boolean keepCorrectAnswer = !cropFields || profile.presentFields.contains("correctAnswer");
        if (keepQuestionText) {
            requiredItem.put("questionText");
        }
        if (keepCorrectAnswer) {
            requiredItem.put("correctAnswer");
        }
        // confidence 必填(门控用,依据 byaiteam:单条 confidence)
        requiredItem.put("confidence");
        item.put("required", requiredItem);

        return base;
    }

    /**
     * 动态抽取系统提示词（告诉 LLM 只抽哪些字段、题型 enum 定制）。
     * <p>
     * 基于 {@link QuestionSchemaDictionary#getExtractionSystemPrompt()} 的核心要求，
     * 追加本次文件检测到的题型、含有的字段、字段定位提示等动态约束。
     * 若 profile 为 null 或 presentFields 为空，回退到固定 prompt。
     *
     * @param profile 结构识别结果，可为 null
     * @return 动态抽取系统提示词
     */
    public static String buildExtractionPrompt(StructureProfile profile) {
        // 回退：profile 为 null 或 presentFields 空 → 固定 prompt
        if (profile == null
                || profile.presentFields == null
                || profile.presentFields.isEmpty()) {
            return QuestionSchemaDictionary.getExtractionSystemPrompt();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(QuestionSchemaDictionary.getExtractionSystemPrompt());
        sb.append("\n\n【本次动态约束】\n");

        // 题型约束
        if (profile.detectedQuestionTypes != null && !profile.detectedQuestionTypes.isEmpty()) {
            sb.append("- 本次文件检测到的题型：")
                    .append(String.join("、", profile.detectedQuestionTypes))
                    .append("；questionType 字段只能取这些值。\n");
        }

        // 字段约束
        sb.append("- 本次文件含有的字段：")
                .append(String.join("、", profile.presentFields))
                .append("；只抽取这些字段，未提及的字段（如 hint/tags/analysis）若文本中无明确内容则填 null，不要编造。\n");

        // 字段定位提示
        if (profile.fieldHints != null && !profile.fieldHints.isEmpty()) {
            sb.append("- 字段识别提示（帮助定位）：\n");
            for (Map.Entry<String, String> e : profile.fieldHints.entrySet()) {
                sb.append("  · ").append(e.getKey()).append(" → ").append(e.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 结构识别系统提示词（用于让 LLM 输出 StructureProfile 对应 JSON）。
     * <p>
     * 要求 LLM 分析样本，输出 JSON 含 detectedFormat/detectedQuestionTypes/fieldHints/
     * presentFields/confidence；明确告知 presentFields 只能从给定字段清单选择。
     *
     * @return 结构识别系统提示词
     */
    public static String buildDiscoveryPrompt() {
        return "你是一个题库结构识别助手。你的任务是分析用户提供的题目文本样本，"
                + "判断其文档格式、出现的题型、字段分布与字段定位线索，"
                + "并严格按照指定 JSON Schema 输出。\n"
                + "要求：\n"
                + "1. 必须输出合法 JSON，不要输出任何解释性文字或 Markdown 代码块标记。\n"
                + "2. detectedFormat：描述文档整体格式与题型分布，如 \"单选多选混排\"、\"纯文本问答\"、\"Markdown 列表\"。\n"
                + "3. detectedQuestionTypes：文件里实际出现的题型，取自枚举 单选/多选/判断/填空/简答；可多个。\n"
                + "4. fieldHints：字段映射提示，键为 schema 字段名，值为原文中对应字段的关键词或位置线索，"
                + "帮助后续抽取阶段定位。\n"
                + "5. presentFields：文件里真实存在的字段名，只能从以下清单中选择：\n"
                + "   " + String.join(", ", ALL_FIELDS) + "\n"
                + "6. confidence：0~1 的置信度，越接近 1 表示判断越确定。\n"
                + "7. 不要编造原文中不存在的字段或题型。";
    }

    /**
     * 结构识别输出 Schema（约束 LLM 输出 StructureProfile JSON）。
     * <p>
     * properties 含 detectedFormat(string)/detectedQuestionTypes(array of string)/
     * fieldHints(object)/presentFields(array of string)/confidence(number 0~1)。
     *
     * @return 结构识别输出 Schema
     */
    public static JSONObject buildDiscoverySchema() throws JSONException {
        JSONObject properties = new JSONObject();

        // detectedFormat
        JSONObject detectedFormat = new JSONObject();
        detectedFormat.put("type", "string");
        detectedFormat.put("description", "检测到的文档格式，如 \"单选多选混排\"、\"纯文本问答\"、\"Markdown 列表\"。");
        properties.put("detectedFormat", detectedFormat);

        // detectedQuestionTypes：array of string，枚举约束题型
        JSONObject detectedQuestionTypes = new JSONObject();
        detectedQuestionTypes.put("type", "array");
        detectedQuestionTypes.put("description", "文件里实际出现的题型列表。");
        JSONObject qtItems = new JSONObject();
        qtItems.put("type", "string");
        JSONArray qtEnum = new JSONArray();
        for (String t : ALL_QUESTION_TYPES) {
            qtEnum.put(t);
        }
        qtItems.put("enum", qtEnum);
        detectedQuestionTypes.put("items", qtItems);
        properties.put("detectedQuestionTypes", detectedQuestionTypes);

        // fieldHints
        JSONObject fieldHints = new JSONObject();
        fieldHints.put("type", "object");
        fieldHints.put("description",
                "字段映射提示，键为 schema 字段名，值为原文中对应字段的关键词或位置线索。");
        properties.put("fieldHints", fieldHints);

        // presentFields：array of string，枚举约束字段名
        JSONObject presentFields = new JSONObject();
        presentFields.put("type", "array");
        presentFields.put("description", "文件里真实存在的字段名，只能从全字段清单中选择。");
        JSONObject pfItems = new JSONObject();
        pfItems.put("type", "string");
        JSONArray pfEnum = new JSONArray();
        for (String f : ALL_FIELDS) {
            pfEnum.put(f);
        }
        pfItems.put("enum", pfEnum);
        presentFields.put("items", pfItems);
        properties.put("presentFields", presentFields);

        // confidence
        JSONObject confidence = new JSONObject();
        confidence.put("type", "number");
        confidence.put("description", "置信度，0~1 之间。");
        properties.put("confidence", confidence);

        // 顶层
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        // 防幻觉:禁止额外字段(依据 Promptise "additionalProperties:false")
        schema.put("additionalProperties", false);
        JSONArray required = new JSONArray();
        required.put("detectedFormat");
        required.put("confidence");
        schema.put("required", required);
        return schema;
    }

    /**
     * 把 LLM 输出的 JSON 解析成 {@link StructureProfile}。
     * <p>
     * 全程容错（optString/optJSONArray 等），缺失字段给默认值；
     * detectedQuestionTypes 默认空 list，confidence 默认 0。
     *
     * @param jo LLM 输出的 JSON 对象，可为 null
     * @return 解析得到的 StructureProfile（永不为 null）
     */
    public static StructureProfile parseProfile(JSONObject jo) {
        StructureProfile profile = new StructureProfile();
        if (jo == null) {
            return profile;
        }

        // detectedFormat
        profile.detectedFormat = jo.optString("detectedFormat", "");

        // detectedQuestionTypes（默认空 list）
        List<String> types = new ArrayList<>();
        JSONArray typesArr = jo.optJSONArray("detectedQuestionTypes");
        if (typesArr != null) {
            for (int i = 0; i < typesArr.length(); i++) {
                String t = typesArr.optString(i, null);
                if (t != null && !t.isEmpty()) {
                    types.add(t);
                }
            }
        }
        profile.detectedQuestionTypes = types;

        // fieldHints
        java.util.Map<String, String> hints = new java.util.HashMap<>();
        JSONObject hintsObj = jo.optJSONObject("fieldHints");
        if (hintsObj != null) {
            JSONArray keys = hintsObj.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.optString(i, null);
                    if (key == null) {
                        continue;
                    }
                    String val = hintsObj.optString(key, "");
                    hints.put(key, val);
                }
            }
        }
        profile.fieldHints = hints;

        // presentFields
        List<String> fields = new ArrayList<>();
        JSONArray fieldsArr = jo.optJSONArray("presentFields");
        if (fieldsArr != null) {
            for (int i = 0; i < fieldsArr.length(); i++) {
                String f = fieldsArr.optString(i, null);
                if (f != null && !f.isEmpty()) {
                    fields.add(f);
                }
            }
        }
        profile.presentFields = fields;

        // confidence（默认 0，NaN/Infinite 容错为 0）
        double conf = jo.optDouble("confidence", 0.0);
        if (Double.isNaN(conf) || Double.isInfinite(conf)) {
            conf = 0.0;
        }
        profile.confidence = (float) conf;

        // rawAnalysis：JSON 里不一定有，容错读取
        profile.rawAnalysis = jo.optString("rawAnalysis", "");

        return profile;
    }
}
