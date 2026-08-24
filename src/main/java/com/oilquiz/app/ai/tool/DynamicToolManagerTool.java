package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "create_dynamic_tool",
    description = "动态创建和管理AI工具：把重复性任务封装成可复用工具。action=create时填tool_name+description+parameters+logic(Python脚本或DSL)，创建后可被后续对话直接调用；update/delete修改或移除已有工具；list列出全部动态工具；show查看单个工具完整定义(含执行逻辑)；test用给定参数试运行不落库",
    category = "tool_management",
    aliases = {"dynamic_tool", "create_tool", "动态工具"},
    actions = {
        @Action(name = "create", description = "创建新工具"),
        @Action(name = "update", description = "更新已存在的工具"),
        @Action(name = "delete", description = "删除工具"),
        @Action(name = "list", description = "列出所有动态工具"),
        @Action(name = "show", description = "查看单个工具的完整定义（参数schema+执行逻辑）"),
        @Action(name = "test", description = "用给定参数试运行工具（不修改工具定义）")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "tool_name", type = "string", description = "工具名称(仅英文、数字和下划线)", required = false),
        @Param(name = "description", type = "string", description = "工具描述", required = false),
        @Param(name = "parameters", type = "string", description = "工具参数定义：支持三种格式——1.简单{\"参数名\":\"参数描述\"}；2.属性级{\"参数名\":{\"type\":\"string\",\"description\":\"...\",\"required\":true,\"default\":...,\"enum\":[...]}}；3.完整JSON Schema{\"type\":\"object\",\"properties\":{...},\"required\":[...]}。类型支持string/number/integer/boolean/array/object", required = false),
        @Param(name = "logic", type = "string", description = "执行逻辑脚本：支持Python脚本(自动识别，脚本内用script_args['参数名']读取工具参数，支持顶层return，print输出/返回值作为结果)或DSL命令(echo/set/if/call_tool等)", required = false),
        @Param(name = "test_params", type = "string", description = "试运行参数JSON(action=test时使用，格式{\"参数名\":值})", required = false)
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
        return "动态创建和管理AI工具：把重复性任务封装成可复用工具。action=create时填tool_name+description+parameters+logic(Python脚本或DSL)，创建后可被后续对话直接调用；update/delete修改或移除已有工具；list列出全部动态工具；show查看单个工具完整定义(含执行逻辑)；test用给定参数试运行不落库";
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型：create(创建工具)、delete(删除工具)、list(列出工具)、update(更新工具)、show(查看单个工具定义)、test(试运行)");
        params.put("tool_name", "工具名称(必填，仅英文、数字和下划线)");
        params.put("description", "工具描述");
        params.put("parameters", "工具参数定义，支持三种格式：\n" +
                "  1. 简单：{\"参数名\":\"参数描述\"}\n" +
                "  2. 属性级：{\"参数名\":{\"type\":\"string\",\"description\":\"...\",\"required\":true,\"default\":...,\"enum\":[...]}}\n" +
                "  3. 完整 JSON Schema：{\"type\":\"object\",\"properties\":{...},\"required\":[...]}\n" +
                "  类型支持：string/number/integer/boolean/array/object");
        params.put("logic", "执行逻辑脚本，支持Python脚本(自动识别，脚本内用script_args['参数名']读取工具参数，支持顶层return)或DSL命令。DSL支持的命令：\n" +
                "  - echo 文本：输出文本\n" +
                "  - print 文本：输出文本\n" +
                "  - log 文本：记录日志\n" +
                "  - set 变量=值：设置变量\n" +
                "  - if (条件) then 命令：条件判断\n" +
                "  - concat 字符串1,字符串2,...：拼接字符串\n" +
                "  - length 字符串：求长度\n" +
                "  - upper 文本：转大写\n" +
                "  - lower 文本：转小写\n" +
                "  - replace 文本,旧值,新值：替换文本\n" +
                "  - split 文本,分隔符：分割文本\n" +
                "  - join 分隔符,列表：连接列表\n" +
                "  - call_tool 工具名,{\"参数名\":\"参数值\"}：调用其他工具\n" +
                "  - 变量替换：${参数名} 或 ${变量名}");
        params.put("test_params", "试运行参数JSON(action=test时使用，格式{\"参数名\":值})");
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
                case "show":
                case "info":
                case "debug":
                    return handleShow(parameters);
                case "test":
                case "run":
                    return handleTest(parameters);
                default:
                    return createErrorResult("未知操作类型: " + action + "，支持的操作：create, update, delete, list, show, test");
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
        DynamicToolParams toolParams = parseParameters(parameters);
        if (toolParams == null) {
            return createErrorResult("parameters 参数不是合法 JSON，已取消创建");
        }
        String logic = getStringParam(parameters, "logic", null);
        
        Log.i(TAG, "Creating dynamic tool: " + toolName);
        Log.d(TAG, "Description: " + description);
        Log.d(TAG, "Parameters: " + toolParams.toJson());
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
            
            List<ParamDefinition> defs = tool.getParameterDefinitions();
            if (defs != null && !defs.isEmpty()) {
                result.append("参数: \n");
                for (ParamDefinition def : defs) {
                    result.append("  - ").append(def.getName())
                          .append(" (").append(def.getType()).append(")")
                          .append(def.isRequired() ? " [必填]" : " [可选]");
                    if (def.getDescription() != null && !def.getDescription().isEmpty()) {
                        result.append(": ").append(def.getDescription());
                    }
                    result.append("\n");
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

            return AIToolResult.success(result.toString(), additionalInfo);
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
        
        // update 只更新传入的字段：未提供的 description/logic/parameters 沿用旧值，
        // 避免"只改描述"时把执行逻辑/参数全抹掉
        AITool existing = toolManager.getTool(toolName);
        String description = parameters.containsKey("description")
                ? getStringParam(parameters, "description", null) : null;
        DynamicToolParams toolParams = parseParameters(parameters);
        if (toolParams == null) {
            return createErrorResult("parameters 参数不是合法 JSON，已取消更新");
        }
        String logic = parameters.containsKey("logic")
                ? getStringParam(parameters, "logic", null) : null;

        if (existing != null) {
            if (description == null || description.isEmpty()) {
                description = existing.getDescription();
            }
            if (toolParams.isEmpty()) {
                // 未传 parameters：沿用旧工具的结构化参数（优先）或 name→desc 回退
                if (existing instanceof DynamicAITool) {
                    DynamicToolParams old = ((DynamicAITool) existing).getDynamicParams();
                    if (old != null && !old.isEmpty()) {
                        toolParams = old;
                    } else {
                        toolParams = new DynamicToolParams(
                                DynamicAITool.toDefinitionsCompat(existing.getParameterDescriptions()));
                    }
                } else if (existing.getParameterDescriptions() != null) {
                    toolParams = new DynamicToolParams(
                            DynamicAITool.toDefinitionsCompat(existing.getParameterDescriptions()));
                }
            }
            if (logic == null) {
                if (existing instanceof DynamicAITool) {
                    logic = ((DynamicAITool) existing).getExecutionLogic();
                }
            }
        }
        if (description == null || description.isEmpty()) {
            description = "用户自定义工具";
        }

        // createAndRegisterDynamicTool 用同名注册会覆盖旧工具（即替换），
        // 创建失败时旧工具仍保留，不会丢失
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

            return AIToolResult.success("✅ 动态工具更新成功: " + toolName, additionalInfo);
        } else {
            return createErrorResult("工具更新失败（原工具已保留）");
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
        
        return AIToolResult.success("✅ 动态工具已删除: " + toolName, additionalInfo);
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
                    
                    List<ParamDefinition> defs = tool instanceof DynamicAITool
                            ? ((DynamicAITool) tool).getParameterDefinitions() : null;
                    if (defs != null && !defs.isEmpty()) {
                        result.append("   参数: ");
                        StringBuilder sb = new StringBuilder();
                        for (ParamDefinition def : defs) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(def.getName()).append("(").append(def.getType())
                              .append(def.isRequired() ? ",必填" : ",可选").append(")");
                        }
                        result.append(sb).append("\n");
                    } else {
                        Map<String, String> params = tool.getParameterDescriptions();
                        if (params != null && !params.isEmpty()) {
                            result.append("   参数: ").append(String.join(", ", params.keySet())).append("\n");
                        }
                    }
                    
                    if (tool instanceof DynamicAITool) {
                        String logic = ((DynamicAITool) tool).getExecutionLogic();
                        if (logic != null && !logic.isEmpty()) {
                            result.append("   逻辑: 已定义\n");
                        } else {
                            result.append("   逻辑: 未定义\n");
                        }
                    }
                    result.append("   提示: 用 action=show 查看完整定义，action=test 试运行\n");
                    result.append("\n");
                }
            }
        }
        
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("action", "list");
        additionalInfo.put("count", dynamicTools.size());
        additionalInfo.put("tools", dynamicTools);

        return AIToolResult.success(result.toString(), additionalInfo);
    }
    
    /** ③ 单工具调试入口：查看完整定义（参数 schema + 执行逻辑全文） */
    private AIToolResult handleShow(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("show 操作必须提供 tool_name");
        }
        AITool tool = toolManager.getTool(toolName);
        if (tool == null) {
            return createErrorResult("工具 " + toolName + " 不存在");
        }
        
        StringBuilder result = new StringBuilder();
        result.append("🔍 工具详情: ").append(toolName).append("\n\n");
        result.append("名称: ").append(tool.getName()).append("\n");
        result.append("描述: ").append(tool.getDescription()).append("\n");
        result.append("类型: ").append(toolManager.isDynamicTool(toolName)
                ? (tool instanceof com.oilquiz.app.ai.python.PythonDynamicTool ? "python(ai_create_tool)" : "java(create_dynamic_tool)")
                : "内置工具").append("\n");
        
        List<ParamDefinition> defs = tool instanceof DynamicAITool
                ? ((DynamicAITool) tool).getParameterDefinitions() : null;
        if (defs != null && !defs.isEmpty()) {
            result.append("\n参数定义 (JSON Schema):\n");
            DynamicToolParams p = ((DynamicAITool) tool).getDynamicParams();
            result.append(p != null ? p.toJson() : String.valueOf(defs));
            result.append("\n");
        } else {
            Map<String, String> params = tool.getParameterDescriptions();
            if (params != null && !params.isEmpty()) {
                result.append("\n参数:\n");
                for (Map.Entry<String, String> e : params.entrySet()) {
                    result.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
            }
        }
        
        String logic = tool instanceof DynamicAITool
                ? ((DynamicAITool) tool).getExecutionLogic()
                : (tool instanceof com.oilquiz.app.ai.python.PythonDynamicTool
                        ? ((com.oilquiz.app.ai.python.PythonDynamicTool) tool).getCode() : null);
        if (logic != null && !logic.isEmpty()) {
            result.append("\n执行逻辑:\n```\n").append(logic).append("\n```\n");
        } else {
            result.append("\n执行逻辑: (未定义)\n");
        }
        
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("action", "show");
        additionalInfo.put("tool_name", toolName);
        additionalInfo.put("success", true);
        if (logic != null) additionalInfo.put("logic", logic);

        return AIToolResult.success(result.toString(), additionalInfo);
    }
    
    /** ③ 单工具调试入口：用给定参数试运行（不修改工具定义） */
    private AIToolResult handleTest(Map<String, Object> parameters) {
        String toolName = getStringParam(parameters, "tool_name", null);
        if (toolName == null || toolName.isEmpty()) {
            return createErrorResult("test 操作必须提供 tool_name");
        }
        AITool tool = toolManager.getTool(toolName);
        if (tool == null) {
            return createErrorResult("工具 " + toolName + " 不存在");
        }
        
        Map<String, Object> testParams = new HashMap<>();
        Object raw = parameters.get("test_params");
        if (raw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            testParams.putAll(m);
        } else if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (!s.isEmpty()) {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(s);
                    java.util.Iterator<String> keys = jo.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        testParams.put(key, jo.opt(key));
                    }
                } catch (Exception e) {
                    return createErrorResult("test_params 不是合法 JSON: " + e.getMessage());
                }
            }
        } else if (raw != null) {
            return createErrorResult("test_params 参数类型不支持（应为对象或JSON字符串）");
        }
        // 兼容直接传参（不包 test_params）
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            String k = e.getKey();
            if (!"action".equals(k) && !"tool_name".equals(k) && !"test_params".equals(k)) {
                testParams.putIfAbsent(k, e.getValue());
            }
        }
        
        Log.i(TAG, "Testing dynamic tool: " + toolName + " params=" + testParams);
        AIToolResult r;
        if (tool instanceof DynamicAITool) {
            r = ((DynamicAITool) tool).executeTest(testParams);
        } else {
            r = tool.execute(testParams);
        }
        if (r == null) {
            return createErrorResult("试运行无结果");
        }
        
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("action", "test");
        additionalInfo.put("tool_name", toolName);
        additionalInfo.put("success", r.isSuccess());
        additionalInfo.put("test_params", testParams);
        Object output = r.getResult() != null ? r.getResult() : "(无输出)";
        return AIToolResult.success(
                (r.isSuccess() ? "🧪 试运行成功:\n\n" : "🧪 试运行失败:\n\n") + output, additionalInfo);
    }
    
    private String getStringParam(Map<String, Object> parameters, String key, String defaultValue) {
        if (parameters == null) return defaultValue;
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }
    
    /**
     * 解析 parameters 参数为标准 function schema。
     * 非法 JSON 返回 null（由调用方报错），空/缺失返回空结构。
     */
    private DynamicToolParams parseParameters(Map<String, Object> parameters) {
        if (parameters == null || !parameters.containsKey("parameters")) {
            return new DynamicToolParams(null);
        }
        return DynamicToolParams.parse(parameters.get("parameters"));
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
        return AIToolResult.fail("❌ " + message, additionalInfo);
    }
}
