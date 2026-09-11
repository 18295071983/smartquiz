package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.util.CharsetDetector;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Tool(
    value = "file_reader",
    description = "文件阅读工具，支持读取文本文件、按行读取、搜索文本、提取实体、预览",
    category = "file",
    actions = {
        @Action(name = "read", description = "读取文件内容"),
        @Action(name = "read_lines", description = "按行读取文件"),
        @Action(name = "extract_text", description = "提取文本内容"),
        @Action(name = "search_text", description = "搜索文本"),
        @Action(name = "extract_entities", description = "提取实体信息"),
        @Action(name = "preview", description = "预览文件"),
        @Action(name = "parse_structured", description = "解析结构化文件(Excel/CSV/JSON/XML)，自动识别格式"),
        @Action(name = "parse_excel", description = "解析Excel文件，返回表格数据"),
        @Action(name = "parse_csv", description = "解析CSV文件"),
        @Action(name = "parse_json", description = "解析JSON文件"),
        @Action(name = "parse_xml", description = "解析XML文件，提取标签内容"),
        @Action(name = "list", description = "列出目录中的文件")
    },
    params = {
        @Param(name = "file_path", type = "string", description = "文件路径(必填，除list外；支持content://开头URI)", required = false),
        @Param(name = "file_uri", type = "string", description = "content:// URI(与file_path二选一)", required = false),
        @Param(name = "directory_path", type = "string", description = "目录路径(list用，留空默认应用目录)", required = false),
        @Param(name = "encoding", type = "string", description = "文件编码(留空自动检测UTF-8/UTF-16/GB18030/GBK)", required = false),
        @Param(name = "action", type = "string", description = "操作类型", required = true)
    }
)
public class FileReaderTool implements AITool {
    private static final String TAG = "FileReaderTool";
    private final Context context;

