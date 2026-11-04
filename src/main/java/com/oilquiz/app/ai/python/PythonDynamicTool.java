package com.oilquiz.app.ai.python;

import android.content.Context;

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

    /**
     * 与执行侧同一个预算（{@link com.oilquiz.app.ai.tool.DynamicToolBudget}）：
     * 脚本时限 + 收尾余量。不申报的话调用方按自己的默认档（在线主路径 30s）等，
     * 而 Python 引擎那边是另一个数——到点外层放弃、脚本还在跑。
     */
    @Override
    public long executionTimeoutMs(Map<String, Object> args) {
        return com.oilquiz.app.ai.tool.DynamicToolBudget.callerWaitMs();
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            // ai_logs 可追溯 + 不整包打参数值（值里常带用户内容/路径）
            com.oilquiz.app.util.AILogger.i(TAG, "执行 Python 动态工具 " + name
                    + " 参数=" + com.oilquiz.app.ai.tool.ScriptArgs.keyNames(parameters));

            String fullCode = com.oilquiz.app.ai.tool.ScriptArgs.fullPythonScript(code, parameters);
            int timeoutSeconds = com.oilquiz.app.ai.tool.DynamicToolBudget.engineTimeoutSeconds(
                    com.oilquiz.app.ai.tool.DynamicToolBudget.SCRIPT_BUDGET_MS,
                    com.oilquiz.app.ai.tool.DynamicToolBudget.PYTHON_ENGINE_MAX_SECONDS);

            PythonToolManager.ExecutionResult result = toolManager.executeCode(fullCode, null, timeoutSeconds);
            
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
            com.oilquiz.app.util.AILogger.e(TAG, "Python 动态工具 " + name + " 执行失败: "
                    + e.getMessage(), e);
            
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("error", e.getMessage());
            additionalInfo.put("isDynamic", true);
            
            // 异常分支同样必须是失败——这条链上"成功/失败"只由构造函数选中的重载决定，
            // 上面 result 失败那处刚补过一次，这里漏了就会以"工具正常返回了一段报错文本"的面目出现。
            return new AIToolResult("动态工具执行失败: " + e.getMessage(), additionalInfo, false);
        }
    }
}

