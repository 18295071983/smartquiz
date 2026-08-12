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

import java.util.ArrayList;
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
            
            Object dataObj = parameters.get("data");
            String task = (String) parameters.get("task");
            
            // 支持多种数据格式：List、JSON 字符串、单个值
            List<Object> dataList;
            if (dataObj instanceof List) {
                dataList = (List<Object>) dataObj;
            } else if (dataObj instanceof String) {
                String dataStr = ((String) dataObj).trim();
                dataList = parseJsonArray(dataStr);
                if (dataList == null) {
                    // 尝试作为单个值处理
                    dataList = new ArrayList<>();
                    try {
                        dataList.add(Integer.parseInt(dataStr));
                    } catch (NumberFormatException e1) {
                        try {
                            dataList.add(Double.parseDouble(dataStr));
                        } catch (NumberFormatException e2) {
                            dataList.add(dataStr);
                        }
                    }
                }
            } else if (dataObj instanceof Number) {
                dataList = new ArrayList<>();
                dataList.add(dataObj);
            } else {
                return AIToolResult.fail("data 参数格式不支持，请提供数组或 JSON 字符串");
            }
            
            if (dataList.isEmpty()) {
                return AIToolResult.fail("请提供 data 参数（数据数组），如 [1,2,3,4,5]");
            }
            
            Map<String, Object> contextData = new HashMap<>();
            contextData.put("data", dataList);
            
            String taskDesc = task != null ? task : "数据分析";
            String code = buildAnalysisCode(dataList, taskDesc);
            
            PythonToolManager.ExecutionResult result = toolManager.executeCode(code, contextData);
            
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
            "print('任务: ' + str(task))\n" +
            "print('数据量: ' + str(len(data)))\n" +
            "\n" +
            "result = {}\n" +
            "result['count'] = len(data)\n" +
            "\n" +
            "if len(data) > 0 and isinstance(data[0], dict):\n" +
            "    # dict列表：提取数值字段分析\n" +
            "    numeric_fields = {}\n" +
            "    for key in data[0].keys():\n" +
            "        vals = []\n" +
            "        for row in data:\n" +
            "            v = row.get(key)\n" +
            "            if isinstance(v, (int, float)):\n" +
            "                vals.append(float(v))\n" +
            "        if len(vals) == len(data) and len(vals) > 0:\n" +
            "            numeric_fields[key] = vals\n" +
            "    if numeric_fields:\n" +
            "        print('数值字段统计:')\n" +
            "        for field, nums in numeric_fields.items():\n" +
            "            info = {\n" +
            "                'count': len(nums),\n" +
            "                'mean': round(statistics.mean(nums), 2),\n" +
            "                'median': statistics.median(nums),\n" +
            "                'max': max(nums),\n" +
            "                'min': min(nums),\n" +
            "                'sum': sum(nums)\n" +
            "            }\n" +
            "            if len(nums) > 1:\n" +
            "                info['stdev'] = round(statistics.stdev(nums), 2)\n" +
            "            result[field] = info\n" +
            "            print('  [' + field + ']')\n" +
            "            print('    平均值=' + str(info['mean']) + ' 中位数=' + str(info['median']))\n" +
            "            print('    最大=' + str(info['max']) + ' 最小=' + str(info['min']) + ' 总和=' + str(info['sum']))\n" +
            "            if 'stdev' in info:\n" +
            "                print('    标准差=' + str(info['stdev']))\n" +
            "    # 非数值字段展示\n" +
            "    str_fields = [k for k in data[0].keys() if k not in numeric_fields]\n" +
            "    if str_fields:\n" +
            "        print('文本字段:')\n" +
            "        for field in str_fields:\n" +
            "            vals = [str(row.get(field, '')) for row in data]\n" +
            "            c = Counter(vals)\n" +
            "            unique = len(c)\n" +
            "            print('  [' + field + '] 唯一值: ' + str(unique))\n" +
            "            for val, cnt in c.most_common(5):\n" +
            "                print('    ' + str(val) + ': ' + str(cnt))\n" +
            "else:\n" +
            "    if all(isinstance(x, (int, float)) for x in data):\n" +
            "        nums = [float(x) for x in data]\n" +
            "        result['mean'] = statistics.mean(nums)\n" +
            "        result['median'] = statistics.median(nums)\n" +
            "        result['max'] = max(nums)\n" +
            "        result['min'] = min(nums)\n" +
            "        result['sum'] = sum(nums)\n" +
            "        if len(nums) > 1:\n" +
            "            result['stdev'] = statistics.stdev(nums)\n" +
            "            result['variance'] = statistics.variance(nums)\n" +
            "        print('统计信息:')\n" +
            "        print('  平均值: ' + str(round(result['mean'], 2)))\n" +
            "        print('  中位数: ' + str(result['median']))\n" +
            "        print('  最大值: ' + str(result['max']))\n" +
            "        print('  最小值: ' + str(result['min']))\n" +
            "        print('  总和: ' + str(result['sum']))\n" +
            "        if 'stdev' in result:\n" +
            "            print('  标准差: ' + str(round(result['stdev'], 2)))\n" +
            "    else:\n" +
            "        counter = Counter([str(x) for x in data])\n" +
            "        result['unique_count'] = len(counter)\n" +
            "        print('频率统计:')\n" +
            "        for item, count in counter.most_common(10):\n" +
            "            print('  ' + str(item) + ': ' + str(count))\n" +
            "\n" +
            "print()\n" +
            "print('分析完成')",
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
    
    /**
     * 解析 JSON 数组字符串为 List
     * @return 解析后的 List，如果解析失败返回 null
     */
    private List<Object> parseJsonArray(String jsonStr) {
        if (!jsonStr.startsWith("[")) return null;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.get(i));
            }
            return list;
        } catch (JSONException e) {
            return null;
        }
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
