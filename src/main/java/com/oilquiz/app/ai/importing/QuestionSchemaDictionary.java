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

    /** 题目类型枚举值 */
    private static final String[] QUESTION_TYPES = {"单选", "多选", "判断", "填空", "简答"};

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
     * 极简抽取系统提示词（v3 NoThinking：零推理链，直接输出）。
     * <p>
     * 核心原则：零思考、零解释、零编造。
     * 基于 NoThinking 方法论（arXiv:2504.09858）：跳过显式思考步骤反而更准更快。
     */
    public static String getMinimalExtractionPrompt() {
        return "你是JSON提取器。从文本中提取题目，直接输出JSON，禁止思考过程。\n"
                + "规则：\n"
                + "1. 只输出{\"questions\":[...]}，禁止任何其他文字\n"
                + "2. 保留原文，不翻译不改写不编造\n"
                + "3. questionType取：单选/多选/判断/填空/简答\n"
                + "4. 原文无内容则填空字符串\"\"\n"
                + "5. 没有题目则输出{\"questions\":[]}";
    }
}