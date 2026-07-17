# OpenAI 标准工具调用流程 - 本地化适配设计

> 版本: 1.0 | 兼容 OpenAI Function Calling API

## 一、架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户输入                                     │
└─────────────────────────────────┬───────────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Tool Calling Router                              │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  1. 意图识别 → 确定是否需要工具                              │   │
│  │  2. 工具选择 → 从工具注册表匹配                              │   │
│  │  3. 参数提取 → LLM 生成结构化参数                           │   │
│  │  4. 执行调度 → 并行/串行执行                                │   │
│  │  5. 结果整合 → 生成最终回复                                 │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────┬───────────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    本地 LLM 推理层                                   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  llama.cpp / GGUF 模型                                      │   │
│  │  - Function Calling 解析                                    │   │
│  │  - JSON Schema 约束生成                                     │   │
│  │  - 流式输出支持                                             │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

## 二、OpenAI 工具调用标准

### 2.1 工具定义格式 (tools)

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "获取指定城市的天气信息",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "城市名称，如：北京、上海"
            },
            "date": {
              "type": "string",
              "description": "日期，格式：YYYY-MM-DD，默认今天",
              "default": "today"
            }
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

### 2.2 LLM 响应格式 (tool_calls)

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_abc123",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": "{\"city\": \"北京\", \"date\": \"2026-07-17\"}"
            }
          }
        ]
      }
    }
  ]
}
```

### 2.3 工具结果格式 (tool response)

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "{\"temperature\": 25, \"condition\": \"多云\", \"humidity\": 60}"
}
```

## 三、本地化适配

### 3.1 Prompt 模板 (本地模型)

```java
/**
 * 本地模型的 Function Calling Prompt 模板
 * 适配 Qwen2.5、Llama3 等本地模型
 */
public class LocalToolCallPrompt {
    
    /**
     * 构建工具调用 Prompt
     */
    public static String buildToolCallPrompt(String userMessage, List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        
        // 系统提示
        sb.append("你是一个智能助手，可以使用工具来完成任务。\n\n");
        
        // 工具定义
        sb.append("【可用工具】\n");
        for (ToolDefinition tool : tools) {
            sb.append(formatToolDefinition(tool));
        }
        
        // 输出格式约束
        sb.append("\n【输出格式】\n");
        sb.append("如果你需要使用工具，请严格按以下 JSON 格式输出：\n");
        sb.append("```json\n");
        sb.append("{\"tool_calls\": [{\"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}}]}\n");
        sb.append("```\n\n");
        sb.append("如果你不需要工具，直接回答用户问题。\n\n");
        
        // 用户消息
        sb.append("【用户消息】\n");
        sb.append(userMessage);
        sb.append("\n\n【助手回复】\n");
        
        return sb.toString();
    }
    
    /**
     * 格式化工具定义
     */
    private static String formatToolDefinition(ToolDefinition tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具名称: ").append(tool.getName()).append("\n");
        sb.append("功能描述: ").append(tool.getDescription()).append("\n");
        sb.append("参数:\n");
        
        for (ParamDefinition param : tool.getParameters()) {
            sb.append("  - ").append(param.getName());
            sb.append(" (").append(param.getType()).append(")");
            if (param.isRequired()) sb.append(" [必填]");
            sb.append(": ").append(param.getDescription()).append("\n");
        }
        sb.append("\n");
        
        return sb.toString();
    }
}
```

### 3.2 工具定义注册表

```java
/**
 * 工具定义 - 符合 OpenAI 标准
 */
public class ToolDefinition {
    
    private final String name;
    private final String description;
    private final List<ParamDefinition> parameters;
    private final String category;
    private final boolean requiresAuth;
    private final long timeoutMs;
    
    /**
     * 转换为 OpenAI JSON 格式
     */
    public JSONObject toOpenAIFormat() {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        
        JSONObject function = new JSONObject();
        function.put("name", name);
        function.put("description", description);
        
        JSONObject params = new JSONObject();
        params.put("type", "object");
        
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        
        for (ParamDefinition param : parameters) {
            JSONObject paramSchema = new JSONObject();
            paramSchema.put("type", param.getType());
            paramSchema.put("description", param.getDescription());
            if (param.getDefaultValue() != null) {
                paramSchema.put("default", param.getDefaultValue());
            }
            properties.put(param.getName(), paramSchema);
            
            if (param.isRequired()) {
                required.put(param.getName());
            }
        }
        
        params.put("properties", properties);
        params.put("required", required);
        function.put("parameters", params);
        
        tool.put("function", function);
        return tool;
    }
}

