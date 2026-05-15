package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class HTMLExporter implements Exporter {

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());

        try (FileWriter writer = new FileWriter(exportFile)) {
            // 写入HTML头部
            writer.write("<!DOCTYPE html>\n");
            writer.write("<html lang=\"zh-CN\">\n");
            writer.write("<head>\n");
            writer.write("<meta charset=\"UTF-8\">\n");
            writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n");
            writer.write("<meta name=\"format-detection\" content=\"telephone=no, email=no, address=no\">\n");
            writer.write("<title>导出题目</title>\n");
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
            writer.write("  max-width: 1000px;\n");
            writer.write("  margin: 0 auto;\n");
            writer.write("  background-color: #fff;\n");
            writer.write("  border-radius: 8px;\n");
            writer.write("  box-shadow: 0 2px 10px rgba(0,0,0,0.1);\n");
            writer.write("  padding: 30px;\n");
            writer.write("}\n");
            writer.write("h1 {\n");
            writer.write("  color: #2c3e50;\n");
            writer.write("  margin-bottom: 30px;\n");
            writer.write("  text-align: center;\n");
            writer.write("  padding-bottom: 15px;\n");
            writer.write("  border-bottom: 2px solid #3498db;\n");
            writer.write("}\n");
            writer.write(".filter-section {\n");
            writer.write("  background-color: #f8f9fa;\n");
            writer.write("  padding: 20px;\n");
            writer.write("  border-radius: 8px;\n");
            writer.write("  margin-bottom: 30px;\n");
            writer.write("  border: 1px solid #e9ecef;\n");
            writer.write("}\n");
            writer.write(".filter-row {\n");
            writer.write("  display: flex;\n");
            writer.write("  gap: 20px;\n");
            writer.write("  margin-bottom: 15px;\n");
            writer.write("  flex-wrap: wrap;\n");
            writer.write("}\n");
            writer.write(".filter-group {\n");
            writer.write("  display: flex;\n");
            writer.write("  align-items: center;\n");
            writer.write("  gap: 10px;\n");
            writer.write("}\n");
            writer.write(".filter-group label {\n");
            writer.write("  font-weight: 500;\n");
            writer.write("  color: #495057;\n");
            writer.write("}\n");
            writer.write(".filter-group select, .filter-group input[type=checkbox] {\n");
            writer.write("  padding: 6px 12px;\n");
            writer.write("  border: 1px solid #ced4da;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("}");
            writer.write(".filter-group select {\n");
            writer.write("  min-width: 120px;\n");
            writer.write("}");
            writer.write(".filter-group button {\n");
            writer.write("  padding: 6px 16px;\n");
            writer.write("  background-color: #3498db;\n");
            writer.write("  color: white;\n");
            writer.write("  border: none;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  cursor: pointer;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("  transition: background-color 0.3s ease;\n");
            writer.write("}");
            writer.write(".filter-group button:hover {\n");
            writer.write("  background-color: #2980b9;\n");
            writer.write("}");
            writer.write(".question {\n");
            writer.write("  margin-bottom: 30px;\n");
            writer.write("  padding: 20px;\n");
            writer.write("  border: 1px solid #e0e0e0;\n");
            writer.write("  border-radius: 8px;\n");
            writer.write("  background-color: #fafafa;\n");
            writer.write("  transition: all 0.3s ease;\n");
            writer.write("}\n");
            writer.write(".question:hover {\n");
            writer.write("  box-shadow: 0 2px 8px rgba(0,0,0,0.1);\n");
            writer.write("  transform: translateY(-2px);\n");
            writer.write("}\n");
            writer.write(".question-header {\n");
            writer.write("  margin-bottom: 15px;\n");
            writer.write("  display: flex;\n");
            writer.write("  align-items: center;\n");
            writer.write("  flex-wrap: wrap;\n");
            writer.write("  gap: 10px;\n");
            writer.write("}\n");
            writer.write(".question-number {\n");
            writer.write("  display: inline-block;\n");
            writer.write("  background-color: #3498db;\n");
            writer.write("  color: white;\n");
            writer.write("  width: 30px;\n");
            writer.write("  height: 30px;\n");
            writer.write("  border-radius: 50%;\n");
            writer.write("  text-align: center;\n");
            writer.write("  line-height: 30px;\n");
            writer.write("  margin-right: 10px;\n");
            writer.write("  font-weight: bold;\n");
            writer.write("}\n");
            writer.write(".question-text {\n");
            writer.write("  font-size: 18px;\n");
            writer.write("  font-weight: 600;\n");
            writer.write("  margin-bottom: 10px;\n");
            writer.write("  color: #2c3e50;\n");
            writer.write("}\n");
            writer.write(".question-type {\n");
            writer.write("  display: inline-block;\n");
            writer.write("  background-color: #95a5a6;\n");
            writer.write("  color: white;\n");
            writer.write("  padding: 2px 8px;\n");
            writer.write("  border-radius: 12px;\n");
            writer.write("  font-size: 12px;\n");
            writer.write("  margin-left: 10px;\n");
            writer.write("}\n");
            writer.write(".options {\n");
            writer.write("  margin: 15px 0;\n");
            writer.write("  padding-left: 20px;\n");
            writer.write("}\n");
            writer.write(".option {\n");
            writer.write("  margin: 10px 0;\n");
            writer.write("  padding: 10px;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  transition: all 0.2s ease;\n");
            writer.write("  cursor: pointer;\n");
            writer.write("}\n");
            writer.write(".option:hover {\n");
            writer.write("  background-color: #f0f8ff;\n");
            writer.write("}");
            writer.write(".correct {\n");
            writer.write("  margin-top: 15px;\n");
            writer.write("  padding: 12px;\n");
            writer.write("  background-color: #d4edda;\n");
            writer.write("  border: 1px solid #c3e6cb;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  color: #155724;\n");
            writer.write("  font-weight: 600;\n");
            writer.write("  display: none;\n");
            writer.write("}");
            writer.write(".correct.visible {\n");
            writer.write("  display: block;\n");
            writer.write("}");
            writer.write(".explanation {\n");
            writer.write("  margin-top: 15px;\n");
            writer.write("  padding: 15px;\n");
            writer.write("  background-color: #e3f2fd;\n");
            writer.write("  border-left: 4px solid #2196f3;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  color: #1565c0;\n");
            writer.write("}");
            writer.write(".explanation h4 {\n");
            writer.write("  margin-bottom: 8px;\n");
            writer.write("  color: #0d47a1;\n");
            writer.write("}");
            writer.write(".difficulty {\n");
            writer.write("  display: inline-block;\n");
            writer.write("  background-color: #ff9800;\n");
            writer.write("  color: white;\n");
            writer.write("  padding: 2px 8px;\n");
            writer.write("  border-radius: 12px;\n");
            writer.write("  font-size: 12px;\n");
            writer.write("  margin-left: 10px;\n");
            writer.write("}");
            writer.write(".footer {\n");
            writer.write("  margin-top: 40px;\n");
            writer.write("  padding-top: 20px;\n");
            writer.write("  border-top: 1px solid #e0e0e0;\n");
            writer.write("  text-align: center;\n");
            writer.write("  color: #666;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("}");
            writer.write("#question-count {\n");
            writer.write("  font-weight: bold;\n");
            writer.write("  color: #3498db;\n");
            writer.write("}");
            writer.write("@media (max-width: 768px) {\n");
            writer.write("body {\n");
            writer.write("  padding: 10px;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("}");
            writer.write(".container {\n");
            writer.write("  padding: 15px;\n");
            writer.write("}");
            writer.write("h1 {\n");
            writer.write("  font-size: 20px;\n");
            writer.write("  margin-bottom: 20px;\n");
            writer.write("}");
            writer.write(".filter-section {\n");
            writer.write("  padding: 15px;\n");
            writer.write("  margin-bottom: 20px;\n");
            writer.write("}");
            writer.write(".filter-row {\n");
            writer.write("  flex-direction: column;\n");
            writer.write("  align-items: flex-start;\n");
            writer.write("  gap: 10px;\n");
            writer.write("}");
            writer.write(".filter-group {\n");
            writer.write("  width: 100%;\n");
            writer.write("  justify-content: space-between;\n");
            writer.write("}");
            writer.write(".filter-group select {\n");
            writer.write("  flex: 1;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("}");
            writer.write(".question {\n");
            writer.write("  padding: 15px;\n");
            writer.write("  margin-bottom: 20px;\n");
            writer.write("}");
            writer.write(".question-text {\n");
            writer.write("  font-size: 16px;\n");
            writer.write("}");
            writer.write(".options {\n");
            writer.write("  padding-left: 15px;\n");
            writer.write("}");
            writer.write(".option {\n");
            writer.write("  padding: 8px;\n");
            writer.write("  margin: 8px 0;\n");
            writer.write("}");
            writer.write(".correct {\n");
            writer.write("  padding: 10px;\n");
            writer.write("}");
            writer.write(".explanation {\n");
            writer.write("  padding: 12px;\n");
            writer.write("}");
            writer.write("}");
            writer.write("@media (max-width: 480px) {\n");
            writer.write("body {\n");
            writer.write("  font-size: 13px;\n");
            writer.write("}");
            writer.write(".container {\n");
            writer.write("  padding: 10px;\n");
            writer.write("}");
            writer.write("h1 {\n");
            writer.write("  font-size: 18px;\n");
            writer.write("  margin-bottom: 15px;\n");
            writer.write("}");
            writer.write(".question-text {\n");
            writer.write("  font-size: 15px;\n");
            writer.write("}");
            writer.write("}");
            writer.write("</style>\n");
            writer.write("<script>\n");
            writer.write("document.addEventListener('DOMContentLoaded', function() {\n");
            writer.write("  // 筛选功能\n");
            writer.write("  const typeFilter = document.getElementById('type-filter');\n");
            writer.write("  const difficultyFilter = document.getElementById('difficulty-filter');\n");
            writer.write("  const applyFilterBtn = document.getElementById('apply-filter');\n");
            writer.write("  const showAnswersCheckbox = document.getElementById('show-answers');\n");
            writer.write("  const questions = document.querySelectorAll('.question');\n");
            writer.write("  const questionCount = document.getElementById('question-count');\n");
            writer.write("\n");
            writer.write("  // 应用筛选\n");
            writer.write("  function applyFilters() {\n");
            writer.write("    const selectedType = typeFilter.value;\n");
            writer.write("    const selectedDifficulty = difficultyFilter.value;\n");
            writer.write("    let visibleCount = 0;\n");
            writer.write("\n");
            writer.write("    questions.forEach(question => {\n");
            writer.write("      const questionType = question.querySelector('.question-type').textContent.trim();\n");
            writer.write("      const difficultyText = question.querySelector('.difficulty');\n");
            writer.write("      const difficulty = difficultyText ? difficultyText.textContent.trim().replace('难度: ', '') : '1';\n");
            writer.write("\n");
            writer.write("      // 类型筛选\n");
            writer.write("      const typeMatch = selectedType === 'all' || questionType === selectedType;\n");
            writer.write("      // 难度筛选\n");
            writer.write("      const difficultyMatch = selectedDifficulty === 'all' || difficulty === selectedDifficulty;\n");
            writer.write("\n");
            writer.write("      if (typeMatch && difficultyMatch) {\n");
            writer.write("        question.style.display = 'block';\n");
            writer.write("        visibleCount++;");
            writer.write("      } else {\n");
            writer.write("        question.style.display = 'none';\n");
            writer.write("      }\n");
            writer.write("    });\n");
            writer.write("\n");
            writer.write("    questionCount.textContent = visibleCount;\n");
            writer.write("  }\n");
            writer.write("\n");
            writer.write("  // 显示/隐藏答案\n");
            writer.write("  function toggleAnswers() {\n");
            writer.write("    const correctElements = document.querySelectorAll('.correct');\n");
            writer.write("    correctElements.forEach(element => {\n");
            writer.write("      if (showAnswersCheckbox.checked) {\n");
            writer.write("        element.classList.add('visible');\n");
            writer.write("      } else {\n");
            writer.write("        element.classList.remove('visible');\n");
            writer.write("      }\n");
            writer.write("    });\n");
            writer.write("  }\n");
            writer.write("\n");
            writer.write("  // 事件监听\n");
            writer.write("  applyFilterBtn.addEventListener('click', applyFilters);\n");
            writer.write("  showAnswersCheckbox.addEventListener('change', toggleAnswers);\n");
            writer.write("\n");
            writer.write("  // 初始化\n");
            writer.write("  applyFilters();\n");
            writer.write("  if (showAnswersCheckbox.checked) {\n");
            writer.write("    toggleAnswers();\n");
            writer.write("  }\n");
            writer.write("});\n");
            writer.write("</script>\n");
            writer.write("</head>\n");
            writer.write("<body>\n");
            writer.write("<div class=\"container\">\n");
            writer.write("<h1>导出题目</h1>\n");
            
            // 写入筛选区域
            writer.write("<div class=\"filter-section\">\n");
            writer.write("<div class=\"filter-row\">\n");
            writer.write("<div class=\"filter-group\">\n");
            writer.write("<label for=\"type-filter\">题目类型:</label>\n");
            writer.write("<select id=\"type-filter\">\n");
            writer.write("<option value=\"all\">全部</option>\n");
            
            // 动态获取题型
            java.util.Set<String> questionTypes = new java.util.HashSet<>();
            for (Question question : questions) {
                if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
                    questionTypes.add(question.getQuestionType());
                }
            }
            if (questionTypes.isEmpty()) {
                writer.write("<option value=\"未分类\">未分类</option>\n");
            } else {
                for (String type : questionTypes) {
                    writer.write("<option value=\"" + type + "\">" + type + "</option>\n");
                }
            }
            
            writer.write("</select>\n");
            writer.write("</div>\n");
            writer.write("<div class=\"filter-group\">\n");
            writer.write("<label for=\"difficulty-filter\">难度:</label>\n");
            writer.write("<select id=\"difficulty-filter\">\n");
            writer.write("<option value=\"all\">全部</option>\n");
            writer.write("<option value=\"1\">1</option>\n");
            writer.write("<option value=\"2\">2</option>\n");
            writer.write("<option value=\"3\">3</option>\n");
            writer.write("<option value=\"4\">4</option>\n");
            writer.write("<option value=\"5\">5</option>\n");
            writer.write("</select>\n");
            writer.write("</div>\n");
            writer.write("<div class=\"filter-group\">\n");
            writer.write("<button id=\"apply-filter\">应用筛选</button>\n");
            writer.write("</div>\n");
            writer.write("<div class=\"filter-group\">\n");
            writer.write("<label for=\"show-answers\">显示答案:</label>\n");
            writer.write("<input type=\"checkbox\" id=\"show-answers\">\n");
            writer.write("</div>\n");
            writer.write("<div class=\"filter-group\">\n");
            writer.write("<span>显示题目数: <span id=\"question-count\">0</span></span>\n");
            writer.write("</div>\n");
            writer.write("</div>\n");
            writer.write("</div>\n");
            
            // 写入问题数据
            int questionNumber = 1;
            int total = questions.size();
            for (int i = 0; i < total; i++) {
                Question question = questions.get(i);
                writer.write("<div class=\"question\">\n");
                writer.write("<div class=\"question-header\">\n");
                writer.write("<span class=\"question-number\">" + questionNumber++ + "</span>\n");
                
                // 只导出非空字段
                if (question.getQuestionType() != null && !question.getQuestionType().isEmpty()) {
                    writer.write("<span class=\"question-type\">" + question.getQuestionType() + "</span>\n");
                }
                
                if (task.getConfig().isIncludeDifficulty()) {
                    writer.write("<span class=\"difficulty\">难度: " + question.getDifficulty() + "</span>\n");
                }
                
                writer.write("</div>\n");
                
                if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                    writer.write("<div class=\"question-text\">" + question.getQuestionText() + "</div>\n");
                }
                
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
                
                // 根据配置决定是否包含答案
                if (task.getConfig().isIncludeAnswers() && question.getCorrectAnswer() != null && !question.getCorrectAnswer().isEmpty()) {
                    writer.write("<div class=\"correct\">正确答案: " + question.getCorrectAnswer() + "</div>\n");
                }
                
                // 根据配置决定是否包含解析
                if (task.getConfig().isIncludeExplanations() && question.getExplanation() != null && !question.getExplanation().isEmpty()) {
                    writer.write("<div class=\"explanation\">\n");
                    writer.write("<h4>解析</h4>\n");
                    writer.write(question.getExplanation() + "\n");
                    writer.write("</div>\n");
                }
                
                writer.write("</div>\n");
                
                // 更新进度
                if (task.getCallback() != null && i % 10 == 0) {
                    int progress = (int) ((i + 1) * 100.0 / total);
                    task.getCallback().onExportProgress(progress);
                }
            }
            
            // 确保最后更新到100%
            if (task.getCallback() != null) {
                task.getCallback().onExportProgress(100);
            }
            
            // 写入页脚
            writer.write("<div class=\"footer\">\n");
            writer.write("<p>导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
            writer.write("<p>导出题目数量: " + questions.size() + "</p>\n");
            writer.write("</div>\n");
            writer.write("</div>\n");
            writer.write("</body>\n");
            writer.write("</html>\n");
            
            // 导出完成后发送广播通知
            try {
                android.content.Intent intent = new android.content.Intent("com.oilquiz.app.EXPORT_COMPLETE");
                intent.putExtra("file_path", exportFile.getAbsolutePath());
                task.getContext().sendBroadcast(intent);
            } catch (Exception e) {
                // 忽略调用异常
                e.printStackTrace();
            }
            
            return exportFile;
        }
    }

    @Override
    public String getFormatName() {
        return "HTML";
    }

    @Override
    public String getFileExtension() {
        return "html";
    }

    @Override
    public void validateParameters(ExportManager.ExportTask task) throws IllegalArgumentException {
        if (task == null) {
            throw new IllegalArgumentException("导出任务不能为空");
        }

        if (task.getConfig() == null) {
            throw new IllegalArgumentException("导出配置不能为空");
        }

        if (task.getQuestions() == null) {
            throw new IllegalArgumentException("问题列表不能为空");
        }
    }
}