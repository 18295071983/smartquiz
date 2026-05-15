package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;
import org.apache.poi.xwpf.usermodel.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class WordExporter implements Exporter {

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

        try (XWPFDocument document = new XWPFDocument()) {
            // 创建标题
            XWPFParagraph titlePara = document.createParagraph();
            titlePara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText("导出题目");
            titleRun.setFontSize(20);
            titleRun.setBold(true);
            titleRun.setFontFamily("宋体");
            titleRun.addBreak();
            titleRun.addBreak();
            
            // 添加导出信息
            XWPFParagraph infoPara = document.createParagraph();
            infoPara.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT);
            XWPFRun infoRun = infoPara.createRun();
            infoRun.setText("导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()));
            infoRun.setFontSize(10);
            infoRun.setFontFamily("宋体");
            infoRun.addBreak();
            infoRun.setText("导出题目总数: " + questions.size());
            infoRun.addBreak();
            infoRun.addBreak();
            
            // 按题型写入问题数据
            int total = questions.size();
            int processed = 0;
            int typeIndex = 1;
            for (java.util.Map.Entry<String, java.util.List<Question>> entry : sortedTypes) {
                String type = entry.getKey();
                java.util.List<Question> typeQuestions = entry.getValue();
                
                // 题型标题
                XWPFParagraph typePara = document.createParagraph();
                XWPFRun typeRun = typePara.createRun();
                typeRun.setText(typeIndex + "、" + type + " (" + typeQuestions.size() + "题)");
                typeRun.setFontSize(16);
                typeRun.setBold(true);
                typeRun.setFontFamily("宋体");
                typeRun.addBreak();
                typeIndex++;
                
                // 写入该题型的题目，每个题型从1开始编号
                int typeQuestionNumber = 1;
                for (Question question : typeQuestions) {
                    // 问题标题
                    if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                        XWPFParagraph questionPara = document.createParagraph();
                        XWPFRun questionRun = questionPara.createRun();
                        questionRun.setText("第" + typeQuestionNumber++ + "题: " + question.getQuestionText());
                        questionRun.setFontSize(12);
                        questionRun.setFontFamily("宋体");
                    } else {
                        typeQuestionNumber++;
                    }
                    
                    // 选项
                    if (question.hasOptions()) {
                        XWPFParagraph optionsPara = document.createParagraph();
                        XWPFRun optionsRun = optionsPara.createRun();
                        optionsRun.setText("选项:");
                        optionsRun.setFontSize(12);
                        optionsRun.setFontFamily("宋体");
                        
                        if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                            XWPFParagraph optionAPara = document.createParagraph();
                            optionAPara.setIndentationFirstLine(300);
                            XWPFRun optionARun = optionAPara.createRun();
                            optionARun.setText("A. " + question.getOptionA());
                            optionARun.setFontSize(12);
                            optionARun.setFontFamily("宋体");
                        }
                        
                        if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                            XWPFParagraph optionBPara = document.createParagraph();
                            optionBPara.setIndentationFirstLine(300);
                            XWPFRun optionBRun = optionBPara.createRun();
                            optionBRun.setText("B. " + question.getOptionB());
                            optionBRun.setFontSize(12);
                            optionBRun.setFontFamily("宋体");
                        }
                        
                        if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                            XWPFParagraph optionCPara = document.createParagraph();
                            optionCPara.setIndentationFirstLine(300);
                            XWPFRun optionCRun = optionCPara.createRun();
                            optionCRun.setText("C. " + question.getOptionC());
                            optionCRun.setFontSize(12);
                            optionCRun.setFontFamily("宋体");
                        }
                        
                        if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                            XWPFParagraph optionDPara = document.createParagraph();
                            optionDPara.setIndentationFirstLine(300);
                            XWPFRun optionDRun = optionDPara.createRun();
                            optionDRun.setText("D. " + question.getOptionD());
                            optionDRun.setFontSize(12);
                            optionDRun.setFontFamily("宋体");
                        }
                    }
                    
                    // 根据配置决定是否包含答案
                    if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                        XWPFParagraph answerPara = document.createParagraph();
                        XWPFRun answerRun = answerPara.createRun();
                        answerRun.setText("正确答案: " + question.getCorrectAnswer());
                        answerRun.setFontSize(12);
                        answerRun.setFontFamily("宋体");
                        answerRun.setColor("009900");
                    }
                    
                    // 根据配置决定是否包含解析
                    if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                        XWPFParagraph explanationPara = document.createParagraph();
                        XWPFRun explanationRun = explanationPara.createRun();
                        explanationRun.setText("解析: " + question.getExplanation());
                        explanationRun.setFontSize(12);
                        explanationRun.setFontFamily("宋体");
                        explanationRun.setColor("336699");
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
            
            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(exportFile)) {
                document.write(fos);
            }
            
            return exportFile;
        }
    }

    @Override
    public String getFormatName() {
        return "Word";
    }

    @Override
    public String getFileExtension() {
        return "docx";
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