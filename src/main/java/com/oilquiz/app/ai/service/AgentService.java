package com.oilquiz.app.ai.service;

import android.content.Context;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.ToolSchemaExtractor;
import com.oilquiz.app.ai.tool.ToolSchemaExtractor.ExtractedSchema;
import com.oilquiz.app.ai.tool.ToolDependencyChecker;
import com.oilquiz.app.ai.tool.ToolDependencyChecker.DependencyCheckResult;
import com.oilquiz.app.ai.tool.ToolDependencyChecker.PreToolCall;
import com.oilquiz.app.util.AILogger;
import com.google.gson.Gson;
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
    
    private static volatile AgentService instance;
    private static final Object LOCK = new Object();

    private static final Pattern TOOL_CALL_PATTERN_STANDARD = Pattern.compile(
        "<\\|tool_call_begin\\|>[\\s\\S]*?function\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?arguments\\s*:\\s*(\\{[^}]*\\})[\\s\\S]*?<\\|tool_call_end\\|>",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_NO_END = Pattern.compile(
        "<\\|tool_call_begin\\|>.*?function\\s*:\\s*\"([^\"]+)\".*?arguments\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_TOOLS_BLOCK = Pattern.compile(
        "TOOLS_CALL[\\s\\S]*?\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})[\\s\\S]*?TOOLS_END",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOOL_CALL_PATTERN_TOOLS_NO_END = Pattern.compile(
        "TOOLS_CALL[\\s\\S]*?\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOOL_CALL_PATTERN_JSON = Pattern.compile(
        "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})\\s*\\}",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_MARKDOWN = Pattern.compile(
        "```json\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})\\s*\\}\\s*```",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_SIMPLE = Pattern.compile(
        "\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"arguments\"\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL
    );
    private static final Pattern TOOL_CALL_PATTERN_FUNCTION = Pattern.compile(
        "function\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?arguments\\s*:\\s*(\\{[^}]*(?:\\{[^}]*\\}[^}]*)*\\})",
        Pattern.DOTALL
    );

    private final Context context;
    private final AIToolManager toolManager;
    private final ToolDependencyChecker dependencyChecker;
    private final List<ToolSchema> toolSchemas = new ArrayList<>();
    private final Map<String, ToolParamSchema> paramSchemas = new LinkedHashMap<>();
    private final Map<String, String> toolNameAliases = new HashMap<>();
    private final ConcurrentHashMap<String, String> toolResultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> toolTimestamps = new ConcurrentHashMap<>();
    private ToolSelectionStrategy currentStrategy = ToolSelectionStrategy.STANDARD;
    private boolean useDynamicTools = true;
    
    private AgentService(Context context) {
        Context appContext;
        try {
            appContext = context.getApplicationContext();
        } catch (Exception e) {
            appContext = context;
        }
        this.context = appContext;
        this.toolManager = AIToolManager.getInstance(appContext);
        this.dependencyChecker = new ToolDependencyChecker(appContext, toolManager);
        buildAliasMap();
        autoDiscoverToolSchemas();
        registerDefaultTools();
    }
    
    public static AgentService getInstance(Context context) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new AgentService(context);
                }
            }
        }
        return instance;
    }

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
        public final String id;
        public final String name;
        public final String arguments;
        public Map<String, Object> resolvedArgs;

        public ToolCall(String name, String arguments) {
            this.id = java.util.UUID.randomUUID().toString();
            this.name = name;
            this.arguments = arguments;
            this.resolvedArgs = new HashMap<>();
        }

        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
            this.resolvedArgs = new HashMap<>();
        }

        public ToolCall(String id, String name, Map<String, Object> resolvedArgs, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
            this.resolvedArgs = resolvedArgs != null ? resolvedArgs : new HashMap<>();
        }
    }

    public static class ToolResult {
        public final String toolName;
        public final String result;
        public final String errorMessage;
        public final boolean success;
        public final long executionTimeMs;
        public final int retryCount;

        public ToolResult(String toolName, String result, boolean success) {
            this.toolName = toolName;
            this.result = result;
            this.errorMessage = success ? null : result;
            this.success = success;
            this.executionTimeMs = 0;
            this.retryCount = 0;
        }

        public ToolResult(String toolName, String result, boolean success, long executionTimeMs, int retryCount) {
            this.toolName = toolName;
            this.result = result;
            this.errorMessage = success ? null : result;
            this.success = success;
            this.executionTimeMs = executionTimeMs;
            this.retryCount = retryCount;
        }
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
        toolNameAliases.put("search_questions", "database");
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
        registerToolSchema("database", "数据库查询(题目搜索/用户管理/分数记录等)", "action(操作类型,必填: execute_query/get_questions/search_questions/get_question_count等), keyword(关键词,可选), category(分类,可选), type(类型,可选), difficulty(难度,可选)");
        registerToolSchema("database_query", "数据库查询", "action(操作类型,必填), keyword(关键词,可选)");
        registerToolSchema("search_questions", "搜索题目", "action(操作类型,必填: search_questions), keyword(关键词,必填), category(分类,可选)");
        registerToolSchema("translation", "翻译文本", "text(文本,必填), target_lang(目标语言,可选)");
        registerToolSchema("translate", "翻译文本", "text(文本,必填), target_lang(目标语言,可选)");
        registerToolSchema("web_page_reader", "读取网页内容", "url(网址,必填)");
        registerToolSchema("read_webpage", "读取网页内容", "url(网址,必填)");
        registerToolSchema("read_url", "读取网页内容", "url(网址,必填)");
        registerToolSchema("app_toolkit", "应用工具集(天气/OCR/图片处理/文件解析/网页解析等)", "action(操作类型,必填: weather_current/ocr_recognize/image_save/file_parse_text/web_parse_html等)");
        registerToolSchema("smart_research", "智能研究(搜索+阅读网页)", "query(研究问题,必填), depth(研究深度,可选)");
        registerToolSchema("system_resource", "系统资源(打开应用/发送短信等)", "action(操作类型,必填), params(参数,可选)");
        registerToolSchema("file_reader", "读取文件内容", "file_path(文件路径,必填)");
        registerToolSchema("file_analyzer", "分析文件", "file_path(文件路径,必填), analysis_type(分析类型,可选)");
        registerToolSchema("file_generator", "生成文件", "file_name(文件名,必填), content(内容,必填), format(格式,可选)");
        registerToolSchema("permission_manager", "权限管理", "action(权限操作,必填), permission(权限名,可选)");
        registerToolSchema("create_dynamic_tool", "动态创建和管理AI工具", "action(操作类型,必填: create/update/delete/list), tool_name(工具名称,必填), description(工具描述), parameters(参数定义JSON), logic(执行逻辑脚本)");
        registerToolSchema("python_execute", "执行Python代码", "task(任务描述,必填), code(Python代码,可选)");
        registerToolSchema("python_calculate", "执行数学计算", "expression(数学表达式,必填)");
        registerToolSchema("python_analyze_data", "分析数据", "data(数据,可选), task(任务描述,可选)");

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
            new ToolParamSchema("expression", "string", "数学表达式，支持加减乘除、括号、幂运算", true, null));

        registerToolParamSchema("translation",
            new ToolParamSchema("text", "string", "翻译文本", true, null),
            new ToolParamSchema("target_lang", "string", "目标语言", false, "中文"));
    }

    private void buildDynamicToolSchemas() {
        autoDiscoverToolSchemas();
    }
    
    /**
     * P0: 自动从 AIToolManager 发现所有已注册工具的 schema
     * 优先使用注解信息，没有注解时使用工具接口提供的信息
     * 新工具只需在 AIToolManager 注册，此处会自动同步 schema
     * 优化：通过反射获取注解，不实例化工具，节省内存
     */
    private void autoDiscoverToolSchemas() {
        try {
            List<String> toolNames = toolManager.getRegisteredToolNames();
            for (String name : toolNames) {
                try {
                    Class<? extends com.oilquiz.app.ai.tool.AITool> toolClass = toolManager.getToolClass(name);
                    if (toolClass != null) {
                        com.oilquiz.app.ai.tool.annotation.Tool toolAnnotation = 
                            toolClass.getAnnotation(com.oilquiz.app.ai.tool.annotation.Tool.class);
                        if (toolAnnotation != null) {
                            registerToolSchemasFromAnnotation(name, toolAnnotation);
                            if (toolAnnotation.aliases().length > 0) {
                                for (String alias : toolAnnotation.aliases()) {
                                    if (!toolNameAliases.containsKey(alias)) {
                                        toolNameAliases.put(alias, name);
                                    }
                                }
                            }
                        } else {
                            com.oilquiz.app.ai.tool.AITool tool = toolManager.getToolsMap().get(name);
                            if (tool != null) {
                                registerToolSchemaFromTool(tool);
                            }
                        }
                    }
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to extract schema for tool: " + name + ", error: " + e.getMessage());
                }
            }
            AILogger.i(TAG, "Auto-discovered " + toolSchemas.size() + " tool schemas from AIToolManager");
        } catch (Exception e) {
            AILogger.e(TAG, "Auto-discovery failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从注解注册工具 schema（不实例化工具）
     */
    private void registerToolSchemasFromAnnotation(String toolName, com.oilquiz.app.ai.tool.annotation.Tool annotation) {
        String name = annotation.value().isEmpty() ? toolName : annotation.value();
        String description = annotation.description();
        
        StringBuilder paramDesc = new StringBuilder();
        
        com.oilquiz.app.ai.tool.annotation.Action[] actions = annotation.actions();
        if (actions.length > 0) {
            List<String> actionNames = new ArrayList<>();
            for (com.oilquiz.app.ai.tool.annotation.Action action : actions) {
                actionNames.add(action.name());
            }
            paramDesc.append("action(操作类型,必填: ").append(String.join("/", actionNames)).append(")");
            
            for (com.oilquiz.app.ai.tool.annotation.Action action : actions) {
                com.oilquiz.app.ai.tool.annotation.Param[] actionParams = action.params();
                for (com.oilquiz.app.ai.tool.annotation.Param param : actionParams) {
                    paramDesc.append(", ").append(param.name())
                        .append("(").append(param.description())
                        .append(param.required() ? ",必填" : ",可选").append(")");
                }
            }
        }
        
        com.oilquiz.app.ai.tool.annotation.Param[] params = annotation.params();
        if (params.length > 0) {
            if (paramDesc.length() > 0) paramDesc.append(", ");
            for (com.oilquiz.app.ai.tool.annotation.Param param : params) {
                StringBuilder desc = new StringBuilder(param.description());
                if (param.required()) desc.append(",必填");
                else desc.append(",可选");
                if (param.options().length > 0) {
                    desc.append(": ").append(String.join("/", param.options()));
                }
                if (paramDesc.length() > 0 && !paramDesc.toString().contains(param.name())) {
                    paramDesc.append(", ").append(param.name()).append("(").append(desc).append(")");
                }
            }
        }
        
        registerToolSchema(name, description, paramDesc.toString());
        AILogger.i(TAG, "Auto-registered (annotation): " + name + " -> " + description);
    }
    
    /**
     * 从注解注册工具 schema（保留原方法用于向后兼容）
     */
    private void registerToolSchemasFromAnnotation(com.oilquiz.app.ai.tool.AITool tool, com.oilquiz.app.ai.tool.annotation.Tool annotation) {
        registerToolSchemasFromAnnotation(tool.getName(), annotation);
    }
    
    /**
     * 从工具接口注册 schema（无注解时）
     */
    private void registerToolSchemaFromTool(AITool tool) {
        String name = tool.getName();
        String description = tool.getDescription();
        String paramDesc = ToolSchemaExtractor.formatParamDescriptions(tool.getParameterDescriptions());
        registerToolSchema(name, description, paramDesc);
        AILogger.i(TAG, "Auto-registered (reflection): " + name + " -> " + description);
    }

    public void refreshToolSchemas() {
        toolSchemas.clear();
        paramSchemas.clear();
        buildAliasMap();
        autoDiscoverToolSchemas();
        registerDefaultTools();
        AILogger.i(TAG, "Tool schemas refreshed, total: " + toolSchemas.size());
    }

    private void registerToolSchema(String name, String description, String paramDesc) {
        // 如果已有同名schema，更新（用于手动注册覆盖自动发现）
        for (int i = 0; i < toolSchemas.size(); i++) {
            if (toolSchemas.get(i).name.equals(name)) {
                toolSchemas.set(i, new ToolSchema(name, description, paramDesc));
                return;
            }
        }
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
        sb.append("[工具使用说明]\n\n");
        sb.append("当需要使用工具时，按以下格式输出：\n\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n");
        sb.append("TOOLS_END\n\n");
        sb.append("示例1 - 查天气：\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"weather\", \"arguments\": {\"city\": \"北京\"}}\n");
        sb.append("TOOLS_END\n\n");
        sb.append("示例2 - 计算器：\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"calculator\", \"arguments\": {\"expression\": \"3+5\"}}\n");
        sb.append("TOOLS_END\n\n");
        sb.append("示例3 - 搜索：\n");
        sb.append("TOOLS_CALL\n");
        sb.append("{\"name\": \"search\", \"arguments\": {\"query\": \"人工智能\"}}\n");
        sb.append("TOOLS_END\n\n");
        sb.append("可用工具列表：\n\n");
        Map<String, ToolSchema> uniqueTools = deduplicateTools();
        for (ToolSchema tool : uniqueTools.values()) {
            sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n");
            sb.append("  ").append(tool.paramDesc).append("\n\n");
        }
        sb.append("重要规则：\n");
        sb.append("1. 工具调用必须以 TOOLS_CALL 开头，TOOLS_END 结尾\n");
        sb.append("2. 每次只调用一个工具\n");
        sb.append("3. 不需要工具时，直接回答用户问题\n");
        sb.append("4. 工具返回结果后，基于结果回答用户\n");
        sb.append("5. 参数名必须与工具定义一致\n");
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

        AILogger.d(TAG, "Parsing tool calls from: " + output.substring(0, Math.min(200, output.length())));

        Matcher m1 = TOOL_CALL_PATTERN_TOOLS_BLOCK.matcher(output);
        while (m1.find()) {
            String toolName = m1.group(1);
            if (isKnownOrAliasedTool(toolName)) {
                AILogger.d(TAG, "Found TOOLS_BLOCK pattern: " + toolName);
                calls.add(new ToolCall(toolName, m1.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m2 = TOOL_CALL_PATTERN_TOOLS_NO_END.matcher(output);
            while (m2.find()) {
                String toolName = m2.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found TOOLS_NO_END pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m2.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Matcher m3 = TOOL_CALL_PATTERN_STANDARD.matcher(output);
            while (m3.find()) {
                AILogger.d(TAG, "Found standard pattern");
                calls.add(new ToolCall(m3.group(1), m3.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m4 = TOOL_CALL_PATTERN_NO_END.matcher(output);
            while (m4.find()) {
                AILogger.d(TAG, "Found no-end pattern");
                calls.add(new ToolCall(m4.group(1), m4.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m5 = TOOL_CALL_PATTERN_MARKDOWN.matcher(output);
            while (m5.find()) {
                AILogger.d(TAG, "Found markdown pattern");
                calls.add(new ToolCall(m5.group(1), m5.group(2)));
            }
        }

        if (calls.isEmpty()) {
            Matcher m6 = TOOL_CALL_PATTERN_JSON.matcher(output);
            while (m6.find()) {
                String toolName = m6.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found JSON pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m6.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Matcher m7 = TOOL_CALL_PATTERN_FUNCTION.matcher(output);
            while (m7.find()) {
                String toolName = m7.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found function pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m7.group(2)));
                }
            }
        }

        if (calls.isEmpty()) {
            Matcher m8 = TOOL_CALL_PATTERN_SIMPLE.matcher(output);
            while (m8.find()) {
                String toolName = m8.group(1);
                if (isKnownOrAliasedTool(toolName)) {
                    AILogger.d(TAG, "Found simple pattern: " + toolName);
                    calls.add(new ToolCall(toolName, m8.group(2)));
                }
            }
        }

        AILogger.d(TAG, "Total tool calls found: " + calls.size());
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

    public ToolResult executeTool(String toolName, Map<String, Object> params) {
        String arguments = params != null ? new Gson().toJson(params) : "{}";
        return executeTool(new ToolCall(toolName, arguments));
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

        // P2: 检查并执行工具依赖
        DependencyCheckResult depResult = dependencyChecker.checkDependencies(realToolName, params);
        if (!depResult.requiredPreCalls.isEmpty()) {
            AILogger.i(TAG, "Tool " + realToolName + " has " + depResult.requiredPreCalls.size() + " dependencies, executing...");
            boolean depsOk = dependencyChecker.executePreCalls(depResult.requiredPreCalls);
            if (!depsOk && !depResult.unresolvableDeps.isEmpty()) {
                return new ToolResult(call.name, "工具依赖检查失败: " + String.join(", ", depResult.unresolvableDeps), false, 0, 0);
            }
        }

        AILogger.i(TAG, "Resolved tool: " + call.name + " -> " + realToolName);

        ValidationResult validation = validateParams(realToolName, params);
        if (!validation.valid) {
            String errorMsg = "参数验证失败: " + String.join(", ", validation.errors);
            AILogger.e(TAG, errorMsg);
            return new ToolResult(call.name, errorMsg, false, 0, 0);
        }
        if (!validation.warnings.isEmpty()) {
            AILogger.w(TAG, "Params warnings: " + String.join(", ", validation.warnings));
        }

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
        // 保护：toolManager可能为null
        if (toolManager == null) {
            AILogger.e(TAG, "toolManager is null, cannot execute tool: " + realToolName);
            return new ToolResult(displayName, "工具管理器未初始化: " + realToolName, false, 0, 0);
        }
        
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            final int currentAttempt = attempt;
            try {
                CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        AIToolResult toolResult = toolManager.executeTool(realToolName, params);
                        // 保护：toolResult可能为null
                        if (toolResult == null) {
                            AILogger.e(TAG, "toolManager.executeTool returned null for: " + realToolName);
                            return new ToolResult(displayName, "工具执行返回null: " + realToolName, false, 0, currentAttempt);
                        }
                        
                        if (toolResult.isSuccess() && toolResult.getResult() != null) {
                            return new ToolResult(displayName, formatToolOutput(toolResult.getResult()), true, 0, currentAttempt);
                        } else {
                            String errorMsg = toolResult.getErrorMessage() != null ? toolResult.getErrorMessage() : "未知错误";
                            return new ToolResult(displayName, "工具执行失败: " + errorMsg, false, 0, currentAttempt);
                        }
                    } catch (Exception e) {
                        AILogger.e(TAG, "Exception in tool execution: " + realToolName, e);
                        throw new RuntimeException(e);
                    }
                });

                return future.get(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                lastException = e;
                AILogger.w(TAG, "Tool " + realToolName + " timeout on attempt " + (attempt + 1));
                if (attempt < maxRetries) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new ToolResult(displayName, "工具执行被中断", false, 0, attempt);
                    }
                }
            } catch (ExecutionException e) {
                lastException = e;
                AILogger.w(TAG, "Tool " + realToolName + " execution error on attempt " + (attempt + 1) + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(300); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new ToolResult(displayName, "工具执行被中断", false, 0, attempt);
                    }
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

    private static final int TOOL_RESULT_MAX_LENGTH = 2000;
    private static final int TOOL_CONTENT_PREVIEW_LENGTH = 400;

    private String formatToolOutput(Object result) {
        if (result == null) return "工具返回空结果";
        if (result instanceof String) return (String) result;

        if (result instanceof Map) {
            Map<?, ?> resultMap = (Map<?, ?>) result;
            StringBuilder sb = new StringBuilder();

            Object statusObj = resultMap.get("status");
            boolean isSuccess = false;
            if (statusObj != null) {
                if ("success".equals(statusObj.toString())) {
                    isSuccess = true;
                } else if (statusObj instanceof Boolean) {
                    isSuccess = (Boolean) statusObj;
                }
            }

            if (!isSuccess) {
                sb.append("[工具执行失败]\n");
                if (resultMap.containsKey("error")) {
                    sb.append("错误信息: ").append(resultMap.get("error")).append("\n");
                }
                if (resultMap.containsKey("error_message")) {
                    sb.append("详细错误: ").append(resultMap.get("error_message")).append("\n");
                }
                if (resultMap.containsKey("message")) {
                    sb.append("信息: ").append(resultMap.get("message")).append("\n");
                }
                if (resultMap.containsKey("attempts")) {
                    sb.append("尝试次数: ").append(resultMap.get("attempts")).append("\n");
                }
                if (resultMap.containsKey("attempts_log")) {
                    sb.append("尝试日志: ").append(resultMap.get("attempts_log")).append("\n");
                }
                return sb.toString();
            }

            sb.append("[工具执行成功]\n");

            if (resultMap.containsKey("attempts")) {
                sb.append("尝试次数: ").append(resultMap.get("attempts")).append("\n");
            }
            if (resultMap.containsKey("provider")) {
                sb.append("数据源: ").append(resultMap.get("provider")).append("\n");
            }
            if (resultMap.containsKey("query_type")) {
                sb.append("查询方式: ").append(resultMap.get("query_type")).append("\n");
            }
            if (resultMap.containsKey("provider_used")) {
                sb.append("定位方式: ").append(resultMap.get("provider_used")).append("\n");
            }
            sb.append("\n");

            if (resultMap.containsKey("data")) {
                Object dataObj = resultMap.get("data");
                if (dataObj instanceof String) {
                    sb.append("【数据】\n").append((String) dataObj).append("\n");
                } else if (dataObj != null) {
                    sb.append("【数据】\n").append(formatNestedData(dataObj, "")).append("\n");
                }
            }

            if (resultMap.containsKey("current")) {
                sb.append("\n【当前天气】\n").append(resultMap.get("current")).append("\n");
            }
            if (resultMap.containsKey("forecast")) {
                sb.append("\n【天气预报】\n").append(resultMap.get("forecast")).append("\n");
            }
            if (resultMap.containsKey("hourly")) {
                sb.append("\n【逐时预报】\n").append(resultMap.get("hourly")).append("\n");
            }
            if (resultMap.containsKey("air_quality")) {
                sb.append("\n【空气质量】\n").append(resultMap.get("air_quality")).append("\n");
            }
            if (resultMap.containsKey("indices")) {
                sb.append("\n【生活指数】\n").append(resultMap.get("indices")).append("\n");
            }
            if (resultMap.containsKey("alerts")) {
                sb.append("\n【天气预警】\n").append(resultMap.get("alerts")).append("\n");
            }
            if (resultMap.containsKey("attempts_info")) {
                sb.append("\n【执行详情】\n").append(resultMap.get("attempts_info")).append("\n");
            }

            if (resultMap.containsKey("latitude")) {
                sb.append("\n【位置信息】\n");
                sb.append("纬度: ").append(resultMap.get("latitude")).append("\n");
                sb.append("经度: ").append(resultMap.get("longitude")).append("\n");
                if (resultMap.containsKey("accuracy")) {
                    sb.append("精度: ").append(resultMap.get("accuracy")).append("米\n");
                }
                if (resultMap.containsKey("city")) {
                    sb.append("城市: ").append(resultMap.get("city")).append("\n");
                }
                if (resultMap.containsKey("provider")) {
                    sb.append("原始Provider: ").append(resultMap.get("provider")).append("\n");
                }
            }

            if (resultMap.containsKey("city")) {
                sb.append("城市: ").append(resultMap.get("city")).append("\n");
            }
            if (resultMap.containsKey("weather")) {
                sb.append("天气: ").append(resultMap.get("weather")).append("\n");
            }
            if (resultMap.containsKey("temperature") || resultMap.containsKey("temp")) {
                sb.append("温度: ").append(resultMap.get("temperature") != null ? resultMap.get("temperature") : resultMap.get("temp")).append("\n");
            }
            if (resultMap.containsKey("humidity")) {
                sb.append("湿度: ").append(resultMap.get("humidity")).append("\n");
            }
            if (resultMap.containsKey("wind")) {
                sb.append("风力: ").append(resultMap.get("wind")).append("\n");
            }

            if (resultMap.containsKey("expression")) {
                sb.append("表达式: ").append(resultMap.get("expression")).append("\n");
            }
            if (resultMap.containsKey("result")) {
                sb.append("结果: ").append(resultMap.get("result")).append("\n");
            }

            if (resultMap.containsKey("query")) {
                sb.append("查询: ").append(resultMap.get("query")).append("\n");
            }
            if (resultMap.containsKey("count")) {
                sb.append("结果数量: ").append(resultMap.get("count")).append("\n");
            }
            if (resultMap.containsKey("total")) {
                sb.append("总计: ").append(resultMap.get("total")).append("\n");
            }

            if (resultMap.containsKey("note")) {
                sb.append("\n").append(resultMap.get("note")).append("\n");
            }
            if (resultMap.containsKey("results")) {
                Object resultsObj = resultMap.get("results");
                if (resultsObj instanceof List) {
                    List<?> results = (List<?>) resultsObj;
                    if (!results.isEmpty()) {
                        sb.append("\n搜索结果:\n\n");
                        for (int i = 0; i < Math.min(results.size(), 5); i++) {
                            sb.append(i + 1).append(". ");
                            Object item = results.get(i);
                            if (item instanceof Map) {
                                Map<?, ?> itemMap = (Map<?, ?>) item;
                                if (itemMap.containsKey("title")) sb.append(itemMap.get("title")).append("\n");
                                if (itemMap.containsKey("snippet")) sb.append("   ").append(itemMap.get("snippet")).append("\n");
                                if (itemMap.containsKey("url")) sb.append("   链接: ").append(itemMap.get("url")).append("\n");
                                if (itemMap.containsKey("content")) sb.append("   内容: ").append(itemMap.get("content")).append("\n");
                            } else {
                                sb.append(item.toString()).append("\n");
                            }
                            sb.append("\n");
                        }
                        if (results.size() > 5) {
                            sb.append("... 还有 ").append(results.size() - 5).append(" 条结果\n");
                        }
                    }
                }
            }

            if (resultMap.containsKey("content")) {
                Object contentObj = resultMap.get("content");
                if (contentObj instanceof String) {
                    String content = (String) contentObj;
                    if (content.length() > TOOL_CONTENT_PREVIEW_LENGTH) {
                        sb.append("\n【文件内容预览】\n");
                        sb.append(content, 0, TOOL_CONTENT_PREVIEW_LENGTH).append("\n");
                        sb.append("[内容过长，已截断，共 ").append(content.length()).append(" 字符]\n");
                    } else {
                        sb.append("\n【文件内容】\n").append(content).append("\n");
                    }
                }
            }

            if (resultMap.containsKey("text")) {
                Object textObj = resultMap.get("text");
                if (textObj instanceof String) {
                    String text = (String) textObj;
                    if (text.length() > TOOL_CONTENT_PREVIEW_LENGTH) {
                        sb.append("\n【OCR识别结果预览】\n");
                        sb.append(text, 0, TOOL_CONTENT_PREVIEW_LENGTH).append("\n");
                        sb.append("[内容过长，已截断，共 ").append(text.length()).append(" 字符]\n");
                    } else {
                        sb.append("\n【OCR识别结果】\n").append(text).append("\n");
                    }
                }
            }

            if (resultMap.containsKey("fileName")) {
                sb.append("\n文件名: ").append(resultMap.get("fileName")).append("\n");
            }
            if (resultMap.containsKey("length")) {
                sb.append("内容长度: ").append(resultMap.get("length")).append(" 字符\n");
            }

            if (sb.length() <= 10) {
                sb.setLength(0);
                sb.append("[工具返回数据]\n");
                for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (value instanceof Map || value instanceof List) continue;
                    sb.append(key).append(": ").append(value).append("\n");
                }
            }

            return sb.toString();
        }

        return result.toString();
    }
    
    private String formatNestedData(Object data, String indent) {
        StringBuilder sb = new StringBuilder();
        if (data == null) return "null";
        
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Map || value instanceof List) {
                    sb.append(indent).append(key).append(":\n");
                    sb.append(formatNestedData(value, indent + "  "));
                } else {
                    sb.append(indent).append(key).append(": ").append(value).append("\n");
                }
            }
        } else if (data instanceof List) {
            List<?> list = (List<?>) data;
            for (int i = 0; i < Math.min(list.size(), 10); i++) {
                Object item = list.get(i);
                sb.append(indent).append("[").append(i).append("]: ");
                if (item instanceof Map || item instanceof List) {
                    sb.append("\n").append(formatNestedData(item, indent + "  "));
                } else {
                    sb.append(item).append("\n");
                }
            }
            if (list.size() > 10) {
                sb.append(indent).append("... 还有 ").append(list.size() - 10).append(" 项\n");
            }
        } else {
            sb.append(data.toString());
        }
        return sb.toString();
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
            boolean isCalculateTool = "calculate".equals(originalName) || 
                                     "calculator".equals(originalName);
            
            if (isWeatherTool) {
                if (!transformed.containsKey("action") || transformed.get("action") == null) {
                    transformed.put("action", "weather_all");
                }
            } else if (isCalculateTool) {
                if (!transformed.containsKey("action") || transformed.get("action") == null) {
                    transformed.put("action", "calculate");
                }
            }

            Object action = transformed.get("action");
            String actionStr = action != null ? action.toString() : "";
            
            if ("ocr_recognize".equals(actionStr)) {
                if (!transformed.containsKey("image_path")) {
                    if (transformed.containsKey("file_path")) {
                        transformed.put("image_path", transformed.get("file_path"));
                    } else if (transformed.containsKey("path")) {
                        transformed.put("image_path", transformed.get("path"));
                    }
                }
            } else if ("ocr_recognize_pdf".equals(actionStr)) {
                if (!transformed.containsKey("pdf_path")) {
                    if (transformed.containsKey("file_path")) {
                        transformed.put("pdf_path", transformed.get("file_path"));
                    } else if (transformed.containsKey("path")) {
                        transformed.put("pdf_path", transformed.get("path"));
                    }
                }
            } else if (actionStr.startsWith("file_")) {
                if (!transformed.containsKey("file_path")) {
                    if (transformed.containsKey("path")) {
                        transformed.put("file_path", transformed.get("path"));
                    } else if (transformed.containsKey("image_path")) {
                        transformed.put("file_path", transformed.get("image_path"));
                    } else if (transformed.containsKey("pdf_path")) {
                        transformed.put("file_path", transformed.get("pdf_path"));
                    }
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
        // 保护：result可能为null
        if (result == null) {
            return "<tool_result>\n工具: 未知\n状态: 失败\n结果: 工具执行返回null\n</tool_result>\n" +
                "请基于以上工具返回的结果回答用户的问题。如果工具执行失败，请告知用户并建议其他方式。";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("<tool_result>\n");
        sb.append("工具: ").append(result.toolName != null ? result.toolName : "未知").append("\n");
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
                        if ("calculate".equals(tool.name) || "translation".equals(tool.name) || "translate".equals(tool.name) || "network_search".equals(tool.name)) {
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
                            "database".equals(tool.name) || "web_page_reader".equals(tool.name) ||
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
            case "calculate": return "app_toolkit";
            case "database": return "database";
            case "translation": return "translation";
            case "generate_questions": return "database";
            case "search_questions": return "database";
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
    public ToolDependencyChecker getDependencyChecker() { return dependencyChecker; }
}