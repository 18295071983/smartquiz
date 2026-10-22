package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PythonDynamicTool implements AITool, com.oilquiz.app.ai.tool.openai.StructuredParamTool {
    private static final String TAG = "PythonDynamicTool";
    
    private final Context context;
    private final String name;
    private final String description;
    private final Map<String, String> parameterDescriptions;
    /** 结构化参数（2026-09-23：Python 动态工具与内置工具体系统一 schema 出口；可为空） */
    private final com.oilquiz.app.ai.tool.DynamicToolParams dynamicParams;
    private final String code;
    private final PythonToolManager toolManager;
    
    public PythonDynamicTool(Context context, String name, String description,
                             Map<String, String> parameterDescriptions,
                             String code) {
        this(context, name, description, parameterDescriptions, null, code);
    }
    
    /** 结构化参数构造（2026-09-23：支持 paramSchema 属性级/完整 JSON Schema 注册） */
    public PythonDynamicTool(Context context, String name, String description,
                             Map<String, String> parameterDescriptions,
                             com.oilquiz.app.ai.tool.DynamicToolParams dynamicParams,
                             String code) {
        this.context = context.getApplicationContext();
        this.name = name;
        this.description = description;
        this.parameterDescriptions = parameterDescriptions != null ? 
            parameterDescriptions : new HashMap<>();
        this.dynamicParams = dynamicParams;
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
    
    /** 结构化参数出口（StructuredParamTool）：dynamicParams 优先，否则从 Map 派生
     *  （name→string，与 DynamicAITool 退化路径一致——保证 tool_registry(get) 永远能解析） */
    @Override
    public List<com.oilquiz.app.ai.tool.openai.ParamDefinition> getParameterDefinitions() {
        if (dynamicParams != null && !dynamicParams.isEmpty()) {
            return dynamicParams.getDefinitions();
        }
        List<com.oilquiz.app.ai.tool.openai.ParamDefinition> defs = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : parameterDescriptions.entrySet()) {
            defs.add(new com.oilquiz.app.ai.tool.openai.ParamDefinition(
                    entry.getKey(), "string", entry.getValue(), false));
        }
        return defs;
    }
    
    /** 结构化参数（持久化/调试用） */
    public com.oilquiz.app.ai.tool.DynamicToolParams getDynamicParams() {
        return dynamicParams;
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
            
            PythonToolManager.ExecutionResult result = toolManager.executeCode(fullCode, null);
            
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("isDynamic", true);
            additionalInfo.put("parameters", parameters);
            additionalInfo.put("attempts", result.attempts);
            
            // 文本渲染交给共用出口（这条以前是 python_execute 那份的复制品，
            // 所以自动修复摘要、环境错误标记这些后加的东西它都没跟上）
            if (result.success) {
                additionalInfo.put("stdout", result.stdout);
                additionalInfo.put("result", result.result);
                additionalInfo.put("fixes_applied", result.fixes.size());
                return AIToolResult.success(
                        PythonExecutionResultText.render(result, "✅ 工具执行成功", "❌ 工具执行失败"),
                        additionalInfo);
            }

            additionalInfo.put("error", result.error);
            additionalInfo.put("stderr", result.stderr);
            // 注意：两参构造 new AIToolResult(text, info) 的 success 是 true，
            // 旧代码在这里用的正是它 —— 动态工具执行失败一直被当成成功上报（引擎/卡片都不降级）。
            // 现在显式走三参构造，把成败标志还回去。
            return new AIToolResult(
                    PythonExecutionResultText.render(result, "✅ 工具执行成功", "❌ 工具执行失败"),
                    additionalInfo, false);
            
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
        // org.json 类型（AgentService 解析的嵌套参数）：先转标准容器再序列化，
        // 避免把 JSONObject.toString() 当字符串拼进 script_args 导致参数注入损坏
        if (value instanceof org.json.JSONObject) {
            org.json.JSONObject jo = (org.json.JSONObject) value;
            Map<String, Object> m = new HashMap<>();
            java.util.Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                m.put(k, jo.opt(k));
            }
            return mapToPythonDict(m);
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray ja = (org.json.JSONArray) value;
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (int i = 0; i < ja.length(); i++) {
                list.add(ja.opt(i));
            }
            return pythonList(list);
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
