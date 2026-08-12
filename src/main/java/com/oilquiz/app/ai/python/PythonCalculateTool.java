package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.BaseAITool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "python_calculate",
    description = "使用 Python 进行数学计算，支持复杂表达式、数学函数等",
    category = "python",
    aliases = {"calculate", "math", "计算"},
    actions = {
        @Action(name = "evaluate", description = "计算数学表达式"),
        @Action(name = "solve", description = "求解数学问题")
    },
    params = {
        @Param(name = "expression", type = "string", description = "数学表达式", required = true),
        @Param(name = "task", type = "string", description = "任务描述", required = false)
    }
)
public class PythonCalculateTool extends BaseAITool {
    private static final String TAG = "PythonCalculateTool";
    private final Context context;
    private final PythonToolManager toolManager;
    
    public PythonCalculateTool(Context context) {
        super("python_calculate", "使用 Python 进行数学计算，支持复杂表达式、数学函数等");
        this.context = context.getApplicationContext();
        this.toolManager = PythonToolManager.getInstance(context);
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Log.i(TAG, "Executing Python calculation");
        
        try {
            if (!toolManager.isInitialized()) {
                if (!toolManager.initialize()) {
                    return AIToolResult.fail("Python 工具初始化失败，请检查 Chaquopy 配置");
                }
            }
            
            String expression = (String) parameters.get("expression");
            String task = (String) parameters.get("task");
            
            if (expression == null || expression.isEmpty()) {
                return AIToolResult.fail("请提供 expression 参数（数学表达式）");
            }
            
            String code = buildCalculationCode(expression, task);
            PythonToolManager.ExecutionResult result = toolManager.executeCode(code, null);
            
            return formatResult(result, expression);
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing Python calculation: " + e.getMessage(), e);
            return AIToolResult.fail("计算失败: " + e.getMessage());
        }
    }
    
    private String buildCalculationCode(String expression, String task) {
        String taskDesc = task != null ? task : "数学计算";
        
        return String.format(
            "# -*- coding: utf-8 -*-\n" +
            "import math\n" +
            "import statistics\n" +
            "import random\n" +
            "\n" +
            "task = %s\n" +
            "expression = %s\n" +
            "\n" +
            "print(f'任务: {task}')\n" +
            "print(f'表达式: {expression}')\n" +
            "\n" +
            "safe_globals = {\n" +
            "    '__builtins__': {},\n" +
            "    'math': math,\n" +
            "    'statistics': statistics,\n" +
            "    'abs': abs,\n" +
            "    'pow': pow,\n" +
            "    'round': round,\n" +
            "    'int': int,\n" +
            "    'float': float,\n" +
            "    'min': min,\n" +
            "    'max': max,\n" +
            "    'sum': sum,\n" +
            "    'len': len,\n" +
            "    'range': range,\n" +
            "    'list': list,\n" +
            "}\n" +
            "\n" +
            "try:\n" +
            "    result = eval(expression, safe_globals, {})\n" +
            "    print(f'计算结果: {result}')\n" +
            "except Exception as e:\n" +
            "    print(f'计算错误: {e}')\n" +
            "    result = str(e)",
            quoteString(taskDesc),
            quoteString(expression)
        );
    }
    
    private String quoteString(String s) {
        if (s == null) return "None";
        return "\"" + s.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t") + "\"";
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("expression", "string, 数学表达式，如 '3+5*2', 'math.sqrt(16)', 'math.sin(math.pi/2)'");
        params.put("task", "string, 任务描述（可选）");
        return params;
    }
    
    private AIToolResult formatResult(PythonToolManager.ExecutionResult result, String expression) {
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("expression", expression);
        additionalInfo.put("attempts", result.attempts);
        
        if (result.success) {
            StringBuilder output = new StringBuilder();
            output.append("计算成功\n\n");
            
            if (result.stdout != null && !result.stdout.isEmpty()) {
                output.append("=== 输出 ===\n");
                output.append(result.stdout).append("\n");
            } else if (result.result != null) {
                output.append("结果: ").append(result.result).append("\n");
            }
            
            additionalInfo.put("stdout", result.stdout);
            additionalInfo.put("result", result.result);
            
            return new AIToolResult(output.toString(), additionalInfo, true);
        } else {
            StringBuilder output = new StringBuilder();
            output.append("计算失败\n\n");
            
            if (result.error != null) {
                output.append("错误: ").append(result.error).append("\n");
            }
            if (result.stderr != null && !result.stderr.isEmpty()) {
                output.append("详细信息: ").append(result.stderr).append("\n");
            }
            
            return new AIToolResult(output.toString(), additionalInfo, false);
        }
    }
}
