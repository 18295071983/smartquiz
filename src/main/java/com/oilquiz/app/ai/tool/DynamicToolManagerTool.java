package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;

@Tool(
    value = "create_dynamic_tool",
    description = "动态创建和管理AI工具。可以定义新工具的名称、描述、参数和执行逻辑。",
    category = "tool_management",
    aliases = {"dynamic_tool", "create_tool", "动态工具"},
    actions = {
        @Action(name = "create", description = "创建新工具"),
        @Action(name = "update", description = "更新已存在的工具"),
        @Action(name = "delete", description = "删除工具"),
        @Action(name = "list", description = "列出所有动态工具")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "tool_name", type = "string", description = "工具名称(仅英文、数字和下划线)", required = false),
        @Param(name = "description", type = "string", description = "工具描述", required = false),
        @Param(name = "parameters", type = "string", description = "工具参数定义(JSON格式)", required = false),
        @Param(name = "logic", type = "string", description = "执行逻辑脚本", required = false)
    }
)
public class DynamicToolManagerTool implements AITool {
    private static final String TAG = "DynamicToolManagerTool";
    private static final String TOOL_NAME = "create_dynamic_tool";
    
    private final Context context;
    private final AIToolManager toolManager;
    
    public DynamicToolManagerTool(Context context) {
        this.context = context;
        this.toolManager = AIToolManager.getInstance(context);
    }
    
    @Override
    public String getName() {
        return TOOL_NAME;
    }
    
    @Override
    public String getDescription() {
        return "动态创建和管理AI工具。可以定义新工具的名称、描述、参数和执行逻辑。";
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型：create(创建工具)、delete(删除工具)、list(列出工具)、update(更新工具)");
        params.put("tool_name", "工具名称(必填，仅英文、数字和下划线)");
        params.put("description", "工具描述");
        params.put("parameters", "工具参数定义，JSON格式：{\"参数名\":\"参数描述\",...}");
        params.put("logic", "执行逻辑脚本，每行一个命令。支持的命令：\n" +
                "  - echo 文本：输出文本\n" +
                "  - print 文本：输出文本\n" +
                "  - log 文本：记录日志\n" +
                "  - set 变量=值：设置变量\n" +
                "  - if (条件) then 命令：条件判断\n" +
                "  - concat 字符串1,字符串2,...：拼接字符串\n" +
                "  - upper 文本：转大写\n" +
                "  - lower 文本：转小写\n" +
                "  - replace 文本,旧值,新值：替换文本\n" +
                "  - split 文本,分隔符：分割文本\n" +
                "  - join 分隔符,列表：连接列表\n" +
                "  - call_tool 工具名,{\"参数名\":\"参数值\"}：调用其他工具\n" +
                "  - 变量替换：${参数名} 或 ${变量名}");
        return params;
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Log.i(TAG, "Executing DynamicToolManagerTool with params: " + parameters);
            
            if (parameters == null || parameters.isEmpty()) {
                return createErrorResult("缺少参数，请提供操作类型");
            }
            
            String action = getStringParam(parameters, "action", "create");
            
            switch (action.toLowerCase()) {
                case "create":
                    return handleCreate(parameters);
                case "update":
                    return handleUpdate(parameters);
                case "delete":
                case "remove":
                    return handleDelete(parameters);
                case "list":
                    return handleList(parameters);
                default:
                    return createErrorResult("未知操作类型: " + action + "，支持的操作：create, update, delete, list");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing DynamicToolManagerTool: " + e.getMessage(), e);
            return createErrorResult("执行错误: " + e.getMessage());
        }
    }
    
    private AIToolResult handleCreate(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("必须提供工具名称(tool_name)");
        }
        
        if (!isValidToolName(toolName)) {
            return createErrorResult("工具名称只能包含英文、数字和下划线");
        }
        
        if (toolManager.hasTool(toolName)) {
            return createErrorResult("工具 " + toolName + " 已存在，请使用update操作更新");
        }
        
        String description = getStringParam(parameters, "description", "用户自定义工具");
        Map<String, String> toolParams = parseParameters(parameters);
        String logic = getStringParam(parameters, "logic", null);
        
        Log.i(TAG, "Creating dynamic tool: " + toolName);
        Log.d(TAG, "Description: " + description);
        Log.d(TAG, "Parameters: " + toolParams);
        Log.d(TAG, "Logic: " + (logic != null ? logic.substring(0, Math.min(200, logic.length())) : "null"));
        
        DynamicAITool tool = toolManager.createAndRegisterDynamicTool(
            toolName,
            description,
            toolParams,
            logic
        );
        
