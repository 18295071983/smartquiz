package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ExportUtils {

    private static List<String> cachedFields = null;

    /**
     * 从数据库模型获取Question的所有字段名称
     * @return 字段名称列表
     */
    public static List<String> getQuestionFields() {
        if (cachedFields == null) {
            cachedFields = loadQuestionFieldsFromDatabaseModel();
        }
        return new ArrayList<>(cachedFields);
    }

    /**
     * 从数据库模型加载字段
     * @return 字段名称列表
     */
    private static List<String> loadQuestionFieldsFromDatabaseModel() {
        List<String> fields = new ArrayList<>();
        
        // 从Question实体类获取字段，这些字段对应数据库表结构
        Field[] declaredFields = Question.class.getDeclaredFields();
        for (Field field : declaredFields) {
            // 跳过静态字段
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                fields.add(field.getName());
            }
        }
        
        return fields;
    }

    /**
     * 刷新字段列表（当数据库结构变化时调用）
     */
    public static void refreshQuestionFields() {
        cachedFields = loadQuestionFieldsFromDatabaseModel();
    }

    /**
     * 获取字段的显示名称
     * @param fieldName 字段名称
     * @return 显示名称
     */
    public static String getFieldDisplayName(String fieldName) {
        // 可以在这里添加字段的中文显示名称
        switch (fieldName) {
            case "id":
                return "ID";
            case "questionText":
                return "题目内容";
            case "optionA":
                return "选项A";
            case "optionB":
                return "选项B";
            case "optionC":
                return "选项C";
            case "optionD":
                return "选项D";
            case "correctAnswer":
                return "正确答案";
            case "category":
                return "分类";
            case "difficulty":
                return "难度";
            case "explanation":
                return "解析";
            case "relatedQuestion":
                return "相关题目";
            case "questionType":
                return "题目类型";
            case "favorite":
                return "收藏";
            default:
                return fieldName;
        }
    }

    /**
     * 获取字段值
     * @param question Question对象
     * @param fieldName 字段名称
     * @return 字段值
     */
    public static Object getFieldValue(Question question, String fieldName) {
        try {
            Field field = Question.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(question);
            
            // 处理原始类型的默认值
            if (field.getType() == int.class || field.getType() == Integer.class) {
                int intValue = (Integer) value;
                // 对于统计类字段，如果值为0，可能是默认值，返回null
                if (intValue == 0 && !isRequiredNumericField(fieldName)) {
                    return null;
                }
            } else if (field.getType() == long.class || field.getType() == Long.class) {
                long longValue = (Long) value;
                // 对于时间戳字段，如果值为0，可能是默认值，返回null
                if (longValue == 0 && !isRequiredNumericField(fieldName)) {
                    return null;
                }
            } else if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                boolean boolValue = (Boolean) value;
                // 对于收藏字段，如果值为false，可能是默认值，返回null
                if (!boolValue && fieldName.equals("favorite")) {
                    return null;
                }
            }
            
            return value;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 判断是否是必需的数字字段
     * @param fieldName 字段名称
     * @return 是否是必需的数字字段
     */
    private static boolean isRequiredNumericField(String fieldName) {
        // 这些字段即使值为0也应该保留
        switch (fieldName) {
            case "id":
            case "difficulty":
            case "points":
            case "timeLimit":
            case "status":
            case "isPublic":
                return true;
            default:
                return false;
        }
    }

    /**
     * 获取字段类型
     * @param fieldName 字段名称
     * @return 字段类型
     */
    public static Class<?> getFieldType(String fieldName) {
        try {
            Field field = Question.class.getDeclaredField(fieldName);
            return field.getType();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
