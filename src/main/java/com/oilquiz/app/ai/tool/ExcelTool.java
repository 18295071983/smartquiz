package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 表格工具（xls / xlsx）：查询与修改。
 *
 * 查询：sheets(工作表列表)、query(按条件过滤行)、cell(读单元格)
 * 修改：write_cell(改单元格)、add_row(追加行)、add_sheet(新建工作表) —— 修改后立即保存
 *
 * 坐标约定：
 * - sheet：sheet 名称或索引（0-based），默认 0
 * - cell_ref：A1 风格，如 "B3"
 * - row/column：1-based 行号 / 列名(表头)或1-based列号
 *
 * 输入支持绝对路径 / 应用目录相对路径 / content:// URI（file_uri）。
 * 修改默认保存回原文件，可指定 output_path 另存为新文件。
 */
@Tool(
    value = "excel_tool",
    description = "Excel表格工具：查询与修改xls/xlsx。查询=sheets(工作表列表)/query(条件过滤行)/cell(读单元格)；修改=write_cell(改单元格)/add_row(追加行)/add_sheet(新建工作表)，修改后自动保存",
    category = "data",
    aliases = {"excel", "表格", "xlsx", "xls", "电子表格", "spreadsheet"},
    actions = {
        @Action(name = "sheets", description = "列出所有工作表及行列数"),
        @Action(name = "query", description = "按条件查询行(支持列名=值/包含/大于/小于)"),
        @Action(name = "cell", description = "读取指定单元格"),
        @Action(name = "write_cell", description = "修改指定单元格并保存"),
        @Action(name = "add_row", description = "追加一行并保存"),
        @Action(name = "add_sheet", description = "新建工作表并保存")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "file_path", type = "string", description = "文件路径(支持content://开头URI)", required = false),
        @Param(name = "file_uri", type = "string", description = "content:// URI(与file_path二选一)", required = false),
        @Param(name = "output_path", type = "string", description = "另存路径(不传默认保存回原文件)", required = false),
        @Param(name = "sheet", type = "string", description = "工作表名称或索引(默认0)", required = false),
        @Param(name = "cell_ref", type = "string", description = "单元格A1引用如B3(write_cell/cell用)", required = false),
        @Param(name = "row", type = "integer", description = "1-based行号(与column配合)", required = false),
        @Param(name = "column", type = "string", description = "列名或1-based列号(与row配合)", required = false),
        @Param(name = "value", type = "string", description = "写入值(write_cell用，自动识别数字/布尔/文本)", required = false),
        @Param(name = "values", type = "array", description = "行数据数组(add_row用，如[\"张三\",18,\"北京\"])", required = false),
        @Param(name = "new_sheet_name", type = "string", description = "新工作表名称(add_sheet用)", required = false),
        @Param(name = "column_name", type = "string", description = "条件列名(query用，与row_column二选一)", required = false),
        @Param(name = "row_column", type = "string", description = "条件列名(column_name的别名)", required = false),
        @Param(name = "op", type = "string", description = "比较操作: eq(等于,默认)/ne/contains/gt/gte/lt/lte", required = false),
        @Param(name = "match_value", type = "string", description = "匹配值(query用)", required = false),
        @Param(name = "row_start", type = "integer", description = "起始行号(query用，1-based，含)", required = false),
        @Param(name = "row_end", type = "integer", description = "结束行号(query用，1-based，含)", required = false),
        @Param(name = "max_rows", type = "integer", description = "最大返回行数(query用，默认100)", required = false)
    }
)
public class ExcelTool implements AITool {

    private static final String TAG = "ExcelTool";
    private static final long MAX_FILE_BYTES = 5 * 1024 * 1024;

    private final Context context;

