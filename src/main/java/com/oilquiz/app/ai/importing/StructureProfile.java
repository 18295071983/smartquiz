package com.oilquiz.app.ai.importing;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库文件结构识别结果数据类。
 * <p>
 * 承载 LLM 对题库文件的结构分析输出：检测到的格式、出现的题型、字段分布、字段定位提示等。
 * 供 {@link DynamicSchemaBuilder} 据此动态裁剪抽取 Schema 与提示词，使抽取更精准、避免编造字段。
 * <p>
 * 纯数据类，无 Android 依赖，可序列化。
 */
public class StructureProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 检测到的格式，如 "单选多选混排"、"纯文本问答"、"Markdown 列表" */
    public String detectedFormat;

    /** 文件里实际出现的题型，如 ["单选","判断"] */
    public List<String> detectedQuestionTypes;

    /** 字段标识提示，如 {"questionText":"第N题/题目","correctAnswer":"答案"} */
    public Map<String, String> fieldHints;

    /** 文件里真有的字段名（数据库字段名） */
    public List<String> presentFields;

    /** 置信度 0~1 */
    public float confidence;

    /** LLM 原始分析文本（供 thinking 展示，不一定来自结构化 JSON） */
    public String rawAnalysis;

    public StructureProfile() {
        this.detectedFormat = "";
        this.detectedQuestionTypes = new ArrayList<>();
        this.fieldHints = new HashMap<>();
        this.presentFields = new ArrayList<>();
        this.confidence = 0f;
        this.rawAnalysis = "";
    }

    public String getDetectedFormat() {
        return detectedFormat;
    }

    public void setDetectedFormat(String detectedFormat) {
        this.detectedFormat = detectedFormat;
    }

    public List<String> getDetectedQuestionTypes() {
        return detectedQuestionTypes;
    }

    public void setDetectedQuestionTypes(List<String> detectedQuestionTypes) {
        this.detectedQuestionTypes = detectedQuestionTypes;
    }

    public Map<String, String> getFieldHints() {
        return fieldHints;
    }

    public void setFieldHints(Map<String, String> fieldHints) {
        this.fieldHints = fieldHints;
    }

    public List<String> getPresentFields() {
        return presentFields;
    }

    public void setPresentFields(List<String> presentFields) {
        this.presentFields = presentFields;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public String getRawAnalysis() {
        return rawAnalysis;
    }

    public void setRawAnalysis(String rawAnalysis) {
        this.rawAnalysis = rawAnalysis;
    }
}
