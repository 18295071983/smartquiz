package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ReadingMaterialTemplate implements HTMLTemplate {

    @Override
    public void writeTemplate(FileWriter writer, ExportManager.ExportTask task, List<Question> questions, List<Map.Entry<String, List<Question>>> sortedTypes) throws IOException {
        writer.write("<!DOCTYPE html>\n");
        writer.write("<html lang=\"zh-CN\">\n");
        writer.write("<head>\n");
        writer.write("<meta charset=\"UTF-8\">\n");
        writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        writer.write("<title>阅读材料</title>\n");
        writer.write("<style>\n");
        writer.write("* {\n");
        writer.write("  box-sizing: border-box;\n");
        writer.write("  margin: 0;\n");
        writer.write("  padding: 0;\n");
        writer.write("}\n");
        writer.write("body {\n");
        writer.write("  font-family: 'Microsoft YaHei', Arial, sans-serif;\n");
        writer.write("  line-height: 1.8;\n");
        writer.write("  color: #333;\n");
        writer.write("  background-color: #f9f9f9;\n");
        writer.write("  padding: 20px;\n");
        writer.write("}\n");
        writer.write(".container {\n");
        writer.write("  max-width: 1000px;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("  background-color: #fff;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  box-shadow: 0 2px 10px rgba(0,0,0,0.1);\n");
        writer.write("  padding: 40px;\n");
        writer.write("}");
        writer.write("h1 {\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  padding-bottom: 20px;\n");
        writer.write("  border-bottom: 3px solid #3498db;\n");
        writer.write("  font-size: 28px;\n");
        writer.write("}");
        writer.write(".intro {\n");
        writer.write("  background-color: #f8f9fa;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  color: #666;\n");
        writer.write("}");
        writer.write(".reading-section {\n");
        writer.write("  margin-bottom: 50px;\n");
        writer.write("}");
        writer.write(".section-title {\n");
        writer.write("  font-size: 22px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("  border-bottom: 2px solid #e0e0e0;\n");
        writer.write("  padding-bottom: 10px;\n");
        writer.write("}");
        writer.write(".reading-item {\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  padding: 25px;\n");
        writer.write("  border: 1px solid #e0e0e0;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  background-color: #fafafa;\n");
        writer.write("}");
        writer.write(".item-header {\n");
        writer.write("  display: flex;\n");
        writer.write("  justify-content: space-between;\n");
        writer.write("  align-items: center;\n");
        writer.write("  margin-bottom: 15px;\n");
        writer.write("}");
        writer.write(".item-number {\n");
        writer.write("  background-color: #3498db;\n");
        writer.write("  color: white;\n");
        writer.write("  padding: 5px 12px;\n");
        writer.write("  border-radius: 15px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("}");
        writer.write(".item-type {\n");
        writer.write("  background-color: #95a5a6;\n");
        writer.write("  color: white;\n");
        writer.write("  padding: 3px 10px;\n");
        writer.write("  border-radius: 12px;\n");
        writer.write("  font-size: 12px;\n");
        writer.write("}");
        writer.write(".item-content {\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("  line-height: 1.8;\n");
        writer.write("  text-align: justify;\n");
        writer.write("}");
        writer.write(".options {\n");
        writer.write("  margin: 20px 0;\n");
        writer.write("  padding-left: 20px;\n");
        writer.write("}");
        writer.write(".option {\n");
        writer.write("  margin: 10px 0;\n");
        writer.write("  padding: 10px;\n");
        writer.write("  background-color: #f9f9f9;\n");
        writer.write("  border-radius: 4px;\n");
        writer.write("  border-left: 3px solid #e0e0e0;\n");
        writer.write("}");
        writer.write(".answer-box {\n");
        writer.write("  margin-top: 20px;\n");
        writer.write("  padding: 15px;\n");
        writer.write("  background-color: #d4edda;\n");
        writer.write("  border: 1px solid #c3e6cb;\n");
        writer.write("  border-radius: 4px;\n");
        writer.write("  color: #155724;\n");
        writer.write("}");
        writer.write(".explanation-box {\n");
        writer.write("  margin-top: 15px;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  background-color: #e3f2fd;\n");
        writer.write("  border-left: 4px solid #2196f3;\n");
        writer.write("  border-radius: 4px;\n");
        writer.write("  color: #1565c0;\n");
        writer.write("}");
        writer.write(".explanation-title {\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 10px;\n");
        writer.write("  color: #0d47a1;\n");
        writer.write("}");
        writer.write(".footer {\n");
        writer.write("  margin-top: 50px;\n");
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
        writer.write("h1 {\n");
        writer.write("  font-size: 24px;\n");
        writer.write("}");
        writer.write(".section-title {\n");
        writer.write("  font-size: 20px;\n");
        writer.write("}");
        writer.write(".item-header {\n");
        writer.write("  flex-direction: column;\n");
        writer.write("  align-items: flex-start;\n");
        writer.write("  gap: 10px;\n");
        writer.write("}");
        writer.write("}");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<h1>阅读材料</h1>\n");
        
        writer.write("<div class=\"intro\">\n");
        writer.write("<p><strong>说明：</strong>适合阅读的学习材料，包含详细的题目和解答</p>\n");
        writer.write("<p><strong>生成时间：</strong>" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("<p><strong>内容数量：</strong>" + questions.size() + " 个题目</p>\n");
        writer.write("<p><strong>建议：</strong>仔细阅读每个题目，理解其含义和解答思路</p>\n");
        writer.write("</div>\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            String type = entry.getKey();
            List<Question> typeQuestions = entry.getValue();
            
            writer.write("<div class=\"reading-section\">\n");
            writer.write("<div class=\"section-title\">" + type + "（" + typeQuestions.size() + "题）</div>\n");
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"reading-item\">\n");
                writer.write("<div class=\"item-header\">\n");
                writer.write("<span class=\"item-number\">第 " + questionNumber++ + " 题</span>\n");
                writer.write("<span class=\"item-type\">" + type + "</span>\n");
                writer.write("</div>\n");
                
                writer.write("<div class=\"item-content\">" + question.getQuestionText() + "</div>\n");
                
                writer.write("<div class=\"options\">\n");
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    writer.write("<div class=\"option\">A. " + question.getOptionA() + "</div>\n");
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    writer.write("<div class=\"option\">B. " + question.getOptionB() + "</div>\n");
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    writer.write("<div class=\"option\">C. " + question.getOptionC() + "</div>\n");
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    writer.write("<div class=\"option\">D. " + question.getOptionD() + "</div>\n");
                }
                writer.write("</div>\n");
                
                // 答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"answer-box\">\n");
                    writer.write("<strong>正确答案：</strong>" + question.getCorrectAnswer() + "\n");
                    writer.write("</div>\n");
                }
                
                // 解析
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation-box\">\n");
                    writer.write("<div class=\"explanation-title\">解析</div>\n");
                    writer.write(question.getExplanation() + "\n");
                    writer.write("</div>\n");
                }
                
                writer.write("</div>\n");
            }
            
            writer.write("</div>\n");
        }
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 系统</p>\n");
        writer.write("<p>本阅读材料由系统自动生成</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