/**
 * 参数定义
 */
public class ParamDefinition {
    private final String name;
    private final String type;        // string, number, boolean, array, object
    private final String description;
    private final boolean required;
    private final Object defaultValue;
    private final List<String> enumValues;  // 枚举值
}
```

### 3.3 工具注册表

```java
/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolExecutor> executors = new ConcurrentHashMap<>();
    
    /**
     * 注册工具
     */
    public void register(ToolDefinition definition, ToolExecutor executor) {
        tools.put(definition.getName(), definition);
        executors.put(definition.getName(), executor);
    }
    
    /**
     * 获取所有工具定义 (用于 LLM)
     */
    public List<ToolDefinition> getAllDefinitions() {
        return new ArrayList<>(tools.values());
    }
    
    /**
     * 获取工具执行器
     */
    public ToolExecutor getExecutor(String toolName) {
        return executors.get(toolName);
    }
    
    /**
     * 根据意图匹配工具
     */
    public List<ToolDefinition> matchTools(String intent, String message) {
        return tools.values().stream()
            .filter(tool -> tool.matchesIntent(intent) || tool.matchesKeywords(message))
            .sorted(Comparator.comparingDouble(t -> -t.getRelevanceScore(message)))
            .limit(5)
            .collect(Collectors.toList());
    }
}
```

## 四、Tool Calling 流程

### 4.1 完整流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    Tool Calling Flow                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Step 1: 意图识别                                               │
│  ├─ 输入: "北京天气怎么样？"                                     │
│  ├─ 输出: Intent{type: WEATHER, confidence: 0.95}               │
│  └─ 决策: 需要调用天气工具                                       │
│                                                                 │
│  Step 2: 工具匹配                                               │
│  ├─ 输入: Intent{type: WEATHER}                                 │
│  ├─ 匹配: get_weather 工具                                      │
│  └─ 输出: ToolDefinition{get_weather, parameters: [city, date]} │
│                                                                 │
│  Step 3: 参数提取 (LLM)                                         │
│  ├─ 输入: 用户消息 + 工具定义                                    │
│  ├─ Prompt: "请提取工具参数..."                                  │
│  ├─ LLM 输出: {"city": "北京", "date": "today"}                 │
│  └─ 解析: ToolCall{name: "get_weather", arguments: {...}}       │
│                                                                 │
│  Step 4: 工具执行                                               │
│  ├─ 输入: ToolCall{get_weather, {city: "北京"}}                  │
│  ├─ 执行: ToolExecutor.execute("get_weather", args)             │
│  └─ 输出: {"temperature": 25, "condition": "多云"}              │
│                                                                 │
│  Step 5: 结果整合 (LLM)                                         │
│  ├─ 输入: 用户消息 + 工具结果                                    │
│  ├─ Prompt: "请根据工具结果回答..."                              │
│  └─ 输出: "北京今天多云，气温25°C"                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 核心类实现

```java
/**
 * Tool Calling 处理器
 */
public class ToolCallingHandler {
    
    private final ToolRegistry toolRegistry;
    private final LLMInterface llm;
    private final ToolCallParser parser;
    
    /**
     * 处理用户消息
     */
    public ToolCallResponse handle(String userMessage, List<ChatMessage> history) {
        // Step 1: 意图识别
        Intent intent = recognizeIntent(userMessage);
        
        // Step 2: 检查是否需要工具
        if (!intent.needsTool()) {
            return directResponse(userMessage, history);
        }
        
        // Step 3: 匹配工具
        List<ToolDefinition> matchedTools = toolRegistry.matchTools(
            intent.getType(), userMessage);
        
        if (matchedTools.isEmpty()) {
            return directResponse(userMessage, history);
        }
        
        // Step 4: 调用 LLM 提取参数
        ToolCall toolCall = extractToolCall(userMessage, matchedTools, history);
        
        if (toolCall == null) {
            return directResponse(userMessage, history);
        }
        
        // Step 5: 执行工具
        ToolResult result = executeTool(toolCall);
        
        // Step 6: 整合结果生成回复
        String response = generateResponse(userMessage, toolCall, result, history);
        
        return new ToolCallResponse(response, toolCall, result);
    }
    
    /**
     * 从 LLM 响应中提取工具调用
     */
    private ToolCall extractToolCall(String userMessage, 
                                      List<ToolDefinition> tools,
                                      List<ChatMessage> history) {
        // 构建 Prompt
        String prompt = LocalToolCallPrompt.buildToolCallPrompt(userMessage, tools);
        
        // 调用 LLM
        String response = llm.generate(prompt, 512, 0.1f);
        
        // 解析 JSON 响应
        return parser.parseToolCall(response);
    }
    
