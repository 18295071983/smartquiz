package com.oilquiz.app.model;

import androidx.room.ColumnInfo;
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
        @Index(value = "questionType", name = "index_question_questionType"),
        @Index(value = {"category", "difficulty"}),
        @Index(value = {"questionType", "status"}, name = "index_question_questionType_status")
    }
)
public class Question implements java.io.Serializable {

    // ========== 难度常量 ==========
    public static final int DIFFICULTY_EASY = 1;
    public static final int DIFFICULTY_MEDIUM = 2;
    public static final int DIFFICULTY_HARD = 3;

    // ========== 状态常量 ==========
    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_DISABLED = 1;
    public static final int STATUS_PENDING = 2;

    // ========== 可见性常量 ==========
    public static final int VISIBILITY_PRIVATE = 0;
    public static final int VISIBILITY_PUBLIC = 1;

    // ========== 题型常量 ==========
    public static final String TYPE_SINGLE = "单选题";
    public static final String TYPE_MULTIPLE = "多选题";
    public static final String TYPE_JUDGE = "判断题";
    public static final String TYPE_FILL = "填空题";
    public static final String TYPE_SHORT_ANSWER = "简答题";

    @PrimaryKey(autoGenerate = true)
    private long id;
    private String questionText;
    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String correctAnswer;
    private String category;
    // 默认值 1（简单）：保证外部 INSERT 省略该列时不报 NOT NULL 约束失败
    @ColumnInfo(defaultValue = "1")
    private int difficulty;
    private String explanation;
    private String relatedQuestion;
    private String questionType;
    @ColumnInfo(defaultValue = "0")
    private boolean favorite;
    
    // 新增字段：创建和更新时间
    @ColumnInfo(defaultValue = "0")
    private long createdAt;
    @ColumnInfo(defaultValue = "0")
    private long updatedAt;
    
    // 新增字段：题目来源
    private String source;
    
    // 新增字段：题目标签（多个标签用逗号分隔）
    private String tags;
    
    // 新增字段：题目分值
    @ColumnInfo(defaultValue = "0")
    private int points;
    
    // 新增字段：答题时限（秒）
    @ColumnInfo(defaultValue = "0")
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
    @ColumnInfo(defaultValue = "0")
    private int usageCount;
    @ColumnInfo(defaultValue = "0")
    private int correctCount;
    @ColumnInfo(defaultValue = "0")
    private int incorrectCount;
    @ColumnInfo(defaultValue = "0")
    private long lastUsedAt;
    
    // 新增字段：题目状态（0-正常，1-禁用，2-待审核）
    // 默认 0（正常）：App 查询均带 WHERE status=0，默认 0 保证外部导入题目可见
    @ColumnInfo(defaultValue = "0")
    private int status;
    
    // 新增字段：是否公开（0-私有，1-公开）
    @ColumnInfo(defaultValue = "1")
    private int isPublic;
    
    // 新增字段：题目作者
    private String author;
    
    // 新增字段：题目备注
    private String comment;
    
    // ========== v22: 选项E~L独立列（替代extraOptions JSON） ==========
    private String optionE;
    private String optionF;
    private String optionG;
    private String optionH;
    private String optionI;
    private String optionJ;
    private String optionK;
    private String optionL;
    
    // ========== v21 新增字段 ==========
    
    // 标准答案文本（填空题/简答题使用，与correctAnswer分离）
    // correctAnswer存选项字母(如"A"/"AB")，answerText存实际答案文本
    private String answerText;
    
    // 题目配图路径（几何题、图表题等）
    private String imageUri;
    
    // 听力题音频路径
    private String audioUri;
    
    // 母题ID（子题关联，如"材料分析第1/2/3小题"共享题干）
    @ColumnInfo(defaultValue = "0")
    private long parentId;
    
    // 排序权重（组卷时控制题目顺序）
    @ColumnInfo(defaultValue = "0")
    private int sortOrder;

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
    
    // ========== v22: 选项E~L独立字段 getter/setter ==========
    
    public String getOptionE() { return optionE; }
    public void setOptionE(String v) { this.optionE = v; }
    public String getOptionF() { return optionF; }
    public void setOptionF(String v) { this.optionF = v; }
    public String getOptionG() { return optionG; }
    public void setOptionG(String v) { this.optionG = v; }
    public String getOptionH() { return optionH; }
    public void setOptionH(String v) { this.optionH = v; }
    public String getOptionI() { return optionI; }
    public void setOptionI(String v) { this.optionI = v; }
    public String getOptionJ() { return optionJ; }
    public void setOptionJ(String v) { this.optionJ = v; }
    public String getOptionK() { return optionK; }
    public void setOptionK(String v) { this.optionK = v; }
    public String getOptionL() { return optionL; }
    public void setOptionL(String v) { this.optionL = v; }
    
    // ========== v21 新增字段 getter/setter ==========
    
