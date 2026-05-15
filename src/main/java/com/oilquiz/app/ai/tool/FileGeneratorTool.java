package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件生成工具，提供文件创建和内容生成功能
 */
public class FileGeneratorTool implements AITool {
    private static final String TAG = "FileGeneratorTool";
    private final Context context;
    
    public FileGeneratorTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "file_generator";
    }
    
    @Override
    public String getDescription() {
        return "文件生成工具，支持创建文本文件、JSON文件、配置文件等";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                action = "create";
            }
            
            switch (action) {
                case "create":
                    return createFile(parameters);
                case "append":
                    return appendToFile(parameters);
                case "write_json":
                    return writeJson(parameters);
                case "create_config":
                    return createConfig(parameters);
                case "create_markdown":
                    return createMarkdown(parameters);
                case "create_template":
                    return createTemplate(parameters);
                case "generate_report":
                    return generateReport(parameters);
                case "delete_file":
                    return deleteFile(parameters);
                case "copy_file":
                    return copyFile(parameters);
                default:
                    return createFile(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing file generator: " + e.getMessage(), e);
            return new AIToolResult("文件生成失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult createFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String content = (String) parameters.get("content");
        String encoding = (String) parameters.get("encoding");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (content == null) {
            content = "";
        }
        
        if (encoding == null) {
            encoding = "UTF-8";
        }
        
        try {
            File file = new File(filePath);
            
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(content);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "文件创建成功");
            result.put("filePath", filePath);
            result.put("size", file.length());
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("创建文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult appendToFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String content = (String) parameters.get("content");
        Boolean newLine = (Boolean) parameters.get("newLine");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (content == null) {
            content = "";
        }
        
        if (newLine == null) {
            newLine = true;
        }
        
        try {
            File file = new File(filePath);
            
            if (!file.exists()) {
                return new AIToolResult("文件不存在: " + filePath, parameters);
            }
            
            String appendContent = newLine ? "\n" + content : content;
            
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(appendContent);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "内容追加成功");
            result.put("filePath", filePath);
            result.put("newSize", file.length());
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("追加内容失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult writeJson(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Map<String, Object> data = (Map<String, Object>) parameters.get("data");
        Boolean pretty = (Boolean) parameters.get("pretty");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (data == null) {
            return new AIToolResult("缺少参数: data", parameters);
        }
        
        if (pretty == null) {
            pretty = true;
        }
        
        try {
            String jsonContent = convertToJson(data, pretty);
            
            return createFile(Map.of(
                "file_path", filePath,
                "content", jsonContent,
                "encoding", "UTF-8"
            ));
        } catch (Exception e) {
            return new AIToolResult("写入JSON失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult createConfig(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Map<String, String> config = (Map<String, String>) parameters.get("config");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (config == null) {
            config = new HashMap<>();
        }
        
        StringBuilder content = new StringBuilder();
        content.append("# Configuration File\n");
        content.append("# Generated at: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
        
        for (Map.Entry<String, String> entry : config.entrySet()) {
            content.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", content.toString(),
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult createMarkdown(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String title = (String) parameters.get("title");
        String content = (String) parameters.get("content");
        List<String> sections = (List<String>) parameters.get("sections");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        StringBuilder mdContent = new StringBuilder();
        
        if (title != null) {
            mdContent.append("# ").append(title).append("\n\n");
        }
        
        mdContent.append("> Generated at: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
        
        if (sections != null && !sections.isEmpty()) {
            for (int i = 0; i < sections.size(); i++) {
                mdContent.append("## ").append(i + 1).append(". ").append(sections.get(i)).append("\n\n");
            }
        } else if (content != null) {
            mdContent.append(content).append("\n");
        }
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", mdContent.toString(),
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult createTemplate(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String templateType = (String) parameters.get("type");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (templateType == null) {
            templateType = "default";
        }
        
        String content = generateTemplate(templateType);
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", content,
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult generateReport(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String title = (String) parameters.get("title");
        Map<String, Object> data = (Map<String, Object>) parameters.get("data");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        StringBuilder report = new StringBuilder();
        
        report.append("# ").append(title != null ? title : "报告").append("\n\n");
        report.append("**生成时间**: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
        
        if (data != null) {
            report.append("## 内容摘要\n\n");
            
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                report.append("### ").append(entry.getKey()).append("\n\n");
                
                Object value = entry.getValue();
                if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    for (int i = 0; i < list.size(); i++) {
                        report.append(i + 1).append(". ").append(list.get(i)).append("\n");
                    }
                } else {
                    report.append(value.toString()).append("\n");
                }
                report.append("\n");
            }
        }
        
        report.append("---\n");
        report.append("*报告结束*\n");
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", report.toString(),
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult deleteFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            boolean deleted = file.delete();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", deleted ? "success" : "failed");
            result.put("message", deleted ? "文件删除成功" : "文件删除失败");
            result.put("filePath", filePath);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("删除文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult copyFile(Map<String, Object> parameters) {
        String sourcePath = (String) parameters.get("source");
        String destinationPath = (String) parameters.get("destination");
        
        if (sourcePath == null) {
            return new AIToolResult("缺少参数: source", parameters);
        }
        
        if (destinationPath == null) {
            return new AIToolResult("缺少参数: destination", parameters);
        }
        
        File source = new File(sourcePath);
        File destination = new File(destinationPath);
        
        if (!source.exists()) {
            return new AIToolResult("源文件不存在: " + sourcePath, parameters);
        }
        
        try {
            File parentDir = destination.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            java.nio.file.Files.copy(source.toPath(), destination.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "文件复制成功");
            result.put("source", sourcePath);
            result.put("destination", destinationPath);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("复制文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private String convertToJson(Map<String, Object> data, boolean pretty) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        
        if (pretty) {
            json.append("\n");
        }
        
        int index = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (index > 0) {
                json.append(",");
            }
            
            if (pretty) {
                json.append("\n  ");
            }
            
            json.append("\"").append(entry.getKey()).append("\": ");
            
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(escapeString((String) value)).append("\"");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value.toString());
            } else if (value instanceof List) {
                json.append(convertListToJson((List<?>) value, pretty));
            } else if (value instanceof Map) {
                json.append(convertToJson((Map<String, Object>) value, pretty));
            } else {
                json.append("null");
            }
            
            index++;
        }
        
        if (pretty) {
            json.append("\n");
        }
        
        json.append("}");
        return json.toString();
    }
    
    private String convertListToJson(List<?> list, boolean pretty) {
        StringBuilder json = new StringBuilder();
        json.append("[");
        
        if (pretty) {
            json.append("\n");
        }
        
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            
            if (pretty) {
                json.append("\n  ");
            }
            
            Object value = list.get(i);
            if (value instanceof String) {
                json.append("\"").append(escapeString((String) value)).append("\"");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value.toString());
            } else {
                json.append("null");
            }
        }
        
        if (pretty) {
            json.append("\n");
        }
        
        json.append("]");
        return json.toString();
    }
    
    private String escapeString(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    private String generateTemplate(String type) {
        switch (type.toLowerCase()) {
            case "java":
                return "public class Template {\n    public static void main(String[] args) {\n        System.out.println(\"Hello World\");\n    }\n}\n";
            case "python":
                return "#!/usr/bin/env python3\n\"\"\"Template script\"\"\"\n\ndef main():\n    print(\"Hello World\")\n\nif __name__ == \"__main__\":\n    main()\n";
            case "markdown":
                return "# Document Title\n\n## Section 1\n\nContent here...\n\n## Section 2\n\nMore content...\n";
            case "json":
                return "{\n  \"key\": \"value\",\n  \"number\": 123,\n  \"list\": [1, 2, 3]\n}\n";
            case "xml":
                return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n    <item>value</item>\n</root>\n";
            case "html":
                return "<!DOCTYPE html>\n<html>\n<head>\n    <title>Document</title>\n</head>\n<body>\n    <h1>Hello World</h1>\n</body>\n</html>\n";
            case "csv":
                return "Name,Age,Email\nJohn Doe,30,john@example.com\nJane Smith,25,jane@example.com\n";
            default:
                return "# Template File\n# Generated at: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n\n";
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: create, append, write_json, create_config, create_markdown, create_template, generate_report, delete_file, copy_file");
        descriptions.put("file_path", "文件路径（必填）");
        descriptions.put("content", "文件内容");
        descriptions.put("encoding", "文件编码（默认UTF-8）");
        descriptions.put("data", "JSON数据（用于write_json和generate_report操作）");
        descriptions.put("pretty", "是否格式化输出（用于write_json操作，默认true）");
        descriptions.put("config", "配置键值对（用于create_config操作）");
        descriptions.put("title", "标题（用于create_markdown和generate_report操作）");
        descriptions.put("sections", "章节列表（用于create_markdown操作）");
        descriptions.put("type", "模板类型（用于create_template操作：java, python, markdown, json, xml, html, csv）");
        descriptions.put("newLine", "追加时是否换行（用于append操作，默认true）");
        descriptions.put("source", "源文件路径（用于copy_file操作）");
        descriptions.put("destination", "目标文件路径（用于copy_file操作）");
        return descriptions;
    }
}