    /**
     * 执行工具调用
     */
    private ToolResult executeTool(ToolCall toolCall) {
        ToolExecutor executor = toolRegistry.getExecutor(toolCall.getName());
        if (executor == null) {
            return ToolResult.error("Tool not found: " + toolCall.getName());
        }
        
        try {
            return executor.execute(toolCall.getArguments());
        } catch (Exception e) {
            return ToolResult.error("Execution failed: " + e.getMessage());
        }
    }
}
```

### 4.3 响应解析器

```java
/**
 * 工具调用解析器 - 从 LLM 响应中提取 JSON
 */
public class ToolCallParser {
    
    private static final Pattern JSON_PATTERN = 
        Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    
    private static final Pattern TOOL_CALLS_PATTERN = 
        Pattern.compile("\"tool_calls\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    
    /**
     * 解析工具调用
     */
    public ToolCall parseToolCall(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        
        // 尝试提取 JSON
        String jsonStr = extractJson(response);
        if (jsonStr == null) {
            return null;
        }
        
        try {
            JSONObject json = new JSONObject(jsonStr);
            
            // 检查是否有 tool_calls
            if (!json.has("tool_calls")) {
                return null;
            }
            
            JSONArray toolCalls = json.getJSONArray("tool_calls");
            if (toolCalls.length() == 0) {
                return null;
            }
            
            // 解析第一个工具调用
            JSONObject toolCall = toolCalls.getJSONObject(0);
            String name = toolCall.getString("name");
            JSONObject arguments = toolCall.getJSONObject("arguments");
            
            return new ToolCall(name, arguments);
            
        } catch (JSONException e) {
            return null;
        }
    }
    
    /**
     * 从响应中提取 JSON
     */
    private String extractJson(String response) {
        // 尝试匹配 ```json ... ```
        Matcher matcher = JSON_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // 尝试直接解析整个响应
        try {
            new JSONObject(response);
            return response;
        } catch (JSONException e) {
            return null;
        }
    }
}
```

## 五、本地模型适配

### 5.1 模型能力检测

```java
/**
 * 本地模型能力检测
 */
public class LocalModelCapability {
    
    /**
     * 检测模型是否支持 Function Calling
     */
    public static boolean supportsFunctionCalling(String modelName) {
        // 支持 Function Calling 的模型列表
        Set<String> supportedModels = Set.of(
            "qwen2.5", "qwen3", "qwen3.5",
            "llama3", "llama3.1", "llama3.2",
            "mistral", "mixtral",
            "phi-3", "phi-3.5",
            "gemma2", "gemma3"
        );
        
        String lowerName = modelName.toLowerCase();
        return supportedModels.stream().anyMatch(lowerName::contains);
    }
    
    /**
     * 获取模型推荐的 Prompt 模板
     */
    public static String getRecommendedTemplate(String modelName) {
        if (modelName.contains("qwen")) {
            return "qwen_tool_call";
        } else if (modelName.contains("llama")) {
            return "llama_tool_call";
        } else {
            return "generic_tool_call";
        }
    }
    
    /**
     * 检测模型的 JSON 输出能力
     */
    public static JsonCapability detectJsonCapability(String modelName) {
        if (modelName.contains("qwen")) {
            return JsonCapability.GOOD;  // Qwen JSON 输出较好
        } else if (modelName.contains("llama3")) {
            return JsonCapability.MODERATE;  // Llama3 需要约束
        } else {
            return JsonCapability.BASIC;  // 需要更多约束
        }
    }
    
    public enum JsonCapability {
        GOOD,      // 直接输出 JSON
        MODERATE,  // 需要格式约束
        BASIC      // 需要详细示例
    }
}
```

### 5.2 Prompt 模板适配

```java
/**
 * 本地模型 Prompt 模板管理器
 */
public class LocalPromptManager {
    
    private static final Map<String, String> TEMPLATES = new HashMap<>();
    
