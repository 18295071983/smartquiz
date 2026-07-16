package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * CSV模板导出器
 * 支持基于模板的CSV导出
 */
public class CSVTemplateExporter extends TemplateBasedExporter {

    public CSVTemplateExporter(Template template) {
        super(template);
    }

    @Override
    protected File doExport(ExportManager.ExportTask task) throws Exception {
        List<Question> questions = task.getQuestions();
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = "export_" + System.currentTimeMillis();
        }
        File exportFile = new File(com.oilquiz.app.util.export.ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());

        try (FileWriter writer = new FileWriter(exportFile)) {
            // 写入CSV表头
            writeCSVHeader(writer, task);
            
            // 写入数据
            for (Question question : questions) {
                writeCSVRow(writer, question, task);
            }
        }

        return exportFile;
    }

    /**
     * 写入CSV表头
     */
    private void writeCSVHeader(FileWriter writer, ExportManager.ExportTask task) throws IOException {
        List<String> fields = getFieldsToExport(task);
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            String displayName = FieldMapper.getFieldDisplayName(template, field);
            writer.write("\"" + displayName + "\"");
            if (i < fields.size() - 1) {
                writer.write(",");
            }
        }
        writer.write("\n");
    }

    /**
     * 写入CSV行
     */
    private void writeCSVRow(FileWriter writer, Question question, ExportManager.ExportTask task) throws IOException {
        List<String> fields = getFieldsToExport(task);
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            Object value = FieldMapper.getFieldValue(question, field);
            String stringValue = value != null ? value.toString() : "";
            // 处理CSV特殊字符
            stringValue = stringValue.replace("\"", "\"\"");
            writer.write("\"" + stringValue + "\"");
            if (i < fields.size() - 1) {
                writer.write(",");
            }
        }
        writer.write("\n");
    }

    /**
     * 获取要导出的字段
     */
    private List<String> getFieldsToExport(ExportManager.ExportTask task) {
        List<String> selectedFields = task.getConfig().getSelectedFields();
        if (selectedFields != null && !selectedFields.isEmpty()) {
            return selectedFields;
        }
        if (template != null && template.getFields() != null) {
            return template.getFields();
        }
        // 默认字段
        return FieldMapper.getAllAvailableFields().keySet().stream().toList();
    }

    @Override
    public String getFileExtension() {
        return "csv";
    }
}
