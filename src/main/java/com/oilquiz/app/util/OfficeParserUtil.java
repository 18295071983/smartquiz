package com.oilquiz.app.util;

import android.util.Log;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Office文件解析工具类
 * 用于解析Word和Excel文件
 */
public class OfficeParserUtil {
    private static final String TAG = "OfficeParserUtil";

    /**
     * 解析Word文档为文本
     * @param file Word文件
     * @return 文本内容
     */
    public static String parseWordToText(File file) {
        StringBuilder content = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            
            // 读取所有段落
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isEmpty()) {
                    content.append(text).append("\n");
                }
            }
            
            Log.i(TAG, "Word文档解析成功: " + file.getAbsolutePath());
            return content.toString();
        } catch (IOException e) {
            Log.e(TAG, "解析Word文档失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析Word文档为段落列表
     * @param file Word文件
     * @return 段落列表
     */
    public static List<String> parseWordToParagraphs(File file) {
        List<String> paragraphs = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    paragraphs.add(text);
                }
            }
            
            Log.i(TAG, "Word文档解析成功: " + file.getAbsolutePath() + 
                  ", 共 " + paragraphs.size() + " 个段落");
            return paragraphs;
        } catch (IOException e) {
            Log.e(TAG, "解析Word文档失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析Excel文件为二维数据列表
     * @param file Excel文件
     * @param sheetIndex 工作表索引（从0开始）
     * @return 数据列表
     */
    public static List<String[]> parseExcel(File file, int sheetIndex) {
        List<String[]> data = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) {
                Log.e(TAG, "工作表不存在: " + sheetIndex);
                return null;
            }
            
            for (Row row : sheet) {
                List<String> rowData = new ArrayList<>();
                for (Cell cell : row) {
                    String cellValue = getCellValue(cell);
                    rowData.add(cellValue);
                }
                data.add(rowData.toArray(new String[0]));
            }
            
            Log.i(TAG, "Excel文件解析成功: " + file.getAbsolutePath() + 
                  ", 共 " + data.size() + " 行");
            return data;
        } catch (IOException e) {
            Log.e(TAG, "解析Excel文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析Excel文件的第一个工作表
     * @param file Excel文件
     * @return 数据列表
     */
    public static List<String[]> parseExcelFirstSheet(File file) {
        return parseExcel(file, 0);
    }

    /**
     * 获取单元格值
     */
    private static String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        
        CellType cellType = cell.getCellType();
        if (cellType == CellType.FORMULA) {
            cellType = cell.getCachedFormulaResultType();
        }
        
        switch (cellType) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    /**
     * 获取Excel文件的工作表数量
     * @param file Excel文件
     * @return 工作表数量
     */
    public static int getExcelSheetCount(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            return workbook.getNumberOfSheets();
        } catch (IOException e) {
            Log.e(TAG, "获取工作表数量失败: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 获取Excel文件的工作表名称列表
     * @param file Excel文件
     * @return 工作表名称列表
     */
    public static List<String> getExcelSheetNames(File file) {
        List<String> sheetNames = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                sheetNames.add(workbook.getSheetName(i));
            }
            
            return sheetNames;
        } catch (IOException e) {
            Log.e(TAG, "获取工作表名称失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取Excel文件的表头（第一行）
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @return 表头数组
     */
    public static String[] getExcelHeaders(File file, int sheetIndex) {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null || sheet.getLastRowNum() < 0) {
                return new String[0];
            }
            
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return new String[0];
            }
            
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellValue(cell));
            }
            
            return headers.toArray(new String[0]);
        } catch (IOException e) {
            Log.e(TAG, "获取表头失败: " + e.getMessage(), e);
            return null;
        }
    }
}
