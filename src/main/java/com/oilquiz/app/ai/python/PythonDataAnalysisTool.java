package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.BaseAITool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "python_analyze_data",
    description = "使用 Python 进行数据分析，支持统计、汇总、数据处理等",
    category = "python",
    aliases = {"data_analysis", "analyze", "数据分析"},
    actions = {
        @Action(name = "statistics", description = "统计分析"),
        @Action(name = "summary", description = "数据汇总"),
        @Action(name = "process", description = "数据处理")
    },
    params = {
        @Param(name = "data", type = "list", description = "数据数组", required = true),
        @Param(name = "task", type = "string", description = "任务描述", required = false)
    }
)
public class PythonDataAnalysisTool extends BaseAITool {
    private static final String TAG = "PythonDataAnalysisTool";
    private final Context context;
    private final PythonToolManager toolManager;
    
    public PythonDataAnalysisTool(Context context) {
        super("python_analyze_data", "使用 Python 进行数据分析，支持统计、汇总、数据处理等");
        this.context = context.getApplicationContext();
        this.toolManager = PythonToolManager.getInstance(context);
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Log.i(TAG, "Executing Python data analysis");
        
        try {
            if (!toolManager.isInitialized()) {
                if (!toolManager.initialize()) {
                    return AIToolResult.fail("Python 工具初始化失败，请检查 Chaquopy 配置");
                }
            }
            
            @SuppressWarnings("unchecked")
            List<Object> dataList = (List<Object>) parameters.get("data");
            String task = (String) parameters.get("task");
            
            if (dataList == null || dataList.isEmpty()) {
                return AIToolResult.fail("请提供 data 参数（数据数组）");
            }
            
            Map<String, Object> contextData = new HashMap<>();
            contextData.put("data", dataList);
            
            String taskDesc = task != null ? task : "数据分析";
            String code = buildAnalysisCode(dataList, taskDesc);
            
            PythonToolManager.ExecutionResult result = toolManager.processTask(code, contextData);
            
            return formatResult(result, dataList.size());
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing Python data analysis: " + e.getMessage(), e);
            return AIToolResult.fail("分析失败: " + e.getMessage());
        }
    }
    
    private String buildAnalysisCode(List<Object> dataList, String task) {
        String jsonData = listToJson(dataList);
        
        return String.format(
            "# -*- coding: utf-8 -*-\n" +
            "import json\n" +
            "import statistics\n" +
            "from collections import Counter\n" +
            "\n" +
            "task = %s\n" +
            "data = %s\n" +
            "\n" +
            "print(f'任务: {task}')\n" +
            "print(f'数据量: {len(data)}')\n" +
            "\n" +
            "result = {}\n" +
            "result['count'] = len(data)\n" +
            "\n" +
            "if all(isinstance(x, (int, float)) for x in data):\n" +
            "    nums = [float(x) for x in data]\n" +
            "    result['mean'] = statistics.mean(nums)\n" +
            "    result['median'] = statistics.median(nums)\n" +
            "    result['max'] = max(nums)\n" +
            "    result['min'] = min(nums)\n" +
            "    result['sum'] = sum(nums)\n" +
            "    if len(nums) > 1:\n" +
            "        result['stdev'] = statistics.stdev(nums)\n" +
            "        result['variance'] = statistics.variance(nums)\n" +
            "    print('统计信息:')\n" +
            "    print(f'  平均值: {result[\"mean\"]}')\n" +
            "    print(f'  中位数: {result[\"median\"]}')\n" +
            "    print(f'  最大值: {result[\"max\"]}')\n" +
            "    print(f'  最小值: {result[\"min\"]}')\n" +
            "    print(f'  总和: {result[\"sum\"]}')\n" +
            "    if 'stdev' in result:\n" +
            "        print(f'  标准差: {result[\"stdev\"]}')\n" +
            "else:\n" +
            "    counter = Counter(data)\n" +
            "    result['unique_count'] = len(counter)\n" +
            "    result['frequency'] = dict(counter.most_common(10))\n" +
            "    print('频率统计:')\n" +
            "    for item, count in counter.most_common(10):\n" +
            "        print(f'  {item}: {count}')\n" +
            "\n" +
            "print()\n" +
            "print(f'分析完成')",
            quoteString(task),
            jsonData
        );
    }
    
    private String listToJson(List<Object> list) {
        JSONArray arr = new JSONArray();
        for (Object item : list) {
            arr.put(item);
        }
        return arr.toString();
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
        params.put("data", "array, 数据数组，如 [1,2,3,4,5] 或 ['a','b','a','c']");
        params.put("task", "string, 分析任务描述（可选）");
        return params;
    }
    
    private AIToolResult formatResult(PythonToolManager.ExecutionResult result, int dataCount) {
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("data_count", dataCount);
        additionalInfo.put("attempts", result.attempts);
        
        if (result.success) {
            StringBuilder output = new StringBuilder();
            output.append("数据分析完成\n\n");
            
            if (result.stdout != null && !result.stdout.isEmpty()) {
                output.append(result.stdout);
            }
            
            additionalInfo.put("stdout", result.stdout);
            additionalInfo.put("result", result.result);
            
            return new AIToolResult(output.toString(), additionalInfo, true);
        } else {
            StringBuilder output = new StringBuilder();
            output.append("数据分析失败\n\n");
            
            if (result.error != null) {
                output.append("错误: ").append(result.error).append("\n");
            }
            
            return new AIToolResult(output.toString(), additionalInfo, false);
        }
    }
}
