package com.oilquiz.app.util.export;

import android.util.Log;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * CSV导出器
 * 导出问题为CSV格式
 */
public class CSVExporter implements Exporter {
    private static final String TAG = "CSVExporter";

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        // 创建导出文件
        File exportDir = ExportManager.getExportDirectory(task.getContext());
        if (exportDir == null) {
            throw new IOException("无法创建导出目录");
        }

        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }

        File file = new File(exportDir, fileName + ".csv");

        // 写入CSV数据
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            List<Question> questions = task.getQuestions();
            
            // 收集所有非空字段
            java.util.Set<String> nonEmptyFieldsSet = collectNonEmptyFields(task, questions);
            List<String> nonEmptyFields = new java.util.ArrayList<>(nonEmptyFieldsSet);
            
            // 写入表头
            writeCSVHeader(writer, task, questions);

            // 写入问题数据
            int total = questions.size();
            for (int i = 0; i < total; i++) {
                writeCSVQuestion(writer, task, questions.get(i), nonEmptyFields);
                
                // 更新进度
                if (task.getCallback() != null && i % 10 == 0) {
                    int progress = (int) ((i + 1) * 100.0 / total);
                    task.getCallback().onExportProgress(progress);
                }
            }
            
            // 确保最后更新到100%
            if (task.getCallback() != null) {
                task.getCallback().onExportProgress(100);
            }
        }

        return file;
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

    @Override
    public String getFileExtension() {
        return "csv";
    }

    @Override
    public String getFormatName() {
        return "CSV";
    }

    /**
     * 写入CSV表头
     */
    private void writeCSVHeader(BufferedWriter writer, ExportManager.ExportTask task, List<Question> questions) throws IOException {
        // 收集所有非空字段
        java.util.Set<String> nonEmptyFields = collectNonEmptyFields(task, questions);
        
        // 写入表头
        int i = 0;
        for (String fieldName : nonEmptyFields) {
            if (i > 0) {
                writer.write(",");
            }
            writer.write(fieldName);
            i++;
        }
        writer.newLine();
    }

    /**
     * 写入CSV问题数据
     */
    private void writeCSVQuestion(BufferedWriter writer, ExportManager.ExportTask task, Question question, List<String> nonEmptyFields) throws IOException {
        int i = 0;
        for (String fieldName : nonEmptyFields) {
            if (i > 0) {
                writer.write(",");
            }
            Object value = ExportUtils.getFieldValue(question, fieldName);
            writer.write(escapeCsvValue(value != null ? value.toString() : ""));
            i++;
        }
        writer.newLine();
    }

    /**
     * 收集所有非空字段
     */
    private java.util.Set<String> collectNonEmptyFields(ExportManager.ExportTask task, List<Question> questions) {
        java.util.Set<String> nonEmptyFields = new java.util.HashSet<>();
        List<String> selectedFields = task.getConfig().getSelectedFields();
        if (selectedFields == null || selectedFields.isEmpty()) {
            selectedFields = ExportUtils.getQuestionFields();
        }

        // 检查每个题目，收集非空字段
        for (Question question : questions) {
            for (String fieldName : selectedFields) {
                Object value = ExportUtils.getFieldValue(question, fieldName);
                if (value != null && !value.toString().isEmpty()) {
                    nonEmptyFields.add(fieldName);
                }
            }
        }

        // 如果没有非空字段，至少保留id字段
        if (nonEmptyFields.isEmpty() && selectedFields.contains("id")) {
            nonEmptyFields.add("id");
        }

        return nonEmptyFields;
    }

    /**
     * 转义CSV值
     */
    private String escapeCsvValue(String value) {
        if (value == null) {
            return "";
        }

        // 如果值包含逗号、引号或换行符，需要用引号包围
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            // 替换双引号为两个双引号
            value = value.replace("\"", "\"\"");
            // 用双引号包围
            return "\"" + value + "\"";
        }

        return value;
    }
}