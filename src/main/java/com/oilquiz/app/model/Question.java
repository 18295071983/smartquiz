package com.oilquiz.app.model;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 题目实体类
 * 添加了索引优化查询性能
 */
@Entity(
    tableName = "question",
    indices = {
        @Index(value = "category"),
        @Index(value = "difficulty"),
        @Index(value = "favorite"),
        @Index(value = {"category", "difficulty"})
    }
)
public class Question implements java.io.Serializable {
    @PrimaryKey(autoGenerate = true)
    private long id;
    private String questionText;
    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String correctAnswer;
    private String category;
    private int difficulty;
    private String explanation;
    private String relatedQuestion;
    private String questionType;
    private boolean favorite;
    
    // 新增字段：创建和更新时间
    private long createdAt;
    private long updatedAt;
    
    // 新增字段：题目来源
    private String source;
    
    // 新增字段：题目标签（多个标签用逗号分隔）
    private String tags;
    
    // 新增字段：题目分值
    private int points;
    
    // 新增字段：答题时限（秒）
    private int timeLimit;
    
    // 新增字段：题目提示
    private String hint;
    
    // 新增字段：题目解析（详细解析）
    private String analysis;
    
    // 新增字段：知识点
    private String knowledgePoint;
    
    // 新增字段：子分类
    private String subCategory;
    
    // 新增字段：使用统计
    private int usageCount;
    private int correctCount;
    private int incorrectCount;
    private long lastUsedAt;
    
    // 新增字段：题目状态（0-正常，1-禁用，2-待审核）
    private int status;
    
    // 新增字段：是否公开（0-私有，1-公开）
    private int isPublic;
    
    // 新增字段：题目作者
    private String author;
    
    // 新增字段：题目备注
    private String comment;
    
    // 新增字段：额外选项（JSON格式，存储E、F、G、H等选项）
    // 格式: {"E":"选项E内容","F":"选项F内容","G":"选项G内容"}
    private String extraOptions;

    public Question() {
    }

