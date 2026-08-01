package com.oilquiz.app.ai.importing;

import android.util.Log;

import com.oilquiz.app.util.render.ExcelUtil;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Excel 多工作表选择 + 表头行自动识别工具。
 * <p>
 * 复用 {@link ExcelUtil} 读取能力(枚举 sheet、单元格转字符串、格式探测),不重复造轮子。
 * 用于题库 AI 导入流程:解析 Excel 各 sheet,自动识别表头行,并把指定 sheet 从表头行起导出为 Markdown 表格文本,
 * 供 {@link AIImportOrchestrator} 作为普通文本文件读取。
 * <p>
 * 注意:{@link ExcelUtil#readExcelData} 固定从第 1 行起读(跳过第 0 行),无法满足"识别表头在第几行"的需求,
 * 故此处新增 {@link #readRows} 私有方法从任意 0-based 起始行读取,单元格转换仍复用 {@link ExcelUtil#getCellValue}。
 */
public class ExcelSheetPicker {

    private static final String TAG = "ExcelSheetPicker";

    /** 表头关键词集合:命中数最多的行视为表头行;全 0 命中时默认第 0 行 */
    private static final Set<String> HEADER_KEYWORDS = new HashSet<>(Arrays.asList(
            "题干", "题目", "问题", "题", "答案", "正确答案", "选项", "选项A", "A",
            "选项B", "B", "解析", "详解", "说明", "知识点", "考点", "难度",
            "分类", "类别", "类型", "分值", "分数", "时限", "作者", "来源",
            "备注", "标签", "提示", "分析", "子分类", "章节"
    ));

    /** 单个 sheet 的剖析结果 */
    public static class SheetProfile implements Serializable {
        public String sheetName;
        public int sheetIndex;
        public int rowCount;
        public int columnCount;
        /** 自动识别的表头行(0-based) */
        public int headerRowIndex;
        /** 表头列名 */
        public List<String> headerColumns;
        /** 前 12 行原始数据(供预览) */
        public List<List<String>> sampleRows;
    }

    /**
     * 分析 Excel 所有 sheet,自动识别每个 sheet 的表头行。
     * 失败返回空 list。
     */
    public static List<SheetProfile> analyzeSheets(File excelFile) {
        List<SheetProfile> result = new ArrayList<>();
        if (excelFile == null || !excelFile.exists()) {
            return result;
        }
        try {
            List<ExcelUtil.SheetInfo> sheets = ExcelUtil.getExcelSheets(excelFile);
            if (sheets == null || sheets.isEmpty()) {
                return result;
            }
            for (ExcelUtil.SheetInfo info : sheets) {
                SheetProfile profile = new SheetProfile();
                profile.sheetName = info.sheetName;
                profile.sheetIndex = info.sheetIndex;
                profile.rowCount = info.rowCount;
                profile.columnCount = info.columnCount;
                // 读前 12 行(含第 0 行)用于表头识别与预览
                profile.sampleRows = readRows(excelFile, info.sheetIndex, 0, 12);
                profile.headerRowIndex = detectHeaderRow(profile.sampleRows);
                profile.headerColumns = extractHeaderColumns(
                        profile.sampleRows, profile.headerRowIndex, profile.columnCount);
                result.add(profile);
            }
        } catch (Exception e) {
            Log.e(TAG, "分析 Excel sheet 失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 把指定 sheet 从表头行开始的数据导出为 Markdown 表格文本(最多 600 行,避免过大)。
     * 异常返回 ""。
     */
    public static String exportSheetAsMarkdown(File excelFile, int sheetIndex, int headerRowIndex) {
        if (excelFile == null || !excelFile.exists()) {
            return "";
        }
        if (headerRowIndex < 0) {
            headerRowIndex = 0;
        }
        try {
            List<List<String>> rows = readRows(excelFile, sheetIndex, headerRowIndex, 600);
            if (rows == null || rows.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            // 第一行(表头)
            List<String> header = rows.get(0);
            int colCount = header.size();
            if (colCount <= 0) {
                return "";
            }
            sb.append("|");
            for (String h : header) {
                sb.append(" ").append(escapeMd(h)).append(" |");
            }
            sb.append("\n");
            // 分隔行
            sb.append("|");
            for (int i = 0; i < colCount; i++) {
                sb.append("---|");
            }
            sb.append("\n");
            // 数据行
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                sb.append("|");
                for (int c = 0; c < colCount; c++) {
                    String v = (row != null && c < row.size()) ? row.get(c) : "";
                    sb.append(" ").append(escapeMd(v)).append(" |");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "导出 sheet 为 Markdown 失败: " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * 自动识别表头行:统计每行命中"表头关键词"的单元格数,命中数最多(且 >0)的行为表头;
     * 若所有行命中数都 0,默认返回 0。
     */
    private static int detectHeaderRow(List<List<String>> sampleRows) {
        if (sampleRows == null || sampleRows.isEmpty()) {
            return 0;
        }
        int bestIdx = 0;
        int bestHits = 0;
        for (int i = 0; i < sampleRows.size(); i++) {
            List<String> row = sampleRows.get(i);
            if (row == null) {
                continue;
            }
            int hits = 0;
            for (String cell : row) {
                if (cell == null) {
                    continue;
                }
                String trimmed = cell.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (HEADER_KEYWORDS.contains(trimmed)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                bestIdx = i;
            }
        }
        return bestIdx; // 全 0 命中时 bestIdx 保持初始值 0
    }

    /** 取表头行列名:单元格转字符串,空值兜底为 "列N" */
    private static List<String> extractHeaderColumns(List<List<String>> sampleRows,
                                                      int headerRowIndex, int columnCount) {
        List<String> headers = new ArrayList<>();
        if (sampleRows == null || headerRowIndex < 0 || headerRowIndex >= sampleRows.size()) {
            for (int i = 0; i < columnCount; i++) {
                headers.add("列" + (i + 1));
            }
            return headers;
        }
        List<String> row = sampleRows.get(headerRowIndex);
        int count = Math.max((row != null) ? row.size() : 0, columnCount);
        for (int i = 0; i < count; i++) {
            String v = (row != null && i < row.size()) ? row.get(i) : "";
            if (v == null || v.trim().isEmpty()) {
                headers.add("列" + (i + 1));
            } else {
                headers.add(v);
            }
        }
        return headers;
    }

    /** 转义 Markdown 表格中的管道符 */
    private static String escapeMd(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|");
    }

    /**
     * 从指定 sheet 的 startRow(0-based,含)开始读取最多 maxRows 行。
     * 单元格转换复用 {@link ExcelUtil#getCellValue};null 行保留为空 list 以维持行索引对齐。
     */
    private static List<List<String>> readRows(File file, int sheetIndex, int startRow, int maxRows) {
        List<List<String>> data = new ArrayList<>();
        if (file == null || !file.exists()) {
            return data;
        }
        FileInputStream fis = null;
        Workbook workbook = null;
        try {
            fis = new FileInputStream(file);
            String name = file.getName().toLowerCase();
            if (name.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(fis);
            } else if (name.endsWith(".xls")) {
                workbook = new HSSFWorkbook(fis);
            } else {
                return data;
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) {
                return data;
            }
            int lastRow = sheet.getLastRowNum();
            int read = 0;
            for (int i = startRow; i <= lastRow; i++) {
                if (maxRows > 0 && read >= maxRows) {
                    break;
                }
                Row row = sheet.getRow(i);
                if (row == null) {
                    data.add(new ArrayList<>());
                    read++;
                    continue;
                }
                int colNum = row.getLastCellNum();
                if (colNum < 0) {
                    colNum = 0;
                }
                List<String> rowData = new ArrayList<>();
                for (int j = 0; j < colNum; j++) {
                    Cell cell = row.getCell(j);
                    rowData.add(ExcelUtil.getCellValue(cell));
                }
                data.add(rowData);
                read++;
            }
        } catch (IOException e) {
            Log.e(TAG, "读取 Excel 行失败: " + e.getMessage(), e);
        } finally {
            try {
                if (workbook != null) {
                    workbook.close();
                }
                if (fis != null) {
                    fis.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "关闭资源失败: " + e.getMessage(), e);
            }
        }
        return data;
    }
}
