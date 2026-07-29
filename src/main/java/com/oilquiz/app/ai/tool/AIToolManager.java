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
        switch (toolName) {
            case "file":
                return createToolDesc("file", "文件操作工具", Map.of("path", "文件路径", "action", "操作类型: read/write/delete/list"));
            case "database":
                return createToolDesc("database", "数据库查询工具", Map.of("query", "SQL查询语句", "action", "操作类型"));
            case "network_search":
                return createToolDesc("network_search", "网络搜索工具", Map.of("keyword", "搜索关键词", "num_results", "结果数量(可选)"));
            case "webpage_reader":
                return createToolDesc("webpage_reader", "网页内容读取工具", Map.of("url", "网页URL"));
            case "smart_research":
                return createToolDesc("smart_research", "智能研究工具", Map.of("topic", "研究主题", "depth", "研究深度(可选)"));
            case "system_resource":
                return createToolDesc("system_resource", "系统资源查询工具", Map.of("action", "操作类型: open_app/send_sms等", "params", "参数(可选)"));
            case "file_reader":
                return createToolDesc("file_reader", "文件读取工具", Map.of("file_path", "文件路径"));
            case "file_analyzer":
                return createToolDesc("file_analyzer", "文件分析工具", Map.of("file_path", "文件路径", "analysis_type", "分析类型(可选)"));
            case "file_generator":
                return createToolDesc("file_generator", "文件生成工具", Map.of("file_name", "文件名", "content", "文件内容", "format", "格式(可选)"));
            case "permission_manager":
                return createToolDesc("permission_manager", "权限管理工具", Map.of("action", "操作类型: check/request/request_and_wait等", "permission", "权限名称"));
            case "app_operation":
                return createToolDesc("app_operation", "应用操作工具", Map.of("action", "操作类型"));
            case "translation":
                return createToolDesc("translation", "翻译工具", Map.of("text", "待翻译文本", "target_lang", "目标语言(可选)"));
            case "location":
                return createToolDesc("location", "位置查询工具", Map.of("action", "操作类型: get_current/get_city/get_coordinates"));
            case "ai_weather":
                return createToolDesc("ai_weather", "天气查询工具", Map.of("action", "操作类型: current/forecast/hourly/air_quality/alerts/indices/all", "city", "城市名称", "lat", "纬度", "lon", "经度"));
            case "app_toolkit":
                return createToolDesc("app_toolkit", "应用工具集", Map.of("action", "操作类型: weather_current/weather_forecast/calculate/ocr_recognize等"));
            case "create_dynamic_tool":
                return createToolDesc("create_dynamic_tool", "动态创建和管理AI工具", Map.of("action", "操作类型: create/update/delete/list", "tool_name", "工具名称", "description", "工具描述", "parameters", "参数定义JSON", "logic", "执行逻辑脚本"));
            case "python_execute":
                return createToolDesc("python_execute", "执行Python代码", Map.of("code", "Python代码(可选)", "task", "任务描述(可选)", "context", "上下文数据(可选)"));
            case "python_calculate":
                return createToolDesc("python_calculate", "使用Python进行数学计算", Map.of("expression", "数学表达式", "task", "任务描述(可选)"));
            case "python_analyze_data":
                return createToolDesc("python_analyze_data", "使用Python分析数据", Map.of("data", "数据(可选)", "task", "任务描述(可选)"));
            case "ai_create_tool":
                return createToolDesc("ai_create_tool", "AI创建工具", Map.of("tool_name", "工具名称", "description", "工具描述", "parameters", "参数定义", "logic", "执行逻辑"));
            default:
                return null;
        }
    }

    private Map<String, Object> createToolDesc(String name, String description, Map<String, String> parameters) {
        Map<String, Object> desc = new HashMap<>();
        desc.put("name", name);
        desc.put("description", description);
        desc.put("parameters", parameters);
        return desc;
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
                return ToolDefinition.builder("network_search", "网络搜索工具，搜索网络信息")
                    .addParameter("action", "string", "操作类型: search/get_webpage/extract_info/summarize/search_and_read/smart_search", false, "search")
                    .addParameter("query", "string", "搜索关键词", true)
                    .addParameter("keyword", "string", "搜索关键词（query的别名）", false)
                    .addParameter("limit", "integer", "结果数量限制，默认5", false, 5)
                    .addParameter("num_results", "integer", "返回结果数量（limit的别名）", false, 5)
                    .addParameter("url", "string", "网页URL（用于get_webpage操作）", false)
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
                return ToolDefinition.builder("file_reader", "文件读取工具，读取文件内容")
                    .addParameter("file_path", "string", "文件路径", true)
                    .category("file")
                    .build();
            case "file_analyzer":
                return ToolDefinition.builder("file_analyzer", "文件分析工具，分析文件内容")
                    .addParameter("file_path", "string", "文件路径", true)
                    .addParameter("analysis_type", "string", "分析类型（可选）", false)
                    .category("file")
                    .build();
            case "file_generator":
                return ToolDefinition.builder("file_generator", "文件生成工具，生成文件")
                    .addParameter("file_name", "string", "文件名", true)
                    .addParameter("content", "string", "文件内容", true)
                    .addParameter("format", "string", "文件格式（可选）", false)
                    .category("file")
                    .build();
            case "database":
                return ToolDefinition.builder("database", "数据库操作工具，用于执行题目查询、用户管理、分数记录等操作")
                    .addParameter("action", "string", "操作类型: execute_query/get_questions/search_questions/get_question_count/get_question_statistics/get_question_by_id/add_questions/update_question/delete_question/get_user/add_user/get_score_history/add_score/get_average_score", true)
                    .addParameter("query", "string", "SQL查询语句", false)
                    .addParameter("keyword", "string", "搜索关键词", false)
                    .addParameter("id", "string", "题目/用户ID", false)
                    .addParameter("category", "string", "题目分类", false)
                    .addParameter("type", "string", "题目类型", false)
                    .addParameter("difficulty", "integer", "难度: 1-简单, 2-中等, 3-困难", false)
                    .addParameter("page", "integer", "页码", false)
                    .addParameter("page_size", "integer", "每页数量", false)
                    .category("data")
                    .build();
            case "smart_research":
                return ToolDefinition.builder("smart_research", "智能研究工具，整合搜索和阅读功能，自动完成搜索→选择→阅读→摘要的完整研究流程")
                    .addParameter("topic", "string", "研究主题", true)
                    .addParameter("depth", "integer", "研究深度(默认1)", false, 1)
                    .addParameter("maxResults", "integer", "最大结果数(默认5)", false, 5)
                    .category("research")
                    .build();
            case "location":
                return ToolDefinition.builder("location", "位置查询工具，获取当前位置信息")
                    .addParameter("action", "string", "操作类型: get_current/get_city/get_coordinates", false, "get_current")
                    .category("location")
                    .build();
            case "webpage_reader":
                return ToolDefinition.builder("webpage_reader", "网页阅读工具，用于获取网页内容、提取关键信息、生成智能摘要")
                    .addParameter("action", "string", "操作类型: read, extract, summarize, read_multiple, follow_links", false, "read")
                    .addParameter("url", "string", "网页URL", true)
                    .addParameter("content", "string", "网页内容(与url二选一)", false)
                    .addParameter("query", "string", "搜索查询词", false)
                    .addParameter("maxDepth", "integer", "最大链接深度(默认2)", false, 2)
                    .addParameter("maxLinks", "integer", "最大链接数量(默认10)", false, 10)
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
                return ToolDefinition.builder("app_toolkit", "应用工具集，提供多种实用功能")
                    .addParameter("action", "string", "操作类型: weather_current/weather_forecast/calculate/ocr_recognize等", true)
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
