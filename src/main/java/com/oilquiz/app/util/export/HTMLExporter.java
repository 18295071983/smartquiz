package com.oilquiz.app.util.export;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HTMLExporter implements Exporter {

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        // 模板生效字段列表（场景模板/自定义字段），决定导出内容与顺序
        List<String> fields = ExportUtils.getEffectiveFields(task.getConfig());
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());

        try (OutputStreamWriter writer = new OutputStreamWriter(new java.io.FileOutputStream(exportFile), StandardCharsets.UTF_8)) {
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
            writer.write("}\n");
            writer.write(".meta-chip {\n");
            writer.write("  display: inline-block;\n");
            writer.write("  background-color: #ecf0f1;\n");
            writer.write("  color: #2c3e50;\n");
            writer.write("  padding: 2px 10px;\n");
            writer.write("  border-radius: 12px;\n");
            writer.write("  font-size: 12px;\n");
            writer.write("  margin-left: 10px;\n");
            writer.write("}\n");
            writer.write(".meta-line {\n");
            writer.write("  margin-top: 10px;\n");
            writer.write("  padding: 10px 12px;\n");
            writer.write("  background-color: #f8f9fa;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  color: #666;\n");
            writer.write("  font-size: 13px;\n");
            writer.write("  line-height: 1.7;\n");
            writer.write("}\n");
            writer.write(".analysis {\n");
            writer.write("  margin-top: 10px;\n");
            writer.write("  padding: 15px;\n");
            writer.write("  background-color: #f3e5f5;\n");
            writer.write("  border-left: 4px solid #9c27b0;\n");
            writer.write("  border-radius: 4px;\n");
            writer.write("  color: #6a1b9a;\n");
            writer.write("}\n");
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
            writer.write("      const typeEl = question.querySelector('.question-type');\n");
                        writer.write("      const questionType = typeEl ? typeEl.textContent.trim() : '未分类';\n");
            writer.write("      const difficultyText = question.querySelector('.difficulty');\n");
            writer.write("      const difficulty = difficultyText ? difficultyText.textContent.trim().replace('难度: ', '') : '未设置';\n");
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
            writer.write("<option value=\"简单\">简单</option>\n");
            writer.write("<option value=\"中等\">中等</option>\n");
            writer.write("<option value=\"困难\">困难</option>\n");
            writer.write("<option value=\"未设置\">未设置</option>\n");
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
            
            // 写入问题数据（按模板字段渲染）
            int questionNumber = 1;
            int total = questions.size();
            boolean includeAnswers = task.getConfig().isIncludeAnswers();
            boolean includeExplanations = task.getConfig().isIncludeExplanations();
            for (int i = 0; i < total; i++) {
                Question question = questions.get(i);
                writer.write("<div class=\"question\">\n");
                writer.write("<div class=\"question-header\">\n");
                writer.write("<span class=\"question-number\">" + questionNumber++ + "</span>\n");

                // 按模板字段输出头部元信息（题型/难度/分类/知识点/分值等）
                if (ExportUtils.hasField(fields, "questionType")) {
                    String type = question.getQuestionType();
                    if (type != null && !type.isEmpty()) {
                        writer.write("<span class=\"question-type\">" + ExportUtils.escapeHtml(type) + "</span>\n");
                    }
                }
                if (ExportUtils.hasField(fields, "difficulty")) {
                    writer.write("<span class=\"difficulty\">难度: " + ExportUtils.escapeHtml(question.getDifficultyText()) + "</span>\n");
                }
                if (ExportUtils.hasField(fields, "category")) {
                    String category = question.getCategory();
                    if (category != null && !category.isEmpty()) {
                        writer.write("<span class=\"meta-chip\">分类: " + ExportUtils.escapeHtml(category) + "</span>\n");
                    }
                }
                if (ExportUtils.hasField(fields, "subCategory")) {
                    String subCategory = question.getSubCategory();
                    if (subCategory != null && !subCategory.isEmpty()) {
                        writer.write("<span class=\"meta-chip\">子分类: " + ExportUtils.escapeHtml(subCategory) + "</span>\n");
                    }
                }
                if (ExportUtils.hasField(fields, "knowledgePoint")) {
                    String knowledgePoint = question.getKnowledgePoint();
                    if (knowledgePoint != null && !knowledgePoint.isEmpty()) {
                        writer.write("<span class=\"meta-chip\">知识点: " + ExportUtils.escapeHtml(knowledgePoint) + "</span>\n");
                    }
                }
                if (ExportUtils.hasField(fields, "points")) {
                    writer.write("<span class=\"meta-chip\">分值: " + question.getPoints() + "</span>\n");
                }
                if (ExportUtils.hasField(fields, "timeLimit")) {
                    writer.write("<span class=\"meta-chip\">时限: " + question.getTimeLimit() + "秒</span>\n");
                }

                writer.write("</div>\n");

                // 题目内容
                if (ExportUtils.hasField(fields, "questionText")) {
                    String text = question.getQuestionText();
                    if (text != null && !text.isEmpty()) {
                        writer.write("<div class=\"question-text\">" + ExportUtils.escapeHtml(text) + "</div>\n");
                    }
                }

                // 选项（A~L 动态渲染，只输出模板选中且非空的选项）
                writer.write("<div class=\"options\">\n");
                for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                    if (!ExportUtils.hasField(fields, ExportUtils.OPTION_FIELDS[o])) continue;
                    Object optionValue = ExportUtils.getOptionValue(question, o);
                    if (optionValue != null && !optionValue.toString().isEmpty()) {
                        writer.write("<div class=\"option\">" + ExportUtils.OPTION_LABELS[o] + ". " + ExportUtils.escapeHtml(optionValue.toString()) + "</div>\n");
                    }
                }
                writer.write("</div>\n");

                // 正确答案 + 答案文本（受「包含答案」开关控制）
                if (includeAnswers) {
                    StringBuilder answerText = new StringBuilder();
                    if (ExportUtils.hasField(fields, "correctAnswer")) {
                        String correctAnswer = question.getCorrectAnswer();
                        if (correctAnswer != null && !correctAnswer.isEmpty()) {
                            answerText.append("正确答案: ").append(ExportUtils.escapeHtml(correctAnswer));
                        }
                    }
                    if (ExportUtils.hasField(fields, "answerText")) {
                        String answerTextValue = question.getAnswerText();
                        if (answerTextValue != null && !answerTextValue.isEmpty()) {
                            if (answerText.length() > 0) answerText.append(" ");
                            answerText.append(ExportUtils.escapeHtml(answerTextValue));
                        }
                    }
                    if (answerText.length() > 0) {
                        writer.write("<div class=\"correct\">" + answerText + "</div>\n");
                    }
                }

                // 解析（受「包含解析」开关控制）
                if (includeExplanations && ExportUtils.hasField(fields, "explanation")) {
                    String explanation = question.getExplanation();
                    if (explanation != null && !explanation.isEmpty()) {
                        writer.write("<div class=\"explanation\">\n");
                        writer.write("<h4>解析</h4>\n");
                        writer.write(ExportUtils.escapeHtml(explanation) + "\n");
                        writer.write("</div>\n");
                    }
                }

                // 详细解析
                if (includeExplanations && ExportUtils.hasField(fields, "analysis")) {
                    String analysis = question.getAnalysis();
                    if (analysis != null && !analysis.isEmpty()) {
                        writer.write("<div class=\"explanation analysis\">\n");
                        writer.write("<h4>详细解析</h4>\n");
                        writer.write(ExportUtils.escapeHtml(analysis) + "\n");
                        writer.write("</div>\n");
                    }
                }

                // 其他元信息字段（标签/提示/来源/作者/备注/相关题目等）
                StringBuilder metaLine = new StringBuilder();
                if (ExportUtils.hasField(fields, "tags")) {
                    String tags = question.getTags();
                    if (tags != null && !tags.isEmpty()) {
                        metaLine.append("标签: ").append(ExportUtils.escapeHtml(tags)).append("&nbsp;&nbsp;");
                    }
                }
                if (ExportUtils.hasField(fields, "hint")) {
                    String hint = question.getHint();
                    if (hint != null && !hint.isEmpty()) {
                        metaLine.append("提示: ").append(ExportUtils.escapeHtml(hint)).append("&nbsp;&nbsp;");
                    }
                }
                if (ExportUtils.hasField(fields, "source")) {
                    String source = question.getSource();
                    if (source != null && !source.isEmpty()) {
                        metaLine.append("来源: ").append(ExportUtils.escapeHtml(source)).append("&nbsp;&nbsp;");
                    }
                }
                if (ExportUtils.hasField(fields, "author")) {
                    String author = question.getAuthor();
                    if (author != null && !author.isEmpty()) {
                        metaLine.append("作者: ").append(ExportUtils.escapeHtml(author)).append("&nbsp;&nbsp;");
                    }
                }
                if (ExportUtils.hasField(fields, "comment")) {
                    String comment = question.getComment();
                    if (comment != null && !comment.isEmpty()) {
                        metaLine.append("备注: ").append(ExportUtils.escapeHtml(comment)).append("&nbsp;&nbsp;");
                    }
                }
                if (ExportUtils.hasField(fields, "relatedQuestion")) {
                    String relatedQuestion = question.getRelatedQuestion();
                    if (relatedQuestion != null && !relatedQuestion.isEmpty()) {
                        metaLine.append("相关题目: ").append(ExportUtils.escapeHtml(relatedQuestion)).append("&nbsp;&nbsp;");
                    }
                }
                if (metaLine.length() > 0) {
                    writer.write("<div class=\"meta-line\">" + metaLine + "</div>\n");
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