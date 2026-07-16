package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolResult;

import java.util.HashMap;
import java.util.Map;

public class PythonDynamicTool implements AITool {
    private static final String TAG = "PythonDynamicTool";
    
    private final Context context;
    private final String name;
    private final String description;
    private final Map<String, String> parameterDescriptions;
    private final String code;
    private final PythonToolManager toolManager;
    
    public PythonDynamicTool(Context context, String name, String description,
                             Map<String, String> parameterDescriptions,
                             String code) {
        this.context = context.getApplicationContext();
        this.name = name;
        this.description = description;
        this.parameterDescriptions = parameterDescriptions != null ? 
            parameterDescriptions : new HashMap<>();
        this.code = code;
        this.toolManager = PythonToolManager.getInstance(context);
        
        if (!toolManager.isInitialized()) {
            toolManager.initialize();
        }
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        return new HashMap<>(parameterDescriptions);
    }
    
    public String getCode() {
        return code;
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Log.i(TAG, "Executing Python dynamic tool: " + name);
            Log.d(TAG, "Parameters: " + parameters);
            
            String fullCode = buildFullCode(code, parameters);
            Log.d(TAG, "Code: " + fullCode.substring(0, Math.min(200, fullCode.length())));
            
            PythonToolManager.ExecutionResult result = toolManager.processTask(fullCode, parameters);
            
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("isDynamic", true);
            additionalInfo.put("parameters", parameters);
            additionalInfo.put("attempts", result.attempts);
            
            if (result.success) {
                StringBuilder output = new StringBuilder();
                output.append("✅ 工具执行成功\n\n");
                
                if (result.stdout != null && !result.stdout.isEmpty()) {
                    output.append("=== 输出 ===\n");
                    output.append(result.stdout).append("\n\n");
                }
                
                if (result.result != null) {
                    output.append("=== 结果 ===\n");
                    output.append(result.result).append("\n");
                }
                
                if (!result.fixes.isEmpty()) {
                    output.append("\n=== 自动修复 ===\n");
                    for (Map<String, String> fix : result.fixes) {
                        String type = fix.get("error_type");
                        if (type != null) {
                            output.append("- 修复: ").append(type).append("\n");
                        }
                    }
                }
                
                additionalInfo.put("stdout", result.stdout);
                additionalInfo.put("result", result.result);
                additionalInfo.put("fixes_applied", result.fixes.size());
                
                return new AIToolResult(output.toString(), additionalInfo);
            } else {
                StringBuilder output = new StringBuilder();
                output.append("❌ 工具执行失败\n\n");
                output.append("尝试次数: ").append(result.attempts).append("\n\n");
                
                if (result.error != null) {
                    output.append("=== 错误 ===\n");
                    output.append(result.error).append("\n\n");
                }
                
                if (result.stderr != null && !result.stderr.isEmpty()) {
                    output.append("=== 错误输出 ===\n");
                    output.append(result.stderr).append("\n\n");
                }
                
                if (result.stdout != null && !result.stdout.isEmpty()) {
                    output.append("=== 部分输出 ===\n");
                    output.append(result.stdout).append("\n");
                }
                
                if (!result.fixes.isEmpty()) {
                    output.append("\n=== 尝试的修复 ===\n");
                    for (Map<String, String> fix : result.fixes) {
                        String type = fix.get("error_type");
                        if (type != null) {
                            output.append("- 尝试修复: ").append(type).append("\n");
                        }
                    }
                }
                
                additionalInfo.put("error", result.error);
                additionalInfo.put("stderr", result.stderr);
                
                return new AIToolResult(output.toString(), additionalInfo);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing Python dynamic tool " + name + ": " + e.getMessage(), e);
            
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("error", e.getMessage());
            additionalInfo.put("isDynamic", true);
            
            return new AIToolResult("动态工具执行失败: " + e.getMessage(), additionalInfo);
        }
    }
    
    private String buildFullCode(String originalCode, Map<String, Object> parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("# -*- coding: utf-8 -*-\n");
        sb.append("import sys\n");
        sb.append("sys.path.insert(0, '.')\n\n");
        sb.append("# 脚本参数\n");
        sb.append("script_args = ").append(mapToPythonDict(parameters)).append("\n\n");
        sb.append(originalCode);
        return sb.toString();
    }
    
    private String mapToPythonDict(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            
            String key = entry.getKey();
            Object value = entry.getValue();
            
            sb.append("'").append(key.replace("'", "\\'")).append("': ");
            sb.append(pythonValue(value));
        }
        
        sb.append("}");
        return sb.toString();
    }
    
    private String pythonValue(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String) {
            return "'" + ((String) value).replace("'", "\\'") + "'";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "True" : "False";
        }
        if (value instanceof java.util.List) {
            return pythonList((java.util.List<?>) value);
        }
        if (value instanceof java.util.Map) {
            return mapToPythonDict((java.util.Map<String, Object>) value);
        }
        return "'" + value.toString().replace("'", "\\'") + "'";
    }
    
    private String pythonList(java.util.List<?> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(pythonValue(item));
        }
        
        sb.append("]");
        return sb.toString();
    }
}
