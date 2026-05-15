package com.oilquiz.app.util;

import android.util.Log;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Office文件生成工具类
 * 用于生成Word和Excel文件
 */
public class OfficeGeneratorUtil {
    private static final String TAG = "OfficeGeneratorUtil";

    /**
     * 生成简单的Word文档
     * @param file 目标文件
     * @param title 标题
     * @param content 内容
     * @return 是否成功
     */
    public static boolean generateSimpleWord(File file, String title, String content) {
        try (XWPFDocument document = new XWPFDocument()) {
            // 创建标题
            if (title != null && !title.isEmpty()) {
                XWPFParagraph titlePara = document.createParagraph();
                titlePara.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(title);
                titleRun.setFontSize(18);
                titleRun.setBold(true);
                titleRun.addBreak();
                titleRun.addBreak();
            }

            // 创建内容段落
            if (content != null && !content.isEmpty()) {
                XWPFParagraph contentPara = document.createParagraph();
                XWPFRun contentRun = contentPara.createRun();
                contentRun.setText(content);
                contentRun.setFontSize(12);
            }

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(file)) {
                document.write(fos);
            }

            Log.i(TAG, "Word文档生成成功: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "生成Word文档失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成带段落的Word文档
     * @param file 目标文件
     * @param title 标题
     * @param paragraphs 段落列表
     * @return 是否成功
     */
    public static boolean generateWordWithParagraphs(File file, String title, List<String> paragraphs) {
        try (XWPFDocument document = new XWPFDocument()) {
            // 创建标题
            if (title != null && !title.isEmpty()) {
                XWPFParagraph titlePara = document.createParagraph();
                titlePara.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(title);
                titleRun.setFontSize(18);
                titleRun.setBold(true);
                titleRun.addBreak();
            }

            // 创建内容段落
            if (paragraphs != null) {
                for (String paragraph : paragraphs) {
                    if (paragraph != null && !paragraph.isEmpty()) {
                        XWPFParagraph para = document.createParagraph();
                        para.setAlignment(ParagraphAlignment.LEFT);
                        XWPFRun run = para.createRun();
                        run.setText(paragraph);
                        run.setFontSize(12);
                        run.addBreak();
                    }
                }
            }

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(file)) {
                document.write(fos);
            }

            Log.i(TAG, "Word文档生成成功: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "生成Word文档失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成简单的Excel文件
     * @param file 目标文件
     * @param sheetName 工作表名称
     * @param headers 表头
     * @param data 数据行
     * @return 是否成功
     */
    public static boolean generateSimpleExcel(File file, String sheetName, 
                                               String[] headers, List<String[]> data) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName != null ? sheetName : "Sheet1");

            // 创建表头行
            if (headers != null && headers.length > 0) {
                Row headerRow = sheet.createRow(0);
                CellStyle headerStyle = createHeaderStyle(workbook);
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }
            }

            // 创建数据行
            if (data != null) {
                int rowIndex = headers != null ? 1 : 0;
                for (String[] rowData : data) {
                    Row row = sheet.createRow(rowIndex++);
                    if (rowData != null) {
                        for (int i = 0; i < rowData.length; i++) {
                            Cell cell = row.createCell(i);
                            cell.setCellValue(rowData[i]);
                        }
                    }
                }
            }

            // 自动调整列宽
            if (headers != null) {
                for (int i = 0; i < headers.length; i++) {
                    sheet.autoSizeColumn(i);
                }
            }

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }

            Log.i(TAG, "Excel文件生成成功: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "生成Excel文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成带样式的Excel文件
     * @param file 目标文件
     * @param sheetName 工作表名称
     * @param title 标题（跨列）
     * @param headers 表头
     * @param data 数据行
     * @return 是否成功
     */
    public static boolean generateStyledExcel(File file, String sheetName, 
                                               String title, String[] headers, 
                                               List<String[]> data) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName != null ? sheetName : "Sheet1");

            int currentRowIndex = 0;

            // 创建标题
            if (title != null && !title.isEmpty()) {
                Row titleRow = sheet.createRow(currentRowIndex++);
                Cell titleCell = titleRow.createCell(0);
                titleCell.setCellValue(title);
                CellStyle titleStyle = createTitleStyle(workbook);
                titleCell.setCellStyle(titleStyle);
                
                // 合并单元格
                if (headers != null && headers.length > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.length - 1));
                }
            }

            // 创建表头行
            if (headers != null && headers.length > 0) {
                Row headerRow = sheet.createRow(currentRowIndex++);
                CellStyle headerStyle = createHeaderStyle(workbook);
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }
            }

            // 创建数据行
            if (data != null) {
                for (String[] rowData : data) {
                    Row row = sheet.createRow(currentRowIndex++);
                    if (rowData != null) {
                        for (int i = 0; i < rowData.length; i++) {
                            Cell cell = row.createCell(i);
                            cell.setCellValue(rowData[i]);
                        }
                    }
                }
            }

            // 自动调整列宽
            if (headers != null) {
                for (int i = 0; i < headers.length; i++) {
                    sheet.autoSizeColumn(i);
                }
            }

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }

            Log.i(TAG, "Excel文件生成成功: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "生成Excel文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建标题样式
     */
    private static CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    /**
     * 创建表头样式
     */
    private static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        
        // 设置背景色
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        
        // 设置边框
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        
        return style;
    }

    /**
     * 生成带列表的Word文档
     * @param file 目标文件
     * @param title 标题
     * @param items 列表项
     * @param ordered 是否有序列表
     * @return 是否成功
     */
    public static boolean generateWordWithList(File file, String title, 
                                                 List<String> items, boolean ordered) {
        try (XWPFDocument document = new XWPFDocument()) {
            // 创建标题
            if (title != null && !title.isEmpty()) {
                XWPFParagraph titlePara = document.createParagraph();
                titlePara.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(title);
                titleRun.setFontSize(18);
                titleRun.setBold(true);
                titleRun.addBreak();
                titleRun.addBreak();
            }

            // 创建列表
            if (items != null) {
                int itemIndex = 1;
                for (String item : items) {
                    if (item != null && !item.isEmpty()) {
                        XWPFParagraph para = document.createParagraph();
                        para.setIndentationLeft(720);
                        
                        XWPFRun run = para.createRun();
                        if (ordered) {
                            run.setText(itemIndex + ". " + item);
                            itemIndex++;
                        } else {
                            run.setText("• " + item);
                        }
                        run.setFontSize(12);
                        run.addBreak();
                    }
                }
            }

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(file)) {
                document.write(fos);
            }

            Log.i(TAG, "Word文档生成成功: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "生成Word文档失败: " + e.getMessage(), e);
            return false;
        }
    }
}
