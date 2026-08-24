package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynamicAITool implements AITool {
    private static final String TAG = "DynamicAITool";
    
    private final String name;
    private final String description;
    /** 结构化参数定义（标准 function schema），可为空 */
    private final DynamicToolParams params;
    /** 兼容旧数据：无结构化参数时的 name→description 回退 */
    private final Map<String, String> legacyParameterDescriptions;
    private final String executionLogic;
    private final DynamicToolExecutor executor;
    private final Context context;
    
    public DynamicAITool(Context context, String name, String description, 
                         Map<String, String> parameterDescriptions,
                         String executionLogic) {
        this(context, name, description,
                parameterDescriptions != null && !parameterDescriptions.isEmpty()
                        ? new DynamicToolParams(toDefinitions(parameterDescriptions))
                        : new DynamicToolParams(null),
                executionLogic);
        this.legacyParameterDescriptions.putAll(
                parameterDescriptions != null ? parameterDescriptions : new HashMap<>());
    }

    /** 结构化参数构造（create_dynamic_tool 修复后的主路径） */
    public DynamicAITool(Context context, String name, String description,
                         DynamicToolParams params,
                         String executionLogic) {
        this.context = context;
        this.name = name;
        this.description = description;
        this.params = params != null ? params : new DynamicToolParams(null);
        this.legacyParameterDescriptions = new HashMap<>();
        this.executionLogic = executionLogic;
        this.executor = new DynamicToolExecutor(context);
    }

    /** Map<String,String>（name→desc）→ ParamDefinition 列表（旧数据回退：全部 string、非必填） */
    private static List<ParamDefinition> toDefinitions(Map<String, String> map) {
        return toDefinitionsCompat(map);
    }

    /** 供 DynamicToolManagerTool 等外部类复用：name→desc 回退为全 string、非必填 */
    public static List<ParamDefinition> toDefinitionsCompat(Map<String, String> map) {
        java.util.List<ParamDefinition> defs = new java.util.ArrayList<>();
        if (map != null) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                defs.add(new ParamDefinition(e.getKey(), "string", e.getValue(), false));
            }
        }
        return defs;
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
        if (!params.isEmpty()) {
            return params.toDisplayMap();
        }
        return new HashMap<>(legacyParameterDescriptions);
    }
    
    /** 标准 function schema 参数列表（供 getToolDefinition/OpenAI 工具定义使用） */
    public List<ParamDefinition> getParameterDefinitions() {
        if (!params.isEmpty()) {
            return params.getDefinitions();
        }
        return toDefinitions(legacyParameterDescriptions);
    }

    /** 结构化参数（供持久化/调试入口使用） */
    public DynamicToolParams getDynamicParams() {
        return params;
    }
    
    public String getExecutionLogic() {
        return executionLogic;
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Log.i(TAG, "Executing dynamic tool: " + name);
            Log.d(TAG, "Parameters: " + parameters);
            Log.d(TAG, "Logic: " + (executionLogic != null ? executionLogic.substring(0, Math.min(200, executionLogic.length())) : "null"));
            
            if (executionLogic == null || executionLogic.trim().isEmpty()) {
                return executeDefaultLogic(parameters);
            }
            
            String result = executor.execute(executionLogic, parameters);

            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("isDynamic", true);
            additionalInfo.put("parameters", parameters);

            return AIToolResult.success(result, additionalInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing dynamic tool " + name + ": " + e.getMessage(), e);
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("error", e.getMessage());
            return new AIToolResult("动态工具执行失败: " + e.getMessage(), additionalInfo);
        }
    }

    /**
     * 试运行入口（create_dynamic_tool action=test 用）：
     * 用传入的测试参数执行逻辑，不改变工具本身。与 execute 逻辑一致，
     * 便于在创建/更新前验证脚本是否正确。
     */
    public AIToolResult executeTest(Map<String, Object> parameters) {
        if (executionLogic == null || executionLogic.trim().isEmpty()) {
            return AIToolResult.fail("工具未定义执行逻辑，无法试运行", null);
        }
        try {
            // 独立 executor：避免污染实例级 variables（DSL set 变量）
            DynamicToolExecutor testExecutor = new DynamicToolExecutor(context);
            String result = testExecutor.execute(executionLogic, parameters);
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("isDynamic", true);
            additionalInfo.put("isTest", true);
            additionalInfo.put("parameters", parameters);
            return AIToolResult.success(result, additionalInfo);
        } catch (Exception e) {
            Log.e(TAG, "Test execution failed for " + name + ": " + e.getMessage(), e);
            return new AIToolResult("试运行失败: " + e.getMessage(), null);
        }
    }
    
    private AIToolResult executeDefaultLogic(Map<String, Object> parameters) {
        StringBuilder result = new StringBuilder();
        result.append("动态工具 ").append(name).append(" 执行结果：\n");
        result.append("参数信息：\n");
        
        if (parameters != null && !parameters.isEmpty()) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                result.append("  - ").append(entry.getKey()).append(": ")
                      .append(entry.getValue()).append("\n");
            }
        } else {
            result.append("  (无参数)\n");
        }
        
        result.append("\n提示：该工具尚未定义具体执行逻辑，请使用create_dynamic_tool工具定义执行逻辑。");
        
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("toolName", name);
        additionalInfo.put("isDynamic", true);
        additionalInfo.put("parameters", parameters);
        
        return AIToolResult.success(result.toString(), additionalInfo);
    }
}
