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
        // 模板生效字段列表（场景模板/自定义字段），决定导出内容
        List<String> fields = ExportUtils.getEffectiveFields(task.getConfig());
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
                    // 问题标题（含难度/知识点等元信息）
                    if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                        XWPFParagraph questionPara = document.createParagraph();
                        XWPFRun questionRun = questionPara.createRun();
                        StringBuilder questionTitle = new StringBuilder("第").append(typeQuestionNumber++).append("题: ").append(question.getQuestionText());
                        questionRun.setText(questionTitle.toString());
                        questionRun.setFontSize(12);
                        questionRun.setFontFamily("宋体");
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
                        XWPFParagraph metaPara = document.createParagraph();
                        XWPFRun metaRun = metaPara.createRun();
                        metaRun.setText(metaInfo.substring(0, metaInfo.length() - 3));
                        metaRun.setFontSize(10);
                        metaRun.setFontFamily("宋体");
                        metaRun.setColor("888888");
                    }
                    
                    // 选项（A~L 动态渲染，只输出模板选中且非空的选项）
                    if (question.hasOptions()) {
                        for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                            if (!ExportUtils.hasField(fields, ExportUtils.OPTION_FIELDS[o])) continue;
                            Object optionValue = ExportUtils.getOptionValue(question, o);
                            if (optionValue == null || optionValue.toString().isEmpty()) continue;
                            XWPFParagraph optionPara = document.createParagraph();
                            optionPara.setIndentationFirstLine(300);
                            XWPFRun optionRun = optionPara.createRun();
                            optionRun.setText(ExportUtils.OPTION_LABELS[o] + ". " + optionValue);
                            optionRun.setFontSize(12);
                            optionRun.setFontFamily("宋体");
                        }
                    }
                    
                    // 根据配置决定是否包含答案（正确答案 + 答案文本）
                    if (includeAnswers) {
                        if (ExportUtils.hasField(fields, "correctAnswer") && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                            XWPFParagraph answerPara = document.createParagraph();
                            XWPFRun answerRun = answerPara.createRun();
                            answerRun.setText("正确答案: " + question.getCorrectAnswer());
                            answerRun.setFontSize(12);
                            answerRun.setFontFamily("宋体");
                            answerRun.setColor("009900");
                        }
                        if (ExportUtils.hasField(fields, "answerText") && question.getAnswerText() != null && !question.getAnswerText().isEmpty()) {
                            XWPFParagraph answerTextPara = document.createParagraph();
                            XWPFRun answerTextRun = answerTextPara.createRun();
                            answerTextRun.setText("答案文本: " + question.getAnswerText());
                            answerTextRun.setFontSize(12);
                            answerTextRun.setFontFamily("宋体");
                            answerTextRun.setColor("009900");
                        }
                    }
                    
                    // 根据配置决定是否包含解析（解析 + 详细解析）
                    if (includeExplanations && ExportUtils.hasField(fields, "explanation") && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                        XWPFParagraph explanationPara = document.createParagraph();
                        XWPFRun explanationRun = explanationPara.createRun();
                        explanationRun.setText("解析: " + question.getExplanation());
                        explanationRun.setFontSize(12);
                        explanationRun.setFontFamily("宋体");
                        explanationRun.setColor("336699");
                    }
                    if (includeExplanations && ExportUtils.hasField(fields, "analysis") && question.getAnalysis() != null && !question.getAnalysis().isEmpty()) {
                        XWPFParagraph analysisPara = document.createParagraph();
                        XWPFRun analysisRun = analysisPara.createRun();
                        analysisRun.setText("详细解析: " + question.getAnalysis());
                        analysisRun.setFontSize(12);
                        analysisRun.setFontFamily("宋体");
                        analysisRun.setColor("336699");
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
                        XWPFParagraph extraPara = document.createParagraph();
                        XWPFRun extraRun = extraPara.createRun();
                        extraRun.setText(extraInfo.toString().trim());
                        extraRun.setFontSize(10);
                        extraRun.setFontFamily("宋体");
                        extraRun.setColor("666666");
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