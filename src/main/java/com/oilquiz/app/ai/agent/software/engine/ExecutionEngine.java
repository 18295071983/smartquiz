package com.oilquiz.app.ai.agent.software.engine;

import com.oilquiz.app.ai.agent.software.model.ExecutionResult;
import com.oilquiz.app.ai.agent.software.model.Task;
import com.oilquiz.app.ai.agent.software.model.TaskPlan;
import com.oilquiz.app.ai.agent.software.model.TaskResult;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExecutionEngine - 执行引擎
 * 
 * 管理工具调用和任务执行
 * 硬件层调用: LLM.generate(), Tool.execute()
 */
public class ExecutionEngine {
    
    private static final String TAG = "ExecutionEngine";
    private final AIService aiService;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    public interface ToolCallback {
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, boolean success, String result);
    }
    
    public ExecutionEngine(AIService aiService) {
        this.aiService = aiService;
    }
    
    /**
     * 执行任务计划
     */
    public ExecutionResult execute(TaskPlan taskPlan, ToolCallback callback) {
        ExecutionResult result = new ExecutionResult();
        
        for (Task task : taskPlan.getTasks()) {
            if (isCancelled.get()) {
                result.setError("执行已取消");
                break;
            }
            
            AILogger.i(TAG, "Executing task: " + task.getDescription());
            
            // 执行任务
            TaskResult taskResult = executeTask(task, callback);
            result.addTaskResult(task.getId(), taskResult);
            
            // 检查是否需要停止
            if (taskResult.isError() && task.isCritical()) {
                result.setError("关键任务失败: " + task.getDescription());
                break;
            }
        }
        
        return result;
    }
    
    /**
     * 执行单个任务
     */
    private TaskResult executeTask(Task task, ToolCallback callback) {
        // 检查是否需要工具调用
        if (task.needsTool() && task.getToolName() != null) {
            return executeWithTool(task, callback);
        } else {
            return executeWithLLM(task);
        }
    }
    
    /**
     * 使用工具执行任务
     */
    private TaskResult executeWithTool(Task task, ToolCallback callback) {
        String toolName = task.getToolName();
        Map<String, Object> params = task.getParameters();
        
        AILogger.i(TAG, "Executing tool: " + toolName + " with params: " + params);
        
        // 通知工具调用开始
        if (callback != null) {
            callback.onToolCallStart(toolName, params.toString());
        }
        
        try {
            // 根据工具名称执行相应的工具
            String result = executeToolByName(toolName, params);
            
            // 通知工具调用完成
            if (callback != null) {
                callback.onToolCallComplete(toolName, true, result);
            }
            
            return TaskResult.success(result);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Tool execution failed: " + e.getMessage(), e);
            
            // 通知工具调用失败
            if (callback != null) {
                callback.onToolCallComplete(toolName, false, e.getMessage());
            }
            
            return TaskResult.error("工具执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 使用 LLM 执行任务
     */
    private TaskResult executeWithLLM(Task task) {
        try {
            String prompt = "请完成以下任务: " + task.getDescription();
            
            // 如果聊天上下文活跃，先关闭再使用 generate
            boolean contextWasActive = com.oilquiz.app.ai.jni.LlamaHelper.isChatContextActive();
            if (contextWasActive) {
                AILogger.i(TAG, "Chat context active, destroying before LLM execution");
                try {
                    com.oilquiz.app.ai.jni.LlamaHelper.chatDestroy();
                    Thread.sleep(100);
                } catch (Exception e) {
                    AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                }
            }
            
            // 调用 LLM 执行任务
            String response = com.oilquiz.app.ai.jni.LlamaHelper.generate(prompt, 500, 0.7f);
            
            return TaskResult.success(response);
        } catch (Exception e) {
            return TaskResult.error("LLM 执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 根据工具名称执行工具
     */
    private String executeToolByName(String toolName, Map<String, Object> params) throws Exception {
        switch (toolName.toLowerCase()) {
            case "weather":
                return executeWeatherTool(params);
            case "search":
                return executeSearchTool(params);
            case "calculator":
                return executeCalculatorTool(params);
            case "database":
                return executeDatabaseTool(params);
            case "file":
                return executeFileTool(params);
            case "translate":
                return executeTranslateTool(params);
            default:
                throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
    }
    
    /**
     * 执行天气工具
     */
    private String executeWeatherTool(Map<String, Object> params) {
        String city = (String) params.getOrDefault("city", "北京");
        // 这里应该调用实际的天气工具
        return "天气查询结果: " + city + " 今天晴天，气温25°C";
    }
    
    /**
     * 执行搜索工具
     */
    private String executeSearchTool(Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");
        // 这里应该调用实际的搜索工具
        return "搜索结果: 关于 '" + query + "' 的信息";
    }
    
    /**
     * 执行计算工具
     */
    private String executeCalculatorTool(Map<String, Object> params) {
        String expression = (String) params.getOrDefault("expression", "0");
        // 这里应该调用实际的计算工具
        return "计算结果: " + expression + " = 42";
    }
    
    /**
     * 执行数据库工具
     */
    private String executeDatabaseTool(Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");
        // 这里应该调用实际的数据库工具
        return "数据库查询结果: " + query;
    }
    
    /**
     * 执行文件工具
     */
    private String executeFileTool(Map<String, Object> params) {
        String path = (String) params.getOrDefault("path", "");
        // 这里应该调用实际的文件工具
        return "文件处理结果: " + path;
    }
    
    /**
     * 执行翻译工具
     */
    private String executeTranslateTool(Map<String, Object> params) {
        String text = (String) params.getOrDefault("text", "");
        // 这里应该调用实际的翻译工具
        return "翻译结果: " + text;
    }
    
    /**
     * 取消执行
     */
    public void cancel() {
        isCancelled.set(true);
    }
}
