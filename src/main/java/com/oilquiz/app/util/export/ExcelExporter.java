package com.oilquiz.app.util.export;

import android.util.Log;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportUtils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Excel导出器
 * 导出问题为Excel格式
 */
public class ExcelExporter implements Exporter {
    private static final String TAG = "ExcelExporter";
    private static final int PROGRESS_UPDATE_INTERVAL = 10;

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
            Sheet sheet = workbook.createSheet("Questions");
            
            int rowIndex = 0;

            List<Question> questions = task.getQuestions();
            
            // 按id重新排序题目
            questions.sort((q1, q2) -> Long.compare(q1.getId(), q2.getId()));
            
            // 收集所有非空字段
            java.util.Set<String> nonEmptyFieldsSet = collectNonEmptyFields(task, questions);
            List<String> nonEmptyFields = new java.util.ArrayList<>(nonEmptyFieldsSet);
            
            // 按指定顺序排序字段
            sortFieldsByPriority(nonEmptyFields);
            
            // 创建样式
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            
            org.apache.poi.ss.usermodel.CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.LEFT);
            dataStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            dataStyle.setWrapText(true);
            
            // 添加标题
            Row titleRow = sheet.createRow(rowIndex++);
            org.apache.poi.ss.usermodel.Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("题目导出报告");
            org.apache.poi.ss.usermodel.CellStyle titleStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            titleCell.setCellStyle(titleStyle);
            // 只在有字段时合并单元格
            if (!nonEmptyFields.isEmpty()) {
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, nonEmptyFields.size() - 1));
            }
            rowIndex++;
            
            // 添加导出信息
            Row infoRow1 = sheet.createRow(rowIndex++);
            infoRow1.createCell(0).setCellValue("导出时间:");
            infoRow1.createCell(1).setCellValue(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            
            Row infoRow2 = sheet.createRow(rowIndex++);
            infoRow2.createCell(0).setCellValue("导出题目数量:");
            infoRow2.createCell(1).setCellValue(questions.size());
            
            Row infoRow3 = sheet.createRow(rowIndex++);
            infoRow3.createCell(0).setCellValue("导出格式:");
            infoRow3.createCell(1).setCellValue("Excel");
            rowIndex++;
            
            // 添加表头
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < nonEmptyFields.size(); i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(ExportUtils.getFieldDisplayName(nonEmptyFields.get(i)));
                cell.setCellStyle(headerStyle);
            }
            
            // 填充数据
            int total = questions.size();
            for (int i = 0; i < total; i++) {
                Question question = questions.get(i);
                if (question != null) {
                    Row dataRow = sheet.createRow(rowIndex++);

                    for (int j = 0; j < nonEmptyFields.size(); j++) {
                        String fieldName = nonEmptyFields.get(j);
                        Object value = ExportUtils.getFieldValue(question, fieldName);
                        org.apache.poi.ss.usermodel.Cell cell = dataRow.createCell(j);
                        cell.setCellStyle(dataStyle);
                        
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
            
            // 自动调整列宽
            if (task.getConfig().isAutoSizeColumns() && !nonEmptyFields.isEmpty()) {
                int columnCount = sheet.getRow(0).getLastCellNum();
                for (int i = 0; i < columnCount; i++) {
                    sheet.autoSizeColumn(i);
                    // 确保列宽足够大
                    if (sheet.getColumnWidth(i) < 10 * 256) {
                        sheet.setColumnWidth(i, 15 * 256);
                    }
                }
            }
            
            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }

        return file;
    }

    /**
     * 收集所有非空字段
     */
    private java.util.Set<String> collectNonEmptyFields(ExportManager.ExportTask task, List<Question> questions) {
        java.util.Set<String> nonEmptyFields = new java.util.HashSet<>();
        List<String> selectedFields = task.getConfig().getSelectedFields();
        if (selectedFields == null || selectedFields.isEmpty()) {
            selectedFields = ExportUtils.getQuestionFields();
        }

        // 检查每个题目，收集非空字段，排除收藏字段
        for (Question question : questions) {
            for (String fieldName : selectedFields) {
                // 跳过收藏字段
                if (fieldName.equals("favorite")) {
                    continue;
                }
                
                Object value = ExportUtils.getFieldValue(question, fieldName);
                if (value != null && !value.toString().isEmpty()) {
                    nonEmptyFields.add(fieldName);
                }
            }
        }

        // 如果没有非空字段，至少保留id字段
        if (nonEmptyFields.isEmpty() && selectedFields.contains("id")) {
            nonEmptyFields.add("id");
        }

        return nonEmptyFields;
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
        // 定义字段优先级顺序
        java.util.List<String> priorityOrder = new java.util.ArrayList<>();
        priorityOrder.add("id");
        priorityOrder.add("questionType");
        priorityOrder.add("questionText");
        priorityOrder.add("optionA");
        priorityOrder.add("optionB");
        priorityOrder.add("optionC");
        priorityOrder.add("optionD");
        priorityOrder.add("correctAnswer");
        priorityOrder.add("explanation");
        priorityOrder.add("category");
        priorityOrder.add("difficulty");
        priorityOrder.add("relatedQuestion");
        
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