package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ExportUtils {

    /**
     * 选项字段（A~L），供各导出器循环渲染
     */
    public static final String[] OPTION_FIELDS = {
            "optionA", "optionB", "optionC", "optionD", "optionE", "optionF",
            "optionG", "optionH", "optionI", "optionJ", "optionK", "optionL"
    };

    /** 选项字母标签（A~L） */
    public static final String[] OPTION_LABELS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"
    };

    /**
     * 获取导出任务实际生效的字段列表：优先模板选中的字段，否则使用全部核心字段
     */
    public static List<String> getEffectiveFields(ExportManager.ExportConfig config) {
        if (config != null && config.getSelectedFields() != null && !config.getSelectedFields().isEmpty()) {
            return new ArrayList<>(config.getSelectedFields());
        }
        return getQuestionFields();
    }

    /**
     * 判断某字段是否在生效字段列表中（大小写不敏感）
     */
    public static boolean hasField(List<String> fields, String fieldName) {
        if (fields == null || fieldName == null) return false;
        return fields.contains(fieldName);
    }

    /**
     * 判断任务配置是否包含某字段（模板字段优先，其次默认全字段）
     */
    public static boolean hasField(ExportManager.ExportConfig config, String fieldName) {
        return hasField(getEffectiveFields(config), fieldName);
    }

    /**
     * 循环取选项值：返回第 index 个选项字段名对应的值（index 从 0 起）
     */
    public static Object getOptionValue(Question question, int index) {
        if (question == null || index < 0 || index >= OPTION_FIELDS.length) return null;
        return getFieldValue(question, OPTION_FIELDS[index]);
    }

    /**
     * 转义 HTML 特殊字符，防止题目内容破坏页面结构与 XSS
     */
    public static String escapeHtml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    /**
     * 可导出字段（有序，唯一来源）：
     * 排除对题库文件无意义的技术字段（imageUri/audioUri/parentId/sortOrder/
     * createdAt/updatedAt/lastUsedAt/status/isPublic 等），避免导出空列与列表冗长。
     */
    public static final String[] EXPORTABLE_FIELDS = {
            "id", "questionType", "questionText",
            "optionA", "optionB", "optionC", "optionD", "optionE", "optionF",
            "optionG", "optionH", "optionI", "optionJ", "optionK", "optionL",
            "correctAnswer", "answerText", "explanation", "analysis",
            "difficulty", "difficultyText", "category", "subCategory", "knowledgePoint",
            "tags", "hint", "source", "author", "comment", "relatedQuestion",
            "points", "timeLimit", "favorite", "usageCount", "correctCount", "incorrectCount"
    };

    /**
     * 获取可导出字段列表（默认全字段）
     */
    public static List<String> getQuestionFields() {
        return new ArrayList<>(java.util.Arrays.asList(EXPORTABLE_FIELDS));
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
                return "序号";
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
            case "optionE":
                return "选项E";
            case "optionF":
                return "选项F";
            case "optionG":
                return "选项G";
            case "optionH":
                return "选项H";
            case "optionI":
                return "选项I";
            case "optionJ":
                return "选项J";
            case "optionK":
                return "选项K";
            case "optionL":
                return "选项L";
            case "correctAnswer":
                return "正确答案";
            case "answerText":
                return "答案文本";
            case "category":
                return "分类";
            case "subCategory":
                return "子分类";
            case "difficulty":
                return "难度";
            case "explanation":
                return "解析";
            case "analysis":
                return "详细解析";
            case "knowledgePoint":
                return "知识点";
            case "relatedQuestion":
                return "相关题目";
            case "questionType":
                return "题型";
            case "favorite":
                return "收藏";
            case "tags":
                return "标签";
            case "hint":
                return "提示";
            case "source":
                return "来源";
            case "points":
                return "分值";
            case "timeLimit":
                return "时限(秒)";
            case "usageCount":
                return "使用次数";
            case "correctCount":
                return "答对次数";
            case "incorrectCount":
                return "答错次数";
            case "lastUsedAt":
                return "最后使用时间";
            case "status":
                return "状态";
            case "isPublic":
                return "是否公开";
            case "author":
                return "作者";
            case "comment":
                return "备注";
            case "createdAt":
                return "创建时间";
            case "updatedAt":
                return "更新时间";
            case "lastAnsweredTime":
                return "最后作答时间";
            case "lastAnsweredScore":
                return "最后作答得分";
            case "imageUri":
                return "配图路径";
            case "audioUri":
                return "音频路径";
            case "parentId":
                return "母题ID";
            case "sortOrder":
                return "排序";
            default:
                return fieldName;
        }
    }

    /**
     * 获取格式化后的字段值（用于导出）：难度转文字、收藏转是/否，其余与 getFieldValue 一致
     * @param question Question对象
     * @param fieldName 字段名称
     * @return 格式化后的值
     */
    public static Object getFormattedFieldValue(Question question, String fieldName) {
        if (fieldName == null || question == null) {
            return null;
        }
        if ("difficulty".equals(fieldName)) {
            return question.getDifficultyText();
        }
        if ("favorite".equals(fieldName)) {
            return question.isFavorite() ? "是" : "否";
        }
        if ("status".equals(fieldName)) {
            return question.getStatusText();
        }
        if ("createdAt".equals(fieldName) || "updatedAt".equals(fieldName) || "lastUsedAt".equals(fieldName)) {
            long ts = 0;
            if ("createdAt".equals(fieldName)) ts = question.getCreatedAt();
            else if ("updatedAt".equals(fieldName)) ts = question.getUpdatedAt();
            else ts = question.getLastUsedAt();
            if (ts <= 0) return null;
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(ts));
        }
        return getFieldValue(question, fieldName);
    }

    /**
     * 获取字段值（通过直接调用 getter 方法）
     * @param question Question对象
     * @param fieldName 字段名称
     * @return 字段值
     */
    public static Object getFieldValue(Question question, String fieldName) {
        if (question == null || fieldName == null) return null;
        
        try {
            // 通过 getter 方法名调用获取字段值
            String getterName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
            java.lang.reflect.Method method = Question.class.getMethod(getterName);
            Object value = method.invoke(question);
            
            // 处理原始类型的默认值
            if (value instanceof Integer || value instanceof int[]) {
                int intValue = (Integer) value;
                if (intValue == 0 && !isRequiredNumericField(fieldName)) {
                    return null;
                }
            } else if (value instanceof Long || value instanceof long[]) {
                long longValue = (Long) value;
                if (longValue == 0 && !isRequiredNumericField(fieldName)) {
                    return null;
                }
            } else if (value instanceof Boolean || value instanceof boolean[]) {
                boolean boolValue = (Boolean) value;
                if (!boolValue && fieldName.equals("favorite")) {
                    return null;
                }
            }
            
            return value;
        } catch (NoSuchMethodException e) {
            // getter 方法不存在
            return null;
        } catch (Exception e) {
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
