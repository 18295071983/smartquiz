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
        writer.write("  line-height: 1.8;\n");
        writer.write("  color: #1f2937;\n");
        writer.write("  background-color: #F3F4F6;\n");
        writer.write("  padding: 20px;\n");
        writer.write("}\n");
        writer.write(".container {\n");
        writer.write("  max-width: 210mm;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("  background: white;\n");
        writer.write("  padding: 40px;\n");
        writer.write("  border-radius: 12px;\n");
        writer.write("  box-shadow: 0 2px 8px rgba(0,0,0,0.08);\n");
        writer.write("}\n");
        writer.write(".header {\n");
        writer.write("  text-align: center;\n");
        writer.write("  margin-bottom: 40px;\n");
        writer.write("  padding-bottom: 20px;\n");
        writer.write("  border-bottom: 2px solid #667eea;\n");
        writer.write("}\n");
        writer.write(".header h1 {\n");
        writer.write("  font-size: 26px;\n");
        writer.write("  color: #1f2937;\n");
        writer.write("  font-family: 'Microsoft YaHei', 'SimSun', sans-serif;\n");
        writer.write("  margin-bottom: 12px;\n");
        writer.write("}\n");
        writer.write(".header-info {\n");
        writer.write("  display: flex;\n");
        writer.write("  justify-content: center;\n");
        writer.write("  gap: 30px;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  color: #6b7280;\n");
        writer.write("  font-family: 'Microsoft YaHei', sans-serif;\n");
        writer.write("}\n");
        writer.write(".question {\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  background: #FAFAFA;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  border-left: 3px solid #667eea;\n");
        writer.write("  page-break-inside: avoid;\n");
        writer.write("}\n");
        writer.write(".question-number {\n");
        writer.write("  display: inline-block;\n");
        writer.write("  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        writer.write("  color: white;\n");
        writer.write("  width: 28px;\n");
        writer.write("  height: 28px;\n");
        writer.write("  border-radius: 50%;\n");
        writer.write("  text-align: center;\n");
        writer.write("  line-height: 28px;\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-right: 10px;\n");
        writer.write("  vertical-align: middle;\n");
        writer.write("  font-family: 'Microsoft YaHei', sans-serif;\n");
        writer.write("}\n");
        writer.write(".question-text {\n");
        writer.write("  font-size: 16px;\n");
        writer.write("  margin-bottom: 12px;\n");
        writer.write("  text-align: justify;\n");
        writer.write("  display: inline;\n");
        writer.write("}\n");
        writer.write(".options {\n");
        writer.write("  margin-left: 38px;\n");
        writer.write("  margin-bottom: 12px;\n");
        writer.write("  font-size: 15px;\n");
        writer.write("}\n");
        writer.write(".option {\n");
        writer.write("  margin: 6px 0;\n");
        writer.write("  line-height: 1.6;\n");
        writer.write("}\n");
        writer.write(".option-letter {\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-right: 8px;\n");
        writer.write("  color: #4b5563;\n");
        writer.write("}\n");
        writer.write(".answer {\n");
        writer.write("  margin-left: 38px;\n");
        writer.write("  margin-top: 12px;\n");
        writer.write("  padding: 10px 16px;\n");
        writer.write("  background: #FEF3C7;\n");
        writer.write("  border-radius: 6px;\n");
        writer.write("  color: #92400E;\n");
        writer.write("  font-weight: 600;\n");
        writer.write("  font-size: 15px;\n");
        writer.write("  border-left: 3px solid #F59E0B;\n");
        writer.write("  font-family: 'Microsoft YaHei', sans-serif;\n");
        writer.write("}\n");
        writer.write(".explanation {\n");
        writer.write("  margin-left: 38px;\n");
        writer.write("  margin-top: 12px;\n");
        writer.write("  padding: 14px 16px;\n");
        writer.write("  background: #EFF6FF;\n");
        writer.write("  border-left: 3px solid #3B82F6;\n");
        writer.write("  border-radius: 6px;\n");
        writer.write("  font-size: 15px;\n");
        writer.write("  color: #1E40AF;\n");
        writer.write("  line-height: 1.7;\n");
        writer.write("  font-family: 'Microsoft YaHei', sans-serif;\n");
        writer.write("}\n");
        writer.write(".footer {\n");
        writer.write("  margin-top: 40px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  font-size: 13px;\n");
        writer.write("  color: #9CA3AF;\n");
        writer.write("  padding-top: 20px;\n");
        writer.write("  border-top: 1px solid #E5E7EB;\n");
        writer.write("  font-family: 'Microsoft YaHei', sans-serif;\n");
        writer.write("}\n");
        writer.write("@media print {\n");
        writer.write("  body {\n");
        writer.write("    background: white;\n");
        writer.write("    padding: 0;\n");
        writer.write("  }\n");
        writer.write(".container {\n");
        writer.write("    box-shadow: none;\n");
        writer.write("    padding: 20mm;\n");
        writer.write("    border-radius: 0;\n");
        writer.write("  }\n");
        writer.write(".question {\n");
        writer.write("    background: white;\n");
        writer.write("    border-left: 2px solid #667eea;\n");
        writer.write("  }\n");
        writer.write(".header {\n");
        writer.write("    border-bottom: 2px solid #667eea;\n");
        writer.write("  }\n");
        writer.write("@page {\n");
        writer.write("    size: A4;\n");
        writer.write("    margin: 20mm;\n");
        writer.write("  }\n");
        writer.write("}\n");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<div class=\"header\">\n");
        writer.write("<h1>📄 打印材料</h1>\n");
        writer.write("<div class=\"header-info\">\n");
        writer.write("<span>生成时间：" + new java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm").format(new java.util.Date()) + "</span>\n");
        writer.write("<span>题目数量：" + questions.size() + " 题</span>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"question\">\n");
                writer.write("<div class=\"question-text\"><span class=\"question-number\">" + questionNumber++ + "</span>" + question.getQuestionText() + "</div>\n");
                
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
                    writer.write("<div class=\"answer\">✅ 正确答案：" + question.getCorrectAnswer() + "</div>\n");
                }
                
                // 解析
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation\">💡 " + question.getExplanation() + "</div>\n");
                }
                
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
