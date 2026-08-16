package com.oilquiz.app.util.export;

import android.util.Log;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportUtils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Excel导出器（现代简洁样式）
 * 导出问题为Excel格式，无框设计、浅灰底表头、清爽配色
 */
public class ExcelExporter implements Exporter {
    private static final String TAG = "ExcelExporter";
    private static final int PROGRESS_UPDATE_INTERVAL = 10;

    // 颜色常量 - 使用 IndexedColors
    private static final short HEADER_BG_COLOR = IndexedColors.DARK_BLUE.getIndex();
    private static final short HEADER_FONT_COLOR = IndexedColors.WHITE.getIndex();
    private static final short DATA_ROW_EVEN_BG = IndexedColors.LIGHT_YELLOW.getIndex();

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);

        // 创建导出文件
        File exportDir = ExportManager.getExportDirectory(task.getContext());
        if (exportDir == null) {
            throw new IOException("无法创建导出目录");
        }

        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            // 导出中文格式加导出日期和具体时间，精确到分钟
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmm");
            String timestamp = sdf.format(new java.util.Date());
            fileName = "导出题目_" + timestamp;
        }

        File file = new File(exportDir, fileName + ".xlsx");

        // 创建工作簿
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("题目导出");
            
            int rowIndex = 0;

            List<Question> questions = task.getQuestions();
            
            // 按id重新排序题目
            questions.sort((q1, q2) -> Long.compare(q1.getId(), q2.getId()));
            
            // 收集非空字段（保持选中顺序，过滤全空列）
            java.util.Set<String> nonEmptyFieldsSet = collectNonEmptyFields(task, questions);
            List<String> nonEmptyFields = new java.util.ArrayList<>(nonEmptyFieldsSet);
            
            // 按指定顺序排序字段
            sortFieldsByPriority(nonEmptyFields);
            
            // 创建表头样式：现代蓝底白字，无边框
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(HEADER_BG_COLOR);
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(HEADER_FONT_COLOR);
            headerFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            headerStyle.setWrapText(true);
            
            // 创建数据样式：无边框，左对齐
            org.apache.poi.ss.usermodel.CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.LEFT);
            dataStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            dataStyle.setWrapText(true);
            org.apache.poi.ss.usermodel.Font dataFont = workbook.createFont();
            dataFont.setFontHeightInPoints((short) 10);
            dataStyle.setFont(dataFont);
            
            // 创建偶数行样式：浅灰底
            org.apache.poi.ss.usermodel.CellStyle evenRowStyle = workbook.createCellStyle();
            evenRowStyle.cloneStyleFrom(dataStyle);
            evenRowStyle.setFillForegroundColor(DATA_ROW_EVEN_BG);
            evenRowStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            
            // 填充数据（必须先创建第 0 行：空 sheet 上 getRow(0) 返回 null 会空指针）
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int i = 0; i < nonEmptyFields.size(); i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(ExportUtils.getFieldDisplayName(nonEmptyFields.get(i)));
                cell.setCellStyle(headerStyle);
            }
            rowIndex++;
            
            // 填充数据行
            int total = questions.size();
            for (int i = 0; i < total; i++) {
                Question question = questions.get(i);
                if (question != null) {
                    Row dataRow = sheet.createRow(rowIndex++);
                    dataRow.setHeightInPoints(24);

                    for (int j = 0; j < nonEmptyFields.size(); j++) {
                        String fieldName = nonEmptyFields.get(j);
                        Object value = ExportUtils.getFormattedFieldValue(question, fieldName);
                        org.apache.poi.ss.usermodel.Cell cell = dataRow.createCell(j);
                        cell.setCellStyle((i % 2 == 0) ? evenRowStyle : dataStyle);
                        
                        // 如果是id字段，使用从1开始的序号
                        if (fieldName.equals("id")) {
                            cell.setCellValue(i + 1);
                        } else if (value != null) {
                            if (value instanceof String) {
                                cell.setCellValue((String) value);
                            } else if (value instanceof Integer) {
                                cell.setCellValue((Integer) value);
                            } else if (value instanceof Long) {
                                cell.setCellValue((Long) value);
                            } else if (value instanceof Boolean) {
                                cell.setCellValue((Boolean) value);
                            } else {
                                cell.setCellValue(value.toString());
                            }
                        } else {
                            cell.setCellValue("");
                        }
                    }
                }

                // 更新进度
                if (task.getCallback() != null && i % PROGRESS_UPDATE_INTERVAL == 0) {
                    int progress = (int) ((i + 1) * 100.0 / total);
                    task.getCallback().onExportProgress(progress);
                }
            }
            
            // 设置列宽（禁用 autoSizeColumn）
            if (!nonEmptyFields.isEmpty()) {
                applyFixedColumnWidths(sheet, nonEmptyFields);
                
                // 冻结首行
                sheet.createFreezePane(0, 1);
            }
            
            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }

        return file;
    }

    /**
     * 收集非空字段：保持 selectedFields 的选中顺序，仅保留至少一题有值的字段
     */
    private java.util.Set<String> collectNonEmptyFields(ExportManager.ExportTask task, List<Question> questions) {
        java.util.LinkedHashSet<String> nonEmptyFields = new java.util.LinkedHashSet<>();
        List<String> selectedFields = task.getConfig().getSelectedFields();
        if (selectedFields == null || selectedFields.isEmpty()) {
            selectedFields = ExportUtils.getQuestionFields();
        }

        // 按选中顺序检查每个字段，跳过收藏字段与开关关闭的字段，仅保留有值的字段
        for (String fieldName : selectedFields) {
            // 跳过收藏字段
            if (fieldName.equals("favorite")) {
                continue;
            }
            // 导出选项开关：包含答案/解析/难度（与 Markdown/PDF/Word 等导出器口径一致）
            ExportManager.ExportConfig cfg = task.getConfig();
            if (!cfg.isIncludeAnswers() && isAnswerField(fieldName)) continue;
            if (!cfg.isIncludeExplanations() && isExplanationField(fieldName)) continue;
            if (!cfg.isIncludeDifficulty() && isDifficultyField(fieldName)) continue;

            boolean hasValue = false;
            for (Question question : questions) {
                if (question == null) continue;
                Object value = ExportUtils.getFormattedFieldValue(question, fieldName);
                if (value != null && !value.toString().isEmpty()) {
                    hasValue = true;
                    break;
                }
            }
            if (hasValue) {
                nonEmptyFields.add(fieldName);
            }
        }

        // 如果没有非空字段，至少保留id字段
        if (nonEmptyFields.isEmpty() && selectedFields.contains("id")) {
            nonEmptyFields.add("id");
        }

        return nonEmptyFields;
    }

    /** 答案类字段（受「包含答案」开关控制） */
    private static boolean isAnswerField(String field) {
        return "correctAnswer".equals(field) || "answerText".equals(field)
                || (field != null && field.startsWith("blankAnswer"));
    }

    /** 解析类字段（受「包含解析」开关控制） */
    private static boolean isExplanationField(String field) {
        return "explanation".equals(field) || "analysis".equals(field);
    }

    /** 难度类字段（受「包含难度」开关控制） */
    private static boolean isDifficultyField(String field) {
        return "difficulty".equals(field) || "difficultyText".equals(field);
    }

    /**
     * 按字段类型设置差异化固定列宽（禁用 autoSizeColumn）
     */
    private void applyFixedColumnWidths(org.apache.poi.ss.usermodel.Sheet sheet, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            int width;
            if ("questionText".equals(field) || "explanation".equals(field) || "analysis".equals(field)) {
                // 长文本列：题目/解析
                width = 45 * 256;
            } else if (field != null && field.startsWith("option")) {
                // 选项列
                width = 30 * 256;
            } else if ("knowledgePoint".equals(field) || "relatedQuestion".equals(field) || "tags".equals(field) || "category".equals(field) || "subCategory".equals(field)) {
                width = 18 * 256;
            } else if ("id".equals(field) || "difficulty".equals(field) || "favorite".equals(field)
                    || (field != null && (field.startsWith("correct") || field.startsWith("answer")
                    || field.startsWith("usage") || field.startsWith("correctCount") || field.startsWith("incorrect")))) {
                // 短内容列：序号/答案/统计
                width = 12 * 256;
            } else {
                width = 25 * 256;
            }
            sheet.setColumnWidth(i, width);
        }
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

    @Override
    public String getFileExtension() {
        return "xlsx";
    }

    /**
     * 按优先级排序字段
     */
    private void sortFieldsByPriority(List<String> fields) {
        // 定义字段优先级顺序（匹配 CORE_FIELDS）
        java.util.List<String> priorityOrder = new java.util.ArrayList<>();
        priorityOrder.add("id");
        priorityOrder.add("questionType");
        priorityOrder.add("questionText");
        priorityOrder.add("optionA");
        priorityOrder.add("optionB");
        priorityOrder.add("optionC");
        priorityOrder.add("optionD");
        priorityOrder.add("optionE");
        priorityOrder.add("correctAnswer");
        priorityOrder.add("explanation");
        priorityOrder.add("knowledgePoint");
        priorityOrder.add("category");
        priorityOrder.add("difficulty");
        
        // 按优先级排序
        fields.sort((field1, field2) -> {
            int index1 = priorityOrder.indexOf(field1);
            int index2 = priorityOrder.indexOf(field2);
            if (index1 == -1 && index2 == -1) {
                return field1.compareTo(field2);
            } else if (index1 == -1) {
                return 1;
            } else if (index2 == -1) {
                return -1;
            } else {
                return Integer.compare(index1, index2);
            }
        });
    }

    @Override
    public String getFormatName() {
        return "Excel";
    }
    
    /**
     * 生成Excel模板文件
     * @param file 模板文件
     * @param callback 导出回调
     */
    public static void generateExcelTemplate(File file, ExportManager.ExportCallback callback) {
        List<Question> templateQuestions = new java.util.ArrayList<>();
        
        // 添加示例题目
        Question sampleQuestion1 = new Question();
        sampleQuestion1.setQuestionType("单选题");
        sampleQuestion1.setQuestionText("示例题目1：下列哪个是正确答案？");
        sampleQuestion1.setOptionA("选项A");
        sampleQuestion1.setOptionB("选项B");
        sampleQuestion1.setOptionC("选项C");
        sampleQuestion1.setOptionD("选项D");
        sampleQuestion1.setCorrectAnswer("A");
        sampleQuestion1.setDifficulty(1);
        sampleQuestion1.setExplanation("这是示例题目的解析");
        templateQuestions.add(sampleQuestion1);

        Question sampleQuestion2 = new Question();
        sampleQuestion2.setQuestionType("多选题");
        sampleQuestion2.setQuestionText("示例题目2：下列哪些是正确答案？");
        sampleQuestion2.setOptionA("选项A");
        sampleQuestion2.setOptionB("选项B");
        sampleQuestion2.setOptionC("选项C");
        sampleQuestion2.setOptionD("选项D");
        sampleQuestion2.setCorrectAnswer("AB");
        sampleQuestion2.setDifficulty(2);
        sampleQuestion2.setExplanation("这是多选题的示例解析");
        templateQuestions.add(sampleQuestion2);

        // 创建导出任务
        ExportManager.ExportTask task = new ExportManager.ExportTask();
        ExportManager.ExportConfig config = new ExportManager.ExportConfig();
        config.setIncludeAnswers(true);
        config.setIncludeExplanations(true);
        config.setIncludeDifficulty(true);
        config.setAutoSizeColumns(true);
        task.setConfig(config);
        task.setQuestions(templateQuestions);
        task.setCallback(callback);

        // 执行导出
        ExcelExporter exporter = new ExcelExporter();
        try {
            if (callback != null) {
                callback.onExportStart();
            }
            File exportedFile = exporter.export(task);
            if (callback != null) {
                callback.onExportComplete(exportedFile);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating Excel template: " + e.getMessage(), e);
            if (callback != null) {
                callback.onExportError("生成模板失败: " + e.getMessage());
            }
        }
    }
}