package com.oilquiz.app.util.export;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.List;
import com.itextpdf.layout.element.ListItem;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PDFExporter implements Exporter {

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        java.util.List<Question> questions = task.getQuestions();
        
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

        try (PdfWriter writer = new PdfWriter(exportFile);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {
            
            // 设置页面大小为A4竖向
            com.itextpdf.kernel.geom.PageSize pageSize = com.itextpdf.kernel.geom.PageSize.A4;
            pdf.setDefaultPageSize(pageSize);
            
            // 设置页边距
            document.setMargins(36, 36, 36, 36); // 1 inch margins
            
            // 加载中文字体
            PdfFont font = null;
            try {
                // 尝试从assets目录加载字体
                InputStream fontStream = task.getContext().getAssets().open("fonts/simkai.ttf");
                // 使用 Android 兼容的方式读取输入流
                byte[] fontBytes = toByteArray(fontStream);
                font = PdfFontFactory.createFont(fontBytes, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            } catch (Exception e) {
                // 如果字体加载失败，尝试使用系统字体
                try {
                    font = PdfFontFactory.createFont("C:/Windows/Fonts/simkai.ttf");
                } catch (Exception ex) {
                    // 如果系统字体也不可用，使用默认字体
                    font = PdfFontFactory.createFont();
                }
            }
            
            // 设置文档默认字体
            document.setFont(font);
            
            // 创建标题
            Paragraph title = new Paragraph("导出题目");
            title.setFont(font);
            title.setFontSize(20);
            title.setBold();
            title.setTextAlignment(com.itextpdf.layout.properties.TextAlignment.CENTER);
            document.add(title);
            document.add(new Paragraph("\n").setFont(font));
            
            // 添加导出信息
            Paragraph timeInfo = new Paragraph("导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()));
            timeInfo.setFont(font);
            timeInfo.setFontSize(10);
            timeInfo.setTextAlignment(com.itextpdf.layout.properties.TextAlignment.RIGHT);
            document.add(timeInfo);
            
            Paragraph countInfo = new Paragraph("导出题目总数: " + questions.size());
            countInfo.setFont(font);
            countInfo.setFontSize(10);
            countInfo.setTextAlignment(com.itextpdf.layout.properties.TextAlignment.RIGHT);
            document.add(countInfo);
            document.add(new Paragraph("\n").setFont(font));
            
            // 按题型写入问题数据
            int total = questions.size();
            int processed = 0;
            int typeIndex = 1;
            for (java.util.Map.Entry<String, java.util.List<Question>> entry : sortedTypes) {
                String type = entry.getKey();
                java.util.List<Question> typeQuestions = entry.getValue();
                
                // 题型标题
                document.add(new Paragraph(typeIndex + "、" + type + " (" + typeQuestions.size() + "题)").setFont(font).setFontSize(16).setBold());
                document.add(new Paragraph("\n").setFont(font));
                typeIndex++;
                
                // 写入该题型的题目，每个题型从1开始编号
                int typeQuestionNumber = 1;
                for (Question question : typeQuestions) {
                    // 问题标题
                    if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                        document.add(new Paragraph("第" + typeQuestionNumber++ + "题: " + question.getQuestionText()).setFont(font).setFontSize(12));
                    } else {
                        typeQuestionNumber++;
                    }
                    
                    // 选项
                    if (question.hasOptions()) {
                        document.add(new Paragraph("选项:").setFont(font).setFontSize(12));
                        com.itextpdf.layout.element.List optionsList = new com.itextpdf.layout.element.List();
                        if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                            ListItem itemA = new ListItem("A. " + question.getOptionA());
                            itemA.setFont(font);
                            optionsList.add(itemA);
                        }
                        if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                            ListItem itemB = new ListItem("B. " + question.getOptionB());
                            itemB.setFont(font);
                            optionsList.add(itemB);
                        }
                        if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                            ListItem itemC = new ListItem("C. " + question.getOptionC());
                            itemC.setFont(font);
                            optionsList.add(itemC);
                        }
                        if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                            ListItem itemD = new ListItem("D. " + question.getOptionD());
                            itemD.setFont(font);
                            optionsList.add(itemD);
                        }
                        document.add(optionsList);
                    }
                    
                    // 根据配置决定是否包含答案
                    if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                        document.add(new Paragraph("正确答案: " + question.getCorrectAnswer()).setFont(font).setFontSize(12).setBold());
                    }
                    
                    // 根据配置决定是否包含解析
                    if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                        document.add(new Paragraph("解析: " + question.getExplanation()).setFont(font).setFontSize(12));
                    }
                    

                    
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
        return "PDF";
    }

    @Override
    public String getFileExtension() {
        return "pdf";
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

    /**
     * 将输入流转换为字节数组（Android 兼容）
     */
    private byte[] toByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
        }
        return outputStream.toByteArray();
    }
}