package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件阅读工具，提供高级文件读取和内容解析功能
 */
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
            return new AIToolResult("文件不存在: " + filePath, parameters);
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