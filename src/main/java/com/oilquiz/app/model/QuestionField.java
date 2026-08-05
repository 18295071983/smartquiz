package com.oilquiz.app.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 题目字段注册表
 * 集中定义所有可导入/导出的题目字段，统一管理显示名、模型字段名、分类和别名。
 * 被 MappingEditorActivity、ExcelUtil、ExportUtils 共用，消除散落在各处的硬编码。
 */
public enum QuestionField {

    // ========== 基础信息 ==========
    QUESTION_TEXT("题目", "questionText", "基础信息", "question", "title", "content", "问题", "题干"),
    QUESTION_TYPE("题型", "questionType", "基础信息", "type", "questiontype", "question_type", "类型"),
    DIFFICULTY("难度", "difficulty", "基础信息", "difficulty", "level", "难度等级"),
    POINTS("分值", "points", "基础信息", "score", "point", "分数", "配分"),
    TIME_LIMIT("答题时限", "timeLimit", "基础信息", "timelimit", "time_limit", "时限", "时间限制"),

    // ========== 选项 ==========
    OPTION_A("选项A", "optionA", "选项", "a", "optiona", "option_a", "选项甲"),
    OPTION_B("选项B", "optionB", "选项", "b", "optionb", "option_b", "选项乙"),
    OPTION_C("选项C", "optionC", "选项", "c", "optionc", "option_c", "选项丙"),
    OPTION_D("选项D", "optionD", "选项", "d", "optiond", "option_d", "选项丁"),
    OPTION_E("选项E", "extraOptions", "选项", "e", "optione", "option_e", "选项戊"),
    OPTION_F("选项F", "extraOptions", "选项", "f", "optionf", "option_f", "选项己"),
    OPTION_G("选项G", "extraOptions", "选项", "g", "optiong", "option_g", "选项庚"),
    OPTION_H("选项H", "extraOptions", "选项", "h", "optionh", "option_h", "选项辛"),
    OPTION_I("选项I", "extraOptions", "选项", "i", "optioni", "option_i", "选项壬"),
    OPTION_J("选项J", "extraOptions", "选项", "j", "optionj", "option_j", "选项癸"),
    OPTION_K("选项K", "extraOptions", "选项", "k", "optionk", "option_k", "选项子"),
    OPTION_L("选项L", "extraOptions", "选项", "l", "optionl", "option_l", "选项丑"),

    // ========== 答案与解析 ==========
    CORRECT_ANSWER("正确答案", "correctAnswer", "答案与解析", "answer", "correct", "答案", "标准答案", "参考答案", "正确选项"),
    BLANK_ANSWER_1("空1答案", "blankAnswer1", "答案与解析", "空1", "填空1", "答案1", "第一空", "空一答案", "空一"),
    BLANK_ANSWER_2("空2答案", "blankAnswer2", "答案与解析", "空2", "填空2", "答案2", "第二空", "空二答案", "空二"),
    BLANK_ANSWER_3("空3答案", "blankAnswer3", "答案与解析", "空3", "填空3", "答案3", "第三空", "空三答案", "空三"),
    BLANK_ANSWER_4("空4答案", "blankAnswer4", "答案与解析", "空4", "填空4", "答案4", "第四空", "空四答案", "空四"),
    BLANK_ANSWER_5("空5答案", "blankAnswer5", "答案与解析", "空5", "填空5", "答案5", "第五空", "空五答案", "空五"),
    BLANK_ANSWER_6("空6答案", "blankAnswer6", "答案与解析", "空6", "填空6", "答案6", "第六空", "空六答案", "空六"),
    BLANK_ANSWER_7("空7答案", "blankAnswer7", "答案与解析", "空7", "填空7", "答案7", "第七空", "空七答案", "空七"),
    BLANK_ANSWER_8("空8答案", "blankAnswer8", "答案与解析", "空8", "填空8", "答案8", "第八空", "空八答案", "空八"),
    BLANK_ANSWER_9("空9答案", "blankAnswer9", "答案与解析", "空9", "填空9", "答案9", "第九空", "空九答案", "空九"),
    BLANK_ANSWER_10("空10答案", "blankAnswer10", "答案与解析", "空10", "填空10", "答案10", "第十空", "空十答案", "空十"),
    BLANK_ANSWER_11("空11答案", "blankAnswer11", "答案与解析", "空11", "填空11", "答案11", "第十一空", "空十一答案", "空十一"),
    BLANK_ANSWER_12("空12答案", "blankAnswer12", "答案与解析", "空12", "填空12", "答案12", "第十二空", "空十二答案", "空十二"),
    EXPLANATION("解析", "explanation", "答案与解析", "explanation", "答案解析"),
    ANALYSIS("详细解析", "analysis", "答案与解析", "analysis", "detailed_explanation"),
    HINT("提示", "hint", "答案与解析", "hint", "tip", "clue"),

