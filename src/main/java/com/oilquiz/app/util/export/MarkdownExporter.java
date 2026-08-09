package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MarkdownExporter implements Exporter {

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        // 模板生效字段列表（场景模板/自定义字段），决定导出内容与顺序
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
        // 按题型名称排序
        sortedTypes.sort((a, b) -> a.getKey().compareTo(b.getKey()));

        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());

        // UTF-8 带 BOM 写入，避免 Windows 记事本/旧编辑器打开中文乱码
        try (OutputStreamWriter writer = new OutputStreamWriter(new java.io.FileOutputStream(exportFile), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("# 导出题目\n\n");
            writer.write("## 导出信息\n\n");
            writer.write("- 导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "\n");
            writer.write("- 导出题目总数: " + questions.size() + "\n\n");

            // 按题型写入问题数据
            int questionNumber = 1;
            int total = questions.size();
            int processed = 0;
            for (java.util.Map.Entry<String, java.util.List<Question>> entry : sortedTypes) {
                String type = entry.getKey();
                java.util.List<Question> typeQuestions = entry.getValue();

                // 题型标题
                writer.write("## " + type + " (" + typeQuestions.size() + "题)\n\n");

                // 写入该题型的题目
                for (Question question : typeQuestions) {
                    writer.write("### 第 " + questionNumber++ + " 题\n\n");

                    // 头部信息行：题型/难度/分类/子分类/知识点/分值/时限
                    StringBuilder headInfo = new StringBuilder();
                    if (ExportUtils.hasField(fields, "questionType")) {
                        String typeValue = question.getQuestionType();
                        if (typeValue != null && !typeValue.isEmpty()) {
                            headInfo.append("**题型:** ").append(typeValue).append(" | ");
                        }
                    }
                    if (ExportUtils.hasField(fields, "difficulty")) {
                        headInfo.append("**难度:** ").append(question.getDifficultyText()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "category")) {
                        String category = question.getCategory();
                        if (category != null && !category.isEmpty()) {
                            headInfo.append("**分类:** ").append(category).append(" | ");
                        }
                    }
                    if (ExportUtils.hasField(fields, "subCategory")) {
                        String subCategory = question.getSubCategory();
                        if (subCategory != null && !subCategory.isEmpty()) {
                            headInfo.append("**子分类:** ").append(subCategory).append(" | ");
                        }
                    }
                    if (ExportUtils.hasField(fields, "knowledgePoint")) {
                        String knowledgePoint = question.getKnowledgePoint();
                        if (knowledgePoint != null && !knowledgePoint.isEmpty()) {
                            headInfo.append("**知识点:** ").append(knowledgePoint).append(" | ");
                        }
                    }
                    if (ExportUtils.hasField(fields, "points")) {
                        headInfo.append("**分值:** ").append(question.getPoints()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "timeLimit")) {
                        headInfo.append("**时限:** ").append(question.getTimeLimit()).append("秒 | ");
                    }
                    if (headInfo.length() > 0) {
                        writer.write(headInfo.substring(0, headInfo.length() - 3) + "\n\n");
                    }

                    // 题目内容
                    if (ExportUtils.hasField(fields, "questionText")) {
                        String text = question.getQuestionText();
                        if (text != null && !text.isEmpty()) {
                            writer.write("**题目内容:** " + text + "\n\n");
                        }
                    }

                    // 选项（A~L 动态渲染，只输出模板选中且非空的选项）
                    writer.write("**选项:**\n\n");
                    for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                        if (!ExportUtils.hasField(fields, ExportUtils.OPTION_FIELDS[o])) continue;
                        Object optionValue = ExportUtils.getOptionValue(question, o);
                        if (optionValue != null && !optionValue.toString().isEmpty()) {
                            writer.write("- " + ExportUtils.OPTION_LABELS[o] + ". " + optionValue + "\n");
                        }
                    }
                    writer.write("\n");

                    // 正确答案 + 答案文本（受「包含答案」开关控制）
                    if (includeAnswers) {
                        if (ExportUtils.hasField(fields, "correctAnswer")) {
                            String correctAnswer = question.getCorrectAnswer();
                            if (correctAnswer != null && !correctAnswer.isEmpty()) {
                                writer.write("**正确答案:** " + correctAnswer + "\n\n");
                            }
                        }
                        if (ExportUtils.hasField(fields, "answerText")) {
                            String answerText = question.getAnswerText();
                            if (answerText != null && !answerText.isEmpty()) {
                                writer.write("**答案文本:** " + answerText + "\n\n");
                            }
                        }
                    }

                    // 解析（受「包含解析」开关控制）
                    if (includeExplanations && ExportUtils.hasField(fields, "explanation")) {
                        String explanation = question.getExplanation();
                        if (explanation != null && !explanation.isEmpty()) {
                            writer.write("**解析:**\n\n");
                            writer.write(explanation + "\n\n");
                        }
                    }

                    // 详细解析
                    if (includeExplanations && ExportUtils.hasField(fields, "analysis")) {
                        String analysis = question.getAnalysis();
                        if (analysis != null && !analysis.isEmpty()) {
                            writer.write("**详细解析:**\n\n");
                            writer.write(analysis + "\n\n");
                        }
                    }

                    // 其他元信息字段（标签/提示/来源/作者/备注/相关题目/收藏等）
                    StringBuilder metaInfo = new StringBuilder();
                    if (ExportUtils.hasField(fields, "tags")) {
                        String tags = question.getTags();
                        if (tags != null && !tags.isEmpty()) metaInfo.append("- 标签: ").append(tags).append("\n");
                    }
                    if (ExportUtils.hasField(fields, "hint")) {
                        String hint = question.getHint();
                        if (hint != null && !hint.isEmpty()) metaInfo.append("- 提示: ").append(hint).append("\n");
                    }
                    if (ExportUtils.hasField(fields, "source")) {
                        String source = question.getSource();
                        if (source != null && !source.isEmpty()) metaInfo.append("- 来源: ").append(source).append("\n");
                    }
                    if (ExportUtils.hasField(fields, "author")) {
                        String author = question.getAuthor();
                        if (author != null && !author.isEmpty()) metaInfo.append("- 作者: ").append(author).append("\n");
                    }
                    if (ExportUtils.hasField(fields, "comment")) {
                        String comment = question.getComment();
                        if (comment != null && !comment.isEmpty()) metaInfo.append("- 备注: ").append(comment).append("\n");
                    }
                    if (ExportUtils.hasField(fields, "relatedQuestion")) {
                        String relatedQuestion = question.getRelatedQuestion();
                        if (relatedQuestion != null && !relatedQuestion.isEmpty()) metaInfo.append("- 相关题目: ").append(relatedQuestion).append("\n");
                    }
                    if (ExportUtils.hasField(fields, "favorite")) {
                        metaInfo.append("- 收藏: ").append(question.isFavorite() ? "是" : "否").append("\n");
                    }
                    if (ExportUtils.hasField(fields, "usageCount")) {
                        metaInfo.append("- 使用次数: ").append(question.getUsageCount()).append("\n");
                    }
                    if (ExportUtils.hasField(fields, "incorrectCount")) {
                        metaInfo.append("- 答错次数: ").append(question.getIncorrectCount()).append("\n");
                    }
                    if (metaInfo.length() > 0) {
                        writer.write(metaInfo + "\n");
                    }

                    writer.write("---\n\n");

                    // 更新进度
                    processed++;
                    if (task.getCallback() != null && processed % 10 == 0) {
                        int progress = (int) (processed * 100.0 / total);
                        task.getCallback().onExportProgress(progress);
                    }
                }
            }

            // 确保最后更新到100%
            if (task.getCallback() != null) {
                task.getCallback().onExportProgress(100);
            }

            return exportFile;
        }
    }

    @Override
    public String getFormatName() {
        return "Markdown";
    }

    @Override
    public String getFileExtension() {
        return "md";
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
