package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.oilquiz.app.ai.tool.FileTool;
import com.oilquiz.app.ai.tool.DatabaseTool;
import com.oilquiz.app.ai.tool.NetworkSearchTool;
import com.oilquiz.app.ai.tool.WebPageReaderTool;
import com.oilquiz.app.ai.tool.SmartResearchTool;
import com.oilquiz.app.ai.tool.SystemResourceTool;
import com.oilquiz.app.ai.tool.FileReaderTool;
import com.oilquiz.app.ai.tool.FileAnalyzerTool;
import com.oilquiz.app.ai.tool.FileGeneratorTool;
import com.oilquiz.app.ai.tool.PermissionManagerTool;
import com.oilquiz.app.ai.tool.AppToolkitAITool;
import com.oilquiz.app.ai.python.PythonExecuteTool;
import com.oilquiz.app.ai.python.PythonCalculateTool;
import com.oilquiz.app.ai.python.PythonDataAnalysisTool;
import com.oilquiz.app.ai.python.AIToolCreatorTool;

/**
 * AI工具管理器，负责管理和执行AI工具
 * 采用懒加载 + 自动卸载机制：工具只在需要时初始化，一段时间不使用后自动卸载
 */
public class AIToolManager {
    private static final String TAG = "AIToolManager";
    private static AIToolManager instance;
    private final Context context;
    private final Map<String, ToolFactory> toolFactories;
    private final Map<String, Class<? extends AITool>> toolClasses;
    private final Map<String, AITool> initializedTools;
    private final Map<String, AITool> dynamicTools;
    private final Map<String, Long> toolLastUseTime;
    private long idleTimeoutMs = 5 * 60 * 1000;
    private ScheduledExecutorService cleanupExecutor;
    private volatile boolean cleanupEnabled = true;
    
    private interface ToolFactory {
        AITool create(Context context);
    }
    
    private AIToolManager(Context context) {
        Context appContext;
        try {
            appContext = context.getApplicationContext();
        } catch (Exception e) {
            appContext = context;
        }
        this.context = appContext;
        this.toolFactories = new HashMap<>();
        this.toolClasses = new HashMap<>();
        this.initializedTools = new ConcurrentHashMap<>();
        this.dynamicTools = new ConcurrentHashMap<>();
        this.toolLastUseTime = new ConcurrentHashMap<>();
        try {
            registerToolFactories();
            Log.i(TAG, "AIToolManager initialized with lazy loading, " + toolFactories.size() + " tools registered, idle timeout: " + (idleTimeoutMs / 1000) + "s");
            startAutoCleanup();
        } catch (Throwable e) {
            Log.e(TAG, "Error initializing tools: " + e.getMessage(), e);
        }
    }
    