    public ExcelTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "excel_tool";
    }

    @Override
    public String getDescription() {
        return "Excel表格工具：查询与修改xls/xlsx。查询=sheets(工作表列表)/query(条件过滤行)/cell(读单元格)；"
                + "修改=write_cell(改单元格)/add_row(追加行)/add_sheet(新建工作表)，修改后自动保存到原文件或output_path。"
                + "坐标：sheet(名称或索引)、cell_ref(A1如B3)、row(1-based)+column(列名或列号)。支持content:// URI(file_uri)。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: sheets(列工作表)/query(条件查行)/cell(读单元格)/write_cell(改单元格)/add_row(追加行)/add_sheet(新建工作表)");
        params.put("file_path", "文件路径(支持content://开头URI，与file_uri二选一)");
        params.put("file_uri", "content:// URI(与file_path二选一)");
        params.put("output_path", "另存路径(修改类操作，不传默认保存回原文件)");
        params.put("sheet", "工作表名称或索引(默认0)");
        params.put("cell_ref", "单元格A1引用如B3(write_cell/cell用)");
        params.put("row", "1-based行号(与column配合定位单元格)");
        params.put("column", "列名(表头)或1-based列号(与row配合)");
        params.put("value", "写入值(write_cell用，自动识别数字/布尔/文本)");
        params.put("values", "行数据数组(add_row用，如[\"张三\",18,\"北京\"])");
        params.put("new_sheet_name", "新工作表名称(add_sheet用)");
        params.put("column_name", "条件列名(query用，别名row_column)");
        params.put("op", "比较操作: eq(等于,默认)/ne/contains/gt/gte/lt/lte");
        params.put("match_value", "匹配值(query用)");
        params.put("row_start", "起始行号(query用，1-based，含)");
        params.put("row_end", "结束行号(query用，1-based，含)");
        params.put("max_rows", "最大返回行数(query用，默认100)");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "sheets";
            switch (action) {
                case "sheets":
                    return sheets(parameters);
                case "query":
                    return query(parameters);
                case "cell":
                    return readCell(parameters);
                case "write_cell":
                case "write":
                    return writeCell(parameters);
                case "add_row":
                    return addRow(parameters);
                case "add_sheet":
                    return addSheet(parameters);
                default:
                    return AIToolResult.fail("未知操作: " + action);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Excel工具执行失败: " + e.getMessage(), e);
            return AIToolResult.fail("Excel操作失败: " + e.getMessage());
        }
    }

    // ==================== 输入解析 ====================

    private File resolveInputFile(Map<String, Object> parameters) {
        String filePath = parameters.get("file_path") != null
                ? String.valueOf(parameters.get("file_path")) : null;
        String uriStr = parameters.get("file_uri") != null
                ? String.valueOf(parameters.get("file_uri")) : null;
        if ((uriStr == null || uriStr.isEmpty()) && filePath != null && filePath.startsWith("content://")) {
            uriStr = filePath;
        }
        if (uriStr != null && !uriStr.isEmpty()) {
            try {
                Uri uri = Uri.parse(uriStr);
                File cache = new File(context.getCacheDir(),
                        "agent_excel_" + System.currentTimeMillis() + ".tmp");
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(cache)) {
                    if (in == null) return null;
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                return cache;
            } catch (Exception e) {
                AILogger.w(TAG, "URI读取失败: " + uriStr + " - " + e.getMessage());
                return null;
            }
        }
        if (filePath == null) return null;
        File file = new File(filePath);
        if (file.exists()) return file;
        File appFile = new File(context.getFilesDir(), filePath);
        return appFile.exists() ? appFile : null;
    }

    /** 打开工作簿（只读输入时使用；修改操作要重新打开以保持原格式写入） */
    private org.apache.poi.ss.usermodel.Workbook openWorkbook(File file) throws Exception {
        if (!file.exists()) throw new Exception("文件不存在: " + file.getAbsolutePath());
        if (file.length() > MAX_FILE_BYTES) {
            throw new Exception("文件过大（" + formatSize(file.length()) + " > 5MB），请先拆分");
        }
        return org.apache.poi.ss.usermodel.WorkbookFactory.create(new FileInputStream(file));
    }

    /** 保存工作簿：优先 output_path，否则写回原文件 */
    private File saveWorkbook(org.apache.poi.ss.usermodel.Workbook workbook,
                              Map<String, Object> parameters, File source) throws Exception {
        String output = parameters.get("output_path") != null
                ? String.valueOf(parameters.get("output_path")) : null;
        File target;
        if (output != null && !output.isEmpty()) {
            target = new File(output);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
        } else {
            target = source;
        }
        try (FileOutputStream fos = new FileOutputStream(target)) {
            workbook.write(fos);
        }
        return target;
    }

    // ==================== 工作表/单元格工具 ====================

    private org.apache.poi.ss.usermodel.Sheet resolveSheet(org.apache.poi.ss.usermodel.Workbook workbook,
                                                            Map<String, Object> parameters) throws Exception {
        Object sheetParam = parameters.get("sheet");
        if (sheetParam == null) {
            return workbook.getSheetAt(0);
        }
        String s = String.valueOf(sheetParam).trim();
        // 数字索引
        try {
            int idx = Integer.parseInt(s);
            if (idx >= 0 && idx < workbook.getNumberOfSheets()) {
                return workbook.getSheetAt(idx);
            }
            throw new Exception("sheet 索引越界（共 " + workbook.getNumberOfSheets() + " 个）");
        } catch (NumberFormatException ignored) {
        }
        org.apache.poi.ss.usermodel.Sheet byName = workbook.getSheet(s);
        if (byName == null) {
            throw new Exception("未找到工作表: " + s + "（可用 sheets 查看全部）");
        }
        return byName;
    }

    /** 列名(表头)或1-based列号 → 0-based列索引 */
    private int resolveColumnIndex(org.apache.poi.ss.usermodel.Sheet sheet, String column) throws Exception {
        String c = column == null ? "" : column.trim();
        if (c.isEmpty()) throw new Exception("缺少 column（列名或列号）");
        // 纯数字：1-based 列号
        try {
            int idx = Integer.parseInt(c);
            if (idx < 1) throw new Exception("列号必须 ≥1");
            return idx - 1;
        } catch (NumberFormatException ignored) {
        }
        // 列字母：A→0, B→1, AA→26
        if (c.matches("[a-zA-Z]+")) {
            int col = 0;
            for (int i = 0; i < c.length(); i++) {
                col = col * 26 + (Character.toUpperCase(c.charAt(i)) - 'A' + 1);
            }
            return col - 1;
        }
        // 表头匹配
        org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
        if (headerRow != null) {
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                String h = getCellValueAsString(headerRow.getCell(i));
                if (c.equalsIgnoreCase(h != null ? h.trim() : "")) {
                    return i;
                }
            }
        }
        throw new Exception("找不到列: " + column + "（支持表头列名/列字母A,B,C/1-based列号）");
    }

    /** A1引用 → [0-based行, 0-based列] */
    private int[] parseCellRef(String ref) throws Exception {
        String r = ref == null ? "" : ref.trim();
        int i = 0;
        while (i < r.length() && Character.isLetter(r.charAt(i))) i++;
        if (i == 0 || i >= r.length()) throw new Exception("单元格引用格式错误: " + ref + "（应为A1风格如B3）");
        String colLetters = r.substring(0, i);
        int rowNum;
        try {
            rowNum = Integer.parseInt(r.substring(i));
        } catch (NumberFormatException e) {
            throw new Exception("单元格引用格式错误: " + ref + "（应为A1风格如B3）");
        }
        int col = 0;
        for (int k = 0; k < colLetters.length(); k++) {
            col = col * 26 + (Character.toUpperCase(colLetters.charAt(k)) - 'A' + 1);
        }
        return new int[]{rowNum - 1, col - 1};
    }

    /** 定位单元格：优先 cell_ref，其次 row+column */
    private org.apache.poi.ss.usermodel.Cell locateCell(org.apache.poi.ss.usermodel.Sheet sheet,
                                                        Map<String, Object> parameters) throws Exception {
        Object refObj = parameters.get("cell_ref");
        if (refObj != null) {
            int[] rc = parseCellRef(String.valueOf(refObj));
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(rc[0]);
            if (row == null) row = sheet.createRow(rc[0]);
            return row.getCell(rc[1]) != null ? row.getCell(rc[1]) : row.createCell(rc[1]);
        }
        Object rowObj = parameters.get("row");
        Object colObj = parameters.get("column");
        if (rowObj == null || colObj == null) {
            throw new Exception("缺少定位参数：请传 cell_ref（如B3）或 row+column");
        }
        int rowIdx = Integer.parseInt(String.valueOf(rowObj).trim()) - 1;
        if (rowIdx < 0) throw new Exception("row 必须 ≥1");
        int colIdx = resolveColumnIndex(sheet, String.valueOf(colObj));
        org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        return row.getCell(colIdx) != null ? row.getCell(colIdx) : row.createCell(colIdx);
    }

    /** 读取表头（空/重复自动兜底，与 file_reader 一致） */
    private List<String> extractHeaders(org.apache.poi.ss.usermodel.Sheet sheet) {
        List<String> headers = new ArrayList<>();
        org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
        if (headerRow != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String h = getCellValueAsString(headerRow.getCell(c));
                if (h == null || h.trim().isEmpty()) {
                    h = "col_" + c;
                } else {
                    h = h.trim();
                    String base = h;
                    int dup = 1;
                    while (seen.contains(h)) {
                        h = base + "_" + (++dup);
                    }
                }
                seen.add(h);
                headers.add(h);
            }
        }
        if (headers.isEmpty()) {
            headers.add("col_0");
        }
        return headers;
    }

    /** 单元格转字符串（公式求值、日期带时间、数字去科学计数法） */
    private String getCellValueAsString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        try {
            switch (cell.getCellType()) {
                case STRING:
                    return cell.getStringCellValue();
                case NUMERIC:
                    if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                        return sdf.format(cell.getDateCellValue());
                    }
                    double num = cell.getNumericCellValue();
                    if (num == Math.floor(num) && !Double.isInfinite(num) && Math.abs(num) < 1e15) {
                        return String.valueOf((long) num);
                    }
                    return new java.math.BigDecimal(num).stripTrailingZeros().toPlainString();
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                case FORMULA:
                    return cell.getCellFormula();
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 写入值：自动识别布尔/数字/文本 */
    private void setCellValue(org.apache.poi.ss.usermodel.Cell cell, String value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            cell.setCellValue(Boolean.parseBoolean(v));
        } else if (v.startsWith("=")) {
            cell.setCellFormula(v.substring(1));
        } else {
            try {
                cell.setCellValue(Double.parseDouble(v));
            } catch (NumberFormatException e) {
                cell.setCellValue(value);
            }
        }
    }

    /** 读取 sheet 为 headers + rows（带行号，供 query/输出） */
    private Map<String, Object> readSheetData(org.apache.poi.ss.usermodel.Sheet sheet, int maxRows) {
        List<String> headers = extractHeaders(sheet);
        List<Map<String, Object>> rows = new ArrayList<>();
        int endRow = Math.min(sheet.getPhysicalNumberOfRows(), maxRows + 1);
        for (int r = 1; r < endRow; r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, Object> rowData = new LinkedHashMap<>();
            rowData.put("row", r + 1); // Excel 行号（1-based，含表头行）
            for (int c = 0; c < headers.size(); c++) {
                String key = headers.get(c);
                String val = c < row.getLastCellNum() ? getCellValueAsString(row.getCell(c)) : "";
                rowData.put(key, val);
            }
            rows.add(rowData);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headers", headers);
        result.put("rows", rows);
        return result;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    // ==================== 查询操作 ====================

    /** sheets：列出所有工作表（名称/行列数） */
    private AIToolResult sheets(Map<String, Object> parameters) {
        File file = resolveInputFile(parameters);
        if (file == null) return AIToolResult.fail("缺少参数: file_path（或 file_uri）");
        org.apache.poi.ss.usermodel.Workbook workbook = null;
        try {
            workbook = openWorkbook(file);
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                org.apache.poi.ss.usermodel.Sheet s = workbook.getSheetAt(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", i);
                item.put("name", s.getSheetName());
                item.put("rows", s.getPhysicalNumberOfRows());
                item.put("columns", s.getRow(0) != null ? s.getRow(0).getLastCellNum() : 0);
                list.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheetCount", list.size());
            result.put("sheets", list);
            return AIToolResult.success(result);
        } catch (Exception e) {
            AILogger.e(TAG, "sheets 失败: " + e.getMessage(), e);
            return AIToolResult.fail("读取工作表列表失败: " + e.getMessage());
        } finally {
            if (workbook != null) {
                try { workbook.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** query：按条件过滤行 */
    private AIToolResult query(Map<String, Object> parameters) {
        File file = resolveInputFile(parameters);
        if (file == null) return AIToolResult.fail("缺少参数: file_path（或 file_uri）");
        org.apache.poi.ss.usermodel.Workbook workbook = null;
        try {
            workbook = openWorkbook(file);
            org.apache.poi.ss.usermodel.Sheet sheet = resolveSheet(workbook, parameters);

            String columnName = parameters.get("column_name") != null
                    ? String.valueOf(parameters.get("column_name"))
                    : (parameters.get("row_column") != null ? String.valueOf(parameters.get("row_column")) : null);
            String op = parameters.get("op") != null ? String.valueOf(parameters.get("op")) : "eq";
            String matchValue = parameters.get("match_value") != null
                    ? String.valueOf(parameters.get("match_value")) : "";
            int maxRows = intParam(parameters, "max_rows", 100, 1, 500);
            int rowStart = intParam(parameters, "row_start", 1, 1, Integer.MAX_VALUE);
            int rowEnd = intParam(parameters, "row_end", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

            Map<String, Object> data = readSheetData(sheet, Math.max(maxRows * 4, 1000));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allRows = (List<Map<String, Object>>) data.get("rows");
            @SuppressWarnings("unchecked")
            List<String> headers = (List<String>) data.get("headers");

            List<Map<String, Object>> matched = new ArrayList<>();
            int matchCount = 0;
            for (Map<String, Object> row : allRows) {
                int excelRow = ((Number) row.get("row")).intValue();
                if (excelRow < rowStart || excelRow > rowEnd) continue;
                if (columnName != null && !columnName.isEmpty()) {
                    String cellVal = row.containsKey(columnName) ? String.valueOf(row.get(columnName)) : "";
                    if (!matches(cellVal, op, matchValue)) continue;
                }
                matched.add(row);
                matchCount++;
                if (matched.size() >= maxRows) break;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheet", sheet.getSheetName());
            result.put("headers", headers);
            result.put("matchCount", matchCount);
            result.put("rows", matched);
            result.put("condition", columnName != null && !columnName.isEmpty()
                    ? columnName + " " + op + " " + matchValue : "无(行号区间)");
            return AIToolResult.success(result);
        } catch (Exception e) {
            AILogger.e(TAG, "query 失败: " + e.getMessage(), e);
            return AIToolResult.fail("查询失败: " + e.getMessage());
        } finally {
            if (workbook != null) {
                try { workbook.close(); } catch (Exception ignored) { }
            }
        }
    }

    private boolean matches(String cellVal, String op, String matchValue) {
        String a = cellVal == null ? "" : cellVal.trim();
        String b = matchValue == null ? "" : matchValue.trim();
        switch (op) {
            case "eq":
            default:
                return a.equals(b);
            case "ne":
                return !a.equals(b);
            case "contains":
                return a.contains(b);
            case "gt":
                return compareNumeric(a, b) > 0;
            case "gte":
                return compareNumeric(a, b) >= 0;
            case "lt":
                return compareNumeric(a, b) < 0;
            case "lte":
                return compareNumeric(a, b) <= 0;
        }
    }

    private int compareNumeric(String a, String b) {
        try {
            double da = Double.parseDouble(a);
            double db = Double.parseDouble(b);
            return Double.compare(da, db);
        } catch (NumberFormatException e) {
            return a.compareToIgnoreCase(b); // 非数字按文本比较
        }
    }

    /** cell：读取指定单元格 */
    private AIToolResult readCell(Map<String, Object> parameters) {
        File file = resolveInputFile(parameters);
        if (file == null) return AIToolResult.fail("缺少参数: file_path（或 file_uri）");
        org.apache.poi.ss.usermodel.Workbook workbook = null;
        try {
            workbook = openWorkbook(file);
            org.apache.poi.ss.usermodel.Sheet sheet = resolveSheet(workbook, parameters);
            org.apache.poi.ss.usermodel.Cell cell = locateCell(sheet, parameters);
            String value = getCellValueAsString(cell);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheet", sheet.getSheetName());
            Object refObj = parameters.get("cell_ref");
            if (refObj != null) {
                result.put("cell", String.valueOf(refObj));
            } else {
                result.put("row", parameters.get("row"));
                result.put("column", parameters.get("column"));
            }
            result.put("value", value);
            return AIToolResult.success(result);
        } catch (Exception e) {
            AILogger.e(TAG, "cell 读取失败: " + e.getMessage(), e);
            return AIToolResult.fail("读取单元格失败: " + e.getMessage());
        } finally {
            if (workbook != null) {
                try { workbook.close(); } catch (Exception ignored) { }
            }
        }
    }

    // ==================== 修改操作 ====================

    /** write_cell：修改单元格并保存 */
    private AIToolResult writeCell(Map<String, Object> parameters) {
        File file = resolveInputFile(parameters);
        if (file == null) return AIToolResult.fail("缺少参数: file_path（或 file_uri）");
        String value = parameters.get("value") != null ? String.valueOf(parameters.get("value")) : null;
        org.apache.poi.ss.usermodel.Workbook workbook = null;
        try {
            workbook = openWorkbook(file);
            org.apache.poi.ss.usermodel.Sheet sheet = resolveSheet(workbook, parameters);
            org.apache.poi.ss.usermodel.Cell cell = locateCell(sheet, parameters);
            String oldValue = getCellValueAsString(cell);
            setCellValue(cell, value);

            File target = saveWorkbook(workbook, parameters, file);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheet", sheet.getSheetName());
            Object refObj = parameters.get("cell_ref");
            if (refObj != null) {
                result.put("cell", String.valueOf(refObj));
            } else {
                result.put("row", parameters.get("row"));
                result.put("column", parameters.get("column"));
            }
            result.put("oldValue", oldValue);
            result.put("newValue", value == null ? "(空)" : value);
            result.put("savedTo", target.getAbsolutePath());
            return AIToolResult.success("已写入 " + sheet.getSheetName()
                    + (refObj != null ? "!" + refObj : "") + ": " + oldValue + " → " + value
                    + "（保存至 " + target.getAbsolutePath() + "）", result);
        } catch (Exception e) {
            AILogger.e(TAG, "write_cell 失败: " + e.getMessage(), e);
            return AIToolResult.fail("写入单元格失败: " + e.getMessage());
        } finally {
            if (workbook != null) {
                try { workbook.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** add_row：追加一行并保存 */
    private AIToolResult addRow(Map<String, Object> parameters) {
        File file = resolveInputFile(parameters);
        if (file == null) return AIToolResult.fail("缺少参数: file_path（或 file_uri）");
        org.apache.poi.ss.usermodel.Workbook workbook = null;
        try {
            workbook = openWorkbook(file);
            org.apache.poi.ss.usermodel.Sheet sheet = resolveSheet(workbook, parameters);

            // values 支持 JSONArray / List / 逗号分隔字符串
            List<String> values = new ArrayList<>();
            Object valuesObj = parameters.get("values");
            if (valuesObj instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) valuesObj;
                for (int i = 0; i < arr.length(); i++) {
                    values.add(arr.isNull(i) ? null : String.valueOf(arr.opt(i)));
                }
            } else if (valuesObj instanceof List) {
                for (Object o : (List<?>) valuesObj) {
                    values.add(o == null ? null : String.valueOf(o));
                }
            } else if (valuesObj instanceof String) {
                String s = ((String) valuesObj).trim();
                if (s.startsWith("[")) {
                    org.json.JSONArray arr = new org.json.JSONArray(s);
                    for (int i = 0; i < arr.length(); i++) {
                        values.add(arr.isNull(i) ? null : String.valueOf(arr.opt(i)));
                    }
                } else if (!s.isEmpty()) {
                    for (String part : s.split(",", -1)) {
                        values.add(part.trim());
                    }
                }
            }
            if (values.isEmpty()) {
                return AIToolResult.fail("缺少参数: values（要追加的行数据数组）");
            }

            int lastRowNum = sheet.getLastRowNum();
            org.apache.poi.ss.usermodel.Row newRow = sheet.createRow(lastRowNum + 1);
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i) == null) continue;
                setCellValue(newRow.createCell(i), values.get(i));
            }
            int excelRowNum = newRow.getRowNum() + 1;

            File target = saveWorkbook(workbook, parameters, file);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheet", sheet.getSheetName());
            result.put("row", excelRowNum);
            result.put("values", values);
            result.put("savedTo", target.getAbsolutePath());
            return AIToolResult.success("已在 " + sheet.getSheetName() + " 追加第 " + excelRowNum
                    + " 行（保存至 " + target.getAbsolutePath() + "）", result);
        } catch (Exception e) {
            AILogger.e(TAG, "add_row 失败: " + e.getMessage(), e);
            return AIToolResult.fail("追加行失败: " + e.getMessage());
        } finally {
            if (workbook != null) {
                try { workbook.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** add_sheet：新建工作表并保存 */
    private AIToolResult addSheet(Map<String, Object> parameters) {
        File file = resolveInputFile(parameters);
        if (file == null) return AIToolResult.fail("缺少参数: file_path（或 file_uri）");
        String newName = parameters.get("new_sheet_name") != null
                ? String.valueOf(parameters.get("new_sheet_name")).trim() : null;
        if (newName == null || newName.isEmpty()) {
            return AIToolResult.fail("缺少参数: new_sheet_name（新工作表名称）");
        }
        org.apache.poi.ss.usermodel.Workbook workbook = null;
        try {
            workbook = openWorkbook(file);
            if (workbook.getSheet(newName) != null) {
                return AIToolResult.fail("工作表已存在: " + newName);
            }
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(newName);
            File target = saveWorkbook(workbook, parameters, file);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheet", newName);
            result.put("sheetIndex", workbook.getSheetIndex(sheet));
            result.put("savedTo", target.getAbsolutePath());
            return AIToolResult.success("已新建工作表: " + newName
                    + "（保存至 " + target.getAbsolutePath() + "）", result);
        } catch (Exception e) {
            AILogger.e(TAG, "add_sheet 失败: " + e.getMessage(), e);
            return AIToolResult.fail("新建工作表失败: " + e.getMessage());
        } finally {
            if (workbook != null) {
                try { workbook.close(); } catch (Exception ignored) { }
            }
        }
    }

    // ==================== 工具方法 ====================

    private static int intParam(Map<String, Object> parameters, String key, int defaultValue,
                                int min, int max) {
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
