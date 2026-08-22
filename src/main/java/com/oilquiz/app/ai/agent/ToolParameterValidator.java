package com.oilquiz.app.ai.agent;

import java.util.*;

/**
 * 工具参数验证器
 * 定义各工具的必需参数，并检查参数完整性
 */
public class ToolParameterValidator {
    
    /**
     * 工具参数规范
     */
    private static final Map<String, ToolSpec> TOOL_SPECS = new HashMap<>();
    
    static {
        // ========== 天气工具 ==========
        TOOL_SPECS.put("ai_weather", new ToolSpec(
            Arrays.asList("city", "location"),
            Arrays.asList("days", "unit"),
            "请告诉我您想查询哪个城市的天气？",
            Arrays.asList("可以说：'北京天气'、'上海天气预报'、'帮我查杭州的天气'")
        ));
        
        TOOL_SPECS.put("weather", new ToolSpec(
            Arrays.asList("city", "location"),
            Arrays.asList("days", "unit"),
            "请告诉我您想查询哪个城市的天气？",
            Arrays.asList("可以说：'北京天气'、'上海天气预报'、'帮我查杭州的天气'")
        ));
        
        // ========== 计算工具 ==========
        TOOL_SPECS.put("python_execute", new ToolSpec(
            Arrays.asList("code", "expression", "task"),
            Arrays.asList("timeout"),
            "请提供要计算的数学表达式或任务描述？",
            Arrays.asList("可以说：'计算 1+2+3'、'计算 100 的阶乘'、'分析这组数据的平均值'")
        ));
        
        TOOL_SPECS.put("python_calculate", new ToolSpec(
            Arrays.asList("expression"),
            Arrays.asList("precision"),
            "请提供要计算的数学表达式？",
            Arrays.asList("可以说：'计算 1+2*3'、'计算 2^10'、'求 100 以内质数之和'")
        ));
        
        TOOL_SPECS.put("python_analyze_data", new ToolSpec(
            Arrays.asList("data", "analysis_type"),
            Arrays.asList("options"),
            "请提供要分析的数据或分析类型？",
            Arrays.asList("可以说：'分析这些数据 [1,2,3,4,5] 的平均值'、'统计用户年龄分布'")
        ));
        
        // ========== 搜索工具 ==========
        TOOL_SPECS.put("web_search", new ToolSpec(
            Arrays.asList("query", "keyword"),
            Arrays.asList("count", "source"),
            "请告诉我您想搜索什么内容？",
            Arrays.asList("可以说：'搜索人工智能的最新进展'、'帮我查一下量子计算'")
        ));
        
        TOOL_SPECS.put("image_search", new ToolSpec(
            Arrays.asList("query", "keyword"),
            Arrays.asList("count"),
            "请告诉我您想搜索什么图片？",
            Arrays.asList("可以说：'搜索风景图片'、'找一些猫咪的图片'")
        ));
        
        TOOL_SPECS.put("news_search", new ToolSpec(
            Arrays.asList("query", "keyword"),
            Arrays.asList("count", "category"),
            "请告诉我您想搜索什么新闻？",
            Arrays.asList("可以说：'搜索今天的科技新闻'、'查找最近的政策动向'")
        ));
        
        // ========== 日程工具 ==========
        TOOL_SPECS.put("schedule", new ToolSpec(
            Arrays.asList("event", "time"),
            Arrays.asList("location", "remind", "repeat"),
            "请告诉我日程的时间和内容？",
            Arrays.asList("可以说：'明天上午10点开会'、'设置下午3点提醒吃药'、'周五晚上7点看电影'")
        ));
        
        TOOL_SPECS.put("reminder", new ToolSpec(
            Arrays.asList("message", "time"),
            Arrays.asList("repeat", "priority"),
            "请告诉我提醒的时间和内容？",
            Arrays.asList("可以说：'半小时后提醒我喝水'、'每天早上8点提醒我锻炼'")
        ));
        
        TOOL_SPECS.put("calendar", new ToolSpec(
            Arrays.asList("event", "start_time"),
            Arrays.asList("end_time", "location", "description"),
            "请告诉我日历事件的时间和内容？",
            Arrays.asList("可以说：'今天下午2点到4点开会'、'明天上午10点看医生'")
        ));
        
        // ========== 文档工具 ==========
        TOOL_SPECS.put("document_create", new ToolSpec(
            Arrays.asList("content", "type"),
            Arrays.asList("filename", "format"),
            "请告诉我文档的内容和类型？",
            Arrays.asList("可以说：'创建一份报告'、'写一个待办事项清单'")
        ));
        
        // ========== 文件工具 ==========
        TOOL_SPECS.put("file_read", new ToolSpec(
            Arrays.asList("path", "filename"),
            Arrays.asList("encoding"),
            "请告诉我要读取的文件路径？",
            Arrays.asList("可以说：'读取 notes.txt'、'查看 download/report.pdf'")
        ));
        
        TOOL_SPECS.put("file_write", new ToolSpec(
            Arrays.asList("content", "path"),
            Arrays.asList("filename", "append"),
            "请告诉我要写入的内容和文件路径？",
            Arrays.asList("可以说：'写入内容到 notes.txt'、'保存这段文本'")
        ));
        
        // ========== 通讯工具 ==========
        TOOL_SPECS.put("send_message", new ToolSpec(
            Arrays.asList("message", "to"),
            Arrays.asList("media"),
            "请告诉我要发送的消息和接收人？",
            Arrays.asList("可以说：'发消息给张三说今天加班'、'通知团队明天休息'")
        ));
        
        TOOL_SPECS.put("send_email", new ToolSpec(
            Arrays.asList("to", "subject"),
            Arrays.asList("body", "cc"),
            "请告诉我邮件的收件人和主题？",
            Arrays.asList("可以说：'发邮件给 john@example.com 主题是项目更新'")
        ));
        
        // ========== 系统工具 ==========
        TOOL_SPECS.put("system_info", new ToolSpec(
            Collections.emptyList(),
            Arrays.asList("info_type"),
            null,
            null
        ));
        
        TOOL_SPECS.put("battery_info", new ToolSpec(
            Collections.emptyList(),
            Arrays.asList("details"),
            null,
            null
        ));
    }
    
