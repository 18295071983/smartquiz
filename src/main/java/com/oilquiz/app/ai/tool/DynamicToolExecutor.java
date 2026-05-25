package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DynamicToolExecutor {
    private static final String TAG = "DynamicToolExecutor";
    
    private final Context context;
    private final Map<String, Object> variables = new HashMap<>();
    
    public DynamicToolExecutor(Context context) {
        this.context = context;
    }
    
    public String execute(String logic, Map<String, Object> parameters) {
        if (logic == null || logic.trim().isEmpty()) {
            return "未提供执行逻辑";
        }
        
        StringBuilder result = new StringBuilder();
        String[] lines = logic.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            String lineResult = executeLine(line, parameters);
            if (lineResult != null && !lineResult.isEmpty()) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(lineResult);
            }
        }
        
        return result.length() > 0 ? result.toString() : "执行完成";
    }
    
    private String executeLine(String line, Map<String, Object> parameters) {
        line = substituteVariables(line, parameters);
        
        if (line.startsWith("echo ")) {
            return line.substring(5);
        }
        
        if (line.startsWith("print ")) {
            return line.substring(6);
        }
        
        if (line.startsWith("log ")) {
            Log.i(TAG, line.substring(4));
            return null;
        }
        
        if (line.startsWith("set ")) {
            return handleSet(line);
        }
        
        if (line.startsWith("if ")) {
            return handleIf(line, parameters);
        }
        
        if (line.startsWith("concat ")) {
            return handleConcat(line);
        }
        
        if (line.startsWith("length ")) {
            return handleLength(line);
        }
        
        if (line.startsWith("upper ")) {
            return line.substring(6).toUpperCase();
        }
        
        if (line.startsWith("lower ")) {
            return line.substring(6).toLowerCase();
        }
        
        if (line.startsWith("trim ")) {
            return line.substring(5).trim();
        }
        
        if (line.startsWith("replace ")) {
            return handleReplace(line);
        }
        
        if (line.startsWith("split ")) {
            return handleSplit(line);
        }
        
        if (line.startsWith("join ")) {
            return handleJoin(line);
        }
        
        if (line.startsWith("call_tool ")) {
            return handleCallTool(line, parameters);
        }
        
        return line;
    }
    
    private String substituteVariables(String line, Map<String, Object> parameters) {
        String result = line;
        
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(line);
        
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = null;
            
            if (parameters != null && parameters.containsKey(varName)) {
                value = parameters.get(varName);
            } else if (variables.containsKey(varName)) {
                value = variables.get(varName);
            }
            
            matcher.appendReplacement(sb, value != null ? String.valueOf(value) : "");
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    private String handleSet(String line) {
        try {
            String content = line.substring(4);
            int equalsIndex = content.indexOf('=');
            if (equalsIndex > 0) {
                String varName = content.substring(0, equalsIndex).trim();
                String varValue = content.substring(equalsIndex + 1).trim();
                variables.put(varName, varValue);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in set command: " + e.getMessage());
        }
        return "语法错误: set 变量名=值";
    }
    
    private String handleIf(String line, Map<String, Object> parameters) {
        try {
            String condition = line.substring(3).trim();
            Pattern ifPattern = Pattern.compile("\\(([^)]+)\\)\\s*then\\s+(.+)");
            Matcher matcher = ifPattern.matcher(condition);
            
            if (matcher.matches()) {
                String cond = matcher.group(1).trim();
                String thenPart = matcher.group(2).trim();
                
                if (evaluateCondition(cond, parameters)) {
                    return executeLine(thenPart, parameters);
                }
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in if command: " + e.getMessage());
        }
        return "语法错误: if (条件) then 命令";
    }
    
    private boolean evaluateCondition(String condition, Map<String, Object> parameters) {
        try {
            condition = substituteVariables(condition, parameters);
            
            if (condition.contains("==")) {
                String[] parts = condition.split("==", 2);
                return parts[0].trim().equals(parts[1].trim());
            }
            
            if (condition.contains("!=")) {
                String[] parts = condition.split("!=", 2);
                return !parts[0].trim().equals(parts[1].trim());
            }
            
            if (condition.contains(">")) {
                String[] parts = condition.split(">", 2);
                try {
                    double left = Double.parseDouble(parts[0].trim());
                    double right = Double.parseDouble(parts[1].trim());
                    return left > right;
                } catch (Exception e) {
                    return false;
                }
            }
            
            if (condition.contains("<")) {
                String[] parts = condition.split("<", 2);
                try {
                    double left = Double.parseDouble(parts[0].trim());
                    double right = Double.parseDouble(parts[1].trim());
                    return left < right;
                } catch (Exception e) {
                    return false;
                }
            }
            
            return Boolean.parseBoolean(condition);
        } catch (Exception e) {
            Log.e(TAG, "Error evaluating condition: " + e.getMessage());
            return false;
        }
    }
    
    private String handleConcat(String line) {
        try {
            String content = line.substring(7);
            String[] parts = content.split(",");
            StringBuilder result = new StringBuilder();
            for (String part : parts) {
                result.append(part.trim());
            }
            return result.toString();
        } catch (Exception e) {
            return "语法错误: concat 字符串1,字符串2,...";
        }
    }
    
    private String handleLength(String line) {
        try {
            String content = line.substring(7).trim();
            return String.valueOf(content.length());
        } catch (Exception e) {
            return "语法错误: length 字符串";
        }
    }
    
    private String handleReplace(String line) {
        try {
            String content = line.substring(8);
            String[] parts = content.split(",", 3);
            if (parts.length == 3) {
                return parts[0].trim().replace(parts[1].trim(), parts[2].trim());
            }
        } catch (Exception e) {
        }
        return "语法错误: replace 原字符串,旧内容,新内容";
    }
    
    private String handleSplit(String line) {
        try {
            String content = line.substring(6);
            String[] parts = content.split(",", 2);
            if (parts.length == 2) {
                String text = parts[0].trim();
                String delimiter = parts[1].trim();
                String[] splitResult = text.split(Pattern.quote(delimiter));
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < splitResult.length; i++) {
                    if (i > 0) result.append("\n");
                    result.append(i + 1).append(": ").append(splitResult[i]);
                }
                return result.toString();
            }
        } catch (Exception e) {
        }
        return "语法错误: split 文本,分隔符";
    }
    
    private String handleJoin(String line) {
        try {
            String content = line.substring(5);
            String[] parts = content.split(",", 2);
            if (parts.length == 2) {
                String delimiter = parts[0].trim();
                String listStr = parts[1].trim();
                if (listStr.startsWith("[") && listStr.endsWith("]")) {
                    listStr = listStr.substring(1, listStr.length() - 1);
                }
                String[] items = listStr.split(",");
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < items.length; i++) {
                    if (i > 0) result.append(delimiter);
                    result.append(items[i].trim());
                }
                return result.toString();
            }
        } catch (Exception e) {
        }
        return "语法错误: join 分隔符,列表";
    }
    
    private String handleCallTool(String line, Map<String, Object> parameters) {
        try {
            String content = line.substring(10);
            String[] parts = content.split(",", 2);
            
            if (parts.length < 1) {
                return "语法错误: call_tool 工具名,[参数JSON]";
            }
            
            String toolName = parts[0].trim();
            Map<String, Object> toolParams = new HashMap<>();
            
            if (parts.length == 2) {
                String jsonStr = parts[1].trim();
                toolParams = parseSimpleJson(jsonStr);
            }
            
            AIToolManager toolManager = AIToolManager.getInstance(context);
            if (toolManager.hasTool(toolName)) {
                AIToolResult result = toolManager.executeTool(toolName, toolParams);
                return result.getResult() != null ? result.getResult().toString() : "工具执行完成";
            } else {
                return "工具不存在: " + toolName;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error calling tool: " + e.getMessage());
            return "调用工具失败: " + e.getMessage();
        }
    }
    
    private Map<String, Object> parseSimpleJson(String jsonStr) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            jsonStr = jsonStr.trim();
            if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
                jsonStr = jsonStr.substring(1, jsonStr.length() - 1);
            }
            
            Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
            Matcher matcher = kvPattern.matcher(jsonStr);
            
            while (matcher.find()) {
                String key = matcher.group(1);
                String value = matcher.group(2);
                result.put(key, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing simple JSON: " + e.getMessage());
        }
        
        return result;
    }
    
    public void clearVariables() {
        variables.clear();
    }
    
    public Object getVariable(String name) {
        return variables.get(name);
    }
}
