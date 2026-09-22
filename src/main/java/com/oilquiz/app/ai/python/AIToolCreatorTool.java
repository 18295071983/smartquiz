package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.DynamicAITool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "ai_create_tool",
    description = "AI创建工具，使用AI自动生成新工具",
    category = "tool_creation",
    aliases = {"create_tool", "tool_creator", "创建工具"},
    actions = {
        @Action(name = "analyze", description = "分析需求是否需要新工具"),
        @Action(name = "create", description = "创建新工具"),
        @Action(name = "execute", description = "执行工具"),
        @Action(name = "list", description = "列出已创建的工具"),
        @Action(name = "delete", description = "删除工具")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "task", type = "string", description = "任务描述", required = false),
        @Param(name = "tool_name", type = "string", description = "工具名称", required = false),
        @Param(name = "description", type = "string", description = "工具描述", required = false),
        @Param(name = "parameters", type = "string", description = "工具参数定义(JSON格式)", required = false),
        @Param(name = "code", type = "string", description = "Python或JavaScript代码(create时使用，可选，不提供则自动生成Python代码)", required = false),
        @Param(name = "tool_params", type = "string", description = "执行参数(JSON格式)", required = false)
    }
)
public class AIToolCreatorTool implements AITool {
    private static final String TAG = "AIToolCreatorTool";
    private static final String TOOL_NAME = "ai_create_tool";
    
    private final Context context;
    private final AIToolCreatorManager creatorManager;
    private final AIToolManager toolManager;
    
    public AIToolCreatorTool(Context context) {
        this.context = context.getApplicationContext();
        this.creatorManager = AIToolCreatorManager.getInstance(context);
        this.toolManager = AIToolManager.getInstance(context);
    }
    
    @Override
    public String getName() {
        return TOOL_NAME;
    }
    
    @Override
    public String getDescription() {
        return "AI 自动工具创建器。可以分析任务需求，自动创建、测试、修复和注册新工具。支持单位转换、JSON格式化、正则测试、哈希计算、时间戳转换等常用功能。";
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型：analyze(分析需求)、create(创建工具)、execute(执行工具)、list(列出工具)、delete(删除工具)");
        params.put("task", "任务描述，用于分析是否需要新工具（analyze 或 create 时使用）");
        params.put("tool_name", "工具名称（create、execute、delete 时使用）");
        params.put("description", "工具描述（create 时使用，可选）");
        params.put("parameters", "工具参数定义，JSON格式：{\"参数名\":\"参数描述\",...}（create 时使用，可选）");
        params.put("code", "Python/JavaScript 代码（create 时使用，可选，不提供则自动生成 Python 代码）");
        params.put("examples", "示例参数列表，JSON数组格式（create 时使用，可选）");
        params.put("tool_params", "执行工具时的参数，JSON格式（execute 时使用）");
        return params;
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Log.i(TAG, "Executing AIToolCreatorTool with params: " + parameters);
            
            if (!creatorManager.isInitialized()) {
                if (!creatorManager.initialize()) {
                    return createErrorResult("AI 工具创建器初始化失败，请检查 Chaquopy 配置");
                }
            }
            
            if (parameters == null || parameters.isEmpty()) {
                return createErrorResult("缺少参数，请提供操作类型");
            }
            
            String action = getStringParam(parameters, "action", "create");
            
            switch (action.toLowerCase()) {
                case "analyze":
                    return handleAnalyze(parameters);
                case "create":
                    return handleCreate(parameters);
                case "execute":
                    return handleExecute(parameters);
                case "list":
                    return handleList(parameters);
                case "delete":
                case "remove":
                    return handleDelete(parameters);
                default:
                    return createErrorResult("未知操作类型: " + action + "，支持的操作：analyze, create, execute, list, delete");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing AIToolCreatorTool: " + e.getMessage(), e);
            return createErrorResult("执行错误: " + e.getMessage());
        }
    }
    
