package com.oilquiz.app.util;

import android.util.Log;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
        } catch (Exception e) {
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
        } catch (Exception e) {
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
             Workbook workbook = WorkbookFactory.create(fis)) {
            
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
            // ========== 诊断：POI读取节点 ==========
            if (!data.isEmpty()) {
                // 打印表头（首行）
                String[] headerRow = data.get(0);
                StringBuilder headerSb = new StringBuilder();
                for (int i = 0; i < Math.min(headerRow.length, 6); i++) {
                    headerSb.append("[").append(headerRow[i]).append("] ");
                }
                ImportDebugTracer.trace("【1】OfficeParserUtil-POI-表头", headerSb.toString());
                // 打印数据首行
                if (data.size() > 1) {
                    String[] firstData = data.get(1);
                    StringBuilder dataSb = new StringBuilder();
                    for (int i = 0; i < Math.min(firstData.length, 6); i++) {
                        dataSb.append("[").append(firstData[i]).append("] ");
                    }
                    ImportDebugTracer.trace("【2】OfficeParserUtil-POI-首条数据", dataSb.toString());
                }
            }
            return data;
        } catch (Exception e) {
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
     * 表头关键词（子串匹配，一个表头只计一次）。
     * 供「导入侧选表」与「附件预处理侧选表」共用，避免两处关键词漂移。
     */
    public static final String[] HEADER_KEYWORDS = {
            // 题干类
            "题干", "题目", "问题", "question", "题目内容", "内容", "题干内容",
            "试题", "试题内容", "题目描述", "题干描述", "quiz", "题目文本", "题干文本", "question_text",
            // 选项类
            "选项", "option", "答案选项", "备选", "choice", "options",
            // 答案类
            "答案", "answer", "正确答案", "正确", "参考答案", "标准答案", "答案内容",
            "正确答案选项", "answer_text", "answer_key", "答案项",
            // 解析类
            "解析", "explanation", "详解", "分析", "解答",
            // 题型/难度/分类类
            "题型", "type", "题目类型",
            "难度", "difficulty", "等级",
            "分类", "category", "章节", "知识点", "标签"
    };

    /** 表头关键词命中计数（小写子串匹配，一个表头只记一次） */
    public static int headerScore(String[] headers) {
        if (headers == null || headers.length == 0) {
            return 0;
        }
        int score = 0;
        for (String h : headers) {
            if (h == null) {
                continue;
            }
            String hs = h.trim().toLowerCase(java.util.Locale.ROOT);
            if (hs.isEmpty()) {
                continue;
            }
            for (String kw : HEADER_KEYWORDS) {
                if (hs.contains(kw.toLowerCase(java.util.Locale.ROOT))) {
                    score++;
                    break;
                }
            }
        }
        return score;
    }

    /** Excel 选表结果：选中表的数据 + 全表清单（供预处理预览携带，避免"只见首表"） */
    public static final class SheetPick {
        public List<String[]> data;
        public int sheetIndex = -1;
        public String sheetName;
        public int sheetCount;
        public List<String> sheetNames = new ArrayList<>();
        public int[] sheetRowCounts = new int[0];
    }

    /**
     * 选取「最像题库」的工作表。
     * <p>
     * 策略：表头关键词分 > 0 视为"像题库的表"；在其中取<b>行数最多</b>的一张
     * （平手取靠前的）；若全部无命中，则回退行数最多的表。
     * 这样多工作表文件不会再把首张示例/模板表当成主表。
     *
     * @return SheetPick（data 为选中表的数据，含表头行）；文件不可解析返回 null
     */
    public static SheetPick pickBestSheet(File file) {
        SheetPick pick = new SheetPick();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            int n = workbook.getNumberOfSheets();
            pick.sheetCount = n;
            pick.sheetRowCounts = new int[n];

            int bestIndex = -1;
            boolean bestValid = false;
            int bestRows = -1;
            for (int i = 0; i < n; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                pick.sheetNames.add(workbook.getSheetName(i));
                int rows = (sheet == null) ? 0
                        : Math.max(sheet.getPhysicalNumberOfRows(), sheet.getLastRowNum() + 1);
                pick.sheetRowCounts[i] = rows;
                int score = (sheet == null) ? 0 : headerScore(firstNonEmptyRow(sheet));
                boolean valid = score > 0;

                if (bestIndex < 0
                        || (valid && !bestValid)
                        || (valid == bestValid && rows > bestRows)) {
                    bestIndex = i;
                    bestValid = valid;
                    bestRows = rows;
                    pick.sheetName = workbook.getSheetName(i);
                }
            }
            pick.sheetIndex = bestIndex;
        } catch (Exception e) {
            Log.e(TAG, "选取最佳工作表失败: " + e.getMessage(), e);
            return null;
        }
        if (pick.sheetIndex >= 0) {
            // 第二遍读取选中表的数据（复用既有解析，避免同一 Workbook 反复持有大表）
            pick.data = parseExcel(file, pick.sheetIndex);
        }
        return pick;
    }

    /** 取工作表首个非空行的单元格文本（作为待判定的表头行） */
    private static String[] firstNonEmptyRow(Sheet sheet) {
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            boolean hasText = false;
            for (Cell cell : row) {
                String v = getCellValue(cell);
                cells.add(v);
                if (v != null && !v.trim().isEmpty()) {
                    hasText = true;
                }
            }
            if (hasText) {
                return cells.toArray(new String[0]);
            }
        }
        return new String[0];
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
             Workbook workbook = WorkbookFactory.create(fis)) {
            return workbook.getNumberOfSheets();
        } catch (Exception e) {
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
             Workbook workbook = WorkbookFactory.create(fis)) {
            
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                sheetNames.add(workbook.getSheetName(i));
            }
            
            return sheetNames;
        } catch (Exception e) {
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
             Workbook workbook = WorkbookFactory.create(fis)) {
            
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
        } catch (Exception e) {
            Log.e(TAG, "获取表头失败: " + e.getMessage(), e);
            return null;
        }
    }
}
