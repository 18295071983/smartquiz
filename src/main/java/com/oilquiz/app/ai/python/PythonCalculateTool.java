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
    description = "使用Python进行数学计算",
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
        super("python_calculate", "使用Python进行数学计算");
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
            // 数学计算场景给短超时（默认30s对纯计算过长；防死循环残留线程）
            PythonToolManager.ExecutionResult result = toolManager.executeCode(code, null, 10);
            
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
            "import ast\n" +
            "\n" +
            "task = %s\n" +
            "expression = %s\n" +
            "\n" +
            "# ===== AST 白名单安全检查（防沙箱绕过/内存耗尽） =====\n" +
            "ALLOWED_MODULES = {'math', 'statistics'}\n" +
            "ALLOWED_FUNCS = {'abs', 'pow', 'round', 'int', 'float', 'min', 'max', 'sum', 'len', 'random'}\n" +
            "def check_expression(expr):\n" +
            "    tree = ast.parse(expr, mode='eval')\n" +
            "    for node in ast.walk(tree):\n" +
            "        if isinstance(node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp,\n" +
            "                            ast.Lambda, ast.Subscript, ast.Slice, ast.Starred, ast.Yield,\n" +
            "                            ast.Await)):\n" +
            "            raise ValueError('不允许的语法: ' + type(node).__name__)\n" +
            "        if isinstance(node, ast.Attribute):\n" +
            "            v = node.value\n" +
            "            if not (isinstance(v, ast.Name) and v.id in ALLOWED_MODULES):\n" +
            "                raise ValueError('不允许的属性访问')\n" +
            "        elif isinstance(node, ast.Call):\n" +
            "            f = node.func\n" +
            "            if isinstance(f, ast.Name) and f.id not in ALLOWED_FUNCS:\n" +
            "                raise ValueError('不允许的函数: ' + f.id)\n" +
            "        elif isinstance(node, ast.Name):\n" +
            "            if node.id not in ALLOWED_MODULES and node.id not in ALLOWED_FUNCS:\n" +
            "                raise ValueError('不允许的变量/函数名: ' + node.id)\n" +
            "    return tree\n" +
            "\n" +
            "print(f'任务: {task}')\n" +
            "print(f'表达式: {expression}')\n" +
            "\n" +
            "safe_globals = {\n" +
            "    '__builtins__': {},\n" +
            "    'math': math,\n" +
            "    'statistics': statistics,\n" +
            "    'random': random,\n" +
            "    'abs': abs, 'pow': pow, 'round': round,\n" +
            "    'int': int, 'float': float, 'min': min, 'max': max, 'sum': sum, 'len': len,\n" +
            "}\n" +
            "\n" +
            "try:\n" +
            "    check_expression(expression)\n" +
            "    result = eval(expression, safe_globals, {})\n" +
            "    if isinstance(result, (int, float)):\n" +
            "        print(f'计算结果: {result}')\n" +
            "    else:\n" +
            "        print(f'计算结果: {result}')\n" +
            "except Exception as e:\n" +
            "    print('计算错误: ' + str(e))",
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
        
        // 脚本内已把表达式/安全检查错误标记为 "计算错误:" 前缀（正常退出0），这里转成失败结果
        if (result.success && result.stdout != null && result.stdout.contains("计算错误:")) {
            int idx = result.stdout.indexOf("计算错误:");
            String errMsg = result.stdout.substring(idx + "计算错误:".length()).trim();
            additionalInfo.put("error", errMsg);
            return new AIToolResult("计算失败: " + errMsg, additionalInfo, false);
        }
        
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