    private AIToolResult handleAnalyze(Map<String, Object> parameters) {
        String task = getStringParam(parameters, "task", null);
        if (task == null || task.isEmpty()) {
            return createErrorResult("必须提供 task 参数（任务描述）");
        }
        
        List<String> existingTools = new ArrayList<>();
        for (AITool tool : toolManager.getTools()) {
            existingTools.add(tool.getName());
        }
        
        AIToolCreatorManager.ToolAnalysisResult analysis = creatorManager.analyzeTaskForTool(task, existingTools);
        
        StringBuilder result = new StringBuilder();
        Map<String, Object> additionalInfo = new HashMap<>();
        
        result.append("🔍 任务分析结果\n\n");
        result.append("任务: ").append(task).append("\n\n");
        
        if (analysis.needsNewTool) {
            result.append("✅ 建议创建新工具\n\n");
            
            if (analysis.toolName != null) {
                result.append("工具名称: ").append(analysis.toolName).append("\n");
            }
            if (analysis.toolDescription != null) {
                result.append("工具描述: ").append(analysis.toolDescription).append("\n");
            }
            if (analysis.reason != null && !analysis.reason.isEmpty()) {
                result.append("原因: ").append(analysis.reason).append("\n");
            }
            
            if (analysis.parameters != null && !analysis.parameters.isEmpty()) {
                result.append("\n参数定义:\n");
                for (Map.Entry<String, String> entry : analysis.parameters.entrySet()) {
                    result.append("  - ").append(entry.getKey()).append(": ")
                          .append(entry.getValue()).append("\n");
                }
            }
            
            result.append("\n💡 使用 ai_create_tool 工具创建此工具！");
        } else {
            result.append("ℹ️ 不需要创建新工具\n\n");
            
            if (analysis.reason != null && !analysis.reason.isEmpty()) {
                result.append("原因: ").append(analysis.reason).append("\n");
            }
            
            result.append("\n建议使用现有工具完成任务。");
        }
        
        additionalInfo.put("needs_new_tool", analysis.needsNewTool);
        additionalInfo.put("tool_name", analysis.toolName);
        additionalInfo.put("tool_description", analysis.toolDescription);
        additionalInfo.put("reason", analysis.reason);
        additionalInfo.put("parameters", analysis.parameters);

        return AIToolResult.success(result.toString(), additionalInfo);
    }
    
    private AIToolResult handleCreate(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        String description = getStringParam(parameters, "description", null);
        String code = getStringParam(parameters, "code", null);
        String task = getStringParam(parameters, "task", null);
        
        Map<String, String> toolParams = parseParameters(parameters);
        List<String> examples = parseExamples(parameters);
        
        if (toolName == null || toolName.isEmpty()) {
            if (task != null && !task.isEmpty()) {
                List<String> existingTools = new ArrayList<>();
                for (AITool tool : toolManager.getTools()) {
                    existingTools.add(tool.getName());
                }
                
                AIToolCreatorManager.ToolAnalysisResult analysis = 
                    creatorManager.analyzeTaskForTool(task, existingTools);
                
                if (analysis.needsNewTool && analysis.toolName != null) {
                    toolName = analysis.toolName;
                    if (description == null) {
                        description = analysis.toolDescription;
                    }
                    if (toolParams == null || toolParams.isEmpty()) {
                        toolParams = analysis.parameters;
                    }
                } else {
                    return createErrorResult("无法从任务推断工具名称，请提供 tool_name 参数");
                }
            } else {
                return createErrorResult("必须提供 tool_name 参数");
            }
        }
        
        if (toolManager.hasTool(toolName)) {
            return createErrorResult("工具 " + toolName + " 已存在");
        }
        
        if (description == null || description.isEmpty()) {
            description = "AI 创建的工具: " + toolName;
        }
        
        Log.i(TAG, "Creating AI tool: " + toolName);
        Log.d(TAG, "Description: " + description);
        Log.d(TAG, "Parameters: " + toolParams);
        Log.d(TAG, "Code: " + (code != null ? code.substring(0, Math.min(100, code.length())) : "auto-generated"));
        
        // 2026-09-23：并行解析结构化参数（属性级/完整 JSON Schema），
        // 注册时保留 type/required/enum——不再全部降级成 Map 字符串描述
        com.oilquiz.app.ai.tool.DynamicToolParams structuredParams = parseStructuredParams(parameters);
        
        AIToolCreatorManager.ToolCreationResult creationResult = 
            creatorManager.createTool(toolName, description, toolParams, examples, code);
        
        if (creationResult.success) {
            registerDynamicTool(creationResult, structuredParams);
            
            StringBuilder result = new StringBuilder();
            result.append("✅ 工具创建成功！\n\n");
            result.append("工具名称: ").append(toolName).append("\n");
            result.append("描述: ").append(description).append("\n");
            result.append("尝试次数: ").append(creationResult.attempts).append("\n\n");
            
            if (toolParams != null && !toolParams.isEmpty()) {
                result.append("参数定义:\n");
                for (Map.Entry<String, String> entry : toolParams.entrySet()) {
                    result.append("  - ").append(entry.getKey()).append(": ")
                          .append(entry.getValue()).append("\n");
                }
            }
            
            result.append("\n🎉 工具 ").append(toolName).append(" 已注册并可以使用！");
            
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("tool_name", toolName);
            additionalInfo.put("success", true);
            additionalInfo.put("attempts", creationResult.attempts);
            additionalInfo.put("registered", true);
            
            if (creationResult.spec != null) {
                additionalInfo.put("code", creationResult.spec.code);
            }
            
            return AIToolResult.success(result.toString(), additionalInfo);
        } else {
            StringBuilder result = new StringBuilder();
            result.append("❌ 工具创建失败\n\n");
            result.append("工具名称: ").append(toolName).append("\n");
            result.append("尝试次数: ").append(creationResult.attempts).append("\n");
            
            if (creationResult.error != null) {
                result.append("错误: ").append(creationResult.error).append("\n");
            }
            
            return createErrorResult(result.toString());
        }
    }
    