    /**
     * 工具规格定义
     */
    public static class ToolSpec {
        public final List<String> requiredParams;  // 必需参数
        public final List<String> optionalParams; // 可选参数
        public final String missingPrompt;        // 缺失时的提示
        public final List<String> suggestions;    // 建议
        
        public ToolSpec(List<String> required, List<String> optional, String prompt, List<String> suggestions) {
            this.requiredParams = required;
            this.optionalParams = optional;
            this.missingPrompt = prompt;
            this.suggestions = suggestions;
        }
    }
    
    /**
     * 检查缺失的参数
     * @param toolName 工具名称
     * @param params 当前参数
     * @return 缺失的参数列表
     */
    public static List<String> checkMissingParams(String toolName, Map<String, Object> params) {
        ToolSpec spec = TOOL_SPECS.get(toolName.toLowerCase());
        if (spec == null) {
            // 对于未定义的工具，宽松处理
            return Collections.emptyList();
        }
        
        List<String> missing = new ArrayList<>();
        for (String required : spec.requiredParams) {
            if (!hasParam(params, required)) {
                missing.add(required);
            }
        }
        
        return missing;
    }
    
    /**
     * 检查参数是否存在（包括可能的别名）
     */
    private static boolean hasParam(Map<String, Object> params, String paramName) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        
        // 直接检查
        if (params.containsKey(paramName)) {
            Object value = params.get(paramName);
            return value != null && !value.toString().trim().isEmpty();
        }
        
        // 检查常见别名
        String[] aliases = getAliases(paramName);
        for (String alias : aliases) {
            if (params.containsKey(alias)) {
                Object value = params.get(alias);
                if (value != null && !value.toString().trim().isEmpty()) {
                    return true;
                }
            }
        }
        
        // 检查值中是否包含该参数
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() != null) {
                String value = entry.getValue().toString().toLowerCase();
                // 如果值很长且包含关键信息，认为参数已提供
                if (value.length() > 20 && isInformative(value, paramName)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 获取参数的别名
     */
    private static String[] getAliases(String paramName) {
        switch (paramName) {
            case "city":
            case "location":
                return new String[]{"city_name", "cityName", "loc", "place", "where"};
            case "query":
            case "keyword":
                return new String[]{"search", "search_keyword", "q", "keywords", "text"};
            case "expression":
            case "code":
            case "task":
                return new String[]{"expr", "calculation", "operation", "input"};
            case "message":
            case "content":
                return new String[]{"msg", "text", "body", "data"};
            case "time":
            case "start_time":
                return new String[]{"datetime", "when", "date_time", "schedule_time"};
            case "event":
                return new String[]{"title", "name", "what", "summary"};
            case "to":
                return new String[]{"recipient", "target", "receiver"};
            default:
                return new String[]{};
        }
    }
    
    /**
     * 检查值是否包含有效信息
     */
    private static boolean isInformative(String value, String paramName) {
        // 简单的启发式检查
        return value.contains(paramName) ||
               value.contains("城市") ||
               value.contains("查询") ||
               value.contains("搜索") ||
               value.length() > 30;
    }
    
    /**
     * 获取缺失参数的描述
     */
    public static String getMissingDescription(String toolName, Map<String, Object> params) {
        List<String> missing = checkMissingParams(toolName, params);
        if (missing.isEmpty()) {
            return null;
        }
        
        ToolSpec spec = TOOL_SPECS.get(toolName.toLowerCase());
        if (spec != null && spec.missingPrompt != null) {
            return spec.missingPrompt;
        }
        
        return "缺少以下参数：" + String.join(", ", missing);
    }
    
    /**
     * 获取补充建议
     */
    public static List<String> getSuggestions(String toolName, Map<String, Object> params) {
        ToolSpec spec = TOOL_SPECS.get(toolName.toLowerCase());
        if (spec != null && spec.suggestions != null) {
            return spec.suggestions;
        }
        
        // 默认建议
        return Arrays.asList("请补充必要的信息后重试");
    }
    
    /**
     * 获取工具的必需参数列表
     */
    public static List<String> getRequiredParams(String toolName) {
        ToolSpec spec = TOOL_SPECS.get(toolName.toLowerCase());
        return spec != null ? spec.requiredParams : Collections.emptyList();
    }
    
    /**
     * 获取工具的可选参数列表
     */
    public static List<String> getOptionalParams(String toolName) {
        ToolSpec spec = TOOL_SPECS.get(toolName.toLowerCase());
        return spec != null ? spec.optionalParams : Collections.emptyList();
    }
    
    /**
     * 检查工具是否有规范定义
     */
    public static boolean hasSpec(String toolName) {
        return TOOL_SPECS.containsKey(toolName.toLowerCase());
    }
    
    /**
     * 注册自定义工具规范
     */
    public static void registerToolSpec(String toolName, ToolSpec spec) {
        TOOL_SPECS.put(toolName.toLowerCase(), spec);
    }
}