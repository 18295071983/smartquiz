package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class PrintMaterialTemplate implements HTMLTemplate {

    @Override
    public void writeTemplate(FileWriter writer, ExportManager.ExportTask task, List<Question> questions, List<Map.Entry<String, List<Question>>> sortedTypes) throws IOException {
        writer.write("<!DOCTYPE html>\n");
        writer.write("<html lang=\"zh-CN\">\n");
        writer.write("<head>\n");
        writer.write("<meta charset=\"UTF-8\">\n");
        writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        writer.write("<title>打印材料</title>\n");
        writer.write("<style>\n");
        writer.write("* {\n");
        writer.write("  box-sizing: border-box;\n");
        writer.write("  margin: 0;\n");
        writer.write("  padding: 0;\n");
        writer.write("}\n");
        writer.write("body {\n");
        writer.write("  font-family: 'SimSun', '宋体', serif;\n");
        writer.write("  line-height: 1.6;\n");
        writer.write("  color: #000;\n");
        writer.write("  background-color: #fff;\n");
        writer.write("  padding: 20px;\n");
        writer.write("}");
        writer.write(".container {\n");
        writer.write("  max-width: 210mm;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("  min-height: 297mm;\n");
        writer.write("  padding: 20mm;\n");
        writer.write("}");
        writer.write("h1 {\n");
        writer.write("  text-align: center;\n");
        writer.write("  font-size: 24px;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("}");
        writer.write(".header {\n");
        writer.write("  text-align: right;\n");
        writer.write("  font-size: 12px;\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("  color: #666;\n");
        writer.write("}");
        writer.write(".question {\n");
        writer.write("  margin-bottom: 25px;\n");
        writer.write("  page-break-inside: avoid;\n");
        writer.write("}");
        writer.write(".question-number {\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-right: 10px;\n");
        writer.write("}");
        writer.write(".question-text {\n");
        writer.write("  margin-bottom: 10px;\n");
        writer.write("  text-align: justify;\n");
        writer.write("}");
        writer.write(".options {\n");
        writer.write("  margin-left: 20px;\n");
        writer.write("  margin-bottom: 10px;\n");
        writer.write("}");
        writer.write(".option {\n");
        writer.write("  margin: 5px 0;\n");
        writer.write("}");
        writer.write(".option-letter {\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-right: 10px;\n");
        writer.write("}");
        writer.write(".answer {\n");
        writer.write("  margin-top: 10px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  color: #e74c3c;\n");
        writer.write("}");
        writer.write(".explanation {\n");
        writer.write("  margin-top: 10px;\n");
        writer.write("  padding: 10px;\n");
        writer.write("  background-color: #f9f9f9;\n");
        writer.write("  border-left: 3px solid #3498db;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("}");
        writer.write(".explanation-title {\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 5px;\n");
        writer.write("}");
        writer.write(".footer {\n");
        writer.write("  margin-top: 40px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  font-size: 12px;\n");
        writer.write("  color: #666;\n");
        writer.write("  border-top: 1px solid #e0e0e0;\n");
        writer.write("  padding-top: 10px;\n");
        writer.write("}");
        writer.write("@media print {\n");
        writer.write("  body {\n");
        writer.write("    padding: 0;\n");
        writer.write("  }\n");
        writer.write(".container {\n");
        writer.write("  padding: 20mm;\n");
        writer.write("  box-shadow: none;\n");
        writer.write("}\n");
        writer.write("@page {\n");
        writer.write("  size: A4;\n");
        writer.write("  margin: 20mm;\n");
        writer.write("}\n");
        writer.write("}");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<h1>打印材料</h1>\n");
        
        writer.write("<div class=\"header\">\n");
        writer.write("<p>生成时间：" + new java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("<p>题目数量：" + questions.size() + " 题</p>\n");
        writer.write("</div>\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"question\">\n");
                writer.write("<div class=\"question-text\"><span class=\"question-number\">" + questionNumber++ + ".</span>" + question.getQuestionText() + "</div>\n");
                
                writer.write("<div class=\"options\">\n");
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    writer.write("<div class=\"option\"><span class=\"option-letter\">A.</span>" + question.getOptionA() + "</div>\n");
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    writer.write("<div class=\"option\"><span class=\"option-letter\">B.</span>" + question.getOptionB() + "</div>\n");
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    writer.write("<div class=\"option\"><span class=\"option-letter\">C.</span>" + question.getOptionC() + "</div>\n");
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    writer.write("<div class=\"option\"><span class=\"option-letter\">D.</span>" + question.getOptionD() + "</div>\n");
                }
                writer.write("</div>\n");
                
                // 答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"answer\">正确答案：" + question.getCorrectAnswer() + "</div>\n");
                }
                
                // 解析
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation\">\n");
                    writer.write("<div class=\"explanation-title\">解析：</div>\n");
                    writer.write(question.getExplanation() + "\n");
                    writer.write("</div>\n");
                }
                
                writer.write("</div>\n");
            }
        }
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 系统</p>\n");
        writer.write("<p>本材料由系统自动生成</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
