package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class MarkdownExporter implements Exporter {

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        
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

        try (FileWriter writer = new FileWriter(exportFile)) {
            writer.write("# 导出题目\n\n");
            writer.write("<!-- 自适应显示优化 -->\n");
            writer.write("<!-- 该Markdown文件已优化，可在WebView中自适应显示 -->\n\n");
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
                    
                    // 只导出非空字段
                    if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                        writer.write("**题目内容:** " + question.getQuestionText() + "\n\n");
                    }
                    
                    writer.write("**题目信息:** 题型: " + type + " | 难度: " + question.getDifficulty() + "\n\n");
                    
                    writer.write("**选项:**\n\n");
                    if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                        writer.write("- A. " + question.getOptionA() + "\n");
                    }
                    if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                        writer.write("- B. " + question.getOptionB() + "\n");
                    }
                    if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                        writer.write("- C. " + question.getOptionC() + "\n");
                    }
                    if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                        writer.write("- D. " + question.getOptionD() + "\n");
                    }
                    writer.write("\n");
                    
                    // 根据配置决定是否包含答案
                    if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                        writer.write("**正确答案:** " + question.getCorrectAnswer() + "\n\n");
                    }
                    
                    // 根据配置决定是否包含解析
                    if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                        writer.write("**解析:**\n\n");
                        writer.write(question.getExplanation() + "\n\n");
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