    static {
        // Qwen 模板
        TEMPLATES.put("qwen_tool_call", 
            "你是一个智能助手，可以使用工具来完成任务。\n\n" +
            "【可用工具】\n{tools}\n\n" +
            "【输出格式】\n" +
            "如果你需要使用工具，请输出以下 JSON 格式：\n" +
            "```json\n" +
            "{\"tool_calls\": [{\"name\": \"工具名\", \"arguments\": {\"参数\": \"值\"}}]}\n" +
            "```\n" +
            "如果不需要工具，直接回答。\n\n" +
            "用户消息：{message}\n\n" +
            "助手回复：");
        
        // Llama 模板
        TEMPLATES.put("llama_tool_call",
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" +
            "你是一个智能助手。可用工具：\n{tools}\n\n" +
            "如果需要工具，输出：{\"tool_calls\": [{\"name\": \"名称\", \"arguments\": {}}]}\n" +
            "如果不需要，直接回答。<|eot_id|>\n\n" +
            "<|start_header_id|>user<|end_header_id|>\n\n" +
            "{message}<|eot_id|>\n\n" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n");
    }
    
    /**
     * 获取模板
     */
    public static String getTemplate(String modelName) {
        String key = LocalModelCapability.getRecommendedTemplate(modelName);
        return TEMPLATES.getOrDefault(key, TEMPLATES.get("qwen_tool_call"));
    }
}
```

## 六、工具执行器接口

### 6.1 工具执行器

```java
/**
 * 工具执行器接口
 */
public interface ToolExecutor {
    
    /**
     * 执行工具
     */
    ToolResult execute(Map<String, Object> arguments);
    
    /**
     * 验证参数
     */
    ValidationResult validate(Map<String, Object> arguments);
    
    /**
     * 获取工具定义
     */
    ToolDefinition getDefinition();
}

/**
 * 工具执行结果
 */
public class ToolResult {
    private final boolean success;
    private final String content;
    private final Map<String, Object> data;
    private final long executionTimeMs;
    private final String error;
    
    public static ToolResult success(String content) {
        return new ToolResult(true, content, null, 0, null);
    }
    
    public static ToolResult success(Map<String, Object> data) {
        return new ToolResult(true, null, data, 0, null);
    }
    
    public static ToolResult error(String error) {
        return new ToolResult(false, null, null, 0, error);
    }
}
```

### 6.2 预定义工具实现

```java
/**
 * 天气工具
 */
public class WeatherTool implements ToolExecutor {
    
    private final WeatherService weatherService;
    
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("get_weather")
            .description("获取指定城市的天气信息")
            .category("weather")
            .addParameter("city", "string", "城市名称", true)
            .addParameter("date", "string", "日期 YYYY-MM-DD", false, "today")
            .timeoutMs(5000)
            .build();
    }
    
    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String city = (String) arguments.get("city");
        String date = (String) arguments.getOrDefault("date", "today");
        
        try {
            WeatherInfo weather = weatherService.getWeather(city, date);
            return ToolResult.success(Map.of(
                "temperature", weather.getTemperature(),
                "condition", weather.getCondition(),
                "humidity", weather.getHumidity(),
                "wind", weather.getWind()
            ));
        } catch (Exception e) {
            return ToolResult.error("获取天气失败: " + e.getMessage());
        }
    }
}

/**
 * 搜索工具
 */
public class SearchTool implements ToolExecutor {
    
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("search")
            .description("搜索网络信息")
            .category("search")
            .addParameter("query", "string", "搜索关键词", true)
            .addParameter("num_results", "number", "返回结果数量", false, 5)
            .timeoutMs(10000)
            .build();
    }
    
    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        int numResults = ((Number) arguments.getOrDefault("num_results", 5)).intValue();
        
        // 执行搜索
        List<SearchResult> results = searchService.search(query, numResults);
        
        return ToolResult.success(Map.of(
            "results", results.stream()
                .map(r -> Map.of("title", r.getTitle(), "snippet", r.getSnippet()))
                .collect(Collectors.toList())
        ));
    }
}
```

## 七、流式工具调用

### 7.1 流式处理流程

```java
/**
 * 流式工具调用处理器
 */
public class StreamingToolCallHandler {
    
    private final ToolCallingHandler toolHandler;
    private final StreamingCallback callback;
    
    /**
     * 流式处理用户消息
     */
    public void handleStream(String userMessage, List<ChatMessage> history) {
        // Step 1: 意图识别 (非流式)
        Intent intent = toolHandler.recognizeIntent(userMessage);
        
        if (!intent.needsTool()) {
            // 直接流式回复
            toolHandler.streamResponse(userMessage, history, callback);
            return;
        }
        
        // Step 2: 匹配工具
        List<ToolDefinition> tools = toolHandler.matchTools(intent, userMessage);
        
        // Step 3: 流式提取参数
        StreamingToolCall streamCall = new StreamingToolCall();
        
        toolHandler.streamToolCall(userMessage, tools, history, new StreamCallback() {
            @Override
            public void onToken(String token) {
                streamCall.appendToken(token);
                
                // 检查是否完成了 JSON 解析
                if (streamCall.isComplete()) {
                    ToolCall toolCall = streamCall.parse();
                    if (toolCall != null) {
                        // 执行工具并流式返回结果
                        executeAndStream(toolCall, userMessage, history);
                    }
                }
            }
            
            @Override
            public void onComplete() {
                // 处理完成
            }
        });
    }
    