    /** 整读文件大小上限：超过则提示改用 read_lines/preview/search_text/parse_*（防止大文件 OOM） */
    private static final long MAX_READ_BYTES = 5 * 1024 * 1024;
    
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}|0\\d{2,3}-\\d{7,8}");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})|(\\d{4}年\\d{1,2}月\\d{1,2}日)");
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    
    public FileReaderTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "file_reader";
    }
    
    @Override
    public String getDescription() {
        return "文件阅读工具：读取全文(read)/按行(read_lines)/区间提取(extract_text)/搜索(search_text)/实体提取(extract_entities)/预览(preview)/解析结构化文件(parse_excel/csv/json/xml/parse_structured)/列目录(list)。大文件用 read_lines/preview/search_text 分片读取。parse_excel 返回 sheetSummaries（每张表的 index/名称/数据行数），多表文件先看它判断哪张是数据主表（数据行最多的），再带 sheet_index 精读。示例：读文件全文→file_reader(action=read, path=report.md)；只看前50行→file_reader(action=read_lines, path=log.txt, start_line=1, end_line=50)；在文件里搜关键词→file_reader(action=search_text, path=data.csv, keyword=错误)；解析Excel→file_reader(action=parse_excel, path=成绩表.xlsx)；列工作区目录→file_reader(action=list, path=.)";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                action = "read";
            }
            
            switch (action) {
                case "read":
                    return readFile(parameters);
                case "read_lines":
                    return readLines(parameters);
                case "extract_text":
                    return extractText(parameters);
                case "search_text":
                    return searchText(parameters);
                case "extract_entities":
                    return extractEntities(parameters);
                case "preview":
                    return previewFile(parameters);
                case "parse_structured":
                    return parseStructuredAuto(parameters);
                case "parse_excel":
                    return parseExcel(parameters);
                case "parse_csv":
                    return parseCsv(parameters);
                case "parse_json":
                    return parseJson(parameters);
                case "parse_xml":
                    return parseXml(parameters);
                case "list":
                    return listFiles(parameters);
                default:
                    return readFile(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing file reader: " + e.getMessage(), e);
            return new AIToolResult("文件阅读失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult readFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String encoding = (String) parameters.get("encoding");

        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }

        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) {
            return fileNotFoundResult(filePath, parameters);
        }
        if (!file.isFile()) {
            return new AIToolResult("不是文件: " + filePath, parameters);
        }
        if (file.length() > MAX_READ_BYTES) {
            return new AIToolResult("文件过大（" + formatSize(file.length()) + " > 5MB），整读可能内存溢出。"
                    + "请改用 read_lines(按行) / search_text(搜索) / extract_text(区间提取) / preview(预览) 分片读取",
                    parameters);
        }
        
        try {
            Object[] readerInfo = openTextReader(file, encoding);
            BufferedReader reader = (BufferedReader) readerInfo[0];
            String actualEncoding = (String) readerInfo[1];
            if (reader == null) {
                return new AIToolResult("无法打开文件: " + filePath, parameters);
            }
            
            StringBuilder content = new StringBuilder();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
                lineCount++;
            }
            reader.close();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("content", content.toString());
            result.put("lineCount", lineCount);
            result.put("byteCount", file.length());
            result.put("encoding", actualEncoding);
            result.put("encodingAutoDetected", encoding == null || encoding.trim().isEmpty());
            result.put("fileName", file.getName());
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("读取文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult readLines(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Integer startLine = (Integer) parameters.get("startLine");
        Integer endLine = (Integer) parameters.get("endLine");
        String encoding = (String) parameters.get("encoding");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (startLine == null) startLine = 1;
        if (endLine == null) endLine = 50;
        
        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Object[] readerInfo = openTextReader(file, encoding);
            BufferedReader reader = (BufferedReader) readerInfo[0];
            String actualEncoding = (String) readerInfo[1];
            if (reader == null) {
                return new AIToolResult("无法打开文件: " + filePath, parameters);
            }
            
            List<String> lines = new ArrayList<>();
            String line;
            int currentLine = 0;
            
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine >= startLine && currentLine <= endLine) {
                    lines.add(line);
                }
                if (currentLine > endLine) {
                    break;
                }
            }
            reader.close();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("lines", lines);
            result.put("startLine", startLine);
            result.put("endLine", startLine + lines.size() - 1);
            result.put("totalRead", lines.size());
            result.put("encoding", actualEncoding);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("读取文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult extractText(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String startMarker = (String) parameters.get("startMarker");
        String endMarker = (String) parameters.get("endMarker");
        String encoding = (String) parameters.get("encoding");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Object[] readerInfo = openTextReader(file, encoding);
            BufferedReader reader = (BufferedReader) readerInfo[0];
            if (reader == null) {
                return new AIToolResult("无法打开文件: " + filePath, parameters);
            }
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            
            String fullContent = content.toString();
            String extracted = "";
            
            if (startMarker != null && endMarker != null) {
                int startIdx = fullContent.indexOf(startMarker);
                int endIdx = fullContent.indexOf(endMarker, startIdx + startMarker.length());
                if (startIdx >= 0 && endIdx >= 0) {
                    extracted = fullContent.substring(startIdx + startMarker.length(), endIdx).trim();
                }
            } else if (startMarker != null) {
                int startIdx = fullContent.indexOf(startMarker);
                if (startIdx >= 0) {
                    extracted = fullContent.substring(startIdx + startMarker.length()).trim();
                }
            } else if (endMarker != null) {
                int endIdx = fullContent.indexOf(endMarker);
                if (endIdx >= 0) {
                    extracted = fullContent.substring(0, endIdx).trim();
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("extracted", extracted);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("提取内容失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult searchText(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        // 参数兼容：Schema 声明的 keyword 与实现历史用的 pattern 都支持
        String pattern = (String) parameters.get("pattern");
        if (pattern == null) {
            pattern = (String) parameters.get("keyword");
        }
        boolean regex = false;
        Object regexObj = parameters.get("regex");
        if (regexObj instanceof Boolean) {
            regex = (Boolean) regexObj;
        } else if (regexObj != null) {
            regex = "true".equalsIgnoreCase(String.valueOf(regexObj));
        }
        String encoding = (String) parameters.get("encoding");
        
        if (filePath == null || pattern == null) {
            return new AIToolResult("缺少参数: file_path 或 pattern(keyword)", parameters);
        }
        
        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Object[] readerInfo = openTextReader(file, encoding);
            BufferedReader reader = (BufferedReader) readerInfo[0];
            if (reader == null) {
                return new AIToolResult("无法打开文件: " + filePath, parameters);
            }
            
            List<Map<String, Object>> matches = new ArrayList<>();
            String line;
            int lineNumber = 0;
            
            Pattern searchPattern = regex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
            
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = searchPattern.matcher(line);
                
                if (matcher.find()) {
                    Map<String, Object> match = new HashMap<>();
                    match.put("lineNumber", lineNumber);
                    match.put("line", line);
                    match.put("match", matcher.group());
                    match.put("startIndex", matcher.start());
                    match.put("endIndex", matcher.end());
                    matches.add(match);
                }
            }
            reader.close();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("matches", matches);
            result.put("matchCount", matches.size());
            result.put("pattern", pattern);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("搜索失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult extractEntities(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String customPattern = (String) parameters.get("entity_pattern");
        String encoding = (String) parameters.get("encoding");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Object[] readerInfo = openTextReader(file, encoding);
            BufferedReader reader = (BufferedReader) readerInfo[0];
            if (reader == null) {
                return new AIToolResult("无法打开文件: " + filePath, parameters);
            }
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            
            String text = content.toString();
            
            Map<String, Object> entities = new HashMap<>();
            entities.put("emails", extractPattern(text, EMAIL_PATTERN));
            entities.put("phones", extractPattern(text, PHONE_PATTERN));
            entities.put("urls", extractPattern(text, URL_PATTERN));
            entities.put("dates", extractPattern(text, DATE_PATTERN));
            entities.put("ips", extractPattern(text, IP_PATTERN));
            // 自定义正则：提供 entity_pattern 时用它提取任意模式
            if (customPattern != null && !customPattern.trim().isEmpty()) {
                try {
                    Pattern p = Pattern.compile(customPattern);
                    entities.put("custom", extractPattern(text, p));
                } catch (Exception e) {
                    return new AIToolResult("自定义正则无效: " + e.getMessage(), parameters);
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("entities", entities);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("提取实体失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult previewFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Integer maxLength = (Integer) parameters.get("maxLength");
        String encoding = (String) parameters.get("encoding");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (maxLength == null) maxLength = 1000;
        
        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Object[] readerInfo = openTextReader(file, encoding);
            BufferedReader reader = (BufferedReader) readerInfo[0];
            if (reader == null) {
                return new AIToolResult("无法打开文件: " + filePath, parameters);
            }
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[maxLength];
            int bytesRead = reader.read(buffer, 0, maxLength);
            
            if (bytesRead > 0) {
                content.append(buffer, 0, bytesRead);
            }
            reader.close();
            
            String preview = content.toString();
            boolean truncated = file.length() > maxLength;
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("preview", preview);
            result.put("truncated", truncated);
            result.put("totalSize", file.length());
            result.put("previewSize", preview.length());
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("预览文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private List<String> extractPattern(String text, Pattern pattern) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }

    // ==================== 结构化文件解析 ====================

    /** 自动识别文件格式并解析 */
    private AIToolResult parseStructuredAuto(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        if (filePath == null) return new AIToolResult("缺少参数: file_path", parameters);

        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);

        String ext = getExtension(file.getName()).toLowerCase();
        switch (ext) {
            case "xlsx": case "xls":
                return parseExcel(parameters);
            case "csv": case "tsv":
                return parseCsv(parameters);
            case "json":
                return parseJson(parameters);
            case "xml":
                return parseXml(parameters);
            default:
                // 尝试读取为文本
                return readFile(parameters);
        }
    }

    /** 解析 Excel 文件（使用 Apache POI） */
    private AIToolResult parseExcel(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Integer sheetIndex = (Integer) parameters.get("sheet_index");
        Integer maxRows = (Integer) parameters.get("max_rows");
        if (sheetIndex == null) sheetIndex = 0;
        if (maxRows == null) maxRows = 500;

        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);
        if (file.length() > MAX_READ_BYTES) {
            return new AIToolResult("文件过大（" + formatSize(file.length()) + " > 5MB），请先拆分或截取后再解析", parameters);
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis);
            org.apache.poi.ss.usermodel.FormulaEvaluator evaluator =
                    workbook.getCreationHelper().createFormulaEvaluator();

            // sheet 名称列表 + 行数清单（供 Agent 判断哪张表是真正的题库表：
            // 示例/说明/目录表通常只有几行，题库表有大量数据行）
            List<String> sheetNames = new ArrayList<>();
            List<Map<String, Object>> sheetSummaries = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                String name = workbook.getSheetName(i);
                sheetNames.add(name);
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("index", i);
                sm.put("name", name);
                sm.put("totalRows", workbook.getSheetAt(i).getPhysicalNumberOfRows());
                sheetSummaries.add(sm);
            }
            if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
                workbook.close();
                return new AIToolResult("sheet_index 越界（共 " + workbook.getNumberOfSheets()
                        + " 个 sheet: " + String.join(", ", sheetNames) + "）", parameters);
            }
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheetName", sheet.getSheetName());
            result.put("sheetIndex", sheetIndex);
            result.put("sheetCount", workbook.getNumberOfSheets());
            result.put("sheetNames", sheetNames);
            result.put("sheetSummaries", sheetSummaries);
            result.put("totalRows", sheet.getPhysicalNumberOfRows());
            result.put("mergedRegionCount", sheet.getNumMergedRegions());

            // 合并单元格映射：区域内所有格子 → 左上角值
            Map<String, String> mergedMap = buildMergedRegionMap(sheet, evaluator);

            // 提取表头（空表头/重复表头自动兜底为 col_N / col_N_2）
            List<String> headers = new ArrayList<>();
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
            int colCount = 0;
            java.util.Set<String> headerSeen = new java.util.HashSet<>();
            if (headerRow != null) {
                colCount = headerRow.getLastCellNum();
                for (int c = 0; c < colCount; c++) {
                    String h = getCellValueAsString(headerRow.getCell(c), evaluator);
                    if (h == null || h.trim().isEmpty()) {
                        h = "col_" + c;
                    } else {
                        h = h.trim();
                        // 去重：重复表头加后缀
                        String base = h;
                        int dup = 1;
                        while (headerSeen.contains(h)) {
                            h = base + "_" + (++dup);
                        }
                    }
                    headerSeen.add(h);
                    headers.add(h);
                }
            }
            if (headers.isEmpty()) {
                // 无表头：用 col_N 兜底
                colCount = Math.max(colCount, 1);
                for (int c = 0; c < colCount; c++) {
                    headers.add("col_" + c);
                }
            }
            result.put("headers", headers);
            result.put("columnCount", headers.size());

            // 提取数据行（合并单元格取左上角值，公式求值，缺列补空）
            List<Map<String, String>> rows = new ArrayList<>();
            int startRow = 1; // 跳过表头
            int endRow = Math.min(sheet.getPhysicalNumberOfRows(), maxRows + 1);
            for (int r = startRow; r < endRow; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> rowData = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    String key = headers.get(c);
                    // 合并区域优先
                    String mergedVal = mergedMap.get(r + ":" + c);
                    if (mergedVal != null) {
                        rowData.put(key, mergedVal);
                        continue;
                    }
                    if (c < row.getLastCellNum()) {
                        rowData.put(key, getCellValueAsString(row.getCell(c), evaluator));
                    } else {
                        rowData.put(key, ""); // 缺列补空
                    }
                }
                rows.add(rowData);
            }
            result.put("data", rows);
            result.put("returnedRows", rows.size());
            workbook.close();

            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "Excel parse error: " + e.getMessage(), e);
            return new AIToolResult("Excel解析失败: " + e.getMessage(), parameters);
        }
    }

    /** 构建合并单元格映射：合并区域内所有坐标 → 左上角单元格值 */
    private Map<String, String> buildMergedRegionMap(org.apache.poi.ss.usermodel.Sheet sheet,
                                                     org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        Map<String, String> merged = new HashMap<>();
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            org.apache.poi.ss.util.CellRangeAddress region = sheet.getMergedRegion(i);
            String value = "";
            org.apache.poi.ss.usermodel.Row topRow = sheet.getRow(region.getFirstRow());
            if (topRow != null) {
                org.apache.poi.ss.usermodel.Cell topLeft = topRow.getCell(region.getFirstColumn());
                value = getCellValueAsString(topLeft, evaluator);
            }
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    merged.put(r + ":" + c, value);
                }
            }
        }
        return merged;
    }

    /**
     * 单元格转字符串：
     * - 公式单元格经 FormulaEvaluator 求值（失败回退公式文本）
     * - 日期保留时间（yyyy-MM-dd HH:mm:ss）
     * - 数字用 BigDecimal 去科学计数法
     */
    private String getCellValueAsString(org.apache.poi.ss.usermodel.Cell cell,
                                        org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    java.util.Date d = cell.getDateCellValue();
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                    return sdf.format(d);
                }
                return formatNumeric(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                if (evaluator != null) {
                    try {
                        org.apache.poi.ss.usermodel.CellValue cv = evaluator.evaluate(cell);
                        switch (cv.getCellType()) {
                            case STRING: return cv.getStringValue();
                            case NUMERIC:
                                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                                    java.util.Date d = cell.getDateCellValue();
                                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                                    return sdf.format(d);
                                }
                                return formatNumeric(cv.getNumberValue());
                            case BOOLEAN: return String.valueOf(cv.getBooleanValue());
                            case BLANK: return "";
                            default: return "";
                        }
                    } catch (Exception e) {
                        return cell.getCellFormula();
                    }
                }
                return cell.getCellFormula();
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    /** 数字格式化：整数直出，小数去尾零、避免科学计数法 */
    private static String formatNumeric(double num) {
        if (num == Math.floor(num) && !Double.isInfinite(num) && Math.abs(num) < 1e15) {
            return String.valueOf((long) num);
        }
        java.math.BigDecimal bd = new java.math.BigDecimal(num);
        return bd.stripTrailingZeros().toPlainString();
    }

    /** 解析 CSV 文件（RFC 4180 严格解析：支持引号内逗号/换行/双引号转义） */
    private AIToolResult parseCsv(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String delimiter = (String) parameters.get("delimiter");
        Integer maxRows = (Integer) parameters.get("max_rows");
        String encoding = (String) parameters.get("encoding");
        if (delimiter == null || delimiter.isEmpty()) delimiter = ",";
        if (maxRows == null) maxRows = 500;

        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);
        if (file.length() > MAX_READ_BYTES) {
            return new AIToolResult("文件过大（" + formatSize(file.length()) + " > 5MB），请先拆分或截取后再解析", parameters);
        }

        try {
            Object[] readerInfo = openTextReader(file, encoding);
            BufferedReader reader = (BufferedReader) readerInfo[0];
            String actualEncoding = (String) readerInfo[1];
            if (reader == null) return new AIToolResult("无法打开文件: " + filePath, parameters);

            char delim = delimiter.charAt(0);
            if ("tab".equalsIgnoreCase(delimiter) || "\\t".equals(delimiter)) delim = '\t';

            List<List<String>> records = parseCsvRecords(reader, delim, maxRows);
            reader.close();

            List<String> headers = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();
            if (!records.isEmpty()) {
                for (String h : records.get(0)) {
                    headers.add(h.trim());
                }
                // 从第 2 行起为数据
                for (int i = 1; i < records.size(); i++) {
                    List<String> record = records.get(i);
                    Map<String, String> rowData = new LinkedHashMap<>();
                    for (int c = 0; c < headers.size(); c++) {
                        String val = c < record.size() ? record.get(c).trim() : "";
                        rowData.put(headers.get(c), val);
                    }
                    rows.add(rowData);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("headers", headers);
            result.put("columnCount", headers.size());
            result.put("data", rows);
            result.put("returnedRows", rows.size());
            result.put("encoding", actualEncoding);
            result.put("delimiter", String.valueOf(delim));
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("CSV解析失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * RFC 4180 CSV 记录解析（状态机）：
     * - 支持引号内逗号/换行/CRLF
     * - 双引号转义（"" → "）
     * - 未闭合引号的字段跨行拼接
     */
    private List<List<String>> parseCsvRecords(Reader reader, char delimiter, int maxRecords) throws java.io.IOException {
        List<List<String>> records = new ArrayList<>();
        PushbackReader in = new PushbackReader(reader);
        List<String> currentRecord = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean recordHasContent = false;

        int ch;
        while ((ch = in.read()) != -1) {
            char c = (char) ch;
            if (inQuotes) {
                if (c == '"') {
                    int next = in.read();
                    if (next == -1) {
                        // 引号在文件末尾闭合
                        inQuotes = false;
                        break;
                    }
                    if ((char) next == '"') {
                        field.append('"'); // "" → "
                    } else {
                        inQuotes = false;
                        in.unread(next);    // 放回后续字符
                    }
                } else {
                    field.append(c);       // 引号内换行/逗号都是数据
                }
            } else {
                if (c == '"' && field.length() == 0) {
                    inQuotes = true;
                    recordHasContent = true;
                } else if (c == delimiter) {
                    currentRecord.add(field.toString());
                    field.setLength(0);
                    recordHasContent = true;
                } else if (c == '\n') {
                    currentRecord.add(field.toString());
                    field.setLength(0);
                    records.add(currentRecord);
                    currentRecord = new ArrayList<>();
                    recordHasContent = false;
                    if (records.size() >= maxRecords) return records;
                } else if (c == '\r') {
                    // 忽略 CR：Windows CRLF 行尾的 \r（引号外）
                } else {
                    field.append(c);
                    recordHasContent = true;
                }
            }
        }
        // 末尾无换行：补最后一条记录
        if (inQuotes || field.length() > 0 || !currentRecord.isEmpty() || recordHasContent) {
            currentRecord.add(field.toString());
            records.add(currentRecord);
        }
        return records;
    }

    /** 解析 JSON 文件 */
    private AIToolResult parseJson(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String jsonPath = (String) parameters.get("json_path"); // 简单路径如 "data.items"

        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);
        if (file.length() > MAX_READ_BYTES) {
            return new AIToolResult("文件过大（" + formatSize(file.length()) + " > 5MB），请使用 json_path 缩小范围或先拆分", parameters);
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);

            // 如果指定了 json_path，导航到子节点
            if (jsonPath != null && !jsonPath.isEmpty()) {
                for (String segment : jsonPath.split("\\.")) {
                    if (root.isJsonObject()) {
                        root = root.getAsJsonObject().get(segment);
                    } else if (root.isJsonArray()) {
                        int idx = Integer.parseInt(segment);
                        root = root.getAsJsonArray().get(idx);
                    }
                    if (root == null) break;
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());

            if (root == null) {
                result.put("data", null);
                result.put("type", "null");
            } else if (root.isJsonArray()) {
                JsonArray arr = root.getAsJsonArray();
                List<Object> list = new ArrayList<>();
                Gson gson = new Gson();
                for (JsonElement el : arr) {
                    list.add(gson.fromJson(el, Object.class));
                }
                result.put("data", list);
                result.put("type", "array");
                result.put("length", arr.size());
            } else if (root.isJsonObject()) {
                Gson gson = new Gson();
                result.put("data", gson.fromJson(root, Object.class));
                result.put("type", "object");
                result.put("keys", new ArrayList<>(root.getAsJsonObject().keySet()));
            } else {
                result.put("data", root.getAsString());
                result.put("type", "primitive");
            }
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("JSON解析失败: " + e.getMessage(), parameters);
        }
    }

    /** 解析 XML 文件（提取标签内容） */
    private AIToolResult parseXml(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String targetTag = (String) parameters.get("target_tag"); // 可选：只提取特定标签
        Integer maxItems = (Integer) parameters.get("max_items");
        if (maxItems == null) maxItems = 200;

        File file = resolveInputFile(parameters, filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);
        if (file.length() > MAX_READ_BYTES) {
            return new AIToolResult("文件过大（" + formatSize(file.length()) + " > 5MB），请先拆分或截取后再解析", parameters);
        }

        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(file);
            doc.getDocumentElement().normalize();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("rootElement", doc.getDocumentElement().getTagName());

            if (targetTag != null && !targetTag.isEmpty()) {
                org.w3c.dom.NodeList nodes = doc.getElementsByTagName(targetTag);
                List<Map<String, String>> items = new ArrayList<>();
                for (int i = 0; i < Math.min(nodes.getLength(), maxItems); i++) {
                    org.w3c.dom.Node node = nodes.item(i);
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("text", node.getTextContent().trim());
                    if (node.getAttributes() != null) {
                        for (int a = 0; a < node.getAttributes().getLength(); a++) {
                            org.w3c.dom.Node attr = node.getAttributes().item(a);
                            item.put("@" + attr.getNodeName(), attr.getNodeValue());
                        }
                    }
                    items.add(item);
                }
                result.put("data", items);
                result.put("matchedCount", nodes.getLength());
                result.put("returnedCount", items.size());
            } else {
                // 返回顶层结构概览
                org.w3c.dom.Element root = doc.getDocumentElement();
                Map<String, Integer> tagSummary = new LinkedHashMap<>();
                summarizeTags(root, tagSummary, 0, 2);
                result.put("tagSummary", tagSummary);
                result.put("totalChildElements", root.getChildNodes().getLength());
                // 返回前 N 个子元素的文本
                List<Map<String, String>> children = new ArrayList<>();
                org.w3c.dom.NodeList children_nodes = root.getChildNodes();
                for (int i = 0; i < Math.min(children_nodes.getLength(), maxItems); i++) {
                    org.w3c.dom.Node child = children_nodes.item(i);
                    if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("tag", child.getNodeName());
                        item.put("text", child.getTextContent().trim());
                        children.add(item);
                    }
                }
                result.put("data", children);
            }
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("XML解析失败: " + e.getMessage(), parameters);
        }
    }

    private void summarizeTags(org.w3c.dom.Element element, Map<String, Integer> summary, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        String tag = element.getTagName();
        summary.put(tag, summary.getOrDefault(tag, 0) + 1);
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                summarizeTags((org.w3c.dom.Element) child, summary, depth + 1, maxDepth);
            }
        }
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 && dot < fileName.length() - 1) ? fileName.substring(dot + 1) : "";
    }

    private File resolveFile(String filePath) {
        File file = new File(filePath);
        if (file.exists()) return file;
        File appFile = new File(context.getFilesDir(), filePath);
        if (appFile.exists()) return appFile;
        return null;
    }

    /** 文件不存在时的提示（列出应用目录中可用文件，便于 Agent 修正路径） */
    private AIToolResult fileNotFoundResult(String filePath, Map<String, Object> parameters) {
        File[] files = context.getFilesDir().listFiles();
        StringBuilder hint = new StringBuilder("文件不存在: " + filePath);
        if (files != null && files.length > 0) {
            hint.append("\n应用目录中可用的文件:");
            for (File f : files) {
                hint.append("\n  - ").append(f.getName());
            }
        } else {
            hint.append("\n应用目录为空，可先用 file_generator 工具创建文件");
        }
        return new AIToolResult(hint.toString(), parameters);
    }

    /**
     * 解析输入文件：支持绝对路径 / 应用目录相对路径 / content:// URI。
     * URI 来源：参数 file_uri，或 file_path 以 content:// 开头（文件选择器/分享的 Uri）。
     * URI 文件会被复制到缓存临时文件后按普通文件处理（所有操作统一走 File）。
     */
    private File resolveInputFile(Map<String, Object> parameters, String filePath) {
        String uriStr = parameters != null ? (String) parameters.get("file_uri") : null;
        if ((uriStr == null || uriStr.isEmpty()) && filePath != null && filePath.startsWith("content://")) {
            uriStr = filePath;
        }
        if (uriStr != null && !uriStr.isEmpty()) {
            try {
                Uri uri = Uri.parse(uriStr);
                String name = "agent_uri_" + System.currentTimeMillis() + "_"
                        + (filePath != null ? new File(filePath).getName() : "file.bin");
                File cache = new File(context.getCacheDir(), name);
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
                AILogger.w(TAG, "content:// URI 读取失败: " + uriStr + " - " + e.getMessage());
                return null;
            }
        }
        return resolveFile(filePath);
    }

    /**
     * 打开文本读取器：encoding 为空时自动检测编码（UTF-8 BOM/UTF-16/GB18030/GBK，含乱码二次校验），
     * 指定 encoding 时按指定编码打开（非法编码回退 UTF-8）。
     *
     * @return [BufferedReader, 实际编码名]；打开失败返回 [null, "UTF-8"]
     */
    private Object[] openTextReader(File file, String encoding) {
        if (encoding == null || encoding.trim().isEmpty()) {
            try {
                Object[] auto = CharsetDetector.openBufferedReaderAutoDetect(file);
                return new Object[]{auto[0], String.valueOf(auto[1])};
            } catch (Exception e) {
                return new Object[]{null, "UTF-8"};
            }
        }
        try {
            Charset charset = Charset.forName(encoding.trim());
            return new Object[]{new BufferedReader(new InputStreamReader(new FileInputStream(file), charset)), encoding};
        } catch (Exception e) {
            try {
                return new Object[]{new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)), "UTF-8"};
            } catch (Exception e2) {
                return new Object[]{null, "UTF-8"};
            }
        }
    }

    /** 列出目录中的文件（带 file_list 富 UI 卡片） */
    private AIToolResult listFiles(Map<String, Object> parameters) {
        String directoryPath = (String) parameters.get("directory_path");
        // 未指定目录时默认列出应用文件目录
        if (directoryPath == null || directoryPath.trim().isEmpty() || directoryPath.equals("/")) {
            directoryPath = context.getFilesDir().getAbsolutePath();
        }

        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            // 回退到应用文件目录
            directory = context.getFilesDir();
            directoryPath = directory.getAbsolutePath();
        }

        File[] files = directory.listFiles();
        Map<String, Object> result = new HashMap<>();
        if (files != null) {
            List<Map<String, Object>> fileList = new ArrayList<>();
            for (File file : files) {
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("name", file.getName());
                fileInfo.put("path", file.getAbsolutePath());
                fileInfo.put("is_file", file.isFile());
                fileInfo.put("is_directory", file.isDirectory());
                if (file.isFile()) {
                    fileInfo.put("size", file.length());
                }
                fileList.add(fileInfo);
            }
            result.put("files", fileList);
            result.put("count", fileList.size());
        } else {
            result.put("files", new ArrayList<>());
            result.put("count", 0);
        }
        result.put("directory", directoryPath);

        AIToolResult toolResult = new AIToolResult(result, parameters);
        // 附加文件列表组件（富 UI 展示目录内容）
        try {
            org.json.JSONArray items = new org.json.JSONArray();
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) result.get("files");
            if (list != null) {
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> f = (Map<?, ?>) o;
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("name", String.valueOf(f.get("name")));
                    item.put("path", String.valueOf(f.get("path")));
                    boolean isDir = Boolean.TRUE.equals(f.get("is_directory"));
                    item.put("type", isDir ? "dir" : "file");
                    if (f.get("size") instanceof Number) {
                        long bytes = ((Number) f.get("size")).longValue();
                        item.put("size", formatSize(bytes));
                    }
                    items.put(item);
                }
            }
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("title", "目录内容");
            props.put("path", directoryPath);
            props.put("files", items);
            toolResult.withComponent(com.oilquiz.app.ai.chat.component.ComponentData.of("file_list", props));
        } catch (Exception ignore) {
        }
        return toolResult;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: read(默认), read_lines, extract_text, search_text, extract_entities, preview, parse_structured, parse_excel, parse_csv, parse_json, parse_xml, list");
        descriptions.put("file_path", "文件路径（必填，除list外；也支持 content:// 开头 URI）");
        descriptions.put("file_uri", "content:// URI（文件选择器/分享的Uri，与file_path二选一）");
        descriptions.put("directory_path", "目录路径（list用，留空默认应用目录）");
        descriptions.put("encoding", "文件编码（留空自动检测 UTF-8/UTF-16/GB18030/GBK；也可指定如 GBK）");
        descriptions.put("startLine", "起始行号（用于read_lines操作）");
        descriptions.put("endLine", "结束行号（用于read_lines操作）");
        descriptions.put("startMarker", "起始标记（用于extract_text操作）");
        descriptions.put("endMarker", "结束标记（用于extract_text操作）");
        descriptions.put("pattern", "搜索关键词（用于search_text操作，等价参数keyword）");
        descriptions.put("keyword", "搜索关键词（search_text的等价参数）");
        descriptions.put("regex", "是否正则表达式（用于search_text操作，默认false）");
        descriptions.put("entity_pattern", "自定义正则表达式（用于extract_entities操作，可选，提取任意模式）");
        descriptions.put("maxLength", "最大预览长度（用于preview操作，默认1000）");
        descriptions.put("delimiter", "CSV分隔符（parse_csv用，默认逗号，支持tab）");
        descriptions.put("max_rows", "最大解析行数（parse_excel/parse_csv用，默认500）");
        descriptions.put("sheet_index", "工作表索引（parse_excel用，默认0）");
        descriptions.put("json_path", "JSON子节点路径（parse_json用，如 data.items）");
        descriptions.put("target_tag", "目标标签（parse_xml用，可选，只提取该标签）");
        descriptions.put("max_items", "最大条目数（parse_xml用，默认200）");
        return descriptions;
    }
}