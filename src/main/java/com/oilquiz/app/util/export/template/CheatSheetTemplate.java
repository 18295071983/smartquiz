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
        writer.write("  background-color: #F3F4F6;\n");
        writer.write("  padding: 15px;\n");
        writer.write("}\n");
        writer.write(".container {\n");
        writer.write("  max-width: 1200px;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("}\n");
        writer.write(".header {\n");
        writer.write("  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        writer.write("  color: white;\n");
        writer.write("  padding: 20px 24px;\n");
        writer.write("  border-radius: 12px;\n");
        writer.write("  margin-bottom: 24px;\n");
        writer.write("  text-align: center;\n");
        writer.write("}\n");
        writer.write(".header h1 {\n");
        writer.write("  font-size: 22px;\n");
        writer.write("  margin-bottom: 8px;\n");
        writer.write("}\n");
        writer.write(".header p {\n");
        writer.write("  font-size: 13px;\n");
        writer.write("  opacity: 0.9;\n");
        writer.write("}\n");
        writer.write(".cheat-grid {\n");
        writer.write("  display: grid;\n");
        writer.write("  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));\n");
        writer.write("  gap: 12px;\n");
        writer.write("  margin-bottom: 20px;\n");
        writer.write("}\n");
        writer.write(".cheat-item {\n");
        writer.write("  background: white;\n");
        writer.write("  border-radius: 10px;\n");
        writer.write("  padding: 16px;\n");
        writer.write("  box-shadow: 0 2px 4px rgba(0,0,0,0.05);\n");
        writer.write("  transition: all 0.2s ease;\n");
        writer.write("}\n");
        writer.write(".cheat-item:hover {\n");
        writer.write("  box-shadow: 0 4px 12px rgba(0,0,0,0.1);\n");
        writer.write("  transform: translateY(-2px);\n");
        writer.write("}\n");
        writer.write(".cheat-number {\n");
        writer.write("  display: inline-block;\n");
        writer.write("  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        writer.write("  color: white;\n");
        writer.write("  width: 26px;\n");
        writer.write("  height: 26px;\n");
        writer.write("  border-radius: 50%;\n");
        writer.write("  text-align: center;\n");
        writer.write("  line-height: 26px;\n");
        writer.write("  font-size: 13px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-right: 8px;\n");
        writer.write("  vertical-align: middle;\n");
        writer.write("}\n");
        writer.write(".cheat-question {\n");
        writer.write("  font-weight: 600;\n");
        writer.write("  margin-bottom: 10px;\n");
        writer.write("  color: #1f2937;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  line-height: 1.5;\n");
        writer.write("}\n");
        writer.write(".cheat-option {\n");
        writer.write("  font-size: 13px;\n");
        writer.write("  color: #6b7280;\n");
        writer.write("  margin: 3px 0;\n");
        writer.write("  padding-left: 4px;\n");
        writer.write("}\n");
        writer.write(".cheat-answer {\n");
        writer.write("  background: #FEF3C7;\n");
        writer.write("  color: #92400E;\n");
        writer.write("  padding: 8px 12px;\n");
        writer.write("  border-radius: 6px;\n");
        writer.write("  font-weight: 600;\n");
        writer.write("  margin-top: 10px;\n");
        writer.write("  font-size: 13px;\n");
        writer.write("  border-left: 3px solid #F59E0B;\n");
        writer.write("}\n");
        writer.write(".footer {\n");
        writer.write("  margin-top: 24px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  font-size: 12px;\n");
        writer.write("  color: #9CA3AF;\n");
        writer.write("  padding: 16px;\n");
        writer.write("  background: white;\n");
        writer.write("  border-radius: 10px;\n");
        writer.write("}\n");
        writer.write("@media print {\n");
        writer.write("  body {\n");
        writer.write("    background: white;\n");
        writer.write("    padding: 10px;\n");
        writer.write("  }\n");
        writer.write(".header {\n");
        writer.write("  background: #667eea;\n");
        writer.write("  color: white;\n");
        writer.write("  -webkit-print-color-adjust: exact;\n");
        writer.write("}\n");
        writer.write(".cheat-grid {\n");
        writer.write("  gap: 8px;\n");
        writer.write("}\n");
        writer.write(".cheat-item {\n");
        writer.write("  padding: 10px;\n");
        writer.write("  break-inside: avoid;\n");
        writer.write("}\n");
        writer.write("}\n");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<div class=\"header\">\n");
        writer.write("<h1>📝 小抄</h1>\n");
        writer.write("<p>共 " + questions.size() + " 个题目 | 生成时间：" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("</div>\n");
        
        writer.write("<div class=\"cheat-grid\">\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"cheat-item\">\n");
                writer.write("<div class=\"cheat-question\"><span class=\"cheat-number\">" + questionNumber++ + "</span>" + question.getQuestionText() + "</div>\n");
                
                // 选项（简要显示）
                if (question.getOptionA() != null && !question.getOptionA().isEmpty()) {
                    writer.write("<div class=\"cheat-option\">A. " + truncateText(question.getOptionA(), 35) + "</div>\n");
                }
                if (question.getOptionB() != null && !question.getOptionB().isEmpty()) {
                    writer.write("<div class=\"cheat-option\">B. " + truncateText(question.getOptionB(), 35) + "</div>\n");
                }
                if (question.getOptionC() != null && !question.getOptionC().isEmpty()) {
                    writer.write("<div class=\"cheat-option\">C. " + truncateText(question.getOptionC(), 35) + "</div>\n");
                }
                if (question.getOptionD() != null && !question.getOptionD().isEmpty()) {
                    writer.write("<div class=\"cheat-option\">D. " + truncateText(question.getOptionD(), 35) + "</div>\n");
                }
                
                // 答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"cheat-answer\">✅ 答案：" + question.getCorrectAnswer() + "</div>\n");
                }
                
                writer.write("</div>\n");
            }
        }
        
        writer.write("</div>\n");
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© OilQuiz 系统 | 本材料由系统自动生成</p>\n");
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
