package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DynamicAITool implements AITool {
    private static final String TAG = "DynamicAITool";
    
    private final String name;
    private final String description;
    private final Map<String, String> parameterDescriptions;
    private final String executionLogic;
    private final DynamicToolExecutor executor;
    private final Context context;
    
    public DynamicAITool(Context context, String name, String description, 
                         Map<String, String> parameterDescriptions,
                         String executionLogic) {
        this.context = context;
        this.name = name;
        this.description = description;
        this.parameterDescriptions = parameterDescriptions != null ? 
            parameterDescriptions : new HashMap<>();
        this.executionLogic = executionLogic;
        this.executor = new DynamicToolExecutor(context);
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
            
            return new AIToolResult(result, additionalInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing dynamic tool " + name + ": " + e.getMessage(), e);
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("error", e.getMessage());
            return new AIToolResult("动态工具执行失败: " + e.getMessage(), additionalInfo);
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
        
        return new AIToolResult(result.toString(), additionalInfo);
    }
}
