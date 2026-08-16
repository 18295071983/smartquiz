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
        writer.write("  background-color: #F3F4F6;\n");
        writer.write("  padding: 20px;\n");
        writer.write("}\n");
        writer.write(".container {\n");
        writer.write("  max-width: 900px;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("}\n");
        writer.write(".header {\n");
        writer.write("  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        writer.write("  color: white;\n");
        writer.write("  padding: 24px;\n");
        writer.write("  border-radius: 12px;\n");
        writer.write("  margin-bottom: 24px;\n");
        writer.write("  text-align: center;\n");
        writer.write("}\n");
        writer.write(".header h1 {\n");
        writer.write("  font-size: 24px;\n");
        writer.write("  margin-bottom: 8px;\n");
        writer.write("}\n");
        writer.write(".header p {\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  opacity: 0.9;\n");
        writer.write("}\n");
        writer.write(".recitation-item {\n");
        writer.write("  background: white;\n");
        writer.write("  border-radius: 12px;\n");
        writer.write("  padding: 24px;\n");
        writer.write("  margin-bottom: 16px;\n");
        writer.write("  box-shadow: 0 2px 4px rgba(0,0,0,0.05);\n");
        writer.write("  transition: all 0.2s ease;\n");
        writer.write("}\n");
        writer.write(".recitation-item:hover {\n");
        writer.write("  box-shadow: 0 4px 12px rgba(0,0,0,0.1);\n");
        writer.write("  transform: translateY(-2px);\n");
        writer.write("}\n");
        writer.write(".question-header {\n");
        writer.write("  display: flex;\n");
        writer.write("  align-items: flex-start;\n");
        writer.write("  margin-bottom: 16px;\n");
        writer.write("}\n");
        writer.write(".item-number {\n");
        writer.write("  display: inline-flex;\n");
        writer.write("  align-items: center;\n");
        writer.write("  justify-content: center;\n");
        writer.write("  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        writer.write("  color: white;\n");
        writer.write("  width: 32px;\n");
        writer.write("  height: 32px;\n");
        writer.write("  border-radius: 50%;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  font-size: 15px;\n");
        writer.write("  margin-right: 12px;\n");
        writer.write("  flex-shrink: 0;\n");
        writer.write("}\n");
        writer.write(".question-content {\n");
        writer.write("  font-size: 16px;\n");
        writer.write("  line-height: 1.8;\n");
        writer.write("  color: #1f2937;\n");
        writer.write("  font-weight: 500;\n");
        writer.write("  flex: 1;\n");
        writer.write("}\n");
        writer.write(".options {\n");
        writer.write("  margin: 12px 0;\n");
        writer.write("  padding-left: 44px;\n");
        writer.write("}\n");
        writer.write(".option {\n");
        writer.write("  margin: 6px 0;\n");
        writer.write("  color: #4b5563;\n");
        writer.write("  font-size: 15px;\n");
        writer.write("}\n");
        writer.write(".answer-section {\n");
        writer.write("  margin-top: 20px;\n");
        writer.write("  padding-top: 16px;\n");
        writer.write("  border-top: 2px dashed #E5E7EB;\n");
        writer.write("}\n");
        writer.write(".answer {\n");
        writer.write("  background: #FEF3C7;\n");
        writer.write("  padding: 12px 16px;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  color: #92400E;\n");
        writer.write("  font-weight: 600;\n");
        writer.write("  margin-bottom: 12px;\n");
        writer.write("  font-size: 15px;\n");
        writer.write("  border-left: 4px solid #F59E0B;\n");
        writer.write("}\n");
        writer.write(".explanation {\n");
        writer.write("  background: #EFF6FF;\n");
        writer.write("  padding: 16px;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  color: #1E40AF;\n");
        writer.write("  font-size: 15px;\n");
        writer.write("  line-height: 1.7;\n");
        writer.write("  border-left: 4px solid #3B82F6;\n");
        writer.write("}\n");
        writer.write(".footer {\n");
        writer.write("  margin-top: 32px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  color: #9CA3AF;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  background: white;\n");
        writer.write("  border-radius: 12px;\n");
        writer.write("}\n");
        writer.write("@media (max-width: 768px) {\n");
        writer.write(".container {\n");
        writer.write("  padding: 10px;\n");
        writer.write("}\n");
        writer.write(".recitation-item {\n");
        writer.write("  padding: 16px;\n");
        writer.write("}\n");
        writer.write(".question-content {\n");
        writer.write("  font-size: 15px;\n");
        writer.write("}\n");
        writer.write("}\n");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<div class=\"header\">\n");
        writer.write("<h1>📖 背诵材料</h1>\n");
        writer.write("<p>共 " + questions.size() + " 个题目 | 生成时间：" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("</div>\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"recitation-item\">\n");
                writer.write("<div class=\"question-header\">\n");
                writer.write("<span class=\"item-number\">" + questionNumber++ + "</span>\n");
                writer.write("<div class=\"question-content\">" + question.getQuestionText() + "</div>\n");
                writer.write("</div>\n");
                
                // 选项
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
                
                writer.write("<div class=\"answer-section\">\n");
                
                // 答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"answer\">✅ 答案：" + question.getCorrectAnswer() + "</div>\n");
                }
                
                // 解析
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation\">💡 " + question.getExplanation() + "</div>\n");
                }
                
                writer.write("</div>\n");
                writer.write("</div>\n");
            }
        }
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 系统 | 本材料由系统自动生成</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
