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
        writer.write("  background-color: #f5f5f5;\n");
        writer.write("  padding: 20px;\n");
        writer.write("}\n");
        writer.write(".container {\n");
        writer.write("  max-width: 1200px;\n");
        writer.write("  margin: 0 auto;\n");
        writer.write("}");
        writer.write("h1 {\n");
        writer.write("  color: #2c3e50;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("  text-align: center;\n");
        writer.write("  padding-bottom: 15px;\n");
        writer.write("  border-bottom: 2px solid #3498db;\n");
        writer.write("}");
        writer.write(".card-grid {\n");
        writer.write("  display: grid;\n");
        writer.write("  grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));\n");
        writer.write("  gap: 20px;\n");
        writer.write("  margin-bottom: 30px;\n");
        writer.write("}");
        writer.write(".memory-card {\n");
        writer.write("  background-color: #fff;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("  box-shadow: 0 2px 10px rgba(0,0,0,0.1);\n");
        writer.write("  padding: 25px;\n");
        writer.write("  transition: all 0.3s ease;\n");
        writer.write("  cursor: pointer;\n");
        writer.write("  min-height: 200px;\n");
        writer.write("  position: relative;\n");
        writer.write("  perspective: 1000px;\n");
        writer.write("}");
        writer.write(".memory-card:hover {\n");
        writer.write("  box-shadow: 0 4px 15px rgba(0,0,0,0.15);\n");
        writer.write("  transform: translateY(-5px);\n");
        writer.write("}");
        writer.write(".card-inner {\n");
        writer.write("  position: relative;\n");
        writer.write("  width: 100%;\n");
        writer.write("  height: 100%;\n");
        writer.write("  text-align: center;\n");
        writer.write("  transition: transform 0.6s;\n");
        writer.write("  transform-style: preserve-3d;\n");
        writer.write("}");
        writer.write(".memory-card.flipped .card-inner {\n");
        writer.write("  transform: rotateY(180deg);\n");
        writer.write("}");
        writer.write(".card-front, .card-back {\n");
        writer.write("  position: absolute;\n");
        writer.write("  width: 100%;\n");
        writer.write("  height: 100%;\n");
        writer.write("  backface-visibility: hidden;\n");
        writer.write("  display: flex;\n");
        writer.write("  flex-direction: column;\n");
        writer.write("  justify-content: center;\n");
        writer.write("  align-items: center;\n");
        writer.write("  padding: 20px;\n");
        writer.write("  border-radius: 8px;\n");
        writer.write("}");
        writer.write(".card-front {\n");
        writer.write("  background-color: #3498db;\n");
        writer.write("  color: white;\n");
        writer.write("}");
        writer.write(".card-back {\n");
        writer.write("  background-color: #f9f9f9;\n");
        writer.write("  color: #333;\n");
        writer.write("  transform: rotateY(180deg);\n");
        writer.write("  border: 1px solid #e0e0e0;\n");
        writer.write("}");
        writer.write(".card-question {\n");
        writer.write("  font-size: 16px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  margin-bottom: 15px;\n");
        writer.write("  line-height: 1.4;\n");
        writer.write("}");
        writer.write(".card-answer {\n");
        writer.write("  font-size: 18px;\n");
        writer.write("  font-weight: bold;\n");
        writer.write("  color: #e74c3c;\n");
        writer.write("  margin-bottom: 15px;\n");
        writer.write("}");
        writer.write(".card-explanation {\n");
        writer.write("  font-size: 14px;\n");
        writer.write("  color: #666;\n");
        writer.write("  line-height: 1.4;\n");
        writer.write("  text-align: left;\n");
        writer.write("}");
        writer.write(".card-instructions {\n");
        writer.write("  font-size: 12px;\n");
        writer.write("  color: rgba(255,255,255,0.8);\n");
        writer.write("  position: absolute;\n");
        writer.write("  bottom: 10px;\n");
        writer.write("  left: 0;\n");
        writer.write("  right: 0;\n");
        writer.write("  text-align: center;\n");
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
        writer.write(".card-grid {\n");
        writer.write("  grid-template-columns: 1fr;\n");
        writer.write("}");
        writer.write(".memory-card {\n");
        writer.write("  min-height: 180px;\n");
        writer.write("  padding: 20px;\n");
        writer.write("}");
        writer.write("}");
        writer.write("</style>\n");
        writer.write("</head>\n");
        writer.write("<body>\n");
        writer.write("<div class=\"container\">\n");
        writer.write("<h1>记忆卡片</h1>\n");
        
        writer.write("<div class=\"card-grid\">\n");
        
        int questionNumber = 1;
        for (Map.Entry<String, List<Question>> entry : sortedTypes) {
            List<Question> typeQuestions = entry.getValue();
            
            for (Question question : typeQuestions) {
                writer.write("<div class=\"memory-card\" onclick=\"this.classList.toggle('flipped')\">\n");
                writer.write("<div class=\"card-inner\">\n");
                
                // 卡片正面（问题）
                writer.write("<div class=\"card-front\">\n");
                writer.write("<div class=\"card-question\">" + question.getQuestionText() + "</div>\n");
                writer.write("<div class=\"card-instructions\">点击卡片查看答案</div>\n");
                writer.write("</div>\n");
                
                // 卡片背面（答案和解析）
                writer.write("<div class=\"card-back\">\n");
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"card-answer\">答案：" + question.getCorrectAnswer() + "</div>\n");
                }
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"card-explanation\">" + question.getExplanation() + "</div>\n");
                }
                writer.write("</div>\n");
                
                writer.write("</div>\n");
                writer.write("</div>\n");
            }
        }
        
        writer.write("</div>\n");
        
        writer.write("<div class=\"footer\">\n");
        writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 系统</p>\n");
        writer.write("<p>生成时间：" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
        writer.write("<p>共 " + questions.size() + " 张记忆卡片</p>\n");
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("<script>\n");
        writer.write("// 可选：添加键盘支持\n");
        writer.write("document.addEventListener('keydown', function(e) {\n");
        writer.write("  if (e.key === ' ' || e.key === 'Enter') {\n");
        writer.write("    e.preventDefault();\n");
        writer.write("    const activeCard = document.querySelector('.memory-card:focus');\n");
        writer.write("    if (activeCard) {\n");
        writer.write("      activeCard.classList.toggle('flipped');\n");
        writer.write("    }\n");
        writer.write("  }\n");
        writer.write("});\n");
        writer.write("// 为所有卡片添加焦点支持\n");
        writer.write("document.querySelectorAll('.memory-card').forEach(card => {\n");
        writer.write("  card.setAttribute('tabindex', '0');\n");
        writer.write("});\n");
        writer.write("</script>\n");
        writer.write("</body>\n");
        writer.write("</html>\n");
    }
}