    // ========== 分类与标签 ==========
    CATEGORY("分类", "category", "分类与标签", "category", "科目", "class"),
    SUB_CATEGORY("子分类", "subCategory", "分类与标签", "subcategory", "sub_category", "子类"),
    KNOWLEDGE_POINT("知识点", "knowledgePoint", "分类与标签", "knowledgepoint", "knowledge_point", "knowledge"),
    TAGS("标签", "tags", "分类与标签", "tag", "label"),
    RELATED_QUESTION("相关题目", "relatedQuestion", "分类与标签", "relatedquestion", "related_question", "related", "关联"),

    // ========== 元数据 ==========
    AUTHOR("作者", "author", "元数据", "author", "creator", "出题人"),
    SOURCE("来源", "source", "元数据", "source", "origin"),
    COMMENT("备注", "comment", "元数据", "comment", "note", "remark");

    private final String displayName;
    private final String modelFieldName;
    private final String category;
    private final String[] aliases;

    QuestionField(String displayName, String modelFieldName, String category, String... aliases) {
        this.displayName = displayName;
        this.modelFieldName = modelFieldName;
        this.category = category;
        this.aliases = aliases;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getModelFieldName() {
        return modelFieldName;
    }

    public String getCategory() {
        return category;
    }

    public String[] getAliases() {
        return aliases;
    }

    /**
     * 获取所有可导入字段
     */
    public static List<QuestionField> getImportableFields() {
        return Arrays.asList(values());
    }

    /**
     * 获取字段选项列表（以"不映射"开头，然后按分类排列所有字段的显示名）。
     * 用于 Spinner / WebView <select> 等下拉选择控件。
     */
    public static List<String> getFieldOptions() {
        List<String> options = new ArrayList<>();
        options.add("不映射");
        for (String category : getCategories()) {
            for (QuestionField field : getByCategory(category)) {
                options.add(field.getDisplayName());
            }
        }
        return options;
    }

    /**
     * 获取所有分类名称（按定义顺序，去重）
     */
    public static List<String> getCategories() {
        List<String> categories = new ArrayList<>();
        for (QuestionField field : values()) {
            if (!categories.contains(field.category)) {
                categories.add(field.category);
            }
        }
        return categories;
    }

    /**
     * 获取指定分类下的所有字段
     */
    public static List<QuestionField> getByCategory(String category) {
        List<QuestionField> result = new ArrayList<>();
        for (QuestionField field : values()) {
            if (field.category.equals(category)) {
                result.add(field);
            }
        }
        return result;
    }

    /**
     * 通过显示名查找字段
     */
    public static QuestionField getByDisplayName(String displayName) {
        if (displayName == null) return null;
        for (QuestionField field : values()) {
            if (field.displayName.equals(displayName)) {
                return field;
            }
        }
        return null;
    }

    /**
     * 通过模型字段名查找字段
     */
    public static QuestionField getByModelFieldName(String modelFieldName) {
        if (modelFieldName == null) return null;
        for (QuestionField field : values()) {
            if (field.modelFieldName.equals(modelFieldName)) {
                return field;
            }
        }
        return null;
    }

    /**
     * 智能推荐：根据 Excel 列头匹配最可能的字段
     * @param columnHeader Excel 则头文字
     * @return 匹配的字段，无匹配时返回 null
     */
    public static QuestionField suggestField(String columnHeader) {
        if (columnHeader == null || columnHeader.trim().isEmpty()) {
            return null;
        }

        String headerLower = columnHeader.toLowerCase().trim();

        // 第一轮：精确匹配显示名或别名
        for (QuestionField field : values()) {
            if (field.displayName.equalsIgnoreCase(columnHeader.trim())) {
                return field;
            }
            for (String alias : field.aliases) {
                if (alias.equalsIgnoreCase(headerLower)) {
                    return field;
                }
            }
        }

        // 第二轮：包含匹配（填空题空N答案优先，避免"答案"截断"答案1"）
        for (QuestionField field : values()) {
            if (!field.category.equals("答案与解析")) continue;
            if (field == CORRECT_ANSWER || field == EXPLANATION || field == ANALYSIS || field == HINT) continue;
            // BLANK_ANSWER_1..12
            if (headerLower.contains(field.displayName.toLowerCase())) {
                return field;
            }
            for (String alias : field.aliases) {
                if (alias.length() > 1 && headerLower.contains(alias)) {
                    return field;
                }
            }
        }

        // 第三轮：包含匹配（选项 A~L 优先）
        for (QuestionField field : values()) {
            if (!field.category.equals("选项")) continue;
            if (headerLower.contains(field.displayName.toLowerCase())) {
                return field;
            }
            for (String alias : field.aliases) {
                if (headerLower.contains(alias) && alias.length() > 1) {
                    return field;
                }
            }
        }

        // 其余字段包含匹配
        for (QuestionField field : values()) {
            // 跳过已处理的选项和blank答案
            if (field.category.equals("选项")) continue;
            if (field.isBlankAnswerField()) continue;

            if (headerLower.contains(field.displayName.toLowerCase())) {
                return field;
            }
            for (String alias : field.aliases) {
                if (alias.length() > 2 && headerLower.contains(alias)) {
                    return field;
                }
            }
        }

        return null;
    }

    /**
     * 判断该字段是否为填空题空N答案（BLANK_ANSWER_1..12）
     */
    public boolean isBlankAnswerField() {
        return this == BLANK_ANSWER_1 || this == BLANK_ANSWER_2 || this == BLANK_ANSWER_3
                || this == BLANK_ANSWER_4 || this == BLANK_ANSWER_5 || this == BLANK_ANSWER_6
                || this == BLANK_ANSWER_7 || this == BLANK_ANSWER_8 || this == BLANK_ANSWER_9
                || this == BLANK_ANSWER_10 || this == BLANK_ANSWER_11 || this == BLANK_ANSWER_12;
    }

    /**
     * 获取空N答案的序号（1~12），非空答案字段返回 -1
     */
    public int getBlankAnswerIndex() {
        switch (this) {
            case BLANK_ANSWER_1: return 1;
            case BLANK_ANSWER_2: return 2;
            case BLANK_ANSWER_3: return 3;
            case BLANK_ANSWER_4: return 4;
            case BLANK_ANSWER_5: return 5;
            case BLANK_ANSWER_6: return 6;
            case BLANK_ANSWER_7: return 7;
            case BLANK_ANSWER_8: return 8;
            case BLANK_ANSWER_9: return 9;
            case BLANK_ANSWER_10: return 10;
            case BLANK_ANSWER_11: return 11;
            case BLANK_ANSWER_12: return 12;
            default: return -1;
        }
    }

    /**
     * 判断该字段是否为额外选项（E~L，存储在 extraOptions JSON 中）
     */
    public boolean isExtraOption() {
        return this == OPTION_E || this == OPTION_F || this == OPTION_G || this == OPTION_H
                || this == OPTION_I || this == OPTION_J || this == OPTION_K || this == OPTION_L;
    }

    /**
     * 获取额外选项的键名（"E".."L"）
     */
    public String getExtraOptionKey() {
        switch (this) {
            case OPTION_E: return "E";
            case OPTION_F: return "F";
            case OPTION_G: return "G";
            case OPTION_H: return "H";
            case OPTION_I: return "I";
            case OPTION_J: return "J";
            case OPTION_K: return "K";
            case OPTION_L: return "L";
            default: return null;
        }
    }

    /**
     * 判断该字段是否为数值型字段（导入时需要 Integer.parseInt 转换）
     */
    public boolean isNumericField() {
        return this == DIFFICULTY || this == POINTS || this == TIME_LIMIT;
    }

    /**
     * 从字段映射表中按优先级获取列索引：显示名 → 全部别名
     * 替代 ExcelUtil 中各处散落的 finalFieldMapping.get("题目"); ... finalFieldMapping.get("question"); 写法
     */
    public Integer getColumnIndex(Map<String, Integer> fieldMapping) {
        if (fieldMapping == null) return null;
        // 显示名优先
        Integer idx = fieldMapping.get(displayName);
        if (idx != null) return idx;
        // 别名回退
        for (String alias : aliases) {
            idx = fieldMapping.get(alias);
            if (idx != null) return idx;
        }
        return null;
    }

    /**
     * 将单元格字符串值设置到 Question 对象对应字段。
     * 额外选项(E~L)会聚合到 extraOptions JSON 中；空N答案会通过 question.addBlankAnswer() 合并到 correctAnswer。
     * @param question 题目对象
     * @param value 字符串值
     * @param extraOptionsAggregator 额外选项聚合器（可为 null，仅对 E~L 生效）
     */
    public void setValue(Question question, String value, org.json.JSONObject extraOptionsAggregator) {
        if (question == null || value == null) return;
        if (isExtraOption()) {
            String key = getExtraOptionKey();
            if (extraOptionsAggregator != null && key != null && !value.isEmpty()) {
                try { extraOptionsAggregator.put(key, value); } catch (Exception ignored) {}
            }
            return;
        }
        if (isBlankAnswerField()) {
            // 空N答案：合并写入 correctAnswer（分号分隔）
            if (value.trim().isEmpty()) return;
            String existing = question.getCorrectAnswer() == null ? "" : question.getCorrectAnswer().trim();
            if (existing.isEmpty()) {
                question.setCorrectAnswer(value.trim());
            } else {
                question.setCorrectAnswer(existing + "；" + value.trim());
            }
            return;
        }
        switch (this) {
            case QUESTION_TEXT:    question.setQuestionText(value); break;
            case QUESTION_TYPE:    question.setQuestionType(value); break;
            case CORRECT_ANSWER:   question.setCorrectAnswer(value); break;
            case OPTION_A:         question.setOptionA(value); break;
            case OPTION_B:         question.setOptionB(value); break;
            case OPTION_C:         question.setOptionC(value); break;
            case OPTION_D:         question.setOptionD(value); break;
            case EXPLANATION:      question.setExplanation(value); break;
            case ANALYSIS:         question.setAnalysis(value); break;
            case HINT:             question.setHint(value); break;
            case CATEGORY:         question.setCategory(value); break;
            case SUB_CATEGORY:     question.setSubCategory(value); break;
            case KNOWLEDGE_POINT:  question.setKnowledgePoint(value); break;
            case TAGS:             question.setTags(value); break;
            case RELATED_QUESTION: question.setRelatedQuestion(value); break;
            case AUTHOR:           question.setAuthor(value); break;
            case SOURCE:           question.setSource(value); break;
            case COMMENT:          question.setComment(value); break;
            case DIFFICULTY:
                try { question.setDifficulty(parseIntSafe(value, 1)); }
                catch (Exception ignored) {}
                break;
            case POINTS:
                try { question.setPoints(parseIntSafe(value, 0)); }
                catch (Exception ignored) {}
                break;
            case TIME_LIMIT:
                try { question.setTimeLimit(parseIntSafe(value, 0)); }
                catch (Exception ignored) {}
                break;
            default:
                // 未识别的枚举值，跳过
                break;
        }
    }

    private static int parseIntSafe(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
