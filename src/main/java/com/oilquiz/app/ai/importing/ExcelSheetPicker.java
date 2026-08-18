package com.oilquiz.app.ai.importing;

import android.util.Log;

import com.oilquiz.app.util.render.ExcelUtil;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

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

    /** 表头关键词集合（≥2 字,避免短词误命中,如单独字母"A"作为数据内容也很常见） */
    private static final Set<String> HEADER_KEYWORDS_STRONG = new HashSet<>(Arrays.asList(
            "题干", "题目", "问题", "答案", "正确答案", "选项",
            "解析", "详解", "知识点", "考点", "难度",
            "分类", "类别", "类型", "分值", "分数", "时限", "作者", "来源",
            "备注", "标签", "提示", "分析", "子分类", "章节",
            "填空项", "答案解析", "创建人", "创建时间"
    ));
    /** 弱关键词:只要包含就算命中,权重减半（如"正确答案(必填)"包含"正确答案"） */
    private static final Set<String> HEADER_KEYWORDS_CONTAIN = HEADER_KEYWORDS_STRONG;

    /** 说明行关键词:出现任何一个就可判定为说明文本 */
    private static final Set<String> DESCRIPTION_KEYWORDS = new HashSet<>(Arrays.asList(
            "说明", "提示", "注意", "请勿", "填写", "必填", "举例", "示例",
            "模板", "格式", "规范", "要求", "编号"
    ));

    /** 单个 sheet 的剖析结果 */
    public static class SheetProfile implements Serializable {
        public String sheetName;
        public int sheetIndex;
        public int rowCount;
        public int columnCount;
        /** 自动识别的主表头行(0-based) */
        public int headerRowIndex;
        /** 子表头行索引（-1 表示无子表头），主表头下一行若是 A/B/.../L 或 空1/空2... 则并入表头 */
        public int subHeaderRowIndex = -1;
        /** 最终数据起始行（0-based 含）：跳过表头、子表头、中间空行后的第一条真实数据行 */
        public int dataStartRowIndex;
        /** 表头列名（若有子表头已合并） */
        public List<String> headerColumns;
        /** 从 Sheet 名推断的题型（中文标准名："单选题"、"多选题"、"判断题"、"填空题"、"简答题"、"问答题"、"案例分析题"、"匹配题"、"未分类"） */
        public String inferredQuestionType;
        /** 前 20 行原始数据(供预览) */
        public List<List<String>> sampleRows;
    }

    /** 多相扫描的内部结构判定结果 */
    private static class StructureResult {
        int headerIdx = 0;
        int subHeaderIdx = -1;
        int dataStartIdx = 1;
        List<String> finalHeader;
    }

    /**
     * 分析 Excel 所有 sheet,自动识别每个 sheet 的表头行。
     * 单次加载 workbook（避免每个 sheet 都全量重载导致大文件卡顿）。
     * 失败返回空 list。
     */
    public static List<SheetProfile> analyzeSheets(File excelFile) {
        List<SheetProfile> result = new ArrayList<>();
        if (excelFile == null || !excelFile.exists()) {
            return result;
        }
        FileInputStream fis = null;
        Workbook workbook = null;
        try {
            fis = new FileInputStream(excelFile);
            workbook = WorkbookFactory.create(fis);
            int sheetCount = workbook.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet == null) continue;
                SheetProfile profile = new SheetProfile();
                profile.sheetName = sheet.getSheetName() != null ? sheet.getSheetName() : "工作表" + (i + 1);
                profile.sheetIndex = i;
                profile.rowCount = sheet.getLastRowNum() + 1;
                profile.columnCount = 0;
                Row firstRow = sheet.getRow(0);
                if (firstRow != null) profile.columnCount = firstRow.getLastCellNum();
                // 读前 20 行(含第 0 行)用于表头识别与预览（复用已加载的 workbook）
                profile.sampleRows = readRowsFromWorkbook(workbook, i, 0, 20);
                StructureResult sr = detectSheetStructure(profile.sampleRows, profile.columnCount);
                profile.headerRowIndex = sr.headerIdx;
                profile.subHeaderRowIndex = sr.subHeaderIdx;
                profile.dataStartRowIndex = sr.dataStartIdx;
                profile.headerColumns = sr.finalHeader;
                profile.inferredQuestionType = inferQuestionTypeFromName(profile.sheetName);
                int colSize = (profile.headerColumns == null) ? -1 : profile.headerColumns.size();
                String logMsg = "sheet[" + profile.sheetName + "] headerIdx=" + profile.headerRowIndex
                        + " subHeaderIdx=" + profile.subHeaderRowIndex
                        + " dataStart=" + profile.dataStartRowIndex
                        + " inferType=" + profile.inferredQuestionType
                        + " cols=" + colSize;
                Log.i(TAG, logMsg);
                if (profile.headerColumns != null && !profile.headerColumns.isEmpty()) {
                    StringBuilder hsb = new StringBuilder();
                    for (int j = 0; j < Math.min(profile.headerColumns.size(), 12); j++) {
                        hsb.append("[").append(profile.headerColumns.get(j)).append("] ");
                    }
                    Log.i(TAG, "  headerPreview: " + hsb.toString());
                }
                result.add(profile);
            }
        } catch (Exception e) {
            Log.e(TAG, "分析 Excel sheet 失败: " + e.getMessage(), e);
        } finally {
            try {
                if (workbook != null) workbook.close();
                if (fis != null) fis.close();
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    /** 从已加载的 workbook 读取指定 sheet 的行（避免重复全量加载） */
    private static List<List<String>> readRowsFromWorkbook(Workbook workbook, int sheetIndex,
                                                           int startRow, int maxRows) {
        List<List<String>> data = new ArrayList<>();
        if (workbook == null) return data;
        try {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) return data;
            int lastRow = sheet.getLastRowNum();
            int read = 0;
            for (int i = startRow; i <= lastRow; i++) {
                if (maxRows > 0 && read >= maxRows) break;
                Row row = sheet.getRow(i);
                if (row == null) {
                    data.add(new ArrayList<>());
                    read++;
                    continue;
                }
                int colNum = row.getLastCellNum();
                if (colNum < 0) colNum = 0;
                List<String> rowData = new ArrayList<>();
                for (int j = 0; j < colNum; j++) {
                    Cell cell = row.getCell(j);
                    rowData.add(ExcelUtil.getCellValue(cell));
                }
                data.add(rowData);
                read++;
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 sheet 行失败: " + e.getMessage());
        }
        return data;
    }

    /**
     * 把指定 sheet 从表头行开始的数据导出为 Markdown 表格文本(最多 600 行,避免过大)。
     * 若 subHeaderRowIndex &ge; 0（表示有 A/B/.../空1/空2 子表头行），会把子表头与主表头合并为最终列名；
     * 数据从 dataStartRowIndex（0-based，含）开始读取。
     * 异常返回 ""。
     */
    public static String exportSheetAsMarkdown(File excelFile, int sheetIndex,
                                               int headerRowIndex, int subHeaderRowIndex,
                                               int dataStartRowIndex) {
        if (excelFile == null || !excelFile.exists()) {
            return "";
        }
        if (headerRowIndex < 0) headerRowIndex = 0;
        if (dataStartRowIndex <= headerRowIndex) dataStartRowIndex = headerRowIndex + 1;
        try {
            // 一次性读取从 headerRowIndex 开始的最多 602 行（含主表头、子表头、数据）
            int maxRows = 600 + (subHeaderRowIndex >= 0 ? 2 : 1);
            List<List<String>> rawRows = readRows(excelFile, sheetIndex, headerRowIndex, maxRows);
            if (rawRows == null || rawRows.isEmpty()) {
                return "";
            }
            List<String> finalHeader;
            int firstDataLocalIndex; // rawRows 中的数据起始下标
            if (subHeaderRowIndex >= 0
                    && rawRows.size() >= 2
                    && isSubHeaderRow(rawRows.get(1))) {
                // 有子表头：主表头 rawRows[0] + 子表头 rawRows[1]
                int realCols = Math.max(rawRows.get(0).size(), rawRows.get(1).size());
                firstDataLocalIndex = 2;
                List<String> mainSpread = spreadGroupHeader(rawRows.get(0));
                finalHeader = mergeHeaderWithSubHeader(mainSpread, rawRows.get(1), realCols);
            } else {
                firstDataLocalIndex = 1;
                List<String> h0 = rawRows.get(0);
                finalHeader = new ArrayList<>();
                int realCols = h0 == null ? 0 : h0.size();
                for (int i = 0; i < realCols; i++) {
                    String v = (h0 != null && i < h0.size()) ? h0.get(i) : "";
                    if (v == null || v.trim().isEmpty()) v = "列" + (i + 1);
                    finalHeader.add(v);
                }
            }
            int colCount = finalHeader.size();
            if (colCount <= 0) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("|");
            for (String h : finalHeader) sb.append(" ").append(escapeMd(h)).append(" |");
            sb.append("\n|---|");
            for (int i = 1; i < colCount; i++) sb.append("---|");
            sb.append("\n");
            for (int i = firstDataLocalIndex; i < rawRows.size(); i++) {
                List<String> row = rawRows.get(i);
                // 跳过全空行
                if (row == null || row.isEmpty()) continue;
                boolean allEmpty = true;
                for (String c : row) if (c != null && !c.trim().isEmpty()) { allEmpty = false; break; }
                if (allEmpty) continue;
                sb.append("|");
                for (int c = 0; c < colCount; c++) {
                    String v = (c < row.size()) ? row.get(c) : "";
                    sb.append(" ").append(escapeMd(v == null ? "" : v)).append(" |");
                }
                sb.append("\n");
            }
            Log.i(TAG, "exportSheetAsMarkdown: cols=" + colCount
                    + " firstDataLocalIdx=" + firstDataLocalIndex
                    + " rowsOutput=" + Math.max(0, rawRows.size() - firstDataLocalIndex));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "导出 sheet 为 Markdown 失败: " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * 简化版：仅指定 headerRowIndex，默认无子表头（从 headerRowIndex+1 开始数据）
     */
    public static String exportSheetAsMarkdown(File excelFile, int sheetIndex, int headerRowIndex) {
        return exportSheetAsMarkdown(excelFile, sheetIndex, headerRowIndex, -1, headerRowIndex + 1);
    }

    /**
     * 扩展版：导出 sheet 为 Markdown，并在开头加入 META 注释块（不影响渲染，供下游解析）。
     * META 内容格式（HTML 注释）：
     *   <!-- SHEET_META: sheetName=xxx questionType=单选题 sheetIndex=1 -->
     * @param sheetName 可选，sheet 原名
     * @param inferredQuestionType 可选，从 sheetName 推断的标准题型
     */
    public static String exportSheetAsMarkdownWithMeta(File excelFile, int sheetIndex,
                                                        int headerRowIndex, int subHeaderRowIndex,
                                                        int dataStartRowIndex,
                                                        String sheetName,
                                                        String inferredQuestionType) {
        String mdBody = exportSheetAsMarkdown(excelFile, sheetIndex, headerRowIndex,
                subHeaderRowIndex, dataStartRowIndex);
        if (mdBody == null || mdBody.isEmpty()) return mdBody;
        StringBuilder meta = new StringBuilder();
        meta.append("<!-- SHEET_META: sheetIndex=").append(sheetIndex);
        if (sheetName != null && !sheetName.trim().isEmpty()) {
            meta.append(" sheetName=").append(sheetName.replace(" ", "_"));
        }
        if (inferredQuestionType != null && !inferredQuestionType.trim().isEmpty()
                && !"未分类".equals(inferredQuestionType)) {
            meta.append(" questionType=").append(inferredQuestionType);
        }
        meta.append(" -->\n");
        return meta.toString() + mdBody;
    }

    /**
     * 解析 Markdown 开头的 SHEET_META 注释块，返回 Map（键为 sheetName/questionType/sheetIndex 等）。
     * 找不到返回空 Map（不返回 null）。
     */
    public static java.util.Map<String, String> parseSheetMeta(String markdown) {
        java.util.Map<String, String> res = new java.util.HashMap<>();
        if (markdown == null || markdown.isEmpty()) return res;
        // 最多看开头 4096 个字符，避免长文档全量扫描
        String head = markdown.substring(0, Math.min(markdown.length(), 4096));
        int start = head.indexOf("<!-- SHEET_META:");
        if (start < 0) return res;
        int end = head.indexOf("-->", start);
        if (end < 0) return res;
        String body = head.substring(start + "<!-- SHEET_META:".length(), end).trim();
        // 按空格切 token，每个 token 形式为 key=value（value 不含空格，或下划线/连字号）
        for (String tok : body.split("\\s+")) {
            if (tok.isEmpty()) continue;
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String k = tok.substring(0, eq);
            String v = tok.substring(eq + 1).replace("_", " ");
            if (!k.isEmpty() && !v.isEmpty()) res.put(k, v);
        }
        return res;
    }

    // =========================================================
    //  新一代多相结构扫描引擎
    // =========================================================

    /** 扫描行类型枚举 */
    private enum RowType {
        EMPTY,         // 全空
        DESCRIPTION,   // 说明/注释行
        HEADER_CAND,   // 表头候选（关键词命中数高）
        SUB_HEADER,    // 子表头（A~L / 空1 空2 ...）
        DATA,          // 数据行（首列是序号或题目内容非空且长）
        UNKNOWN
    }

    private static boolean isPureEmpty(List<String> row) {
        if (row == null || row.isEmpty()) return true;
        for (String s : row) if (s != null && !s.trim().isEmpty()) return false;
        return true;
    }

    /** 一行非空列统计 */
    private static int countNonEmpty(List<String> row) {
        int n = 0;
        if (row == null) return 0;
        for (String s : row) if (s != null && !s.trim().isEmpty()) n++;
        return n;
    }

    /** 判定说明/注释行: 包含说明关键词 或 大部分空+小部分长文本 */
    private static boolean isDescriptionRow(List<String> row) {
        if (row == null || row.isEmpty()) return false;
        int nonEmpty = 0, empty = 0;
        int firstNonEmptyIdx = -1;
        boolean hasDescKw = false;
        int longTextCount = 0;
        for (int i = 0; i < row.size(); i++) {
            String cell = row.get(i);
            if (cell == null) cell = "";
            String t = cell.trim();
            if (t.isEmpty()) {
                empty++;
            } else {
                nonEmpty++;
                if (firstNonEmptyIdx < 0) firstNonEmptyIdx = i;
                if (t.length() > 25) longTextCount++;
                for (String kw : DESCRIPTION_KEYWORDS) {
                    if (t.contains(kw)) { hasDescKw = true; break; }
                }
            }
        }
        if (nonEmpty == 0) return false; // EMPTY 归为 EMPTY 类型
        double emptyRatio = (double) empty / (nonEmpty + empty);
        // 条件：含说明关键词  或  (空列占比高 且 非空列少 且 集中在前几列)
        if (hasDescKw) return true;
        if (emptyRatio >= 0.55 && nonEmpty <= 3 && firstNonEmptyIdx <= 1) return true;
        if (longTextCount >= 1 && nonEmpty <= 2 && emptyRatio >= 0.5) return true;
        return false;
    }

    /**
     * 扩展子表头检测:
     * 支持形式：
     *   1. 大写字母 A~L
     *   2. 小写字母 a~l
     *   3. 括号字母: (A) (b) [C] 【D】
     *   4. 空N（空1 空2 ...）
     *   5. 中文数字：选项一/二/…/十、1./2./3. (数字加标点)
     *   6. 罗马数字：ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ
     * 占非空列多数 → 判定为子表头行
     */
    private static boolean isSubHeaderRow(List<String> candidateRow) {
        if (candidateRow == null || candidateRow.isEmpty()) return false;
        int nonEmpty = countNonEmpty(candidateRow);
        if (nonEmpty < 2) return false;
        int hit = 0;
        for (String s : candidateRow) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) continue;
            if (isSubHeaderCellToken(t)) hit++;
        }
        return hit >= 2 && hit * 10 >= nonEmpty * 6;
    }

    /** 单个单元格是否是子表头 token（扩展形式） */
    private static boolean isSubHeaderCellToken(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.isEmpty()) return false;
        // 1. A~L / a~l
        if (s.matches("[A-La-l]")) return true;
        // 2. (A) [b] 【C】 各种括号字母
        String stripped = s.replaceAll("[()\\[\\]【】（）\\.。、\\s]", "");
        if (stripped.matches("[A-La-l]")) return true;
        // 3. 空N（空1~空99）
        if (s.matches("空\\d{1,2}")) return true;
        // 4. 选项一二三四五六七八九十（可带"选项"/"答案"前缀）
        String s1 = s.replaceFirst("^(选项|答案|备选)", "");
        if ("一二三四五六七八九十".contains(s1) && s1.length() == 1) return true;
        // 5. 阿拉伯数字加标点 1. 2、 3)
        if (stripped.matches("\\d{1,2}")) {
            try {
                int n = Integer.parseInt(stripped);
                if (n >= 1 && n <= 20) return true;
            } catch (Exception ignore) {}
        }
        // 6. 罗马数字（Unicode）
        if (s.length() <= 3 && "ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ".contains(s)) return true;
        return false;
    }

    /** 规范化子表头 token → "A"/"B"/.../"L" 或 "空1"/"空2" 或原字符串 */
    private static String normalizeSubHeaderToken(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        // 空N
        if (t.matches("空\\d{1,2}")) return t;
        // A~L / a~l
        if (t.matches("[A-La-l]")) return t.toUpperCase();
        // 括号字母/字母带标点 (A) [b] C.
        String stripped = t.replaceAll("[()\\[\\]【】（）\\.。、\\s]", "");
        if (stripped.matches("[A-La-l]")) return stripped.toUpperCase();
        // 中文数字 一~十 → A~J
        String s1 = t.replaceFirst("^(选项|答案|备选)", "");
        String chinese = "一二三四五六七八九十";
        if (s1.length() == 1 && chinese.contains(s1)) {
            int idx = chinese.indexOf(s1); // 0~9
            if (idx >= 0 && idx < 12) return String.valueOf((char) ('A' + idx));
        }
        // 阿拉伯数字 1~20 → A~T（但只取 1~12，因为 AIImport 支持到 L）
        if (stripped.matches("\\d{1,2}")) {
            try {
                int n = Integer.parseInt(stripped);
                if (n >= 1 && n <= 12) return String.valueOf((char) ('A' + n - 1));
            } catch (Exception ignore) {}
        }
        // 罗马数字 Ⅰ~Ⅹ → A~J（简单映射）
        String roman = "ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ";
        if (t.length() <= 3 && roman.contains(t)) {
            int r = roman.indexOf(t);
            if (r >= 0 && r < 12) return String.valueOf((char) ('A' + r));
        }
        return t;
    }

    /** 中文数字字符 → 对应阿拉伯数字（0~9 对十以内有效） */
    private static int chineseDigitToIndex(char c) {
        return "一二三四五六七八九十".indexOf(c);
    }

    /**
     * 从 Sheet 名 / 章节名 推断题型（返回标准中文题型名）
     * 支持：
     *   - 单选 / 单选题 / 一、单选题 / single(choice) / SC  → "单选题"
     *   - 多选 / 多选题 / multiple(choice) / MC             → "多选题"
     *   - 判断 / 判断题 / 对错 / 判断正误 / T/F / judge     → "判断题"
     *   - 填空 / 填空题 / blank / fill                      → "填空题"
     *   - 简答 / 简答题 / 问答 / 问答题 / short / answer     → "简答题"
     *   - 案例 / 案例分析 / 论述 / 分析题                   → "案例分析题"
     *   - 匹配 / 配对 / match                               → "匹配题"
     *   - 计算 / 计算题                                     → "计算题"
     *   - 综合 / 综合题                                     → "综合题"
     */
    public static String inferQuestionTypeFromName(String name) {
        if (name == null) return "未分类";
        String n = name.toLowerCase().trim();
        if (n.isEmpty()) return "未分类";

        // 中文关键词（按长度从长到短匹配，避免"单选"先于"单选题"命中导致不标准）
        String[][] cnRules = new String[][] {
                {"单选题", "单项选择", "singlechoice", "single choice", "单选"},
                {"多选题", "多项选择", "multiplechoice", "multiple choice", "多选"},
                {"判断题", "判断正误", "判断对错", "是非题", "判断"},
                {"填空题", "完形填空", "填空"},
                {"简答题", "问答题", "问答", "简答"},
                {"案例分析题", "案例分析", "案例题", "论述题", "分析题", "论述"},
                {"匹配题", "配对题", "匹配", "配对"},
                {"计算题", "计算"},
                {"综合题", "综合"},
        };
        String[] cnStandard = new String[] {
                "单选题", "多选题", "判断题", "填空题",
                "简答题", "案例分析题", "匹配题", "计算题", "综合题"
        };
        for (int i = 0; i < cnRules.length; i++) {
            for (String kw : cnRules[i]) {
                if (n.contains(kw.toLowerCase())) {
                    return cnStandard[i];
                }
            }
        }

        // 英文缩写（全词匹配或边界匹配）
        if (n.matches(".*\\bsc\\b.*") || n.contains("(单选)") || n.contains("【单选】")) return "单选题";
        if (n.matches(".*\\bmc\\b.*") || n.contains("(多选)") || n.contains("【多选】")) return "多选题";
        if (n.contains("tf") || n.contains("t/f") || n.contains("(判断)")) return "判断题";
        if (n.contains("(填空)") || n.contains("blank")) return "填空题";
        if (n.contains("(简答)") || n.contains("(问答)") || n.contains("saq")) return "简答题";

        return "未分类";
    }

    /** 计算表头候选得分:关键词精确(+3) + 包含匹配(+2) + 非空列(+1) + 密集非空分布(+1) */
    private static double headerRowScore(List<String> row) {
        if (row == null || isPureEmpty(row)) return -1;
        if (isDescriptionRow(row)) return -0.5;
        double score = 0;
        int nonEmpty = 0;
        int strongHits = 0;
        int containHits = 0;
        for (String c : row) {
            if (c == null) continue;
            String t = c.trim();
            if (t.isEmpty()) continue;
            nonEmpty++;
            if (HEADER_KEYWORDS_STRONG.contains(t)) {
                strongHits++;
                continue;
            }
            for (String kw : HEADER_KEYWORDS_CONTAIN) {
                if (kw.length() >= 2 && t.contains(kw)) {
                    containHits++;
                    break;
                }
            }
        }
        score += strongHits * 3.0;
        score += containHits * 2.0;
        score += nonEmpty * 0.5;
        // 非空列太少（只有1~2列有值且不是关键词）→ 可能是说明行，扣分
        if (nonEmpty <= 1 && strongHits + containHits == 0) score -= 2;
        return score;
    }

    /** 找主表头行索引:选 headerRowScore 最高的行 */
    private static int pickHeaderRowIndex(List<List<String>> sampleRows) {
        if (sampleRows == null || sampleRows.isEmpty()) return 0;
        double bestScore = -1e9;
        int bestIdx = 0;
        for (int i = 0; i < sampleRows.size(); i++) {
            double s = headerRowScore(sampleRows.get(i));
            if (s > bestScore) {
                bestScore = s;
                bestIdx = i;
            }
        }
        // 分数非常低（<0.5）时退回到：非空列数最多的非说明非空行
        if (bestScore < 0.5) {
            int bestNonEmptyCount = -1;
            for (int i = 0; i < sampleRows.size(); i++) {
                List<String> r = sampleRows.get(i);
                if (isPureEmpty(r) || isDescriptionRow(r)) continue;
                int n = countNonEmpty(r);
                if (n > bestNonEmptyCount) {
                    bestNonEmptyCount = n;
                    bestIdx = i;
                }
            }
        }
        return bestIdx;
    }

    /** 判定首列/首格内容是否为"题号格式"（第X题、一、二、(1)、1.、[1]、第一部分等） */
    private static boolean isIndexOrSectionMarker(String cell) {
        if (cell == null) return false;
        String s = cell.trim();
        if (s.isEmpty()) return false;
        // 纯数字序号（最多 5 位，避免被当成长文本）
        if (s.matches("\\d{1,5}")) return true;
        // 1. / 2、 / 3) / 4】
        if (s.matches("\\d{1,3}[\\.。、)）】\\s]+.*")) return true;
        // (1) [2] 【3】
        if (s.matches("[\\(\\[【（]\\d{1,3}[\\)\\]】）].*")) return true;
        // 第X题 / 第X小题 / 第X问
        if (s.matches("第[一二三四五六七八九十百千万零\\d]{1,6}[题小问].*")) return true;
        // 中文数字开头：一、 二、 三. (仅一~十 + 百/千简单组合)
        if (s.matches("[一二三四五六七八九十百千]{1,4}[、。\\.\\s)].*")) return true;
        // 部分/章节：第一部分 / Part 1 / Section A
        String low = s.toLowerCase();
        if (low.matches("第[一二三四五六七八九十零\\d]+(部分|章|节|组|大类).*")) return true;
        if (low.startsWith("part ") || low.startsWith("section ") || low.startsWith("chapter ")) return true;
        return false;
    }

    /** 判定是否像数据行（题目行）：
     *  (1) 第0列是"序号/题号格式标记"
     *  (2) 或"题目/题干"对应的列（有主表头时）内容长度 >= 10
     *  (3) 或整行非空列 >= 3，且未命中任何说明/子表头模式
     *  (4) 或没有主表头时，非空列>=2且首列有标记或题目列内容>=10
     */
    private static boolean looksLikeDataRow(List<String> row, List<String> headerRow) {
        if (row == null || isPureEmpty(row)) return false;
        if (isDescriptionRow(row)) return false;
        if (isSubHeaderRow(row)) return false;
        int nonEmpty = countNonEmpty(row);
        if (nonEmpty == 0) return false;
        // (1) 首列：纯数字 / 题号 / 部分标记
        String col0 = row.isEmpty() ? "" : row.get(0);
        if (isIndexOrSectionMarker(col0)) return true;

        // (2) 有主表头时,定位"题目/题干/问题"列,看内容是否>=10字
        if (headerRow != null && !headerRow.isEmpty()) {
            int qi = -1;
            for (int i = 0; i < headerRow.size(); i++) {
                String h = headerRow.get(i);
                if (h == null) continue;
                String ht = h.trim();
                if (ht.contains("题目") || ht.contains("题干") || ht.contains("问题")
                        || ht.contains("内容") || ht.contains("正文")) { qi = i; break; }
            }
            if (qi >= 0 && qi < row.size()) {
                String qv = row.get(qi);
                if (qv != null && qv.trim().length() >= 8) return true;
            }
        } else {
            // 无表头时，任一列内容>=15字且非空列>=2，也当作题行
            for (String s : row) {
                if (s != null && s.trim().length() >= 15 && nonEmpty >= 2) return true;
            }
        }
        // (3) 非空列较多且子表头标记占比极低（普通数据行）
        int letter = 0, num = 0;
        for (String s : row) {
            String t = s == null ? "" : s.trim();
            if (t.matches("[A-L]") && t.length() == 1) letter++;
            else if (t.matches("空\\d{1,2}")) num++;
        }
        if ((letter + num) * 10 < nonEmpty * 4 && nonEmpty >= 3) return true;
        return false;
    }

    /**
     * 用左侧最近的非空组名填充主表头中的空列（组扩散）。
     * 例如：主表头["序号","题目","正确答案","选项","","","","","知识点"]
     * 扩散后：["序号","题目","正确答案","选项","选项","选项","选项","选项","知识点"]
     */
    private static List<String> spreadGroupHeader(List<String> rawHeader) {
        if (rawHeader == null || rawHeader.isEmpty()) return new ArrayList<>();
        List<String> res = new ArrayList<>(rawHeader.size());
        String lastGroup = "";
        for (int i = 0; i < rawHeader.size(); i++) {
            String v = rawHeader.get(i);
            if (v == null) v = "";
            String t = v.trim();
            if (!t.isEmpty()) {
                res.add(t);
                // 只把"选项/填空项/答案"类的组名扩散
                if (t.contains("选项") || t.contains("填空项")
                        || t.equals("答案") || t.contains("正确答案")) {
                    lastGroup = t;
                } else {
                    lastGroup = "";
                }
            } else {
                if (!lastGroup.isEmpty()) {
                    res.add(lastGroup);
                } else {
                    res.add(""); // 保留空，等后面兜底列N
                }
            }
        }
        return res;
    }

    /** 合并主表头（已扩散组名）与子表头行:生成最终列名列表 */
    private static List<String> mergeHeaderWithSubHeader(List<String> mainHeaderSpread,
                                                         List<String> subHeaderRow,
                                                         int columnCount) {
        if (mainHeaderSpread == null) mainHeaderSpread = new ArrayList<>();
        if (subHeaderRow == null) subHeaderRow = new ArrayList<>();
        int len = Math.max(columnCount, Math.max(mainHeaderSpread.size(), subHeaderRow.size()));
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            String m = (i < mainHeaderSpread.size()) ? mainHeaderSpread.get(i) : "";
            String s = (i < subHeaderRow.size()) ? subHeaderRow.get(i) : "";
            if (m == null) m = ""; else m = m.trim();
            if (s == null) s = ""; else s = s.trim();
            if (m.isEmpty() && s.isEmpty()) {
                merged.add("列" + (i + 1));
            } else if (s.isEmpty()) {
                merged.add(m);
            } else if (m.isEmpty()) {
                merged.add(s);
            } else {
                // 两边都有:分类处理
                String norm = normalizeSubHeaderToken(s);
                // norm 可能是 "A"/"B"/... "空1"/"空2"... 或原样
                if (norm.matches("[A-L]")) {
                    if (m.contains("选项")) {
                        merged.add("选项" + norm);
                    } else {
                        merged.add(m + norm);
                    }
                } else if (norm.matches("空\\d{1,2}")) {
                    // 填空项类：空1 空2 直接用
                    merged.add(norm);
                } else {
                    // 其他（如"选项一"规范化为"选项A"等 无法识别的保留原样）
                    merged.add(m + "_" + s);
                }
            }
        }
        return merged;
    }

    /** 兜底:从 sampleRows[headerIdx] 提取列名,空列用"列N"兜底 */
    private static List<String> simpleHeaderOf(List<List<String>> sampleRows, int headerIdx, int columnCount) {
        List<String> headers = new ArrayList<>();
        if (sampleRows == null || headerIdx < 0 || headerIdx >= sampleRows.size()) {
            for (int i = 0; i < columnCount; i++) headers.add("列" + (i + 1));
            return headers;
        }
        List<String> row = spreadGroupHeader(sampleRows.get(headerIdx));
        int count = Math.max(row.size(), columnCount);
        for (int i = 0; i < count; i++) {
            String v = (i < row.size()) ? row.get(i) : "";
            if (v == null || v.trim().isEmpty()) headers.add("列" + (i + 1));
            else headers.add(v.trim());
        }
        return headers;
    }

    /**
     * 新一代多相扫描主入口。
     * Phase 1: 对每行打类型标签（EMPTY / DESCRIPTION / HEADER_CAND / SUB_HEADER / DATA）
     * Phase 2: 选 headerRowScore 最高的行作 headerIdx
     * Phase 3: 从 headerIdx+1 起逐行判定，处理"子表头/空行/说明行/数据行"的过渡关系
     * Phase 4: 生成最终表头列名（扩散+合并）
     */
    private static StructureResult detectSheetStructure(List<List<String>> sampleRows, int columnCount) {
        StructureResult r = new StructureResult();
        if (sampleRows == null || sampleRows.isEmpty()) {
            r.headerIdx = 0;
            r.subHeaderIdx = -1;
            r.dataStartIdx = 1;
            r.finalHeader = new ArrayList<>();
            for (int i = 0; i < Math.max(columnCount, 1); i++) r.finalHeader.add("列" + (i + 1));
            return r;
        }

        // Phase 2: headerIdx
        int headerIdx = pickHeaderRowIndex(sampleRows);
        r.headerIdx = headerIdx;

        List<String> mainRow = sampleRows.get(headerIdx);
        List<String> mainSpread = spreadGroupHeader(mainRow);

        // Phase 3: 从 headerIdx+1 扫描
        int pointer = headerIdx + 1;
        int subHeaderIdx = -1;
        List<String> subHeaderRow = null;
        // 最多扫描 8 行，防止文件结构不规范时越过数据区
        int maxScan = Math.min(8, sampleRows.size() - pointer);
        for (int step = 0; step < maxScan; step++) {
            int idx = pointer + step;
            if (idx >= sampleRows.size()) break;
            List<String> row = sampleRows.get(idx);
            // (a) 空行 → 跳过
            if (isPureEmpty(row)) {
                Log.d(TAG, "  phase3 idx=" + idx + " → EMPTY, skip");
                continue;
            }
            // (b) 子表头行 → 仅合并一次（pointer+step 这一行）
            if (subHeaderIdx < 0 && isSubHeaderRow(row)) {
                subHeaderIdx = idx;
                subHeaderRow = row;
                Log.d(TAG, "  phase3 idx=" + idx + " → SUB_HEADER, merged");
                continue;
            }
            // (c) 说明行/注释行 → 跳过
            if (isDescriptionRow(row)) {
                Log.d(TAG, "  phase3 idx=" + idx + " → DESCRIPTION, skip");
                continue;
            }
            // (d) 数据行 → 停止扫描
            if (looksLikeDataRow(row, mainRow)) {
                pointer = idx;
                Log.d(TAG, "  phase3 idx=" + idx + " → DATA (first row)");
                break;
            }
            // (e) 无法识别：再根据非空列判断是否像数据
            int ne = countNonEmpty(row);
            if (ne >= 3) {
                pointer = idx;
                Log.d(TAG, "  phase3 idx=" + idx + " → fallback as DATA (nonEmpty=" + ne + ")");
                break;
            }
        }
        // 如果循环没命中数据行，退回到 headerIdx + (hasSubHeader ? 2 : 1)
        int fallbackDataStart = headerIdx + (subHeaderIdx >= 0 ? 2 : 1);
        if (pointer <= headerIdx) pointer = fallbackDataStart;
        // 如果 pointer 落在子表头行或更前，强制跳过
        if (subHeaderIdx >= 0 && pointer <= subHeaderIdx) pointer = subHeaderIdx + 1;

        r.subHeaderIdx = subHeaderIdx;
        r.dataStartIdx = pointer;

        // Phase 4: 生成表头列名
        if (subHeaderIdx >= 0 && subHeaderRow != null) {
            r.finalHeader = mergeHeaderWithSubHeader(mainSpread, subHeaderRow, columnCount);
        } else {
            r.finalHeader = simpleHeaderOf(sampleRows, headerIdx, columnCount);
        }
        return r;
    }

    /** 取表头行列名:单元格转字符串,空值兜底为 "列N"（保留兼容,直接用新函数） */
    private static List<String> extractHeaderColumns(List<List<String>> sampleRows,
                                                      int headerRowIndex, int columnCount) {
        return simpleHeaderOf(sampleRows, headerRowIndex, columnCount);
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
            workbook = WorkbookFactory.create(fis);
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
