package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
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
}
