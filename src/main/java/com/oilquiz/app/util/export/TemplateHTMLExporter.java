package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.template.Template;
import com.oilquiz.app.util.export.template.HTMLTemplate;
import com.oilquiz.app.util.export.template.HTMLTemplateFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;

public class TemplateHTMLExporter implements Exporter {

    private Template template;

    public TemplateHTMLExporter(Template template) {
        this.template = template;
    }

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = template.getName() + "_" + timestamp;
        }
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());

        try (FileWriter writer = new FileWriter(exportFile)) {
            // 按题型分组并排序
            Map<String, List<Question>> questionsByType = new HashMap<>();
            for (Question question : questions) {
                String type = question.getQuestionType() != null ? question.getQuestionType() : "未分类";
                if (!questionsByType.containsKey(type)) {
                    questionsByType.put(type, new ArrayList<>());
                }
                questionsByType.get(type).add(question);
            }
            
            // 动态生成题型顺序
            List<Map.Entry<String, List<Question>>> sortedTypes = new ArrayList<>(questionsByType.entrySet());
            // 按题型名称排序
            Collections.sort(sortedTypes, (a, b) -> a.getKey().compareTo(b.getKey()));

            // 使用模板工厂创建对应的模板实例
            HTMLTemplate htmlTemplate = HTMLTemplateFactory.createTemplate(template.getName());
            // 调用模板的writeTemplate方法
            htmlTemplate.writeTemplate(writer, task, questions, sortedTypes);
            
            return exportFile;
        }
    }

    @Override
    public void validateParameters(ExportManager.ExportTask task) throws IllegalArgumentException {
        if (task == null) {
            throw new IllegalArgumentException("Export task cannot be null");
        }
        if (task.getQuestions() == null) {
            throw new IllegalArgumentException("Questions cannot be null");
        }
        if (task.getContext() == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
    }

    @Override
    public String getFormatName() {
        return "Template HTML";
    }

    @Override
    public String getFileExtension() {
        return "html";
    }
}
