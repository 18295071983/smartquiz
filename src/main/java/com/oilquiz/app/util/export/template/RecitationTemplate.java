package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class RecitationTemplate implements HTMLTemplate {

    @Override
    public void writeTemplate(FileWriter writer, ExportManager.ExportTask task, List<Question> questions, List<Map.Entry<String, List<Question>>> sortedTypes) throws IOException {
        writer.write("<!DOCTYPE html>\n");
        writer.write("<html lang=\"zh-CN\">\n");
        writer.write("<head>\n");
        writer.write("<meta charset=\"UTF-8\">\n");
        writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        writer.write("<title>背诵材料</title>\n");
        writer.write("<style>\n");
        writer.write("* {\n");
        writer.write("  box-sizing: border-box;\n");
        writer.write("  margin: 0;\n");
        writer.write("  padding: 0;\n");
        writer.write("}\n");
        writer.write("body {\n");
        writer.write("  font-family: 'Microsoft YaHei', Arial, sans-serif;\n");
        writer.write("  line-height: 1.6;\n");
        writer.write("  color: #333;\n");
        writer.write("  background-color: #f5f5f5;\n");
        writer.write("  padding: 20px;\n");
        writer.write("}\n");
        writer.write(".container {\n");
        writer.write("  max-width: 900px;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("  background-color: #fff;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  box-shadow: 0 2px 10px rgba(0,0,0,0.1);\n");
        writer.write("  padding: 30px;\n");
        writer.write("}");
        writer.write("h1 {\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  padding-bottom: 15px;\n");
        writer.write("  border-bottom: 2px solid #3498db;\n");
        writer.write("}");
        writer.write(".recitation-item {\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  border: 1px solid #e0e0e0;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  background-color: #fafafa;\n");
        writer.write("  transition: all 0.3s ease;\n");
        writer.write("}");
        writer.write(".recitation-item:hover {\n");
        writer.write("  box-shadow: 0 4px 12px rgba(0,0,0,0.1);\n");
        writer.write("  transform: translateY(-2px);\n");
        writer.write("}");
        writer.write(".item-number {\n");
        writer.write("  display: inline-block;\n");
        writer.write("  background-color: #3498db;\n");
        writer.write("  color: white;\n");
        writer.write("  width: 30px;\n");
        writer.write("  height: 30px;\n");
        writer.write("  border-radius: 50%;\n");
        writer.write("  text-align: center;\n");
        writer.write("  line-height: 30px;\n");
        writer.write("  margin-right: 15px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  vertical-align: middle;\n");
        writer.write("}");
        writer.write(".question-content {\n");
        writer.write("  font-size: 16px;\n");
        writer.write("  margin-bottom: 15px;\n");
        writer.write("  line-height: 1.8;\n");
        writer.write("}");
        writer.write(".answer-section {\n");
        writer.write("  margin-top: 20px;\n");
        writer.write("  padding-top: 15px;\n");
        writer.write("  border-top: 1px dashed #e0e0e0;\n");
        writer.write("}");
        writer.write(".answer-title {\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 10px;\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("}");
        writer.write(".answer-content {\n");
        writer.write("  color: #e74c3c;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 15px;\n");
        writer.write("}");
        writer.write(".explanation-content {\n");
        writer.write("  background-color: #e3f2fd;\n");
        writer.write("  padding: 15px;\n");
        writer.write("  border-left: 4px solid #2196f3;\n");
        writer.write("  border-radius: 4px;\n");
        writer.write("  color: #1565c0;\n");
        writer.write("}");
        writer.write(".footer {\n");
        writer.write("  margin-top: 40px;\n");
        writer.write("  padding-top: 20px;\n");
        writer.write("  border-top: 2px solid #e0e0e0;\n");
        writer.write("  text-align: center;\n");
        writer.write("  color: #666;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("}");
        writer.write("@media (max-width: 768px) {\n");
        writer.write(".container {\n");
        writer.write("  padding: 20px;\n");
        writer.write("}");
        writer.write(".recitation-item {\n");
        writer.write("  padding: 15px;\n");
        writer.write("}");
        writer.write("}");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<h1>背诵材料</h1>\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"recitation-item\">\n");
                writer.write("<div class=\"question-content\"><span class=\"item-number\">" + questionNumber++ + "</span>" + question.getQuestionText() + "</div>\n");
                
                // 选项
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    writer.write("<div>A. " + question.getOptionA() + "</div>\n");
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    writer.write("<div>B. " + question.getOptionB() + "</div>\n");
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    writer.write("<div>C. " + question.getOptionC() + "</div>\n");
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    writer.write("<div>D. " + question.getOptionD() + "</div>\n");
                }
                
                writer.write("<div class=\"answer-section\">\n");
                
                // 答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"answer-title\">答案：</div>\n");
                    writer.write("<div class=\"answer-content\">" + question.getCorrectAnswer() + "</div>\n");
                }
                
                // 解析
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation-content\">\n");
                    writer.write(question.getExplanation() + "\n");
                    writer.write("</div>\n");
                }
                
                writer.write("</div>\n");
                writer.write("</div>\n");
            }
        }
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 系统</p>\n");
        writer.write("<p>生成时间：" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("<p>共 " + questions.size() + " 个题目</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
