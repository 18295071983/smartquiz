package com.oilquiz.app.ai.service;

import android.content.Context;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentService {
    private static final String TAG = "AgentService";
    private static final int MAX_TOOL_LOOPS = 5;
    private static final long TOOL_TIMEOUT_MS = 30000;
    private static final long CACHE_EXPIRY_MS = 300000;
    private static final int MAX_RETRY_COUNT = 2;

    private final Context context;
    private final AIToolManager toolManager;
    private final List<ToolSchema> toolSchemas = new ArrayList<>();
    private final Map<String, ToolParamSchema> paramSchemas = new LinkedHashMap<>();
    private final Map<String, String> toolNameAliases = new HashMap<>();
    private final ConcurrentHashMap<String, String> toolResultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> toolTimestamps = new ConcurrentHashMap<>();
    private ToolSelectionStrategy currentStrategy = ToolSelectionStrategy.STANDARD;
    private boolean useDynamicTools = true;

    public enum ToolSelectionStrategy {
        MINIMAL,
        STANDARD,
        FULL
    }

    public static class ToolSchema {
        public final String name;
        public final String description;
        public final String paramDesc;

        public ToolSchema(String name, String description, String paramDesc) {
            this.name = name;
            this.description = description;
            this.paramDesc = paramDesc;
        }
    }

    public static class ToolParamSchema {
        public final String paramName;
        public final String type;
        public final String description;
        public final boolean required;
        public final Object defaultValue;

        public ToolParamSchema(String paramName, String type, String description, boolean required, Object defaultValue) {
            this.paramName = paramName;
            this.type = type;
            this.description = description;
            this.required = required;
            this.defaultValue = defaultValue;
        }
    }

    public static class ToolCall {
        public final String name;
        public final String arguments;
        public Map<String, Object> resolvedArgs;

        public ToolCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
            this.resolvedArgs = new HashMap<>();
        }
    }

    public static class ToolResult {
        public final String toolName;
        public final String result;
        public final boolean success;
        public final long executionTimeMs;
        public final int retryCount;

        public ToolResult(String toolName, String result, boolean success) {
            this.toolName = toolName;
            this.result = result;
            this.success = success;
            this.executionTimeMs = 0;
            this.retryCount = 0;
        }

        public ToolResult(String toolName, String result, boolean success, long executionTimeMs, int retryCount) {
            this.toolName = toolName;
            this.result = result;
            this.success = success;
            this.executionTimeMs = executionTimeMs;
            this.retryCount = retryCount;
        }
    }

    public AgentService(Context context) {
        this.context = context;
        this.toolManager = AIToolManager.getInstance(context);
        buildAliasMap();
        registerDefaultTools();
        buildDynamicToolSchemas();
    }

    private void buildAliasMap() {
        toolNameAliases.put("get_weather", "app_toolkit");
        toolNameAliases.put("weather_query", "app_toolkit");
        toolNameAliases.put("weather", "app_toolkit");
        toolNameAliases.put("get_location", "location");
        toolNameAliases.put("location_query", "location");
        toolNameAliases.put("get_current_location", "location");
        toolNameAliases.put("get_city", "location");
        toolNameAliases.put("get_gps", "location");
        toolNameAliases.put("network_search", "network_search");
        toolNameAliases.put("search", "network_search");
        toolNameAliases.put("web_search", "network_search");
        toolNameAliases.put("calculate", "app_toolkit");
        toolNameAliases.put("calculator", "app_toolkit");
        toolNameAliases.put("database_query", "database");
        toolNameAliases.put("database", "database");
        toolNameAliases.put("translation", "translation");
        toolNameAliases.put("translate", "translation");
        toolNameAliases.put("generate_questions", "app_toolkit");
        toolNameAliases.put("generate", "app_toolkit");
        toolNameAliases.put("search_questions", "app_toolkit");
        toolNameAliases.put("web_page_reader", "webpage_reader");
        toolNameAliases.put("read_webpage", "webpage_reader");
        toolNameAliases.put("read_url", "webpage_reader");
        toolNameAliases.put("file_analysis", "file_analyzer");
    }

    private void registerDefaultTools() {
        registerToolSchema("get_weather", "查询天气", "action(操作类型,必填: weather_current/weather_forecast/weather_hourly/weather_air/weather_alerts/weather_indices/weather_all), city(城市,可选), lat(纬度,可选), lon(经度,可选)");
        registerToolSchema("weather", "查询天气", "action(操作类型,必填), city(城市,可选), lat(纬度,可选), lon(经度,可选)");
        registerToolSchema("weather_query", "查询天气", "action(操作类型,必填), city(城市,可选), lat(纬度,可选), lon(经度,可选)");
        registerToolSchema("location", "获取位置信息", "action(操作类型,可选: get_current/get_city/get_coordinates)");
        registerToolSchema("get_location", "获取位置信息", "action(操作类型,可选)");
        registerToolSchema("get_current_location", "获取当前位置", "action(操作类型,可选)");
        registerToolSchema("get_city", "获取当前城市", "无参数");
        registerToolSchema("network_search", "搜索网络信息", "query(搜索关键词,必填), num_results(结果数,可选)");
        registerToolSchema("search", "搜索网络信息", "query(搜索关键词,必填), num_results(结果数,可选)");
        registerToolSchema("web_search", "搜索网络信息", "query(搜索关键词,必填), num_results(结果数,可选)");
        registerToolSchema("calculate", "执行数学计算", "expression(数学表达式,必填)");
        registerToolSchema("calculator", "执行数学计算", "expression(数学表达式,必填)");
        registerToolSchema("database_query", "数据库查询", "operation(操作类型,必填), keyword(关键词,可选)");
        registerToolSchema("database", "数据库查询", "operation(操作类型,必填), keyword(关键词,可选)");
        registerToolSchema("translation", "翻译文本", "text(文本,必填), target_lang(目标语言,可选)");
        registerToolSchema("translate", "翻译文本", "text(文本,必填), target_lang(目标语言,可选)");
        registerToolSchema("generate_questions", "生成练习题", "topic(主题,必填), count(数量,可选), difficulty(难度,可选)");
        registerToolSchema("generate", "生成练习题", "topic(主题,必填), count(数量,可选), difficulty(难度,可选)");
        registerToolSchema("search_questions", "搜索题目", "keyword(关键词,必填), category(分类,可选)");
        registerToolSchema("web_page_reader", "读取网页内容", "url(网址,必填)");
        registerToolSchema("read_webpage", "读取网页内容", "url(网址,必填)");
        registerToolSchema("read_url", "读取网页内容", "url(网址,必填)");
        registerToolSchema("app_toolkit", "应用工具集(天气/计算/题目等)", "action(操作类型,必填)");
        registerToolSchema("smart_research", "智能研究(搜索+阅读网页)", "query(研究问题,必填), depth(研究深度,可选)");
        registerToolSchema("system_resource", "系统资源(打开应用/发送短信等)", "action(操作类型,必填), params(参数,可选)");
        registerToolSchema("file_reader", "读取文件内容", "file_path(文件路径,必填)");
        registerToolSchema("file_analyzer", "分析文件", "file_path(文件路径,必填), analysis_type(分析类型,可选)");
        registerToolSchema("file_generator", "生成文件", "file_name(文件名,必填), content(内容,必填), format(格式,可选)");
        registerToolSchema("permission_manager", "权限管理", "action(权限操作,必填), permission(权限名,可选)");

        registerToolParamSchema("get_weather",
            new ToolParamSchema("action", "string", "操作类型：weather_current(当前天气), weather_forecast(未来预报), weather_hourly(24小时预报), weather_air(空气质量), weather_alerts(天气预警), weather_indices(生活指数), weather_all(全部信息)", true, "weather_all"),
            new ToolParamSchema("city", "string", "城市名称", false, "北京"),
            new ToolParamSchema("lat", "number", "纬度", false, 0),
            new ToolParamSchema("lon", "number", "经度", false, 0));

        registerToolParamSchema("location",
            new ToolParamSchema("action", "string", "操作类型：get_current(获取完整位置), get_city(获取城市), get_coordinates(获取坐标)", false, "get_current"));

        registerToolParamSchema("network_search",
            new ToolParamSchema("query", "string", "搜索关键词", true, null),
            new ToolParamSchema("num_results", "int", "结果数量", false, 5));

        registerToolParamSchema("calculate",
            new ToolParamSchema("expression", "string", "数学表达式", true, null));

        registerToolParamSchema("translation",
            new ToolParamSchema("text", "string", "翻译文本", true, null),
            new ToolParamSchema("target_lang", "string", "目标语言", false, "中文"));
    }

    private void buildDynamicToolSchemas() {
        try {
            List<Map<String, Object>> toolDescs = toolManager.getToolDescriptions();
            for (Map<String, Object> desc : toolDescs) {
                String name = (String) desc.get("name");
                String description = (String) desc.get("description");
                @SuppressWarnings("unchecked")
                Map<String, String> params = (Map<String, String>) desc.get("parameters");

                if (name != null && description != null && !hasToolSchema(name)) {
                    StringBuilder paramDesc = new StringBuilder();
                    if (params != null && !params.isEmpty()) {
                        for (Map.Entry<String, String> entry : params.entrySet()) {
                            if (paramDesc.length() > 0) paramDesc.append(", ");
                            paramDesc.append(entry.getKey()).append("(").append(entry.getValue()).append(")");
                        }
                    } else {
                        paramDesc.append("无参数或参数由action指定");
                    }
                    registerToolSchema(name, description, paramDesc.toString());
                    AILogger.i(TAG, "Dynamic tool registered: " + name + " -> " + description);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to build dynamic tool schemas: " + e.getMessage());
        }
    }

    public void refreshToolSchemas() {
        toolSchemas.clear();
        paramSchemas.clear();
        buildAliasMap();
        registerDefaultTools();
        buildDynamicToolSchemas();
        AILogger.i(TAG, "Tool schemas refreshed, total: " + toolSchemas.size());
    }

    private void registerToolSchema(String name, String description, String paramDesc) {
        if (hasToolSchema(name)) return;
        toolSchemas.add(new ToolSchema(name, description, paramDesc));
    }

    private void registerToolParamSchema(String toolName, ToolParamSchema... schemas) {
        for (ToolParamSchema schema : schemas) {
            paramSchemas.put(toolName + "." + schema.paramName, schema);
        }
    }

    private boolean hasToolSchema(String name) {
        for (ToolSchema schema : toolSchemas) {
            if (schema.name.equals(name)) return true;
        }
        return false;
    }

    public String buildToolSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你可以使用以下工具来帮助回答问题。当需要使用工具时，请按以下JSON格式输出：\n");
        sb.append("```json\n{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n```\n\n");
        sb.append("可用工具列表：\n\n");
        Map<String, ToolSchema> uniqueTools = deduplicateTools();
        for (ToolSchema tool : uniqueTools.values()) {
            sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
            sb.append("  ").append(tool.paramDesc).append("\n\n");
        }
        sb.append("重要规则：\n");
        sb.append("1. 当需要查询实时信息（如天气）时，必须使用工具\n");
        sb.append("2. 工具调用后，你会收到工具返回的结果，基于结果回答用户\n");
        sb.append("3. 如果不需要工具，直接回答即可\n");
        sb.append("4. 每次只调用一个工具\n");
        sb.append("5. 工具参数请严格按照指定的参数名填写\n");
        return sb.toString();
    }

    private Map<String, ToolSchema> deduplicateTools() {
        Map<String, ToolSchema> unique = new LinkedHashMap<>();
        for (ToolSchema schema : toolSchemas) {
            String mappedName = resolveToolName(schema.name);
            if (mappedName != null && !unique.containsKey(mappedName)) {
                unique.put(mappedName, schema);
            }
        }
        return unique;
    }

    public List<ToolCall> parseToolCalls(String output) {
        List<ToolCall> calls = new ArrayList<>();
        if (output == null || output.isEmpty()) return calls;

        Pattern p1 = Pattern.compile("<\\|tool_call_begin\\|>[\\s\\S]*?function\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?arguments\\s*:\\s*(\\{[^}]*\\})[\\s\\S]*?<\\|tool_call_end\\|>", Pattern.DOTALL);
        Matcher m1 = p1.matcher(output);
        while (m1.find()) {
            calls.add(new ToolCall(m1.group(1), m1.group(2)));
        }

        if (calls.isEmpty()) {
            Pattern p2 = Pattern.compile("<\\|tool_call_begin\\|>.*?function\\s*:\\s*\"([^\"]+)\".*?arguments\\s*:\\s*(\\{[^}]*\\})", Pattern.DOTALL);
            Matcher m2 = p2.matcher(output);
            while (m2.find()) {
                calls.add(new ToolCall(m2.group(1), m2.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Pattern p3 = Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*\\})\\s*\\}", Pattern.DOTALL);
            Matcher m3 = p3.matcher(output);
            while (m3.find()) {
                String toolName = m3.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    calls.add(new ToolCall(toolName, m3.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Pattern p4 = Pattern.compile("```json\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*\\})\\s*\\}\\s*```", Pattern.DOTALL);
            Matcher m4 = p4.matcher(output);
            while (m4.find()) {
                calls.add(new ToolCall(m4.group(1), m4.group(2)));
            }
        }

        return calls;
    }

    private boolean isKnownOrAliasedTool(String name) {
        if (isKnownTool(name)) return true;
        String resolved = toolNameAliases.get(name);
        return resolved != null && toolManager.hasTool(resolved);
    }

    private boolean isKnownTool(String name) {
        for (ToolSchema schema : toolSchemas) {
            if (schema.name.equals(name)) return true;
        }
        return toolNameAliases.containsKey(name);
    }

    public ToolResult executeTool(ToolCall call) {
        AILogger.i(TAG, "Executing tool: " + call.name + " with args: " + call.arguments);
        long startTime = System.currentTimeMillis();

        String cacheKey = call.name + ":" + call.arguments;
        String cached = getCachedResult(cacheKey);
        if (cached != null) {
            AILogger.d(TAG, "Tool cache HIT: " + call.name);
            return new ToolResult(call.name, cached, true, 0, 0);
        }

        Map<String, Object> params = parseArguments(call.arguments);
        call.resolvedArgs = params;

        String realToolName = resolveToolName(call.name);
        if (realToolName == null) {
            AILogger.w(TAG, "Unknown tool: " + call.name + ", attempting direct execution");
            realToolName = call.name;
        }

        AILogger.i(TAG, "Resolved tool: " + call.name + " -> " + realToolName);

        Map<String, Object> transformedParams = transformToolParams(call.name, realToolName, params);

        ToolResult result = executeWithRetry(realToolName, call.name, transformedParams, MAX_RETRY_COUNT);

        long elapsed = System.currentTimeMillis() - startTime;
        AILogger.i(TAG, "Tool " + call.name + " completed in " + elapsed + "ms, success=" + result.success);

        if (result.success) {
            cacheResult(cacheKey, result.result);
        }

        return new ToolResult(result.toolName, result.result, result.success, elapsed, result.retryCount);
    }

    private ToolResult executeWithRetry(String realToolName, String displayName, Map<String, Object> params, int maxRetries) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            final int currentAttempt = attempt;
            try {
                CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        AIToolResult toolResult = toolManager.executeTool(realToolName, params);
                        if (toolResult.isSuccess() && toolResult.getResult() != null) {
                            return new ToolResult(displayName, formatToolOutput(toolResult.getResult()), true, 0, currentAttempt);
                        } else {
                            String errorMsg = toolResult.getErrorMessage() != null ? toolResult.getErrorMessage() : "未知错误";
                            return new ToolResult(displayName, "工具执行失败: " + errorMsg, false, 0, currentAttempt);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                return future.get(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                lastException = e;
                AILogger.w(TAG, "Tool " + realToolName + " timeout on attempt " + (attempt + 1));
                if (attempt < maxRetries) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            } catch (ExecutionException e) {
                lastException = e;
                AILogger.w(TAG, "Tool " + realToolName + " execution error on attempt " + (attempt + 1) + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ToolResult(displayName, "工具执行被中断", false, 0, attempt);
            } catch (Exception e) {
                lastException = e;
                break;
            }
        }
        String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
        return new ToolResult(displayName, "工具执行出错(重试" + maxRetries + "次后): " + errorMsg, false, 0, maxRetries);
    }

    private String formatToolOutput(Object result) {
        if (result == null) return "工具返回空结果";
        if (result instanceof String) return (String) result;

        if (result instanceof Map) {
            Map<?, ?> resultMap = (Map<?, ?>) result;
            StringBuilder sb = new StringBuilder();
            if (resultMap.containsKey("note")) {
                sb.append(resultMap.get("note")).append("\n\n");
            }
            if (resultMap.containsKey("results")) {
                Object resultsObj = resultMap.get("results");
                if (resultsObj instanceof List) {
                    List<?> results = (List<?>) resultsObj;
                    for (int i = 0; i < results.size(); i++) {
                        sb.append(i + 1).append(". ");
                        Object item = results.get(i);
                        if (item instanceof Map) {
                            Map<?, ?> itemMap = (Map<?, ?>) item;
                            if (itemMap.containsKey("title")) sb.append(itemMap.get("title")).append("\n");
                            if (itemMap.containsKey("snippet")) sb.append("   ").append(itemMap.get("snippet")).append("\n");
                            if (itemMap.containsKey("url")) sb.append("   链接: ").append(itemMap.get("url")).append("\n");
                        } else {
                            sb.append(item.toString()).append("\n");
                        }
                        sb.append("\n");
                    }
                }
            }
            if (sb.length() == 0) {
                for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                    sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
            return sb.toString();
        }

        return result.toString();
    }

    public String resolveToolName(String alias) {
        String resolved = toolNameAliases.get(alias);
        if (resolved != null && toolManager.hasTool(resolved)) {
            return resolved;
        }
        if (toolManager.hasTool(alias)) {
            return alias;
        }
        return resolved;
    }

    private Map<String, Object> transformToolParams(String originalName, String realName, Map<String, Object> params) {
        Map<String, Object> transformed = new HashMap<>(params);
        
        if ("app_toolkit".equals(realName)) {
            boolean isWeatherTool = "get_weather".equals(originalName) || 
                                   "weather".equals(originalName) || 
                                   "weather_query".equals(originalName);
            
            if (isWeatherTool) {
                if (!transformed.containsKey("action") || transformed.get("action") == null) {
                    transformed.put("action", "weather_all");
                }
            }
        }
        
        return transformed;
    }

    public Map<String, Object> parseArguments(String argsJson) {
        Map<String, Object> params = new HashMap<>();
        if (argsJson == null || argsJson.trim().isEmpty()) return params;

        try {
            JSONObject obj = new JSONObject(argsJson.trim());
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = obj.get(key);
                params.put(key, value);
            }
        } catch (JSONException e) {
            AILogger.w(TAG, "Failed to parse JSON arguments, trying key:value format: " + e.getMessage());
            String cleaned = argsJson.replaceAll("[{}\"]", "").trim();
            String[] pairs = cleaned.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return params;
    }

    public String extractStringParam(String argsJson, String key) {
        try {
            JSONObject obj = new JSONObject(argsJson);
            return obj.optString(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    public ValidationResult validateParams(String toolName, Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, ToolParamSchema> entry : paramSchemas.entrySet()) {
            if (entry.getKey().startsWith(toolName + ".")) {
                ToolParamSchema schema = entry.getValue();
                Object value = params.get(schema.paramName);

                if (schema.required && (value == null || value.toString().trim().isEmpty())) {
                    if (schema.defaultValue != null) {
                        params.put(schema.paramName, schema.defaultValue);
                        warnings.add("参数 " + schema.paramName + " 使用默认值: " + schema.defaultValue);
                    } else {
                        errors.add("缺少必填参数: " + schema.paramName + " (" + schema.description + ")");
                    }
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }
    }

    public String formatToolResultForContext(ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tool_result>\n");
        sb.append("工具: ").append(result.toolName).append("\n");
        sb.append("状态: ").append(result.success ? "成功" : "失败").append("\n");
        if (result.executionTimeMs > 0) {
            sb.append("耗时: ").append(result.executionTimeMs).append("ms\n");
        }
        String truncatedResult = result.result != null ? result.result : "无结果";
        sb.append("结果: ").append(truncatedResult).append("\n");
        sb.append("</tool_result>\n");
        sb.append("请基于以上工具返回的结果回答用户的问题。如果工具执行失败，请告知用户并建议其他方式。");
        return sb.toString();
    }

    public boolean shouldUseAgent(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) return false;
        try {
            String lower = userMessage.toLowerCase();

            String[][] intentPatterns = {
                {"天气", "气温", "下雨", "下雪", "温度多少", "天气预报"},
                {"搜索", "查找资料", "检索", "网上查", "帮我搜索", "搜索一下"},
                {"计算", "算一下", "等于多少", "加起来", "乘以"},
                {"数据库", "题库", "错题", "学习统计", "做题记录"},
                {"翻译", "translate", "翻译成"},
                {"生成题目", "出题", "练习题"},
                {"搜索题目", "找题", "查找题目"},
                {"打开", "启动应用", "打开应用", "启动"},
                {"文件", "读取文件", "分析文件", "生成文件"},
                {"网页", "打开网页", "读取链接"},
            };

            String[][] excludePatterns = {
                {"冷吗", "热吗", "好冷", "好热", "太冷", "太热", "冷死", "热死"},
                {"查一下", "看一下", "想一下", "觉得"}
            };

            for (String[] patterns : excludePatterns) {
                for (String pattern : patterns) {
                    if (lower.contains(pattern)) {
                        return false;
                    }
                }
            }

            for (String[] patterns : intentPatterns) {
                for (String pattern : patterns) {
                    if (lower.contains(pattern)) return true;
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error checking if agent should be used: " + e.getMessage());
        }
        return false;
    }

    public String getIntentType(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) return "chat";
        try {
            String lower = userMessage.toLowerCase();
            if (lower.contains("天气") || lower.contains("气温") || lower.contains("下雨") || lower.contains("温度"))
                return "weather";
            if (lower.contains("我在哪") || lower.contains("我的位置") || lower.contains("我在什么地方") || 
                lower.contains("定位") || lower.contains("我的城市") || lower.contains("gps") || 
                lower.contains("我所在") || (lower.contains("我在") && lower.length() < 10))
                return "location";
            if (lower.contains("搜索") || lower.contains("查找") || lower.contains("检索"))
                return "search";
            if (lower.contains("计算") || lower.contains("算一下") || lower.matches(".*[\\d+\\-*/=().\\s]+.*"))
                return "calculate";
            if (lower.contains("数据库") || lower.contains("题库") || lower.contains("错题"))
                return "database";
            if (lower.contains("翻译") || lower.contains("translate"))
                return "translation";
            if (lower.contains("生成题目") || lower.contains("出题"))
                return "generate_questions";
            if (lower.contains("搜索题目") || lower.contains("找题"))
                return "search_questions";
            if (lower.contains("打开") || lower.contains("启动"))
                return "system";
            if (lower.contains("网页") || lower.contains("链接") || lower.contains("http"))
                return "web";
            if (lower.contains("文件") || lower.contains("读取") || lower.contains("生成文件"))
                return "file";
        } catch (Exception e) {
            AILogger.e(TAG, "Error determining intent type: " + e.getMessage());
        }
        return "chat";
    }

    public ToolSelectionStrategy determineStrategy(String userMessage) {
        if (userMessage == null || userMessage.isEmpty()) {
            return ToolSelectionStrategy.STANDARD;
        }

        try {
            int complexityScore = 0;
            String lower = userMessage.toLowerCase();

            if (userMessage.length() > 100) {
                complexityScore += 2;
            } else if (userMessage.length() <= 30) {
                complexityScore -= 1;
            }

            String[] complexIndicators = {"如何", "怎么", "为什么", "分析", "总结", "详细", "步骤", "方法"};
            for (String indicator : complexIndicators) {
                if (lower.contains(indicator)) {
                    complexityScore++;
                }
            }

            String[] intentPatterns = {"搜索", "计算", "翻译", "天气", "数据库", "生成", "分析", "打开", "网页", "文件"};
            int intentCount = 0;
            for (String pattern : intentPatterns) {
                if (lower.contains(pattern)) {
                    intentCount++;
                }
            }
            if (intentCount >= 2) {
                complexityScore += 2;
            }

            AILogger.i(TAG, "Complexity score: " + complexityScore);

            if (complexityScore >= 3) {
                return ToolSelectionStrategy.FULL;
            } else if (complexityScore <= 0) {
                return ToolSelectionStrategy.MINIMAL;
            } else {
                return ToolSelectionStrategy.STANDARD;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error determining strategy: " + e.getMessage());
            return ToolSelectionStrategy.STANDARD;
        }
    }

    public List<ToolSchema> selectToolsByIntent(String userMessage) {
        try {
            ToolSelectionStrategy strategy = determineStrategy(userMessage);
            this.currentStrategy = strategy;
            List<ToolSchema> selectedTools = new ArrayList<>();
            Map<String, ToolSchema> unique = deduplicateTools();

            switch (strategy) {
                case MINIMAL:
                    for (ToolSchema tool : unique.values()) {
                        if ("calculate".equals(tool.name) || "translation".equals(tool.name) || "translate".equals(tool.name)) {
                            selectedTools.add(tool);
                        }
                    }
                    break;
                case FULL:
                    selectedTools.addAll(unique.values());
                    break;
                case STANDARD:
                default:
                    for (ToolSchema tool : unique.values()) {
                        if ("get_weather".equals(tool.name) || "network_search".equals(tool.name) ||
                            "calculate".equals(tool.name) || "translation".equals(tool.name) ||
                            "database_query".equals(tool.name) || "web_page_reader".equals(tool.name) ||
                            "smart_research".equals(tool.name) || "system_resource".equals(tool.name) ||
                            "file_reader".equals(tool.name) || "file_analyzer".equals(tool.name) ||
                            "file_generator".equals(tool.name) || "app_toolkit".equals(tool.name)) {
                            selectedTools.add(tool);
                        }
                    }
                    break;
            }

            String intentType = getIntentType(userMessage);
            selectedTools = prioritizeToolsByIntent(selectedTools, intentType);

            AILogger.i(TAG, "Selected " + selectedTools.size() + " tools for intent: " + intentType);
            return selectedTools;
        } catch (Exception e) {
            AILogger.e(TAG, "Error selecting tools: " + e.getMessage());
            return new ArrayList<>(deduplicateTools().values());
        }
    }

    private List<ToolSchema> prioritizeToolsByIntent(List<ToolSchema> tools, String intentType) {
        if (tools == null || tools.isEmpty()) return new ArrayList<>();

        List<ToolSchema> prioritized = new ArrayList<>();
        List<ToolSchema> remaining = new ArrayList<>(tools);

        String primaryToolName = getPrimaryToolForIntent(intentType);
        if (primaryToolName != null) {
            for (int i = 0; i < remaining.size(); i++) {
                ToolSchema tool = remaining.get(i);
                if (tool != null && tool.name != null && tool.name.equals(primaryToolName)) {
                    prioritized.add(remaining.remove(i));
                    break;
                }
            }
        }

        prioritized.addAll(remaining);
        return prioritized;
    }

    private String getPrimaryToolForIntent(String intentType) {
        switch (intentType) {
            case "weather": return "get_weather";
            case "location": return "location";
            case "search": return "network_search";
            case "calculate": return "calculate";
            case "database": return "database_query";
            case "translation": return "translation";
            case "generate_questions": return "generate_questions";
            case "search_questions": return "search_questions";
            case "system": return "system_resource";
            case "web": return "web_page_reader";
            case "file": return "file_reader";
            default: return null;
        }
    }

    public String buildToolSystemPromptForStrategy(String userMessage) {
        try {
            List<ToolSchema> selectedTools = selectToolsByIntent(userMessage);
            StringBuilder sb = new StringBuilder();
            sb.append("你可以使用以下工具来帮助回答问题。当需要使用工具时，请按以下JSON格式输出：\n");
            sb.append("```json\n{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n```\n\n");
            sb.append("可用工具列表（按优先级排序）：\n\n");
            for (ToolSchema tool : selectedTools) {
                if (tool != null && tool.name != null && tool.description != null) {
                    sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
                    if (tool.paramDesc != null) {
                        sb.append("  ").append(tool.paramDesc).append("\n\n");
                    }
                }
            }
            sb.append("重要规则：\n");
            sb.append("1. 当需要查询实时信息（如天气）时，必须使用工具\n");
            sb.append("2. 工具调用后，你会收到工具返回的结果，基于结果回答用户\n");
            sb.append("3. 如果不需要工具，直接回答即可\n");
            sb.append("4. 每次只调用一个工具\n");
            return sb.toString();
        } catch (Exception e) {
            AILogger.e(TAG, "Error building tool system prompt: " + e.getMessage());
            return buildToolSystemPrompt();
        }
    }

    public ToolSelectionStrategy getCurrentStrategy() {
        return currentStrategy;
    }

    private void cacheResult(String key, String result) {
        toolResultCache.put(key, result);
        toolTimestamps.put(key, System.currentTimeMillis());
    }

    private String getCachedResult(String key) {
        Long timestamp = toolTimestamps.get(key);
        if (timestamp == null) return null;
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) {
            toolResultCache.remove(key);
            toolTimestamps.remove(key);
            return null;
        }
        return toolResultCache.get(key);
    }

    public void clearCache() {
        toolResultCache.clear();
        toolTimestamps.clear();
    }

    public List<ToolSchema> getToolSchemas() { return toolSchemas; }
    public int getMaxToolLoops() { return MAX_TOOL_LOOPS; }
    public Map<String, String> getToolNameAliases() { return new HashMap<>(toolNameAliases); }
    public boolean isToolAvailable(String toolName) {
        if (isKnownTool(toolName)) return true;
        String resolved = toolNameAliases.get(toolName);
        return resolved != null && toolManager.hasTool(resolved);
    }
}