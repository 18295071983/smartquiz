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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "python_analyze_data",
    description = "使用Python分析数据(统计/清洗/转换/图表计算等)。与python_execute的区别：本工具专注数据分析场景，适合处理用户提供的数据或表格内容；python_execute可执行任意Python代码(含文件/网络/UI组件等)。数据量大时用本工具，复杂任务用python_execute；data 支持 JSON 数组/对象、带表头 CSV、逗号或换行分隔列表(如 10, 20, 30)",
    category = "python",
    aliases = {"data_analysis", "analyze", "数据分析"},
    actions = {
        @Action(name = "statistics", description = "统计分析"),
        @Action(name = "summary", description = "数据汇总"),
        @Action(name = "process", description = "数据处理")
    },
    params = {
        @Param(name = "data", type = "list", description = "数据：JSON 数组/对象，或带表头 CSV、逗号/换行分隔列表", required = true),
        @Param(name = "task", type = "string", description = "任务描述", required = false)
    }
)
public class PythonDataAnalysisTool extends BaseAITool {
    private static final String TAG = "PythonDataAnalysisTool";
    private final Context context;
    private final PythonToolManager toolManager;
    
    public PythonDataAnalysisTool(Context context) {
        super("python_analyze_data", "使用Python分析数据(统计/清洗/转换/图表计算等)。与python_execute的区别：本工具专注数据分析场景，适合处理用户提供的数据或表格内容；python_execute可执行任意Python代码(含文件/网络/UI组件等)。数据量大时用本工具，复杂任务用python_execute；data 支持 JSON 数组/对象、带表头 CSV、逗号或换行分隔列表(如 10, 20, 30)");
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
                    // 非 JSON：按 CSV/逗号/换行/分号/顿号/制表符解析
                    // （用户常直接贴 "10, 20, 30" 或带表头的 CSV，此前会被当成"1 条记录"）
                    dataList = parseCsvOrDelimited(dataStr);
                }
                if (dataList == null) {
                    // 仍解析不出：作为单个值处理
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
            
            String taskDesc = task != null ? task : "数据分析";
            String code = buildAnalysisCode(dataList, taskDesc);
            
            // 数据已作为字面量注入脚本，不依赖 contextData（executeCode 3参版不透传 ctx）；
            // 数据分析给 30s 超时
            PythonToolManager.ExecutionResult result = toolManager.executeCode(code, null, 30);
            
            return formatResult(result, dataList.size());
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing Python data analysis: " + e.getMessage(), e);
            return AIToolResult.fail("分析失败: " + errText(e));
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
            "# 数据用 json.loads 注入（JSON 的 true/false/null 是合法 JSON，不能直接当 Python 字面量）\n" +
            "data = json.loads(%s)\n" +
            "\n" +
            "try:\n" +
            "    print('任务: ' + str(task))\n" +
            "    print('数据量: ' + str(len(data)))\n" +
            "\n" +
            "    result = {}\n" +
            "    result['count'] = len(data)\n" +
            "\n" +
            "    if len(data) > 0 and isinstance(data[0], dict):\n" +
            "        # dict列表：按所有行的键并集分析（避免 data[0] 独有键导致字段丢失）\n" +
            "        all_keys = []\n" +
            "        for row in data:\n" +
            "            for k in row.keys():\n" +
            "                if k not in all_keys:\n" +
            "                    all_keys.append(k)\n" +
            "        numeric_fields = {}\n" +
            "        for key in all_keys:\n" +
            "            vals = []\n" +
            "            for row in data:\n" +
            "                v = row.get(key)\n" +
            "                if isinstance(v, (int, float)):\n" +
            "                    vals.append(float(v))\n" +
            "            if len(vals) == len(data) and len(vals) > 0:\n" +
            "                numeric_fields[key] = vals\n" +
            "        if numeric_fields:\n" +
            "            print('数值字段统计:')\n" +
            "            for field, nums in numeric_fields.items():\n" +
            "                info = {\n" +
            "                    'count': len(nums),\n" +
            "                    'mean': round(statistics.mean(nums), 2),\n" +
            "                    'median': statistics.median(nums),\n" +
            "                    'max': max(nums),\n" +
            "                    'min': min(nums),\n" +
            "                    'sum': sum(nums)\n" +
            "                }\n" +
            "                if len(nums) > 1:\n" +
            "                    info['stdev'] = round(statistics.stdev(nums), 2)\n" +
            "                result[field] = info\n" +
            "                print('  [' + field + ']')\n" +
            "                print('    平均值=' + str(info['mean']) + ' 中位数=' + str(info['median']))\n" +
            "                print('    最大=' + str(info['max']) + ' 最小=' + str(info['min']) + ' 总和=' + str(info['sum']))\n" +
            "                if 'stdev' in info:\n" +
            "                    print('    标准差=' + str(info['stdev']))\n" +
            "        # 非数值字段展示\n" +
            "        str_fields = [k for k in all_keys if k not in numeric_fields]\n" +
            "        if str_fields:\n" +
            "            print('文本字段:')\n" +
            "            for field in str_fields:\n" +
            "                vals = [str(row.get(field, '')) for row in data]\n" +
            "                c = Counter(vals)\n" +
            "                unique = len(c)\n" +
            "                print('  [' + field + '] 唯一值: ' + str(unique))\n" +
            "                for val, cnt in c.most_common(5):\n" +
            "                    print('    ' + str(val) + ': ' + str(cnt))\n" +
            "    else:\n" +
            "        if all(isinstance(x, (int, float)) for x in data):\n" +
            "            nums = [float(x) for x in data]\n" +
            "            result['mean'] = statistics.mean(nums)\n" +
            "            result['median'] = statistics.median(nums)\n" +
            "            result['max'] = max(nums)\n" +
            "            result['min'] = min(nums)\n" +
            "            result['sum'] = sum(nums)\n" +
            "            if len(nums) > 1:\n" +
            "                result['stdev'] = statistics.stdev(nums)\n" +
            "                result['variance'] = statistics.variance(nums)\n" +
            "            print('统计信息:')\n" +
            "            print('  平均值: ' + str(round(result['mean'], 2)))\n" +
            "            print('  中位数: ' + str(result['median']))\n" +
            "            print('  最大值: ' + str(result['max']))\n" +
            "            print('  最小值: ' + str(result['min']))\n" +
            "            print('  总和: ' + str(result['sum']))\n" +
            "            if 'stdev' in result:\n" +
            "                print('  标准差: ' + str(round(result['stdev'], 2)))\n" +
            "        else:\n" +
            "            counter = Counter([str(x) for x in data])\n" +
            "            result['unique_count'] = len(counter)\n" +
            "            print('频率统计:')\n" +
            "            for item, count in counter.most_common(10):\n" +
            "                print('  ' + str(item) + ': ' + str(count))\n" +
            "\n" +
            "    print()\n" +
            "    print('分析完成')\n" +
            "except Exception as e:\n" +
            "    print('分析错误: ' + str(e))",
            quoteString(task),
            quoteString(jsonData)
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
    
    /**
     * 非 JSON 文本的兜底解析：
     * <ol>
     *   <li>带表头的 CSV（≥2 行、列数一致、首行含非数字列名）→ [{列名: 值}, ...]，可直接做分组统计；</li>
     *   <li>单行/多行分隔列表（逗号/中文逗号/分号/顿号/制表符/竖线/换行）→ [值, ...]；</li>
     *   <li>都解析不出（少于 2 个值）返回 null，由调用方按"单个值"处理。</li>
     * </ol>
     */
    private static List<Object> parseCsvOrDelimited(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        String[] lines = t.split("\\r?\\n");
        // 1) 带表头的 CSV
        if (lines.length >= 2) {
            List<String[]> rows = new ArrayList<>();
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] cells = splitDelimitedLine(line);
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].trim().replaceAll("^\"|\"$", "");
                }
                rows.add(cells);
            }
            if (rows.size() >= 2) {
                int cols = rows.get(0).length;
                boolean uniform = cols >= 2;
                for (String[] r : rows) {
                    if (r.length != cols) { uniform = false; break; }
                }
                boolean headerLooksText = false;
                for (String c : rows.get(0)) {
                    if (!c.isEmpty() && !isNumericValue(c)) { headerLooksText = true; break; }
                }
                if (uniform && headerLooksText) {
                    List<Object> out = new ArrayList<>();
                    String[] head = rows.get(0);
                    for (int r = 1; r < rows.size(); r++) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 0; c < cols; c++) {
                            row.put(head[c], coerceValue(rows.get(r)[c]));
                        }
                        out.add(row);
                    }
                    if (out.size() >= 1) return out;
                }
            }
        }
        // 2) 分隔列表
        String[] parts = splitDelimitedLine(t);
        if (parts.length < 2) parts = lines;
        List<Object> out = new ArrayList<>();
        for (String p : parts) {
            String v = p.trim().replaceAll("^\"|\"$", "");
            if (!v.isEmpty()) out.add(coerceValue(v));
        }
        return out.size() >= 2 ? out : null;
    }

    /**
     * 按分隔符（半/全角逗号、半/全角分号、顿号、制表符、竖线）拆分一"行"，
     * 支持双引号包裹的字段：{"香,蕉",3} → ["香,蕉", "3"]（避免内嵌逗号被拆开导致静默错数据）。
     */
    private static String[] splitDelimitedLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (!inQuote && isDelimiterChar(ch)) {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString().trim());
        return cells.toArray(new String[0]);
    }

    private static boolean isDelimiterChar(char c) {
        return c == ',' || c == '\uFF0C' || c == ';' || c == '\uFF1B'
                || c == '\t' || c == '\u3001' || c == '|';
    }

    private static boolean isNumericValue(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object coerceValue(String s) {
        if (isNumericValue(s)) {
            try {
                return Integer.parseInt(s);
            } catch (Exception e) {
                return Double.parseDouble(s);
            }
        }
        return s;
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
        params.put("data", "array/text, 数据数组或文本：JSON [1,2,3] / ['a','b']、带表头 CSV（name,qty\n苹果,2）、逗号或换行分隔（10, 20, 30）");
        params.put("task", "string, 分析任务描述（可选）");
        return params;
    }
    
    private AIToolResult formatResult(PythonToolManager.ExecutionResult result, int dataCount) {
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("data_count", dataCount);
        additionalInfo.put("attempts", result.attempts);
        
        // 脚本内已用 try/except 捕获，错误标记为 "分析错误:" 前缀（正常退出0，避免触发 LLM auto-fix 循环）
        if (result.success && result.stdout != null && result.stdout.contains("分析错误:")) {
            int idx = result.stdout.indexOf("分析错误:");
            String errMsg = result.stdout.substring(idx + "分析错误:".length()).trim();
            additionalInfo.put("error", errMsg);
            return new AIToolResult("数据分析失败: " + errMsg, additionalInfo, false);
        }
        
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
