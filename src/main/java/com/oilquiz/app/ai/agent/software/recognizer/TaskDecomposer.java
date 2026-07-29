package com.oilquiz.app.ai.agent.software.recognizer;

import com.oilquiz.app.ai.agent.software.model.ComplexityLevel;
import com.oilquiz.app.ai.agent.software.model.IntentResult;
import com.oilquiz.app.ai.agent.software.model.Task;
import com.oilquiz.app.ai.agent.software.model.TaskPlan;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private final AIToolManager toolManager;
    
    public TaskDecomposer(com.oilquiz.app.ai.service.AIService aiService) {
        this(aiService, null);
    }
    
    public TaskDecomposer(com.oilquiz.app.ai.service.AIService aiService, AIToolManager toolManager) {
        this.aiService = aiService;
        this.toolManager = toolManager;
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
            
            // 调用 LLM 分解任务（generate 独占推理锁，不触碰 chat context，无需 destroy）
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
        
        // 根据意图类型设置对应的工具名称
        if (intent.type != null) {
            task.setToolName(mapIntentToToolName(intent.type));
        }
        
        return task;
    }
    
    /**
     * 将意图类型映射到 AIToolManager 中注册的真实工具名称
     */
    private String mapIntentToToolName(String intentType) {
        switch (intentType.toUpperCase()) {
            case IntentRecognizer.INTENT_WEATHER:
                return "ai_weather";
            case IntentRecognizer.INTENT_SEARCH:
                return "network_search";
            case IntentRecognizer.INTENT_CALCULATOR:
                return "python_calculate";
            case IntentRecognizer.INTENT_TRANSLATE:
                return "translation";
            case IntentRecognizer.INTENT_FILE:
                return "file_reader";
            case IntentRecognizer.INTENT_DATABASE:
                return "database";
            case IntentRecognizer.INTENT_QUIZ:
                return "database";
            case IntentRecognizer.INTENT_APP_OPERATION:
                return "app_operation";
            default:
                return null;
        }
    }
    
    /**
     * 构建任务分解 Prompt - 使用 OpenAI 标准格式
     * 包含角色约束、可用工具、few-shot 示例、依赖关系字段
     */
    private String buildDecompositionPrompt(String userMessage, IntentResult intent,
                                            ComplexityLevel complexity) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是一个任务规划引擎，负责将用户请求分解为可执行的工具调用序列。\n\n");
        sb.append("【角色约束】\n");
        sb.append("- 严格输出 JSON 格式，不要解释、不要多余文字\n");
        sb.append("- 只使用下面列出的工具，不要编造工具名\n");
        sb.append("- 参数必须与工具定义匹配，必填参数不能遗漏\n");
        sb.append("- 如果任务之间有依赖（后一个需要前一个的结果），用 depends_on 指定前置任务的 id\n");
        sb.append("- 如果任务互相独立，depends_on 留空数组，它们会被并行执行\n");
        sb.append("- 后续任务可通过 ${task_id.result} 引用前置任务的完整结果，或 ${task_id.result.字段名} 引用 JSON 字段\n");
        sb.append("- 如果不需要任何工具，直接输出纯文本回答即可\n\n");

        sb.append("【用户消息】\n").append(userMessage).append("\n\n");
        sb.append("【识别意图】").append(intent.type).append("\n");
        sb.append("【复杂度】").append(complexity).append("\n\n");

        sb.append("【可用工具】\n");
        if (toolManager != null) {
            sb.append(toolManager.getToolsForPrompt());
        } else {
            sb.append(getDefaultToolsDescription());
        }

        sb.append("\n【输出格式（严格）】\n");
        sb.append("如果需要使用工具，输出且仅输出以下 JSON（不要加```标记）：\n");
        sb.append("{\"tool_calls\": [{\"id\": \"task1\", \"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}, \"depends_on\": []}]}\n\n");
        sb.append("【示例 1 - 单工具】\n");
        sb.append("用户: 北京今天天气\n");
        sb.append("{\"tool_calls\": [{\"id\": \"task1\", \"name\": \"ai_weather\", \"arguments\": {\"city\": \"北京\", \"action\": \"current\"}, \"depends_on\": []}]}\n\n");
        sb.append("【示例 2 - 依赖引用】\n");
        sb.append("用户: 查一下我在的城市天气然后翻译成英文\n");
        sb.append("{\"tool_calls\": [{\"id\": \"task1\", \"name\": \"ai_weather\", \"arguments\": {\"action\": \"current\"}, \"depends_on\": []}, ");
        sb.append("{\"id\": \"task2\", \"name\": \"translation\", \"arguments\": {\"text\": \"${task1.result}\", \"target_lang\": \"en\"}, \"depends_on\": [\"task1\"]}]}\n\n");
        sb.append("【示例 3 - 并行无依赖】\n");
        sb.append("用户: 北京和上海今天天气\n");
        sb.append("{\"tool_calls\": [{\"id\": \"task1\", \"name\": \"ai_weather\", \"arguments\": {\"city\": \"北京\", \"action\": \"current\"}, \"depends_on\": []}, ");
        sb.append("{\"id\": \"task2\", \"name\": \"ai_weather\", \"arguments\": {\"city\": \"上海\", \"action\": \"current\"}, \"depends_on\": []}]}\n\n");
        sb.append("【示例 4 - 无需工具】\n");
        sb.append("用户: 你好\n");
        sb.append("你好！有什么可以帮你的吗？\n\n");
        sb.append("【输出】\n");

        return sb.toString();
    }
    
    /**
     * 获取默认工具描述（当 toolManager 不可用时）
     */
    private String getDefaultToolsDescription() {
        return "- ai_weather: 天气查询工具，获取指定城市的天气信息\n" +
               "  参数:\n" +
               "    - action (string) 默认: current: 操作类型\n" +
               "    - city (string): 城市名称\n" +
               "\n" +
               "- network_search: 网络搜索工具，搜索网络信息\n" +
               "  参数:\n" +
               "    - query (string) [必填]: 搜索关键词\n" +
               "    - keyword (string): 搜索关键词（别名）\n" +
               "    - limit (integer) 默认: 5: 结果数量限制\n" +
               "\n" +
               "- python_calculate: 使用Python进行数学计算\n" +
               "  参数:\n" +
               "    - expression (string) [必填]: 数学表达式\n" +
               "\n" +
               "- translation: 翻译工具\n" +
               "  参数:\n" +
               "    - text (string) [必填]: 待翻译文本\n" +
               "    - target_lang (string) 默认: zh: 目标语言\n" +
               "\n" +
               "- database: 数据库查询工具\n" +
               "  参数:\n" +
               "    - query (string) [必填]: 查询内容\n" +
               "\n" +
               "- file_reader: 文件读取工具\n" +
               "  参数:\n" +
               "    - file_path (string) [必填]: 文件路径\n" +
               "\n";
    }
    
    /**
     * 解析任务分解响应 - 支持 OpenAI 标准格式
     */
    private List<Task> parseDecompositionResponse(String response) {
        List<Task> tasks = new ArrayList<>();
        
        try {
            // 首先尝试解析 OpenAI 标准格式: {"tool_calls": [...]}
            List<Task> jsonTasks = parseJsonToolCalls(response);
            if (!jsonTasks.isEmpty()) {
                AILogger.i(TAG, "Parsed " + jsonTasks.size() + " tasks from JSON format");
                return jsonTasks;
            }
            
            // 备用：匹配旧格式任务行: "1. [任务描述] - TOOL: 工具名, PARAMS: {...}"
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
                
                parseParameters(task, paramsStr);
                
                tasks.add(task);
            }
            
        } catch (Exception e) {
            AILogger.w(TAG, "Error parsing decomposition response: " + e.getMessage());
        }
        
        return tasks;
    }
    
    /**
     * 解析 OpenAI 标准格式的工具调用
     * 支持 id、name、arguments、depends_on 字段
     */
    private List<Task> parseJsonToolCalls(String response) {
        List<Task> tasks = new ArrayList<>();

        try {
            // 提取 JSON 内容（去除 ```json 标记）
            String jsonStr = extractJsonFromResponse(response);
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                return tasks;
            }

            JSONObject json = new JSONObject(jsonStr);

            if (!json.has("tool_calls")) {
                return tasks;
            }

            JSONArray toolCalls = json.getJSONArray("tool_calls");
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject toolCall = toolCalls.getJSONObject(i);

                String toolName = toolCall.optString("name", "");
                if (toolName.isEmpty()) {
                    continue;
                }

                // 解析 id（LLM 可能给出 task1 等，否则用 UUID）
                String taskId = toolCall.optString("id", "");
                if (taskId.isEmpty()) {
                    taskId = "task_" + (i + 1);
                }

                JSONObject arguments = toolCall.optJSONObject("arguments");
                Map<String, Object> params = new HashMap<>();
                if (arguments != null) {
                    Iterator<String> keys = arguments.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        params.put(key, arguments.get(key));
                    }
                }

                // 使用 LLM 给出的 id 作为 Task id，便于 depends_on 引用
                Task task = new Task(taskId);
                task.setDescription("调用工具: " + toolName);
                task.setToolName(toolName);
                task.setNeedsTool(true);

                // 解析 depends_on 数组
                JSONArray deps = toolCall.optJSONArray("depends_on");
                if (deps != null) {
                    for (int j = 0; j < deps.length(); j++) {
                        String depId = deps.optString(j);
                        if (!depId.isEmpty()) {
                            task.addDependency(depId);
                        }
                    }
                }

                // 设置参数
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    task.addParameter(entry.getKey(), entry.getValue());
                }

                tasks.add(task);
            }

        } catch (JSONException e) {
            AILogger.w(TAG, "Error parsing JSON tool calls: " + e.getMessage());
        }

        return tasks;
    }
    
    /**
     * 从响应中提取 JSON 内容
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        
        // 尝试匹配 ```json ... ```
        Pattern jsonPattern = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
        Matcher matcher = jsonPattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // 尝试直接解析整个响应
        int startIndex = response.indexOf("{");
        int endIndex = response.lastIndexOf("}");
        if (startIndex >= 0 && endIndex > startIndex) {
            return response.substring(startIndex, endIndex + 1);
        }
        
        return null;
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