    private void registerDynamicTool(AIToolCreatorManager.ToolCreationResult result,
                                     com.oilquiz.app.ai.tool.DynamicToolParams dynamicParams) {
        if (result.spec == null) {
            return;
        }
        
        // 2026-09-23：创建路径保留结构化参数（属性级/完整 JSON Schema 格式）——
        // python 动态工具与内置工具体系统一 schema 出口，tool_registry(get) 解析出真实类型/必填/枚举
        PythonDynamicTool dynamicTool = dynamicParams != null && !dynamicParams.isEmpty()
            ? new PythonDynamicTool(context, result.toolName, result.spec.description,
                    result.spec.parameters, dynamicParams, result.spec.code)
            : new PythonDynamicTool(context, result.toolName, result.spec.description,
                    result.spec.parameters, result.spec.code);
        
        toolManager.registerDynamicTool(dynamicTool);
        Log.i(TAG, "Dynamic tool registered: " + result.toolName
                + (dynamicParams != null && !dynamicParams.isEmpty() ? " (structured)" : ""));
    }
    
    private AIToolResult handleExecute(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("必须提供 tool_name 参数");
        }
        
        // tool_params 兼容 Map 与 JSON 字符串（@Param 文档标注为 JSON 格式）
        Map<String, Object> toolParams = new HashMap<>();
        Object rawParams = parameters.get("tool_params");
        if (rawParams instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) rawParams;
            toolParams.putAll(m);
        } else if (rawParams instanceof String) {
            String s = ((String) rawParams).trim();
            if (!s.isEmpty()) {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(s);
                    java.util.Iterator<String> keys = jo.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        toolParams.put(key, jo.opt(key));
                    }
                } catch (Exception e) {
                    return createErrorResult("tool_params 不是合法 JSON: " + e.getMessage());
                }
            }
        } else if (rawParams != null) {
            return createErrorResult("tool_params 参数类型不支持（应为对象或JSON字符串）");
        }
        
        Log.i(TAG, "Executing AI tool: " + toolName);
        
        PythonToolManager.ExecutionResult result = creatorManager.executeTool(toolName, toolParams);
        
        StringBuilder output = new StringBuilder();
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("tool_name", toolName);
        additionalInfo.put("success", result.success);
        
        if (result.success) {
            output.append("✅ 工具执行成功\n\n");
            
            if (result.stdout != null && !result.stdout.isEmpty()) {
                output.append("=== 输出 ===\n");
                output.append(result.stdout).append("\n\n");
            }
            
            if (result.result != null) {
                output.append("=== 结果 ===\n");
                output.append(result.result).append("\n");
            }
            
            additionalInfo.put("stdout", result.stdout);
            additionalInfo.put("result", result.result);
            
            return AIToolResult.success(output.toString(), additionalInfo);
        } else {
            output.append("❌ 工具执行失败\n\n");
            
            if (result.error != null) {
                output.append("错误: ").append(result.error).append("\n");
            }
            if (result.stderr != null && !result.stderr.isEmpty()) {
                output.append("详细信息: ").append(result.stderr).append("\n");
            }
            
            return createErrorResult(output.toString());
        }
    }
    
    private AIToolResult handleList(Map<String, Object> parameters) {
        // 统一列 AIToolManager 的全部动态工具（含 ai_create_tool 与 create_dynamic_tool 创建的），
        // 避免只看 Python 侧导致清单不完整
        List<String> toolNames = toolManager.getDynamicTools();
        List<AITool> tools = new ArrayList<>();
        for (String name : toolNames) {
            AITool tool = toolManager.getTool(name);
            if (tool != null) tools.add(tool);
        }

        StringBuilder result = new StringBuilder();
        result.append("📋 动态工具列表:\n\n");

        if (tools.isEmpty()) {
            result.append("(暂无动态工具)\n");
            result.append("\n使用 ai_create_tool 或 create_dynamic_tool 来创建新工具！");
        } else {
            for (AITool tool : tools) {
                result.append("🔧 ").append(tool.getName()).append("\n");
                result.append("   描述: ").append(tool.getDescription()).append("\n");

                Map<String, String> params = tool.getParameterDescriptions();
                if (params != null && !params.isEmpty()) {
                    result.append("   参数: ").append(String.join(", ", params.keySet())).append("\n");
                }

                if (tool instanceof com.oilquiz.app.ai.python.PythonDynamicTool) {
                    String code = ((com.oilquiz.app.ai.python.PythonDynamicTool) tool).getCode();
                    if (code != null && !code.isEmpty()) {
                        result.append("   类型: Python（代码已定义）\n");
                    }
                } else if (tool instanceof DynamicAITool) {
                    String logic = ((DynamicAITool) tool).getExecutionLogic();
                    result.append("   类型: 脚本" + (logic != null && !logic.isEmpty() ? "（逻辑已定义）" : "（逻辑未定义）") + "\n");
                }
                result.append("\n");
            }
        }

        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("count", tools.size());
        additionalInfo.put("tools", toolNames);

        return AIToolResult.success(result.toString(), additionalInfo);
    }
    
    private AIToolResult handleDelete(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("必须提供 tool_name 参数");
        }
        
        if (!toolManager.hasTool(toolName)) {
            return createErrorResult("工具 " + toolName + " 不存在");
        }

        AITool tool = toolManager.getTool(toolName);
        boolean deleted = true;
        // Python 工具需同时删除 Python 侧；Java 脚本工具只需删 AIToolManager 侧
        if (tool instanceof com.oilquiz.app.ai.python.PythonDynamicTool) {
            deleted = creatorManager.deleteTool(toolName);
        }
        toolManager.unregisterDynamicTool(toolName);

        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("tool_name", toolName);
        additionalInfo.put("success", deleted);

        if (deleted) {
            return AIToolResult.success("✅ 工具已删除: " + toolName, additionalInfo);
        } else {
            return createErrorResult("工具删除失败: " + toolName + "（已从运行中移除，Python 侧清理失败）");
        }
    }
    
    private String getStringParam(Map<String, Object> parameters, String key, String defaultValue) {
        if (parameters == null) return defaultValue;
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }
    
    /**
     * 解析结构化参数定义（create_dynamic_tool 三种格式之二）：
     * 1) 属性级：{"param":{"type":"string","description":"...","required":true,"enum":[...]}}
     * 2) 完整 JSON Schema：{"type":"object","properties":{...},"required":[...]}
     * 简单格式 {"name":"desc"}（值不是对象）返回 null——无结构化信息，走 Map 退化路径。
     */
    private com.oilquiz.app.ai.tool.DynamicToolParams parseStructuredParams(Map<String, Object> parameters) {
        try {
            if (parameters == null) return null;
            Object paramsObj = parameters.get("parameters");
            String jsonStr = null;
            if (paramsObj instanceof String) {
                jsonStr = ((String) paramsObj).trim();
            } else if (paramsObj instanceof Map) {
                jsonStr = new org.json.JSONObject((Map<?, ?>) paramsObj).toString();
            }
            if (jsonStr == null || jsonStr.isEmpty()) return null;
            
            org.json.JSONObject root = new org.json.JSONObject(jsonStr);
            // 完整 JSON Schema：properties 承载参数；属性级：根本身就是参数映射
            org.json.JSONObject props = root.optJSONObject("properties");
            if (props == null) {
                boolean allObjects = true;
                java.util.Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    if (!(root.opt(keys.next()) instanceof org.json.JSONObject)) {
                        allObjects = false;
                        break;
                    }
                }
                if (allObjects) props = root;
            }
            if (props == null) return null;
            
            List<com.oilquiz.app.ai.tool.openai.ParamDefinition> defs = new java.util.ArrayList<>();
            java.util.Iterator<String> pKeys = props.keys();
            while (pKeys.hasNext()) {
                String pName = pKeys.next();
                org.json.JSONObject p = props.optJSONObject(pName);
                if (p == null) continue;
                String type = p.optString("type", "string");
                String desc = p.optString("description", p.optString("desc", ""));
                boolean required = p.optBoolean("required", false);
                List<String> enumValues = null;
                org.json.JSONArray enumArr = p.optJSONArray("enum");
                if (enumArr != null && enumArr.length() > 0) {
                    enumValues = new java.util.ArrayList<>();
                    for (int i = 0; i < enumArr.length(); i++) {
                        Object v = enumArr.opt(i);
                        enumValues.add(v != null ? String.valueOf(v) : "");
                    }
                }
                Object defaultValue = p.has("default") ? p.opt("default") : null;
                defs.add(new com.oilquiz.app.ai.tool.openai.ParamDefinition(
                        pName, type, desc, required, defaultValue, enumValues));
            }
            // 根级 required 数组补必填标记（完整 JSON Schema 格式）
            org.json.JSONArray reqArr = root.optJSONArray("required");
            if (reqArr != null && !defs.isEmpty()) {
                for (int i = 0; i < reqArr.length(); i++) {
                    String reqName = reqArr.optString(i, "");
                    if (reqName.isEmpty()) continue;
                    for (int j = 0; j < defs.size(); j++) {
                        com.oilquiz.app.ai.tool.openai.ParamDefinition d = defs.get(j);
                        if (d.getName().equals(reqName) && !d.isRequired()) {
                            defs.set(j, new com.oilquiz.app.ai.tool.openai.ParamDefinition(
                                    d.getName(), d.getType(), d.getDescription(), true,
                                    d.getDefaultValue(), d.getEnumValues()));
                            break;
                        }
                    }
                }
            }
            return defs.isEmpty() ? null : new com.oilquiz.app.ai.tool.DynamicToolParams(defs);
        } catch (Exception e) {
            Log.w(TAG, "parseStructuredParams failed: " + e.getMessage());
            return null;
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, String> parseParameters(Map<String, Object> parameters) {
        Map<String, String> result = new HashMap<>();
        
        if (parameters == null) return result;
        
        Object paramsObj = parameters.get("parameters");
        if (paramsObj instanceof Map) {
            Map<String, Object> paramsMap = (Map<String, Object>) paramsObj;
            for (Map.Entry<String, Object> entry : paramsMap.entrySet()) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        } else if (paramsObj instanceof String) {
            String paramsStr = (String) paramsObj;
            try {
                org.json.JSONObject json = new org.json.JSONObject(paramsStr);
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    result.put(key, json.getString(key));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse parameters JSON: " + e.getMessage());
            }
        }
        
        return result;
    }
    
    @SuppressWarnings("unchecked")
    private List<String> parseExamples(Map<String, Object> parameters) {
        List<String> result = new ArrayList<>();
        
        if (parameters == null) return result;
        
        Object examplesObj = parameters.get("examples");
        if (examplesObj instanceof List) {
            List<Object> examplesList = (List<Object>) examplesObj;
            for (Object item : examplesList) {
                result.add(String.valueOf(item));
            }
        } else if (examplesObj instanceof String) {
            String examplesStr = (String) examplesObj;
            try {
                org.json.JSONArray json = new org.json.JSONArray(examplesStr);
                for (int i = 0; i < json.length(); i++) {
                    result.add(json.get(i).toString());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse examples JSON: " + e.getMessage());
            }
        }
        
        return result;
    }
    
    private AIToolResult createErrorResult(String message) {
        Log.w(TAG, "Error: " + message);
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("error", message);
        additionalInfo.put("success", false);
        return AIToolResult.fail("❌ " + message, additionalInfo);
    }
}