        if (tool != null) {
            StringBuilder result = new StringBuilder();
            result.append("✅ 动态工具创建成功！\n\n");
            result.append("工具名称: ").append(toolName).append("\n");
            result.append("描述: ").append(description).append("\n");
            
            if (toolParams != null && !toolParams.isEmpty()) {
                result.append("参数: \n");
                for (Map.Entry<String, String> entry : toolParams.entrySet()) {
                    result.append("  - ").append(entry.getKey()).append(": ")
                          .append(entry.getValue()).append("\n");
                }
            }
            
            if (logic != null && !logic.isEmpty()) {
                result.append("执行逻辑: 已定义\n");
            } else {
                result.append("执行逻辑: 未定义(将使用默认逻辑)\n");
            }
            
            result.append("\n现在可以使用 ").append(toolName).append(" 工具了！");
            
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("tool_name", toolName);
            additionalInfo.put("action", "create");
            additionalInfo.put("success", true);
            
            return new AIToolResult(result.toString(), additionalInfo);
        } else {
            return createErrorResult("工具创建失败");
        }
    }
    
    private AIToolResult handleUpdate(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("必须提供工具名称(tool_name)");
        }
        
        if (!toolManager.hasTool(toolName)) {
            return createErrorResult("工具 " + toolName + " 不存在");
        }
        
        if (!toolManager.isDynamicTool(toolName)) {
            return createErrorResult("工具 " + toolName + " 不是动态工具，无法更新");
        }
        
        toolManager.unregisterDynamicTool(toolName);
        
        String description = getStringParam(parameters, "description", "用户自定义工具");
        Map<String, String> toolParams = parseParameters(parameters);
        String logic = getStringParam(parameters, "logic", null);
        
        DynamicAITool tool = toolManager.createAndRegisterDynamicTool(
            toolName,
            description,
            toolParams,
            logic
        );
        
        if (tool != null) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("tool_name", toolName);
            additionalInfo.put("action", "update");
            additionalInfo.put("success", true);
            
            return new AIToolResult("✅ 动态工具更新成功: " + toolName, additionalInfo);
        } else {
            return createErrorResult("工具更新失败");
        }
    }
    
    private AIToolResult handleDelete(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("必须提供工具名称(tool_name)");
        }
        
        if (!toolManager.hasTool(toolName)) {
            return createErrorResult("工具 " + toolName + " 不存在");
        }
        
        if (!toolManager.isDynamicTool(toolName)) {
            return createErrorResult("工具 " + toolName + " 不是动态工具，无法删除");
        }
        
        toolManager.unregisterDynamicTool(toolName);
        
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("tool_name", toolName);
        additionalInfo.put("action", "delete");
        additionalInfo.put("success", true);
        
        return new AIToolResult("✅ 动态工具已删除: " + toolName, additionalInfo);
    }
    
    private AIToolResult handleList(Map<String, Object> parameters) {
        java.util.List<String> dynamicTools = toolManager.getDynamicTools();
        
        StringBuilder result = new StringBuilder();
        result.append("📋 当前动态工具列表:\n\n");
        
        if (dynamicTools.isEmpty()) {
            result.append("(暂无动态工具)\n");
            result.append("\n使用 create_dynamic_tool 工具来创建新工具！");
        } else {
            for (String toolName : dynamicTools) {
                AITool tool = toolManager.getTool(toolName);
                if (tool != null) {
                    result.append("🔧 ").append(toolName).append("\n");
                    result.append("   描述: ").append(tool.getDescription()).append("\n");
                    
                    Map<String, String> params = tool.getParameterDescriptions();
                    if (params != null && !params.isEmpty()) {
                        result.append("   参数: ");
                        result.append(String.join(", ", params.keySet()));
                        result.append("\n");
                    }
                    
                    if (tool instanceof DynamicAITool) {
                        String logic = ((DynamicAITool) tool).getExecutionLogic();
                        if (logic != null && !logic.isEmpty()) {
                            result.append("   逻辑: 已定义\n");
                        } else {
                            result.append("   逻辑: 未定义\n");
                        }
                    }
                    result.append("\n");
                }
            }
        }
        
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("action", "list");
        additionalInfo.put("count", dynamicTools.size());
        additionalInfo.put("tools", dynamicTools);
        
        return new AIToolResult(result.toString(), additionalInfo);
    }
    
    private String getStringParam(Map<String, Object> parameters, String key, String defaultValue) {
        if (parameters == null) return defaultValue;
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }
    
    private Map<String, String> parseParameters(Map<String, Object> parameters) {
        Map<String, String> result = new HashMap<>();
        
        if (parameters == null) return result;
        
        Object paramsObj = parameters.get("parameters");
        if (paramsObj instanceof Map) {
            @SuppressWarnings("unchecked")
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
    
    private boolean isValidToolName(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.matches("^[a-zA-Z0-9_]+$");
    }
    
    private AIToolResult createErrorResult(String message) {
        Log.w(TAG, "Error: " + message);
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("error", message);
        additionalInfo.put("success", false);
        return new AIToolResult("❌ " + message, additionalInfo);
    }
}
