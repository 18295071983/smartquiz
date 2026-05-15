package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class CheatSheetTemplate implements HTMLTemplate {

    @Override
    public void writeTemplate(FileWriter writer, ExportManager.ExportTask task, List<Question> questions, List<Map.Entry<String, List<Question>>> sortedTypes) throws IOException {
        writer.write("<!DOCTYPE html>\n");
        writer.write("<html lang=\"zh-CN\">\n");
        writer.write("<head>\n");
        writer.write("<meta charset=\"UTF-8\">\n");
        writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        writer.write("<title>小抄</title>\n");
        writer.write("<style>\n");
        writer.write("* {\n");
        writer.write("  box-sizing: border-box;\n");
        writer.write("  margin: 0;\n");
        writer.write("  padding: 0;\n");
        writer.write("}\n");
        writer.write("body {\n");
        writer.write("  font-family: 'Microsoft YaHei', Arial, sans-serif;\n");
        writer.write("  line-height: 1.4;\n");
        writer.write("  color: #333;\n");
        writer.write("  background-color: #fff;\n");
        writer.write("  padding: 15px;\n");
        writer.write("}\n");
        writer.write(".container {\n");
        writer.write("  max-width: 800px;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("}");
        writer.write("h1 {\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  font-size: 20px;\n");
        writer.write("  border-bottom: 1px solid #e0e0e0;\n");
        writer.write("  padding-bottom: 10px;\n");
        writer.write("}");
        writer.write(".cheat-grid {\n");
        writer.write("  display: grid;\n");
        writer.write("  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));\n");
        writer.write("  gap: 15px;\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("}");
        writer.write(".cheat-item {\n");
        writer.write("  border: 1px solid #e0e0e0;\n");
        writer.write("  border-radius: 4px;\n");
        writer.write("  padding: 12px;\n");
        writer.write("  background-color: #f9f9f9;\n");
        writer.write("  font-size: 12px;\n");
        writer.write("}");
        writer.write(".cheat-question {\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 8px;\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("}");
        writer.write(".cheat-answer {\n");
        writer.write("  color: #e74c3c;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-top: 5px;\n");
        writer.write("}");
        writer.write(".footer {\n");
        writer.write("  margin-top: 20px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  font-size: 10px;\n");
        writer.write("  color: #999;\n");
        writer.write("  border-top: 1px solid #e0e0e0;\n");
        writer.write("  padding-top: 10px;\n");
        writer.write("}");
        writer.write("@media print {\n");
        writer.write("  body {\n");
        writer.write("    padding: 10px;\n");
        writer.write("  }\n");
        writer.write(".cheat-grid {\n");
        writer.write("  gap: 10px;\n");
        writer.write("}");
        writer.write(".cheat-item {\n");
        writer.write("  padding: 8px;\n");
        writer.write("  font-size: 10px;\n");
        writer.write("}");
        writer.write("}");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<h1>小抄</h1>\n");
        
        writer.write("<div class=\"cheat-grid\">\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"cheat-item\">\n");
                writer.write("<div class=\"cheat-question\">" + questionNumber++ + ". " + question.getQuestionText() + "</div>\n");
                
                // 选项（简要显示）
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    writer.write("<div>A. " + truncateText(question.getOptionA(), 30) + "</div>\n");
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    writer.write("<div>B. " + truncateText(question.getOptionB(), 30) + "</div>\n");
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    writer.write("<div>C. " + truncateText(question.getOptionC(), 30) + "</div>\n");
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    writer.write("<div>D. " + truncateText(question.getOptionD(), 30) + "</div>\n");
                }
                
                // 答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"cheat-answer\">答案：" + question.getCorrectAnswer() + "</div>\n");
                }
                
                writer.write("</div>\n");
            }
        }
        
        writer.write("</div>\n");
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>生成时间：" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("<p>共 " + questions.size() + " 个题目</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }

    private String truncateText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
