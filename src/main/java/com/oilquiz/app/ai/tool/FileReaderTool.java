package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
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
    description = "文件阅读工具，支持读取文本文件、解析结构化内容、提取关键信息",
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
        @Action(name = "parse_xml", description = "解析XML文件，提取标签内容")
    },
    params = {
        @Param(name = "file_path", type = "string", description = "文件路径", required = true),
        @Param(name = "encoding", type = "string", description = "文件编码(默认UTF-8)", required = false),
        @Param(name = "action", type = "string", description = "操作类型", required = true)
    }
)
public class FileReaderTool implements AITool {
    private static final String TAG = "FileReaderTool";
    private final Context context;
    
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
        return "文件阅读工具，支持读取文本文件、解析结构化内容、提取关键信息";
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

        if (encoding == null) {
            encoding = "UTF-8";
        }

        File file = new File(filePath);
        if (!file.exists()) {
            // 尝试在应用文件目录中查找
            File appFile = new File(context.getFilesDir(), filePath);
            if (appFile.exists()) {
                file = appFile;
            } else {
                // 列出应用目录中可用的文件
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
        }
        if (!file.isFile()) {
            return new AIToolResult("不是文件: " + filePath, parameters);
        }
        
        try {
            Charset charset = getCharset(encoding);
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset));
            
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
            result.put("encoding", encoding);
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
        if (encoding == null) encoding = "UTF-8";
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Charset charset = getCharset(encoding);
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset));
            
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
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("读取文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult extractText(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String startMarker = (String) parameters.get("startMarker");
        String endMarker = (String) parameters.get("endMarker");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
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
        String pattern = (String) parameters.get("pattern");
        Boolean regex = (Boolean) parameters.get("regex");
        
        if (filePath == null || pattern == null) {
            return new AIToolResult("缺少参数: file_path 或 pattern", parameters);
        }
        
        if (regex == null) regex = false;
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            
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
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
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
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (maxLength == null) maxLength = 1000;
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
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

        File file = resolveFile(filePath);
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

        File file = resolveFile(filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);

        try (FileInputStream fis = new FileInputStream(file)) {
            org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis);
            if (sheetIndex >= workbook.getNumberOfSheets()) {
                sheetIndex = 0;
            }
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("sheetName", sheet.getSheetName());
            result.put("sheetCount", workbook.getNumberOfSheets());
            result.put("totalRows", sheet.getPhysicalNumberOfRows());

            // 提取表头
            List<String> headers = new ArrayList<>();
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
            int colCount = 0;
            if (headerRow != null) {
                colCount = headerRow.getLastCellNum();
                for (int c = 0; c < colCount; c++) {
                    headers.add(getCellValueAsString(headerRow.getCell(c)));
                }
            }
            result.put("headers", headers);
            result.put("columnCount", colCount);

            // 提取数据行
            List<Map<String, String>> rows = new ArrayList<>();
            int startRow = 1; // 跳过表头
            int endRow = Math.min(sheet.getPhysicalNumberOfRows(), maxRows + 1);
            for (int r = startRow; r < endRow; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> rowData = new LinkedHashMap<>();
                // 容错：如果列数少于表头，用空字符串填充
                int actualCols = Math.max(colCount, headers.size());
                for (int c = 0; c < actualCols; c++) {
                    String key = c < headers.size() ? headers.get(c) : ("col_" + c);
                    // 容错：行中的列数可能少于表头
                    if (c < row.getLastCellNum()) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                        rowData.put(key, getCellValueAsString(cell));
                    } else {
                        // 缺少该列，填空字符串
                        rowData.put(key, "");
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

    private String getCellValueAsString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC:
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double num = cell.getNumericCellValue();
                if (num == Math.floor(num) && !Double.isInfinite(num)) {
                    return String.valueOf((long) num);
                }
                return String.valueOf(num);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA: return cell.getCellFormula();
            case BLANK: return "";
            default: return "";
        }
    }

    /** 解析 CSV 文件 */
    private AIToolResult parseCsv(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String delimiter = (String) parameters.get("delimiter");
        Integer maxRows = (Integer) parameters.get("max_rows");
        if (delimiter == null || delimiter.isEmpty()) delimiter = ",";
        if (maxRows == null) maxRows = 500;

        File file = resolveFile(filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            List<String> headers = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            int lineNum = 0;

            while ((line = reader.readLine()) != null && lineNum < maxRows + 1) {
                String[] parts = line.split(Pattern.quote(delimiter), -1);
                if (lineNum == 0) {
                    for (String p : parts) headers.add(p.trim().replaceAll("^\"|\"$", ""));
                } else {
                    Map<String, String> rowData = new LinkedHashMap<>();
                    // 容错：行的列数可能少于表头，用空字符串填充
                    for (int i = 0; i < headers.size(); i++) {
                        String val = i < parts.length ? parts[i].trim().replaceAll("^\"|\"$", "") : "";
                        rowData.put(headers.get(i), val);
                    }
                    rows.add(rowData);
                }
                lineNum++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("fileName", file.getName());
            result.put("headers", headers);
            result.put("columnCount", headers.size());
            result.put("data", rows);
            result.put("returnedRows", rows.size());
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("CSV解析失败: " + e.getMessage(), parameters);
        }
    }

    /** 解析 JSON 文件 */
    private AIToolResult parseJson(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String jsonPath = (String) parameters.get("json_path"); // 简单路径如 "data.items"

        File file = resolveFile(filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);

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

        File file = resolveFile(filePath);
        if (file == null || !file.exists()) return new AIToolResult("文件不存在: " + filePath, parameters);

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
    
    private Charset getCharset(String encoding) {
        try {
            return Charset.forName(encoding);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: read, read_lines, extract_text, search_text, extract_entities, preview");
        descriptions.put("file_path", "文件路径（必填）");
        descriptions.put("encoding", "文件编码（默认UTF-8）");
        descriptions.put("startLine", "起始行号（用于read_lines操作）");
        descriptions.put("endLine", "结束行号（用于read_lines操作）");
        descriptions.put("startMarker", "起始标记（用于extract_text操作）");
        descriptions.put("endMarker", "结束标记（用于extract_text操作）");
        descriptions.put("pattern", "搜索模式（用于search_text操作）");
        descriptions.put("regex", "是否正则表达式（用于search_text操作，默认false）");
        descriptions.put("maxLength", "最大预览长度（用于preview操作，默认1000）");
        return descriptions;
    }
}