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

    /** 嵌套 call_tool 最大深度（防动态工具递归互调死循环） */
    private static final int MAX_CALL_DEPTH = 5;
    /** 当前线程的 call_tool 嵌套深度（ThreadLocal：并发调用互不干扰） */
    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final Context context;
    private final Map<String, Object> variables = new HashMap<>();
    
    public DynamicToolExecutor(Context context) {
        this.context = context;
    }
    
    public String execute(String logic, Map<String, Object> parameters) {
        if (logic == null || logic.trim().isEmpty()) {
            return "未提供执行逻辑";
        }

        // Python 脚本支持：逻辑是 Python 脚本时交给 Python 执行引擎（脚本内用 script_args 取参数）
        if (looksLikePython(logic)) {
            return executeAsPython(logic, parameters);
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

    // ==================== Python 脚本支持 ====================

    /**
     * 判断执行逻辑是否为 Python 脚本：
     * 1. 显式标记：首行 python / ```python / # python
     * 2. 语法特征：def/import/from/class 行首、# -*- coding、行首缩进的代码块、print( 等
     * DSL 命令（echo/set/if 等）不会被误判为 Python。
     */
    private static boolean looksLikePython(String logic) {
        String[] lines = logic.split("\n");
        boolean hasIndented = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("```python") || lower.startsWith("python:")
                    || lower.equals("python") || lower.startsWith("# python")
                    || lower.contains("coding:")) {
                return true;
            }
            if (line.startsWith("def ") || line.startsWith("import ")
                    || line.startsWith("from ") || line.startsWith("class ")) {
                return true;
            }
            // DSL 命令行 → 不是 Python 块
            if (isDslCommand(line)) {
                if (hasIndented) return true; // 前面已有缩进代码 → 整体 Python
                continue;
            }
            // 非命令行：有缩进 → Python 特征
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                hasIndented = true;
            }
            // print( / return 等 Python 调用特征（DSL 的 print 是裸 print 空格）
            if (line.contains("print(") || line.contains("return ")
                    || line.contains("==") || line.contains("lambda ")
                    || line.matches("[A-Za-z_][\\w]*\\s*=.*")) {
                if (!lower.startsWith("set ") && !lower.startsWith("if ")) {
                    hasIndented = true;
                }
            }
        }
        return hasIndented;
    }

    private static boolean isDslCommand(String line) {
        String lower = line.toLowerCase();
        return lower.startsWith("echo ") || lower.startsWith("print ")
                || lower.startsWith("log ") || lower.startsWith("set ")
                || lower.startsWith("if ") || lower.startsWith("concat ")
                || lower.startsWith("length ") || lower.startsWith("upper ")
                || lower.startsWith("lower ") || lower.startsWith("trim ")
                || lower.startsWith("replace ") || lower.startsWith("split ")
                || lower.startsWith("join ") || lower.startsWith("call_tool ");
    }

    /**
     * 用 Python 执行引擎运行脚本。参数以 script_args dict 注入，
     * 脚本可通过 script_args['参数名'] 读取；print 输出与返回值合并返回。
     */
    private String executeAsPython(String logic, Map<String, Object> parameters) {
        try {
            com.oilquiz.app.ai.python.PythonToolManager ptm =
                    com.oilquiz.app.ai.python.PythonToolManager.getInstance(context);
            if (!ptm.isInitialized()) {
                if (!ptm.initialize()) {
                    return "Python 环境初始化失败，无法执行 Python 脚本";
                }
            }
            StringBuilder fullCode = new StringBuilder();
            fullCode.append("# -*- coding: utf-8 -*-\n");
            fullCode.append("import sys\n");
            fullCode.append("sys.path.insert(0, '.')\n\n");
            fullCode.append("# 脚本参数\n");
            fullCode.append("script_args = ").append(toPythonDict(parameters)).append("\n\n");
            fullCode.append(logic);

            com.oilquiz.app.ai.python.PythonToolManager.ExecutionResult r =
                    ptm.executeCode(fullCode.toString(), parameters);
            if (r != null && r.success) {
                StringBuilder out = new StringBuilder();
                if (r.stdout != null && !r.stdout.trim().isEmpty()) {
                    out.append(r.stdout.trim());
                }
                if (r.result != null && !r.result.trim().isEmpty()) {
                    if (out.length() > 0) out.append("\n");
                    out.append(r.result.trim());
                }
                return out.length() > 0 ? out.toString() : "执行完成";
            }
            StringBuilder err = new StringBuilder("Python 执行失败");
            if (r != null) {
                if (r.error != null && !r.error.isEmpty()) err.append(": ").append(r.error);
                if (r.stderr != null && !r.stderr.trim().isEmpty()) {
                    err.append("\n").append(r.stderr.trim());
                }
            } else {
                err.append("（无返回结果）");
            }
            return err.toString();
        } catch (Throwable t) {
            Log.e(TAG, "Python 动态工具执行异常: " + t.getMessage(), t);
            return "Python 执行异常: " + t.getMessage();
        }
    }

    /** Map → Python dict 字面量 */
    private static String toPythonDict(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("'").append(e.getKey().replace("'", "\\'")).append("': ");
            sb.append(toPythonValue(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String toPythonValue(Object v) {
        if (v == null) return "None";
        if (v instanceof String) return "'" + ((String) v).replace("'", "\\'") + "'";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof java.util.List) {
            StringBuilder sb = new StringBuilder("[");
            java.util.List<?> list = (java.util.List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(toPythonValue(list.get(i)));
            }
            return sb.append("]").toString();
        }
        if (v instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return toPythonDict(m);
        }
        return "'" + v.toString().replace("'", "\\'") + "'";
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
            
            // ===== 递归/嵌套调用防护 =====
            // 1. 禁止调用元工具（动态工具创建自身/互调会造成无限递归）
            if ("create_dynamic_tool".equals(toolName) || "ai_create_tool".equals(toolName)) {
                return "安全限制: 动态工具禁止调用 " + toolName + "（避免递归创建）";
            }
            // 2. 嵌套调用深度限制（动态工具 A call_tool 动态工具 B 再 call_tool...）
            int depth = CALL_DEPTH.get();
            if (depth >= MAX_CALL_DEPTH) {
                return "安全限制: 工具嵌套调用超过 " + MAX_CALL_DEPTH + " 层，已终止";
            }
            
            AIToolManager toolManager = AIToolManager.getInstance(context);
            if (toolManager.hasTool(toolName)) {
                CALL_DEPTH.set(depth + 1);
                try {
                    AIToolResult result = toolManager.executeTool(toolName, toolParams);
                    return result.getResult() != null ? result.getResult().toString() : "工具执行完成";
                } finally {
                    CALL_DEPTH.set(depth);
                }
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
