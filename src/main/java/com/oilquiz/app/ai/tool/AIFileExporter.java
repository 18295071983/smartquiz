package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AIFileExporter {

    private static final String TAG = "AIFileExporter";
    private final Context context;
    private final DatabaseManager databaseManager;

    public AIFileExporter(Context context) {
        this.context = context;
        this.databaseManager = DatabaseManager.getInstance(context);
    }

    // 导出题目到文件
    public String exportQuestions(String format, String outputPath) {
        try {
            // 确保输出路径存在
            File outputDir = new File(outputPath);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            // 获取所有题目
            List<Question> questions = databaseManager.getAllQuestions().get(10, TimeUnit.SECONDS);
            if (questions.isEmpty()) {
                return "没有题目可以导出";
            }

            // 生成文件名
            String fileName = "questions." + format;
            File outputFile = new File(outputDir, fileName);

            // 根据格式导出
            switch (format.toLowerCase()) {
                case "csv":
                    exportToCsv(questions, outputFile);
                    break;
                case "txt":
                    exportToTxt(questions, outputFile);
                    break;
                case "json":
                    exportToJson(questions, outputFile);
                    break;
                default:
                    return "不支持的文件格式: " + format;
            }

            return "题目导出成功，保存位置: " + outputFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Error exporting questions", e);
            return "导出题目时出错: " + e.getMessage();
        }
    }

    // 导出为CSV格式
    private void exportToCsv(List<Question> questions, File outputFile) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));

        // 写入表头
        writer.write("题目,选项,正确答案,解析,难度,类型,科目");
        writer.newLine();

        // 写入题目数据
        for (Question question : questions) {
            StringBuilder line = new StringBuilder();
            line.append(escapeCsvField(question.getQuestionText())).append(",");
            
            // 组合选项
            StringBuilder options = new StringBuilder();
            if (question.getOptionA() != null) options.append("A: ").append(question.getOptionA()).append("\n");
            if (question.getOptionB() != null) options.append("B: ").append(question.getOptionB()).append("\n");
            if (question.getOptionC() != null) options.append("C: ").append(question.getOptionC()).append("\n");
            if (question.getOptionD() != null) options.append("D: ").append(question.getOptionD());
            line.append(escapeCsvField(options.toString())).append(",");
            
            line.append(escapeCsvField(question.getCorrectAnswer())).append(",");
            line.append(escapeCsvField(question.getExplanation())).append(",");
            line.append(escapeCsvField(getDifficultyString(question.getDifficulty()))).append(",");
            line.append(escapeCsvField(question.getQuestionType())).append(",");
            line.append(escapeCsvField(question.getCategory()));
            
            writer.write(line.toString());
            writer.newLine();
        }

        writer.close();
    }

    // 导出为文本格式
    private void exportToTxt(List<Question> questions, File outputFile) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));

        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            writer.write("Q: " + (i + 1) + ". " + question.getQuestionText());
            writer.newLine();
            
            if (question.getOptionA() != null) {
                writer.write("A: " + question.getOptionA());
                writer.newLine();
            }
            if (question.getOptionB() != null) {
                writer.write("B: " + question.getOptionB());
                writer.newLine();
            }
            if (question.getOptionC() != null) {
                writer.write("C: " + question.getOptionC());
                writer.newLine();
            }
            if (question.getOptionD() != null) {
                writer.write("D: " + question.getOptionD());
                writer.newLine();
            }
            
            writer.write("答案: " + question.getCorrectAnswer());
            writer.newLine();
            
            if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                writer.write("解析: " + question.getExplanation());
                writer.newLine();
            }
            
            writer.write("难度: " + getDifficultyString(question.getDifficulty()));
            writer.newLine();
            writer.write("类型: " + question.getQuestionType());
            writer.newLine();
            writer.write("科目: " + question.getCategory());
            writer.newLine();
            writer.newLine();
        }

        writer.close();
    }

    // 导出为JSON格式
    private void exportToJson(List<Question> questions, File outputFile) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));

        writer.write("[");
        writer.newLine();

        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            writer.write("  {");
            writer.newLine();
            writer.write("    \"id\": " + question.getId() + ",");
            writer.newLine();
            writer.write("    \"questionText\": \"" + escapeJsonField(question.getQuestionText()) + "\",");
            writer.newLine();
            
            if (question.getOptionA() != null) {
                writer.write("    \"optionA\": \"" + escapeJsonField(question.getOptionA()) + "\",");
                writer.newLine();
            }
            if (question.getOptionB() != null) {
                writer.write("    \"optionB\": \"" + escapeJsonField(question.getOptionB()) + "\",");
                writer.newLine();
            }
            if (question.getOptionC() != null) {
                writer.write("    \"optionC\": \"" + escapeJsonField(question.getOptionC()) + "\",");
                writer.newLine();
            }
            if (question.getOptionD() != null) {
                writer.write("    \"optionD\": \"" + escapeJsonField(question.getOptionD()) + "\",");
                writer.newLine();
            }
            
            writer.write("    \"correctAnswer\": \"" + escapeJsonField(question.getCorrectAnswer()) + "\",");
            writer.newLine();
            writer.write("    \"explanation\": \"" + escapeJsonField(question.getExplanation()) + "\",");
            writer.newLine();
            writer.write("    \"difficulty\": \"" + escapeJsonField(getDifficultyString(question.getDifficulty())) + "\",");
            writer.newLine();
            writer.write("    \"type\": \"" + escapeJsonField(question.getQuestionType()) + "\",");
            writer.newLine();
            writer.write("    \"subject\": \"" + escapeJsonField(question.getCategory()) + "\"");
            writer.newLine();
            writer.write("  }" + (i < questions.size() - 1 ? "," : ""));
            writer.newLine();
        }

        writer.write("]");
        writer.newLine();
        writer.close();
    }

    // 转义CSV字段
    private String escapeCsvField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\n") || field.contains("\"")) {
            field = field.replace("\"", "\"\"");
            return "\"" + field + "\"";
        }
        return field;
    }

    // 转义JSON字段
    private String escapeJsonField(String field) {
        if (field == null) return "";
        return field.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // 将难度值转换为字符串
    private String getDifficultyString(int difficulty) {
        switch (difficulty) {
            case 1:
                return "简单";
            case 2:
                return "中等";
            case 3:
                return "困难";
            default:
                return "未知";
        }
    }
}
