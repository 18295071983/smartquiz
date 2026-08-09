package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 字段映射器
 * 负责将模板字段映射到问题对象的属性
 */
public class FieldMapper {
    private static final Map<String, String> FIELD_METHOD_MAP = new HashMap<>();
    
    static {
        // 初始化字段到方法的映射
        FIELD_METHOD_MAP.put("questionText", "getQuestionText");
        FIELD_METHOD_MAP.put("optionA", "getOptionA");
        FIELD_METHOD_MAP.put("optionB", "getOptionB");
        FIELD_METHOD_MAP.put("optionC", "getOptionC");
        FIELD_METHOD_MAP.put("optionD", "getOptionD");
        FIELD_METHOD_MAP.put("optionE", "getOptionE");
        FIELD_METHOD_MAP.put("optionF", "getOptionF");
        FIELD_METHOD_MAP.put("optionG", "getOptionG");
        FIELD_METHOD_MAP.put("optionH", "getOptionH");
        FIELD_METHOD_MAP.put("optionI", "getOptionI");
        FIELD_METHOD_MAP.put("optionJ", "getOptionJ");
        FIELD_METHOD_MAP.put("optionK", "getOptionK");
        FIELD_METHOD_MAP.put("optionL", "getOptionL");
        FIELD_METHOD_MAP.put("correctAnswer", "getCorrectAnswer");
        FIELD_METHOD_MAP.put("answerText", "getAnswerText");
        FIELD_METHOD_MAP.put("explanation", "getExplanation");
        FIELD_METHOD_MAP.put("analysis", "getAnalysis");
        FIELD_METHOD_MAP.put("knowledgePoint", "getKnowledgePoint");
        FIELD_METHOD_MAP.put("questionType", "getQuestionType");
        FIELD_METHOD_MAP.put("difficulty", "getDifficulty");
        FIELD_METHOD_MAP.put("category", "getCategory");
        FIELD_METHOD_MAP.put("subCategory", "getSubCategory");
        FIELD_METHOD_MAP.put("tags", "getTags");
        FIELD_METHOD_MAP.put("hint", "getHint");
        FIELD_METHOD_MAP.put("source", "getSource");
        FIELD_METHOD_MAP.put("id", "getId");
        FIELD_METHOD_MAP.put("relatedQuestion", "getRelatedQuestion");
        FIELD_METHOD_MAP.put("favorite", "isFavorite");
        FIELD_METHOD_MAP.put("points", "getPoints");
        FIELD_METHOD_MAP.put("timeLimit", "getTimeLimit");
        FIELD_METHOD_MAP.put("status", "getStatus");
        FIELD_METHOD_MAP.put("author", "getAuthor");
        FIELD_METHOD_MAP.put("comment", "getComment");
        FIELD_METHOD_MAP.put("usageCount", "getUsageCount");
        FIELD_METHOD_MAP.put("correctCount", "getCorrectCount");
        FIELD_METHOD_MAP.put("incorrectCount", "getIncorrectCount");
    }

    /**
     * 获取字段值
     */
    public static Object getFieldValue(Question question, String fieldName) {
        if (question == null || fieldName == null) {
            return null;
        }
        
        try {
            String methodName = FIELD_METHOD_MAP.get(fieldName);
            if (methodName == null) {
                // 尝试直接使用字段名作为方法名
                methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
            }
            
            Method method = Question.class.getMethod(methodName);
            return method.invoke(question);
        } catch (Exception e) {
            // 如果方法不存在，返回null
            return null;
        }
    }

    /**
     * 获取字段显示名称
     */
    public static String getFieldDisplayName(Template template, String fieldName) {
        if (template != null && template.getFieldMappings() != null) {
            String displayName = template.getFieldMappings().get(fieldName);
            if (displayName != null) {
                return displayName;
            }
        }
        // 默认显示名称
        return fieldName;
    }

    /**
     * 检查字段是否有效
     */
    public static boolean isValidField(String fieldName) {
        return FIELD_METHOD_MAP.containsKey(fieldName);
    }

    /**
     * 获取所有可用字段（有序展示用，Key 为字段名）
     */
    public static Map<String, String> getAllAvailableFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("id", "序号");
        fields.put("questionType", "题型");
        fields.put("questionText", "题目内容");
        fields.put("optionA", "选项A");
        fields.put("optionB", "选项B");
        fields.put("optionC", "选项C");
        fields.put("optionD", "选项D");
        fields.put("optionE", "选项E");
        fields.put("optionF", "选项F");
        fields.put("optionG", "选项G");
        fields.put("optionH", "选项H");
        fields.put("optionI", "选项I");
        fields.put("optionJ", "选项J");
        fields.put("optionK", "选项K");
        fields.put("optionL", "选项L");
        fields.put("correctAnswer", "正确答案");
        fields.put("answerText", "答案文本");
        fields.put("explanation", "解析");
        fields.put("analysis", "详细解析");
        fields.put("knowledgePoint", "知识点");
        fields.put("category", "分类");
        fields.put("subCategory", "子分类");
        fields.put("difficulty", "难度");
        fields.put("tags", "标签");
        fields.put("hint", "提示");
        fields.put("relatedQuestion", "相关题目");
        fields.put("source", "来源");
        fields.put("favorite", "收藏");
        fields.put("points", "分值");
        fields.put("timeLimit", "时限(秒)");
        return fields;
    }
}
