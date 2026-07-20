package com.oilquiz.app.ai.agent.software.engine;

import android.content.Context;

import com.oilquiz.app.ai.agent.software.model.ExecutionResult;
import com.oilquiz.app.ai.agent.software.model.Task;
import com.oilquiz.app.ai.agent.software.model.TaskPlan;
import com.oilquiz.app.ai.agent.software.model.TaskResult;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
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
    private final AIToolManager toolManager;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    public interface ToolCallback {
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, boolean success, String result);
    }
    
    public ExecutionEngine(Context context, AIService aiService) {
        this.aiService = aiService;
        this.toolManager = AIToolManager.getInstance(context);
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
     * 使用 AIToolManager 中注册的真实工具名称
     */
    private String executeToolByName(String toolName, Map<String, Object> params) throws Exception {
        AILogger.i(TAG, "Executing tool: " + toolName + " with params: " + params);
        
        AIToolResult result = toolManager.executeTool(toolName, params);
        
        if (result != null && result.isSuccess()) {
            Object resultObj = result.getResult();
            String resultStr = resultObj != null ? resultObj.toString() : null;
            AILogger.i(TAG, "Tool result: " + (resultStr != null ? resultStr.substring(0, Math.min(100, resultStr.length())) : "null"));
            return resultStr != null ? resultStr : "工具执行成功但返回空结果";
        } else {
            String errorMsg = result != null ? result.getErrorMessage() : "工具执行失败";
            AILogger.e(TAG, "Tool execution failed: " + errorMsg);
            throw new Exception(errorMsg);
        }
    }
    
    /**
     * 取消执行
     */
    public void cancel() {
        isCancelled.set(true);
    }
}
