package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Java 导出器
 * 使用纯 Java 处理导出，不依赖 Python
 */
public class JavaExporter implements Exporter {
    private static final String TAG = "JavaExporter";

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        List<String> fields = ExportUtils.getEffectiveFields(task.getConfig());
        boolean includeAnswers = task.getConfig().isIncludeAnswers();
        boolean includeExplanations = task.getConfig().isIncludeExplanations();

        // 按题型分组并排序
        java.util.Map<String, java.util.List<Question>> questionsByType = new java.util.HashMap<>();
        for (Question question : questions) {
            String type = question.getQuestionType() != null ? question.getQuestionType() : "未分类";
            if (!questionsByType.containsKey(type)) {
                questionsByType.put(type, new java.util.ArrayList<>());
            }
            questionsByType.get(type).add(question);
        }

        // 动态生成题型顺序
        java.util.List<java.util.Map.Entry<String, java.util.List<Question>>> sortedTypes = new java.util.ArrayList<>(questionsByType.entrySet());
        sortedTypes.sort((a, b) -> a.getKey().compareTo(b.getKey()));

        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "Java导出题目_" + timestamp;
        }

        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + ".java");

        try (OutputStreamWriter writer = new OutputStreamWriter(new java.io.FileOutputStream(exportFile), StandardCharsets.UTF_8)) {
            // Java 文件头
            writer.write("/*\n");
            writer.write(" * 导出题目 - 自动生成\n");
            writer.write(" * 导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "\n");
            writer.write(" * 导出题目总数: " + questions.size() + "\n");
            writer.write(" */\n\n");
            writer.write("package com.oilquiz.app.model;\n\n");
            writer.write("import java.util.*;\n\n");
            writer.write("public class QuizQuestions {\n\n");
            writer.write("    /**\n");
            writer.write("     * 获取所有题目\n");
            writer.write("     */\n");
            writer.write("    public static List<Question> getQuestions() {\n");
            writer.write("        List<Question> questions = new ArrayList<>();\n\n");

            // 按题型写入问题数据
            int questionNumber = 1;
            int total = questions.size();
            int processed = 0;

            for (java.util.Map.Entry<String, java.util.List<Question>> entry : sortedTypes) {
                String type = entry.getKey();
                java.util.List<Question> typeQuestions = entry.getValue();

                writer.write("        // " + type + " (" + typeQuestions.size() + "题)\n");

                for (Question question : typeQuestions) {
                    writer.write("        {\n");
                    writer.write("            Question q = new Question();\n");
                    writer.write("            q.setId(" + question.getId() + ");\n");
                    
                    // 题型
                    if (ExportUtils.hasField(fields, "questionType")) {
                        String typeValue = question.getQuestionType();
                        if (typeValue != null && !typeValue.isEmpty()) {
                            writer.write("            q.setQuestionType(" + jsonString(typeValue) + ");\n");
                        }
                    }

                    // 题目内容
                    if (ExportUtils.hasField(fields, "questionText")) {
                        String text = question.getQuestionText();
                        if (text != null && !text.isEmpty()) {
                            writer.write("            q.setQuestionText(" + jsonString(text) + ");\n");
                        }
                    }

                    // 选项（A~L）
                    for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                        if (!ExportUtils.hasField(fields, ExportUtils.OPTION_FIELDS[o])) continue;
                        Object optionValue = ExportUtils.getOptionValue(question, o);
                        if (optionValue != null && !optionValue.toString().isEmpty()) {
                            writer.write("            q.setOption" + (o + 1) + "(" + jsonString(optionValue.toString()) + ");\n");
                        }
                    }

                    // 正确答案
                    if (includeAnswers && ExportUtils.hasField(fields, "correctAnswer")) {
                        String correctAnswer = question.getCorrectAnswer();
                        if (correctAnswer != null && !correctAnswer.isEmpty()) {
                            writer.write("            q.setCorrectAnswer(" + jsonString(correctAnswer) + ");\n");
                        }
                    }

                    // 答案文本
                    if (includeAnswers && ExportUtils.hasField(fields, "answerText")) {
                        String answerText = question.getAnswerText();
                        if (answerText != null && !answerText.isEmpty()) {
                            writer.write("            q.setAnswerText(" + jsonString(answerText) + ");\n");
                        }
                    }

                    // 解析
                    if (includeExplanations && ExportUtils.hasField(fields, "explanation")) {
                        String explanation = question.getExplanation();
                        if (explanation != null && !explanation.isEmpty()) {
                            writer.write("            q.setExplanation(" + jsonString(explanation) + ");\n");
                        }
                    }

                    // 难度
                    if (ExportUtils.hasField(fields, "difficulty")) {
                        writer.write("            q.setDifficulty(" + question.getDifficulty() + ");\n");
                    }

                    // 分类
                    if (ExportUtils.hasField(fields, "category")) {
                        String category = question.getCategory();
                        if (category != null && !category.isEmpty()) {
                            writer.write("            q.setCategory(" + jsonString(category) + ");\n");
                        }
                    }

                    // 子分类
                    if (ExportUtils.hasField(fields, "subCategory")) {
                        String subCategory = question.getSubCategory();
                        if (subCategory != null && !subCategory.isEmpty()) {
                            writer.write("            q.setSubCategory(" + jsonString(subCategory) + ");\n");
                        }
                    }

                    // 知识点
                    if (ExportUtils.hasField(fields, "knowledgePoint")) {
                        String knowledgePoint = question.getKnowledgePoint();
                        if (knowledgePoint != null && !knowledgePoint.isEmpty()) {
                            writer.write("            q.setKnowledgePoint(" + jsonString(knowledgePoint) + ");\n");
                        }
                    }

                    // 分值
                    if (ExportUtils.hasField(fields, "points")) {
                        writer.write("            q.setPoints(" + question.getPoints() + ");\n");
                    }

                    // 收藏
                    writer.write("            q.setFavorite(" + question.isFavorite() + ");\n");

                    writer.write("            questions.add(q);\n");
                    writer.write("        }\n\n");

                    // 更新进度
                    processed++;
                    if (task.getCallback() != null && processed % 10 == 0) {
                        int progress = (int) (processed * 100.0 / total);
                        task.getCallback().onExportProgress(progress);
                    }
                }
            }

            writer.write("\n        return questions;\n");
            writer.write("    }\n");
            writer.write("}\n");

            // 确保最后更新到100%
            if (task.getCallback() != null) {
                task.getCallback().onExportProgress(100);
            }

            return exportFile;
        }
    }

    /**
     * 将字符串转换为 Java 字符串字面量
     */
    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        // 转义 Java 字符串特殊字符
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    @Override
    public String getFormatName() {
        return "Java";
    }

    @Override
    public String getFileExtension() {
        return "java";
    }

    @Override
    public void validateParameters(ExportManager.ExportTask task) throws IllegalArgumentException {
        if (task == null) {
            throw new IllegalArgumentException("导出任务不能为空");
        }

        if (task.getConfig() == null) {
            throw new IllegalArgumentException("导出配置不能为空");
        }

        if (task.getQuestions() == null || task.getQuestions().isEmpty()) {
            throw new IllegalArgumentException("没有问题可导出");
        }
    }
}
