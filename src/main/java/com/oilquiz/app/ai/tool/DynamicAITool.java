package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynamicAITool implements AITool, com.oilquiz.app.ai.tool.openai.StructuredParamTool {
    private static final String TAG = "DynamicAITool";
    
    private final String name;
    private final String description;
    /** 结构化参数定义（标准 function schema），可为空 */
    private final DynamicToolParams params;
    /** 兼容旧数据：无结构化参数时的 name→description 回退 */
    private final Map<String, String> legacyParameterDescriptions;
    private final String executionLogic;
    /** 执行器无实例状态（变量表已改成"每次调用"的局部状态），可以共用一个 */
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
    
    /**
     * 动态工具的等待预算由脚本时限决定，不是调用方的默认档。
     *
     * <p>以前这里什么都没有：{@code OnlineToolManager} 等主路径按 30s 等，而脚本本身
     * 可能合法地跑更久（Python 引擎默认就 30s，DSL 里还能嵌套调工具）。到点后外层报一句
     * 通用"超时"，脚本却继续跑完、副作用已经发生、结果没人读——模型只能盲目重试。
     * 申报值与执行侧同源（{@link DynamicToolBudget}），两边不会各说各话。
     */
    @Override
    public long executionTimeoutMs(Map<String, Object> args) {
        return DynamicToolBudget.callerWaitMs();
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (executionLogic == null || executionLogic.trim().isEmpty()) {
                return executeDefaultLogic(parameters);
            }
            // 走 AILogger：动态工具的执行与失败要进 ai_logs（Log.* 只到 logcat，
            // 事后翻不到"这个工具哪一轮跑过、为什么失败"）。参数只记键名与个数，
            // 不整包打值——动态工具的参数里常带用户内容/路径。
            AILogger.i(TAG, "执行动态工具 " + name + " 参数=" + keyNames(parameters));
            DynamicToolExecutor.Outcome outcome = executor.run(executionLogic, parameters);

            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("isDynamic", true);
            additionalInfo.put("engine", outcome.engine);
            additionalInfo.put("parameters", parameters);

            if (!outcome.success) {
                // 引擎失败必须是失败：旧版把 "Python 执行失败: …" 这类文本当成功输出交给模型，
                // 模型会把它当正常结果复述给用户（同一条假成功链的另一半）。
                AILogger.e(TAG, "动态工具 " + name + " 执行失败（" + outcome.engine + "）: "
                        + outcome.error);
                additionalInfo.put("error", outcome.error);
                return AIToolResult.fail(
                        "动态工具 " + name + " 执行失败（引擎 " + outcome.engine + "）："
                                + outcome.error
                                + (outcome.output != null && !outcome.output.isEmpty()
                                        ? "\n已跑出的部分结果：\n" + outcome.output : "")
                                + "\n请用 create_dynamic_tool(action=update, tool_name=" + name
                                + ", logic=…) 修正脚本，或改用已注册的内置工具。",
                        additionalInfo);
            }

            return AIToolResult.success(outcome.output, additionalInfo);
            
        } catch (Exception e) {
            AILogger.e(TAG, "动态工具 " + name + " 执行失败: " + e.getMessage(), e);
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("error", e.getMessage());
            // 这里必须是失败：两参构造 AIToolResult(String, Map) 就是失败结果，
            // 但同类 AIToolResult(Object, Map) 会把成功默认打开——改文案时别把变量声明成 Object。
            return new AIToolResult("动态工具执行失败: " + e.getMessage(), additionalInfo);
        }
    }

    /** 参数摘要（键名 + 个数），用于日志；不打印值。 */
    private static String keyNames(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return "0 个";
        StringBuilder sb = new StringBuilder(String.valueOf(parameters.size())).append(" 个 [");
        boolean first = true;
        for (String k : parameters.keySet()) {
            if (!first) sb.append(',');
            sb.append(k);
            first = false;
        }
        return sb.append(']').toString();
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
            // 执行器没有实例状态（变量表是每次调用的局部量），所以试运行与正式调用同一条路：
            // 试运行的结论必须能代表真实调用，两边不该各搭一套
            DynamicToolExecutor.Outcome outcome = executor.run(executionLogic, parameters);
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", name);
            additionalInfo.put("isDynamic", true);
            additionalInfo.put("isTest", true);
            additionalInfo.put("engine", outcome.engine);
            additionalInfo.put("parameters", parameters);
            if (!outcome.success) {
                return AIToolResult.fail("试运行失败（引擎 " + outcome.engine + "）：" + outcome.error
                                + (outcome.output != null && !outcome.output.isEmpty()
                                        ? "\n已跑出的部分结果：\n" + outcome.output : ""),
                        additionalInfo);
            }
            return AIToolResult.success(outcome.output, additionalInfo);
        } catch (Exception e) {
            AILogger.e(TAG, "动态工具 " + name + " 试运行失败: " + e.getMessage(), e);
            return new AIToolResult("试运行失败: " + e.getMessage(), null);
        }
    }
    
    /**
     * 没有执行逻辑时的出口：<b>失败，不 echo</b>。
     *
     * <p>旧实现返回"执行结果：参数 … 提示：尚未定义执行逻辑"这种<b>成功</b>报文。
     * 一个只有名字和参数、没有逻辑的半成品工具（模型常分两步建：先建壳再补逻辑，
     * 或者中途失败）于是每次被调用都"成功"一次并把它自己的参数回显给模型——
     * 模型据此认为工具是好的，继续用，用户拿到的是回显不是答案。这类假成功比明确报错难查得多。</p>
     */
    private AIToolResult executeDefaultLogic(Map<String, Object> parameters) {
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("toolName", name);
        additionalInfo.put("isDynamic", true);
        additionalInfo.put("not_configured", true);
        AILogger.e(TAG, "动态工具 " + name + " 没有执行逻辑，调用按失败返回");
        return AIToolResult.fail("动态工具 " + name + " 还没有定义执行逻辑，不能返回结果。"
                + "请先用 create_dynamic_tool(action=update, tool_name=" + name
                + ", logic=<Python/JS/DSL>) 补齐，或直接改用已有的内置工具；"
                + "不要把这个工具当作可用。", additionalInfo);
    }
}