    /**
     * 启动自动清理机制
     */
    private void startAutoCleanup() {
        if (cleanupExecutor == null || cleanupExecutor.isShutdown()) {
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "AIToolManager-Cleanup");
                thread.setDaemon(true);
                return thread;
            });
            cleanupExecutor.scheduleWithFixedDelay(this::cleanupIdleTools, 
                idleTimeoutMs, idleTimeoutMs / 2, TimeUnit.MILLISECONDS);
            Log.i(TAG, "Auto cleanup started, interval: " + (idleTimeoutMs / 2000) + "s");
        }
    }
    
    /**
     * 清理空闲工具
     */
    private void cleanupIdleTools() {
        if (!cleanupEnabled || idleTimeoutMs <= 0) {
            return;
        }
        
        long now = System.currentTimeMillis();
        List<String> toolsToRemove = new ArrayList<>();
        
        for (Map.Entry<String, Long> entry : toolLastUseTime.entrySet()) {
            String toolName = entry.getKey();
            long lastUseTime = entry.getValue();
            
            if (now - lastUseTime > idleTimeoutMs) {
                toolsToRemove.add(toolName);
            }
        }
        
        if (!toolsToRemove.isEmpty()) {
            int removedCount = 0;
            for (String toolName : toolsToRemove) {
                if (initializedTools.containsKey(toolName)) {
                    AITool tool = initializedTools.remove(toolName);
                    toolLastUseTime.remove(toolName);
                    removedCount++;
                    Log.i(TAG, "Tool auto-unloaded (idle timeout): " + toolName);
                    
                    try {
                        if (tool instanceof AutoCloseable) {
                            ((AutoCloseable) tool).close();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing tool: " + toolName, e);
                    }
                }
            }
            
            if (removedCount > 0) {
                Log.i(TAG, "Auto cleanup completed: unloaded " + removedCount + " idle tools");
            }
        }
    }
    
    /**
     * 手动触发清理
     */
    public void triggerCleanup() {
        cleanupIdleTools();
    }
    
    /**
     * 启用/禁用自动清理
     */
    public void setCleanupEnabled(boolean enabled) {
        this.cleanupEnabled = enabled;
        Log.i(TAG, "Auto cleanup " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * 设置空闲超时时间
     * @param timeoutMs 超时时间（毫秒）
     */
    public void setIdleTimeout(long timeoutMs) {
        this.idleTimeoutMs = timeoutMs;
        Log.i(TAG, "Idle timeout set to: " + (timeoutMs / 1000) + "s");
        
        if (timeoutMs > 0 && cleanupEnabled) {
            startAutoCleanup();
        }
    }
    
    /**
     * 获取空闲超时时间
     */
    public long getIdleTimeout() {
        return idleTimeoutMs;
    }
    
    /**
     * 获取AIToolManager实例
     * @param context 上下文
     * @return AIToolManager实例
     */
    public static synchronized AIToolManager getInstance(Context context) {
        if (instance == null) {
            instance = new AIToolManager(context);
        }
        return instance;
    }
    
    /**
     * 获取AIToolManager实例（无上下文，会抛出异常）
     * @return AIToolManager实例
     * @throws IllegalStateException 当上下文为null时抛出
     */
    public static synchronized AIToolManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("AIToolManager not initialized with context");
        }
        return instance;
    }
    
    /**
     * 注册工具工厂（懒加载）
     */
    private void registerToolFactories() {
        registerToolFactory("file", FileTool.class, FileTool::new);
        registerToolFactory("database", DatabaseTool.class, DatabaseTool::new);
        registerToolFactory("network_search", NetworkSearchTool.class, NetworkSearchTool::new);
        registerToolFactory("webpage_reader", WebPageReaderTool.class, WebPageReaderTool::new);
        registerToolFactory("smart_research", SmartResearchTool.class, SmartResearchTool::new);
        registerToolFactory("system_resource", SystemResourceTool.class, SystemResourceTool::new);
        registerToolFactory("file_reader", FileReaderTool.class, FileReaderTool::new);
        registerToolFactory("file_analyzer", FileAnalyzerTool.class, FileAnalyzerTool::new);
        registerToolFactory("file_generator", FileGeneratorTool.class, FileGeneratorTool::new);
        registerToolFactory("permission_manager", PermissionManagerTool.class, PermissionManagerTool::new);
        registerToolFactory("app_operation", AppOperationTool.class, AppOperationTool::new);
        registerToolFactory("translation", TranslationTool.class, TranslationTool::new);
        registerToolFactory("location", LocationTool.class, LocationTool::new);
        registerToolFactory("ai_weather", AIWeatherManager.class, AIWeatherManager::new);
        registerToolFactory("app_toolkit", AppToolkitAITool.class, AppToolkitAITool::new);
        registerToolFactory("create_dynamic_tool", DynamicToolManagerTool.class, DynamicToolManagerTool::new);
        
        try {
            registerToolFactory("python_execute", PythonExecuteTool.class, PythonExecuteTool::new);
            registerToolFactory("python_calculate", PythonCalculateTool.class, PythonCalculateTool::new);
            registerToolFactory("python_analyze_data", PythonDataAnalysisTool.class, PythonDataAnalysisTool::new);
            registerToolFactory("ai_create_tool", AIToolCreatorTool.class, AIToolCreatorTool::new);
            Log.i(TAG, "Python tool factories registered");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register Python tool factories: " + e.getMessage());
        }
    }
    
    private void registerToolFactory(String name, Class<? extends AITool> toolClass, ToolFactory factory) {
        toolClasses.put(name, toolClass);
        toolFactories.put(name, factory);
    }
    
    /**
     * 获取或创建工具实例（懒加载）
     * @param name 工具名称
     * @return 工具实例，如果不存在则返回null
     */
    private AITool getOrCreateTool(String name) {
        AITool tool = dynamicTools.get(name);
        if (tool != null) {
            toolLastUseTime.put(name, System.currentTimeMillis());
            return tool;
        }
        
        tool = initializedTools.get(name);
        if (tool != null) {
            toolLastUseTime.put(name, System.currentTimeMillis());
            return tool;
        }
        
        ToolFactory factory = toolFactories.get(name);
        if (factory != null) {
            synchronized (factory) {
                tool = initializedTools.get(name);
                if (tool == null) {
                    try {
                        tool = factory.create(context);
                        if (tool != null) {
                            initializedTools.put(name, tool);
                            toolLastUseTime.put(name, System.currentTimeMillis());
                            Log.i(TAG, "Tool lazily initialized: " + name);
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "Error initializing tool: " + name, e);
                    }
                } else {
                    toolLastUseTime.put(name, System.currentTimeMillis());
                }
            }
        }
        
        return tool;
    }
    
    /**
     * 确保所有工具都已初始化
     */
    private void ensureAllToolsInitialized() {
        for (String name : toolFactories.keySet()) {
            if (!initializedTools.containsKey(name)) {
                getOrCreateTool(name);
            }
        }
    }
    
    /**
     * 获取所有工具
     * @return 工具列表
     */
    public List<AITool> getTools() {
        ensureAllToolsInitialized();
        List<AITool> allTools = new ArrayList<>();
        allTools.addAll(initializedTools.values());
        allTools.addAll(dynamicTools.values());
        return allTools;
    }
    
    /**
     * 获取所有工具Map（名称→工具实例）
     * @return 工具Map
     */
    public Map<String, AITool> getToolsMap() {
        ensureAllToolsInitialized();
        Map<String, AITool> allTools = new HashMap<>();
        allTools.putAll(initializedTools);
        allTools.putAll(dynamicTools);
        return allTools;
    }
    
    /**
     * 根据名称获取工具
     * @param name 工具名称
     * @return 工具实例，如果不存在则返回null
     */
    public AITool getTool(String name) {
        return getOrCreateTool(name);
    }
    
    /**
     * 执行工具
     * @param toolName 工具名称
     * @param parameters 执行参数
     * @return 执行结果
     */
    public AIToolResult executeTool(String toolName, Map<String, Object> parameters) {
        AITool tool = getOrCreateTool(toolName);
        if (tool == null) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", toolName);
            return new AIToolResult("Tool not found: " + toolName, additionalInfo);
        }
        
        try {
            return tool.execute(parameters);
        } catch (Exception e) {
            Log.e(TAG, "Error executing tool: " + toolName, e);
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", toolName);
            additionalInfo.put("error", e.getMessage());
            return new AIToolResult("Error executing tool: " + e.getMessage(), additionalInfo);
        }
    }
    
    /**
     * 获取工具描述列表
     * 优化：不触发所有工具初始化，仅从工厂获取元数据
     * @return 工具描述列表
     */
    public List<Map<String, Object>> getToolDescriptions() {
        List<Map<String, Object>> descriptions = new ArrayList<>();

        // 从工厂获取工具描述，不实际初始化工具
        for (Map.Entry<String, ToolFactory> entry : toolFactories.entrySet()) {
            String toolName = entry.getKey();
            Map<String, Object> description = getToolDescriptionFromFactory(toolName);
            if (description != null) {
                descriptions.add(description);
            }
        }

        // 添加动态工具描述（已初始化的）
        for (AITool tool : dynamicTools.values()) {
            Map<String, Object> description = new HashMap<>();
            description.put("name", tool.getName());
            description.put("description", tool.getDescription());
            description.put("parameters", tool.getParameterDescriptions());
            descriptions.add(description);
        }

        Log.i(TAG, "Returning " + descriptions.size() + " tool descriptions");
        return descriptions;
    }

    /**
     * 从工厂获取工具描述（不初始化工具实例）
     */
    private Map<String, Object> getToolDescriptionFromFactory(String toolName) {
        // 统一从 getToolDefinition 派生描述，避免两套描述不一致导致LLM收到错误参数信息
        ToolDefinition definition = getToolDefinition(toolName);
        if (definition == null) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (definition.getParameters() != null) {
            for (ParamDefinition param : definition.getParameters()) {
                StringBuilder desc = new StringBuilder(param.getDescription());
                if (param.isRequired()) {
                    desc.append("(必填)");
                }
                if (param.getDefaultValue() != null) {
                    desc.append("(默认:").append(param.getDefaultValue()).append(")");
                }
                params.put(param.getName(), desc.toString());
            }
        }
        return createToolDesc(definition.getName(), definition.getDescription(), params);
    }

    private Map<String, Object> createToolDesc(String name, String description, Map<String, String> parameters) {
        Map<String, Object> desc = new HashMap<>();
        desc.put("name", name);
        desc.put("description", description);
        desc.put("parameters", parameters);
        return desc;
    }

    /**
     * 获取所有工具的 OpenAI function calling 格式定义（JSON字符串）
     * 用于在线模型原生工具调用，格式：[{"type":"function","function":{"name","description","parameters":{...}}}]
     */
    public String getOpenAIToolDefinitions() {
        JSONArray tools = new JSONArray();
        for (String toolName : toolFactories.keySet()) {
            ToolDefinition def = getToolDefinition(toolName);
            if (def == null) continue;
            try {
                JSONObject tool = new JSONObject();
                tool.put("type", "function");

                JSONObject function = new JSONObject();
                function.put("name", def.getName());
                function.put("description", def.getDescription());

                // 构造 JSON Schema 参数定义
                JSONObject parameters = new JSONObject();
                parameters.put("type", "object");
                JSONObject properties = new JSONObject();
                JSONArray required = new JSONArray();

                if (def.getParameters() != null) {
                    for (ParamDefinition param : def.getParameters()) {
                        JSONObject prop = new JSONObject();
                        // OpenAI 类型映射: java String→string, Integer/Double→number, Boolean→boolean
                        String pType = param.getType();
                        if (pType == null || pType.isEmpty()) pType = "string";
                        prop.put("type", pType);
                        prop.put("description", param.getDescription());
                        properties.put(param.getName(), prop);
                        if (param.isRequired()) {
                            required.put(param.getName());
                        }
                    }
                }
                parameters.put("properties", properties);
                if (required.length() > 0) {
                    parameters.put("required", required);
                }
                function.put("parameters", parameters);

                tool.put("function", function);
                tools.put(tool);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to build OpenAI tool definition for " + toolName + ": " + e.getMessage());
            }
        }
        return tools.toString();
    }
    
    /**
     * 检查工具是否存在
     * @param toolName 工具名称
     * @return 是否存在
     */
    public boolean hasTool(String toolName) {
        return toolFactories.containsKey(toolName) || dynamicTools.containsKey(toolName);
    }
    
    /**
     * 注册动态工具
     * @param tool 动态工具
     */
    public void registerDynamicTool(AITool tool) {
        if (tool != null && tool.getName() != null) {
            dynamicTools.put(tool.getName(), tool);
            Log.i(TAG, "Dynamic tool registered: " + tool.getName());
        }
    }
    
    /**
     * 创建并注册动态工具
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 参数描述
     * @param executionLogic 执行逻辑
     */
    public DynamicAITool createAndRegisterDynamicTool(String name, String description,
                                                  Map<String, String> parameters,
                                                  String executionLogic) {
        DynamicAITool tool = new DynamicAITool(context, name, description, parameters, executionLogic);
        registerDynamicTool(tool);
        return tool;
    }
    
    /**
     * 卸载动态工具
     * @param name 工具名称
     */
    public void unregisterDynamicTool(String name) {
        if (dynamicTools.remove(name) != null) {
            Log.i(TAG, "Dynamic tool unregistered: " + name);
        }
    }
    
    /**
     * 检查是否为动态工具
     * @param name 工具名称
     */
    public boolean isDynamicTool(String name) {
        return dynamicTools.containsKey(name);
    }
    
    /**
     * 获取所有动态工具
     */
    public List<String> getDynamicTools() {
        return new ArrayList<>(dynamicTools.keySet());
    }
    
    /**
     * 获取已初始化的工具数量
     */
    public int getInitializedToolCount() {
        return initializedTools.size();
    }
    
    /**
     * 获取已注册的工具工厂数量
     */
    public int getRegisteredToolCount() {
        return toolFactories.size();
    }
    
    /**
     * 获取所有已注册工具的名称列表
     * 不实例化工具，仅返回名称
     */
    public List<String> getRegisteredToolNames() {
        return new ArrayList<>(toolFactories.keySet());
    }
    
    /**
     * 获取工具的Class对象，用于通过反射获取注解信息
     * 不实例化工具
     * @param toolName 工具名称
     * @return 工具Class，如果不存在则返回null
     */
    public Class<? extends AITool> getToolClass(String toolName) {
        return toolClasses.get(toolName);
    }
    
    /**
     * 手动卸载指定工具
     */
    public void unloadTool(String toolName) {
        AITool tool = initializedTools.remove(toolName);
        if (tool != null) {
            toolLastUseTime.remove(toolName);
            Log.i(TAG, "Tool manually unloaded: " + toolName);
            
            try {
                if (tool instanceof AutoCloseable) {
                    ((AutoCloseable) tool).close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error closing tool: " + toolName, e);
            }
        }
    }
    
    /**
     * 卸载所有已初始化的工具
     */
    public void unloadAllTools() {
        int count = initializedTools.size();
        for (String toolName : new ArrayList<>(initializedTools.keySet())) {
            unloadTool(toolName);
        }
        toolLastUseTime.clear();
        Log.i(TAG, "All tools unloaded, count: " + count);
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdownNow();
            Log.i(TAG, "Auto cleanup executor shutdown");
        }
        
        unloadAllTools();
        dynamicTools.clear();
        Log.i(TAG, "AIToolManager released");
    }
    
    /**
     * 获取所有工具的 OpenAI 标准格式描述
     * @return JSONArray 包含所有工具的 OpenAI function calling 格式定义
     */
    public JSONArray getToolsAsOpenAIFormat() throws JSONException {
        JSONArray toolsArray = new JSONArray();
        
        for (String toolName : toolFactories.keySet()) {
            ToolDefinition definition = getToolDefinition(toolName);
            if (definition != null) {
                toolsArray.put(definition.toOpenAIFormat());
            }
        }
        
        for (AITool tool : dynamicTools.values()) {
            ToolDefinition definition = createToolDefinitionFromAITool(tool);
            if (definition != null) {
                toolsArray.put(definition.toOpenAIFormat());
            }
        }
        
        Log.i(TAG, "Returning " + toolsArray.length() + " tools in OpenAI format");
        return toolsArray;
    }
    
    /**
     * 获取工具的 ToolDefinition 对象
     */
    public ToolDefinition getToolDefinition(String toolName) {
        switch (toolName) {
            case "ai_weather":
                return ToolDefinition.builder("ai_weather", "天气查询工具，获取指定城市的天气信息")
                    .addParameter("action", "string", "操作类型: current/forecast/hourly/air_quality/alerts/indices/all", false, "current")
                    .addParameter("city", "string", "城市名称，如：北京、上海", false)
                    .addParameter("lat", "number", "纬度", false)
                    .addParameter("lon", "number", "经度", false)
                    .category("weather")
                    .build();
            case "network_search":
                return ToolDefinition.builder("network_search", "网络搜索工具（秘塔搜索引擎驱动），支持搜索、智能问答、网页读取")
                    .addParameter("action", "string", "操作类型: search(搜索)/ask(智能问答)/read_url(网页读取)/get_webpage/extract_info/summarize/search_and_read/smart_search", false, "search")
                    .addParameter("query", "string", "搜索关键词（用于search等操作）", false)
                    .addParameter("question", "string", "问题（用于ask操作，秘塔智能问答，返回答案+引用来源）", false)
                    .addParameter("model", "string", "问答模型: concise(简洁)/detail(深入)/research(研究)，默认concise（用于ask操作）", false, "concise")
                    .addParameter("keyword", "string", "搜索关键词（query的别名）", false)
                    .addParameter("limit", "integer", "结果数量限制，默认5", false, 5)
                    .addParameter("num_results", "integer", "返回结果数量（limit的别名）", false, 5)
                    .addParameter("url", "string", "网页URL（用于read_url/get_webpage操作）", false)
                    .category("search")
                    .build();
            case "python_calculate":
                return ToolDefinition.builder("python_calculate", "使用Python进行数学计算")
                    .addParameter("expression", "string", "数学表达式，如：2+3*4", true)
                    .addParameter("task", "string", "任务描述（可选）", false)
                    .category("calculator")
                    .build();
            case "translation":
                return ToolDefinition.builder("translation", "翻译工具，翻译文本")
                    .addParameter("text", "string", "待翻译文本", true)
                    .addParameter("target_lang", "string", "目标语言，如：zh, en, ja, ko", false, "zh")
                    .category("translation")
                    .build();
            case "file_reader":
                return ToolDefinition.builder("file_reader", "文件阅读工具，支持读取文本文件、按行读取、搜索文本、提取实体、预览")
                    .addParameter("file_path", "string", "文件路径", true)
                    .addParameter("action", "string", "操作类型: read(默认)/read_lines/extract_text/search_text/extract_entities/preview", false, "read")
                    .addParameter("encoding", "string", "文件编码(默认UTF-8)", false, "UTF-8")
                    .addParameter("startLine", "integer", "起始行号(read_lines用)", false)
                    .addParameter("endLine", "integer", "结束行号(read_lines用)", false)
                    .addParameter("keyword", "string", "搜索关键词(search_text用)", false)
                    .addParameter("regex", "string", "正则表达式(search_text用)", false)
                    .addParameter("maxLength", "integer", "最大读取长度(read用)", false)
                    .category("file")
                    .build();
            case "file_analyzer":
                return ToolDefinition.builder("file_analyzer", "文件分析工具，分析文件内容")
                    .addParameter("file_path", "string", "文件路径", true)
                    .addParameter("analysis_type", "string", "分析类型（可选）", false)
                    .category("file")
                    .build();
            case "file_generator":
                return ToolDefinition.builder("file_generator", "文件生成工具，生成文本/JSON/配置/Markdown等文件")
                    .addParameter("action", "string", "操作类型: create(默认)/append/json/config/markdown/template/report/copy/delete", false, "create")
                    .addParameter("file_name", "string", "文件名/路径", true)
                    .addParameter("content", "string", "文件内容(create/append/markdown用)", false)
                    .addParameter("format", "string", "文件格式(可选)", false)
                    .addParameter("encoding", "string", "文件编码(默认UTF-8)", false, "UTF-8")
                    .addParameter("json_data", "object", "JSON数据(json操作用)", false)
                    .addParameter("config", "object", "配置键值对(config操作用)", false)
                    .addParameter("title", "string", "标题(markdown/report用)", false)
                    .addParameter("sections", "array", "章节列表(markdown用)", false)
                    .addParameter("source_path", "string", "源文件路径(copy用)", false)
                    .category("file")
                    .build();
            case "database":
                return ToolDefinition.builder("database", "数据库操作工具，用于执行题目查询、用户管理、分数记录等操作")
                    .addParameter("action", "string", "操作类型: execute_query/get_questions/search_questions/get_question_count/get_question_statistics/get_all_categories/get_all_question_types/get_question_by_id/add_questions/update_question/delete_question/clear_all_questions/get_user/add_user/get_score_history/add_score/get_average_score/get_database_version", true)
                    .addParameter("query", "string", "SQL查询语句(execute_query用，实际按关键字路由)", false)
                    .addParameter("keyword", "string", "搜索关键词(search_questions用)", false)
                    .addParameter("id", "string", "题目/用户ID(get_question_by_id/update_question/delete_question用)", false)
                    .addParameter("category", "string", "题目分类", false)
                    .addParameter("type", "string", "题目类型", false)
                    .addParameter("difficulty", "integer", "难度: 1-简单, 2-中等, 3-困难", false)
                    .addParameter("page", "integer", "页码(get_questions用)", false)
                    .addParameter("page_size", "integer", "每页数量(get_questions用)", false)
                    .addParameter("questions", "array", "题目列表(add_questions用): [{questionText,optionA,optionB,optionC,optionD,correctAnswer,explanation,category,questionType,difficulty}]", false)
                    .addParameter("username", "string", "用户名(get_user/add_user用)", false)
                    .addParameter("userId", "string", "用户ID(get_score_history/get_average_score用)", false)
                    .addParameter("email", "string", "邮箱(add_user用)", false)
                    .addParameter("phone", "string", "电话(add_user用)", false)
                    .addParameter("password", "string", "密码(add_user用)", false)
                    .addParameter("questionText", "string", "题目文本(update_question用)", false)
                    .addParameter("optionA", "string", "选项A(update_question用)", false)
                    .addParameter("optionB", "string", "选项B(update_question用)", false)
                    .addParameter("optionC", "string", "选项C(update_question用)", false)
                    .addParameter("optionD", "string", "选项D(update_question用)", false)
                    .addParameter("correctAnswer", "string", "正确答案(update_question用)", false)
                    .addParameter("explanation", "string", "解析(update_question用)", false)
                    .addParameter("questionType", "string", "题目类型(add_questions/update_question用)", false)
                    .addParameter("score", "integer", "分数(add_score用)", false)
                    .addParameter("totalQuestions", "integer", "总题数(add_score用)", false)
                    .addParameter("correctCount", "integer", "正确数(add_score用)", false)
                    .addParameter("quizType", "string", "答题类型(add_score用)", false)
                    .category("data")
                    .build();
            case "smart_research":
                return ToolDefinition.builder("smart_research", "智能研究工具，整合搜索和阅读功能，自动完成搜索→选择→阅读→摘要的完整研究流程")
                    .addParameter("action", "string", "操作类型: research(默认)/quick_search/deep_read/summarize_topic", false, "research")
                    .addParameter("topic", "string", "研究主题(research/quick_search/summarize_topic用，等价于query)", false)
                    .addParameter("query", "string", "查询词(topic的别名，二者传其一即可)", false)
                    .addParameter("depth", "integer", "研究深度(保留参数)", false, 1)
                    .addParameter("maxResults", "integer", "最大结果数(默认5)", false, 5)
                    .addParameter("includeDetails", "boolean", "是否获取详情内容(默认true)", false, true)
                    .addParameter("urls", "array", "URL列表(deep_read用)", false)
                    .category("research")
                    .build();
            case "location":
                return ToolDefinition.builder("location", "位置查询工具，获取当前位置信息")
                    .addParameter("action", "string", "操作类型: get_current/get_city/get_coordinates", false, "get_current")
                    .category("location")
                    .build();
            case "webpage_reader":
                return ToolDefinition.builder("webpage_reader", "网页阅读工具，用于获取网页内容、提取关键信息、生成智能摘要")
                    .addParameter("action", "string", "操作类型: read(默认)/extract/summarize/read_multiple/follow_links", false, "read")
                    .addParameter("url", "string", "网页URL(read/extract/summarize/follow_links用，与content二选一)", false)
                    .addParameter("urls", "array", "URL列表(read_multiple用)", false)
                    .addParameter("content", "string", "网页内容(extract/summarize用，与url二选一)", false)
                    .addParameter("query", "string", "搜索查询词(相关性计算用)", false)
                    .addParameter("maxDepth", "integer", "最大链接深度(follow_links用，默认2)", false, 2)
                    .addParameter("maxLinks", "integer", "最大链接数量(follow_links用，默认10)", false, 10)
                    .category("web")
                    .build();
            case "system_resource":
                return ToolDefinition.builder("system_resource", "系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话等系统级操作")
                    .addParameter("action", "string", "操作类型: open_app/open_url/send_sms/make_call/list_apps/get_app_info", false, "open_app")
                    .addParameter("app_name", "string", "应用名称(如微信、QQ、支付宝等)", false)
                    .addParameter("url", "string", "URL地址", false)
                    .addParameter("phone_number", "string", "电话号码", false)
                    .addParameter("message", "string", "短信内容", false)
                    .addParameter("params", "string", "附加参数JSON", false)
                    .category("system")
                    .build();
            case "python_execute":
                return ToolDefinition.builder("python_execute", "执行Python代码")
                    .addParameter("code", "string", "Python代码（可选）", false)
                    .addParameter("task", "string", "任务描述（可选）", false)
                    .addParameter("context", "string", "上下文数据（可选）", false)
                    .category("python")
                    .build();
            case "python_analyze_data":
                return ToolDefinition.builder("python_analyze_data", "使用Python分析数据")
                    .addParameter("data", "string", "数据（可选）", false)
                    .addParameter("task", "string", "任务描述（可选）", false)
                    .category("python")
                    .build();
            case "file":
                return ToolDefinition.builder("file", "文件操作工具，用于获取文件信息、读取文件内容、列出目录文件")
                    .addParameter("action", "string", "操作类型: get_file_info, read_file, list_files", true)
                    .addParameter("file_path", "string", "文件路径（用于get_file_info和read_file操作）", false)
                    .addParameter("directory_path", "string", "目录路径（用于list_files操作）", false)
                    .category("file")
                    .build();
            case "app_operation":
                return ToolDefinition.builder("app_operation", "应用内部页面跳转工具，支持跳转到用户、题库、答题、学习计划、错题本等各种页面")
                    .addParameter("action", "string", "操作类型: navigate/list_pages/go_home/go_back", false, "navigate")
                    .addParameter("page", "string", "页面名称(如user/question/quiz/study_plan/wrong_question/note/ocr/ai等)", false)
                    .category("app")
                    .build();
            case "create_dynamic_tool":
                return ToolDefinition.builder("create_dynamic_tool", "动态创建和管理AI工具")
                    .addParameter("action", "string", "操作类型: create/update/delete/list", false, "list")
                    .addParameter("tool_name", "string", "工具名称", false)
                    .addParameter("description", "string", "工具描述", false)
                    .addParameter("parameters", "string", "参数定义JSON", false)
                    .addParameter("logic", "string", "执行逻辑脚本", false)
                    .category("tool")
                    .build();
            case "permission_manager":
                return ToolDefinition.builder("permission_manager", "智能权限管理工具，支持权限检查、请求和管理功能")
                    .addParameter("action", "string", "操作类型: check/check_all/request/request_and_wait/get_status/list_permissions/explain_permission/can_request", false, "check")
                    .addParameter("permission", "string", "权限名称（如camera/位置/录音/存储/拨打电话/发送短信等）", false)
                    .addParameter("permissions", "array", "权限列表（用于check_all操作）", false)
                    .category("system")
                    .build();
            case "app_toolkit":
                return ToolDefinition.builder("app_toolkit", "应用工具集，聚合天气/计算/OCR/图像/文件/网页等能力，通过action指定具体操作")
                    .addParameter("action", "string",
                        "操作类型(必填)。可选值:\n" +
                        "  天气: weather_current, weather_forecast, weather_hourly, weather_air, weather_alerts, weather_indices, weather_all\n" +
                        "  计算: calculate\n" +
                        "  OCR: ocr_recognize, ocr_recognize_pdf, ocr_set_language, ocr_get_language\n" +
                        "  图像识别: image_label_recognize, object_detect\n" +
                        "  图像处理: image_save, image_scale, image_crop, image_rotate, image_generate_color, image_generate_text\n" +
                        "  文件解析: file_parse_text, file_parse_csv, file_parse_json, file_read_lines, file_get_type\n" +
                        "  网页解析: web_parse_html, web_get_title, web_get_links, web_get_images, web_get_text\n" +
                        "  其他: get_info, get_guide", true)
                    .addParameter("image_path", "string", "图片路径(OCR/图像操作使用)", false)
                    .addParameter("file_path", "string", "文件路径(文件解析操作使用)", false)
                    .addParameter("expression", "string", "数学表达式(calculate操作使用)", false)
                    .addParameter("url", "string", "网页URL(网页解析操作使用)", false)
                    .addParameter("language", "string", "OCR语言(可选)", false)
                    .addParameter("width", "integer", "宽度(图像处理使用)", false)
                    .addParameter("height", "integer", "高度(图像处理使用)", false)
                    .addParameter("city", "string", "城市名(天气操作使用)", false)
                    .category("app")
                    .build();
            case "ai_create_tool":
                return ToolDefinition.builder("ai_create_tool", "AI创建工具，使用AI自动生成新工具")
                    .addParameter("tool_name", "string", "工具名称", true)
                    .addParameter("description", "string", "工具描述", true)
                    .addParameter("parameters", "string", "参数定义", false)
                    .addParameter("logic", "string", "执行逻辑", false)
                    .category("tool")
                    .build();
            default:
                return null;
        }
    }
    
    /**
     * 从 AITool 实例创建 ToolDefinition
     */
    private ToolDefinition createToolDefinitionFromAITool(AITool tool) {
        try {
            ToolDefinition.Builder builder = ToolDefinition.builder(
                tool.getName(),
                tool.getDescription()
            );
            
            Map<String, String> paramDescriptions = tool.getParameterDescriptions();
            if (paramDescriptions != null) {
                for (Map.Entry<String, String> entry : paramDescriptions.entrySet()) {
                    builder.addParameter(entry.getKey(), "string", entry.getValue(), false);
                }
            }
            
            return builder.build();
        } catch (Exception e) {
            Log.e(TAG, "Error creating ToolDefinition from AITool: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取工具的 OpenAI 格式字符串描述（用于 Prompt）
     */
    public String getToolsForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("可用工具列表:\n\n");
        
        for (String toolName : toolFactories.keySet()) {
            ToolDefinition definition = getToolDefinition(toolName);
            if (definition != null) {
                sb.append("工具名称: ").append(definition.getName()).append("\n");
                sb.append("功能描述: ").append(definition.getDescription()).append("\n");
                sb.append("参数:\n");
                for (ParamDefinition param : definition.getParameters()) {
                    sb.append("  - ").append(param.getName());
                    sb.append(" (").append(param.getType()).append(")");
                    if (param.isRequired()) sb.append(" [必填]");
                    if (param.getDefaultValue() != null) {
                        sb.append(" 默认: ").append(param.getDefaultValue());
                    }
                    sb.append(": ").append(param.getDescription()).append("\n");
                }
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }
}