    /**
     * 执行工具并流式返回结果
     */
    private void executeAndStream(ToolCall toolCall, String userMessage, 
                                   List<ChatMessage> history) {
        // 执行工具
        ToolResult result = toolHandler.executeTool(toolCall);
        
        // 流式生成最终回复
        toolHandler.streamResponseWithToolResult(
            userMessage, toolCall, result, history, callback);
    }
}
```

## 八、与现有系统集成

### 8.1 集成点

```java
/**
 * Tool Calling 集成到 AIChatActivity
 */
public class AIChatActivity {
    
    private ToolCallingHandler toolCallingHandler;
    private ToolRegistry toolRegistry;
    
    /**
     * 初始化工具系统
     */
    private void initToolSystem() {
        toolRegistry = new ToolRegistry();
        
        // 注册所有工具
        toolRegistry.register(new WeatherTool().getDefinition(), new WeatherTool());
        toolRegistry.register(new SearchTool().getDefinition(), new SearchTool());
        toolRegistry.register(new CalculatorTool().getDefinition(), new CalculatorTool());
        toolRegistry.register(new DatabaseTool().getDefinition(), new DatabaseTool());
        // ... 更多工具
        
        toolCallingHandler = new ToolCallingHandler(toolRegistry, llm);
    }
    
    /**
     * 处理用户消息
     */
    private void processMessage(String message) {
        // 使用 Tool Calling 处理
        ToolCallResponse response = toolCallingHandler.handle(message, chatHistory);
        
        // 显示结果
        addAIMessage(response.getResponse());
        
        // 显示工具调用信息 (可选)
        if (response.getToolCall() != null) {
            showToolCallInfo(response.getToolCall(), response.getResult());
        }
    }
}
```

### 8.2 与 Agent 系统集成

```
┌─────────────────────────────────────────────────────────────────┐
│                    集成架构                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  用户消息                                                       │
│       ↓                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  AIChatActivity                                         │   │
│  │       ↓                                                 │   │
│  │  ToolCallingHandler                                     │   │
│  │       ↓                                                 │   │
│  │  ┌─────────────┐    ┌─────────────┐                    │   │
│  │  │ 本地 LLM    │    │ 工具注册表   │                    │   │
│  │  │ (参数提取)  │    │ (工具执行)   │                    │   │
│  │  └─────────────┘    └─────────────┘                    │   │
│  │       ↓                                                 │   │
│  │  AgentChatHandler (复杂任务时启用)                       │   │
│  │       ↓                                                 │   │
│  │  UnifiedAgentEngine (深度推理)                           │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## 九、性能优化

### 9.1 工具调用缓存

```java
/**
 * 工具调用缓存
 */
public class ToolCallCache {
    
    private final Cache<String, ToolResult> cache;
    
    public ToolCallCache() {
        this.cache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }
    
    /**
     * 获取缓存的工具结果
     */
    public ToolResult get(String toolName, Map<String, Object> args) {
        String key = buildKey(toolName, args);
        return cache.getIfPresent(key);
    }
    
    /**
     * 缓存工具结果
     */
    public void put(String toolName, Map<String, Object> args, ToolResult result) {
        String key = buildKey(toolName, args);
        cache.put(key, result);
    }
    
    private String buildKey(String toolName, Map<String, Object> args) {
        return toolName + ":" + args.hashCode();
    }
}
```

### 9.2 并行工具调用

```java
/**
 * 并行工具执行器
 */
public class ParallelToolExecutor {
    
    private final ExecutorService executor;
    private final int maxParallel;
    
    /**
     * 并行执行多个工具调用
     */
    public List<ToolResult> executeParallel(List<ToolCall> toolCalls) {
        List<Future<ToolResult>> futures = toolCalls.stream()
            .map(call -> executor.submit(() -> executeTool(call)))
            .collect(Collectors.toList());
        
        return futures.stream()
            .map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return ToolResult.error("Timeout");
                }
            })
            .collect(Collectors.toList());
    }
}
```
