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
     * @return 选项列表，格式为 {"A": "选项内容", "B": "选项内容", ...}
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
                    String value = jsonObject.getString(key);
                    options.put(key, value);
                }
            } catch (Exception e) {
                // JSON解析失败，忽略
            }
        }
        
        return options;
    }

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
