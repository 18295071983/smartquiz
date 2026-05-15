package com.oilquiz.app.util.export;

import android.content.Context;
import android.content.res.AssetManager;

import com.oilquiz.app.model.Question;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class ContentTemplateExporter implements Exporter {

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
        
        // 根据选择的导出格式确定文件扩展名
        String fileExtension = getFileExtensionForFormat(task.getConfig().getFormat());
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + fileExtension);

        // 读取内容模板文件
        String templateContent = readTemplateFile(task.getConfig().getContentTemplateFilePath(), task.getContext(), task.getConfig().getFormat());

        // 生成内容
        String content = generateContent(templateContent, questions, task);

        // 写入文件
        if (task.getConfig().getFormat() == ExportManager.ExportFormat.LONG_IMAGE) {
            // 对于长图片格式，需要特殊处理
            // 这里应该使用LongImageExporter来处理
            LongImageExporter imageExporter = new LongImageExporter();
            return imageExporter.export(task);
        } else {
            // 对于其他格式，使用FileWriter
            try (FileWriter writer = new FileWriter(exportFile)) {
                writer.write(content);
            }
            return exportFile;
        }
    }
    
    private String getFileExtensionForFormat(ExportManager.ExportFormat format) {
        switch (format) {
            case CSV:
                return "csv";
            case EXCEL:
                return "xlsx";
            case PDF:
                return "pdf";
            case WORD:
                return "docx";
            case HTML:
            case ENHANCED_HTML:
                return "html";
            case MARKDOWN:
                return "md";
            case JSON:
                return "json";
            case LONG_IMAGE:
                return "png";
            default:
                return "html";
        }
    }

    private String readTemplateFile(String templateFilePath, Context context, ExportManager.ExportFormat format) throws IOException {
        // 尝试从 assets 目录读取模板文件
        try {
            // 移除 assets/ 前缀（如果存在）
            String assetPath = templateFilePath.replace("assets/", "");
            try (InputStream inputStream = context.getAssets().open(assetPath);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                return content.toString();
            }
        } catch (IOException e) {
            // 如果从 assets 读取失败，使用默认模板
            return getDefaultTemplate(format);
        }
    }

    private String getDefaultTemplate(ExportManager.ExportFormat format) {
        switch (format) {
            case MARKDOWN:
                return "# 导出题目\n\n" +
                        "## 导出信息\n\n" +
                        "- 导出时间: {{exportTime}}\n" +
                        "- 题目数量: {{questionCount}}\n\n" +
                        "{{questions}}\n" +
                        "\n---\n\n" +
                        "## 导出完成";
            case JSON:
                return "{\n" +
                        "  \"exportInfo\": {\n" +
                        "    \"exportTime\": \"{{exportTime}}\",\n" +
                        "    \"questionCount\": {{questionCount}}\n" +
                        "  },\n" +
                        "  \"questions\": [\n" +
                        "{{questions}}\n" +
                        "  ]\n" +
                        "}";
            case CSV:
                return "题目编号,题目类型,题目内容,选项A,选项B,选项C,选项D,正确答案,解析\n" +
                        "{{questions}}";
            default:
                // 默认返回HTML模板
                return "<!DOCTYPE html>\n" +
                        "<html lang=\"zh-CN\">\n" +
                        "<head>\n" +
                        "<meta charset=\"UTF-8\">\n" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                        "<title>导出题目</title>\n" +
                        "<style>\n" +
                        "* {\n" +
                        "  box-sizing: border-box;\n" +
                        "  margin: 0;\n" +
                        "  padding: 0;\n" +
                        "}\n" +
                        "body {\n" +
                        "  font-family: 'Microsoft YaHei', Arial, sans-serif;\n" +
                        "  line-height: 1.6;\n" +
                        "  color: #333;\n" +
                        "  background-color: #f5f5f5;\n" +
                        "  padding: 20px;\n" +
                        "}\n" +
                        ".container {\n" +
                        "  max-width: 1000px;\n" +
                        "  margin: 0 auto;\n" +
                        "  background-color: #fff;\n" +
                        "  border-radius: 8px;\n" +
                        "  box-shadow: 0 2px 10px rgba(0,0,0,0.1);\n" +
                        "  padding: 30px;\n" +
                        "}\n" +
                        "h1 {\n" +
                        "  color: #2c3e50;\n" +
                        "  margin-bottom: 30px;\n" +
                        "  text-align: center;\n" +
                        "  padding-bottom: 15px;\n" +
                        "  border-bottom: 2px solid #3498db;\n" +
                        "}\n" +
                        ".info {\n" +
                        "  background-color: #f8f9fa;\n" +
                        "  padding: 20px;\n" +
                        "  border-radius: 8px;\n" +
                        "  margin-bottom: 30px;\n" +
                        "  border: 1px solid #e9ecef;\n" +
                        "}\n" +
                        ".question {\n" +
                        "  margin-bottom: 30px;\n" +
                        "  padding: 20px;\n" +
                        "  border: 1px solid #e0e0e0;\n" +
                        "  border-radius: 8px;\n" +
                        "  background-color: #fafafa;\n" +
                        "  transition: all 0.3s ease;\n" +
                        "}\n" +
                        ".question:hover {\n" +
                        "  box-shadow: 0 2px 8px rgba(0,0,0,0.1);\n" +
                        "  transform: translateY(-2px);\n" +
                        "}\n" +
                        ".question h3 {\n" +
                        "  color: #2c3e50;\n" +
                        "  margin-bottom: 15px;\n" +
                        "  font-size: 18px;\n" +
                        "}\n" +
                        ".question-text {\n" +
                        "  font-size: 16px;\n" +
                        "  margin-bottom: 15px;\n" +
                        "  line-height: 1.6;\n" +
                        "}\n" +
                        ".options {\n" +
                        "  margin: 15px 0;\n" +
                        "  padding-left: 20px;\n" +
                        "}\n" +
                        ".options p {\n" +
                        "  margin: 10px 0;\n" +
                        "  padding: 5px 0;\n" +
                        "}\n" +
                        ".correct-answer {\n" +
                        "  margin-top: 15px;\n" +
                        "  padding: 10px;\n" +
                        "  background-color: #d4edda;\n" +
                        "  border: 1px solid #c3e6cb;\n" +
                        "  border-radius: 4px;\n" +
                        "  color: #155724;\n" +
                        "  font-weight: 600;\n" +
                        "}\n" +
                        ".explanation {\n" +
                        "  margin-top: 15px;\n" +
                        "  padding: 15px;\n" +
                        "  background-color: #e3f2fd;\n" +
                        "  border-left: 4px solid #2196f3;\n" +
                        "  border-radius: 4px;\n" +
                        "  color: #1565c0;\n" +
                        "}\n" +
                        ".footer {\n" +
                        "  margin-top: 40px;\n" +
                        "  padding-top: 20px;\n" +
                        "  border-top: 1px solid #e0e0e0;\n" +
                        "  text-align: center;\n" +
                        "  color: #666;\n" +
                        "  font-size: 14px;\n" +
                        "}\n" +
                        "</style>\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "<div class=\"container\">\n" +
                        "<h1>导出题目</h1>\n" +
                        "<div class=\"info\">\n" +
                        "<p>导出时间: {{exportTime}}</p>\n" +
                        "<p>题目数量: {{questionCount}}</p>\n" +
                        "</div>\n" +
                        "{{questions}}\n" +
                        "<div class=\"footer\">\n" +
                        "<p>导出完成</p>\n" +
                        "</div>\n" +
                        "</div>\n" +
                        "</body>\n" +
                        "</html>";
        }
    }

    private String generateContent(String template, List<Question> questions, ExportManager.ExportTask task) {
        // 替换模板中的占位符
        String content = template;

        // 替换题目数量
        content = content.replace("{{questionCount}}", String.valueOf(questions.size()));

        // 替换导出时间
        String exportTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
        content = content.replace("{{exportTime}}", exportTime);

        // 生成题目列表
        StringBuilder questionsContent = new StringBuilder();
        int questionNumber = 1;
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            questionsContent.append(generateQuestionContent(question, questionNumber++, task.getConfig().getFormat()));

            // 更新进度
            if (task.getCallback() != null && i % 10 == 0) {
                int progress = (int) ((i + 1) * 100.0 / questions.size());
                task.getCallback().onExportProgress(progress);
            }
        }

        // 替换题目列表
        content = content.replace("{{questions}}", questionsContent.toString());

        return content;
    }

    private String generateQuestionContent(Question question, int questionNumber, ExportManager.ExportFormat format) {
        switch (format) {
            case MARKDOWN:
                return generateQuestionMarkdown(question, questionNumber);
            case JSON:
                return generateQuestionJson(question, questionNumber);
            case CSV:
                return generateQuestionCsv(question, questionNumber);
            default:
                // 默认生成HTML格式
                return generateQuestionHtml(question, questionNumber);
        }
    }

    private String generateQuestionHtml(Question question, int questionNumber) {
        StringBuilder html = new StringBuilder();

        html.append("<div class=\"question\">\n");
        html.append("<h3>第").append(questionNumber).append("题")
                .append(question.getQuestionType() != null ? " (" + question.getQuestionType() + ")" : "").append("</h3>\n");

        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            html.append("<p class=\"question-text\">").append(question.getQuestionText()).append("</p>\n");
        }

        if (question.hasOptions()) {
            html.append("<div class=\"options\">\n");
            if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                html.append("<p>A. " + question.getOptionA() + "</p>\n");
            }
            if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                html.append("<p>B. " + question.getOptionB() + "</p>\n");
            }
            if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                html.append("<p>C. " + question.getOptionC() + "</p>\n");
            }
            if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                html.append("<p>D. " + question.getOptionD() + "</p>\n");
            }
            html.append("</div>\n");
        }

        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            html.append("<p class=\"correct-answer\">正确答案: " + question.getCorrectAnswer() + "</p>\n");
        }

        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            html.append("<p class=\"explanation\">解析: " + question.getExplanation() + "</p>\n");
        }

        html.append("</div>\n\n");

        return html.toString();
    }

    private String generateQuestionMarkdown(Question question, int questionNumber) {
        StringBuilder markdown = new StringBuilder();

        markdown.append("### 第 " + questionNumber + " 题\n\n");

        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            markdown.append("**题目内容:** " + question.getQuestionText() + "\n\n");
        }

        if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
            markdown.append("**题目类型:** " + question.getQuestionType() + "\n\n");
        }

        if (question.hasOptions()) {
            markdown.append("**选项:**\n\n");
            if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                markdown.append("- A. " + question.getOptionA() + "\n");
            }
            if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                markdown.append("- B. " + question.getOptionB() + "\n");
            }
            if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                markdown.append("- C. " + question.getOptionC() + "\n");
            }
            if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                markdown.append("- D. " + question.getOptionD() + "\n");
            }
            markdown.append("\n");
        }

        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            markdown.append("**正确答案:** " + question.getCorrectAnswer() + "\n\n");
        }

        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            markdown.append("**解析:**\n\n" + question.getExplanation() + "\n\n");
        }

        markdown.append("---\n\n");

        return markdown.toString();
    }

    private String generateQuestionJson(Question question, int questionNumber) {
        StringBuilder json = new StringBuilder();

        json.append("    {\n");
        json.append("      \"questionNumber\": " + questionNumber + ",\n");
        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            json.append("      \"questionText\": \"" + question.getQuestionText().replace("\"", "\\\"") + "\",\n");
        }
        if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
            json.append("      \"questionType\": \"" + question.getQuestionType() + "\",\n");
        }
        if (question.hasOptions()) {
            json.append("      \"options\": {\n");
            if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                json.append("        \"A\": \"" + question.getOptionA().replace("\"", "\\\"") + "\",\n");
            }
            if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                json.append("        \"B\": \"" + question.getOptionB().replace("\"", "\\\"") + "\",\n");
            }
            if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                json.append("        \"C\": \"" + question.getOptionC().replace("\"", "\\\"") + "\",\n");
            }
            if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                json.append("        \"D\": \"" + question.getOptionD().replace("\"", "\\\"") + "\"\n");
            }
            json.append("      },\n");
        }
        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            json.append("      \"correctAnswer\": \"" + question.getCorrectAnswer() + "\",\n");
        }
        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            json.append("      \"explanation\": \"" + question.getExplanation().replace("\"", "\\\"") + "\"\n");
        }
        json.append("    },\n");

        return json.toString();
    }

    private String generateQuestionCsv(Question question, int questionNumber) {
        StringBuilder csv = new StringBuilder();

        // 题目编号
        csv.append(questionNumber).append(",");

        // 题目类型
        if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
            csv.append("\"").append(question.getQuestionType().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 题目内容
        if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
            csv.append("\"").append(question.getQuestionText().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项A
        if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
            csv.append("\"").append(question.getOptionA().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项B
        if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
            csv.append("\"").append(question.getOptionB().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项C
        if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
            csv.append("\"").append(question.getOptionC().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 选项D
        if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
            csv.append("\"").append(question.getOptionD().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 正确答案
        if (question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
            csv.append("\"").append(question.getCorrectAnswer().replace("\"", "\"\"").replace("\n", " ")).append("\"").append(",");
        } else {
            csv.append(",");
        }

        // 解析
        if (question.getExplanation() != null && !question.getExplanation().isEmpty()) {
            csv.append("\"").append(question.getExplanation().replace("\"", "\"\"").replace("\n", " ")).append("\"");
        }

        csv.append("\n");

        return csv.toString();
    }

    @Override
    public String getFormatName() {
        return "Content Template";
    }

    @Override
    public String getFileExtension() {
        return "html";
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

        if (!task.getConfig().isContentTemplateMode()) {
            throw new IllegalArgumentException("此导出器仅用于内容模板");
        }

        if (task.getConfig().getContentTemplateFilePath() == null || task.getConfig().getContentTemplateFilePath().isEmpty()) {
            throw new IllegalArgumentException("内容模板文件路径不能为空");
        }
    }
}
