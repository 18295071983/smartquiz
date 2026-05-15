package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

/**
 * AI工具管理器，负责管理和执行AI工具
 */
public class AIToolManager {
    private static final String TAG = "AIToolManager";
    private static AIToolManager instance;
    private final Context context;
    private final Map<String, AITool> tools;
    
    private AIToolManager(Context context) {
        Context appContext;
        try {
            appContext = context.getApplicationContext();
        } catch (Exception e) {
            appContext = context;
        }
        this.context = appContext;
        this.tools = new HashMap<>();
        try {
            initializeTools();
        } catch (Throwable e) {
            Log.e(TAG, "Error initializing tools: " + e.getMessage(), e);
        }
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
     * 初始化工具列表
     */
    private void initializeTools() {
        try {
            tools.put("file", new FileTool(context));
            tools.put("database", new DatabaseTool(context));
            tools.put("network_search", new NetworkSearchTool(context));
            tools.put("webpage_reader", new WebPageReaderTool(context));
            tools.put("smart_research", new SmartResearchTool(context));
            tools.put("system_resource", new SystemResourceTool(context));
            tools.put("file_reader", new FileReaderTool(context));
            tools.put("file_analyzer", new FileAnalyzerTool(context));
            tools.put("file_generator", new FileGeneratorTool(context));
            tools.put("permission_manager", new PermissionManagerTool(context));
            tools.put("app_operation", new AppOperationTool(context));
            tools.put("translation", new TranslationTool(context));
            tools.put("location", new LocationTool(context));
            tools.put("ai_weather", new AIWeatherManager(context));
            tools.put("app_toolkit", new AppToolkitAITool(context));
            
            Log.i(TAG, "AIToolManager initialized with " + tools.size() + " tools");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing tools: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取所有工具
     * @return 工具列表
     */
    public List<AITool> getTools() {
        return new ArrayList<>(tools.values());
    }
    
    /**
     * 根据名称获取工具
     * @param name 工具名称
     * @return 工具实例，如果不存在则返回null
     */
    public AITool getTool(String name) {
        return tools.get(name);
    }
    
    /**
     * 执行工具
     * @param toolName 工具名称
     * @param parameters 执行参数
     * @return 执行结果
     */
    public AIToolResult executeTool(String toolName, Map<String, Object> parameters) {
        AITool tool = tools.get(toolName);
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
     * @return 工具描述列表
     */
    public List<Map<String, Object>> getToolDescriptions() {
        List<Map<String, Object>> descriptions = new ArrayList<>();
        for (AITool tool : tools.values()) {
            Map<String, Object> description = new HashMap<>();
            description.put("name", tool.getName());
            description.put("description", tool.getDescription());
            description.put("parameters", tool.getParameterDescriptions());
            descriptions.add(description);
        }
        return descriptions;
    }
    
    /**
     * 检查工具是否存在
     * @param toolName 工具名称
     * @return 是否存在
     */
    public boolean hasTool(String toolName) {
        return tools.containsKey(toolName);
    }
    
    /**
     * 释放资源
     */
    public void release() {
        tools.clear();
        Log.i(TAG, "AIToolManager released");
    }
}
