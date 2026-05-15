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
        FIELD_METHOD_MAP.put("correctAnswer", "getCorrectAnswer");
        FIELD_METHOD_MAP.put("explanation", "getExplanation");
        FIELD_METHOD_MAP.put("questionType", "getQuestionType");
        FIELD_METHOD_MAP.put("difficulty", "getDifficulty");
        FIELD_METHOD_MAP.put("category", "getCategory");
        FIELD_METHOD_MAP.put("id", "getId");
        FIELD_METHOD_MAP.put("relatedQuestion", "getRelatedQuestion");
        FIELD_METHOD_MAP.put("favorite", "isFavorite");
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
     * 获取所有可用字段
     */
    public static Map<String, String> getAllAvailableFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("id", "ID");
        fields.put("questionType", "题目类型");
        fields.put("questionText", "题目内容");
        fields.put("optionA", "选项A");
        fields.put("optionB", "选项B");
        fields.put("optionC", "选项C");
        fields.put("optionD", "选项D");
        fields.put("correctAnswer", "答案");
        fields.put("explanation", "解析");
        fields.put("category", "分类");
        fields.put("difficulty", "难度");
        fields.put("relatedQuestion", "相关题目");
        fields.put("favorite", "收藏");
        return fields;
    }
}