    public String getAnswerText() {
        return answerText;
    }
    
    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }
    
    /**
     * 获取显示用的标准答案：优先answerText，回退correctAnswer
     */
    public String getDisplayAnswer() {
        if (answerText != null && !answerText.isEmpty()) return answerText;
        return correctAnswer;
    }
    
    public String getImageUri() {
        return imageUri;
    }
    
    public void setImageUri(String imageUri) {
        this.imageUri = imageUri;
    }
    
    public String getAudioUri() {
        return audioUri;
    }
    
    public void setAudioUri(String audioUri) {
        this.audioUri = audioUri;
    }
    
    public long getParentId() {
        return parentId;
    }
    
    public void setParentId(long parentId) {
        this.parentId = parentId;
    }
    
    public int getSortOrder() {
        return sortOrder;
    }
    
    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
    
    /**
     * 是否有配图
     */
    public boolean hasImage() {
        return imageUri != null && !imageUri.isEmpty();
    }
    
    /**
     * 是否有音频
     */
    public boolean hasAudio() {
        return audioUri != null && !audioUri.isEmpty();
    }
    
    /**
     * 是否为子题（有母题关联）
     */
    public boolean isSubQuestion() {
        return parentId > 0;
    }

    /**
     * 动态获取所有非空选项（A~L全部独立字段）
     * @return 选项列表，格式为 {"A": "选项内容", "B": "选项内容", ..., "L": ...}
     */
    public java.util.Map<String, String> getOptions() {
        java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
        if (optionA != null && !optionA.isEmpty()) options.put("A", optionA);
        if (optionB != null && !optionB.isEmpty()) options.put("B", optionB);
        if (optionC != null && !optionC.isEmpty()) options.put("C", optionC);
        if (optionD != null && !optionD.isEmpty()) options.put("D", optionD);
        if (optionE != null && !optionE.isEmpty()) options.put("E", optionE);
        if (optionF != null && !optionF.isEmpty()) options.put("F", optionF);
        if (optionG != null && !optionG.isEmpty()) options.put("G", optionG);
        if (optionH != null && !optionH.isEmpty()) options.put("H", optionH);
        if (optionI != null && !optionI.isEmpty()) options.put("I", optionI);
        if (optionJ != null && !optionJ.isEmpty()) options.put("J", optionJ);
        if (optionK != null && !optionK.isEmpty()) options.put("K", optionK);
        if (optionL != null && !optionL.isEmpty()) options.put("L", optionL);
        return options;
    }

    /**
     * 按字母（A~L）设置选项。全部写入独立字段。
     */
    public void setOptionByLetter(String letter, String value) {
        String trimmedLetter = (letter == null) ? "" : letter.trim().toUpperCase();
        if (trimmedLetter.isEmpty()) return;
        String trimmedVal = (value == null) ? "" : value;
        switch (trimmedLetter) {
            case "A": setOptionA(trimmedVal); break;
            case "B": setOptionB(trimmedVal); break;
            case "C": setOptionC(trimmedVal); break;
            case "D": setOptionD(trimmedVal); break;
            case "E": setOptionE(trimmedVal); break;
            case "F": setOptionF(trimmedVal); break;
            case "G": setOptionG(trimmedVal); break;
            case "H": setOptionH(trimmedVal); break;
            case "I": setOptionI(trimmedVal); break;
            case "J": setOptionJ(trimmedVal); break;
            case "K": setOptionK(trimmedVal); break;
            case "L": setOptionL(trimmedVal); break;
        }
    }

    /**
     * 按字母（A~L）读取选项。全部从独立字段读取。
     */
    public String getOptionByLetter(String letter) {
        if (letter == null) return "";
        String l = letter.trim().toUpperCase();
        if (l.isEmpty()) return "";
        switch (l) {
            case "A": return optionA == null ? "" : optionA;
            case "B": return optionB == null ? "" : optionB;
            case "C": return optionC == null ? "" : optionC;
            case "D": return optionD == null ? "" : optionD;
            case "E": return optionE == null ? "" : optionE;
            case "F": return optionF == null ? "" : optionF;
            case "G": return optionG == null ? "" : optionG;
            case "H": return optionH == null ? "" : optionH;
            case "I": return optionI == null ? "" : optionI;
            case "J": return optionJ == null ? "" : optionJ;
            case "K": return optionK == null ? "" : optionK;
            case "L": return optionL == null ? "" : optionL;
            default: return "";
        }
    }

    /**
     * 批量设置选项（支持A-L所有选项）
     */
    public void setOptions(java.util.Map<String, String> options) {
        if (options == null || options.isEmpty()) return;
        // 清空现有选项
        setOptionA(null); setOptionB(null); setOptionC(null); setOptionD(null);
        setOptionE(null); setOptionF(null); setOptionG(null); setOptionH(null);
        setOptionI(null); setOptionJ(null); setOptionK(null); setOptionL(null);
        // 设置新选项
        for (java.util.Map.Entry<String, String> entry : options.entrySet()) {
            String letter = entry.getKey();
            String value = entry.getValue();
            if (letter != null && !letter.isEmpty() && value != null) {
                setOptionByLetter(letter, value);
            }
        }
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

    // ========== 便捷方法 ==========

    /**
     * 获取难度的文字描述
     */
    public String getDifficultyText() {
        switch (difficulty) {
            case DIFFICULTY_EASY: return "简单";
            case DIFFICULTY_MEDIUM: return "中等";
            case DIFFICULTY_HARD: return "困难";
            default: return "未设置";
        }
    }

    /**
     * 获取状态的文字描述
     */
    public String getStatusText() {
        switch (status) {
            case STATUS_NORMAL: return "正常";
            case STATUS_DISABLED: return "已禁用";
            case STATUS_PENDING: return "待审核";
            default: return "未知";
        }
    }

    /**
     * 判断题目是否启用（状态正常）
     */
    public boolean isActive() {
        return status == STATUS_NORMAL;
    }

    /**
     * 判断是否为选择题（单选/多选/判断）
     */
    public boolean isChoiceQuestion() {
        return TYPE_SINGLE.equals(questionType)
            || TYPE_MULTIPLE.equals(questionType)
            || TYPE_JUDGE.equals(questionType);
    }

    /**
     * 判断是否为客观题（有标准答案可自动判分）
     */
    public boolean isObjectiveQuestion() {
        return TYPE_SINGLE.equals(questionType)
            || TYPE_MULTIPLE.equals(questionType)
            || TYPE_JUDGE.equals(questionType)
            || TYPE_FILL.equals(questionType);
    }

    /**
     * 校验用户答案是否正确
     * @param userAnswer 用户提交的答案
     * @return 是否正确
     */
    public boolean checkAnswer(String userAnswer) {
        if (correctAnswer == null || userAnswer == null) return false;
        String normalizedCorrect = normalizeChoiceAnswer(correctAnswer);
        String normalizedUser = normalizeChoiceAnswer(userAnswer);
        return normalizedCorrect.equals(normalizedUser);
    }

    /**
     * 校验多选题答案（忽略选项顺序）
     * @param userAnswer 用户提交的答案，如 "AB" 或 "ACD"
     * @return 是否正确
     */
    public boolean checkMultipleAnswer(String userAnswer) {
        if (correctAnswer == null || userAnswer == null) return false;
        char[] correct = normalizeChoiceAnswer(correctAnswer).toCharArray();
        char[] user = normalizeChoiceAnswer(userAnswer).toCharArray();
        java.util.Arrays.sort(correct);
        java.util.Arrays.sort(user);
        return java.util.Arrays.equals(correct, user);
    }

    /**
     * 规范化选择题答案：去除分隔符（;；,，、空白）并转大写。
     * 兼容历史脏数据如 "A;B;C" → "ABC"；非字母答案（如填空题文本）原样保留。
     */
    public static String normalizeChoiceAnswer(String ans) {
        if (ans == null) return "";
        String n = ans.replaceAll("[;；,，、\\s]+", "").toUpperCase();
        if (!n.isEmpty() && n.matches("[A-L]+")) return n;
        return ans.trim();
    }

    /**
     * 获取正确率（0~100）
     */
    public int getCorrectRate() {
        int total = correctCount + incorrectCount;
        if (total == 0) return 0;
        return (int) ((correctCount * 100.0) / total);
    }

    /**
     * 获取标签列表
     */
    public java.util.List<String> getTagList() {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (tags != null && !tags.isEmpty()) {
            for (String tag : tags.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 设置标签列表
     */
    public void setTagList(java.util.List<String> tagList) {
        if (tagList == null || tagList.isEmpty()) {
            this.tags = null;
        } else {
            this.tags = android.text.TextUtils.join(",", tagList).toString();
        }
    }

    /**
     * 添加单个标签
     */
    public void addTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) return;
        java.util.List<String> list = getTagList();
        String trimmed = tag.trim();
        if (!list.contains(trimmed)) {
            list.add(trimmed);
            setTagList(list);
        }
    }

    /**
     * 获取题型图标 emoji
     */
    public String getTypeEmoji() {
        if (questionType == null) return "❓";
        switch (questionType) {
            case TYPE_SINGLE: return "🔘";
            case TYPE_MULTIPLE: return "☑️";
            case TYPE_JUDGE: return "⚖️";
            case TYPE_FILL: return "✏️";
            case TYPE_SHORT_ANSWER: return "📝";
            default: return "❓";
        }
    }

    /**
     * 获取难度星级
     */
    public String getDifficultyStars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < difficulty; i++) sb.append("★");
        for (int i = difficulty; i < 3; i++) sb.append("☆");
        return sb.toString();
    }

    /**
     * 自动设置更新时间
     */
    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 初始化统计和时间戳（新建题目时调用）
     */
    public void initDefaults() {
        long now = System.currentTimeMillis();
        if (createdAt == 0) createdAt = now;
        updatedAt = now;
        if (points == 0) points = 1;
        if (status == 0 && questionType == null) questionType = TYPE_SINGLE;
    }

    @Override
    public String toString() {
        return "Question{" +
            "id=" + id +
            ", type='" + questionType + '\'' +
            ", text='" + (questionText != null && questionText.length() > 30
                ? questionText.substring(0, 30) + "..." : questionText) + '\'' +
            ", category='" + category + '\'' +
            ", difficulty=" + difficulty +
            ", options=" + getOptionCount() +
            ", favorite=" + favorite +
            '}';
    }
}
