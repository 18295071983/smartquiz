package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "ai_create_tool",
    description = "AI 自动工具创建器。可以分析任务需求，自动创建、测试、修复和注册新工具。",
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
        @Param(name = "code", type = "string", description = "Python代码", required = false),
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
        params.put("code", "Python 代码（create 时使用，可选，不提供则自动生成）");
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
        
        AIToolCreatorManager.ToolCreationResult creationResult = 
            creatorManager.createTool(toolName, description, toolParams, examples, code);
        
        if (creationResult.success) {
            registerDynamicTool(creationResult);
            
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
    
    private void registerDynamicTool(AIToolCreatorManager.ToolCreationResult result) {
        if (result.spec == null) {
            return;
        }
        
        PythonDynamicTool dynamicTool = new PythonDynamicTool(
            context,
            result.toolName,
            result.spec.description,
            result.spec.parameters,
            result.spec.code
        );
        
        toolManager.registerDynamicTool(dynamicTool);
        Log.i(TAG, "Dynamic tool registered: " + result.toolName);
    }
    
    private AIToolResult handleExecute(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("必须提供 tool_name 参数");
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> toolParams = (Map<String, Object>) parameters.get("tool_params");
        if (toolParams == null) {
            toolParams = new HashMap<>();
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
        List<AIToolCreatorManager.ToolInfo> tools = creatorManager.listTools();
        
        StringBuilder result = new StringBuilder();
        result.append("📋 AI 创建的工具列表:\n\n");
        
        if (tools.isEmpty()) {
            result.append("(暂无 AI 创建的工具)\n");
            result.append("\n使用 ai_create_tool 工具来创建新工具！");
        } else {
            for (AIToolCreatorManager.ToolInfo tool : tools) {
                result.append("🔧 ").append(tool.name);
                if (tool.version != null) {
                    result.append(" (v").append(tool.version).append(")");
                }
                result.append("\n");
                
                if (tool.description != null) {
                    result.append("   描述: ").append(tool.description).append("\n");
                }
                if (tool.category != null) {
                    result.append("   分类: ").append(tool.category).append("\n");
                }
                if (tool.parameters != null && !tool.parameters.isEmpty()) {
                    result.append("   参数: ").append(String.join(", ", tool.parameters.keySet())).append("\n");
                }
                result.append("\n");
            }
        }
        
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("count", tools.size());
        additionalInfo.put("tools", tools);

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
        
        boolean deleted = creatorManager.deleteTool(toolName);
        if (deleted) {
            toolManager.unregisterDynamicTool(toolName);
            
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("tool_name", toolName);
            additionalInfo.put("success", true);
            
            return AIToolResult.success("✅ 工具已删除: " + toolName, additionalInfo);
        } else {
            return createErrorResult("工具删除失败: " + toolName);
        }
    }
    
    private String getStringParam(Map<String, Object> parameters, String key, String defaultValue) {
        if (parameters == null) return defaultValue;
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
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
