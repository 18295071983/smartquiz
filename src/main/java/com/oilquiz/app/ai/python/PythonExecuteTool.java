package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.BaseAITool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;

@Tool(
    value = "python_execute",
    description = "执行 Python 代码，可以进行数学计算、数据分析、网络请求、文本处理、加密等操作",
    category = "python",
    aliases = {"python", "run_python", "python_code"},
    actions = {
        @Action(name = "execute_code", description = "执行Python代码"),
        @Action(name = "execute_task", description = "执行任务描述")
    },
    params = {
        @Param(name = "code", type = "string", description = "Python代码", required = false),
        @Param(name = "task", type = "string", description = "任务描述", required = false),
        @Param(name = "context", type = "object", description = "上下文数据", required = false)
    }
)
public class PythonExecuteTool extends BaseAITool {
    private static final String TAG = "PythonExecuteTool";
    private final Context context;
    private final PythonToolManager toolManager;
    
    public PythonExecuteTool(Context context) {
        super("python_execute", "执行 Python 代码，可以进行数学计算、数据分析、网络请求、文本处理、加密等操作");
        this.context = context.getApplicationContext();
        this.toolManager = PythonToolManager.getInstance(context);
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Log.i(TAG, "Executing Python code");
        
        try {
            if (!toolManager.isInitialized()) {
                if (!toolManager.initialize()) {
                    return AIToolResult.fail("Python 工具初始化失败，请检查 Chaquopy 配置");
                }
            }
            
            String code = (String) parameters.get("code");
            String task = (String) parameters.get("task");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> contextData = (Map<String, Object>) parameters.get("context");
            
            PythonToolManager.ExecutionResult result;
            
            if (code != null && !code.isEmpty()) {
                String safeCode = buildSafeCode(code);
                Log.d(TAG, "Executing code: " + safeCode.substring(0, Math.min(200, safeCode.length())));
                result = toolManager.processTask(safeCode, contextData);
            } else if (task != null && !task.isEmpty()) {
                Log.d(TAG, "Processing task: " + task);
                result = toolManager.processTask(task, contextData);
            } else {
                return AIToolResult.fail("请提供 code（Python 代码）或 task（任务描述）参数");
            }
            
            return formatResult(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing Python tool: " + e.getMessage(), e);
            return AIToolResult.fail("执行失败: " + e.getMessage());
        }
    }
    
    private String buildSafeCode(String code) {
        StringBuilder sb = new StringBuilder();
        sb.append("# -*- coding: utf-8 -*-\n");
        sb.append("import sys\n");
        sb.append("sys.path.insert(0, '.')\n\n");
        sb.append(code);
        return sb.toString();
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "string, 要执行的 Python 代码（可选）");
        params.put("task", "string, 任务描述，如 '计算 3+5*2'、'分析数据 [1,2,3,4,5]'（可选）");
        params.put("context", "object, 上下文数据（可选）");
        return params;
    }
    
    private AIToolResult formatResult(PythonToolManager.ExecutionResult result) {
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("attempts", result.attempts);
        additionalInfo.put("success", result.success);
        
        if (result.code != null && !result.code.isEmpty()) {
            additionalInfo.put("code", result.code);
        }
        
        if (!result.fixes.isEmpty()) {
            additionalInfo.put("fixes_applied", result.fixes.size());
        }
        
        if (result.success) {
            StringBuilder output = new StringBuilder();
            output.append("执行成功\n\n");
            
            if (result.stdout != null && !result.stdout.isEmpty()) {
                output.append("=== 输出 ===\n");
                output.append(result.stdout).append("\n\n");
            }
            
            if (result.result != null && !result.result.isEmpty()) {
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
            
            return new AIToolResult(output.toString(), additionalInfo, true);
        } else {
            StringBuilder output = new StringBuilder();
            output.append("执行失败\n\n");
            
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
            
            return new AIToolResult(output.toString(), additionalInfo, false);
        }
    }
}
