package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;

public class EnhancedHTMLExporter implements Exporter {

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
            // 按题型分组并排序
            Map<String, List<Question>> questionsByType = new HashMap<>();
            for (Question question : questions) {
                String type = question.getQuestionType() != null ? question.getQuestionType() : "未分类";
                if (!questionsByType.containsKey(type)) {
                    questionsByType.put(type, new ArrayList<>());
                }
                questionsByType.get(type).add(question);
            }
            
            // 动态生成题型顺序
            List<Map.Entry<String, List<Question>>> sortedTypes = new ArrayList<>(questionsByType.entrySet());
            // 按题型名称排序
            Collections.sort(sortedTypes, (a, b) -> a.getKey().compareTo(b.getKey()));

            // 写入HTML头部
            writer.write("<!DOCTYPE html>\n");
            writer.write("<html lang=\"zh-CN\">\n");
            writer.write("<head>\n");
            writer.write("<meta charset=\"UTF-8\">\n");
            writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
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
            writer.write(".export-info {\n");
            writer.write("  background-color: #f8f9fa;\n");
            writer.write("  padding: 15px;\n");
            writer.write("  border-radius: 8px;\n");
            writer.write("  margin-bottom: 30px;\n");
            writer.write("  border: 1px solid #e9ecef;\n");
            writer.write("  text-align: right;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("  color: #666;\n");
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
            writer.write("}");
            writer.write(".filter-group {\n");
            writer.write("  display: flex;\n");
            writer.write("  align-items: center;\n");
            writer.write("  gap: 10px;\n");
            writer.write("}");
            writer.write(".filter-group label {\n");
            writer.write("  font-weight: 500;\n");
            writer.write("  color: #495057;\n");
            writer.write("}");
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
            writer.write(".type-section {\n");
            writer.write("  margin-bottom: 40px;\n");
            writer.write("}");
            writer.write(".type-header {\n");
            writer.write("  background-color: #3498db;\n");
            writer.write("  color: white;\n");
            writer.write("  padding: 15px;\n");
            writer.write("  border-radius: 8px 8px 0 0;\n");
            writer.write("  margin-bottom: 20px;\n");
            writer.write("  display: flex;\n");
            writer.write("  justify-content: space-between;\n");
            writer.write("  align-items: center;\n");
            writer.write("}");
            writer.write(".type-title {\n");
            writer.write("  font-size: 18px;\n");
            writer.write("  font-weight: bold;\n");
            writer.write("}");
            writer.write(".type-count {\n");
            writer.write("  background-color: rgba(255,255,255,0.2);\n");
            writer.write("  padding: 4px 12px;\n");
            writer.write("  border-radius: 12px;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("}");
            writer.write(".question {\n");
            writer.write("  margin-bottom: 25px;\n");
            writer.write("  padding: 20px;\n");
            writer.write("  border: 1px solid #e0e0e0;\n");
            writer.write("  border-radius: 8px;\n");
            writer.write("  background-color: #fafafa;\n");
            writer.write("  transition: all 0.3s ease;\n");
            writer.write("}");
            writer.write(".question:hover {\n");
            writer.write("  box-shadow: 0 2px 8px rgba(0,0,0,0.1);\n");
            writer.write("  transform: translateY(-2px);\n");
            writer.write("}");
            writer.write(".question-header {\n");
            writer.write("  margin-bottom: 15px;\n");
            writer.write("  display: flex;\n");
            writer.write("  align-items: center;\n");
            writer.write("  flex-wrap: wrap;\n");
            writer.write("  gap: 10px;\n");
            writer.write("}");
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
            writer.write("}");
            writer.write(".question-text {\n");
            writer.write("  font-size: 16px;\n");
            writer.write("  font-weight: 600;\n");
            writer.write("  margin-bottom: 15px;\n");
            writer.write("  color: #2c3e50;\n");
            writer.write("  line-height: 1.5;\n");
            writer.write("}");
            writer.write(".question-info {\n");
            writer.write("  font-size: 12px;\n");
            writer.write("  color: #666;\n");
            writer.write("  margin-bottom: 15px;\n");
            writer.write("  display: flex;\n");
            writer.write("  gap: 15px;\n");
            writer.write("}");
            writer.write(".question-type {\n");
            writer.write("  display: inline-block;\n");
            writer.write("  background-color: #95a5a6;\n");
            writer.write("  color: white;\n");
            writer.write("  padding: 2px 8px;\n");
            writer.write("  border-radius: 12px;\n");
            writer.write("  font-size: 12px;\n");
            writer.write("}");
            writer.write(".difficulty {\n");
            writer.write("  display: inline-block;\n");
            writer.write("  background-color: #ff9800;\n");
            writer.write("  color: white;\n");
            writer.write("  padding: 2px 8px;\n");
            writer.write("  border-radius: 12px;\n");
            writer.write("  font-size: 12px;\n");
            writer.write("}");
            writer.write(".options {\n");
            writer.write("  margin: 15px 0;\n");
            writer.write("  padding-left: 20px;\n");
            writer.write("}");
            writer.write(".option {\n");
            writer.write("  margin: 8px 0;\n");
            writer.write("  padding: 10px;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  transition: all 0.2s ease;\n");
            writer.write("  cursor: pointer;\n");
            writer.write("  background-color: #f9f9f9;\n");
            writer.write("  border-left: 3px solid #e0e0e0;\n");
            writer.write("}");
            writer.write(".option:hover {\n");
            writer.write("  background-color: #f0f8ff;\n");
            writer.write("  border-left-color: #3498db;\n");
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
            writer.write("  display: none;\n");
            writer.write("}");
            writer.write(".explanation.visible {\n");
            writer.write("  display: block;\n");
            writer.write("}");
            writer.write(".explanation h4 {\n");
            writer.write("  margin-bottom: 8px;\n");
            writer.write("  color: #0d47a1;\n");
            writer.write("  font-size: 14px;\n");
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
            writer.write(".action-buttons {\n");
            writer.write("  display: flex;\n");
            writer.write("  gap: 10px;\n");
            writer.write("  margin-bottom: 20px;\n");
            writer.write("  flex-wrap: wrap;\n");
            writer.write("}");
            writer.write(".action-button {\n");
            writer.write("  padding: 8px 16px;\n");
            writer.write("  background-color: #3498db;\n");
            writer.write("  color: white;\n");
            writer.write("  border: none;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  cursor: pointer;\n");
            writer.write("  font-size: 14px;\n");
            writer.write("  transition: background-color 0.3s ease;\n");
            writer.write("}");
            writer.write(".action-button:hover {\n");
            writer.write("  background-color: #2980b9;\n");
            writer.write("}");
            writer.write(".action-button.secondary {\n");
            writer.write("  background-color: #95a5a6;\n");
            writer.write("}");
            writer.write(".action-button.secondary:hover {\n");
            writer.write("  background-color: #7f8c8d;\n");
            writer.write("}");
            writer.write("@media (max-width: 768px) {\n");
            writer.write(".filter-row {\n");
            writer.write("  flex-direction: column;\n");
            writer.write("  align-items: flex-start;\n");
            writer.write("}");
            writer.write(".filter-group {\n");
            writer.write("  width: 100%;\n");
            writer.write("  justify-content: space-between;\n");
            writer.write("}");
            writer.write(".filter-group select {\n");
            writer.write("  flex: 1;\n");
            writer.write("}");
            writer.write(".type-header {\n");
            writer.write("  flex-direction: column;\n");
            writer.write("  align-items: flex-start;\n");
            writer.write("  gap: 10px;\n");
            writer.write("}");
            writer.write(".action-buttons {\n");
            writer.write("  flex-direction: column;\n");
            writer.write("}");
            writer.write(".action-button {\n");
            writer.write("  width: 100%;\n");
            writer.write("  text-align: center;\n");
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
            writer.write("  const showExplanationsCheckbox = document.getElementById('show-explanations');\n");
            writer.write("  const questions = document.querySelectorAll('.question');\n");
            writer.write("  const questionCount = document.getElementById('question-count');\n");
            writer.write("  const typeSections = document.querySelectorAll('.type-section');\n");
            writer.write("\n");
            writer.write("  // 应用筛选\n");
            writer.write("  function applyFilters() {\n");
            writer.write("    const selectedType = typeFilter.value;\n");
            writer.write("    const selectedDifficulty = difficultyFilter.value;\n");
            writer.write("    let visibleCount = 0;\n");
            writer.write("\n");
            writer.write("    // 显示/隐藏题型部分\n");
            writer.write("    typeSections.forEach(section => {\n");
            writer.write("      const typeName = section.getAttribute('data-type');\n");
            writer.write("      const typeQuestions = section.querySelectorAll('.question');\n");
            writer.write("      let typeHasVisibleQuestions = false;\n");
            writer.write("\n");
            writer.write("      typeQuestions.forEach(question => {\n");
            writer.write("        const difficultyText = question.querySelector('.difficulty');\n");
            writer.write("        const difficulty = difficultyText ? difficultyText.textContent.trim().replace('难度: ', '') : '1';\n");
            writer.write("\n");
            writer.write("        // 类型筛选\n");
            writer.write("        const typeMatch = selectedType === 'all' || typeName === selectedType;\n");
            writer.write("        // 难度筛选\n");
            writer.write("        const difficultyMatch = selectedDifficulty === 'all' || difficulty === selectedDifficulty;\n");
            writer.write("\n");
            writer.write("        if (typeMatch && difficultyMatch) {\n");
            writer.write("          question.style.display = 'block';\n");
            writer.write("          typeHasVisibleQuestions = true;\n");
            writer.write("          visibleCount++;");
            writer.write("        } else {\n");
            writer.write("          question.style.display = 'none';\n");
            writer.write("        }\n");
            writer.write("      });\n");
            writer.write("\n");
            writer.write("      // 显示/隐藏题型部分\n");
            writer.write("      section.style.display = typeHasVisibleQuestions ? 'block' : 'none';\n");
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
            writer.write("  // 显示/隐藏解析\n");
            writer.write("  function toggleExplanations() {\n");
            writer.write("    const explanationElements = document.querySelectorAll('.explanation');\n");
            writer.write("    explanationElements.forEach(element => {\n");
            writer.write("      if (showExplanationsCheckbox.checked) {\n");
            writer.write("        element.classList.add('visible');\n");
            writer.write("      } else {\n");
            writer.write("        element.classList.remove('visible');\n");
            writer.write("      }\n");
            writer.write("    });\n");
            writer.write("  }\n");
            writer.write("\n");
            writer.write("  // 全选/取消全选\n");
            writer.write("  document.getElementById('select-all').addEventListener('click', function() {\n");
            writer.write("    showAnswersCheckbox.checked = true;\n");
            writer.write("    showExplanationsCheckbox.checked = true;\n");
            writer.write("    toggleAnswers();\n");
            writer.write("    toggleExplanations();\n");
            writer.write("  });\n");
            writer.write("\n");
            writer.write("  document.getElementById('deselect-all').addEventListener('click', function() {\n");
            writer.write("    showAnswersCheckbox.checked = false;\n");
            writer.write("    showExplanationsCheckbox.checked = false;\n");
            writer.write("    toggleAnswers();\n");
            writer.write("    toggleExplanations();\n");
            writer.write("  });\n");
            writer.write("\n");
            writer.write("  // 事件监听\n");
            writer.write("  applyFilterBtn.addEventListener('click', applyFilters);\n");
            writer.write("  showAnswersCheckbox.addEventListener('change', toggleAnswers);\n");
            writer.write("  showExplanationsCheckbox.addEventListener('change', toggleExplanations);\n");
            writer.write("\n");
            writer.write("  // 初始化\n");
            writer.write("  applyFilters();\n");
            writer.write("  if (showAnswersCheckbox.checked) {\n");
            writer.write("    toggleAnswers();\n");
            writer.write("  }\n");
            writer.write("  if (showExplanationsCheckbox.checked) {\n");
            writer.write("    toggleExplanations();\n");
            writer.write("  }\n");
            writer.write("});\n");
            writer.write("</script>\n");
            writer.write("</head>\n");
            writer.write("<body>\n");
            writer.write("<div class=\"container\">\n");
            writer.write("<h1>导出题目</h1>\n");
            
            // 写入导出信息
            writer.write("<div class=\"export-info\">\n");
            writer.write("<p>导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>\n");
            writer.write("<p>导出题目总数: " + questions.size() + "</p>\n");
            writer.write("<p>题型数量: " + sortedTypes.size() + "</p>\n");
            writer.write("</div>\n");
            
            // 写入操作按钮
            writer.write("<div class=\"action-buttons\">\n");
            writer.write("<button class=\"action-button\" id=\"select-all\">显示全部答案和解析</button>\n");
            writer.write("<button class=\"action-button secondary\" id=\"deselect-all\">隐藏全部答案和解析</button>\n");
            writer.write("</div>\n");
            
            // 写入筛选区域
            writer.write("<div class=\"filter-section\">\n");
            writer.write("<div class=\"filter-row\">\n");
            writer.write("<div class=\"filter-group\">\n");
            writer.write("<label for=\"type-filter\">题目类型:</label>\n");
            writer.write("<select id=\"type-filter\">\n");
            writer.write("<option value=\"all\">全部</option>\n");
            
            // 动态获取题型
            for (Map.Entry<String, List<Question>> entry : sortedTypes) {
                writer.write("<option value=\"" + entry.getKey() + "\">" + entry.getKey() + " (" + entry.getValue().size() + "题)</option>\n");
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
            writer.write("<label for=\"show-explanations\">显示解析:</label>\n");
            writer.write("<input type=\"checkbox\" id=\"show-explanations\">\n");
            writer.write("</div>\n");
            writer.write("<div class=\"filter-group\">\n");
            writer.write("<span>显示题目数: <span id=\"question-count\">0</span></span>\n");
            writer.write("</div>\n");
            writer.write("</div>\n");
            writer.write("</div>\n");
            
            // 写入问题数据（按题型分组）
            int questionNumber = 1;
            int total = questions.size();
            int processed = 0;
            for (Map.Entry<String, List<Question>> entry : sortedTypes) {
                String type = entry.getKey();
                List<Question> typeQuestions = entry.getValue();
                
                // 题型部分
                writer.write("<div class=\"type-section\" data-type=\"" + type + "\">\n");
                writer.write("<div class=\"type-header\">\n");
                writer.write("<div class=\"type-title\">" + type + "</div>\n");
                writer.write("<div class=\"type-count\">" + typeQuestions.size() + "题</div>\n");
                writer.write("</div>\n");
                
                // 写入该题型的题目
                for (Question question : typeQuestions) {
                    writer.write("<div class=\"question\">\n");
                    writer.write("<div class=\"question-header\">\n");
                    writer.write("<span class=\"question-number\">" + questionNumber++ + "</span>\n");
                    writer.write("</div>\n");
                    
                    if (question.getQuestionText() != null && !question.getQuestionText().isEmpty()) {
                        writer.write("<div class=\"question-text\">" + question.getQuestionText() + "</div>\n");
                    }
                    
                    // 题目信息
                    writer.write("<div class=\"question-info\">\n");
                    writer.write("<span class=\"question-type\">" + type + "</span>\n");
                    if (task.getConfig().isIncludeDifficulty()) {
                        writer.write("<span class=\"difficulty\">难度: " + question.getDifficulty() + "</span>\n");
                    }
                    writer.write("</div>\n");
                    
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
                    processed++;
                    if (task.getCallback() != null && processed % 10 == 0) {
                        int progress = (int) (processed * 100.0 / total);
                        task.getCallback().onExportProgress(progress);
                    }
                }
                
                writer.write("</div>\n");
            }
            
            // 确保最后更新到100%
            if (task.getCallback() != null) {
                task.getCallback().onExportProgress(100);
            }
            
            // 写入页脚
            writer.write("<div class=\"footer\">\n");
            writer.write("<p>© " + new java.text.SimpleDateFormat("yyyy").format(new java.util.Date()) + " OilQuiz 导出系统</p>\n");
            writer.write("<p>本文件由系统自动生成，请勿手动修改</p>\n");
            writer.write("</div>\n");
            writer.write("</div>\n");
            writer.write("</body>\n");
            writer.write("</html>\n");
            
            return exportFile;
        }
    }

    @Override
    public String getFormatName() {
        return "Enhanced HTML";
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

        if (task.getQuestions() == null || task.getQuestions().isEmpty()) {
            throw new IllegalArgumentException("没有问题可导出");
        }
    }
}
