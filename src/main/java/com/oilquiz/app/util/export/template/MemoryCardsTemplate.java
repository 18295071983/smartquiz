package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MemoryCardsTemplate implements HTMLTemplate {

    @Override
    public void writeTemplate(FileWriter writer, ExportManager.ExportTask task, List<Question> questions, List<Map.Entry<String, List<Question>>> sortedTypes) throws IOException {
        writer.write("<!DOCTYPE html>\n");
        writer.write("<html lang=\"zh-CN\">\n");
        writer.write("<head>\n");
        writer.write("<meta charset=\"UTF-8\">\n");
        writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        writer.write("<title>记忆卡片</title>\n");
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
        writer.write("  max-width: 1200px;\n");
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
        writer.write(".card-grid {\n");
        writer.write("  display: grid;\n");
        writer.write("  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));\n");
        writer.write("  gap: 16px;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("}\n");
        writer.write(".memory-card {\n");
        writer.write("  cursor: pointer;\n");
        writer.write("  min-height: 220px;\n");
        writer.write("  perspective: 1000px;\n");
        writer.write("}\n");
        writer.write(".card-inner {\n");
        writer.write("  position: relative;\n");
        writer.write("  width: 100%;\n");
        writer.write("  height: 100%;\n");
        writer.write("  text-align: center;\n");
        writer.write("  transition: transform 0.6s;\n");
        writer.write("  transform-style: preserve-3d;\n");
        writer.write("}\n");
        writer.write(".memory-card.flipped .card-inner {\n");
        writer.write("  transform: rotateY(180deg);\n");
        writer.write("}\n");
        writer.write(".card-front, .card-back {\n");
        writer.write("  position: absolute;\n");
        writer.write("  width: 100%;\n");
        writer.write("  height: 100%;\n");
        writer.write("  backface-visibility: hidden;\n");
        writer.write("  display: flex;\n");
        writer.write("  flex-direction: column;\n");
        writer.write("  justify-content: center;\n");
        writer.write("  align-items: center;\n");
        writer.write("  padding: 24px;\n");
        writer.write("  border-radius: 12px;\n");
        writer.write("}\n");
        writer.write(".card-front {\n");
        writer.write("  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n");
        writer.write("  color: white;\n");
        writer.write("  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);\n");
        writer.write("}\n");
        writer.write(".card-front:hover {\n");
        writer.write("  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.4);\n");
        writer.write("}\n");
        writer.write(".card-back {\n");
        writer.write("  background: white;\n");
        writer.write("  color: #333;\n");
        writer.write("  transform: rotateY(180deg);\n");
        writer.write("  box-shadow: 0 4px 12px rgba(0,0,0,0.1);\n");
        writer.write("  border: 2px solid #E0E7FF;\n");
        writer.write("}\n");
        writer.write(".card-question {\n");
        writer.write("  font-size: 16px;\n");
        writer.write("  font-weight: 600;\n");
        writer.write("  margin-bottom: 16px;\n");
        writer.write("  line-height: 1.5;\n");
        writer.write("}\n");
        writer.write(".card-answer {\n");
        writer.write("  font-size: 18px;\n");
        writer.write("  font-weight: 700;\n");
        writer.write("  color: #DC2626;\n");
        writer.write("  margin-bottom: 16px;\n");
        writer.write("  padding: 10px 16px;\n");
        writer.write("  background: #FEF3C7;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("}\n");
        writer.write(".card-explanation {\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  color: #6b7280;\n");
        writer.write("  line-height: 1.6;\n");
        writer.write("  text-align: left;\n");
        writer.write("  width: 100%;\n");
        writer.write("  padding: 12px;\n");
        writer.write("  background: #F9FAFB;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("}\n");
        writer.write(".card-instructions {\n");
        writer.write("  font-size: 13px;\n");
        writer.write("  color: rgba(255,255,255,0.85);\n");
        writer.write("  margin-top: 12px;\n");
        writer.write("}\n");
        writer.write(".card-hint {\n");
        writer.write("  font-size: 12px;\n");
        writer.write("  color: #9CA3AF;\n");
        writer.write("  margin-top: 8px;\n");
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
        writer.write(".card-grid {\n");
        writer.write("  grid-template-columns: 1fr;\n");
        writer.write("}\n");
        writer.write(".memory-card {\n");
        writer.write("  min-height: 200px;\n");
        writer.write("}\n");
        writer.write(".card-front, .card-back {\n");
        writer.write("  padding: 16px;\n");
        writer.write("}\n");
        writer.write("}\n");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<div class=\"header\">\n");
        writer.write("<h1>🎴 记忆卡片</h1>\n");
        writer.write("<p>点击卡片查看答案 | 共 " + questions.size() + " 张卡片 | 生成时间：" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("</div>\n");
        
        writer.write("<div class=\"card-grid\">\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"memory-card\" onclick=\"this.classList.toggle('flipped')\" tabindex=\"0\">\n");
                writer.write("<div class=\"card-inner\">\n");
                
                // 卡片正面（问题）
                writer.write("<div class=\"card-front\">\n");
                writer.write("<div class=\"card-question\">" + questionNumber++ + ". " + question.getQuestionText() + "</div>\n");
                writer.write("<div class=\"card-instructions\">👆 点击查看答案</div>\n");
                writer.write("</div>\n");
                
                // 卡片背面（答案和解析）
                writer.write("<div class=\"card-back\">\n");
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"card-answer\">✅ " + question.getCorrectAnswer() + "</div>\n");
                }
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"card-explanation\">💡 " + question.getExplanation() + "</div>\n");
                }
                writer.write("</div>\n");
                
                writer.write("</div>\n");
                writer.write("</div>\n");
            }
        }
        
        writer.write("</div>\n");
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 系统 | 本材料由系统自动生成</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("<script>\n");
        writer.write("// 键盘支持\n");
        writer.write("document.addEventListener('keydown', function(e) {\n");
        writer.write("  if (e.key === ' ' || e.key === 'Enter') {\n");
        writer.write("    e.preventDefault();\n");
        writer.write("    const activeCard = document.querySelector('.memory-card:focus');\n");
        writer.write("    if (activeCard) {\n");
        writer.write("      activeCard.classList.toggle('flipped');\n");
        writer.write("    }\n");
        writer.write("  }\n");
        writer.write("});\n");
        writer.write("</script>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
