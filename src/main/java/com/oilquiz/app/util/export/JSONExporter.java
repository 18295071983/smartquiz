package com.oilquiz.app.util.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class JSONExporter implements Exporter {

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());

        try (FileWriter writer = new FileWriter(exportFile)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            
            List<String> selectedFields = task.getConfig().getSelectedFields();
            int total = questions.size();
            
            if (selectedFields == null || selectedFields.isEmpty()) {
                // 默认导出所有非空字段
                List<java.util.Map<String, Object>> filteredQuestions = new java.util.ArrayList<>();
                for (int i = 0; i < total; i++) {
                    Question question = questions.get(i);
                    java.util.Map<String, Object> filteredQuestion = new java.util.HashMap<>();
                    for (String fieldName : ExportUtils.getQuestionFields()) {
                        Object value = ExportUtils.getFieldValue(question, fieldName);
                        if (value != null && !value.toString().isEmpty()) {
                            filteredQuestion.put(fieldName, value);
                        }
                    }
                    filteredQuestions.add(filteredQuestion);
                    
                    // 更新进度
                    if (task.getCallback() != null && i % 10 == 0) {
                        int progress = (int) ((i + 1) * 100.0 / total);
                        task.getCallback().onExportProgress(progress);
                    }
                }
                gson.toJson(filteredQuestions, writer);
            } else {
                // 只导出选中的非空字段
                List<java.util.Map<String, Object>> filteredQuestions = new java.util.ArrayList<>();
                for (int i = 0; i < total; i++) {
                    Question question = questions.get(i);
                    java.util.Map<String, Object> filteredQuestion = new java.util.HashMap<>();
                    for (String fieldName : selectedFields) {
                        Object value = ExportUtils.getFieldValue(question, fieldName);
                        if (value != null && !value.toString().isEmpty()) {
                            filteredQuestion.put(fieldName, value);
                        }
                    }
                    filteredQuestions.add(filteredQuestion);
                    
                    // 更新进度
                    if (task.getCallback() != null && i % 10 == 0) {
                        int progress = (int) ((i + 1) * 100.0 / total);
                        task.getCallback().onExportProgress(progress);
                    }
                }
                gson.toJson(filteredQuestions, writer);
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
        return "JSON";
    }

    @Override
    public String getFileExtension() {
        return "json";
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