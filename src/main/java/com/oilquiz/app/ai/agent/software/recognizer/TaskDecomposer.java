package com.oilquiz.app.ai.agent.software.recognizer;

import com.oilquiz.app.ai.agent.software.model.ComplexityLevel;
import com.oilquiz.app.ai.agent.software.model.IntentResult;
import com.oilquiz.app.ai.agent.software.model.Task;
import com.oilquiz.app.ai.agent.software.model.TaskPlan;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TaskDecomposer - 任务分解模块
 * 
 * 使用 LLM 将复杂任务分解为子任务
 * 硬件层调用: LLM.generate()
 */
public class TaskDecomposer {
    
    private static final String TAG = "TaskDecomposer";
    private final com.oilquiz.app.ai.service.AIService aiService;
    
    public TaskDecomposer(com.oilquiz.app.ai.service.AIService aiService) {
        this.aiService = aiService;
    }
    
    /**
     * 分解任务
     * 使用 LLM 将复杂任务分解为子任务
     */
    public TaskPlan decompose(String userMessage, IntentResult intent, ComplexityLevel complexity) {
        TaskPlan plan = new TaskPlan(userMessage, intent);
        
        // 简单任务不需要分解
        if (complexity == ComplexityLevel.SIMPLE) {
            plan.addTask(createSimpleTask(userMessage, intent));
            return plan;
        }
        
        try {
            // 检查模型是否已初始化
            if (!LlamaHelper.isModelInitialized()) {
                AILogger.w(TAG, "Model not initialized, using simple task");
                plan.addTask(createSimpleTask(userMessage, intent));
                return plan;
            }
            
            // 构建任务分解 Prompt
            String prompt = buildDecompositionPrompt(userMessage, intent, complexity);
            
            // 如果聊天上下文活跃，先关闭再使用 generate
            boolean contextWasActive = LlamaHelper.isChatContextActive();
            if (contextWasActive) {
                AILogger.i(TAG, "Chat context active, destroying before task decomposition");
                try {
                    LlamaHelper.chatDestroy();
                    Thread.sleep(100);
                } catch (Exception e) {
                    AILogger.w(TAG, "Error destroying chat context: " + e.getMessage());
                }
            }
            
            // 调用 LLM 分解任务
            String response = LlamaHelper.generate(prompt, 500, 0.3f);
            
            if (response == null || response.trim().isEmpty()) {
                AILogger.w(TAG, "LLM returned empty response for task decomposition");
                plan.addTask(createSimpleTask(userMessage, intent));
                return plan;
            }
            
            List<Task> tasks = parseDecompositionResponse(response);
            
            if (tasks.isEmpty()) {
                plan.addTask(createSimpleTask(userMessage, intent));
            } else {
                for (Task task : tasks) {
                    plan.addTask(task);
                }
            }
            
        } catch (Exception e) {
            AILogger.e(TAG, "Error in task decomposition: " + e.getMessage(), e);
            plan.addTask(createSimpleTask(userMessage, intent));
        }
        
        return plan;
    }
    
    /**
     * 创建简单任务
     */
    private Task createSimpleTask(String userMessage, IntentResult intent) {
        Task task = new Task(UUID.randomUUID().toString());
        task.setDescription(userMessage);
        task.setIntentType(intent.type);
        task.setEntity(intent.entity);
        task.setNeedsTool(intent.type != null && !intent.type.equals("CHAT"));
        return task;
    }
    
    /**
     * 构建任务分解 Prompt
     */
    private String buildDecompositionPrompt(String userMessage, IntentResult intent, 
                                            ComplexityLevel complexity) {
        return "你是一个任务分解助手。\n\n" +
               "【用户消息】\n" + userMessage + "\n\n" +
               "【识别意图】\n" + intent.type + "\n" +
               "【复杂度】\n" + complexity + "\n\n" +
               "【可用工具】\n" +
               "- weather: 查询天气 (参数: city)\n" +
               "- search: 搜索信息 (参数: query)\n" +
               "- calculator: 数学计算 (参数: expression)\n" +
               "- database: 数据库查询 (参数: query)\n" +
               "- file: 文件处理 (参数: path, action)\n" +
               "- translate: 翻译 (参数: text, target_lang)\n\n" +
               "【输出格式】\n" +
               "TASKS:\n" +
               "1. [任务描述] - TOOL: 工具名, PARAMS: {\"参数\": \"值\"}\n" +
               "2. [任务描述] - TOOL: 工具名, PARAMS: {\"参数\": \"值\"}\n" +
               "...\n\n" +
               "【输出】\n";
    }
    
    /**
     * 解析任务分解响应
     */
    private List<Task> parseDecompositionResponse(String response) {
        List<Task> tasks = new ArrayList<>();
        
        try {
            // 匹配任务行: "1. [任务描述] - TOOL: 工具名, PARAMS: {...}"
            Pattern taskPattern = Pattern.compile(
                "\\d+\\.\\s*\\[(.+?)\\]\\s*-\\s*TOOL:\\s*(\\w+)\\s*,\\s*PARAMS:\\s*\\{(.+?)\\}",
                Pattern.CASE_INSENSITIVE
            );
            
            Matcher matcher = taskPattern.matcher(response);
            while (matcher.find()) {
                String description = matcher.group(1).trim();
                String toolName = matcher.group(2).trim();
                String paramsStr = matcher.group(3).trim();
                
                Task task = new Task(UUID.randomUUID().toString());
                task.setDescription(description);
                task.setToolName(toolName);
                task.setNeedsTool(true);
                
                // 简单解析参数
                parseParameters(task, paramsStr);
                
                tasks.add(task);
            }
            
        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing decomposition response: " + e.getMessage());
        }
        
        return tasks;
    }
    
    /**
     * 解析参数
     */
    private void parseParameters(Task task, String paramsStr) {
        try {
            // 简单的参数解析: "key1": "value1", "key2": "value2"
            Pattern paramPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*?)\"");
            Matcher matcher = paramPattern.matcher(paramsStr);
            
            while (matcher.find()) {
                String key = matcher.group(1);
                String value = matcher.group(2);
                task.addParameter(key, value);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing parameters: " + e.getMessage());
        }
    }
}
