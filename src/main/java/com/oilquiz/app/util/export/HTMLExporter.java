package com.oilquiz.app.util.export;

import android.util.Log;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HTMLExporter implements Exporter {
    private static final String TAG = "HTMLExporter";

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        Log.i(TAG, "=== HTML Export started ===");
        validateParameters(task);

        List<Question> questions = task.getQuestions();
        
        // 安全处理：确保 questions 不为 null
        if (questions == null) {
            Log.w(TAG, "Questions list is null");
            questions = java.util.Collections.emptyList();
        }
        Log.i(TAG, "Questions count: " + questions.size());
        
        // 模板生效字段列表（场景模板/自定义字段），决定导出内容与顺序
        List<String> fields = ExportUtils.getEffectiveFields(task.getConfig());
        Log.i(TAG, "Effective fields: " + fields.size() + " - " + fields);
        
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }
        Log.i(TAG, "Output file name: " + fileName);
        
        File exportFile = new File(ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());
        Log.i(TAG, "Export file path: " + exportFile.getAbsolutePath());

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
            // 筛选和显示样式
            writer.write("  * {\n");
            writer.write("    box-sizing: border-box;\n");
            writer.write("    margin: 0;\n");
            writer.write("    padding: 0;\n");
            writer.write("  }\n");
            writer.write("  body {\n");
            writer.write("    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;\n");
            writer.write("    background: #F5F7FA;\n");
            writer.write("    color: #1F2937;\n");
            writer.write("    line-height: 1.6;\n");
            writer.write("    padding: 12px;\n");
            writer.write("    -webkit-text-size-adjust: 100%;\n");
            writer.write("    -webkit-tap-highlight-color: transparent;\n");
            writer.write("  }\n");
            writer.write("  .container {\n");
            writer.write("    max-width: 100%;\n");
            writer.write("    margin: 0 auto;\n");
            writer.write("    padding-bottom: 24px;\n");
            writer.write("  }\n");
            writer.write("  /* 筛选栏 */\n");
            writer.write("  .filter-bar {\n");
            writer.write("    display: flex;\n");
            writer.write("    gap: 8px;\n");
            writer.write("    margin-bottom: 16px;\n");
            writer.write("    overflow-x: auto;\n");
            writer.write("    padding: 4px 0;\n");
            writer.write("    -webkit-overflow-scrolling: touch;\n");
            writer.write("  }\n");
            writer.write("  .filter-btn {\n");
            writer.write("    padding: 8px 16px;\n");
            writer.write("    border: 1px solid #E5E7EB;\n");
            writer.write("    background: white;\n");
            writer.write("    border-radius: 20px;\n");
            writer.write("    font-size: 14px;\n");
            writer.write("    cursor: pointer;\n");
            writer.write("    white-space: nowrap;\n");
            writer.write("    transition: all 0.2s;\n");
            writer.write("  }\n");
            writer.write("  .filter-btn:active {\n");
            writer.write("    transform: scale(0.95);\n");
            writer.write("  }\n");
            writer.write("  .filter-btn.active {\n");
            writer.write("    background: #4285F4;\n");
            writer.write("    color: white;\n");
            writer.write("    border-color: #4285F4;\n");
            writer.write("  }\n");
            writer.write("  /* 题目卡片 */\n");
            writer.write("  .question {\n");
            writer.write("    background: white;\n");
            writer.write("    border-radius: 10px;\n");
            writer.write("    margin-bottom: 12px;\n");
            writer.write("    box-shadow: 0 1px 2px rgba(0,0,0,0.06);\n");
            writer.write("    overflow: hidden;\n");
            writer.write("    border: 1px solid #F0F2F5;\n");
            writer.write("    transition: all 0.3s;\n");
            writer.write("  }\n");
            writer.write("  .question.hidden {\n");
            writer.write("    display: none;\n");
            writer.write("  }\n");
            writer.write("  .question-header {\n");
            writer.write("    padding: 14px 16px;\n");
            writer.write("    cursor: pointer;\n");
            writer.write("    display: flex;\n");
            writer.write("    align-items: center;\n");
            writer.write("    user-select: none;\n");
            writer.write("    -webkit-tap-highlight-color: transparent;\n");
            writer.write("  }\n");
            writer.write("  .question-header:active {\n");
            writer.write("    background: #F9FAFB;\n");
            writer.write("  }\n");
            writer.write("  .question-number {\n");
            writer.write("    width: 24px;\n");
            writer.write("    height: 24px;\n");
            writer.write("    background: #4285F4;\n");
            writer.write("    color: white;\n");
            writer.write("    border-radius: 50%;\n");
            writer.write("    display: flex;\n");
            writer.write("    align-items: center;\n");
            writer.write("    justify-content: center;\n");
            writer.write("    font-size: 13px;\n");
            writer.write("    font-weight: 600;\n");
            writer.write("    margin-right: 10px;\n");
            writer.write("    flex-shrink: 0;\n");
            writer.write("  }\n");
            writer.write("  .meta-tags {\n");
            writer.write("    display: flex;\n");
            writer.write("    flex-wrap: wrap;\n");
            writer.write("    gap: 6px;\n");
            writer.write("    flex: 1;\n");
            writer.write("  }\n");
            writer.write("  .badge {\n");
            writer.write("    padding: 3px 10px;\n");
            writer.write("    border-radius: 12px;\n");
            writer.write("    font-size: 12px;\n");
            writer.write("    font-weight: 500;\n");
            writer.write("  }\n");
            writer.write("  .badge-type {\n");
            writer.write("    background: #E8F0FE;\n");
            writer.write("    color: #1967D2;\n");
            writer.write("  }\n");
            writer.write("  .badge-difficulty {\n");
            writer.write("    background: #FEF7E0;\n");
            writer.write("    color: #F59E0B;\n");
            writer.write("  }\n");
            writer.write("  .badge-category {\n");
            writer.write("    background: #F3F4F6;\n");
            writer.write("    color: #6B7280;\n");
            writer.write("  }\n");
            writer.write("  .toggle-icon {\n");
            writer.write("    color: #9CA3AF;\n");
            writer.write("    font-size: 14px;\n");
            writer.write("    margin-left: auto;\n");
            writer.write("    transition: transform 0.2s;\n");
            writer.write("  }\n");
            writer.write("  .question.expanded .toggle-icon {\n");
            writer.write("    transform: rotate(180deg);\n");
            writer.write("  }\n");
            writer.write("  .question-body {\n");
            writer.write("    padding: 0 16px 16px;\n");
            writer.write("    max-height: 3000px;\n");
            writer.write("    overflow: visible;\n");
            writer.write("  }\n");
            writer.write("  .question-text {\n");
            writer.write("    font-size: 15px;\n");
            writer.write("    font-weight: 600;\n");
            writer.write("    color: #111827;\n");
            writer.write("    margin-bottom: 12px;\n");
            writer.write("    line-height: 1.6;\n");
            writer.write("  }\n");
            writer.write("  .option {\n");
            writer.write("    padding: 10px 14px;\n");
            writer.write("    margin-bottom: 6px;\n");
            writer.write("    background: #F9FAFB;\n");
            writer.write("    border-radius: 8px;\n");
            writer.write("    font-size: 14px;\n");
            writer.write("    color: #374151;\n");
            writer.write("  }\n");
            writer.write("  .answer-box {\n");
            writer.write("    background: #F0FDF4;\n");
            writer.write("    border-left: 3px solid #22C55E;\n");
            writer.write("    padding: 12px 14px;\n");
            writer.write("    border-radius: 6px;\n");
            writer.write("    margin-bottom: 10px;\n");
            writer.write("    display: none;\n");
            writer.write("    font-size: 14px;\n");
            writer.write("    color: #15803D;\n");
            writer.write("  }\n");
            writer.write("  .answer-box.show {\n");
            writer.write("    display: block;\n");
            writer.write("  }\n");
            writer.write("  .explanation-box {\n");
            writer.write("    background: #EFF6FF;\n");
            writer.write("    border-left: 3px solid #3B82F6;\n");
            writer.write("    padding: 12px 14px;\n");
            writer.write("    border-radius: 6px;\n");
            writer.write("    display: none;\n");
            writer.write("    font-size: 14px;\n");
            writer.write("    color: #1E40AF;\n");
            writer.write("  }\n");
            writer.write("  .explanation-box.show {\n");
            writer.write("    display: block;\n");
            writer.write("  }\n");
            writer.write("  .show-answer-btn {\n");
            writer.write("    display: block;\n");
            writer.write("    width: 100%;\n");
            writer.write("    padding: 14px;\n");
            writer.write("    background: #4285F4;\n");
            writer.write("    color: white;\n");
            writer.write("    border: none;\n");
            writer.write("    border-radius: 10px;\n");
            writer.write("    font-size: 16px;\n");
            writer.write("    cursor: pointer;\n");
            writer.write("    margin-bottom: 16px;\n");
            writer.write("    text-align: center;\n");
            writer.write("    font-weight: 600;\n");
            writer.write("    box-shadow: 0 2px 6px rgba(66,133,244,0.25);\n");
            writer.write("  }\n");
            writer.write("  .show-answer-btn:active {\n");
            writer.write("    background: #3367D6;\n");
            writer.write("  }\n");
            writer.write("</style>\n");
            writer.write("<script>\n");
            writer.write("document.addEventListener('DOMContentLoaded', function() {\n");
            writer.write("  const showAnswerBtn = document.getElementById('show-answer-btn');\n");
            writer.write("  const questionCards = document.querySelectorAll('.question');\n");
            writer.write("  const filterBtns = document.querySelectorAll('.filter-btn');\n");
            writer.write("  let allAnswerShown = true;\n");
            writer.write("\n");
            writer.write("  // 默认显示所有答案\n");
            writer.write("  const answerBoxes = document.querySelectorAll('.answer-box');\n");
            writer.write("  const explanationBoxes = document.querySelectorAll('.explanation-box');\n");
            writer.write("  answerBoxes.forEach(box => box.classList.add('show'));\n");
            writer.write("  explanationBoxes.forEach(box => box.classList.add('show'));\n");
            writer.write("  if (showAnswerBtn) showAnswerBtn.textContent = '隐藏答案';\n");
            writer.write("\n");
            writer.write("  // 点击按钮切换所有答案显示/隐藏\n");
            writer.write("  if (showAnswerBtn) {\n");
            writer.write("    showAnswerBtn.addEventListener('click', function() {\n");
            writer.write("      allAnswerShown = !allAnswerShown;\n");
            writer.write("      answerBoxes.forEach(box => box.classList.toggle('show', allAnswerShown));\n");
            writer.write("      explanationBoxes.forEach(box => box.classList.toggle('show', allAnswerShown));\n");
            writer.write("      this.textContent = allAnswerShown ? '隐藏答案' : '显示答案';\n");
            writer.write("    });\n");
            writer.write("  }\n");
            writer.write("\n");
            writer.write("  // 点击题目显示/隐藏该题答案\n");
            writer.write("  questionCards.forEach(card => {\n");
            writer.write("    const header = card.querySelector('.question-header');\n");
            writer.write("    header.addEventListener('click', function() {\n");
            writer.write("      const answerBox = card.querySelector('.answer-box');\n");
            writer.write("      const explanationBox = card.querySelector('.explanation-box');\n");
            writer.write("      if (answerBox && explanationBox) {\n");
            writer.write("        const isHidden = !answerBox.classList.contains('show');\n");
            writer.write("        answerBox.classList.toggle('show', isHidden);\n");
            writer.write("        explanationBox.classList.toggle('show', isHidden);\n");
            writer.write("      }\n");
            writer.write("    });\n");
            writer.write("  });\n");
            writer.write("\n");
            writer.write("  // 按题型筛选\n");
            writer.write("  filterBtns.forEach(btn => {\n");
            writer.write("    btn.addEventListener('click', function() {\n");
            writer.write("      const type = this.dataset.type;\n");
            writer.write("      const isActive = this.classList.contains('active');\n");
            writer.write("\n");
            writer.write("      // 取消所有按钮的选中状态\n");
            writer.write("      filterBtns.forEach(b => b.classList.remove('active'));\n");
            writer.write("\n");
            writer.write("      if (!isActive) {\n");
            writer.write("        this.classList.add('active');\n");
            writer.write("        // 筛选显示该类型题目\n");
            writer.write("        questionCards.forEach(card => {\n");
            writer.write("          const badge = card.querySelector('.badge[data-question-type]');\n");
            writer.write("          if (badge && badge.dataset.questionType === type) {\n");
            writer.write("            card.classList.remove('hidden');\n");
            writer.write("          } else if (badge) {\n");
            writer.write("            card.classList.add('hidden');\n");
            writer.write("          }\n");
            writer.write("        });\n");
            writer.write("      } else {\n");
            writer.write("        // 显示所有题目\n");
            writer.write("        questionCards.forEach(card => card.classList.remove('hidden'));\n");
            writer.write("      }\n");
            writer.write("    });\n");
            writer.write("  });\n");
            writer.write("});\n");
            writer.write("</script>\n");
            writer.write("</head>\n");
            writer.write("<body>\n");
            writer.write("<div class=\"container\">\n");
            
            // 显示答案按钮
            writer.write("<button class=\"show-answer-btn\" id=\"show-answer-btn\">隐藏答案</button>\n");
            
            // 筛选按钮容器
            writer.write("<div class=\"filter-bar\" id=\"filter-bar\">\n");
            
            // 写入问题数据
            int questionNumber = 1;
            int total = questions.size();
            boolean includeAnswers = task.getConfig().isIncludeAnswers();
            boolean includeExplanations = task.getConfig().isIncludeExplanations();
            
        // 收集所有题型（安全处理 null）
        java.util.Set<String> questionTypes = new java.util.LinkedHashSet<>();
        questionTypes.add("所有");
        for (Question q : questions) {
            if (q != null && ExportUtils.hasField(fields, "questionType")) {
                String type = q.getQuestionType();
                if (type != null && !type.isEmpty()) {
                    questionTypes.add(type);
                }
            }
        }
            
            // 输出筛选按钮
            for (String type : questionTypes) {
                writer.write("<button class=\"filter-btn\" data-type=\"" + ExportUtils.escapeHtml(type) + "\">" + ExportUtils.escapeHtml(type) + "</button>\n");
            }
            writer.write("</div>\n");
            
            for (int i = 0; i < total; i++) {
                Question question = questions.get(i);
                
                // 安全处理 null 题目
                if (question == null) {
                    Log.w(TAG, "Question at index " + i + " is null, skipping");
                    questionNumber++;
                    continue;
                }
                
                String typeClass = ExportUtils.hasField(fields, "questionType") ? (question.getQuestionType() != null ? question.getQuestionType() : "") : "";
                writer.write("<div class=\"question\" " + (typeClass.isEmpty() ? "" : "data-question-type=\"" + ExportUtils.escapeHtml(typeClass) + "\"") + ">\n");
                
                // 题目头部
                writer.write("<div class=\"question-header\">\n");
                writer.write("<span class=\"question-number\">" + questionNumber + "</span>\n");
                writer.write("<div class=\"meta-tags\">\n");
                writer.write("<span class=\"badge badge-type\">第 " + questionNumber + " 题</span>\n");
                if (ExportUtils.hasField(fields, "questionType")) {
                    String type = question.getQuestionType();
                    if (type != null && !type.isEmpty()) {
                        writer.write("<span class=\"badge badge-type\" data-question-type=\"" + ExportUtils.escapeHtml(type) + "\">" + ExportUtils.escapeHtml(type) + "</span>\n");
                    }
                }
                if (ExportUtils.hasField(fields, "difficulty")) {
                    String diffText = question.getDifficultyText();
                    if (diffText != null && !diffText.isEmpty()) {
                        writer.write("<span class=\"badge badge-difficulty\">" + ExportUtils.escapeHtml(diffText) + "</span>\n");
                    }
                }
                writer.write("</div>\n");
                writer.write("<span class=\"toggle-icon\">▼</span>\n");
                writer.write("</div>\n");
                
                // 题目详情（默认折叠）
                writer.write("<div class=\"question-body\">\n");
                
                // 题目内容
                if (ExportUtils.hasField(fields, "questionText")) {
                    String text = question.getQuestionText();
                    if (text != null && !text.isEmpty()) {
                        writer.write("<div class=\"question-text\">" + ExportUtils.escapeHtml(text) + "</div>\n");
                    }
                }

                // 选项（安全处理 null）
                writer.write("<div class=\"options\">\n");
                for (int o = 0; o < ExportUtils.OPTION_FIELDS.length; o++) {
                    if (!ExportUtils.hasField(fields, ExportUtils.OPTION_FIELDS[o])) continue;
                    Object optionValue = ExportUtils.getOptionValue(question, o);
                    if (optionValue != null) {
                        String optionStr = optionValue.toString();
                        if (optionStr != null && !optionStr.isEmpty()) {
                            writer.write("<div class=\"option\">" + ExportUtils.OPTION_LABELS[o] + ". " + ExportUtils.escapeHtml(optionStr) + "</div>\n");
                        }
                    }
                }
                writer.write("</div>\n");

                // 分类和子分类
                if (ExportUtils.hasField(fields, "category") && question.getCategory() != null && !question.getCategory().isEmpty()) {
                    writer.write("<div class=\"meta-info\">分类: " + ExportUtils.escapeHtml(question.getCategory()) + "</div>\n");
                }
                if (ExportUtils.hasField(fields, "subCategory") && question.getSubCategory() != null && !question.getSubCategory().isEmpty()) {
                    writer.write("<div class=\"meta-info\">子分类: " + ExportUtils.escapeHtml(question.getSubCategory()) + "</div>\n");
                }
                if (ExportUtils.hasField(fields, "knowledgePoint") && question.getKnowledgePoint() != null && !question.getKnowledgePoint().isEmpty()) {
                    writer.write("<div class=\"meta-info\">知识点: " + ExportUtils.escapeHtml(question.getKnowledgePoint()) + "</div>\n");
                }
                if (ExportUtils.hasField(fields, "points")) {
                    Integer points = question.getPoints();
                    if (points != null && points > 0) {
                        writer.write("<div class=\"meta-info\">分值: " + points + "分</div>\n");
                    }
                }

                // 正确答案
                if (includeAnswers) {
                    StringBuilder answerText = new StringBuilder();
                    if (ExportUtils.hasField(fields, "correctAnswer")) {
                        String correctAnswer = question.getCorrectAnswer();
                        if (correctAnswer != null && !correctAnswer.isEmpty()) {
                            answerText.append("✅ 正确答案: ").append(ExportUtils.escapeHtml(correctAnswer));
                        }
                    }
                    if (ExportUtils.hasField(fields, "answerText")) {
                        String answerTxt = question.getAnswerText();
                        if (answerTxt != null && !answerTxt.isEmpty()) {
                            if (answerText.length() > 0) answerText.append("<br>");
                            answerText.append("答案文本: ").append(ExportUtils.escapeHtml(answerTxt));
                        }
                    }
                    if (answerText.length() > 0) {
                        writer.write("<div class=\"answer-box\">" + answerText + "</div>\n");
                    }
                }

                // 解析
                if (includeExplanations && ExportUtils.hasField(fields, "explanation")) {
                    String explanation = question.getExplanation();
                    if (explanation != null && !explanation.isEmpty()) {
                        writer.write("<div class=\"explanation-box\">💡 <strong>解析</strong>: " + ExportUtils.escapeHtml(explanation) + "</div>\n");
                    }
                }
                if (includeExplanations && ExportUtils.hasField(fields, "analysis")) {
                    String analysis = question.getAnalysis();
                    if (analysis != null && !analysis.isEmpty()) {
                        writer.write("<div class=\"explanation-box\">📖 <strong>详细解析</strong>: " + ExportUtils.escapeHtml(analysis) + "</div>\n");
                    }
                }

                // 其他元信息
                StringBuilder extraInfo = new StringBuilder();
                if (ExportUtils.hasField(fields, "tags") && question.getTags() != null && !question.getTags().isEmpty()) {
                    extraInfo.append("🏷️ 标签: ").append(ExportUtils.escapeHtml(question.getTags()));
                }
                if (ExportUtils.hasField(fields, "hint") && question.getHint() != null && !question.getHint().isEmpty()) {
                    if (extraInfo.length() > 0) extraInfo.append("<br>");
                    extraInfo.append("💡 提示: ").append(ExportUtils.escapeHtml(question.getHint()));
                }
                if (ExportUtils.hasField(fields, "source") && question.getSource() != null && !question.getSource().isEmpty()) {
                    if (extraInfo.length() > 0) extraInfo.append("<br>");
                    extraInfo.append("📚 来源: ").append(ExportUtils.escapeHtml(question.getSource()));
                }
                if (ExportUtils.hasField(fields, "author") && question.getAuthor() != null && !question.getAuthor().isEmpty()) {
                    if (extraInfo.length() > 0) extraInfo.append("<br>");
                    extraInfo.append("✍️ 作者: ").append(ExportUtils.escapeHtml(question.getAuthor()));
                }
                if (ExportUtils.hasField(fields, "comment") && question.getComment() != null && !question.getComment().isEmpty()) {
                    if (extraInfo.length() > 0) extraInfo.append("<br>");
                    extraInfo.append("📝 备注: ").append(ExportUtils.escapeHtml(question.getComment()));
                }
                if (ExportUtils.hasField(fields, "relatedQuestion") && question.getRelatedQuestion() != null && !question.getRelatedQuestion().isEmpty()) {
                    if (extraInfo.length() > 0) extraInfo.append("<br>");
                    extraInfo.append("🔗 相关题目: ").append(ExportUtils.escapeHtml(question.getRelatedQuestion()));
                }
                if (extraInfo.length() > 0) {
                    writer.write("<div class=\"extra-info\">" + extraInfo + "</div>\n");
                }
                
                writer.write("</div>\n"); // question-body
                writer.write("</div>\n"); // question
                
                questionNumber++;

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
            
            writer.write("</div>\n"); // container
            writer.write("</body>\n");
            writer.write("</html>\n");
            Log.i(TAG, "HTML content written successfully");
            
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