    @androidx.room.Ignore
    public Question(String questionText, String optionA, String optionB, String optionC, String optionD, 
                   String correctAnswer, String category, int difficulty, String explanation, 
                   String relatedQuestion, String questionType, boolean favorite) {
        this.questionText = questionText;
        this.optionA = optionA;
        this.optionB = optionB;
        this.optionC = optionC;
        this.optionD = optionD;
        this.correctAnswer = correctAnswer;
        this.category = category;
        this.difficulty = difficulty;
        this.explanation = explanation;
        this.relatedQuestion = relatedQuestion;
        this.questionType = questionType;
        this.favorite = favorite;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getOptionA() {
        return optionA;
    }

    public void setOptionA(String optionA) {
        this.optionA = optionA;
    }

    public String getOptionB() {
        return optionB;
    }

    public void setOptionB(String optionB) {
        this.optionB = optionB;
    }

    public String getOptionC() {
        return optionC;
    }

    public void setOptionC(String optionC) {
        this.optionC = optionC;
    }

    public String getOptionD() {
        return optionD;
    }

    public void setOptionD(String optionD) {
        this.optionD = optionD;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(int difficulty) {
        this.difficulty = difficulty;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getRelatedQuestion() {
        return relatedQuestion;
    }

    public void setRelatedQuestion(String relatedQuestion) {
        this.relatedQuestion = relatedQuestion;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }
    
    // 新增字段的 getter 和 setter 方法
    public long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
    
    public long getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public String getSource() {
        return source;
    }
    
    public void setSource(String source) {
        this.source = source;
    }
    
    public String getTags() {
        return tags;
    }
    
    public void setTags(String tags) {
        this.tags = tags;
    }
    
    public int getPoints() {
        return points;
    }
    
    public void setPoints(int points) {
        this.points = points;
    }
    
    public int getTimeLimit() {
        return timeLimit;
    }
    
    public void setTimeLimit(int timeLimit) {
        this.timeLimit = timeLimit;
    }
    
    public String getHint() {
        return hint;
    }
    
    public void setHint(String hint) {
        this.hint = hint;
    }
    
    public String getAnalysis() {
        return analysis;
    }
    
    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }
    
    public String getKnowledgePoint() {
        return knowledgePoint;
    }
    
    public void setKnowledgePoint(String knowledgePoint) {
        this.knowledgePoint = knowledgePoint;
    }
    
    public String getSubCategory() {
        return subCategory;
    }
    
    public void setSubCategory(String subCategory) {
        this.subCategory = subCategory;
    }
    
    public int getUsageCount() {
        return usageCount;
    }
    
    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }
    
    public int getCorrectCount() {
        return correctCount;
    }
    
    public void setCorrectCount(int correctCount) {
        this.correctCount = correctCount;
    }
    
    public int getIncorrectCount() {
        return incorrectCount;
    }
    
    public void setIncorrectCount(int incorrectCount) {
        this.incorrectCount = incorrectCount;
    }
    
    public long getLastUsedAt() {
        return lastUsedAt;
    }
    
    public void setLastUsedAt(long lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
    
    public int getStatus() {
        return status;
    }
    
    public void setStatus(int status) {
        this.status = status;
    }
    
    public int getIsPublic() {
        return isPublic;
    }
    
    public void setIsPublic(int isPublic) {
        this.isPublic = isPublic;
    }
    
    public String getAuthor() {
        return author;
    }
    
    public void setAuthor(String author) {
        this.author = author;
    }
    
    public String getComment() {
        return comment;
    }
    
    public void setComment(String comment) {
        this.comment = comment;
    }
    
    public String getExtraOptions() {
        return extraOptions;
    }
    
    public void setExtraOptions(String extraOptions) {
        this.extraOptions = extraOptions;
    }

    /**
     * 动态获取所有非空选项（包括额外选项）
     * @return 选项列表，格式为 {"A": "选项内容", "B": "选项内容", ..., "L": ...}
     */
    public java.util.Map<String, String> getOptions() {
        java.util.Map<String, String> options = new java.util.HashMap<>();
        if (optionA != null && !optionA.isEmpty()) {
            options.put("A", optionA);
        }
        if (optionB != null && !optionB.isEmpty()) {
            options.put("B", optionB);
        }
        if (optionC != null && !optionC.isEmpty()) {
            options.put("C", optionC);
        }
        if (optionD != null && !optionD.isEmpty()) {
            options.put("D", optionD);
        }
        // 解析额外选项
        if (extraOptions != null && !extraOptions.isEmpty()) {
            try {
                org.json.JSONObject jsonObject = new org.json.JSONObject(extraOptions);
                java.util.Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = jsonObject.optString(key, "");
                    if (value != null && !value.isEmpty()) {
                        options.put(key, value);
                    }
                }
            } catch (Exception e) {
                // JSON解析失败，忽略
            }
        }
        return options;
    }

    /**
     * 按字母（A~L）设置选项。A~D写入实体字段，E~L写入extraOptions JSON。
     * @param letter 大写字母A/B/.../L
     * @param value 选项内容（空或null清除该选项
     */
    public void setOptionByLetter(String letter, String value) {
        String trimmedLetter = (letter == null) ? "" : letter.trim().toUpperCase();
        if (trimmedLetter.isEmpty()) return;
        char c = trimmedLetter.charAt(0);
        String trimmedVal = (value == null) ? "" : value;
        if ("A".equals(trimmedLetter)) { setOptionA(trimmedVal); return; }
        if ("B".equals(trimmedLetter)) { setOptionB(trimmedVal); return; }
        if ("C".equals(trimmedLetter)) { setOptionC(trimmedVal); return; }
        if ("D".equals(trimmedLetter)) { setOptionD(trimmedVal); return; }
        // E~L 存入 extraOptions (JSONObject
        if (c >= 'E' && c <= 'L') {
            org.json.JSONObject jo = new org.json.JSONObject();
            try {
                if (extraOptions != null && !extraOptions.isEmpty()) {
                    jo = new org.json.JSONObject(extraOptions);
                }
            } catch (Exception ignore) {
                jo = new org.json.JSONObject();
            }
            try {
                if (trimmedVal == null || trimmedVal.isEmpty()) {
                    jo.remove(trimmedLetter);
                } else {
                    jo.put(trimmedLetter, trimmedVal);
                }
                if (jo.length() == 0) extraOptions = null;
                else extraOptions = jo.toString();
            } catch (Exception ignore) {}
        }
    }

    /**
     * 按字母（A~L）读取选项。A~D读实体字段，E~L读extraOptions
     */
    public String getOptionByLetter(String letter) {
        if (letter == null) return "";
        String l = letter.trim().toUpperCase();
        if (l.isEmpty()) return "";
        if ("A".equals(l)) return getOptionA() == null ? "" : getOptionA();
        if ("B".equals(l)) return getOptionB() == null ? "" : getOptionB();
        if ("C".equals(l)) return getOptionC() == null ? "" : getOptionC();
        if ("D".equals(l)) return getOptionD() == null ? "" : getOptionD();
        char c = l.charAt(0);
        if (c < 'A' || c > 'Z') return "";
        // 从extraOptions读
        if (extraOptions != null && !extraOptions.isEmpty()) {
            try {
                org.json.JSONObject jo = new org.json.JSONObject(extraOptions);
                return jo.optString(l, "");
            } catch (Exception ignore) {}
        }
        return "";
    }

    /**
     * 统一便捷set方法：A~L通过letter直接setOptionByLetter
     */
    public void setOptionE(String v) { setOptionByLetter("E", v); }
    public void setOptionF(String v) { setOptionByLetter("F", v); }
    public void setOptionG(String v) { setOptionByLetter("G", v); }
    public void setOptionH(String v) { setOptionByLetter("H", v); }
    public void setOptionI(String v) { setOptionByLetter("I", v); }
    public void setOptionJ(String v) { setOptionByLetter("J", v); }
    public void setOptionK(String v) { setOptionByLetter("K", v); }
    public void setOptionL(String v) { setOptionByLetter("L", v); }
    public String getOptionE() { return getOptionByLetter("E"); }
    public String getOptionF() { return getOptionByLetter("F"); }
    public String getOptionG() { return getOptionByLetter("G"); }
    public String getOptionH() { return getOptionByLetter("H"); }
    public String getOptionI() { return getOptionByLetter("I"); }
    public String getOptionJ() { return getOptionByLetter("J"); }
    public String getOptionK() { return getOptionByLetter("K"); }
    public String getOptionL() { return getOptionByLetter("L"); }

    /**
     * 获取选项数量
     * @return 非空选项的数量
     */
    public int getOptionCount() {
        return getOptions().size();
    }

    /**
     * 检查是否有选项
     * @return 是否有非空选项
     */
    public boolean hasOptions() {
        return getOptionCount() > 0;
    }
}
