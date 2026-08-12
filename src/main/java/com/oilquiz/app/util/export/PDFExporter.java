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
        // 模板生效字段列表（场景模板/自定义字段），决定导出内容
        java.util.List<String> fields = ExportUtils.getEffectiveFields(task.getConfig());
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
                    
                    // 题目元信息行（题型/难度/分类/知识点/分值等，按模板字段）
                    StringBuilder metaInfo = new StringBuilder();
                    if (ExportUtils.hasField(fields, "questionType") && question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
                        metaInfo.append("题型: ").append(question.getQuestionType()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "difficulty")) {
                        metaInfo.append("难度: ").append(question.getDifficultyText()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "category") && question.getCategory() != null && !question.getCategory().isEmpty()) {
                        metaInfo.append("分类: ").append(question.getCategory()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "knowledgePoint") && question.getKnowledgePoint() != null && !question.getKnowledgePoint().isEmpty()) {
                        metaInfo.append("知识点: ").append(question.getKnowledgePoint()).append(" | ");
                    }
                    if (ExportUtils.hasField(fields, "points")) {
                        metaInfo.append("分值: ").append(question.getPoints()).append(" | ");
                    }
                    if (metaInfo.length() > 0) {
                        Paragraph metaPara = new Paragraph(metaInfo.substring(0, metaInfo.length() - 3));
                        metaPara.setFont(font);
                        metaPara.setFontSize(9);
                        metaPara.setFontColor(new com.itextpdf.kernel.colors.DeviceRgb(136, 136, 136));
                        document.add(metaPara);
                    }
                    
                    // 选项（A~L 动态渲染，只输出模板选中且非空的选项）
                    if (question.hasOptions()) {
                        com.itextpdf.layout.element.List optionsList = new com.itextpdf.layout.element.List();
                        for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                            if (!ExportUtils.hasField(fields, ExportUtils.OPTION_FIELDS[o])) continue;
                            Object optionValue = ExportUtils.getOptionValue(question, o);
                            if (optionValue == null || optionValue.toString().isEmpty()) continue;
                            ListItem item = new ListItem(ExportUtils.OPTION_LABELS[o] + ". " + optionValue);
                            item.setFont(font);
                            optionsList.add(item);
                        }
                        document.add(optionsList);
                    }
                    
                    // 根据配置决定是否包含答案（正确答案 + 答案文本）
                    if (includeAnswers) {
                        if (ExportUtils.hasField(fields, "correctAnswer") && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                            document.add(new Paragraph("正确答案: " + question.getCorrectAnswer()).setFont(font).setFontSize(12).setBold());
                        }
                        if (ExportUtils.hasField(fields, "answerText") && question.getAnswerText() != null && !question.getAnswerText().isEmpty()) {
                            document.add(new Paragraph("答案文本: " + question.getAnswerText()).setFont(font).setFontSize(12).setBold());
                        }
                    }
                    
                    // 根据配置决定是否包含解析（解析 + 详细解析）
                    if (includeExplanations && ExportUtils.hasField(fields, "explanation") && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                        document.add(new Paragraph("解析: " + question.getExplanation()).setFont(font).setFontSize(12));
                    }
                    if (includeExplanations && ExportUtils.hasField(fields, "analysis") && question.getAnalysis() != null && !question.getAnalysis().isEmpty()) {
                        document.add(new Paragraph("详细解析: " + question.getAnalysis()).setFont(font).setFontSize(12));
                    }
                    
                    // 其他元信息（标签/提示/来源/作者/备注/相关题目）
                    StringBuilder extraInfo = new StringBuilder();
                    if (ExportUtils.hasField(fields, "tags") && question.getTags() != null && !question.getTags().isEmpty()) {
                        extraInfo.append("标签: ").append(question.getTags()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "hint") && question.getHint() != null && !question.getHint().isEmpty()) {
                        extraInfo.append("提示: ").append(question.getHint()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "source") && question.getSource() != null && !question.getSource().isEmpty()) {
                        extraInfo.append("来源: ").append(question.getSource()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "author") && question.getAuthor() != null && !question.getAuthor().isEmpty()) {
                        extraInfo.append("作者: ").append(question.getAuthor()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "comment") && question.getComment() != null && !question.getComment().isEmpty()) {
                        extraInfo.append("备注: ").append(question.getComment()).append("  ");
                    }
                    if (ExportUtils.hasField(fields, "relatedQuestion") && question.getRelatedQuestion() != null && !question.getRelatedQuestion().isEmpty()) {
                        extraInfo.append("相关题目: ").append(question.getRelatedQuestion()).append("  ");
                    }
                    if (extraInfo.length() > 0) {
                        Paragraph extraPara = new Paragraph(extraInfo.toString().trim());
                        extraPara.setFont(font);
                        extraPara.setFontSize(9);
                        extraPara.setFontColor(new com.itextpdf.kernel.colors.DeviceRgb(102, 102, 102));
                        document.add(extraPara);
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