package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Excel模板导出器
 * 支持基于模板的Excel导出
 */
public class ExcelTemplateExporter extends TemplateBasedExporter {

    public ExcelTemplateExporter(Template template) {
        super(template);
    }

    @Override
    protected File doExport(ExportManager.ExportTask task) throws Exception {
        List<Question> questions = task.getQuestions();
        String fileName = task.getConfig().getFileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = "export_" + System.currentTimeMillis();
        }
        File exportFile = new File(com.oilquiz.app.util.export.ExportManager.getExportDirectory(task.getContext()), fileName + "." + getFileExtension());

        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(exportFile)) {
            
            // 创建工作表
            Sheet sheet = workbook.createSheet("题库导出");
            
            // 写入表头
            writeExcelHeader(sheet, task);
            
            // 写入数据
            writeExcelData(sheet, questions, task);
            
            // 自动调整列宽
            if (task.getConfig().isAutoSizeColumns()) {
                autoSizeColumns(sheet);
            }
            
            workbook.write(fos);
        }

        return exportFile;
    }

    /**
     * 写入Excel表头
     */
    private void writeExcelHeader(Sheet sheet, ExportManager.ExportTask task) {
        List<String> fields = getFieldsToExport(task);
        Row headerRow = sheet.createRow(0);
        
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            String displayName = FieldMapper.getFieldDisplayName(template, field);
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(displayName);
            
            // 设置表头样式
            CellStyle headerStyle = sheet.getWorkbook().createCellStyle();
            Font font = sheet.getWorkbook().createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);
            cell.setCellStyle(headerStyle);
        }
    }

    /**
     * 写入Excel数据
     */
    private void writeExcelData(Sheet sheet, List<Question> questions, ExportManager.ExportTask task) {
        List<String> fields = getFieldsToExport(task);
        
        for (int rowIndex = 0; rowIndex < questions.size(); rowIndex++) {
            Question question = questions.get(rowIndex);
            Row row = sheet.createRow(rowIndex + 1);
            
            for (int colIndex = 0; colIndex < fields.size(); colIndex++) {
                String field = fields.get(colIndex);
                Object value = FieldMapper.getFieldValue(question, field);
                Cell cell = row.createCell(colIndex);
                
                if (value != null) {
                    if (value instanceof String) {
                        cell.setCellValue((String) value);
                    } else if (value instanceof Number) {
                        cell.setCellValue(((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        cell.setCellValue((Boolean) value);
                    } else {
                        cell.setCellValue(value.toString());
                    }
                }
                
                // 设置单元格样式
                CellStyle cellStyle = sheet.getWorkbook().createCellStyle();
                cellStyle.setBorderTop(BorderStyle.THIN);
                cellStyle.setBorderBottom(BorderStyle.THIN);
                cellStyle.setBorderLeft(BorderStyle.THIN);
                cellStyle.setBorderRight(BorderStyle.THIN);
                cell.setCellStyle(cellStyle);
            }
        }
    }

    /**
     * 自动调整列宽
     */
    private void autoSizeColumns(Sheet sheet) {
        for (int i = 0; i < sheet.getRow(0).getLastCellNum(); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * 获取要导出的字段
     */
    private List<String> getFieldsToExport(ExportManager.ExportTask task) {
        List<String> selectedFields = task.getConfig().getSelectedFields();
        if (selectedFields != null && !selectedFields.isEmpty()) {
            return selectedFields;
        }
        if (template != null && template.getFields() != null) {
            return template.getFields();
        }
        // 默认字段
        return FieldMapper.getAllAvailableFields().keySet().stream().toList();
    }

    @Override
    public String getFileExtension() {
        return "xlsx";
    }
}
