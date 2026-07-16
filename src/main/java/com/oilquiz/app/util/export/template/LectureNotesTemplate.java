package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class LectureNotesTemplate implements HTMLTemplate {

    @Override
    public void writeTemplate(FileWriter writer, ExportManager.ExportTask task, List<Question> questions, List<Map.Entry<String, List<Question>>> sortedTypes) throws IOException {
        writer.write("<!DOCTYPE html>\n");
        writer.write("<html lang=\"zh-CN\">\n");
        writer.write("<head>\n");
        writer.write("<meta charset=\"UTF-8\">\n");
        writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        writer.write("<title>讲义</title>\n");
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
        writer.write("  max-width: 1200px;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("  background-color: #fff;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  box-shadow: 0 2px 10px rgba(0,0,0,0.1);\n");
        writer.write("  padding: 40px;\n");
        writer.write("}\n");
        writer.write("h1 {\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("  margin-bottom: 40px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  padding-bottom: 20px;\n");
        writer.write("  border-bottom: 3px solid #3498db;\n");
        writer.write("  font-size: 28px;\n");
        writer.write("}\n");
        writer.write(".header-info {\n");
        writer.write("  background-color: #f8f9fa;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  border-left: 4px solid #3498db;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  color: #666;\n");
        writer.write("}\n");
        writer.write(".section {\n");
        writer.write("  margin-bottom: 50px;\n");
        writer.write("}\n");
        writer.write(".section-title {\n");
        writer.write("  background-color: #3498db;\n");
        writer.write("  color: white;\n");
        writer.write("  padding: 15px 20px;\n");
        writer.write("  border-radius: 8px 8px 0 0;\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("  font-size: 20px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("}\n");
        writer.write(".topic {\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  padding: 25px;\n");
        writer.write("  border: 1px solid #e0e0e0;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  background-color: #fafafa;\n");
        writer.write("}");
        writer.write(".topic-title {\n");
        writer.write("  font-size: 18px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 15px;\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("  border-bottom: 2px solid #e0e0e0;\n");
        writer.write("  padding-bottom: 10px;\n");
        writer.write("}");
        writer.write(".content {\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("  line-height: 1.8;\n");
        writer.write("}");
        writer.write(".explanation {\n");
        writer.write("  background-color: #e3f2fd;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  border-left: 4px solid #2196f3;\n");
        writer.write("  border-radius: 4px;\n");
        writer.write("  margin-top: 15px;\n");
        writer.write("  color: #1565c0;\n");
        writer.write("}");
        writer.write(".explanation h4 {\n");
        writer.write("  margin-bottom: 10px;\n");
        writer.write("  color: #0d47a1;\n");
        writer.write("  font-size: 16px;\n");
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
        writer.write("  font-size: 18px;\n");
        writer.write("}");
        writer.write("}");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<h1>讲义</h1>\n");
        
        // 头部信息
        writer.write("<div class=\"header-info\">\n");
        writer.write("<p><strong>说明：</strong>详细的学习讲义，包含完整的题目、选项、答案和解析</p>\n");
        writer.write("<p><strong>导出时间：</strong>" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("<p><strong>内容数量：</strong>" + questions.size() + " 个题目</p>\n");
        writer.write("<p><strong>题型数量：</strong>" + sortedTypes.size() + " 种题型</p>\n");
        writer.write("</div>\n");
        
        // 按题型分组显示
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            String type = entry.getKey();
            List<Question> typeQuestions = entry.getValue();
            
            writer.write("<div class=\"section\">\n");
            writer.write("<div class=\"section-title\">" + type + "（" + typeQuestions.size() + "题）</div>\n");
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"topic\">\n");
                writer.write("<div class=\"topic-title\">第 " + questionNumber++ + " 题：" + question.getQuestionText() + "</div>\n");
                
                // 选项
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    writer.write("<div class=\"content\">A. " + question.getOptionA() + "</div>\n");
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    writer.write("<div class=\"content\">B. " + question.getOptionB() + "</div>\n");
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    writer.write("<div class=\"content\">C. " + question.getOptionC() + "</div>\n");
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    writer.write("<div class=\"content\">D. " + question.getOptionD() + "</div>\n");
                }
                
                // 答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"content\"><strong>正确答案：</strong>" + question.getCorrectAnswer() + "</div>\n");
                }
                
                // 解析
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation\">\n");
                    writer.write("<h4>解析</h4>\n");
                    writer.write(question.getExplanation() + "\n");
                    writer.write("</div>\n");
                }
                
                writer.write("</div>\n");
            }
            
            writer.write("</div>\n");
        }
        
        // 页脚
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 系统</p>\n");
        writer.write("<p>本讲义由系统自动生成，用于学习参考</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
