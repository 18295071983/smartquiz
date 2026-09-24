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

import com.oilquiz.app.ai.tool.DatabaseTool;
import com.oilquiz.app.ai.tool.NetworkSearchTool;
import com.oilquiz.app.ai.tool.WebPageReaderTool;
import com.oilquiz.app.ai.tool.SmartResearchTool;
import com.oilquiz.app.ai.tool.SystemResourceTool;
import com.oilquiz.app.ai.tool.FileReaderTool;
import com.oilquiz.app.ai.tool.FileAnalyzerTool;
import com.oilquiz.app.ai.tool.FileGeneratorTool;
import com.oilquiz.app.ai.tool.PermissionManagerTool;
import com.oilquiz.app.ai.python.PythonExecuteTool;
import com.oilquiz.app.ai.python.PythonCalculateTool;
import com.oilquiz.app.ai.python.PythonDataAnalysisTool;
import com.oilquiz.app.ai.python.PythonWebReaderTool;
import com.oilquiz.app.ai.python.PythonFileOpsTool;
import com.oilquiz.app.ai.python.PythonChartTool;
import com.oilquiz.app.ai.python.AIToolCreatorTool;
import com.oilquiz.app.ai.knowledge.KnowledgeBaseTool;

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
            // 恢复持久化的动态工具（模型创建的跨重启保留）
            loadDynamicTools();
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
        registerToolFactory("database", DatabaseTool.class, DatabaseTool::new);
        registerToolFactory("network_search", NetworkSearchTool.class, NetworkSearchTool::new);
        registerToolFactory("webpage_reader", WebPageReaderTool.class, WebPageReaderTool::new);
        registerToolFactory("smart_research", SmartResearchTool.class, SmartResearchTool::new);
        registerToolFactory("system_resource", SystemResourceTool.class, SystemResourceTool::new);
        registerToolFactory("file_reader", FileReaderTool.class, FileReaderTool::new);
        registerToolFactory("file_analyzer", FileAnalyzerTool.class, FileAnalyzerTool::new);
        registerToolFactory("file_generator", FileGeneratorTool.class, FileGeneratorTool::new);
        registerToolFactory("export_apk", ExportApkTool.class, ExportApkTool::new); // Agent 导出 HTML→APK（设备端壳打包）
        registerToolFactory("permission_manager", PermissionManagerTool.class, PermissionManagerTool::new);
        registerToolFactory("app_operation", AppOperationTool.class, AppOperationTool::new);
        registerToolFactory("location", LocationTool.class, LocationTool::new);
        registerToolFactory("ai_weather", AIWeatherManager.class, AIWeatherManager::new);
        registerToolFactory("create_dynamic_tool", DynamicToolManagerTool.class, DynamicToolManagerTool::new);
        registerToolFactory("dashscope_media", DashscopeMediaTool.class, DashscopeMediaTool::new);
        registerToolFactory("ui_component", SystemUIComponentTool.class, SystemUIComponentTool::new);
        registerToolFactory("ui_component_plugin", UIComponentPluginTool.class, UIComponentPluginTool::new);
        registerToolFactory("control_lookup", ControlLookupTool.class, ControlLookupTool::new);
        registerToolFactory("layout_editor", LayoutEditorTool.class, LayoutEditorTool::new);
        registerToolFactory("tool_registry", ToolRegistryTool.class, ToolRegistryTool::new);
        registerToolFactory("update_models_profile", UpdateModelsProfileTool.class, UpdateModelsProfileTool::new);
        registerToolFactory("get_models_profile", GetModelsProfileTool.class, GetModelsProfileTool::new);
        registerToolFactory("voice_input", VoiceInputTool.class, VoiceInputTool::new);
        registerToolFactory("speech_synthesis", SpeechSynthesisTool.class, SpeechSynthesisTool::new);
        registerToolFactory("excel_tool", ExcelTool.class, ExcelTool::new);
        registerToolFactory("ocr_recognize", OCRRecognizeTool.class, OCRRecognizeTool::new);
        registerToolFactory("video_to_player", VideoToPlayerTool.class, VideoToPlayerTool::new);
        registerToolFactory("system_connect", SystemConnectTool.class, SystemConnectTool::new);
        // 纯本地工具：文本处理（JSON/编码/正则）与单位换算（零网络依赖）
        registerToolFactory("text_tools", TextToolsTool.class, TextToolsTool::new);
        registerToolFactory("unit_converter", UnitConverterTool.class, UnitConverterTool::new);
        // 知识库：独立 SQLite+FTS4，全文检索与管理（内容由用户维护）
        registerToolFactory("knowledge_base", KnowledgeBaseTool.class, KnowledgeBaseTool::new);
        
        try {
            registerToolFactory("python_execute", PythonExecuteTool.class, PythonExecuteTool::new);
            registerToolFactory("python_calculate", PythonCalculateTool.class, PythonCalculateTool::new);
            registerToolFactory("python_analyze_data", PythonDataAnalysisTool.class, PythonDataAnalysisTool::new);
            registerToolFactory("python_web_reader", PythonWebReaderTool.class, PythonWebReaderTool::new);
            registerToolFactory("python_file_ops", PythonFileOpsTool.class, PythonFileOpsTool::new);
            registerToolFactory("python_chart", PythonChartTool.class, PythonChartTool::new);
            registerToolFactory("ai_create_tool", AIToolCreatorTool.class, AIToolCreatorTool::new);
            registerToolFactory("time_date", TimeDateTool.class, TimeDateTool::new);
            registerToolFactory("calculator", CalculatorTool.class, CalculatorTool::new);
            registerToolFactory("js_execute", JsExecuteTool.class, JsExecuteTool::new); // JS执行（WebView内核，弥补无Node缺口）
            registerToolFactory("pip_install", PipInstallTool.class, PipInstallTool::new); // 运行时安装纯Python包
            registerToolFactory("screen_capture", ScreenCaptureTool.class, ScreenCaptureTool::new); // 截屏（MediaProjection授权）
            registerToolFactory("screen_watch", ScreenWatchTool.class, ScreenWatchTool::new); // 盯梢（监控屏幕直到目标出现/消失/画面变化）
            registerToolFactory("web_render", WebRenderTool.class, WebRenderTool::new); // 网页渲染浏览（DOM文本+截图）
            registerToolFactory("image_gen", ImageGenTool.class, ImageGenTool::new);
            registerToolFactory("memory", MemoryTool.class, MemoryTool::new);
            registerToolFactory("chat_history", ChatHistoryTool.class, ChatHistoryTool::new); // 对话历史（跨会话上下文，读本地持久化历史文件）
            // 2026-09-25：抖音下载工具由动态工具固化为内置工具（脚本内置 assets/douyin_downloader.py，v2.4）
            registerToolFactory("douyin_downloader", DouyinDownloaderTool.class, DouyinDownloaderTool::new);
        registerToolFactory("task", TaskTool.class, TaskTool::new); // 维度四 P0-1：任务状态跟踪
        registerToolFactory("import_list_files", ImportListFilesTool.class, ImportListFilesTool::new); // AI导入：发现题库文件
        registerToolFactory("import_start", ImportStartTool.class, ImportStartTool::new); // AI导入：启动异步导入
        registerToolFactory("import_status", ImportStatusTool.class, ImportStatusTool::new); // AI导入：查询进度
        registerToolFactory("import_cancel", ImportCancelTool.class, ImportCancelTool::new); // AI导入：取消
        registerToolFactory("import_decide", ImportDecideTool.class, ImportDecideTool::new); // AI导入：决策回传（ui_component 交互后）
        registerToolFactory("reminder", ReminderTool.class, ReminderTool::new); // 维度十 PER-02：定时提醒
            registerToolFactory("workspace", WorkspaceTool.class, WorkspaceTool::new);
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
        // 用户动态工具优先（含与内置别名同名的工具，如用户自建 system_ui_control 不被别名劫持）
        AITool dyn = dynamicTools.get(name);
        if (dyn != null) {
            toolLastUseTime.put(name, System.currentTimeMillis());
            return dyn;
        }
        name = resolveToolAlias(name);
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
        // 模糊解析：模型可能把工具名猜错（weather→ai_weather 等），先归一化再执行
        String resolved = resolveToolNameFuzzy(toolName);
        if (resolved == null) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", toolName);
            return new AIToolResult("工具不存在: " + toolName + "（可用 tool_registry(list) 查看全部工具）", additionalInfo);
        }
        AITool tool = getOrCreateTool(resolved);
        if (tool == null) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", toolName);
            return new AIToolResult("Tool not found: " + toolName, additionalInfo);
        }

        // TL-06 权限与调用联动：工具执行前检查所需 Android 权限，未授权先返回引导
        //（避免执行中才失败；模型可用 permission_manager 引导用户授权后重试）
        String permissionError = checkToolPermission(resolved, parameters);
        if (permissionError != null) {
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", resolved);
            additionalInfo.put("requiresPermission", true);
            return AIToolResult.fail(permissionError, additionalInfo);
        }
        
        try {
            // 参数类型归一：引导卡片等UI入口传的都是String，
            // 而工具内部多处用 (Integer)/(Boolean) 强转，不转换会 ClassCastException
            normalizeParamTypes(parameters);
            AIToolResult result = tool.execute(parameters);
            // 工具侧桥接：工具结果携带结构化 UI 组件时收集，本轮生成完成时附加到 AI 消息
            if (result != null && result.getComponent() != null) {
                com.oilquiz.app.ai.chat.component.ComponentCollector.collect(result.getComponent());
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error executing tool: " + toolName, e);
            Map<String, Object> additionalInfo = new HashMap<>();
            additionalInfo.put("toolName", toolName);
            additionalInfo.put("error", e.getMessage());
            return new AIToolResult("Error executing tool: " + e.getMessage(), additionalInfo);
        }
    }

    /**
     * TL-06 权限与调用联动：工具执行前权限门。
     * 仅对几乎必然需要运行时权限的工具做前置检查（粗粒度工具级），
     * 操作级差异（如 voice_input 的 recognize 不需要录音）在工具内自行处理。
     * 未授权返回可读引导；上下文为空或检查失败时放行（不阻塞工具）。
     */
    private String checkToolPermission(String toolName, Map<String, Object> parameters) {
        try {
            if (context == null || toolName == null) return null;
            String androidPermission = null;
            if ("location".equals(toolName)) {
                androidPermission = android.Manifest.permission.ACCESS_FINE_LOCATION;
            } else if ("voice_input".equals(toolName)) {
                Object actionObj = parameters != null ? parameters.get("action") : null;
                String action = actionObj != null ? String.valueOf(actionObj) : "";
                if (action.startsWith("record")) { // record / record_and_recognize 需录音权限
                    androidPermission = android.Manifest.permission.RECORD_AUDIO;
                }
            }
            if (androidPermission == null) return null;
            int result = context.checkSelfPermission(androidPermission);
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
            String friendly = "location".equals(toolName) ? "位置权限" : "录音权限";
            return "工具 " + toolName + " 需要" + friendly + "（" + androidPermission + "），请先调用 permission_manager 检查并引导用户授权后重试";
        } catch (Throwable t) {
            return null; // 检查失败放行，不因权限门阻塞工具
        }
    }

    /** 已知应为整数类型的参数名（白名单，避免误转 id/编号类字符串参数） */
    private static final java.util.Set<String> INTEGER_PARAM_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "page", "page_size", "difficulty", "limit", "num_results", "maxResults", "maxDepth", "maxLinks",
            "startLine", "endLine", "start_line", "end_line", "maxLength",
            "width", "height", "x", "y", "angle",
            "sheet_index", "max_rows", "max_items",
            "duration_seconds", "timeout_seconds",
            "row", "row_start", "row_end"
    ));

    /** 已知应为布尔类型的参数名 */
    private static final java.util.Set<String> BOOLEAN_PARAM_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "regex", "newLine", "allowMultiple", "includeDetails", "enableThinking", "save_voice"
    ));

    /**
     * 将字符串形式的参数按白名单转换为 Integer/Boolean，
     * 保证引导卡片（全String传参）与模型调用（原生类型传参）两条链路都能正常执行。
     */
    private void normalizeParamTypes(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return;
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof String)) continue;
            String str = ((String) value).trim();
            if (str.isEmpty()) continue;
            String key = entry.getKey();
            try {
                if (INTEGER_PARAM_NAMES.contains(key) && str.matches("-?\\d+")) {
                    entry.setValue(Integer.parseInt(str));
                } else if (BOOLEAN_PARAM_NAMES.contains(key)
                        && (str.equalsIgnoreCase("true") || str.equalsIgnoreCase("false"))) {
                    entry.setValue(Boolean.parseBoolean(str));
                }
            } catch (NumberFormatException ignored) { }
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
                // 工具调用体系 P0-1：附适用场景
                attachWhenToUse(description, getToolDefinition(toolName));
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
                params.put(param.getName(), desc.toString());
            }
        }
        return createToolDesc(definition.getName(), definition.getDescription(), params);
    }

    /** 工具调用体系 P0-1：给工具描述附加适用场景（when_to_use），辅助选型 */
    private Map<String, Object> attachWhenToUse(Map<String, Object> desc, ToolDefinition definition) {
        if (desc != null && definition != null) {
            String wtu = definition.getWhenToUse();
            if (wtu != null && !wtu.isEmpty()) {
                desc.put("when_to_use", wtu);
            }
        }
        return desc;
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
     * 包含：内置工具工厂 + 用户动态工具（修复：此前只遍历工厂，在线模型看不到动态工具）。
     */
    public String getOpenAIToolDefinitions() {
        JSONArray tools = new JSONArray();
        java.util.Set<String> added = new java.util.HashSet<>();
        // 内置工具工厂
        for (String toolName : toolFactories.keySet()) {
            ToolDefinition def = getToolDefinition(toolName);
            if (def == null) continue;
            try {
                tools.put(def.toOpenAIFormat());
                added.add(def.getName());
            } catch (JSONException e) {
                Log.w(TAG, "Failed to build OpenAI tool definition for " + toolName + ": " + e.getMessage());
            }
        }
        // 动态工具（create_dynamic_tool / ai_create_tool 创建，含 java 与 python 两类）
        for (AITool tool : dynamicTools.values()) {
            if (tool == null || added.contains(tool.getName())) continue;
            ToolDefinition def = createToolDefinitionFromAITool(tool);
            if (def == null) continue;
            try {
                tools.put(def.toOpenAIFormat());
            } catch (JSONException e) {
                Log.w(TAG, "Failed to build OpenAI tool definition for dynamic tool "
                        + tool.getName() + ": " + e.getMessage());
            }
        }
        Log.i(TAG, "Returning " + tools.length() + " OpenAI tool definitions");
        return tools.toString();
    }
    
    /**
     * 检查工具是否存在
     * @param toolName 工具名称
     * @return 是否存在
     */
    public boolean hasTool(String toolName) {
        // 用户动态工具优先（别名不劫持同名动态工具）
        if (dynamicTools.containsKey(toolName)) return true;
        String resolved = resolveToolNameFuzzy(toolName);
        if (resolved == null) return false;
        toolName = resolveToolAlias(resolved);
        return toolFactories.containsKey(toolName) || dynamicTools.containsKey(toolName);
    }

    /**
     * 工具别名解析：历史/意图层遗留的工具名映射到真实工具。
     * system_ui_control / ui_control → ui_component（系统UI组件控制由 ui_component 承担）。
     */
    private static String resolveToolAlias(String name) {
        if (name == null) return null;
        if ("system_ui_control".equals(name) || "ui_control".equals(name)) {
            return "ui_component";
        }
        return name;
    }

    /**
     * 模糊工具名解析：本地 4B 模型经常把工具名猜错（缩写/中文/漏前缀/大小写/连字符），
     * 例如 "weather"→ai_weather、"text-tools"→text_tools、"画图"→image_gen、"python 画图"→python_chart。
     * 匹配优先级：精确(含别名) > 规范化相等 > 唯一包含 > 唯一描述关键词。
     * @return 真实注册名；无唯一匹配返回 null（调用方提示候选）
     */
    public String resolveToolNameFuzzy(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        // 1) 精确 + 别名（动态工具优先，别名为 ui_component 之类）
        String alias = resolveToolAlias(trimmed);
        if (toolFactories.containsKey(alias) || dynamicTools.containsKey(alias)) {
            return alias;
        }

        // 2) 规范化相等：小写 + 去下划线/连字符/空格，整体相等
        String norm = normalizeToolName(trimmed);
        if (!norm.isEmpty()) {
            for (String name : toolFactories.keySet()) {
                if (normalizeToolName(name).equals(norm)) return name;
            }
            for (String name : dynamicTools.keySet()) {
                if (normalizeToolName(name).equals(norm)) return name;
            }
        }

        // 3) 唯一包含匹配：输入是某工具名的子串，或反之（规范化后）
        if (norm.length() >= 3) {
            List<String> candidates = new ArrayList<>();
            java.util.Set<String> all = new java.util.LinkedHashSet<>();
            all.addAll(toolFactories.keySet());
            all.addAll(dynamicTools.keySet());
            for (String name : all) {
                String n = normalizeToolName(name);
                if (n.isEmpty()) continue;
                if (n.contains(norm) || norm.contains(n)) {
                    candidates.add(name);
                }
            }
            if (candidates.size() == 1) return candidates.get(0);
            // 多个候选：优先最短名（最可能是"本体"而非描述词）
            if (candidates.size() > 1) {
                candidates.sort((a, b) -> a.length() - b.length());
                // 长度差显著（最短明显更短）才判定唯一，避免 ai_weather/network_search 同时命中"天气"这类
                if (candidates.get(0).length() < candidates.get(1).length() - 2) {
                    return candidates.get(0);
                }
            }
        }

        // 4) 描述关键词匹配（唯一）：模型用中文功能词猜测（"画图"/"换算"/"朗读"）
        if (trimmed.length() >= 2) {
            List<String> descMatches = new ArrayList<>();
            java.util.Set<String> all = new java.util.LinkedHashSet<>();
            all.addAll(toolFactories.keySet());
            all.addAll(dynamicTools.keySet());
            for (String name : all) {
                ToolDefinition def = getToolDefinition(name);
                if (def == null || def.getDescription() == null) continue;
                String d = def.getDescription();
                if (d.contains(trimmed) || normalizeToolName(d).contains(norm)) {
                    descMatches.add(name);
                }
            }
            if (descMatches.size() == 1) return descMatches.get(0);
        }
        return null;
    }

    /** 工具名规范化：小写 + 去下划线/连字符/空格/点 */
    private static String normalizeToolName(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replace("_", "").replace("-", "").replace(" ", "")
                .replace(".", "").replace("/", "");
    }

    /**
     * 按关键词搜索工具名（供 tool_registry 未命中时提示候选/修正）。
     * 匹配：名称规范化包含 / 描述包含。
     * @return 匹配的工具名列表（最多 limit 个）
     */
    public java.util.List<String> searchToolNamesByKeyword(String keyword, int limit) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return result;
        String kw = keyword.trim().toLowerCase();
        String normKw = normalizeToolName(keyword);
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        all.addAll(toolFactories.keySet());
        all.addAll(dynamicTools.keySet());
        for (String name : all) {
            if (result.size() >= limit) break;
            String norm = normalizeToolName(name);
            if (norm.contains(normKw) || normKw.contains(norm)) {
                result.add(name);
                continue;
            }
            ToolDefinition def = getToolDefinition(name);
            if (def != null && def.getDescription() != null
                    && (def.getDescription().toLowerCase().contains(kw)
                        || normalizeToolName(def.getDescription()).contains(normKw))) {
                result.add(name);
            }
        }
        return result;
    }
    
    /** 2026-09-23：动态工具变更监听（OnlineToolRegistry 同步用，保证新注册工具即时进入索引） */
    private static volatile Runnable dynamicToolChangeListener;

    /** 注册动态工具变更监听（幂等，单监听） */
    public static void setDynamicToolChangeListener(Runnable listener) {
        dynamicToolChangeListener = listener;
    }

    private void notifyDynamicToolChange() {
        Runnable r = dynamicToolChangeListener;
        if (r != null) {
            try {
                r.run();
            } catch (Exception e) {
                Log.w(TAG, "Dynamic tool change listener failed: " + e.getMessage());
            }
        }
    }

    /**
     * 注册动态工具
     * @param tool 动态工具
     */
    public void registerDynamicTool(AITool tool) {
        if (tool != null && tool.getName() != null) {
            dynamicTools.put(tool.getName(), tool);
            persistDynamicTools();
            Log.i(TAG, "Dynamic tool registered: " + tool.getName());
            notifyDynamicToolChange();
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
     * 创建并注册动态工具（结构化参数，create_dynamic_tool 修复后的主路径）
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 结构化参数定义（标准 function schema），可为空
     * @param executionLogic 执行逻辑
     */
    public DynamicAITool createAndRegisterDynamicTool(String name, String description,
                                                  DynamicToolParams parameters,
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
        Runnable r = dynamicToolChangeListener;
        if (r != null) { try { r.run(); } catch (Exception ignored) {} }
        if (dynamicTools.remove(name) != null) {
            persistDynamicTools();
            Log.i(TAG, "Dynamic tool unregistered: " + name);
        }
    }

    // ==================== 动态工具持久化（跨重启保留模型创建的工具） ====================

    /** 动态工具持久化文件 */
    private java.io.File getDynamicToolsFile() {
        return new java.io.File(context.getFilesDir(), "dynamic_tools.json");
    }

    /** 保存所有动态工具到磁盘（支持 DynamicAITool 与 PythonDynamicTool 两种类型） */
    private synchronized void persistDynamicTools() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (AITool tool : dynamicTools.values()) {
                org.json.JSONObject obj = new org.json.JSONObject();
                if (tool instanceof DynamicAITool) {
                    DynamicAITool dyn = (DynamicAITool) tool;
                    obj.put("type", "java");
                    obj.put("name", dyn.getName());
                    obj.put("description", dyn.getDescription());
                    obj.put("logic", dyn.getExecutionLogic() != null ? dyn.getExecutionLogic() : "");
                } else if (tool instanceof com.oilquiz.app.ai.python.PythonDynamicTool) {
                    com.oilquiz.app.ai.python.PythonDynamicTool py = 
                            (com.oilquiz.app.ai.python.PythonDynamicTool) tool;
                    obj.put("type", "python");
                    obj.put("name", py.getName());
                    obj.put("description", py.getDescription());
                    obj.put("code", py.getCode() != null ? py.getCode() : "");
                } else {
                    continue; // 其他类型不持久化
                }
                org.json.JSONObject params = new org.json.JSONObject();
                Map<String, String> paramMap = tool.getParameterDescriptions();
                if (paramMap != null) {
                    for (Map.Entry<String, String> e : paramMap.entrySet()) {
                        params.put(e.getKey(), e.getValue() != null ? e.getValue() : "");
                    }
                }
                obj.put("parameters", params);
                // 结构化参数 schema（修复后主路径）：保存完整定义，重启后恢复真实类型
                // 2026-09-23：python 动态工具同样持久化 paramSchema（PythonDynamicTool 也支持结构化）
                DynamicToolParams dynParams = null;
                if (tool instanceof DynamicAITool) {
                    dynParams = ((DynamicAITool) tool).getDynamicParams();
                } else if (tool instanceof com.oilquiz.app.ai.python.PythonDynamicTool) {
                    dynParams = ((com.oilquiz.app.ai.python.PythonDynamicTool) tool).getDynamicParams();
                }
                if (dynParams != null && !dynParams.isEmpty()) {
                    String schemaJson = dynParams.toJson();
                    if (schemaJson != null) {
                        obj.put("paramSchema", schemaJson);
                    }
                }
                arr.put(obj);
            }
            java.io.FileWriter writer = new java.io.FileWriter(getDynamicToolsFile());
            writer.write(arr.toString(2));
            writer.close();
            Log.i(TAG, "Dynamic tools persisted: " + dynamicTools.size());
        } catch (Exception e) {
            Log.e(TAG, "Persist dynamic tools failed: " + e.getMessage(), e);
        }
    }

    /** 启动时从磁盘恢复动态工具（按 type 重建对应实例，兼容旧文件无 type 字段 → 视为 Java 工具） */
    private synchronized void loadDynamicTools() {
        try {
            java.io.File file = getDynamicToolsFile();
            if (!file.exists()) return;
            java.io.FileReader reader = new java.io.FileReader(file);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
            reader.close();
            if (sb.length() == 0) return;

            org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
            int restored = 0;
            for (int i = 0; i < arr.length(); i++) {
                try {
                    org.json.JSONObject obj = arr.getJSONObject(i);
                    String name = obj.optString("name", "");
                    if (name.isEmpty() || dynamicTools.containsKey(name)) continue;
                    // 2026-09-25：同名内置工具优先——工具固化为内置后，跳过存量同名动态条目，
                    // 避免旧动态版与新内置版同名共存造成行为分叉（如 douyin_downloader）
                    if (toolFactories.containsKey(name)) continue;
                    String description = obj.optString("description", "用户自定义工具");
                    String type = obj.optString("type", "java");
                    Map<String, String> params = new java.util.LinkedHashMap<>();
                    org.json.JSONObject paramObj = obj.optJSONObject("parameters");
                    if (paramObj != null) {
                        java.util.Iterator<String> keys = paramObj.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            params.put(k, paramObj.optString(k, ""));
                        }
                    }
                    // 2026-09-23 存量数据自愈：旧版退化 schema 把类型信息拼进描述
                    // （"；类型:string,可选,默认:宁夏"），并把 paramSchema 数组当假参数 "definitions" 持久化——
                    // 恢复时清理后重新注册，参数描述恢复干净（真实类型在 paramSchema）
                    params = sanitizeLegacyParams(params);
                    if ("python".equals(type)) {
                        String code = obj.optString("code", "");
                        // 2026-09-23：python 动态工具恢复也支持 paramSchema（结构化参数），
                        // 与 Java 动态工具对称——保证 tool_registry(get) 解析出真实类型/必填/枚举
                        com.oilquiz.app.ai.tool.DynamicToolParams pyDynParams = null;
                        if (obj.has("paramSchema")) {
                            try {
                                pyDynParams = com.oilquiz.app.ai.tool.DynamicToolParams
                                        .fromJson(obj.optString("paramSchema", ""));
                            } catch (Exception ignored) {}
                        }
                        com.oilquiz.app.ai.python.PythonDynamicTool pyTool =
                                pyDynParams != null && !pyDynParams.isEmpty()
                                ? new com.oilquiz.app.ai.python.PythonDynamicTool(
                                        context, name, description, params, pyDynParams, code)
                                : new com.oilquiz.app.ai.python.PythonDynamicTool(
                                        context, name, description, params, code);
                        dynamicTools.put(name, pyTool);
                        restored++;
                    } else {
                        String logic = obj.optString("logic", "");
                        // 优先恢复结构化参数 schema；无则回退 name→desc
                        DynamicToolParams dynParams = null;
                        if (obj.has("paramSchema")) {
                            dynParams = DynamicToolParams.fromJson(obj.optString("paramSchema", ""));
                        }
                        DynamicAITool tool = dynParams != null && !dynParams.isEmpty()
                                ? new DynamicAITool(context, name, description, dynParams, logic)
                                : new DynamicAITool(context, name, description, params, logic);
                        dynamicTools.put(name, tool);
                        restored++;
                    }
                } catch (Exception ignored) {
                }
            }
            if (restored > 0) {
                Log.i(TAG, "Dynamic tools restored: " + restored);
                // 2026-09-23：恢复即重持久化——把自愈后的干净参数（无类型噪音/无假 definitions）
                // 写回磁盘，存量 dynamic_tools.json 一次性完成"重新修改后再注册"的落盘闭环
                try {
                    persistDynamicTools();
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Load dynamic tools failed: " + e.getMessage());
        }
    }
    
    /**
     * 2026-09-23 存量动态工具参数自愈（重新修改后再注册）：
     * 1) 剥掉描述尾部 "；类型:xxx,可选/必填[,默认:xxx][,枚举:xxx]" 噪音后缀（旧版退化 schema 拼入）；
     * 2) 删除名为 "definitions" 的假参数（旧版把 paramSchema.toJson() 数组当参数持久化，
     *    真实参数定义在 paramSchema 中）。
     * 清理后与 paramSchema（结构化）一起重建注册，参数描述恢复干净、schema 完整。
     */
    private Map<String, String> sanitizeLegacyParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) return params;
        Map<String, String> clean = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if ("definitions".equals(e.getKey())) continue;
            String v = e.getValue();
            if (v != null) {
                int idx = v.lastIndexOf("；类型:");
                if (idx >= 0) {
                    v = v.substring(0, idx).trim();
                }
            }
            clean.put(e.getKey(), v != null ? v : "");
        }
        return clean;
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
     * 获取用户创建的动态工具名称列表（已实例化，查询无额外开销）
     */
    public List<String> getDynamicToolNames() {
        return new ArrayList<>(dynamicTools.keySet());
    }

    /**
     * 解析工具定义：优先静态定义，动态工具回退到从 AITool 实例反射生成。
     * 修复本地 Agent 无法调用动态工具的问题（getToolDefinition 对动态工具返回 null）。
     */
    public ToolDefinition resolveToolDefinition(String toolName) {
        // 模糊解析：模型可能拿猜测名（weather 等）请求 schema，先归一化
        String resolved = resolveToolNameFuzzy(toolName);
        if (resolved != null) toolName = resolved;
        // 用户动态工具优先（避免与内置 switch 定义/别名冲突，如用户自建 system_ui_control）
        AITool dyn = dynamicTools.get(toolName);
        if (dyn != null) {
            return createToolDefinitionFromAITool(dyn);
        }
        ToolDefinition def = getToolDefinition(toolName);
        if (def != null) return def;
        return null;
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
    /**
     * 获取工具的输出规范声明（dsh output schema 对齐，2026-09-23）。
     * 工具未声明（getOutputSchema 返回 null）时返回 null；供发现/文档/校验使用。
     */
    public Map<String, Object> getOutputSchema(String toolName) {
        AITool tool = getTool(toolName);
        if (tool == null) return null;
        try {
            return tool.getOutputSchema();
        } catch (Throwable th) {
            Log.w("AIToolManager", "getOutputSchema failed for " + toolName + ": " + th.getMessage());
            return null;
        }
    }

    public ToolDefinition getToolDefinition(String toolName) {
        switch (toolName) {
            case "ai_weather":
                return ToolDefinition.builder("ai_weather", "天气查询工具：获取城市实时天气/预报/空气质量等。"
                        + "action: current(实时天气)/forecast(未来几天预报)/hourly(逐小时)/air_quality(空气质量)/"
                        + "alerts(预警)/indices(生活指数)/all(全部)。"
                        + "city=城市名 或 和风城市编码 或 lat+lon=经纬度(经纬度别名 latitude/longitude 也可用)。"
                        + "current 返回温度/体感/天气现象/风向风力/湿度/能见度/紫外线；"
                        + "forecast 返回逐日 {日期,白天/夜间天气,最高/最低温}；air_quality 返回 AQI/PM2.5/PM10/污染等级。"
                        + "别名: get_weather/weather。")
                    .addParameter("action", "string", "操作类型: current(实时天气)/forecast(预报)/hourly(逐小时)/air_quality(空气质量)/alerts(预警)/indices(生活指数)/all(全部)，按用户需求选择", false)
                    .addParameter("city", "string", "城市名称 或 和风城市编码（与经纬度二选一）", false)
                    .addParameter("lat", "number", "纬度（与city二选一，配合lon；别名latitude）", false)
                    .addParameter("lon", "number", "经度（与city二选一，配合lat；别名longitude）", false)
                    .category("weather")
                    .whenToUse("用户问天气/温度/空气质量/天气预警/生活指数时使用；已知城市直接传city，不知道城市先调location定位")
                    .build();
            case "network_search":
                return ToolDefinition.builder("network_search", "网络搜索工具（秘塔搜索引擎驱动）：联网搜索+智能问答+网页读取。"
                        + "action: search(关键词搜索,返回标题/链接/摘要)/ask(智能问答,返回答案+引用来源)/"
                        + "read_url(读取网页正文)/get_webpage(网页原始内容)/extract_info(提取信息)/"
                        + "summarize(网页摘要)/search_and_read(搜索并读正文)/get_dynamic_content(获取JS渲染动态网页内容)/"
                        + "smart_search(智能搜索,自动读详情)/smart_read(对已有搜索结果逐条读正文生成摘要,需传results)。"
                        + "涉及实时/最新/动态信息（新闻、价格、天气、汇率、政策、热点）用联网搜索获取，训练数据不采纳。"
                        + "别名: search。")
                    .addParameter("action", "string", "操作类型: search(搜索)/ask(智能问答)/read_url(网页读取)/get_webpage/extract_info/summarize/search_and_read/get_dynamic_content/smart_search/smart_read", false, "search")
                    .addParameter("query", "string", "搜索关键词（用于search/search_and_read/smart_search等操作）", false)
                    .addParameter("question", "string", "问题（用于ask操作，秘塔智能问答，返回答案+引用来源）", false)
                    .addParameter("model", "string", "问答模型: concise(简洁)/detail(深入)/research(研究)（用于ask操作）", false, "concise")
                    .addParameter("keyword", "string", "搜索关键词（query的别名）", false)
                    .addParameter("limit", "integer", "结果数量限制，默认5", false, 5)
                    .addParameter("num_results", "integer", "返回结果数量（limit的别名）", false, 5)
                    .addParameter("url", "string", "网页URL（用于read_url/get_webpage/get_dynamic_content操作）", false)
                    .addParameter("maxResults", "integer", "最大结果数（smart_search用）", false, 5)
                    .addParameter("autoRead", "boolean", "是否自动读取详情（smart_search用）", false, true)
                    .addParameter("results", "array", "搜索结果数组（smart_read用，传上一步search返回的results）", false)
                    .category("search")
                    .whenToUse("需要实时/最新/外部信息（新闻、价格、政策、热点、网页内容、链接正文）时使用；常识与知识类问题直接用自身知识回答，不要搜索")
                    .build();
            case "python_calculate":
                return ToolDefinition.builder("python_calculate", "使用Python进行数学计算，支持复杂/多步/科学计算表达式")
                    .addParameter("expression", "string", "数学表达式，如：2+3*4", true)
                    .addParameter("task", "string", "任务描述（可选）", false)
                    .category("calculator")
                    .build();
            case "file_reader":
                return ToolDefinition.builder("file_reader", "文件阅读工具：读取全文/按行/区间提取/搜索/实体提取/预览/解析结构化文件(Excel/CSV/JSON/XML)/列目录(list)。自动检测编码(UTF-8/UTF-16/GB18030/GBK)，支持content:// URI(file_uri)。大文件用 read_lines/preview/search_text 分片读取。Excel整表解析用本工具parse_excel；需要按条件查询/修改Excel请用 excel_tool")
                    .whenToUse("需要读取/查看/搜索本地或工作区文件内容、解析Excel/CSV/JSON/XML、列目录时使用；先确认文件路径再调用")
                    .addParameter("file_path", "string", "文件路径(支持content://开头URI，与file_uri二选一)", false)
                    .addParameter("file_uri", "string", "content:// URI(文件选择器/分享的Uri，与file_path二选一)", false)
                    .addParameter("action", "string", "操作类型: read/read_lines/extract_text/search_text/extract_entities/preview/parse_structured/parse_excel/parse_csv/parse_json/parse_xml/list", false, "read")
                    .addParameter("directory_path", "string", "目录路径(list用，留空默认应用目录)", false)
                    .addParameter("encoding", "string", "文件编码(留空自动检测UTF-8/UTF-16/GB18030/GBK；也可指定如GBK)", false)
                    .addParameter("startLine", "integer", "起始行号(read_lines用)", false)
                    .addParameter("endLine", "integer", "结束行号(read_lines用)", false)
                    .addParameter("startMarker", "string", "起始标记(extract_text用)", false)
                    .addParameter("endMarker", "string", "结束标记(extract_text用)", false)
                    .addParameter("keyword", "string", "搜索关键词(search_text用，等价参数pattern)", false)
                    .addParameter("regex", "boolean", "是否正则表达式(search_text用，默认false)", false, false)
                    .addParameter("entity_pattern", "string", "自定义正则表达式(extract_entities用，提取任意模式)", false)
                    .addParameter("maxLength", "integer", "最大预览长度(preview用，默认1000)", false, 1000)
                    .addParameter("delimiter", "string", "CSV分隔符(parse_csv用，默认逗号，支持tab)", false, ",")
                    .addParameter("max_rows", "integer", "最大解析行数(parse_excel/parse_csv用，默认500)", false, 500)
                    .addParameter("sheet_index", "integer", "工作表索引(parse_excel用，默认0)", false, 0)
                    .addParameter("json_path", "string", "JSON子节点路径(parse_json用，如 data.items)", false)
                    .addParameter("target_tag", "string", "目标标签(parse_xml用，只提取该标签)", false)
                    .addParameter("max_items", "integer", "最大条目数(parse_xml用，默认200)", false, 200)
                    .category("file")
                    .build();
            case "file_analyzer":
                return ToolDefinition.builder("file_analyzer", "文件分析工具：综合分析/统计/关键词/词频/格式检测/目录分析/查找重复文件(大小+内容哈希)")
                    .addParameter("action", "string", "操作类型: analyze/statistics/keywords/word_count/detect_format/analyze_directory/find_duplicates", false, "analyze")
                    .addParameter("file_path", "string", "文件路径(analyze/statistics/keywords/word_count/detect_format用)", false)
                    .addParameter("directory_path", "string", "目录路径(analyze_directory/find_duplicates用)", false)
                    .addParameter("topN", "integer", "关键词数量(keywords用，默认10)", false, 10)
                    .category("file")
                    .build();
            case "file_generator":
                return ToolDefinition.builder("file_generator", "文件生成工具，生成文本/JSON/配置/Markdown等文件。未指定绝对路径时默认保存到 Agent 工作区（用 workspace 工具查看/读取，返回的 filePath 是完整路径）。**文件名规范：一律用英文/数字/下划线（如 daily_report_0911.md），不要用中文文件名**（中文名在跨进程/链接打开环节易被截断丢失）；给用户文件链接时用返回的完整绝对路径或 files/相对路径，不要缩写/丢字（用户实测点开失败多因此）。")
                    .addParameter("action", "string", "操作类型: create/append/json/config/markdown/template/report/copy/delete", false, "create")
                    .addParameter("file_name", "string", "文件名/路径（用英文名，勿中文）", true)
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
                return ToolDefinition.builder("database", "数据库操作工具。支持任意SQL(execute_sql)、查看表结构(list_tables/get_table_schema)、题目查询与管理、用户管理、分数记录等。大批量导入用bulk_import(接受questions数组或file_path JSON文件路径，一次可导入数百道，自动跳过无效条目)，add_questions也可一次传多道题目(数量不限)")
                    .addParameter("action", "string", "操作类型: execute_sql/list_tables/get_table_schema/execute_query/get_questions/search_questions/get_question_count/get_question_statistics/get_all_categories/get_all_question_types/get_question_by_id/add_questions/bulk_import/update_question/delete_question/clear_all_questions/get_user/add_user/get_score_history/add_score/get_average_score/get_database_version", true)
                    .addParameter("sql", "string", "SQL语句(execute_sql用，SELECT/INSERT/UPDATE/DELETE，支持多语句分号分隔)", false)
                    .addParameter("table_name", "string", "表名(get_table_schema用)", false)
                    .addParameter("query", "string", "SQL查询语句(execute_query用，兼容旧接口)", false)
                    .addParameter("keyword", "string", "搜索关键词(search_questions用)", false)
                    .addParameter("id", "string", "题目/用户ID(get_question_by_id/update_question/delete_question用)", false)
                    .addParameter("category", "string", "题目分类", false)
                    .addParameter("type", "string", "题目类型", false)
                    .addParameter("difficulty", "integer", "难度: 1-简单, 2-中等, 3-困难", false)
                    .addParameter("page", "integer", "页码(get_questions用)", false)
                    .addParameter("page_size", "integer", "每页数量(get_questions用)", false)
                    .addParameter("questions", "array", "题目列表(add_questions/bulk_import用): [{questionText,optionA,optionB,optionC,optionD,correctAnswer,explanation,category,questionType,difficulty}]", false)
                    .addParameter("file_path", "string", "JSON文件路径(bulk_import用，文件内容为题目数组或{questions:[...]})", false)
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
                    .addParameter("action", "string", "操作类型: research/quick_search/deep_read/summarize_topic", false, "research")
                    .addParameter("topic", "string", "研究主题(research/quick_search/summarize_topic用，等价于query)", false)
                    .addParameter("query", "string", "查询词(topic的别名，二者传其一即可)", false)
                    .addParameter("depth", "integer", "研究深度(保留参数)", false, 1)
                    .addParameter("maxResults", "integer", "最大结果数(默认5)", false, 5)
                    .addParameter("includeDetails", "boolean", "是否获取详情内容(默认true)", false, true)
                    .addParameter("urls", "array", "URL列表(deep_read用)", false)
                    .category("research")
                    .build();
            case "location":
                return ToolDefinition.builder("location", "位置查询工具：获取当前位置信息（经纬度/城市/详细地址）。"
                        + "action: get_current(当前经纬度+城市+地址)/get_city(当前城市名)/get_coordinates(经纬度坐标)。"
                        + "返回含 latitude/longitude/city/district/address 等字段；"
                        + "位置服务未开启或权限未授予时返回错误提示并引导跳转系统设置。"
                        + "别名: get_location/get_current_location/get_city/get_coordinates。")
                    .addParameter("action", "string", "操作类型: get_current/get_city/get_coordinates", false, "get_current")
                    .category("location")
                    .build();
            case "webpage_reader":
                return ToolDefinition.builder("webpage_reader", "网页阅读工具(Jsoup解析)：抓取网页返回标题/描述/标题结构/正文(text字段)/链接/摘要/关键词/分类。read=读取解析；extract=提取关键信息(可传已有content)；summarize=生成摘要；read_multiple=并行批量读取(urls数组)；follow_links=跟踪链接。单页上限5MB")
                    .addParameter("action", "string", "操作类型: read/extract/summarize/read_multiple/follow_links", false, "read")
                    .addParameter("url", "string", "网页URL(read/extract/summarize/follow_links用，与content二选一)", false)
                    .addParameter("urls", "array", "URL列表(read_multiple用，并行抓取)", false)
                    .addParameter("content", "string", "网页内容(extract/summarize用，与url二选一)", false)
                    .addParameter("query", "string", "搜索查询词(相关性计算用)", false)
                    .addParameter("maxDepth", "integer", "最大链接深度(follow_links用，默认2)", false, 2)
                    .addParameter("maxLinks", "integer", "最大链接数量(follow_links用，默认10)", false, 10)
                    .category("web")
                    .build();
            case "system_resource":
                return ToolDefinition.builder("system_resource", "系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话、发送邮件、打开地图、控制应用、执行Shell命令、读写系统设置等。支持模糊匹配应用名，找不到时自动回退系统选择器。shell_command有安全管控：危险命令(rm/reboot/su/dd/chmod/kill/wget等)与敏感路径(/data/data、/proc、/sys、凭据文件)会被拦截，单条命令10秒超时")
                    .addParameter("action", "string", "操作类型: open_app/open_url/send_sms/make_call/send_email/open_map/list_apps/check_app/get_app_info/app_control/shell_command/read_setting/write_setting/get_current_app/open_settings/share_text", false, "open_app")
                    .addParameter("app", "string", "应用名称或包名，支持模糊匹配", false)
                    .addParameter("url", "string", "URL地址", false)
                    .addParameter("phone", "string", "电话号码", false)
                    .addParameter("message", "string", "短信内容", false)
                    .addParameter("to", "string", "收件人邮箱(send_email用，必填)", false)
                    .addParameter("subject", "string", "邮件主题(send_email用)", false)
                    .addParameter("body", "string", "邮件正文(send_email用)", false)
                    .addParameter("location", "string", "地图查询地点/坐标(open_map用，如\"天安门\"或\"39.9,116.4\")", false)
                    .addParameter("address", "string", "地图地址(open_map用，location的别名)", false)
                    .addParameter("command", "string", "Shell命令（如: pm list packages, dumpsys activity top, input tap 500 500。危险命令/敏感路径被拦截，10秒超时）", false)
                    .addParameter("setting_type", "string", "设置类型: system/secure/global", false)
                    .addParameter("setting_key", "string", "设置键名", false)
                    .addParameter("setting_value", "string", "设置值", false)
                    .addParameter("control_action", "string", "应用控制: force_stop/clear_data/detailed_info", false)
                    .addParameter("setting", "string", "设置页: wifi/bluetooth/location/display/sound/storage/app/battery", false)
                    .category("system")
                    .build();
            case "python_execute":
                return ToolDefinition.builder("python_execute", "执行Python代码。脚本内置android_ui模块(真实显示在手机界面)：系统原生组件 dialog/progress/input/choice(create_component→component_id→update/close/get_result 阻塞取结果)；内置UI组件库(create_component('类型', props={...}) 渲染成聊天流卡片，props带actions可交互；类型列表见 ui_component 工具 component_type 参数；web=网页卡片、image=图片卡片)；便捷函数 ask_input/ask_choice/show_progress；脚本最后print输出作为结果返回。环境预装库(可直接import，无需安装)：requests、beautifulsoup4(bs4)、jieba、lxml、regex、numpy(np)、pandas(pd)、matplotlib(plt)、Pillow(PIL)、openpyxl、yaml、tabulate、python-dateutil、chardet、xlrd、reportlab；绘制图表用matplotlib(先设中文字体)或Pillow")
                    .addParameter("code", "string", "Python代码（可选，上限200KB）", false)
                    .addParameter("task", "string", "任务描述（可选）", false)
                    .addParameter("context", "string", "上下文数据（可选）", false)
                    .addParameter("timeout", "integer", "执行超时秒数(默认30，范围5~120；超时返回TimeoutError)", false, 30)
                    .category("python")
                    .build();
            case "python_analyze_data":
                return ToolDefinition.builder("python_analyze_data", "使用Python分析数据(统计/清洗/转换/图表计算等)。与python_execute的区别：本工具专注数据分析场景，适合处理用户提供的数据或表格内容；python_execute可执行任意Python代码(含文件/网络/UI组件等)。数据量大时用本工具，复杂任务用python_execute")
                    .addParameter("data", "string", "数据（可选，要分析的数据内容）", false)
                    .addParameter("task", "string", "任务描述（可选，如统计/求平均/去重/排序/转换格式等）", false)
                    .category("python")
                    .build();
            case "python_web_reader":
                return ToolDefinition.builder("python_web_reader", "Python网页工具(requests+bs4)：抓取网页/API并提取信息。fetch=GET/POST抓取(可带headers/params/JSON body)；extract=bs4提取标题/正文/链接/表格/JSON-LD；fetch_json=请求JSON API。适合登录态/Cookie/自定义请求头/API等Java网页工具不便处理的场景")
                    .addParameter("action", "string", "操作: fetch/extract/fetch_json", false, "fetch")
                    .addParameter("url", "string", "目标URL", true)
                    .addParameter("method", "string", "HTTP方法(GET/POST，默认GET)", false, "GET")
                    .addParameter("headers", "object", "自定义请求头JSON，如{\"Cookie\":\"...\",\"User-Agent\":\"...\"}", false)
                    .addParameter("params", "object", "URL查询参数JSON", false)
                    .addParameter("data", "object", "POST的JSON body", false)
                    .addParameter("content", "string", "已有HTML内容(extract用，与url二选一)", false)
                    .addParameter("timeout", "integer", "超时秒数(默认15)", false, 15)
                    .addParameter("max_chars", "integer", "内容最大输出字符数(默认8000)", false, 8000)
                    .category("python")
                    .build();
            case "python_file_ops":
                return ToolDefinition.builder("python_file_ops", "Python文件工具(标准库+openpyxl)：阅读与修改。read=读文本(UTF-8/GB18030/UTF-16自动检测)；parse=严格解析CSV(标准库RFC4180)/JSON/XML/Excel(xlsx读写)；write=写文件；append=追加；replace=文本替换。适合严格CSV解析/xlsx写入等场景")
                    .addParameter("action", "string", "操作: read/parse/write/append/replace", false, "read")
                    .addParameter("file_path", "string", "文件路径", true)
                    .addParameter("content", "string", "内容(write/append用)", false)
                    .addParameter("old_text", "string", "被替换文本(replace用)", false)
                    .addParameter("new_text", "string", "替换为(replace用，可为空=删除)", false)
                    .addParameter("encoding", "string", "编码(write/append用，默认utf-8)", false, "utf-8")
                    .addParameter("format", "string", "解析格式(parse用: csv/json/xml/xlsx，留空按扩展名)", false)
                    .addParameter("max_rows", "integer", "最大行数(parse用，默认500)", false, 500)
                    .addParameter("max_chars", "integer", "最大输出字符数(默认8000)", false, 8000)
                    .addParameter("sheet_index", "integer", "工作表索引(parse xlsx用，默认0)", false, 0)
                    .category("python")
                    .build();
            case "python_chart":
                return ToolDefinition.builder("python_chart", "Python绘图工具(Pillow/matplotlib)：数据可视化生成PNG图片。bar=柱状图(支持多系列)/line=折线图(支持多系列)/pie=饼图/scatter=散点图。data传JSON：bar/line用{\"labels\":[\"A\",\"B\"],\"values\":[1,2]}或多系列{\"labels\":[...],\"series\":[{\"name\":\"系列1\",\"values\":[...]}]}；pie用{\"labels\":[...],\"values\":[...]}；scatter用{\"points\":[[x,y],...]}。图片默认保存到工作区files/(用workspace查看)，可指定output_path。与image_gen(AI生图)不同，本工具画数据图表。环境已装matplotlib/Pillow/numpy/pandas；需要子图/对数轴/热力图等复杂图表时，改用python_execute直接编写matplotlib代码")
                    .addParameter("action", "string", "图表类型: bar/line/pie/scatter", true)
                    .addParameter("data", "object", "数据JSON(必填，格式见描述)", true)
                    .addParameter("title", "string", "图表标题(可选)", false)
                    .addParameter("width", "integer", "图片宽度(默认800)", false, 800)
                    .addParameter("height", "integer", "图片高度(默认500)", false, 500)
                    .addParameter("output_path", "string", "保存路径(默认工作区files/)", false)
                    .addParameter("colors", "array", "系列颜色数组(可选，默认内置色板)", false)
                    .addParameter("show_values", "boolean", "是否显示数值(默认true)", false, true)
                    .category("python")
                    .build();
            case "app_operation":
                return ToolDefinition.builder("app_operation", "应用内部页面跳转工具，支持跳转到用户、题库、答题、学习计划、错题本等各种页面；navigate 用 params 动态注入页面参数（如打开 media_gen AI生图页并预填描述）")
                    .addParameter("action", "string", "操作类型: navigate/list_pages/go_home/go_back/get_info/open_settings/share", false, "navigate")
                    .addParameter("page", "string", "页面名称(如user/question/quiz/study_plan/note/ocr/ai等；AI生图/视频用media_gen)", false)
                    .addParameter("params", "object", "动态注入页面参数(navigate用)，如media_gen: {\"mode\":\"image\",\"prompt\":\"一只橘猫\",\"size\":\"1024*1024\"}，页面打开即预填", false)
                    .category("app")
                    .build();
            case "create_dynamic_tool":
                return ToolDefinition.builder("create_dynamic_tool", "动态创建和管理AI工具：把重复性任务封装成可复用工具。action=create时填tool_name+description+parameters+logic(Python脚本/JavaScript脚本或DSL)，创建后可被后续对话直接调用；update/delete修改或移除已有工具；list列出全部动态工具；show查看单个工具完整定义(参数schema+执行逻辑全文)；test用test_params试运行不落库")
                    .addParameter("action", "string", "操作类型: create/update/delete/list/show/test", false, "list")
                    .addParameter("tool_name", "string", "工具名称", false)
                    .addParameter("description", "string", "工具描述", false)
                    .addParameter("parameters", "string", "参数定义，支持三种格式：1.简单{\"参数名\":\"参数描述\"}；2.属性级{\"参数名\":{\"type\":\"string\",\"description\":\"...\",\"required\":true,\"default\":...,\"enum\":[...]}}；3.完整JSON Schema{\"type\":\"object\",\"properties\":{...},\"required\":[...]}。类型支持string/number/integer/boolean/array/object", false)
                    .addParameter("logic", "string", "执行逻辑脚本：支持Python脚本(自动识别，脚本内用script_args['参数名']读取工具参数，支持顶层return，print输出/返回值作为结果)或DSL命令(echo/set/if/call_tool等)", false)
                    .addParameter("test_params", "string", "试运行参数JSON(action=test时使用，格式{\"参数名\":值}，也可直接传参)", false)
                    .category("tool")
                    .build();
            case "dashscope_media":
                return ToolDefinition.builder("dashscope_media", "百炼DashScope文生图/文生视频（通义万相）：image=文生图(wan2.2-t2i-flash/plus)；video=文生视频(wan2.2-t2v-plus,异步提交返回task_id)；query=按task_id查询进度并下载结果；models=列出可用模型。视频尺寸仅限白名单:1080*1920/1920*1080/1440*1440/1632*1248/1248*1632/480*832/832*480/624*624(其他报错)。生成结果保存到工作区files/，返回文件卡片/图片卡片")
                    .addParameter("action", "string", "操作: image(文生图)/video(文生视频)/query(按task_id查询并下载)/models(列出可用模型)", false, "image")
                    .addParameter("prompt", "string", "画面/视频描述（必填）", false)
                    .addParameter("model", "string", "模型(image: wan2.2-t2i-flash/wan2.2-t2i-plus；video: wan2.2-t2v-plus)，按需选择", false)
                    .addParameter("size", "string", "尺寸(image: 1024*1024；video: 白名单 1080*1920/1920*1080/1440*1440/1632*1248/1248*1632/480*832/832*480/624*624)，按需选择", false)
                    .addParameter("duration", "integer", "视频时长秒数(video用，默认5)", false, 5)
                    .addParameter("task_id", "string", "任务ID(query用)", false)
                    .addParameter("api_key", "string", "百炼API Key(可选，默认取当前在线模型配置)", false)
                    .category("media")
                    .build();
            case "ui_component":
                return ToolDefinition.builder("ui_component", "创建UI组件：系统原生(dialog/progress/input/choice/multi_choice/date/time/snackbar/list/notification/custom动态表单/marquee跑马灯/media_task任务监控等)或内置卡片(chart/info_card/table_card等,见component_type参数)。有结构信息一律用组件卡片展示,不用Markdown表格。自定义原生类型：register_type 外部注入新类型名(render.layout 原生控件框架树)；临时layout：create时component_type给任意未注册名+layout参数(顶层layout/props.layout/render.layout三选一等效)不注册即用,仅本次有效。组件参数可放顶层或 props 内(等效,自动合并)。握手:create→component_id→update/close→get_result。layout 控件树与字段详见 layout/render 参数说明；已注册类型/插件/模板可用 ui_component(list_types) 与 ui_component_plugin(list) 查看后直接用 name 创建。注意：layout 的 alert 提示条样式字段用 alert_type 或 variant，勿用 type。")
                    .addParameter("action", "string", "操作: create(创建)/update(更新)/close(关闭)/get_result(获取结果)/register_type(外部注入自定义类型,persist可选)/list_types(列出注册类型)/remove_type(删除类型)/clear_temporary_types(清除临时类型)", true)
                    .addParameter("component_type", "string", "组件类型: dialog/progress/input/choice/multi_choice/date/time/image/snackbar/list/notification/custom(动态自定义原生表单,用fields参数定义任意字段,确定返回全部值JSON)/file_picker(系统文件选择器,返回content:// URI)/image_picker(相册选图,返回URI)/contact_picker(通讯录选联系人,返回{name,phone,uri})/rating(星级评分1-5)/color(取色器,返回#RRGGBB)/otp(验证码输入,length设位数,默认6)/number(数字输入,min/max范围校验)/marquee(跑马灯滚动文字:text=内容,speed=0~3,bold,size,color,repeat)/media_task(文生图/文生视频任务监控:task_id,type=image|video,api_url,api_key)/内置组件类型(chart/info_card/table_card/image_grid/link_card/list_card/alert_card/metric_card/json_viewer/steps_card/note_card/file_list/grid_card/contact_card/todo_card/quiz_card/weather_card/file_card/code_card/progress_card/html/markdown_card)/数据卡片(table(headers,rows)/steps(steps)/timeline(items)/alert(alert_type或variant:success|warning|error|info,title,content)/stat(label,value)/empty(icon,title)/notice(icon,text)/progress_ring(progress))/图表(line_chart(categories,series)/bar_chart/pie_chart(data)/sparkline(data))/工具(qrcode(content)/barcode(content)/countdown(seconds)/calendar(value)/breadcrumb(items))/其他(avatar_group(urls)/toggle(options)/stepper(min,max)/tag_input(tags)/badge/quote/icon)。web=网页卡片(传url或html), image=图片卡片(传default_value或images)。**html组件增强**：style 内容级生效(style.fontSize/color/background 直接作用于页面内容,如 style={background:\"#0F172A\",color:\"#E2E8F0\",fontSize:15})；内容可双指缩放；图片点击全屏预览。各组件参数可放顶层或 props 内(等效,自动合并)", false)
                    .addParameter("component_id", "string", "组件ID(update/close/get_result用)", false)
                    .addParameter("title", "string", "标题", false)
                    .addParameter("message", "string", "内容/提示文本", false)
                    .addParameter("dialog_type", "string", "对话框类型: info/confirm/warning", false)
                    .addParameter("max_value", "integer", "进度最大值(progress/notification用;也可传max或props.max)", false)
                    .addParameter("progress", "integer", "进度值(update用;也可放props内props.progress)", false)
                    .addParameter("options", "array", "选项列表(choice/multi_choice用)", false)
                    .addParameter("default_value", "string", "默认值(input/date/time/image用)", false)
                    .addParameter("input_hint", "string", "输入框提示(input用)", false)
                    .addParameter("action_label", "string", "按钮文字(snackbar用)", false)
                    .addParameter("fields", "array", "custom动态表单字段定义数组，如[{\"key\":\"name\",\"label\":\"姓名\",\"type\":\"text\",\"required\":true},{\"key\":\"age\",\"label\":\"年龄\",\"type\":\"number\"},{\"key\":\"sex\",\"label\":\"性别\",\"type\":\"select\",\"options\":[\"男\",\"女\"]},{\"key\":\"agree\",\"label\":\"同意\",\"type\":\"switch\",\"default\":true},{\"key\":\"score\",\"label\":\"评分\",\"type\":\"slider\",\"min\":0,\"max\":10},{\"key\":\"tags\",\"label\":\"标签\",\"type\":\"checkbox\",\"options\":[\"A\",\"B\"]},{\"key\":\"birth\",\"label\":\"生日\",\"type\":\"date\"}]；字段类型:text/password/number/multiline/select/radio/checkbox/switch/slider/date/time/datetime/otp/email/tel/url/search/file。**连续输入表单（配套能力，实测可用）**: props 内加 rounds=N(N>1) → 多轮连续输入,弹窗含「添加下一条」(收集本轮并清空重建)与「完成」(收集并结束),get_result 返回 {\"rounds\":[{第1轮}...],\"total\":N},适合批量录入多条数据(多条记录/题目/清单项)", false)
                    .addParameter("items", "array", "列表项(list用)", false)
                    .addParameter("url", "string", "网址或HTML内容(web用)", false)
                    .addParameter("props", "object", "内置组件参数(component_type为内置类型时用)。各类型字段：chart:{chartType:'bar|line|pie',title,categories:[分类],series:[{name,data:[数值]}]}; info_card:{title,items:[{label,value}]}; table_card:{title,headers:[列名],rows:[[值]]}; image_grid:{images:[url],columns}; link_card:{url,title,description}; list_card:{title,items:[{icon,title,description,value}]}; alert_card:{type:'success|warning|error|info',title,content}; metric_card:{title,metrics:[{label,value,color}]}; json_viewer:{title,data,maxHeight}; steps_card:{title,steps:[{status:'done|current|failed|todo',title,description}]}; note_card:{type:'note|quote|tip|summary',content,author}; file_list:{title,files:[{name,path,size,type}]}; grid_card:{title,columns,items:[{icon,label}]}; contact_card:{type:'phone|sms|email',title,value,description}; todo_card:{title,items:[{done:bool,text}]}; quiz_card:{type:'single|multiple|judge',question,options:[],answer,analysis}; weather_card:{city,temp,text,icon,humidity,windDir,windScale,forecast:[{date,text,tempMin,tempMax}]}; file_card:{name,size,type,path}; code_card:{language,code,title}; progress_card:{title,progress,description}; html:{html:'<h3>标题</h3>...' 或 url:'https://...'(网页直接加载),title,maxHeight(可选,内容区最大高度dp),style(内容级:{background,color,fontSize}整页应用)};html内容支持双指缩放/横向滚动/图片点击全屏预览,卡片内链接自身加载,右上角←浮标可返回; markdown_card:{content:'**加粗** 文本',title}; 任务需要用户提供信息/反馈(确认/选择/输入/点赞等)时加actions:[{label:'按钮文字',value:'回传值',action:'callback'}]或[{label,link:url}]/[{label,copy:文本}],创建后get_result取回用户点击值；临时layout时也可用props={layout:{控件树}}", false)
                    .addParameter("wait_seconds", "integer", "等待秒数(get_result用,默认30)", false)
                    .addParameter("persist", "string", "是否持久化组件实例(true/false,默认false)：跨重启/会话保留，list_components 可见；恢复后默认静默不弹窗(get_result 返回 inactive)，传 reactivate=true 才重建弹窗；close 取消持久化", false)
                    .addParameter("reactivate", "string", "是否重建跨会话恢复的持久化组件弹窗(true/false,默认false)：仅对恢复的历史组件生效，true 时 get_result/update 重建弹窗让用户操作", false)
                    .addParameter("auto_close", "integer", "自动关闭秒数(create时指定,到点自动关闭并置result=closed;如提示类组件auto_close=5五秒后消失)", false)
                    .addParameter("name", "string", "register_type/remove_type 用：自定义类型名（字母数字下划线）", false)
                    .addParameter("layout", "object", "现场自定义UI（create用，可选）：原生控件框架树JSON。component_type 可给任意未注册名(如 debug_layout_test)，无需 register_type，仅本次创建有效。layout 树内支持：已注册类型名作节点type嵌套({\"type\":\"online_music_player\"},自动展开其render.layout,节点props覆盖占位)；use 引用 layout 模板({\"use\":\"模板名\",\"props\":{参数}},模板内{key}由props替换)；未注册类型节点自带 layout 字段现场展开({\"type\":\"my_widget\",\"layout\":{...}})。控件type: 布局column/row/scroll/card/wrap(流式换行)/grid(网格,columns)/space(弹性空白)/tabs(标签页)/stack(层叠)/accordion(折叠面板)/carousel(图片轮播)；展示text/marquee(跑马灯,speed 0~3)/image/badge/avatar/avatar_group/quote/code/icon；数据table/steps/timeline/alert(alert_type或variant)/stat/empty/notice/progress_ring；图表line_chart/bar_chart/pie_chart/sparkline；工具qrcode/barcode/countdown/calendar/breadcrumb；媒体video/audio/html；输入input/number/password/multiline/otp/email/tel/url/search/search_bar/tag_input；选择select/switch/checkbox/checkbox_group/radio/radio_group/date/time/datetime/color/rating/toggle/dropdown/stepper/slider_range；交互button(提交时收集全部带key控件值)/link/slider/progress/spinner；文件file；装饰divider/divider_v/separator。通用属性: width/height(match/wrap/数字dp/百分比), margin(数字或{top,left,bottom,right}), weight或flex(弹性), align, style(统一style对象:padding/radius/background/border/fontSize/color,见style参数说明), 容器spacing/alignItems/justify", false)
                    .addParameter("render", "object", "register_type 用：渲染定义 {card:内置卡片类型 或 layout:原生控件框架树, props:固定字段}；也可作 create 现场 layout 的容器(render={layout:...} 等效顶层 layout)；layout 控件清单见 layout 参数说明。按钮可加 tool+tool_params 调后端。带 key 控件提交后 get_result 返回 values 收集", false)
                    .addParameter("style", "object", "统一 style 对象：layout 树任意节点可带 style={padding(整数或 t r b l 四空格值或[4个数]),radius(圆角dp),background(颜色#RRGGBB),border(边框,如 1 #CCCCCC 或 border_width+border_color),fontSize(文本字号),color(文本色)}，与顶层字段等效（顶层优先），column/row/card/text 等通用控件均支持；内置卡片/框架控件卡片传 style 时容器级样式生效（padding/radius/background/border 作用于卡片外观，fontSize/color 放组件 props 内由各卡片字段控制）", false)
                    .addParameter("monitor", "object", "register_type 用（可选）：任务监控 {tool,action,poll_seconds,param_map,success_field}，创建后自动轮询", false)
                    .category("system")
                    .build();
            case "ui_component_plugin":
                return ToolDefinition.builder("ui_component_plugin", "原生UI组件插件系统：Agent动态创建/复用原生UI组件插件（任何自定义组件类型，类型安全，兼容校验）与原生layout模板库（可复用控件模板）。插件动作: create(注册插件)/template(取标准模板)/validate(校验定义不落库)/get(查单个)/list(列出全部)/remove(删除)/clear_temporary(清除临时插件)；layout模板动作: register_layout(注册命名layout模板)/layout_list(列出)/layout_remove(删除)/layout_clear_temporary(清除临时模板)。生命周期由任务决定: persist=true长久落盘可复用, false临时仅内存任务结束即消失。兼容性自动校验: 插件名仅字母数字下划线、params类型限string/number/boolean/array/object、render.card限项目内置卡片、render.layout限项目原生控件框架、render不能为空、monitor.tool限已注册工具。创建组件时自动按params schema校验: 缺必填报错/类型转换/默认值填充。创建后可用ui_component(action=update,component_id=...,props={新参数})动态刷新。**插件/类型/模板的复用**: 注册的插件名和register_type类型名可直接作其他layout树的节点type嵌套({\"type\":\"插件名\"},自动展开其render.layout,节点props覆盖占位)；layout模板注册后任意layout内可用{\"use\":\"模板名\",\"props\":{参数}}引用,模板内{key}由props替换。")
                    .addParameter("action", "string", "操作: create/template/validate/get/list/remove/clear_temporary/register_layout/layout_list/layout_remove/layout_clear_temporary", true)
                    .addParameter("name", "string", "插件名或layout模板名（create/get/remove/register_layout/layout_remove 用），仅字母数字下划线", false)
                    .addParameter("description", "string", "插件或模板用途说明（create/register_layout 用，给模型看）", false)
                    .addParameter("params", "object", "参数 schema JSON（create 用）：{字段名: {type: string|number|boolean|array|object, required: 可选布尔, default: 可选, description: 可选, enum: 可选数组}}", false)
                    .addParameter("render", "object", "渲染配置 JSON（create 用，可选）：{card: 项目内置卡片类型(如 info_card/progress_card), title: 标题, props: 卡片固定字段, layout: 项目原生控件框架树(JSON 声明原生 UI，见下)}。layout 示例: {root:{type:'column',children:[{type:'text',text:'标题'},{type:'input',hint:'输入',key:'name'},{type:'button',text:'提交',action:'submit'}]}}；控件 type: 布局 column/row/scroll/card/wrap(流式换行)/grid(网格,columns)/space(弹性空白)/tabs(标签页,tabs=[{label,content}])/stack(层叠)/accordion(折叠面板)/carousel(图片轮播)；展示 text/marquee(跑马灯,speed 0~3)/image/badge/avatar/avatar_group/quote/code/icon；数据 table(headers/rows)/steps(步骤条)/timeline(时间线)/alert(提示条,样式字段用alert_type或variant)/stat(指标卡)/empty(空态)/notice(通知条)/progress_ring(环形进度)；图表 line_chart(折线)/bar_chart(柱状)/pie_chart(饼图)/sparkline(迷你趋势)；工具 qrcode(二维码)/barcode(条形码)/countdown(倒计时)/calendar(日历)/breadcrumb(面包屑)；媒体 video(url或src,title,autoPlay,loop,speed)/audio(url或src,title,artist)/html(富文本)；输入 input/number/password/multiline/otp/email/tel/url/search/search_bar/tag_input；选择 select/switch/checkbox/checkbox_group/radio/radio_group/date/time/datetime/color/rating/toggle(胶囊开关)/dropdown(下拉)/stepper(步进器)/slider_range(双滑块)；交互 button/link(text,url或action)/slider/progress/spinner；文件 file；装饰 divider/divider_v/separator；通用属性 width/height(match/wrap/数字dp/百分比)/margin(数字或对象)/weight或flex(弹性)/align(对齐)/style(统一style对象:padding/radius/background/border/fontSize/color)/容器spacing/alignItems/justify；自定义模板 use=名字（register_layout注册或layout顶层define）；**嵌套**: 已注册插件名/类型名可直接作layout节点type嵌套({\"type\":\"插件名\"})，未注册类型节点带layout字段现场展开({\"type\":\"x\",\"layout\":{...}})；后端组件: button 可加 tool=后端工具名+tool_params={参数,支持{key}占位符}，点击直接调用后端工具并回传结果", false)
                    .addParameter("layout", "object", "layout模板定义（register_layout 用）：控件树JSON，如{type:'card',title:'{label}',children:[...]}，模板内{key}由use的props替换；注册后任意layout内可用use=模板名引用", false)
                    .addParameter("monitor", "object", "任务监控配置 JSON（create 用，可选）：{tool: 已注册工具名, action: 工具action参数, poll_seconds: 轮询间隔秒数(2~30,默认5), param_map: {组件props字段: 查询参数名}, success_field: 查询结果含该字段即完成(展示该文件路径), error_field: 失败原因字段(可选)}", false)
                    .addParameter("persist", "boolean", "生命周期（create/register_layout 用）：true=长久插件落盘跨重启保留可复用；false=临时插件仅内存任务结束即消失", false)
                    .category("system")
                    .build();
            case "system_ui_control":
            case "ui_control":
                // 遗留工具名 → ui_component（系统UI组件控制：对话框/提示条/进度条/输入等）
                return getToolDefinition("ui_component");
            case "control_lookup":
                return ToolDefinition.builder("control_lookup", "控件参数查询：按关键词检索低频 UI 控件的详细参数说明(视频/音频/图表/二维码/日期等)。动作: search(按关键词查控件参数)/list(列出全部低频控件)。当要构建含低频控件的 layout 但不确定字段时调用,避免凭记忆猜字段。高频常用控件(text/button/input/select/table等)已在系统提示词列出,无需查询。")
                        .addParameter("action", "string", "操作: search(按关键词查)/list(列出全部低频控件)", true)
                        .addParameter("keyword", "string", "搜索关键词(search用): 控件名或功能,如video/二维码/图表/stepper/marquee", false)
                        .category("meta")
                        .build();
            case "layout_editor":
                return ToolDefinition.builder("layout_editor", "布局画布编辑器：动态编辑常驻布局画布(layout_canvas组件)的控件树。动作: set(整体替换布局)/add(追加子节点)/patch(修改或删除节点)/get(查看当前布局)/rebuild(强制重渲染)。前置: 先用 ui_component(action=create, component_type=layout_canvas, layout=完整带输入控件的布局, title=标题) 创建画布拿 component_id——**create 时就要带含 input/button 的完整布局，不要只建空画布**；每个 input/select/switch/date/number 必须带 key，button 必须带 action，否则控件无法收集值/不可用。后续编辑用同一 component_id。推荐 set 整树替换: layout_editor(action=set, component_id=画布id, layout={\"root\":{\"type\":\"column\",\"spacing\":12,\"children\":[{\"type\":\"text\",\"text\":\"标题\",\"bold\":true},{\"type\":\"input\",\"key\":\"name\",\"hint\":\"输入姓名\"},{\"type\":\"button\",\"text\":\"提交\",\"action\":\"submit\"}]}})。或用 add 逐个加**单个控件节点**(非容器): add(component_id, node={\"type\":\"input\",\"key\":\"name\",\"hint\":\"输入姓名\"})。**全程用同一个 component_id，不要反复重建画布**；add 的 node 必须是单个控件，勿传含 children 的容器。")
                        .addParameter("action", "string", "操作: set/add/patch/get/rebuild", true)
                        .addParameter("component_id", "string", "画布组件ID(layout_canvas创建返回的component_id),必填,全程保持同一个", true)
                        .addParameter("layout", "object", "set用: 完整布局JSON(如{\"root\":{\"type\":\"column\",\"children\":[...]}}或单节点{\"type\":\"column\"})；add用也可传单控件", false)
                        .addParameter("node", "object", "add用: **单个控件节点JSON**(如{\"type\":\"input\",\"key\":\"name\",\"hint\":\"输入姓名\"}或{\"type\":\"button\",\"text\":\"提交\",\"action\":\"submit\"}或{\"type\":\"select\",\"options\":[\"A\",\"B\"],\"key\":\"choice\"}); 不要传含children的容器; 也兼容layout/item/child参数名", false)
                        .addParameter("container", "string", "add用: 目标容器路径(如'root'或'root/children/0'),默认'root'", false)
                        .addParameter("index", "integer", "add用: 插入位置(0为开头,省略则追加到末尾)", false)
                        .addParameter("key", "string", "patch用: 节点key(容器/节点带key时用key定位)", false)
                        .addParameter("path", "string", "patch用: 节点路径(如'root/children/0');与key二选一", false)
                        .addParameter("props", "object", "patch用: 要合并到节点的属性(替换text/key/value等)", false)
                        .addParameter("remove", "boolean", "patch用: true删除该节点(默认false)", false)
                        .category("system")
                        .build();
            case "tool_registry":
                return ToolDefinition.builder("tool_registry", "工具注册表(MCP式工具发现)：列出可用工具(list)、按关键词搜索工具(search)、获取单个工具完整参数schema(get)。模型不确定有哪些工具或需要某工具详细参数时调用，避免猜测工具名/参数。")
                    .addParameter("action", "string", "操作: list(列出)/search(搜索)/get(取schema)", true)
                    .addParameter("keyword", "string", "搜索关键词(仅search用)", false)
                    .addParameter("tool", "string", "工具名(仅get用)", false)
                    .category("meta")
                    .build();
            case "permission_manager":
                return ToolDefinition.builder("permission_manager", "智能权限管理工具，支持权限检查、请求和管理功能")
                    .addParameter("action", "string", "操作类型: check/check_all/request/request_and_wait/get_status/list_permissions/explain_permission/can_request", false, "check")
                    .addParameter("permission", "string", "权限名称（如camera/位置/录音/存储/拨打电话/发送短信等）", false)
                    .addParameter("permissions", "array", "权限列表（用于check_all操作）", false)
                    .category("system")
                    .build();
            case "ocr_recognize":
                return ToolDefinition.builder("ocr_recognize", "图片理解工具：OCR文字识别 + 视觉问答（看图理解）。识别图片/PDF文字，或看图回答用户问题")
                    .addParameter("action", "string", "操作类型: ocr_recognize(识别图片文字)/ocr_recognize_pdf(识别PDF文字)/image_understand(图片理解视觉问答,自动选模型;image_understand_local/online=指定本地/在线视觉模型)/ocr_set_language(设置语言)/ocr_get_language(获取语言)", false, "ocr_recognize")
                    .addParameter("image_path", "string", "图片路径(ocr_recognize/image_understand用)：绝对路径或 content:// 或 file:// URI", false)
                    .addParameter("pdf_path", "string", "PDF路径(ocr_recognize_pdf用)：绝对路径或 content:// 或 file:// URI", false)
                    .addParameter("question", "string", "关于图片的问题(image_understand用，如：图里有什么？描述一下这张图)", false)
                    .addParameter("language", "string", "识别语言: auto/chinese/english/japanese/korean(可选)", false)
                    .category("utility")
                    .build();
            case "video_to_player":
                return ToolDefinition.builder("video_to_player", "视频下载转播放工具：输入视频页面链接或直链URL，解析视频源并下载到本地工作区，返回 local_path（mp4绝对路径）供 video_player 组件渲染原生播放。直链(mp4/webm等扩展名或视频Content-Type)直接下载；网页链接抓取HTML提取og:video或<video>标签src后下载。下载完成后返回文件卡片可直接点击全屏播放。用户说\"播放视频\"时用本工具下载后创建 video_player 组件")
                    .addParameter("url", "string", "视频页面链接或直链URL（必填）", true)
                    .addParameter("title", "string", "视频标题（可选，默认取文件名）", false)
                    .addParameter("timeout", "integer", "下载超时秒数（可选，默认120）", false)
                    .category("media")
                    .build();
            case "system_connect":
                return ToolDefinition.builder("system_connect", "系统级连接与设备能力工具。"
                        + "系统级UI: notify(系统通知)/floating_window(悬浮窗,需权限)/toast(屏幕提示)/screenshot(截屏,需MediaProjection)。"
                        + "系统级数据: clipboard(剪贴板读写)/battery(电池状态)/network(网络状态)/volume(音量控制)/brightness(亮度调节)。"
                        + "系统级连接: wifi(WiFi状态/开关)/bluetooth(蓝牙状态/开关)/hotspot(热点,需系统权限)/usb(USB状态)/screen(屏幕)。"
                        + "系统参数与设备连接管理统一走本工具；跳转系统设置页用 system_resource(open_settings)。")
                    .addParameter("action", "string", "操作: notify/toast/floating_window/close_floating/screenshot/clipboard/battery/network/volume/brightness/wifi/bluetooth/hotspot/usb/screen", true)
                    .addParameter("title", "string", "通知标题（notify用）", false)
                    .addParameter("content", "string", "通知内容（notify用）", false)
                    .addParameter("text", "string", "Toast文本/剪贴板写入内容/悬浮窗文字", false)
                    .addParameter("op", "string", "子操作: read/write/get/set/up/down/on/off/status/keep_on/keep_off/mobile", false)
                    .addParameter("enable", "boolean", "开关值（network-mobile/hotspot用）", false)
                    .addParameter("stream", "string", "音量类型: music/ring/alarm/notification", false)
                    .addParameter("value", "integer", "数值（音量0-100/亮度0-255）", false)
                    .addParameter("path", "string", "截图保存路径（screenshot用）", false)
                    .addParameter("x", "integer", "悬浮窗X坐标", false)
                    .addParameter("y", "integer", "悬浮窗Y坐标", false)
                    .category("system")
                    .build();
            case "ai_create_tool":
                return ToolDefinition.builder("ai_create_tool", "AI创建工具：使用AI自动生成新工具（工具名+描述+参数定义+执行逻辑），创建后可直接被后续对话调用")
                    .addParameter("tool_name", "string", "工具名称", true)
                    .addParameter("description", "string", "工具描述", true)
                    .addParameter("parameters", "string", "参数定义", false)
                    .addParameter("logic", "string", "执行逻辑", false)
                    .category("tool")
                    .build();
            case "voice_input":
                return ToolDefinition.builder("voice_input", "语音输入工具：将语音/音频转换为文字（语音识别ASR）。本地语音识别（SenseVoice）已内置启用，无需在线模型配置即可使用；在线模型未配置时自动走本地识别。支持交互式录音(record，弹出录音组件让用户说话，点完成结束，录音前自动停止TTS播放防串音)、固定时长录音识别(record_and_recognize，需录音权限)、识别已有音频文件(recognize)、检查可用性(check)。用户说要用语音/要说话/听写时优先用 record 让用户录音")
                    .addParameter("action", "string", "操作类型: recognize(识别音频文件)/record(交互式录音组件，推荐)/record_and_recognize(固定时长录音)/check(检查可用性)", false, "recognize")
                    .addParameter("audio_path", "string", "音频文件路径(mp3/m4a/wav/amr等，与audio_uri二选一)", false)
                    .addParameter("audio_uri", "string", "音频content:// URI(与audio_path二选一)", false)
                    .addParameter("language", "string", "语言提示(zh/en)，默认自动检测", false)
                    .addParameter("duration_seconds", "integer", "录音时长/上限(秒)：record_and_recognize固定录音默认15，record交互式默认30上限60", false, 15)
                    .addParameter("title", "string", "录音组件标题(record用，默认🎤请说话)", false)
                    .addParameter("hint", "string", "录音组件提示文字(record用)", false)
                    .addParameter("timeout_seconds", "integer", "识别超时(秒)，默认30；record等待用户操作超时默认90", false, 30)
                    .category("speech")
                    .build();
            case "speech_synthesis":
                return ToolDefinition.builder("speech_synthesis", "语音合成工具：将文字合成为语音并播放，或保存为音频文件(TTS)。在线TTS不可用时自动回退系统TTS。synthesize=阻塞播放；speak=带播放组件朗读(弹出🔊对话框可见可停止,返回component_id,用ui_component get_result等待completed/stopped)；save=保存文件；play=播放音频；stop=停止；voices/set_voice=音色。播放与应用层共享SpeechManager，应用层停止按钮同样生效")
                    .addParameter("action", "string", "操作类型: synthesize(阻塞播放)/speak(带组件朗读,非阻塞)/save(合成保存为文件)/play(播放音频文件)/stop(停止播放)/check(检查可用性)/voices(获取音色列表)/set_voice(设置音色)", false, "synthesize")
                    .addParameter("text", "string", "要合成/朗读的文字(synthesize/speak/save用)", false)
                    .addParameter("voice", "string", "音色ID(如alloy/echo/nova/shimmer，或sys:系统音色；voices可查列表)", false)
                    .addParameter("title", "string", "播放组件标题(speak用，默认🔊正在朗读)", false)
                    .addParameter("output_path", "string", "输出音频文件路径(save用，不传自动保存到缓存目录)", false)
                    .addParameter("audio_path", "string", "音频文件路径(play用)", false)
                    .addParameter("save_voice", "boolean", "是否持久化音色(set_voice用，默认false)", false, false)
                    .addParameter("duration_seconds", "integer", "最长播放时长(speak用，0=不限)", false, 0)
                    .addParameter("timeout_seconds", "integer", "合成/播放超时(秒)，默认30，播放最长300", false, 30)
                    .category("speech")
                    .build();
            case "excel_tool":
                return ToolDefinition.builder("excel_tool", "Excel表格工具(xls/xlsx)：查询与修改。查询=sheets(工作表列表)/query(按条件过滤行:列名+op+match_value)/cell(读单元格)；修改=write_cell(改单元格)/add_row(追加行)/add_sheet(新建工作表)，修改后自动保存回原文件或output_path。坐标：sheet(名称或索引)、cell_ref(A1如B3)或row(1-based)+column(列名/列字母/列号)。支持content:// URI(file_uri)。整表解析/阅读用 file_reader.parse_excel")
                    .addParameter("action", "string", "操作: sheets/query/cell/write_cell/add_row/add_sheet", true)
                    .addParameter("file_path", "string", "文件路径(支持content://开头URI，与file_uri二选一)", false)
                    .addParameter("file_uri", "string", "content:// URI(与file_path二选一)", false)
                    .addParameter("output_path", "string", "另存路径(修改类操作，不传默认保存回原文件)", false)
                    .addParameter("sheet", "string", "工作表名称或索引(默认0)", false)
                    .addParameter("cell_ref", "string", "单元格A1引用如B3(cell/write_cell用)", false)
                    .addParameter("row", "integer", "1-based行号(与column配合)", false)
                    .addParameter("column", "string", "列名(表头)/列字母/1-based列号(与row配合)", false)
                    .addParameter("value", "string", "写入值(write_cell用，自动识别数字/布尔/文本，=开头为公式)", false)
                    .addParameter("values", "array", "行数据数组(add_row用，如[\"张三\",18,\"北京\"])", false)
                    .addParameter("new_sheet_name", "string", "新工作表名称(add_sheet用)", false)
                    .addParameter("column_name", "string", "条件列名(query用，别名row_column)", false)
                    .addParameter("op", "string", "比较操作: eq(等于)/ne/contains/gt/gte/lt/lte", false, "eq")
                    .addParameter("match_value", "string", "匹配值(query用)", false)
                    .addParameter("row_start", "integer", "起始行号(query用，1-based，含)", false)
                    .addParameter("row_end", "integer", "结束行号(query用，1-based，含)", false)
                    .addParameter("max_rows", "integer", "最大返回行数(query用，默认100)", false, 100)
                    .category("data")
                    .build();
            case "import_list_files":
                return ToolDefinition.builder("import_list_files",
                        "题库文件发现（智能体全自动导入第一步）：列出设备上可导入的题库文件（Excel .xlsx/.xls、CSV、JSON、Markdown .md、文本 .txt），"
                                + "返回 JSON [{path,name,size,type,modified}] 按修改时间倒序。扫描范围：应用公共目录 OilQuiz（含 source 子目录）与系统 Download 目录；"
                                + "拿到 path 后传给 import_start 的 filePath 参数。导入前必须先调用本工具确认文件路径，避免 import_start 报文件不存在。")
                        .addParameter("dir", "string", "要扫描的目录路径（可选，默认自动扫描公共目录 OilQuiz 与 Download）", false)
                        .addParameter("keyword", "string", "文件名关键字过滤（可选，如\"数学\"\"期中\"）", false)
                        .category("data")
                        .whenToUse("用户要求导入题库/题目文件，或需要确认某个题库文件是否存在、路径是什么时，先调用本工具发现文件")
                        .build();
            case "import_start":
                return ToolDefinition.builder("import_start",
                        "题库导入（启动）：异步启动 v2 智能导入管线，把题库文件（Excel/CSV/JSON/Markdown/文本）解析为题目并入库。"
                                + "工作表由调用方显式指定（sheetMode=index/multi/all，不再自动选表）；"
                                + "四个决策点（字段映射/数据预览/智能填充/入库）由你创建多功能 ui_component 与用户交互、"
                                + "get_result 取值后 import_decide 回传（interactive=true，默认）；传入 interactive=false 才全自动无人值守；"
                                + "filePath 必填（先用 import_list_files 发现文件拿到 path），返回 taskId 后用 import_status 轮询（RUNNING→DONE/ERROR），"
                                + "完成后返回新增/重复/失败统计；import_cancel 取消。导入耗时数十秒到数分钟，不要重复启动同一文件。"
                                + "多文件可依次启动多个任务并行导入。")
                        .addParameter("filePath", "string", "题库文件完整路径（必填，先用 import_list_files 发现）", true)
                        .addParameter("sheetMode", "string", "工作表选择模式（必须显式指定）：index(按sheetIndex单表)/multi(按sheetIndexes多表)/all(全扫全部表)；best/auto 已移除，传了会被拒绝", false, "all",
                                java.util.Arrays.asList("index", "multi", "all"))
                        .addParameter("sheetIndex", "integer", "Excel 工作表索引（sheetMode=index 时生效；-1=自动，默认 -1）", false, -1)
                        .addParameter("docHint", "string", "题库说明/字段约定（帮助 AI 识别列含义，可选）", false)
                        .addParameter("fillMissing", "boolean", "是否 AI 补全缺失字段（题型/难度/分类/解析，默认 true）", false, true)
                        .addParameter("skipIncomplete", "boolean", "是否跳过缺字段的行（默认 false=尽量保留）", false, false)
                        .addParameter("questionType", "string", "强制题型（可选，如\"单选题\"；默认按内容自动识别）", false)
                        .addParameter("interactive", "boolean", "是否交互确认（默认 true=四决策点由你创建 ui_component 交互并 import_decide 回传；false=全自动无人值守）", false, true)
                        .category("data")
                        .whenToUse("用户要求把题库文件导入 App/入库题目时使用；先 import_list_files 拿到路径，再本工具启动，然后 import_status 轮询到完成")
                        .build();
            case "import_status":
                return ToolDefinition.builder("import_status",
                        "题库导入（状态查询）：查询 import_start 启动的任务进度与结果，返回 JSON {taskId,status(RUNNING|DONE|ERROR|CANCELLED|NOT_FOUND),"
                                + "stage(start|sheet|mapping|parse|fill|ingest|done|error),current,total,imported,duplicated,failed,totalRows}。"
                                + "若返回含 pendingDecision（awaitingDecision=true），说明正在等你创建 ui_component 与用户交互并 import_decide 回传："
                                + "pendingDecision 含 type/title/message/options/payload，按它渲染选择组件，拿到用户选择后 import_decide。"
                                + "导入中建议每隔数秒查询一次，直到 status=DONE 后向用户汇报统计；status=ERROR 时读取 error 字段说明原因。")
                        .addParameter("taskId", "string", "import_start 返回的任务 ID（必填）", true)
                        .category("data")
                        .whenToUse("import_start 启动后轮询导入进度/结果时使用；返回 pendingDecision 时先 ui_component 交互再 import_decide")
                        .build();
            case "import_cancel":
                return ToolDefinition.builder("import_cancel",
                        "题库导入（取消）：停止进行中的导入任务，返回 {taskId,status:CANCELLED}。")
                        .addParameter("taskId", "string", "import_start 返回的任务 ID（必填）", true)
                        .category("data")
                        .whenToUse("用户要求停止导入，或导入长时间卡住需要中止时使用")
                        .build();
            case "import_decide":
                return ToolDefinition.builder("import_decide",
                        "题库导入（决策回传）：当 import_status 返回 pendingDecision 时，把用户在 ui_component 上的选择回传给导入管线，导入才会继续。"
                                + "返回 {ok:true,decisionId,choice,selected}；失败含 message（如未识别选择/decisionId 不匹配/无待确认决策点）。")
                        .addParameter("taskId", "string", "import_start 返回的任务 ID（必填）", true)
                        .addParameter("decisionId", "string", "import_status 的 pendingDecision.decisionId（必填）", true)
                        .addParameter("selected", "string", "用户选择的选项文本（ui_component get_result 的返回；取消传\"取消导入\"或\"cancelled\"）", false)
                        .addParameter("choice", "integer", "用户选择的选项下标（0 起；与 selected 二选一；取消=最后一项）", false)
                        .addParameter("mapping", "object", "字段映射修改（标准字段→源列名；仅决策点=mapping 时可传）", false)
                        .category("data")
                        .whenToUse("import_status 返回 pendingDecision 后，用 ui_component 与用户交互拿到选择，再用本工具回传")
                        .build();
            case "chat_history":
                return ToolDefinition.builder("chat_history",
                        "对话历史：读取本地保存的聊天历史（跨会话）。新会话里回忆之前说过的话、上次创建的工具/组件/文件时使用；"
                                + "比如用户说\"之前让你创建过xx\"\"上次那个组件\"\"历史里找\"时调用。"
                                + "action: recent(最近消息，source=ai|agent，limit条数默认30最大200)|search(关键词搜索，keyword必填)|count(条数)。"
                                + "返回带序号与角色的消息内容，按时间倒序。历史只读，不能修改/删除。")
                        .addParameter("action", "string", "操作：recent(最近消息，默认)|search(关键词搜索)|count(消息条数)", false)
                        .addParameter("source", "string", "历史来源：ai(AI对话历史，默认)|agent(Agent对话历史)", false)
                        .addParameter("limit", "integer", "最近消息条数（recent用，默认30，最大200）", false)
                        .addParameter("keyword", "string", "搜索关键词（search用）", false)
                        .category("memory")
                        .whenToUse("用户提到之前的对话/历史/上次创建的东西，或 Agent 需要跨会话上下文时使用")
                        .build();
            case "js_execute":
                // 显式定义：保证在线 function calling 拿到正确的 code/timeout 类型
                return ToolDefinition.builder("js_execute",
                        "JS代码执行工具：在后台执行 JavaScript 代码并返回运行结果（弥补手机端无 Node 的缺口）。"
                                + "code=要执行的JS代码（语句或表达式均可），返回：是否成功、返回值、console.log 输出、错误信息。"
                                + "支持标准 ES6 核心语法（箭头函数/模板字符串/解构/let-const/class/Promise/async-await），"
                                + "支持 Promise 异步结果（受 timeout 限制）；支持受限网络 window.__http.get(url,opts)/post(url,body,opts)"
                                + "（返回 Promise，解析后为{ok,status,body,setCookies}对象；opts.headers 可传自定义请求头，"
                                + "未显式传 Cookie 时自动携带应用内该站 Cookie，响应 Set-Cookie 随 setCookies 返回；"
                                + "读 Cookie 用 window.__http.cookies(url)；仅 http/https，6s 超时，响应≤512KB）；"
                                + "支持受限文件 window.__fs.read/write/list/delete/exists"
                                + "（返回 Promise，解析后为对象；仅限工作区 files/ 内，防穿越，文件≤512KB）；"
                                + "执行引擎是系统 WebView 的 JS 引擎，**无 Java 互操作**（Java 包/类不可用，非 Rhino/Nashorn）；"
                                + "**ES 能力以实测为准**：现代机型实测支持 ES2020+（可选链?. / ?? / BigInt / .at() 等），"
                                + "老机型 WebView 可能缺失较新特性，跨机型稳妥写法仍建议 ES6 核心语法；"
                                + "适合算法验证、数据转换/清洗、"
                                + "JSON 处理、正则测试、前端逻辑调试、URL 编解码、调用 HTTP API、读写工作区文件。"
                                + "url=可选，传 http/https 地址时先加载该真实网页，再在真实页面上下文执行代码"
                                + "（可读 document.cookie / localStorage / DOM；否则默认 about:blank 内存页，origin=null，"
                                + "document.cookie/localStorage 会被浏览器安全策略拒绝）。"
                                + "限制：默认（无 url）时原生 fetch/XHR 跨域被 CORS 拦截（网络用 __http、文件用 __fs），不能访问 DOM/页面渲染。"
                                + "代码顶层 return 已自动兼容（自动包 IIFE 并返回其结果），可直接 return 返回结果；"
                                + "并发上限：单批同时执行≤4路（超出等待2s后明确报「JS执行并发已满」，不静默丢值）；"
                                + "脚本内多次桥接调用请串行 await，勿 Promise.all 并发打桥；"
                                + "死循环用 timeout 参数（秒，默认8，最大30）超时终止。")
                        .addParameter("code", "string", "要执行的 JavaScript 代码（语句或表达式，可用 console.log 输出调试）", true)
                        .addParameter("url", "string", "可选，http/https 地址：先加载该真实网页，再在真实页面上下文执行代码（可读 document.cookie / localStorage）", false)
                        .addParameter("timeout", "integer", "执行超时秒数（1-30，默认 8）", false)
                        .category("code")
                        .whenToUse("用户要求运行/验证/调试 JavaScript 代码、处理 JSON 数据、测试正则或前端逻辑时使用；"
                                + "代码可能死循环时给 timeout。")
                        .build();
            case "pip_install":
                // 显式定义：保证在线 function calling 拿到 package/action/timeout 类型
                return ToolDefinition.builder("pip_install",
                        "pip安装工具（手机端Python运行时安装纯Python包）："
                                + "① 在线安装：从PyPI镜像下载wheel并安装，立即生效且跨重启保留；"
                                + "② 本地安装：package 传本地 .whl 路径（如 /sdcard/.../xxx-py3-none-any.whl）直接从文件安装；"
                                + "③ 只下载：action=download 仅下载wheel到工作区 files/wheels/ 返回路径，之后可再用本地安装装它；"
                                + "④ 换源：source 参数指定镜像源（tuna/aliyun/pypi/自定义URL）并持久化为默认，action=set_source 单独设置。"
                                + "package=包名（支持 name、name==版本、name>=版本、name~=版本）或本地whl路径。"
                                + "限制：只能装纯Python包（py3-none-any，如 pytz/tqdm/simplejson/python-docx 等）；"
                                + "带C扩展的包（numpy/scipy/lxml 等）在Android上无法运行时编译（设备无编译工具链、公共PyPI无Android ABI wheel），会明确拒绝并提示预打包。"
                                + "递归处理纯Python依赖，C依赖列入skipped返回。python_execute 可直接 import 已安装包。")
                        .addParameter("package", "string", "要安装的包名或带版本约束（pytz / python-docx==1.1.2 / requests>=2.31），或本地wheel文件路径（/sdcard/.../xxx-py3-none-any.whl）", true)
                        .addParameter("action", "string", "操作: install(默认，安装) / download(仅下载wheel到files/wheels/返回路径) / set_source(设置默认镜像源)", false)
                        .addParameter("source", "string", "镜像源: tuna(默认，清华) / aliyun(阿里云) / pypi(官方) / 自定义URL；设置后持久化为默认", false)
                        .addParameter("timeout", "integer", "下载超时秒数（默认 60，最大 300）", false)
                        .category("code")
                        .whenToUse("用户要求安装 Python 第三方库、下载 wheel 文件、从本地 wheel 安装或切换 pip 镜像源时使用")
                        .build();
            case "screen_capture":
                return ToolDefinition.builder("screen_capture",
                        "截屏工具：截取手机当前屏幕画面并保存到工作区 files/screenshots/，返回图片路径。"
                                + "截图后必须用 ocr_recognize 工具看图：action=ocr_recognize 识别图中文字，"
                                + "action=image_understand 看图理解/回答关于画面的问题（这是 Agent 的『眼睛』）。"
                                + "四种用法：① 截一帧：默认，返回当前画面路径；② 盯屏/轮询观察：action=watch，"
                                + "自动每隔几秒截一帧对比画面变化，检测到变化立即返回（含变化时刻与关键帧路径），"
                                + "变化后用 ocr_recognize(image_understand) 读关键帧即可理解画面，"
                                + "适合等页面加载/下载完成/用户操作结果等场景（观察结束记得 action=stop 停止屏幕共享）；"
                                + "③ 停止共享：action=stop，释放授权（下次截屏重新弹窗）。"
                                + "首次使用会弹系统授权框（共享屏幕授权）：工具会等待用户在弹窗中点击『立即开始』，"
                                + "授权完成后自动继续截屏；授权后同进程内可重复截屏不再弹窗；"
                                + "进程重启或用户在系统设置撤销授权后需重新授权。"
                                + "filename=可选，自定义保存文件名（默认 screen_时间戳.png）。"
                                + "适合：查看当前界面状态、核对用户操作结果、盯屏等页面变化、给后续步骤提供视觉上下文。"
                                + "更强大的盯梢（指定目标文字出现/消失）用 screen_watch 工具。")
                        .addParameter("filename", "string", "可选，保存文件名（默认 screen_时间戳.png，保存到 files/screenshots/）", false)
                        .addParameter("action", "string", "可选: 默认截一帧 / watch=盯屏轮询观察(每隔interval秒截一帧对比变化，检测到变化即返回) / stop=停止屏幕共享释放授权", false)
                        .addParameter("seconds", "integer", "watch 模式观察总时长（秒，默认 30，最大 300）", false)
                        .addParameter("interval", "integer", "watch 模式轮询间隔（秒，默认 3，最小 1）", false)
                        .category("system")
                        .whenToUse("需要查看手机当前屏幕画面/界面状态/用户操作结果时使用，截屏后用 ocr_recognize 看图；盯屏等页面变化/下载完成/用户操作时用 action=watch；截屏授权卡住时可用 action=stop 重置")
                        .build();
            case "screen_watch":
                return ToolDefinition.builder("screen_watch",
                        "盯梢工具（哨兵）：持续监控手机屏幕，直到『指定内容出现/消失』或『画面发生变化』才返回。"
                                + "① 指定目标文字：target=要等的文字，watch_for=appear(出现即报,默认)/disappear(消失即报)，"
                                + "轮询截帧并本地OCR识别判断，命中立即返回关键帧路径与识别文本，"
                                + "适合：等『下载完成/支付成功/加载完成』出现、等『加载中/处理中』消失；"
                                + "② 不指定目标：检测画面变化，画面一变立即返回（适合等页面跳转/内容刷新）。"
                                + "动作：action=start(默认)开始盯梢，agent会在目标命中或超时后返回；"
                                + "action=stop 停止当前盯梢；action=status 查询盯梢状态。"
                                + "参数：seconds=总时长(默认60,最大600)、interval=轮询间隔(默认3,最小2)。"
                                + "首次使用会弹『共享屏幕』授权框：请用户点击『立即开始』完成授权（工具会等待用户操作，不会中途失败）；"
                                + "授权后同进程内复用，盯梢结束可 action=stop 释放授权。"
                                + "盯梢过程中 App 需保持前台（Android 14 切后台投影会中断）。"
                                + "命中后用 ocr_recognize 读关键帧即可理解画面。")
                        .addParameter("action", "string", "start(默认,开始盯梢)/stop(停止盯梢)/status(查询盯梢状态)", false, "start")
                        .addParameter("target", "string", "要等待的目标文字（可选，不填则检测画面变化；如：下载完成、支付成功、加载中）", false)
                        .addParameter("watch_for", "string", "appear(默认,目标出现即报)/disappear(目标消失即报)", false, "appear")
                        .addParameter("seconds", "integer", "总盯梢时长（秒，默认60，最大600）", false)
                        .addParameter("interval", "integer", "轮询间隔（秒，默认3，最小2）", false)
                        .category("system")
                        .whenToUse("用户要求盯着屏幕等某个内容出现/消失（如等下载完成、等支付成功、等加载中消失）、或监控屏幕变化时使用 screen_watch；与 screen_capture 配合使用，命中后用 ocr_recognize 读关键帧理解画面")
                        .build();
            case "web_render":
                return ToolDefinition.builder("web_render",
                        "网页渲染浏览工具：用真实 WebView 打开网页并提取内容（能看到 JS 渲染后的真实页面，"
                                + "比 requests 抓 HTML 文本更完整）。"
                                + "url=网页地址（必填）；action=text（默认，返回标题+正文文本+链接数）/screenshot（截图保存）/both（文本+截图）。"
                                + "js=可选，页面加载完成后在真实页面上下文执行 JS（可读 document.cookie / localStorage，同步表达式，返回值与 console 输出随结果给出）。"
                                + "支持现代网页（含 SPA/JS 动态内容，加载完成后自动提取）；截图保存到工作区 files/screenshots/，返回路径。"
                                + "限制：仅支持 http/https；不能点击/填表，只读浏览。"
                                + "适合：查看网页实际内容、核对页面效果、调研资料、在真实页面读取登录态 Cookie/localStorage。")
                        .addParameter("url", "string", "要浏览的网页地址（必填，http/https）", true)
                        .addParameter("action", "string", "操作: text(默认，提取标题+正文) / screenshot(仅截图) / both(文本+截图)", false)
                        .addParameter("js", "string", "可选，页面加载完成后在真实页面上下文执行的 JS 代码（可读 document.cookie / localStorage，同步表达式）", false)
                        .addParameter("timeout", "integer", "页面加载超时秒数（默认 12，最大 30）", false)
                        .category("search")
                        .whenToUse("需要查看网页实际渲染内容/JS动态页面/核对页面效果时使用（比 python_web_reader 更完整）")
                        .build();
            case "knowledge_base":
                // 显式定义（而非从实例反射）：保证在线 function calling 拿到正确类型与 action 枚举，
                // 否则工具参数全被当成 string，模型容易漏填/填错导致调用失败。
                return ToolDefinition.builder("knowledge_base",
                        "知识库（用户维护的应用专属知识/笔记/资料 + 系统工具定义库 tool_defs 分类）："
                                + "search 全文检索知识库、add/add_batch 添加知识、import_json/import_file 导入知识 JSON、"
                                + "import_document 直接导入文件（Word/Excel/TXT/MD/CSV/PDF/HTML/图片OCR/音频ASR转写，自动切块）、"
                                + "delete 删除、clear 清空、stats 统计。"
                                + "用户问应用专属或用户自己资料里的内容时，先 search 知识库再回答，不要凭空作答。"
                                + "分类说明：category=tool_defs 是系统工具定义库（查询工具用法/参数/适用场景用）；"
                                + "不传 category 检索全部（用户知识各分类都会命中）；查用户资料时如需精准可传具体分类如 guide/faq。")
                    .addParameter("action", "string", "操作类型", true, null, Arrays.asList(
                            "search", "add", "add_batch", "import_json", "import_file",
                            "import_document", "delete", "clear", "stats"))
                    .addParameter("query", "string", "检索关键词（search 必填）：中文/英文/数字均可", false)
                    .addParameter("category", "string", "分类（search 过滤 / add 写入，可选，默认 general；tool_defs=系统工具定义库，查工具用法/参数时用）", false)
                    .addParameter("top_k", "integer", "返回条数上限（search 可选，默认 5，最大 20）", false)
                    .addParameter("semantic", "boolean", "语义重排（search 可选，默认 false）：true=在线 embedding 重排+rerank 精排（消费在线 API），未配置时自动回退关键词检索", false)
                    .addParameter("title", "string", "标题（add 必填；import_document 可选，图片/音频建议传语义化标题；delete 可按标题删）", false)
                    .addParameter("content", "string", "知识正文（add 必填）", false)
                    .addParameter("keywords", "string", "关键词，逗号分隔（add 可选，增强检索命中）", false)
                    .addParameter("source", "string", "来源标识，如文件名/链接（add 可选）", false)
                    .addParameter("id", "integer", "知识 ID（delete 使用，与 title 二选一）", false)
                    .addParameter("items", "array", "知识对象数组（add_batch 必填），每项 {title, content, category?, keywords?, source?}", false)
                    .addParameter("json", "string", "知识 JSON 字符串（import_json 必填）：{\"chunks\":[{...}]} 或裸数组", false)
                    .addParameter("file_path", "string", "文件绝对路径（import_file=知识 JSON 文件；import_document=Word/Excel/TXT/MD/CSV/PDF/HTML/图片/音频）", false)
                    .category("knowledge")
                    .whenToUse("用户问应用专属/个人笔记/课程资料类内容时先 knowledge_base(action=search, query=关键词)；"
                            + "用户要把文件、资料、笔记、录音、截图加入知识库时用 knowledge_base(action=import_document, file_path=绝对路径)")
                    .build();
            default:
                // 已注册工厂但未在 switch 中显式定义的工具（memory/workspace/image_gen/
                // time_date/calculator 等）：从工具实例动态派生描述，保证 Agent 工具清单完整。
                // 描述以工具类 getDescription/getParameterDescriptions 为准（单一来源）。
                AITool tool = createToolInstance(toolName);
                if (tool == null) {
                    // 2026-09-23 P0（模型第三轮自测反馈）：list 与 get 索引不一致——
                    // registerDynamicTool 注册的动态工具不在 toolFactories 中，createToolInstance
                    // 查不到 → get 返回 null → "看得见、用不了"。兜底查 dynamicTools，
                    // 使 getToolDefinition 与 getToolDescriptions 覆盖完全同源。
                    tool = dynamicTools.get(toolName);
                }
                return tool != null ? createToolDefinitionFromAITool(tool) : null;
        }
    }

    /** 通过注册的工厂创建工具实例（不缓存，仅用于派生描述/元数据） */
    private AITool createToolInstance(String toolName) {
        ToolFactory factory = toolFactories.get(toolName);
        if (factory == null) return null;
        try {
            return factory.create(context);
        } catch (Exception e) {
            Log.w(TAG, "实例化工具失败 " + toolName + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 从 AITool 实例创建 ToolDefinition
     * 动态工具（DynamicAITool）优先使用结构化参数（真实类型/必填/默认值/枚举），
     * 其余工具回退到 name→desc（string 类型）。
     */
    private ToolDefinition createToolDefinitionFromAITool(AITool tool) {
        try {
            ToolDefinition.Builder builder = ToolDefinition.builder(
                tool.getName(),
                tool.getDescription()
            );
            
            List<com.oilquiz.app.ai.tool.openai.ParamDefinition> defs = null;
            // 2026-09-23：统一走 StructuredParamTool 接口（DynamicAITool / PythonDynamicTool），
            // 保证动态工具（含 Python 执行体）在 tool_registry(get) 中解析出结构化参数，
            // 不再退回"只有 Map 描述 → 全 string schema → 模型按错误 schema 调用"
            if (tool instanceof com.oilquiz.app.ai.tool.openai.StructuredParamTool) {
                defs = ((com.oilquiz.app.ai.tool.openai.StructuredParamTool) tool).getParameterDefinitions();
            }
            if (defs == null || defs.isEmpty()) {
                Map<String, String> paramDescriptions = tool.getParameterDescriptions();
                if (paramDescriptions != null) {
                    defs = new ArrayList<>();
                    for (Map.Entry<String, String> entry : paramDescriptions.entrySet()) {
                        defs.add(new com.oilquiz.app.ai.tool.openai.ParamDefinition(
                                entry.getKey(), "string", entry.getValue(), false));
                    }
                }
            }
            if (defs != null) {
                for (com.oilquiz.app.ai.tool.openai.ParamDefinition def : defs) {
                    String type = def.getType() != null && !def.getType().isEmpty() ? def.getType() : "string";
                    // 已知枚举参数覆盖：普通 AITool（无结构化参数）的 action/type 等枚举参数，
                    // 手动补上枚举值，让模型看到全部可选 action（否则 2B 模型只敢用第一个值）
                    List<String> enumOverride = getParamEnumOverride(tool.getName(), def.getName());
                    if (enumOverride != null) {
                        builder.addParameter(def.getName(), type, def.getDescription(),
                                def.isRequired(), def.getDefaultValue(), enumOverride);
                    } else if (def.getDefaultValue() != null) {
                        builder.addParameter(def.getName(), type, def.getDescription(),
                                def.isRequired(), def.getDefaultValue(), def.getEnumValues());
                    } else if (def.getEnumValues() != null && !def.getEnumValues().isEmpty()) {
                        builder.addParameter(def.getName(), type, def.getDescription(),
                                def.isRequired(), null, def.getEnumValues());
                    } else {
                        builder.addParameter(def.getName(), type, def.getDescription(), def.isRequired());
                    }
                }
            }
            
            return builder.build();
        } catch (Exception e) {
            Log.e(TAG, "Error creating ToolDefinition from AITool: " + e.getMessage());
            return null;
        }
    }

    /** 已知工具的枚举参数覆盖表（工具名 → 参数名 → 枚举值列表）。
     *  仅用于普通 AITool（getParameterDescriptions 无结构化枚举）的常见枚举参数，补充模型可见性。 */
    private static final Map<String, Map<String, List<String>>> PARAM_ENUM_OVERRIDES = buildParamEnumOverrides();

    private static Map<String, Map<String, List<String>>> buildParamEnumOverrides() {
        Map<String, Map<String, List<String>>> map = new HashMap<>();
        Map<String, List<String>> weather = new HashMap<>();
        weather.put("action", Arrays.asList("current", "forecast", "hourly",
                "air_quality", "alerts", "indices", "all"));
        map.put("ai_weather", weather);
        Map<String, List<String>> chart = new HashMap<>();
        chart.put("action", Arrays.asList("bar", "line", "pie", "scatter",
                "radar", "heatmap", "area", "table"));
        map.put("python_chart", chart);
        Map<String, List<String>> appToolkit = new HashMap<>();
        // 与实际执行 switch 对齐（36 个真实 action，避免分类名调用落到 default 报"未知操作"）
        appToolkit.put("action", Arrays.asList("weather_current", "weather_forecast", "weather_hourly",
                "weather_air", "weather_alerts", "weather_indices", "weather_all", "calculate",
                "ocr_recognize", "ocr_recognize_pdf", "ocr_set_language", "ocr_get_language",
                "image_label_recognize", "image_label_set_threshold", "image_label_get_threshold",
                "image_label_load_custom_model", "object_detect", "object_set_threshold",
                "object_get_threshold", "object_set_multiple", "object_set_classification",
                "image_save", "image_scale", "image_crop", "image_rotate",
                "image_generate_color", "image_generate_text",
                "web_parse_html", "web_get_title", "web_get_links", "web_get_images", "web_get_text",
                "get_info", "get_guide", "predict_intent", "debug_report"));
        Map<String, List<String>> memory = new HashMap<>();
        memory.put("action", Arrays.asList("save", "recall", "delete", "list", "clear"));
        map.put("memory", memory);
        return map;
    }

    /** 查询工具参数的枚举覆盖（无覆盖返回 null） */
    private static List<String> getParamEnumOverride(String toolName, String paramName) {
        Map<String, List<String>> params = PARAM_ENUM_OVERRIDES.get(toolName);
        if (params == null) return null;
        return params.get(paramName);
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
                    sb.append(": ").append(param.getDescription()).append("\n");
                }
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }
}


