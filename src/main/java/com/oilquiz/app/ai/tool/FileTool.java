package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件工具，用于文件操作
 */
public class FileTool implements AITool {
    private static final String TAG = "FileTool";
    private final Context context;
    
    public FileTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "file";
    }
    
    @Override
    public String getDescription() {
        return "文件操作工具，用于获取文件信息、读取文件内容等";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                return new AIToolResult("Missing required parameter: action", parameters);
            }
            
            switch (action) {
                case "get_file_info":
                    return getFileInfo(parameters);
                case "read_file":
                    return readFile(parameters);
                case "list_files":
                    return listFiles(parameters);
                default:
                    return new AIToolResult("Unknown action: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing file tool: " + e.getMessage(), e);
            return new AIToolResult("Error: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getFileInfo(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        if (filePath == null) {
            return new AIToolResult("Missing required parameter: file_path", parameters);
        }
        
        File file = new File(filePath);
        Map<String, Object> result = new HashMap<>();
        result.put("exists", file.exists());
        result.put("is_file", file.isFile());
        result.put("is_directory", file.isDirectory());
        if (file.exists()) {
            result.put("size", file.length());
            result.put("last_modified", file.lastModified());
        }
        
        return new AIToolResult(result, parameters);
    }
    
    private AIToolResult readFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        if (filePath == null) {
            return new AIToolResult("Missing required parameter: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            return new AIToolResult("File does not exist or is not a file", parameters);
        }
        
        try {
            java.util.Scanner scanner = new java.util.Scanner(file);
            String content = scanner.useDelimiter("\\Z").next();
            scanner.close();
            
            Map<String, Object> result = new HashMap<>();
            result.put("content", content);
            result.put("length", content.length());
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("Error reading file: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult listFiles(Map<String, Object> parameters) {
        String directoryPath = (String) parameters.get("directory_path");
        if (directoryPath == null) {
            return new AIToolResult("Missing required parameter: directory_path", parameters);
        }
        
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            return new AIToolResult("Directory does not exist or is not a directory", parameters);
        }
        
        File[] files = directory.listFiles();
        Map<String, Object> result = new HashMap<>();
        if (files != null) {
            java.util.List<Map<String, Object>> fileList = new java.util.ArrayList<>();
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
            result.put("files", new java.util.ArrayList<>());
            result.put("count", 0);
        }
        
        return new AIToolResult(result, parameters);
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: get_file_info, read_file, list_files");
        descriptions.put("file_path", "文件路径（用于get_file_info和read_file操作）");
        descriptions.put("directory_path", "目录路径（用于list_files操作）");
        return descriptions;
    }
}