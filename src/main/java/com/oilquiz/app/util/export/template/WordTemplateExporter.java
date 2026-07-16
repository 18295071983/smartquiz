package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import org.apache.poi.xwpf.usermodel.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Word模板导出器
 * 支持基于模板的Word导出
 */
public class WordTemplateExporter extends TemplateBasedExporter {

    public WordTemplateExporter(Template template) {
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

        try (XWPFDocument document = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(exportFile)) {
            
            // 设置文档属性
            setDocumentProperties(document, task);
            
            // 创建标题
            createTitle(document, task);
            
            // 写入问题内容
            writeQuestions(document, questions, task);
            
            document.write(fos);
        }

        return exportFile;
    }

    /**
     * 设置文档属性
     */
    private void setDocumentProperties(XWPFDocument document, ExportManager.ExportTask task) {
        XWPFDocument doc = document;
        doc.getProperties().getCoreProperties().setTitle(task.getConfig().getDocumentTitle() != null ? task.getConfig().getDocumentTitle() : "题库导出");
        doc.getProperties().getCoreProperties().setCreator(task.getConfig().getDocumentAuthor() != null ? task.getConfig().getDocumentAuthor() : "OilQuiz");
    }

    /**
     * 创建标题
     */
    private void createTitle(XWPFDocument document, ExportManager.ExportTask task) {
        XWPFParagraph titlePara = document.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText(task.getConfig().getDocumentTitle() != null ? task.getConfig().getDocumentTitle() : "题库导出");
        titleRun.setFontSize(18);
        titleRun.setBold(true);
        
        // 添加空行
        document.createParagraph();
    }

    /**
     * 写入问题内容
     */
    private void writeQuestions(XWPFDocument document, List<Question> questions, ExportManager.ExportTask task) {
        List<String> fields = getFieldsToExport(task);
        
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            
            // 问题编号
            XWPFParagraph questionPara = document.createParagraph();
            XWPFRun questionRun = questionPara.createRun();
            questionRun.setText("第 " + (i + 1) + " 题：");
            questionRun.setBold(true);
            questionRun.setFontSize(12);
            
            // 写入字段内容
            for (String field : fields) {
                Object value = FieldMapper.getFieldValue(question, field);
                if (value != null && !value.toString().isEmpty()) {
                    XWPFParagraph fieldPara = document.createParagraph();
                    XWPFRun fieldRun = fieldPara.createRun();
                    fieldRun.setText(FieldMapper.getFieldDisplayName(template, field) + "：" + value.toString());
                    fieldRun.setFontSize(11);
                }
            }
            
            // 添加空行
            document.createParagraph();
        }
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
        return "docx";
    }
}
