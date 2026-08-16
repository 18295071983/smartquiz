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
        writer.write("  * { box-sizing: border-box; margin: 0; padding: 0; }\n");
        writer.write("  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; line-height: 1.8; color: #1F2937; background: #F3F4F6; }\n");
        writer.write("  .container { max-width: 1200px; margin: 0 auto; padding: 40px 24px; }\n");
        writer.write("  .header { text-align: center; margin-bottom: 40px; }\n");
        writer.write("  .header h1 { font-size: 32px; font-weight: 700; color: #111827; margin-bottom: 8px; }\n");
        writer.write("  .header .meta { color: #6B7280; font-size: 14px; }\n");
        writer.write("  .section { margin-bottom: 40px; }\n");
        writer.write("  .section-title { font-size: 20px; font-weight: 600; color: white; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 12px 20px; border-radius: 12px; margin-bottom: 24px; }\n");
        writer.write("  .topic { background: white; border-radius: 12px; padding: 24px; margin-bottom: 16px; box-shadow: 0 2px 4px rgba(0,0,0,0.05); }\n");
        writer.write("  .topic-title { font-size: 17px; font-weight: 600; color: #111827; margin-bottom: 16px; line-height: 1.6; }\n");
        writer.write("  .content { margin: 8px 0; padding: 8px 12px; color: #374151; }\n");
        writer.write("  .explanation { background: #EFF6FF; border-left: 4px solid #3B82F6; padding: 16px; border-radius: 8px; margin-top: 16px; color: #1E40AF; font-size: 15px; line-height: 1.7; }\n");
        writer.write("  .footer { text-align: center; color: #9CA3AF; font-size: 13px; margin-top: 40px; padding-top: 20px; border-top: 1px solid #E5E7EB; }\n");
        writer.write("  @media (max-width: 768px) { .container { padding: 20px 16px; } .header h1 { font-size: 24px; } .topic { padding: 16px; } }\n");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<div class=\"header\">\n");
        writer.write("<h1>📚 讲义</h1>\n");
        writer.write("<div class=\"meta\">共 " + questions.size() + " 个题目 · " + sortedTypes.size() + " 种题型</div>\n");
        writer.write("</div>\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            String type = entry.getKey();
            List<Question> typeQuestions = entry.getValue();
            
            writer.write("<div class=\"section\">\n");
            writer.write("<div class=\"section-title\">" + type + "（" + typeQuestions.size() + "题）</div>\n");
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"topic\">\n");
                writer.write("<div class=\"topic-title\">第 " + questionNumber++ + " 题：" + question.getQuestionText() + "</div>\n");
                
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
                
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"content\"><strong>✅ 正确答案：</strong>" + question.getCorrectAnswer() + "</div>\n");
                }
                
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation\">💡 <strong>解析</strong>: " + question.getExplanation() + "</div>\n");
                }
                
                writer.write("</div>\n");
            }
            
            writer.write("</div>\n");
        }
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>共 " + questions.size() + " 道题目 · " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
